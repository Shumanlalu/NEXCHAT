import { randomUUID } from "node:crypto";
import type { FastifyInstance } from "fastify";
import type { AppConfig } from "../config";
import type { Database } from "../db";
import { HttpError, requireAuth, type EnvelopeRow, type UserRow } from "../http";
import type { TokenService } from "../security/tokens";
import {
  ackSchema,
  parseWith,
  reportSchema,
  sendEnvelopeSchema,
} from "../validation";
import type { WsHub, WsEnvelopePayload } from "../ws/hub";

interface Deps {
  db: Database;
  tokens: TokenService;
  config: AppConfig;
  hub: WsHub;
}

export function envelopePayload(
  envelope: EnvelopeRow,
  senderNexId: string,
  senderSignalDeviceId: number,
  recipientSignalDeviceId: number,
): WsEnvelopePayload {
  return {
    id: envelope.id,
    senderNexId,
    senderDeviceId: envelope.sender_device_id,
    senderSignalDeviceId,
    recipientDeviceId: envelope.recipient_device_id,
    recipientSignalDeviceId,
    groupId: envelope.group_id,
    clientId: envelope.client_id,
    cipherType: envelope.cipher_type,
    ciphertext: envelope.ciphertext,
    createdAt: new Date(envelope.created_at).toISOString(),
    expiresAt: new Date(envelope.expires_at).toISOString(),
    ephemeral: envelope.ephemeral,
  };
}

async function isGroupMember(
  db: Database,
  groupId: string,
  userId: string,
): Promise<boolean> {
  const row = await db.one<{ group_id: string }>(
    `SELECT group_id FROM group_members
     WHERE group_id = $1 AND user_id = $2 AND removed_at IS NULL`,
    [groupId, userId],
  );
  return !!row;
}

