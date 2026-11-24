import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { z } from "zod";
import type { AppConfig } from "../config";
import type { Database } from "../db";
import { HttpError } from "../http";
import type { WsHub } from "../ws/hub";
import { audit, requireAdmin, verifyCsrf } from "../admin/security";
import { parseWith } from "../validation";

interface Deps {
  db: Database;
  config: AppConfig;
  hub: WsHub;
}

const RANGE_DAYS = { "7d": 7, "30d": 30, "90d": 90, "365d": 365 } as const;
type RangeKey = keyof typeof RANGE_DAYS;

interface RangeInfo {
  key: RangeKey;
  days: number;
  start: Date;
  previousStart: Date;
}

function parseRange(value: unknown): RangeInfo {
  const key: RangeKey =
    typeof value === "string" && Object.prototype.hasOwnProperty.call(RANGE_DAYS, value)
      ? (value as RangeKey)
      : "30d";
  const days = RANGE_DAYS[key];
  const now = Date.now();
  return {
    key,
    days,
    start: new Date(now - days * 24 * 60 * 60 * 1000),
    previousStart: new Date(now - 2 * days * 24 * 60 * 60 * 1000),
  };
}

function n(value: unknown): number {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function iso(value: unknown): string | null {
  if (!value) return null;
  const date = new Date(value as string);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function dayKey(value: unknown): string {
  const date = new Date(value as string);
  return date.toISOString().slice(0, 10);
}

function daySeries(days: number): string[] {
  const now = new Date();
  const end = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  const result: string[] = [];
  for (let index = days - 1; index >= 0; index -= 1) {
    result.push(new Date(end - index * 86_400_000).toISOString().slice(0, 10));
  }
  return result;
}

function fillSeries(
  keys: string[],
  rows: Array<{ bucket: unknown; value: unknown }>,
): Array<{ date: string; value: number }> {
  const map = new Map(rows.map((row) => [dayKey(row.bucket), n(row.value)]));
  return keys.map((date) => ({ date, value: map.get(date) ?? 0 }));
}

function pageFrom(query: unknown): { page: number; pageSize: number; offset: number } {
  const value = (query ?? {}) as Record<string, unknown>;
  const page = Math.min(Math.max(Math.trunc(n(value.page) || 1), 1), 100_000);
  const requested = Math.trunc(n(value.pageSize) || 25);
  const pageSize = [10, 25, 50, 100].includes(requested) ? requested : 25;
  return { page, pageSize, offset: (page - 1) * pageSize };
}

function csvEscape(value: unknown): string {
  if (value === null || value === undefined) return "";
  let text = String(value);
  // Spreadsheet applications interpret these leading characters as formulas.
  // Prefixing with an apostrophe keeps the cell as text when an admin opens
  // the export, without changing the underlying stored value.
  if (/^[=+\-@\t\r\n]/.test(text)) text = `'${text}`;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function sendCsv(
  reply: FastifyReply,
  filename: string,
  header: string[],
  rows: unknown[][],
): void {
  const body = [header, ...rows]
    .map((row) => row.map(csvEscape).join(","))
    .join("\r\n");
  reply
    .header("content-type", "text/csv; charset=utf-8")
    .header("content-disposition", `attachment; filename="${filename}"`)
    .header("cache-control", "no-store")
    .send(`\uFEFF${body}`);
}

async function healthSnapshot(db: Database): Promise<Record<string, unknown>> {
  const started = Date.now();
  let dbOk = true;
  try {
    await db.query("SELECT 1");
  } catch {
    dbOk = false;
  }
  const dbLatencyMs = Date.now() - started;
  const pool = (
    db as unknown as {
      pool?: { totalCount?: number; idleCount?: number; waitingCount?: number };
    }
  ).pool;
  let databaseSizeBytes: number | null = null;
  try {
    const row = await db.one<{ bytes: string | number }>(
      "SELECT pg_database_size(current_database()) AS bytes",
    );
    databaseSizeBytes = row ? n(row.bytes) : null;
  } catch {
    databaseSizeBytes = null;
  }
  let lastHeartbeat: string | null = null;
  let heartbeats24h: number | null = null;
  try {
    const row = await db.one<{ last: unknown; beats: unknown }>(
      `SELECT max(created_at) AS last,
              count(*) FILTER (WHERE created_at >= now() - interval '24 hours') AS beats
       FROM service_heartbeats`,
    );
    lastHeartbeat = iso(row?.last);
    heartbeats24h = row ? n(row.beats) : 0;
  } catch {
    heartbeats24h = null;
  }
  const memory = process.memoryUsage();
  return {
    dbOk,
    dbLatencyMs,
    pool: {
      total: n(pool?.totalCount),
      idle: n(pool?.idleCount),
      waiting: n(pool?.waitingCount),
    },
    memoryMb: Math.round((memory.rss / (1024 * 1024)) * 10) / 10,
    uptimeSeconds: Math.round(process.uptime()),
    databaseSizeBytes,
    lastHeartbeat,
    heartbeats24h,
    runtime: `node ${process.version}`,
    region: process.env.VERCEL_REGION ?? null,
  };
}

function userStatus(row: {
  deleted_at?: unknown;
  suspended_at?: unknown;
  banned_at?: unknown;
}): string {
  if (row.deleted_at) return "deleted";
  if (row.banned_at) return "banned";
  if (row.suspended_at) return "suspended";
  return "active";
}

function mapUser(row: Record<string, unknown>): Record<string, unknown> {
  return {
    id: row.id,
    nexId: row.nex_id,
    username: row.username,
    displayName: row.display_name,
    status: userStatus(row),
    suspendedReason: row.suspended_reason ?? null,
    suspendedAt: iso(row.suspended_at),
    bannedReason: row.banned_reason ?? null,
    bannedAt: iso(row.banned_at),
    deletedAt: iso(row.deleted_at),
    createdAt: iso(row.created_at),
    lastSeenAt: iso(row.last_seen_at),
    deviceCount: n(row.device_count),
    platform: row.platform ?? null,
  };
}

function mapCall(row: Record<string, unknown>): Record<string, unknown> {
  return {
    callId: row.call_id,
    mode: row.mode,
    status: row.status,
    failureReason: row.failure_reason ?? null,
    startedAt: iso(row.started_at),
    connectedAt: iso(row.connected_at),
    endedAt: iso(row.ended_at),
    durationSeconds: row.duration_seconds === null ? null : n(row.duration_seconds),
    connectSeconds:
      row.connected_at && row.started_at
        ? Math.max(
            0,
            Math.round(
              (new Date(row.connected_at as string).getTime() -
                new Date(row.started_at as string).getTime()) /
                1000,
            ),
          )
        : null,
    appVersion: row.app_version ?? null,
    platform: row.platform ?? null,
    caller: {
      id: row.caller_user_id ?? null,
      nexId: row.caller_nex_id ?? null,
      username: row.caller_username ?? null,
      displayName: row.caller_display_name ?? null,
    },
    callee: {
      id: row.callee_user_id ?? null,
      nexId: row.callee_nex_id ?? null,
      username: row.callee_username ?? null,
      displayName: row.callee_display_name ?? null,
    },
  };
}

const userActionSchema = z.object({
  action: z.enum(["suspend", "ban", "restore", "unban", "delete"]),
  reason: z.string().trim().max(300).optional(),
});

export async function registerAdminCoreRoutes(
  app: FastifyInstance,
  deps: Deps,
): Promise<void> {
  const { db, config, hub } = deps;

  app.get("/v1/admin/dashboard", async (request, reply) => {
    const context = await requireAdmin(request, db, config, "dashboard.read");
    const range = parseRange((request.query as Record<string, unknown>).range);
    const keys = daySeries(range.days);
    const started = Date.now();

    const [
      userTotals,
      online,
      active30,
      calls,
      previousCalls,
      messages,
      previousMessages,
      crashes,
      previousCrashes,
      events,
      callStatus,
      topEvents,
      platforms,
      versions,
      recentCalls,
      health,
    ] = await Promise.all([
      db.one<Record<string, unknown>>(
        `SELECT count(*) AS total,
                count(*) FILTER (WHERE created_at >= $1) AS new_current,
                count(*) FILTER (WHERE created_at >= $2 AND created_at < $1) AS new_previous,
                count(*) FILTER (WHERE suspended_at IS NOT NULL) AS suspended
         FROM users WHERE deleted_at IS NULL`,
        [range.start, range.previousStart],
      ),
      db.one<{ online: unknown }>(
        `SELECT count(DISTINCT user_id) AS online FROM devices
         WHERE revoked_at IS NULL AND last_seen_at >= now() - interval '2 minutes'`,
      ),
      db.one<{ active: unknown }>(
        `SELECT count(DISTINCT user_id) AS active FROM devices
         WHERE revoked_at IS NULL AND last_seen_at >= now() - interval '30 days'`,
      ),
      db.one<Record<string, unknown>>(
        `SELECT count(*) AS total,
                count(*) FILTER (WHERE status IN ('connected','ended')) AS connected,
                count(*) FILTER (WHERE status = 'declined') AS declined,
                count(*) FILTER (WHERE status IN ('failed','missed')) AS failed,
                avg(duration_seconds) AS avg_duration,
                avg(extract(epoch from connected_at) - extract(epoch from started_at)) AS avg_connect
         FROM call_sessions WHERE started_at >= $1`,
        [range.start],
      ),
      db.one<{ count: unknown }>(
        "SELECT count(*) AS count FROM call_sessions WHERE started_at >= $1 AND started_at < $2",
        [range.previousStart, range.start],
      ),
      db.one<Record<string, unknown>>(
        `SELECT coalesce(sum(sent), 0) AS sent, coalesce(sum(delivered), 0) AS delivered,
                coalesce(sum(skipped), 0) AS skipped
         FROM message_stats_daily WHERE day >= $1::date`,
        [range.start],
      ),
      db.one<{ sent: unknown }>(
        `SELECT coalesce(sum(sent), 0) AS sent FROM message_stats_daily
         WHERE day >= $1::date AND day < $2::date`,
        [range.previousStart, range.start],
      ),
      db.one<{ count: unknown }>(
        "SELECT count(*) AS count FROM crash_reports WHERE created_at >= $1",
        [range.start],
      ),
      db.one<{ count: unknown }>(
        "SELECT count(*) AS count FROM crash_reports WHERE created_at >= $1 AND created_at < $2",
        [range.previousStart, range.start],
      ),
      db.one<{ count: unknown }>(
        "SELECT count(*) AS count FROM app_events WHERE created_at >= $1",
        [range.start],
      ),
      db.query<{ status: string; count: unknown }>(
        `SELECT status, count(*) AS count FROM call_sessions
         WHERE started_at >= $1 GROUP BY status ORDER BY count DESC`,
        [range.start],
      ),
      db.query<{ event_type: string; count: unknown }>(
        `SELECT event_type, count(*) AS count FROM app_events
         WHERE created_at >= $1 GROUP BY event_type ORDER BY count DESC LIMIT 8`,
        [range.start],
      ),
      db.query<{ platform: string; count: unknown }>(
        `SELECT platform, count(*) AS count FROM devices
         WHERE revoked_at IS NULL GROUP BY platform ORDER BY count DESC`,
      ),
      db.query<{ app_version: string; count: unknown }>(
        `SELECT app_version, count(*) AS count FROM (
           SELECT app_version FROM crash_reports WHERE created_at >= $1 AND app_version IS NOT NULL
           UNION ALL
           SELECT app_version FROM app_events WHERE created_at >= $1 AND app_version IS NOT NULL
         ) versions GROUP BY app_version ORDER BY count DESC LIMIT 8`,
        [range.start],
      ),
      db.query<Record<string, unknown>>(
        `SELECT c.*, cu.nex_id AS caller_nex_id, cu.username AS caller_username,
                cu.display_name AS caller_display_name,
                pu.nex_id AS callee_nex_id, pu.username AS callee_username,
                pu.display_name AS callee_display_name
         FROM call_sessions c
         LEFT JOIN users cu ON cu.id = c.caller_user_id
         LEFT JOIN users pu ON pu.id = c.callee_user_id
         WHERE c.started_at >= $1
         ORDER BY c.started_at DESC LIMIT 8`,
        [range.start],
      ),
      healthSnapshot(db),
    ]);

    const usersSeries = await db.query<{ bucket: unknown; value: unknown }>(
      `SELECT created_at::date AS bucket, count(*) AS value
       FROM users WHERE created_at >= $1 GROUP BY created_at::date ORDER BY 1`,
      [range.start],
    );
    const activitySeries = await db.query<{ bucket: unknown; value: unknown }>(
      `SELECT day AS bucket, count(*) AS value FROM user_activity_daily
       WHERE day >= $1::date GROUP BY day ORDER BY day`,
      [range.start],
    );
    const messageSeries = await db.query<{ bucket: unknown; value: unknown }>(
      `SELECT day AS bucket, sent AS value FROM message_stats_daily
       WHERE day >= $1::date GROUP BY day, sent ORDER BY day`,
      [range.start],
    );
    const callSeries = await db.query<{ bucket: unknown; value: unknown }>(
      `SELECT started_at::date AS bucket, count(*) AS value
       FROM call_sessions WHERE started_at >= $1 GROUP BY started_at::date ORDER BY 1`,
      [range.start],
    );
    const connectedSeries = await db.query<{ bucket: unknown; value: unknown }>(
      `SELECT started_at::date AS bucket, count(*) AS value
       FROM call_sessions WHERE started_at >= $1 AND status IN ('connected','ended')
       GROUP BY started_at::date ORDER BY 1`,
      [range.start],
    );
    const crashSeries = await db.query<{ bucket: unknown; value: unknown }>(
      `SELECT created_at::date AS bucket, count(*) AS value
       FROM crash_reports WHERE created_at >= $1 GROUP BY created_at::date ORDER BY 1`,
      [range.start],
    );

    const totalUsers = n(userTotals?.total);
    const newUsers = n(userTotals?.new_current);
    const previousNewUsers = n(userTotals?.new_previous);
    const totalCalls = n(calls?.total);
    const connectedCalls = n(calls?.connected);
    const sent = n(messages?.sent);
    const delivered = n(messages?.delivered);
    const totalCrashes = n(crashes?.count);
    const previousTotalCalls = n(previousCalls?.count);
    const previousSent = n(previousMessages?.sent);
    const previousTotalCrashes = n(previousCrashes?.count);

    return reply
      .header("cache-control", "no-store")
      .send({
        generatedAt: new Date().toISOString(),
        adminLatencyMs: Date.now() - started,
        range: { key: range.key, days: range.days, start: range.start.toISOString() },
        metrics: {
          totalUsers,
          newUsers,
          previousNewUsers,
          onlineUsers: n(online?.online),
          activeUsers30d: n(active30?.active),
          suspendedUsers: n(userTotals?.suspended),
          totalCalls,
          connectedCalls,
          declinedCalls: n(calls?.declined),
          failedCalls: n(calls?.failed),
          previousTotalCalls,
          callSuccessRate: totalCalls > 0 ? Math.round((connectedCalls / totalCalls) * 1000) / 10 : null,
          averageCallSeconds: Math.round(n(calls?.avg_duration)),
          averageConnectSeconds: Math.round(n(calls?.avg_connect)),
          messagesSent: sent,
          messagesDelivered: delivered,
          messagesSkipped: n(messages?.skipped),
          previousMessagesSent: previousSent,
          deliveryRate: sent > 0 ? Math.round((delivered / sent) * 1000) / 10 : null,
          crashes: totalCrashes,
          previousCrashes: previousTotalCrashes,
          events: n(events?.count),
        },
        series: {
          users: fillSeries(keys, usersSeries),
          activity: fillSeries(keys, activitySeries),
          messages: fillSeries(keys, messageSeries),
          calls: fillSeries(keys, callSeries),
          connectedCalls: fillSeries(keys, connectedSeries),
          crashes: fillSeries(keys, crashSeries),
        },
        callStatus: callStatus.map((row) => ({ status: row.status, count: n(row.count) })),
        topEvents: topEvents.map((row) => ({ type: row.event_type, count: n(row.count) })),
        platforms: platforms.map((row) => ({
          platform: row.platform ?? "unknown",
          count: n(row.count),
        })),
        versions: versions.map((row) => ({
          version: row.app_version ?? "unknown",
          count: n(row.count),
        })),
        recentCalls: recentCalls.map(mapCall),
        health,
      });
  });

  app.get("/v1/admin/users", async (request, reply) => {
    await requireAdmin(request, db, config, "users.read");
    const query = request.query as Record<string, unknown>;
    const { page, pageSize, offset } = pageFrom(query);
    const search = typeof query.q === "string" ? query.q.trim().slice(0, 80) : "";
    const status = typeof query.status === "string" ? query.status : "all";
    const sortKey = typeof query.sort === "string" ? query.sort : "created_desc";
    const conditions: string[] = ["1=1"];
    const params: unknown[] = [];
    if (status === "active") {
      conditions.push(
        "u.deleted_at IS NULL AND u.suspended_at IS NULL AND u.banned_at IS NULL",
      );
    } else if (status === "suspended") {
      conditions.push("u.deleted_at IS NULL AND u.suspended_at IS NOT NULL");
    } else if (status === "banned") {
      conditions.push("u.deleted_at IS NULL AND u.banned_at IS NOT NULL");
    } else if (status === "deleted") {
      conditions.push("u.deleted_at IS NOT NULL");
    } else {
      conditions.push("u.deleted_at IS NULL");
    }
    if (search) {
      params.push(`${search}%`, `%${search}%`);
      conditions.push(
        `(u.username LIKE $${params.length - 1} OR u.nex_id LIKE $${params.length - 1} OR u.display_name ILIKE $${params.length})`,
      );
    }
    const where = conditions.join(" AND ");
    const order =
      sortKey === "created_asc"
        ? "u.created_at ASC"
        : sortKey === "last_seen_desc"
          ? "max(d.last_seen_at) DESC NULLS LAST"
          : sortKey === "username_asc"
            ? "u.username ASC"
            : "u.created_at DESC";
    const countRow = await db.one<{ count: unknown }>(
      `SELECT count(*) AS count FROM users u WHERE ${where}`,
      params,
    );
    const rows = await db.query<Record<string, unknown>>(
      `SELECT u.id, u.nex_id, u.username, u.display_name, u.created_at,
              u.suspended_at, u.suspended_reason, u.banned_at, u.banned_reason, u.deleted_at,
              count(d.id) FILTER (WHERE d.revoked_at IS NULL) AS device_count,
              max(d.last_seen_at) AS last_seen_at, max(d.platform) AS platform
       FROM users u LEFT JOIN devices d ON d.user_id = u.id
       WHERE ${where}
       GROUP BY u.id, u.nex_id, u.username, u.display_name, u.created_at,
                u.suspended_at, u.suspended_reason, u.banned_at, u.banned_reason, u.deleted_at
       ORDER BY ${order}
       LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
      [...params, pageSize, offset],
    );
    const total = n(countRow?.count);
    return reply.send({
      users: rows.map(mapUser),
      page,
      pageSize,
      total,
      pages: Math.max(1, Math.ceil(total / pageSize)),
    });
  });

  app.get("/v1/admin/users/:id", async (request, reply) => {
    await requireAdmin(request, db, config, "users.read");
    const { id } = request.params as { id: string };
    const user = await db.one<Record<string, unknown>>(
      `SELECT u.*, (SELECT max(last_seen_at) FROM devices WHERE user_id = u.id AND revoked_at IS NULL)
                AS last_seen_at,
              (SELECT count(*) FROM devices WHERE user_id = u.id AND revoked_at IS NULL)
                AS device_count
       FROM users u WHERE u.id = $1`,
      [id],
    );
    if (!user) throw new HttpError(404, "USER_NOT_FOUND", "User not found");
    const [devices, counts, activity, calls, crashes] = await Promise.all([
      db.query<Record<string, unknown>>(
        `SELECT id, name, platform, signal_device_id, created_at, last_seen_at, revoked_at
         FROM devices WHERE user_id = $1 ORDER BY created_at ASC`,
        [id],
      ),
      db.one<Record<string, unknown>>(
        `SELECT
           (SELECT count(*) FROM envelopes WHERE sender_user_id = $1) AS sent,
           (SELECT count(*) FROM envelopes WHERE recipient_user_id = $1) AS received,
           (SELECT count(*) FROM call_sessions WHERE caller_user_id = $1 OR callee_user_id = $1) AS calls,
           (SELECT count(*) FROM crash_reports WHERE user_id = $1) AS crashes`,
        [id],
      ),
      db.query<Record<string, unknown>>(
        `SELECT day, last_seen_at, app_version, platform FROM user_activity_daily
         WHERE user_id = $1 ORDER BY day DESC LIMIT 30`,
        [id],
      ),
      db.query<Record<string, unknown>>(
        `SELECT c.* FROM call_sessions c
         WHERE c.caller_user_id = $1 OR c.callee_user_id = $1
         ORDER BY c.started_at DESC LIMIT 20`,
        [id],
      ),
      db.query<Record<string, unknown>>(
        `SELECT id, app_version, platform, message, created_at FROM crash_reports
         WHERE user_id = $1 ORDER BY created_at DESC LIMIT 20`,
        [id],
      ),
    ]);
    return reply.send({
      user: {
        ...mapUser(user),
        avatar: user.avatar ?? null,
      },
      devices: devices.map((device) => ({
        id: device.id,
        name: device.name,
        platform: device.platform,
        signalDeviceId: n(device.signal_device_id),
        createdAt: iso(device.created_at),
        lastSeenAt: iso(device.last_seen_at),
        revokedAt: iso(device.revoked_at),
      })),
      counts: {
        sent: n(counts?.sent),
        received: n(counts?.received),
        calls: n(counts?.calls),
        crashes: n(counts?.crashes),
      },
      activity: activity.map((row) => ({
        day: row.day,
        lastSeenAt: iso(row.last_seen_at),
        appVersion: row.app_version ?? null,
        platform: row.platform ?? null,
      })),
      calls: calls.map(mapCall),
      crashes: crashes.map((row) => ({
        id: row.id,
        appVersion: row.app_version ?? null,
        platform: row.platform ?? null,
        message: row.message,
        createdAt: iso(row.created_at),
      })),
    });
  });

  app.patch("/v1/admin/users/:id", async (request, reply) => {
    const context = await requireAdmin(request, db, config, "users.write");
    verifyCsrf(request, context, config);
    const { id } = request.params as { id: string };
    const body = parseWith(userActionSchema, request.body);
    const user = await db.one<Record<string, unknown>>("SELECT * FROM users WHERE id = $1", [id]);
    if (!user) throw new HttpError(404, "USER_NOT_FOUND", "User not found");

    if (body.action === "suspend") {
      await db.query(
        "UPDATE users SET suspended_at = now(), suspended_reason = $1 WHERE id = $2",
        [body.reason?.slice(0, 300) ?? null, id],
      );
      await db.query(
        "UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL",
        [id],
      );
      hub.closeUser(id, 4003, "account suspended");
    } else if (body.action === "ban") {
      await db.query(
        "UPDATE users SET banned_at = now(), banned_reason = $1 WHERE id = $2",
        [body.reason?.slice(0, 300) ?? null, id],
      );
      await db.query(
        "UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL",
        [id],
      );
      hub.closeUser(id, 4003, "account banned");
    } else if (body.action === "unban") {
      await db.query(
        "UPDATE users SET banned_at = NULL, banned_reason = NULL WHERE id = $1",
        [id],
      );
    } else if (body.action === "restore") {
      // A normal restore clears temporary suspension/soft-delete, not an
      // explicit enforcement ban. Use "unban" for that.
      await db.query(
        `UPDATE users SET suspended_at = NULL, suspended_reason = NULL, deleted_at = NULL
         WHERE id = $1`,
        [id],
      );
    } else {
      await db.query("UPDATE users SET deleted_at = now() WHERE id = $1", [id]);
      await db.query(
        "UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL",
        [id],
      );
      hub.closeUser(id, 4003, "account deleted");
    }
    await audit(db, context.admin.id, `user.${body.action}`, request, "user", id, {
      username: user.username,
      reason: body.reason ?? null,
    });
    const updated = await db.one<Record<string, unknown>>("SELECT * FROM users WHERE id = $1", [id]);
    return reply.send({ user: mapUser(updated ?? user) });
  });

  app.get("/v1/admin/calls", async (request, reply) => {
    await requireAdmin(request, db, config, "calls.read");
    const query = request.query as Record<string, unknown>;
    const { page, pageSize, offset } = pageFrom(query);
    const range = parseRange(query.range);
    const allowed = ["ringing", "connected", "ended", "declined", "missed", "failed"];
    const status = typeof query.status === "string" && allowed.includes(query.status)
      ? query.status
      : "";
    const params: unknown[] = [range.start];
    let where = "c.started_at >= $1";
    if (status) {
      params.push(status);
      where += ` AND c.status = $${params.length}`;
    }
    const countRow = await db.one<{ count: unknown }>(
      `SELECT count(*) AS count FROM call_sessions c WHERE ${where}`,
      params,
    );
    const rows = await db.query<Record<string, unknown>>(
      `SELECT c.*, cu.nex_id AS caller_nex_id, cu.username AS caller_username,
              cu.display_name AS caller_display_name,
              pu.nex_id AS callee_nex_id, pu.username AS callee_username,
              pu.display_name AS callee_display_name
       FROM call_sessions c
       LEFT JOIN users cu ON cu.id = c.caller_user_id
       LEFT JOIN users pu ON pu.id = c.callee_user_id
       WHERE ${where}
       ORDER BY c.started_at DESC
       LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
      [...params, pageSize, offset],
    );
    const total = n(countRow?.count);
    return reply.send({
      calls: rows.map(mapCall),
      page,
      pageSize,
      total,
      pages: Math.max(1, Math.ceil(total / pageSize)),
      range: range.key,
    });
  });

  app.get("/v1/admin/monitoring", async (request, reply) => {
    await requireAdmin(request, db, config, "monitoring.read");
    const range = parseRange((request.query as Record<string, unknown>).range);
    const keys = daySeries(range.days);
    const [health, crashes, crashTotal, failedCalls, failureReasons, crashSeries, versionRows] =
      await Promise.all([
        healthSnapshot(db),
        db.query<Record<string, unknown>>(
          `SELECT c.id, c.app_version, c.platform, c.message, c.stack, c.context, c.created_at,
                  u.nex_id, u.username
           FROM crash_reports c LEFT JOIN users u ON u.id = c.user_id
           WHERE c.created_at >= $1 ORDER BY c.created_at DESC LIMIT 100`,
          [range.start],
        ),
        db.one<{ count: unknown }>(
          "SELECT count(*) AS count FROM crash_reports WHERE created_at >= $1",
          [range.start],
        ),
        db.query<Record<string, unknown>>(
          `SELECT c.*, cu.nex_id AS caller_nex_id, cu.username AS caller_username,
                  cu.display_name AS caller_display_name,
                  pu.nex_id AS callee_nex_id, pu.username AS callee_username,
                  pu.display_name AS callee_display_name
           FROM call_sessions c
           LEFT JOIN users cu ON cu.id = c.caller_user_id
           LEFT JOIN users pu ON pu.id = c.callee_user_id
           WHERE c.started_at >= $1 AND c.status IN ('failed','missed','declined')
           ORDER BY c.started_at DESC LIMIT 50`,
          [range.start],
        ),
        db.query<{ reason: string; count: unknown }>(
          `SELECT coalesce(failure_reason, 'unspecified') AS reason, count(*) AS count
           FROM call_sessions
           WHERE started_at >= $1 AND status = 'failed'
           GROUP BY 1 ORDER BY count DESC LIMIT 12`,
          [range.start],
        ),
        db.query<{ bucket: unknown; value: unknown }>(
          `SELECT date_trunc('day', created_at) AS bucket, count(*) AS value
           FROM crash_reports WHERE created_at >= $1 GROUP BY 1 ORDER BY 1`,
          [range.start],
        ),
        db.query<{ app_version: string; count: unknown }>(
          `SELECT app_version, count(*) AS count FROM crash_reports
           WHERE created_at >= $1 AND app_version IS NOT NULL
           GROUP BY app_version ORDER BY count DESC LIMIT 10`,
          [range.start],
        ),
      ]);
    return reply.send({
      generatedAt: new Date().toISOString(),
      range: range.key,
      health,
      crashTotal: n(crashTotal?.count),
      crashes: crashes.map((row) => ({
        id: row.id,
        appVersion: row.app_version ?? null,
        platform: row.platform ?? null,
        message: row.message,
        stack: row.stack ?? null,
        context: row.context ?? null,
        createdAt: iso(row.created_at),
        nexId: row.nex_id ?? null,
        username: row.username ?? null,
      })),
      failedCalls: failedCalls.map(mapCall),
      failureReasons: failureReasons.map((row) => ({
        reason: row.reason,
        count: n(row.count),
      })),
      crashSeries: fillSeries(keys, crashSeries),
      versions: versionRows.map((row) => ({
        version: row.app_version ?? "unknown",
        count: n(row.count),
      })),
    });
  });

  app.get("/v1/admin/audit", async (request, reply) => {
    await requireAdmin(request, db, config, "audit.read");
    const query = request.query as Record<string, unknown>;
    const { page, pageSize, offset } = pageFrom(query);
    const range = parseRange(query.range);
    const action = typeof query.action === "string" ? query.action.slice(0, 80) : "";
    const params: unknown[] = [range.start];
    let where = "l.created_at >= $1";
    if (action) {
      params.push(`${action}%`);
      where += ` AND l.action LIKE $${params.length}`;
    }
    const countRow = await db.one<{ count: unknown }>(
      `SELECT count(*) AS count FROM admin_audit_logs l WHERE ${where}`,
      params,
    );
    const rows = await db.query<Record<string, unknown>>(
      `SELECT l.*, a.username AS admin_username FROM admin_audit_logs l
       LEFT JOIN admin_users a ON a.id = l.admin_id
       WHERE ${where} ORDER BY l.created_at DESC
       LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
      [...params, pageSize, offset],
    );
    const total = n(countRow?.count);
    return reply.send({
      logs: rows.map((row) => ({
        id: row.id,
        adminId: row.admin_id ?? null,
        adminUsername: row.admin_username ?? "system",
        action: row.action,
        targetType: row.target_type ?? null,
        targetId: row.target_id ?? null,
        details: row.details ?? null,
        ip: row.ip ?? null,
        userAgent: row.user_agent ?? null,
        createdAt: iso(row.created_at),
      })),
      page,
      pageSize,
      total,
      pages: Math.max(1, Math.ceil(total / pageSize)),
    });
  });

  app.get("/v1/admin/admins", async (request, reply) => {
    await requireAdmin(request, db, config, "admins.manage");
    const rows = await db.query<Record<string, unknown>>(
      `SELECT id, username, display_name, role, active, last_login_at, created_at
       FROM admin_users ORDER BY created_at ASC`,
    );
    return reply.send({
      admins: rows.map((row) => ({
        id: row.id,
        username: row.username,
        email: row.username,
        displayName: row.display_name,
        role: row.role,
        active: row.active,
        lastLoginAt: iso(row.last_login_at),
        createdAt: iso(row.created_at),
      })),
    });
  });

  app.get("/v1/admin/sessions", async (request, reply) => {
    const context = await requireAdmin(request, db, config);
    const rows = await db.query<Record<string, unknown>>(
      `SELECT id, created_at, last_seen_at, expires_at, revoked_at, ip, user_agent
       FROM admin_sessions WHERE admin_id = $1 ORDER BY created_at DESC LIMIT 25`,
      [context.admin.id],
    );
    return reply.send({
      sessions: rows.map((row) => ({
        id: row.id,
        current: row.id === context.sessionId,
        createdAt: iso(row.created_at),
        lastSeenAt: iso(row.last_seen_at),
        expiresAt: iso(row.expires_at),
        revokedAt: iso(row.revoked_at),
        ip: row.ip ?? null,
        userAgent: row.user_agent ?? null,
      })),
    });
  });

  app.delete("/v1/admin/sessions/:id", async (request, reply) => {
    const context = await requireAdmin(request, db, config);
    verifyCsrf(request, context, config);
    const { id } = request.params as { id: string };
    await db.query(
      "UPDATE admin_sessions SET revoked_at = now() WHERE id = $1 AND admin_id = $2",
      [id, context.admin.id],
    );
    await audit(db, context.admin.id, "admin.session.revoked", request, "session", id);
    return reply.send({ ok: true });
  });

  app.get("/v1/admin/reports/:type/export.csv", async (request, reply) => {
    const context = await requireAdmin(request, db, config, "reports.export");
    const { type } = request.params as { type: string };
    const range = parseRange((request.query as Record<string, unknown>).range);
    const recordExport = (rowCount: number, exportType = type): Promise<void> =>
      audit(db, context.admin.id, "report.exported", request, "report", exportType, {
        range: range.key,
        rowCount,
      });
    if (type === "users") {
      const rows = await db.query<Record<string, unknown>>(
        `SELECT u.nex_id, u.username, u.display_name, u.created_at, u.suspended_at,
                u.deleted_at, d.last_seen_at, d.devices
         FROM users u
         LEFT JOIN (
           SELECT user_id, max(last_seen_at) AS last_seen_at,
                  count(*) FILTER (WHERE revoked_at IS NULL) AS devices
           FROM devices GROUP BY user_id
         ) d ON d.user_id = u.id
         WHERE u.created_at >= $1 ORDER BY u.created_at DESC LIMIT 50000`,
        [range.start],
      );
      await recordExport(rows.length);
      sendCsv(
        reply,
        `nexchat-users-${range.key}.csv`,
        ["nex_id", "username", "display_name", "created_at", "status", "last_seen_at", "devices"],
        rows.map((row) => [
          row.nex_id,
          row.username,
          row.display_name,
          iso(row.created_at),
          userStatus(row),
          iso(row.last_seen_at),
          n(row.devices),
        ]),
      );
      return;
    }
    if (type === "calls") {
      const rows = await db.query<Record<string, unknown>>(
        `SELECT c.*, cu.nex_id AS caller_nex_id, pu.nex_id AS callee_nex_id
         FROM call_sessions c
         LEFT JOIN users cu ON cu.id = c.caller_user_id
         LEFT JOIN users pu ON pu.id = c.callee_user_id
         WHERE c.started_at >= $1 ORDER BY c.started_at DESC LIMIT 50000`,
        [range.start],
      );
      await recordExport(rows.length);
      sendCsv(
        reply,
        `nexchat-calls-${range.key}.csv`,
        [
          "call_id",
          "caller_nex_id",
          "callee_nex_id",
          "mode",
          "status",
          "started_at",
          "connected_at",
          "ended_at",
          "duration_seconds",
          "failure_reason",
          "app_version",
        ],
        rows.map((row) => [
          row.call_id,
          row.caller_nex_id,
          row.callee_nex_id,
          row.mode,
          row.status,
          iso(row.started_at),
          iso(row.connected_at),
          iso(row.ended_at),
          row.duration_seconds ?? "",
          row.failure_reason ?? "",
          row.app_version ?? "",
        ]),
      );
      return;
    }
    if (type === "crashes") {
      const rows = await db.query<Record<string, unknown>>(
        `SELECT c.created_at, c.app_version, c.platform, c.message, u.nex_id
         FROM crash_reports c LEFT JOIN users u ON u.id = c.user_id
         WHERE c.created_at >= $1 ORDER BY c.created_at DESC LIMIT 50000`,
        [range.start],
      );
      await recordExport(rows.length);
      sendCsv(
        reply,
        `nexchat-crashes-${range.key}.csv`,
        ["created_at", "app_version", "platform", "nex_id", "message"],
        rows.map((row) => [
          iso(row.created_at),
          row.app_version ?? "",
          row.platform ?? "",
          row.nex_id ?? "",
          row.message,
        ]),
      );
      return;
    }
    if (type === "audit") {
      await requireAdmin(request, db, config, "audit.read");
      const rows = await db.query<Record<string, unknown>>(
        `SELECT l.created_at, a.username, l.action, l.target_type, l.target_id, l.ip
         FROM admin_audit_logs l LEFT JOIN admin_users a ON a.id = l.admin_id
         WHERE l.created_at >= $1 ORDER BY l.created_at DESC LIMIT 50000`,
        [range.start],
      );
      await recordExport(rows.length, "audit");
      sendCsv(
        reply,
        `nexchat-audit-${range.key}.csv`,
        ["created_at", "admin", "action", "target_type", "target_id", "ip"],
        rows.map((row) => [
          iso(row.created_at),
          row.username ?? "system",
          row.action,
          row.target_type ?? "",
          row.target_id ?? "",
          row.ip ?? "",
        ]),
      );
      return;
    }
    throw new HttpError(404, "REPORT_UNKNOWN", "Unknown report type");
  });
}
