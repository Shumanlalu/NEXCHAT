import { randomUUID } from "node:crypto";
import type { FastifyInstance } from "fastify";
import type { PoolClient } from "pg";
import { z } from "zod";
import type { AppConfig } from "../config";
import type { Database } from "../db";
import { HttpError } from "../http";
import { hashPassword, validatePassword } from "../security/passwords";
import { parseWith } from "../validation";
import {
  audit,
  encryptTotpSecret,
  generateTotpSecret,
  requireAdmin,
  verifyCsrf,
} from "../admin/security";
import {
  DEFAULT_REMOTE_CONFIG,
  loadRemoteConfig,
  remoteConfigSchema,
  rollbackRemoteConfig,
  saveRemoteConfig,
} from "../admin/remote-config";

interface Deps {
  db: Database;
  config: AppConfig;
}

const announcementSchema = z.object({
  title: z.string().trim().min(1).max(120),
  body: z.string().trim().min(1).max(2000),
  severity: z.enum(["info", "warning", "critical"]).default("info"),
  active: z.boolean().default(true),
  startsAt: z.string().datetime().nullable().optional(),
  endsAt: z.string().datetime().nullable().optional(),
});

const announcementPatchSchema = announcementSchema.partial();

const adminCreateSchema = z
  .object({
    email: z
      .string()
      .trim()
      .transform((value) => value.toLowerCase())
      .pipe(z.string().max(254).email("Enter a valid email address")),
    displayName: z.string().trim().min(1).max(60),
    password: z.string().min(12).max(128),
    confirmPassword: z.string().min(12).max(128),
    role: z.enum(["super_admin", "admin", "analyst", "support"]),
  })
  .refine((value) => value.password === value.confirmPassword, {
    path: ["confirmPassword"],
    message: "Passwords do not match",
  });

const adminPatchSchema = z.object({
  role: z.enum(["super_admin", "admin", "analyst", "support"]).optional(),
  active: z.boolean().optional(),
});

/**
 * Serialize changes to the last active super admin. A plain count-then-write
 * check can race when two administrators are demoted/deleted simultaneously;
 * the settings-row update takes a row lock for the duration of the transaction.
 */
async function withSuperAdminGuard<T>(
  db: Database,
  fn: (client: PoolClient) => Promise<T>,
): Promise<T> {
  return db.transaction(async (client) => {
    await client.query(
      `INSERT INTO app_settings (key, value, version, updated_by, updated_at)
       VALUES ('admin_super_admin_guard', $1, 1, NULL, now())
       ON CONFLICT (key) DO UPDATE
         SET version = app_settings.version + 1, updated_at = now()`,
      [JSON.stringify({})],
    );
    return fn(client);
  });
}

async function activeSuperAdminCount(
  client: PoolClient,
): Promise<number> {
  const row = await client.query<{ count: unknown }>(
    "SELECT count(*) AS count FROM admin_users WHERE role = 'super_admin' AND active = TRUE",
  );
  return Number(row.rows[0]?.count ?? 0);
}

