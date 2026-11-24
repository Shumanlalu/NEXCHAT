import { randomUUID } from "node:crypto";
import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { secureEqual } from "../admin/security";
import { loadRemoteConfig } from "../admin/remote-config";
import type { AppConfig } from "../config";
import type { Database } from "../db";
import { HttpError, requireAuth } from "../http";
import type { TokenService } from "../security/tokens";
import { parseWith } from "../validation";

interface Deps {
  db: Database;
  tokens: TokenService;
  config: AppConfig;
}

const heartbeatSchema = z.object({
  appVersion: z.string().trim().max(32).optional(),
  platform: z.string().trim().max(32).optional(),
});

const eventSchema = z.object({
  type: z.string().trim().min(1).max(64),
  appVersion: z.string().trim().max(32).optional(),
  platform: z.string().trim().max(32).optional(),
  metadata: z.record(z.unknown()).optional(),
});

const eventsSchema = z.object({
  events: z.array(eventSchema).min(1).max(25),
});

const crashSchema = z.object({
  message: z.string().trim().min(1).max(4000),
  stack: z.string().max(16_000).optional(),
  appVersion: z.string().trim().max(32).optional(),
  platform: z.string().trim().max(32).optional(),
  context: z.record(z.unknown()).optional(),
});

const callBodySchema = z.object({
  callId: z.string().uuid(),
  role: z.enum(["caller", "callee"]),
  peerNexId: z.string().regex(/^NX-[A-Z2-9]{4}-[A-Z2-9]{4}$/),
  mode: z.enum(["audio", "video"]).default("audio"),
  status: z.enum(["ringing", "connected", "ended", "declined", "missed", "failed"]),
  startedAt: z.string().datetime().optional(),
  connectedAt: z.string().datetime().optional(),
  endedAt: z.string().datetime().optional(),
  durationSeconds: z.number().int().min(0).max(86_400).optional(),
  failureReason: z.string().trim().max(200).optional(),
  appVersion: z.string().trim().max(32).optional(),
  platform: z.string().trim().max(32).optional(),
});

function compactJson(value: Record<string, unknown> | undefined, maxBytes: number): string | null {
  if (!value) return null;
  const text = JSON.stringify(value);
  if (Buffer.byteLength(text, "utf8") <= maxBytes) return text;
  return JSON.stringify({ truncated: true, preview: text.slice(0, maxBytes) });
}

const SENSITIVE_KEY = /(token|secret|password|authorization|cookie|ciphertext|nex.?id|device.?id|phone|email)/i;

function redactText(value: string): string {
  return value
    .replace(/\b[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\b/g, "[jwt]")
    .replace(/\bNX-[A-Z2-9]{4}-[A-Z2-9]{4}\b/g, "[nex-id]")
    .replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, "[uuid]")
    .replace(/\+?\d[\d\s-]{8,}\d/g, "[number]");
}

function redactValue(value: unknown, depth = 0): unknown {
  if (depth > 5) return "[redacted]";
  if (Array.isArray(value)) return value.slice(0, 50).map((item) => redactValue(item, depth + 1));
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [
        key,
        SENSITIVE_KEY.test(key) ? "[redacted]" : redactValue(item, depth + 1),
      ]),
    );
  }
  return typeof value === "string" ? redactText(value) : value;
}

function redactedJson(value: Record<string, unknown> | undefined, maxBytes: number): string | null {
  return compactJson(value ? (redactValue(value) as Record<string, unknown>) : undefined, maxBytes);
}

const TERMINAL = new Set(["ended", "declined", "missed", "failed"]);
const RANK: Record<string, number> = {
  ringing: 0,
  connected: 1,
  ended: 2,
  declined: 2,
  missed: 2,
  failed: 2,
};

