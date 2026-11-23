import { randomUUID } from "node:crypto";
import { setTimeout as sleep } from "node:timers/promises";
import type { FastifyInstance } from "fastify";
import type { AppConfig } from "../config";
import type { Database } from "../db";
import { HttpError, requireAuth, type EnvelopeRow, type UserRow } from "../http";
import type { TokenService } from "../security/tokens";
import {
  ackSchema,
  parseWith,
  receiptAckSchema,
  reportSchema,
  sendEnvelopeSchema,
} from "../validation";
import type { WsHub, WsEnvelopePayload } from "../ws/hub";
import { loadRemoteConfig } from "../admin/remote-config";

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

type PendingEnvelopeRow = EnvelopeRow & {
  sender_nex_id: string;
  sender_signal_device_id: number;
  recipient_signal_device_id: number;
};

/**
 * The one query that defines "waiting for this device". Both the ordinary
 * pending feed and the long-poll feed use it so their ordering and expiry
 * rules can never drift apart.
 */
async function fetchPendingForDevice(
  db: Database,
  deviceId: string,
): Promise<WsEnvelopePayload[]> {
  const rows = await db.query<PendingEnvelopeRow>(
    `SELECT e.*, u.nex_id AS sender_nex_id, d.signal_device_id AS sender_signal_device_id,
            rd.signal_device_id AS recipient_signal_device_id
     FROM envelopes e
     JOIN users u ON u.id = e.sender_user_id
     JOIN devices d ON d.id = e.sender_device_id
     JOIN devices rd ON rd.id = e.recipient_device_id
     WHERE e.recipient_device_id = $1
       AND e.delivered_at IS NULL
       AND e.expires_at > now()
     ORDER BY e.priority DESC, e.created_at ASC
     LIMIT 500`,
    [deviceId],
  );
  return rows.map((row) =>
    envelopePayload(
      row,
      row.sender_nex_id,
      row.sender_signal_device_id,
      row.recipient_signal_device_id,
    ),
  );
}