export async function registerMessageRoutes(app: FastifyInstance, deps: Deps): Promise<void> {
  const { db, tokens, config, hub } = deps;

  app.get("/v1/messages/pending", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const rows = await db.query<EnvelopeRow & {
      sender_nex_id: string;
      sender_signal_device_id: number;
      recipient_signal_device_id: number;
    }>(
      `SELECT e.*, u.nex_id AS sender_nex_id, d.signal_device_id AS sender_signal_device_id,
              rd.signal_device_id AS recipient_signal_device_id
       FROM envelopes e
       JOIN users u ON u.id = e.sender_user_id
       JOIN devices d ON d.id = e.sender_device_id
       JOIN devices rd ON rd.id = e.recipient_device_id
       WHERE e.recipient_device_id = $1
         AND e.delivered_at IS NULL
         AND e.expires_at > now()
       ORDER BY e.created_at ASC
       LIMIT 500`,
      [auth.device.id],
    );
    return reply.send({
      messages: rows.map((row) =>
        envelopePayload(
          row,
          row.sender_nex_id,
          row.sender_signal_device_id,
          row.recipient_signal_device_id,
        ),
      ),
    });
  });

  app.post("/v1/messages", {
    config: { rateLimit: { max: 120, timeWindow: "1 minute" } },
  }, async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const body = parseWith(sendEnvelopeSchema, request.body);
    const accepted: Array<Record<string, unknown>> = [];
    const skipped: Array<Record<string, unknown>> = [];
    const pushed: Array<{ deviceId: string; payload: WsEnvelopePayload }> = [];
    const groupMembershipCache = new Map<string, { sender: boolean }>();
    const userCache = new Map<string, UserRow | null>();
    const devicesCache = new Map<string, { id: string; user_id: string; signal_device_id: number; revoked_at: Date | string | null } | null>();

    for (const message of body.messages) {
      let recipient = userCache.get(message.recipientNexId);
      if (recipient === undefined) {
        recipient = await db.one<UserRow>(
          "SELECT * FROM users WHERE nex_id = $1 AND deleted_at IS NULL",
          [message.recipientNexId],
        );
        userCache.set(message.recipientNexId, recipient);
      }
      if (!recipient) {
        skipped.push({ clientId: message.clientId, reason: "RECIPIENT_NOT_FOUND" });
        continue;
      }

      const deviceKey = `${recipient.id}:${message.recipientSignalDeviceId}`;
      let device = devicesCache.get(deviceKey);
      if (device === undefined) {
        device = await db.one<{
          id: string;
          user_id: string;
          signal_device_id: number;
          revoked_at: Date | string | null;
        }>(
          "SELECT id, user_id, signal_device_id, revoked_at FROM devices WHERE user_id = $1 AND signal_device_id = $2",
          [recipient.id, message.recipientSignalDeviceId],
        );
        devicesCache.set(deviceKey, device);
      }
      if (!device || device.revoked_at) {
        skipped.push({ clientId: message.clientId, reason: "DEVICE_NOT_ACTIVE" });
        continue;
      }

      if (message.groupId) {
        let membership = groupMembershipCache.get(message.groupId);
        if (!membership) {
          const group = await db.one<{ id: string }>("SELECT id FROM groups WHERE id = $1", [message.groupId]);
          if (!group) {
            skipped.push({ clientId: message.clientId, reason: "GROUP_NOT_FOUND" });
            continue;
          }
          const senderIsMember = await isGroupMember(db, message.groupId, auth.user.id);
          membership = { sender: senderIsMember };
          groupMembershipCache.set(message.groupId, membership);
        }
        if (!membership.sender) {
          skipped.push({ clientId: message.clientId, reason: "NOT_A_GROUP_MEMBER" });
          continue;
        }
        const recipientIsMember = await isGroupMember(db, message.groupId, recipient.id);
        if (!recipientIsMember) {
          skipped.push({ clientId: message.clientId, reason: "RECIPIENT_NOT_IN_GROUP" });
          continue;
        }
      }

      if (message.ephemeral && !hub.isOnline(device.id)) {
        skipped.push({ clientId: message.clientId, reason: "EPHEMERAL_TARGET_OFFLINE" });
        continue;
      }

      const requestedExpirySeconds = message.expiresInSeconds ?? 7 * 24 * 3600;
      const expiry = new Date(
        Math.min(
          Date.now() + requestedExpirySeconds * 1000,
          Date.now() + config.envelopeTtlDays * 24 * 3600 * 1000,
        ),
      );
      const envelopeId = randomUUID();
      const insertedRows = await db.query<EnvelopeRow>(
        `INSERT INTO envelopes
          (id, sender_user_id, sender_device_id, recipient_user_id, recipient_device_id,
           group_id, client_id, cipher_type, ciphertext, ephemeral, expires_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
         ON CONFLICT (sender_device_id, recipient_device_id, client_id) DO NOTHING
         RETURNING *`,
        [
          envelopeId,
          auth.user.id,
          auth.device.id,
          recipient.id,
          device.id,
          message.groupId ?? null,
          message.clientId,
          message.cipherType,
          message.ciphertext,
          message.ephemeral,
          expiry,
        ],
      );

      let acceptedEnvelope = insertedRows[0];
      if (!acceptedEnvelope) {
        acceptedEnvelope = (await db.one<EnvelopeRow>(
          `SELECT * FROM envelopes
           WHERE sender_device_id = $1 AND recipient_device_id = $2 AND client_id = $3`,
          [auth.device.id, device.id, message.clientId],
        )) ?? undefined;
      }
      if (!acceptedEnvelope) {
        skipped.push({ clientId: message.clientId, reason: "ENQUEUE_FAILED" });
        continue;
      }

      accepted.push({
        clientId: acceptedEnvelope.client_id,
        recipientSignalDeviceId: device.signal_device_id,
        recipientDeviceId: acceptedEnvelope.recipient_device_id,
        envelopeId: acceptedEnvelope.id,
        createdAt: new Date(acceptedEnvelope.created_at).toISOString(),
      });
      if (insertedRows[0]) {
        pushed.push({
          deviceId: acceptedEnvelope.recipient_device_id,
          payload: envelopePayload(
            acceptedEnvelope,
            auth.user.nex_id,
            auth.device.signal_device_id,
            device.signal_device_id,
          ),
        });
      }
    }

    // Push only after all database writes have completed. A failure in a socket does
    // not roll back the durable offline queue.
    for (const item of pushed) {
      hub.sendToDevice(item.deviceId, { type: "message", envelope: item.payload });
    }

    return reply.code(202).send({ accepted, skipped });
  });

  app.post("/v1/messages/ack", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const body = parseWith(ackSchema, request.body);
    const placeholders = body.envelopeIds.map((_, index) => `$${index + 2}`).join(",");
    const rows = await db.query<EnvelopeRow>(
      `UPDATE envelopes SET delivered_at = now()
       WHERE recipient_device_id = $1 AND delivered_at IS NULL
         AND expires_at > now() AND id IN (${placeholders})
       RETURNING *`,
      [auth.device.id, ...body.envelopeIds],
    );

    for (const row of rows) {
      hub.sendToUser(row.sender_user_id, {
        type: "delivered",
        clientId: row.client_id,
        envelopeId: row.id,
        recipientDeviceId: row.recipient_device_id,
        deliveredAt: new Date().toISOString(),
      });
    }

    if (rows.length > 0) {
      const ids = rows.map((row) => row.id);
      const deletePlaceholders = ids.map((_, index) => `$${index + 1}`).join(",");
      await db.query(`DELETE FROM envelopes WHERE id IN (${deletePlaceholders})`, ids);
    }

    return reply.send({ acknowledged: rows.length });
  });

  app.post("/v1/reports", {
    config: { rateLimit: { max: 5, timeWindow: "10 minutes" } },
  }, async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const body = parseWith(reportSchema, request.body);
    await db.query(
      `INSERT INTO abuse_reports (id, reporter_user_id, target_nex_id, reason)
       VALUES ($1,$2,$3,$4)`,
      [randomUUID(), auth.user.id, body.targetNexId, body.reason],
    );
    return reply.send({ ok: true });
  });

  app.get("/v1/config", async (_request, reply) => {
    return reply.send({
      iceServers: config.iceServers,
      limits: {
        maxEnvelopeBytes: config.maxEnvelopeBytes,
        envelopeTtlDays: config.envelopeTtlDays,
        maxGroupMembers: 100,
      },
      features: {
        e2ee: "libsignal",
        groupEncryption: "pairwise-libsignal",
        nearbyRelay: "client-only",
        audioCalls: "webrtc",
        pushNotifications: false,
      },
    });
  });

  app.get("/healthz", async (_request, reply) => {
    try {
      await db.one("SELECT 1 AS ok");
      return reply.send({ status: "ok" });
    } catch {
      return reply.code(503).send({ status: "unavailable" });
    }
  });

  // Expired ciphertext should not linger even when no client reconnects.
  const cleanup = setInterval(() => {
    void db.query("DELETE FROM envelopes WHERE expires_at <= now()").catch((error: unknown) => {
      app.log.error({ err: error }, "envelope cleanup failed");
    });
  }, 10 * 60 * 1000);
  cleanup.unref();
  app.addHook("onClose", async () => clearInterval(cleanup));
}