export async function registerAnalyticsRoutes(
  app: FastifyInstance,
  deps: Deps,
): Promise<void> {
  const { db, tokens, config } = deps;

  app.post(
    "/v1/analytics/heartbeat",
    { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const auth = await requireAuth(request, db, tokens);
      const body = parseWith(heartbeatSchema, request.body ?? {});
      await db.query(
        `INSERT INTO user_activity_daily (user_id, day, last_seen_at, app_version, platform)
         VALUES ($1, CURRENT_DATE, now(), $2, $3)
         ON CONFLICT (user_id, day) DO UPDATE
           SET last_seen_at = now(),
               app_version = coalesce(EXCLUDED.app_version, user_activity_daily.app_version),
               platform = coalesce(EXCLUDED.platform, user_activity_daily.platform)`,
        [auth.user.id, body.appVersion ?? null, body.platform ?? null],
      );
      return reply.send({ ok: true, serverTime: new Date().toISOString() });
    },
  );

  app.post(
    "/v1/analytics/events",
    { config: { rateLimit: { max: 60, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const auth = await requireAuth(request, db, tokens);
      const body = parseWith(eventsSchema, request.body);
      for (const event of body.events) {
        await db.query(
          `INSERT INTO app_events
             (user_id, device_id, event_type, app_version, platform, metadata)
           VALUES ($1,$2,$3,$4,$5,$6)`,
          [
            auth.user.id,
            auth.device.id,
            event.type,
            event.appVersion ?? null,
            event.platform ?? "android",
            redactedJson(event.metadata, 4096),
          ],
        );
      }
      return reply.code(202).send({ accepted: body.events.length });
    },
  );

  app.post(
    "/v1/analytics/crashes",
    { config: { rateLimit: { max: 10, timeWindow: "1 hour" } } },
    async (request, reply) => {
      const auth = await requireAuth(request, db, tokens);
      const body = parseWith(crashSchema, request.body);
      const id = randomUUID();
      await db.query(
        `INSERT INTO crash_reports
           (id, user_id, device_id, app_version, platform, message, stack, context)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
        [
          id,
          auth.user.id,
          auth.device.id,
          body.appVersion ?? null,
          body.platform ?? "android",
          redactText(body.message),
          body.stack ? redactText(body.stack) : null,
          redactedJson(body.context, 8192),
        ],
      );
      return reply.code(201).send({ id });
    },
  );

  app.post(
    "/v1/analytics/calls",
    { config: { rateLimit: { max: 120, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const auth = await requireAuth(request, db, tokens);
      const body = parseWith(callBodySchema, request.body);
      const remote = await loadRemoteConfig(db).catch(() => null);
      if (remote && (!remote.callsEnabled || (body.mode === "video" && !remote.videoCallsEnabled))) {
        throw new HttpError(403, "CALL_DISABLED", "This call type is disabled by the administrator");
      }
      if (
        remote &&
        body.durationSeconds !== undefined &&
        body.durationSeconds > remote.maxCallDurationSeconds
      ) {
        throw new HttpError(400, "CALL_DURATION_INVALID", "Call duration exceeds the configured limit");
      }
      if (body.startedAt && new Date(body.startedAt).getTime() > Date.now() + 5 * 60_000) {
        throw new HttpError(400, "CALL_TIME_INVALID", "Call start time is in the future");
      }
      const peer = await db.one<{ id: string }>(
        `SELECT id FROM users WHERE nex_id = $1 AND deleted_at IS NULL
           AND suspended_at IS NULL AND banned_at IS NULL`,
        [body.peerNexId],
      );
      const callerId = body.role === "caller" ? auth.user.id : peer?.id ?? null;
      const calleeId = body.role === "callee" ? auth.user.id : peer?.id ?? null;

      const result = await db.transaction(async (client) => {
        const found = await client.query<Record<string, unknown>>(
          "SELECT * FROM call_sessions WHERE call_id = $1",
          [body.callId],
        );
        const row = found.rows[0];
        const participant =
          !row ||
          row.caller_user_id === auth.user.id ||
          row.callee_user_id === auth.user.id ||
          // A null slot may only be claimed when the other slot matches the
          // reported peer. Otherwise an unrelated user can attach to a call.
          (row.caller_user_id === null &&
            callerId === auth.user.id &&
            calleeId !== null &&
            row.callee_user_id === calleeId) ||
          (row.callee_user_id === null &&
            calleeId === auth.user.id &&
            callerId !== null &&
            row.caller_user_id === callerId);
        if (!participant) {
          throw new HttpError(403, "CALL_NOT_YOURS", "This call belongs to another account");
        }
        const currentStatus = row ? String(row.status) : "ringing";
        if (TERMINAL.has(currentStatus)) return { status: currentStatus };
        if ((RANK[body.status] ?? 0) < (RANK[currentStatus] ?? 0)) {
          return { status: currentStatus };
        }

        const connectedAt: Date | null =
          row?.connected_at != null
            ? new Date(String(row.connected_at))
            : body.connectedAt
              ? new Date(body.connectedAt)
              : body.status === "connected"
                ? new Date()
                : null;
        const endedAt: Date | null =
          row?.ended_at != null
            ? new Date(String(row.ended_at))
            : body.endedAt
              ? new Date(body.endedAt)
              : TERMINAL.has(body.status)
                ? new Date()
                : null;
        let duration = body.durationSeconds ?? null;
        if (duration === null && connectedAt && endedAt) {
          duration = Math.max(
            0,
            Math.round((endedAt.getTime() - connectedAt.getTime()) / 1000),
          );
        }
        // INSERT ... ON CONFLICT avoids the SELECT ... FOR UPDATE gap-lock race
        // where caller and callee both report "ringing" for a new call and one
        // request fails with a duplicate-key error.
        await client.query(
          `INSERT INTO call_sessions
             (call_id, caller_user_id, callee_user_id, peer_nex_id, mode, status,
              failure_reason, started_at, connected_at, ended_at, duration_seconds,
              app_version, platform)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
           ON CONFLICT (call_id) DO UPDATE SET
             caller_user_id = coalesce(call_sessions.caller_user_id, EXCLUDED.caller_user_id),
             callee_user_id = coalesce(call_sessions.callee_user_id, EXCLUDED.callee_user_id),
             peer_nex_id = coalesce(call_sessions.peer_nex_id, EXCLUDED.peer_nex_id),
             status = EXCLUDED.status,
             failure_reason = coalesce(EXCLUDED.failure_reason, call_sessions.failure_reason),
             connected_at = coalesce(call_sessions.connected_at, EXCLUDED.connected_at),
             ended_at = coalesce(call_sessions.ended_at, EXCLUDED.ended_at),
             duration_seconds = coalesce(EXCLUDED.duration_seconds, call_sessions.duration_seconds),
             app_version = coalesce(EXCLUDED.app_version, call_sessions.app_version),
             platform = coalesce(EXCLUDED.platform, call_sessions.platform),
             updated_at = now()
           WHERE CASE call_sessions.status
                   WHEN 'ringing' THEN 0 WHEN 'connected' THEN 1 ELSE 2 END <= $14
             AND (call_sessions.caller_user_id = $15 OR call_sessions.callee_user_id = $15
                   OR (call_sessions.caller_user_id IS NULL AND $2 = $15
                       AND $3 IS NOT NULL AND call_sessions.callee_user_id = $3)
                   OR (call_sessions.callee_user_id IS NULL AND $3 = $15
                       AND $2 IS NOT NULL AND call_sessions.caller_user_id = $2))`,
          [
            body.callId,
            callerId,
            calleeId,
            body.peerNexId,
            body.mode,
            body.status,
            body.failureReason ?? null,
            body.startedAt ? new Date(body.startedAt) : new Date(),
            connectedAt,
            endedAt,
            duration,
            body.appVersion ?? null,
            body.platform ?? "android",
            RANK[body.status] ?? 0,
            auth.user.id,
          ],
        );
        const final = await client.query<{ status: string }>(
          "SELECT status FROM call_sessions WHERE call_id = $1",
          [body.callId],
        );
        return { status: final.rows[0]?.status ?? body.status };
      });
      return reply.send({ callId: body.callId, status: result.status });
    },
  );

  app.post("/v1/internal/heartbeat", async (request, reply) => {
    const expected = config.cronSecret ?? "";
    if (expected) {
      const header = request.headers.authorization ?? "";
      const supplied = header.startsWith("Bearer ") ? header.slice(7) : "";
      if (!secureEqual(supplied, expected)) {
        throw new HttpError(401, "CRON_UNAUTHORIZED", "Cron authentication required");
      }
    } else if (config.env === "production") {
      // The x-vercel-cron header is not a cryptographic proof of origin.
      throw new HttpError(503, "CRON_SECRET_MISSING", "Set CRON_SECRET to enable the cron job");
    } else if (!request.headers["x-vercel-cron"]) {
      throw new HttpError(401, "CRON_UNAUTHORIZED", "Cron authentication required");
    }
    const started = Date.now();
    await db.query("SELECT 1");
    const dbLatencyMs = Date.now() - started;
    await db.query("INSERT INTO service_heartbeats (source, db_latency_ms) VALUES ($1,$2)", [
      "cron",
      dbLatencyMs,
    ]);
    // Retention is enforced here so the database does not grow without bound.
    await db.query("DELETE FROM envelopes WHERE expires_at < now()").catch(() => undefined);
    await db
      .query("DELETE FROM delivery_receipts WHERE delivered_at < now() - interval '30 days'")
      .catch(() => undefined);
    await db
      .query(
        `DELETE FROM admin_sessions
         WHERE expires_at < now() OR revoked_at < now() - interval '7 days'`,
      )
      .catch(() => undefined);
    await db
      .query("DELETE FROM user_activity_daily WHERE day < CURRENT_DATE - 400")
      .catch(() => undefined);
    await db
      .query("DELETE FROM app_events WHERE created_at < now() - interval '180 days'")
      .catch(() => undefined);
    await db
      .query("DELETE FROM crash_reports WHERE created_at < now() - interval '180 days'")
      .catch(() => undefined);
    await db
      .query("DELETE FROM service_heartbeats WHERE created_at < now() - interval '90 days'")
      .catch(() => undefined);
    // A client that crashes while ringing would otherwise leave a permanent
    // "ringing" session in the admin panel and in call analytics.
    await db
      .query(
        `UPDATE call_sessions
            SET status = 'failed', failure_reason = 'ring_timeout',
                ended_at = coalesce(ended_at, now()), updated_at = now()
          WHERE status = 'ringing' AND started_at < now() - interval '2 minutes'`,
      )
      .catch(() => undefined);
    await db
      .query("DELETE FROM call_sessions WHERE created_at < now() - interval '180 days'")
      .catch(() => undefined);
    return reply.send({ ok: true, dbLatencyMs });
  });
}