export async function registerAdminConfigRoutes(
  app: FastifyInstance,
  deps: Deps,
): Promise<void> {
  const { db, config } = deps;

  async function history(): Promise<Array<Record<string, unknown>>> {
    const rows = await db.query<Record<string, unknown>>(
      `SELECT h.id, h.previous_value, h.new_value, h.version, h.created_at,
              a.username AS admin_username
       FROM app_setting_history h LEFT JOIN admin_users a ON a.id = h.admin_id
       WHERE h.key = 'remote_config' ORDER BY h.created_at DESC LIMIT 30`,
    );
    return rows.map((row) => ({
      id: row.id,
      previousValue: row.previous_value ?? null,
      newValue: row.new_value,
      version: Number(row.version),
      adminUsername: row.admin_username ?? "system",
      createdAt: row.created_at,
    }));
  }

  app.get("/v1/admin/config", async (request, reply) => {
    await requireAdmin(request, db, config, "config.read");
    const current = await loadRemoteConfig(db);
    return reply.send({
      config: current,
      defaults: DEFAULT_REMOTE_CONFIG,
      history: await history(),
      releaseBound: [
        "Adding a new permission or a new native capability",
        "Changing camera capture resolution or video codec selection",
        "New message types, attachment limits enforced by the client, or encryption changes",
        "Anything that alters the Android manifest, signing keys, or database schema",
      ],
    });
  });

  app.patch("/v1/admin/config", async (request, reply) => {
    const context = await requireAdmin(request, db, config, "config.write");
    verifyCsrf(request, context, config);
    const body = parseWith(remoteConfigSchema, request.body);
    const saved = await saveRemoteConfig(db, body, context.admin.id);
    await audit(db, context.admin.id, "config.updated", request, "remote_config", "remote_config", {
      version: saved.version,
      callsEnabled: saved.callsEnabled,
      videoCallsEnabled: saved.videoCallsEnabled,
      maintenanceMode: saved.maintenanceMode,
    });
    return reply.send({ config: saved, history: await history() });
  });

  app.post("/v1/admin/config/rollback/:id", async (request, reply) => {
    const context = await requireAdmin(request, db, config, "config.write");
    verifyCsrf(request, context, config);
    const { id } = request.params as { id: string };
    try {
      const restored = await rollbackRemoteConfig(db, id, context.admin.id);
      await audit(db, context.admin.id, "config.rolled_back", request, "remote_config", id, {
        version: restored.version,
      });
      return reply.send({ config: restored, history: await history() });
    } catch (error) {
      const status = (error as { statusCode?: number }).statusCode ?? 500;
      throw new HttpError(status, "ROLLBACK_FAILED", (error as Error).message);
    }
  });

  app.get("/v1/admin/announcements", async (request, reply) => {
    await requireAdmin(request, db, config, "config.read");
    const rows = await db.query<Record<string, unknown>>(
      `SELECT id, title, body, severity, active, starts_at, ends_at, created_at, updated_at
       FROM announcements ORDER BY created_at DESC LIMIT 100`,
    );
    return reply.send({
      announcements: rows.map((row) => ({
        id: row.id,
        title: row.title,
        body: row.body,
        severity: row.severity,
        active: row.active,
        startsAt: row.starts_at,
        endsAt: row.ends_at ?? null,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
      })),
    });
  });

  app.post("/v1/admin/announcements", async (request, reply) => {
    const context = await requireAdmin(request, db, config, "announcements.write");
    verifyCsrf(request, context, config);
    const body = parseWith(announcementSchema, request.body);
    const id = randomUUID();
    await db.query(
      `INSERT INTO announcements
         (id, title, body, severity, active, starts_at, ends_at, created_by)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
      [
        id,
        body.title,
        body.body,
        body.severity,
        body.active,
        body.startsAt ? new Date(body.startsAt) : new Date(),
        body.endsAt ? new Date(body.endsAt) : null,
        context.admin.id,
      ],
    );
    await audit(db, context.admin.id, "announcement.created", request, "announcement", id, {
      title: body.title,
      severity: body.severity,
    });
    return reply.code(201).send({ id });
  });

  app.patch("/v1/admin/announcements/:id", async (request, reply) => {
    const context = await requireAdmin(request, db, config, "announcements.write");
    verifyCsrf(request, context, config);
    const { id } = request.params as { id: string };
    const body = parseWith(announcementPatchSchema, request.body);
    const existing = await db.one<Record<string, unknown>>(
      "SELECT * FROM announcements WHERE id = $1",
      [id],
    );
    if (!existing) throw new HttpError(404, "ANNOUNCEMENT_NOT_FOUND", "Announcement not found");
    const values = {
      title: body.title ?? (existing.title as string),
      body: body.body ?? (existing.body as string),
      severity: body.severity ?? (existing.severity as string),
      active: body.active ?? (existing.active as boolean),
      startsAt: body.startsAt ? new Date(body.startsAt) : existing.starts_at,
      endsAt:
        body.endsAt === undefined
          ? existing.ends_at
          : body.endsAt === null
            ? null
            : new Date(body.endsAt),
    };
    await db.query(
      `UPDATE announcements
       SET title=$1, body=$2, severity=$3, active=$4, starts_at=$5, ends_at=$6, updated_at=now()
       WHERE id=$7`,
      [
        values.title,
        values.body,
        values.severity,
        values.active,
        values.startsAt,
        values.endsAt,
        id,
      ],
    );
    await audit(db, context.admin.id, "announcement.updated", request, "announcement", id, {
      active: values.active,
    });
    return reply.send({ ok: true });
  });

  app.delete("/v1/admin/announcements/:id", async (request, reply) => {
    const context = await requireAdmin(request, db, config, "announcements.write");
    verifyCsrf(request, context, config);
    const { id } = request.params as { id: string };
    await db.query("DELETE FROM announcements WHERE id = $1", [id]);
    await audit(db, context.admin.id, "announcement.deleted", request, "announcement", id);
    return reply.send({ ok: true });
  });

  app.post(
    "/v1/admin/admins",
    { config: { rateLimit: { max: 20, timeWindow: "15 minutes" } } },
    async (request, reply) => {
    const context = await requireAdmin(request, db, config, "admins.manage");
    verifyCsrf(request, context, config);
    const body = parseWith(adminCreateSchema, request.body);
    const passwordError = validatePassword(body.password, 12);
    if (passwordError) throw new HttpError(400, "WEAK_PASSWORD", passwordError);
    const existing = await db.one<{ id: string }>(
      "SELECT id FROM admin_users WHERE username = $1",
      [body.email],
    );
    if (existing) throw new HttpError(409, "ADMIN_EXISTS", "That administrator already exists");
    const { hash, salt } = await hashPassword(body.password);
    const unusedSecret = generateTotpSecret();
    const id = randomUUID();
    await db.query(
      `INSERT INTO admin_users
         (id, username, display_name, password_hash, password_salt, totp_secret_enc, role)
       VALUES ($1,$2,$3,$4,$5,$6,$7)`,
      [
        id,
        body.email,
        body.displayName,
        hash,
        salt,
        encryptTotpSecret(unusedSecret, config),
        body.role,
      ],
    );
    await audit(db, context.admin.id, "admin.created", request, "admin", id, {
      email: body.email,
      role: body.role,
    });
    return reply.code(201).send({
      id,
      email: body.email,
      username: body.email,
      message: "Administrator created. Give them this email and password through a secure channel.",
    });
  });

  app.patch("/v1/admin/admins/:id", async (request, reply) => {
    const context = await requireAdmin(request, db, config, "admins.manage");
    verifyCsrf(request, context, config);
    const { id } = request.params as { id: string };
    const body = parseWith(adminPatchSchema, request.body);
    const target = await db.one<Record<string, unknown>>(
      "SELECT * FROM admin_users WHERE id = $1",
      [id],
    );
    if (!target) throw new HttpError(404, "ADMIN_NOT_FOUND", "Administrator not found");
    if (id === context.admin.id && body.active === false) {
      throw new HttpError(409, "SELF_DEACTIVATE", "You cannot deactivate your own account");
    }
    const nextRole = body.role ?? (target.role as string);
    const nextActive = body.active ?? Boolean(target.active);
    await withSuperAdminGuard(db, async (client) => {
      if (
        target.role === "super_admin" &&
        target.active &&
        (nextRole !== "super_admin" || !nextActive) &&
        (await activeSuperAdminCount(client)) <= 1
      ) {
        throw new HttpError(409, "LAST_SUPER_ADMIN", "At least one active super admin is required");
      }
      await client.query("UPDATE admin_users SET role = $1, active = $2 WHERE id = $3", [
        nextRole,
        nextActive,
        id,
      ]);
    });
    if (body.active === false) {
      await db.query(
        "UPDATE admin_sessions SET revoked_at = now() WHERE admin_id = $1 AND revoked_at IS NULL",
        [id],
      );
    }
    await audit(db, context.admin.id, "admin.updated", request, "admin", id, {
      role: nextRole,
      active: nextActive,
    });
    return reply.send({ ok: true });
  });

  app.delete("/v1/admin/admins/:id", async (request, reply) => {
    const context = await requireAdmin(request, db, config, "admins.manage");
    verifyCsrf(request, context, config);
    const { id } = request.params as { id: string };
    if (id === context.admin.id) {
      throw new HttpError(409, "SELF_DELETE", "You cannot delete your own account");
    }
    const target = await db.one<Record<string, unknown>>(
      "SELECT id, username, role, active FROM admin_users WHERE id = $1",
      [id],
    );
    if (!target) throw new HttpError(404, "ADMIN_NOT_FOUND", "Administrator not found");
    await withSuperAdminGuard(db, async (client) => {
      if (
        target.role === "super_admin" &&
        target.active &&
        (await activeSuperAdminCount(client)) <= 1
      ) {
        throw new HttpError(409, "LAST_SUPER_ADMIN", "At least one active super admin is required");
      }
      // Sessions cascade and historical rows are detached by the schema, so
      // the audit trail survives while the account, password hash and TOTP
      // secret do not.
      await client.query("DELETE FROM admin_users WHERE id = $1", [id]);
    });
    await audit(db, context.admin.id, "admin.deleted", request, "admin", id, {
      username: target.username,
      role: target.role,
    });
    return reply.send({ ok: true });
  });

}