export async function registerMessageRoutes(app: FastifyInstance, deps: Deps): Promise<void> {
  const { db, tokens, config, hub } = deps;

  app.get("/v1/messages/pending", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    return reply.send({ messages: await fetchPendingForDevice(db, auth.device.id) });
  });

  /**
   * Near-instant delivery without a shared WebSocket hub. A serverless relay
   * may hold the recipient's socket on a different function instance than the
   * one that accepted the envelope, so the in-memory push can miss. This
   * endpoint holds the request open and returns the moment ciphertext exists,
   * instead of making the client wait for its next 5-second poll.
   *
   * The server still cannot read the payload: it only observes that an opaque
   * envelope is waiting for this device.
   */
  app.get("/v1/messages/wait", {
    config: { rateLimit: { max: 120, timeWindow: "1 minute" } },
  }, async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const query = request.query as { timeoutMs?: string | number };
    const requested = Number(query.timeoutMs ?? 20_000);
    const timeoutMs = Number.isFinite(requested)
      ? Math.min(Math.max(Math.trunc(requested), 1_000), 25_000)
      : 20_000;
    const deadline = Date.now() + timeoutMs;
    let disconnected = false;
    reply.raw.on("close", () => {
      disconnected = true;
    });
    while (!disconnected) {
      const messages = await fetchPendingForDevice(db, auth.device.id);
      if (messages.length > 0) return reply.send({ messages });
      if (Date.now() >= deadline) break;
      // Short enough that a call offer appears effectively immediately, long
      // enough that a mostly idle device does not hammer PostgreSQL.
      await sleep(500);
    }
    if (disconnected) return;
    return reply.send({ messages: [] });
  });

  app.post("/v1/messages", {
    config: { rateLimit: { max: 120, timeWindow: "1 minute" } },
  }, async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const body = parseWith(sendEnvelopeSchema, request.body);
    const accepted: Array<Record<string, unknown>> = [];
    const skipped: Array<Record<string, unknown>> = [];
    const pushed: Array<{ deviceId: string; payload: WsEnvelopePayload }> = [];
    let newEnvelopes = 0;
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
        skipped.push({
          clientId: message.clientId,
          reason: "RECIPIENT_NOT_FOUND",
          recipientSignalDeviceId: message.recipientSignalDeviceId,
        });
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
        skipped.push({
          clientId: message.clientId,
          reason: "DEVICE_NOT_ACTIVE",
          recipientSignalDeviceId: message.recipientSignalDeviceId,
        });
        continue;
      }

      if (message.groupId) {
        let membership = groupMembershipCache.get(message.groupId);
        if (!membership) {
          const group = await db.one<{ id: string }>("SELECT id FROM groups WHERE id = $1", [message.groupId]);
          if (!group) {
            skipped.push({
              clientId: message.clientId,
              reason: "GROUP_NOT_FOUND",
              recipientSignalDeviceId: message.recipientSignalDeviceId,
            });
            continue;
          }
          const senderIsMember = await isGroupMember(db, message.groupId, auth.user.id);
          membership = { sender: senderIsMember };
          groupMembershipCache.set(message.groupId, membership);
        }
        if (!membership.sender) {
          skipped.push({
            clientId: message.clientId,
            reason: "NOT_A_GROUP_MEMBER",
            recipientSignalDeviceId: message.recipientSignalDeviceId,
          });
          continue;
        }
        const recipientIsMember = await isGroupMember(db, message.groupId, recipient.id);
        if (!recipientIsMember) {
          skipped.push({
            clientId: message.clientId,
            reason: "RECIPIENT_NOT_IN_GROUP",
            recipientSignalDeviceId: message.recipientSignalDeviceId,
          });
          continue;
        }
      }

      if (message.ephemeral && !hub.isOnline(device.id)) {
        skipped.push({
          clientId: message.clientId,
          reason: "EPHEMERAL_TARGET_OFFLINE",
          recipientSignalDeviceId: message.recipientSignalDeviceId,
        });
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
           group_id, client_id, cipher_type, ciphertext, ephemeral, priority, expires_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
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
          message.priority,
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
        skipped.push({
          clientId: message.clientId,
          reason: "ENQUEUE_FAILED",
          recipientSignalDeviceId: message.recipientSignalDeviceId,
        });
        continue;
      }

      accepted.push({
        clientId: acceptedEnvelope.client_id,
        recipientSignalDeviceId: device.signal_device_id,
        recipientDeviceId: acceptedEnvelope.recipient_device_id,
        envelopeId: acceptedEnvelope.id,
        createdAt: new Date(acceptedEnvelope.created_at).toISOString(),
        deliveredAt: acceptedEnvelope.delivered_at
          ? new Date(acceptedEnvelope.delivered_at).toISOString()
          : null,
      });
      if (insertedRows[0]) {
        newEnvelopes += 1;
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

    if (newEnvelopes > 0 || skipped.length > 0) {
      await db
        .query(
          `INSERT INTO message_stats_daily (day, sent, skipped)
           VALUES (CURRENT_DATE, $1, $2)
           ON CONFLICT (day) DO UPDATE
             SET sent = message_stats_daily.sent + EXCLUDED.sent,
                 skipped = message_stats_daily.skipped + EXCLUDED.skipped`,
          [newEnvelopes, skipped.length],
        )
        .catch(() => undefined);
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
    // Keep the envelope row after delivery. It is the durable idempotency
    // record for retries and it expires naturally through the TTL cleanup.
    const rows = await db.transaction(async (client) => {
      const updated = await client.query<EnvelopeRow>(
        `UPDATE envelopes SET delivered_at = now()
         WHERE recipient_device_id = $1 AND delivered_at IS NULL
           AND expires_at > now() AND id IN (${placeholders})
         RETURNING *`,
        [auth.device.id, ...body.envelopeIds],
      );
      for (const row of updated.rows) {
        await client.query(
          `INSERT INTO delivery_receipts
             (id, sender_user_id, sender_device_id, recipient_user_id,
              recipient_device_id, envelope_id, client_id, delivered_at)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
           ON CONFLICT (sender_device_id, recipient_device_id, client_id) DO NOTHING`,
          [
            randomUUID(),
            row.sender_user_id,
            row.sender_device_id,
            row.recipient_user_id,
            row.recipient_device_id,
            row.id,
            row.client_id,
            row.delivered_at ?? new Date(),
          ],
        );
      }
      return updated.rows;
    });

    if (rows.length > 0) {
      await db
        .query(
          `INSERT INTO message_stats_daily (day, delivered)
           VALUES (CURRENT_DATE, $1)
           ON CONFLICT (day) DO UPDATE
             SET delivered = message_stats_daily.delivered + EXCLUDED.delivered`,
          [rows.length],
        )
        .catch(() => undefined);
    }

    for (const row of rows) {
      hub.sendToUser(row.sender_user_id, {
        type: "delivered",
        clientId: row.client_id,
        envelopeId: row.id,
        recipientDeviceId: row.recipient_device_id,
        deliveredAt: new Date(row.delivered_at ?? Date.now()).toISOString(),
      });
    }

    return reply.send({ acknowledged: rows.length });
  });

  app.get("/v1/messages/receipts", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const rows = await db.query<{
      id: string;
      client_id: string;
      envelope_id: string;
      recipient_device_id: string;
      delivered_at: Date | string;
    }>(
      `SELECT id, client_id, envelope_id, recipient_device_id, delivered_at
       FROM delivery_receipts
       WHERE sender_device_id = $1
       ORDER BY delivered_at ASC
       LIMIT 500`,
      [auth.device.id],
    );
    return reply.send({
      receipts: rows.map((row) => ({
        id: row.id,
        clientId: row.client_id,
        envelopeId: row.envelope_id,
        recipientDeviceId: row.recipient_device_id,
        deliveredAt: new Date(row.delivered_at).toISOString(),
      })),
    });
  });

  app.post("/v1/messages/receipts/ack", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const body = parseWith(receiptAckSchema, request.body);
    const placeholders = body.receiptIds.map((_, index) => `$${index + 2}`).join(",");
    const rows = await db.query<{ id: string }>(
      `DELETE FROM delivery_receipts
       WHERE sender_device_id = $1 AND id IN (${placeholders})
       RETURNING id`,
      [auth.device.id, ...body.receiptIds],
    );
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
    const turnConfigured = config.iceServers.some((server) => {
      const urls = (server as { urls?: unknown }).urls;
      const list = Array.isArray(urls) ? urls : [urls];
      return list.some(
        (url) => typeof url === "string" && /^turns?:/i.test(url),
      );
    });
    const remote = await loadRemoteConfig(db);
    const announcement = await db
      .one<Record<string, unknown>>(
        `SELECT title, body, severity, starts_at, ends_at FROM announcements
         WHERE active = TRUE AND starts_at <= now()
           AND (ends_at IS NULL OR ends_at > now())
         ORDER BY CASE severity WHEN 'critical' THEN 0 WHEN 'warning' THEN 1 ELSE 2 END,
                  created_at DESC
         LIMIT 1`,
      )
      .catch(() => null);
    return reply
      .header("cache-control", "public, max-age=30")
      .send({
        iceServers: config.iceServers,
        limits: {
          maxEnvelopeBytes: config.maxEnvelopeBytes,
          envelopeTtlDays: config.envelopeTtlDays,
          maxGroupMembers: 100,
        },
        remote: {
          version: remote.version,
          callsEnabled: remote.callsEnabled,
          videoCallsEnabled: remote.videoCallsEnabled,
          maxCallDurationSeconds: remote.maxCallDurationSeconds,
          maintenanceMode: remote.maintenanceMode,
          maintenanceMessage: remote.maintenanceMessage,
          minAppVersion: remote.minAppVersion,
        },
        announcement: announcement
          ? {
              title: announcement.title,
              body: announcement.body,
              severity: announcement.severity,
              startsAt: announcement.starts_at,
              endsAt: announcement.ends_at ?? null,
            }
          : null,
        serverTime: new Date().toISOString(),
        features: {
          e2ee: "libsignal",
          groupEncryption: "pairwise-libsignal",
          nearbyRelay: "unavailable",
          audioCalls: remote.callsEnabled ? "webrtc-audio-1to1" : "disabled",
          videoCalls: remote.videoCallsEnabled ? "webrtc-video-1to1" : "disabled",
          pushNotifications: false,
          turnConfigured,
        },
        // Kept for clients that predate the structured remote object.
        turnConfigured,
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
    void db.query(
      "DELETE FROM delivery_receipts WHERE delivered_at < now() - interval '30 days'",
    ).catch((error: unknown) => {
      app.log.error({ err: error }, "receipt cleanup failed");
    });
  }, 10 * 60 * 1000);
  cleanup.unref();
  app.addHook("onClose", async () => clearInterval(cleanup));
}
