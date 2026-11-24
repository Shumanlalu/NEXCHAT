import { randomUUID } from "node:crypto";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { z } from "zod";
import type { AppConfig } from "../config";
import type { Database } from "../db";
import { HttpError } from "../http";
import {
  hashPassword,
  usesLegacyPasswordHash,
  validatePassword,
  verifyPassword,
} from "../security/passwords";
import { parseWith } from "../validation";
import {
  ADMIN_MAX_SESSION_SECONDS,
  ADMIN_REMEMBER_SECONDS,
  audit,
  csrfFor,
  encryptTotpSecret,
  generateTotpSecret,
  hashSessionToken,
  newSessionToken,
  parseCookies,
  publicAdmin,
  requireAdmin,
  secureEqual,
  serializeAdminCookie,
  verifyCsrf,
  type AdminRole,
  type AdminRow,
} from "../admin/security";

interface Deps {
  db: Database;
  config: AppConfig;
}

// New admin accounts are identified by email. The value is stored in the
// existing `username` column so no schema migration is needed; lookups are
// lowercase and case-insensitive. Legacy username-only rows keep signing in
// so an existing deployment cannot be locked out by this change.
const email = z
  .string()
  .trim()
  .transform((value) => value.toLowerCase())
  .pipe(z.string().max(254).email("Enter a valid email address"));

const loginIdentifier = z
  .string()
  .trim()
  .transform((value) => value.toLowerCase())
  .refine(
    (value) => z.string().email().safeParse(value).success || /^[a-z0-9_.-]{3,32}$/.test(value),
    "Enter a valid email address",
  );

const loginBody = z.object({
  email: loginIdentifier,
  password: z.string().min(1).max(128),
  remember: z.boolean().default(false),
});

const setupBody = z
  .object({
    email,
    displayName: z.string().trim().min(1).max(60),
    password: z.string().min(12).max(128),
    confirmPassword: z.string().min(12).max(128),
  })
  .refine((value) => value.password === value.confirmPassword, {
    path: ["confirmPassword"],
    message: "Passwords do not match",
  });

const changePasswordBody = z
  .object({
    currentPassword: z.string().min(1).max(128),
    newPassword: z.string().min(12).max(128),
    confirmPassword: z.string().min(12).max(128),
  })
  .refine((value) => value.newPassword === value.confirmPassword, {
    path: ["confirmPassword"],
    message: "Passwords do not match",
  });

const resetRequestBody = z.object({ email: loginIdentifier });

const resetConfirmBody = z
  .object({
    token: z.string().trim().min(20).max(200),
    email: loginIdentifier,
    newPassword: z.string().min(12).max(128),
    confirmPassword: z.string().min(12).max(128),
  })
  .refine((value) => value.newPassword === value.confirmPassword, {
    path: ["confirmPassword"],
    message: "Passwords do not match",
  });

interface AdminCreateInput {
  email: string;
  displayName: string;
  password: string;
  role: AdminRole;
}

async function adminCount(db: Database): Promise<number> {
  const row = await db.one<{ count: string }>("SELECT count(*)::text AS count FROM admin_users");
  return Number(row?.count ?? 0);
}

async function insertAdmin(
  db: Database,
  config: AppConfig,
  input: AdminCreateInput,
): Promise<string> {
  const { hash, salt } = await hashPassword(input.password);
  // The column stays NOT NULL for schema compatibility, but sign-in no
  // longer requires a second factor.
  const unusedSecret = generateTotpSecret();
  const id = randomUUID();
  await db.query(
    `INSERT INTO admin_users
       (id, username, display_name, password_hash, password_salt, totp_secret_enc, role)
     VALUES ($1,$2,$3,$4,$5,$6,$7)`,
    [
      id,
      input.email,
      input.displayName,
      hash,
      salt,
      encryptTotpSecret(unusedSecret, config),
      input.role,
    ],
  );
  return id;
}

function setSessionCookie(reply: FastifyReply, token: string, maxAgeSeconds: number): void {
  reply.header("set-cookie", serializeAdminCookie(token, maxAgeSeconds));
}

function suppliedHeaderToken(request: FastifyRequest): string | undefined {
  const supplied = request.headers["x-setup-token"];
  return Array.isArray(supplied) ? supplied[0] : supplied;
}

async function recordLockout(db: Database, admin: AdminRow): Promise<void> {
  const attempts = admin.failed_attempts + 1;
  const lockedUntil = attempts >= 5 ? new Date(Date.now() + 15 * 60 * 1000) : null;
  await db.query(
    `UPDATE admin_users SET failed_attempts = $1, locked_until = $2 WHERE id = $3`,
    [attempts, lockedUntil, admin.id],
  );
}

export async function registerAdminAuthRoutes(
  app: FastifyInstance,
  deps: Deps,
): Promise<void> {
  const { db, config } = deps;
  const sessionHours = config.adminSessionHours ?? 12;
  const sessionSeconds = Math.min(
    Math.max(sessionHours, 1) * 3600,
    ADMIN_MAX_SESSION_SECONDS,
  );

  app.get("/v1/admin/bootstrap", async (_request, reply) => {
    const count = await adminCount(db);
    return reply.send({
      setupRequired: count === 0,
      setupEnabled: count === 0 && Boolean(config.adminSetupToken),
      // There is no public administrator signup. Fresh databases use /setup;
      // later accounts are created only by an authenticated super admin.
      signupEnabled: false,
      sessionHours: sessionHours,
      rememberDays: ADMIN_REMEMBER_SECONDS / 86_400,
    });
  });

  app.post(
    "/v1/admin/setup",
    { config: { rateLimit: { max: 5, timeWindow: "15 minutes" } } },
    async (request, reply) => {
      if ((await adminCount(db)) > 0) {
        throw new HttpError(409, "SETUP_CLOSED", "An administrator already exists");
      }
      const expectedToken = config.adminSetupToken ?? "";
      if (!expectedToken) {
        throw new HttpError(503, "SETUP_DISABLED", "Administrator setup is disabled");
      }
      const token = suppliedHeaderToken(request);
      if (!token || !secureEqual(token, expectedToken)) {
        await audit(db, null, "admin.setup.rejected", request, "admin", undefined, {
          reason: "bad setup token",
        });
        throw new HttpError(403, "SETUP_TOKEN_INVALID", "The setup token is incorrect");
      }
      const body = parseWith(setupBody, request.body);
      const passwordError = validatePassword(body.password, 12);
      if (passwordError) throw new HttpError(400, "WEAK_PASSWORD", passwordError);
      const id = await insertAdmin(db, config, {
        email: body.email,
        displayName: body.displayName,
        password: body.password,
        role: "super_admin",
      });
      await audit(db, id, "admin.setup.completed", request, "admin", id, {
        email: body.email,
      });
      return reply.code(201).send({
        id,
        email: body.email,
        username: body.email,
        message: "Administrator created. Sign in with this email and your password.",
      });
    },
  );

  app.post(
    "/v1/admin/auth/login",
    { config: { rateLimit: { max: 20, timeWindow: "15 minutes" } } },
    async (request, reply) => {
      const body = parseWith(loginBody, request.body);
      const admin = await db.one<AdminRow>(
        "SELECT * FROM admin_users WHERE username = $1",
        [body.email],
      );
      // Always perform a password derivation to keep timing uniform.
      if (!admin) {
        await hashPassword(body.password);
        throw new HttpError(401, "ADMIN_LOGIN_FAILED", "Incorrect email or password");
      }
      if (admin.locked_until && new Date(admin.locked_until).getTime() > Date.now()) {
        await audit(db, admin.id, "admin.login.locked", request, "admin", admin.id);
        throw new HttpError(429, "ADMIN_LOCKED", "Too many attempts. Try again in 15 minutes");
      }
      const passwordOk = await verifyPassword(
        body.password,
        admin.password_hash,
        admin.password_salt,
      );
      if (!admin.active || !passwordOk) {
        await recordLockout(db, admin);
        await audit(db, admin.id, "admin.login.failed", request, "admin", admin.id, {
          passwordOk,
        });
        throw new HttpError(401, "ADMIN_LOGIN_FAILED", "Incorrect email or password");
      }
      if (admin.active && usesLegacyPasswordHash(admin.password_hash, admin.password_salt)) {
        const upgraded = await hashPassword(body.password);
        await db.query(
          "UPDATE admin_users SET password_hash = $1, password_salt = $2 WHERE id = $3",
          [upgraded.hash, upgraded.salt, admin.id],
        );
      }

      const token = newSessionToken();
      const sessionId = randomUUID();
      const lifetimeSeconds = body.remember ? ADMIN_REMEMBER_SECONDS : sessionSeconds;
      const expiresAt = new Date(Date.now() + lifetimeSeconds * 1000);
      await db.query(
        `INSERT INTO admin_sessions
           (id, admin_id, token_hash, expires_at, ip, user_agent)
         VALUES ($1,$2,$3,$4,$5,$6)`,
        [
          sessionId,
          admin.id,
          hashSessionToken(token),
          expiresAt,
          request.ip,
          (request.headers["user-agent"] ?? "").slice(0, 300) || null,
        ],
      );
      await db.query(
        `UPDATE admin_users SET failed_attempts = 0, locked_until = NULL, last_login_at = now()
         WHERE id = $1`,
        [admin.id],
      );
      setSessionCookie(reply, token, lifetimeSeconds);
      await audit(db, admin.id, "admin.login.succeeded", request, "admin", admin.id, {
        remember: body.remember,
      });
      return reply.send({
        admin: publicAdmin({ ...admin, last_login_at: new Date() }),
        csrfToken: csrfFor(token, config.jwtSecret),
        expiresAt: expiresAt.toISOString(),
      });
    },
  );

  // No email provider is configured for this deployment. The existing
  // ADMIN_SETUP_TOKEN proves break-glass authority and the one-time reset link
  // is displayed once on screen instead of being emailed.
  app.post(
    "/v1/admin/auth/reset/request",
    { config: { rateLimit: { max: 5, timeWindow: "15 minutes" } } },
    async (request, reply) => {
      const expectedToken = config.adminSetupToken ?? "";
      if (!expectedToken) {
        throw new HttpError(503, "SETUP_DISABLED", "Password reset is disabled");
      }
      const token = suppliedHeaderToken(request);
      if (!token || !secureEqual(token, expectedToken)) {
        await audit(db, null, "admin.password_reset.rejected", request, "admin", undefined, {
          reason: "bad setup token",
        });
        throw new HttpError(403, "SETUP_TOKEN_INVALID", "The setup token is incorrect");
      }
      const body = parseWith(resetRequestBody, request.body);
      const admin = await db.one<{ id: string; username: string; active: boolean }>(
        "SELECT id, username, active FROM admin_users WHERE username = $1",
        [body.email],
      );
      if (!admin || !admin.active) {
        throw new HttpError(404, "ADMIN_NOT_FOUND", "No active administrator uses that address");
      }
      const resetToken = newSessionToken();
      const expiresAt = new Date(Date.now() + 15 * 60 * 1000);
      await db.query(
        `INSERT INTO admin_password_resets
           (id, admin_id, token_hash, expires_at, ip)
         VALUES ($1,$2,$3,$4,$5)`,
        [randomUUID(), admin.id, hashSessionToken(resetToken), expiresAt, request.ip],
      );
      await audit(db, admin.id, "admin.password_reset.requested", request, "admin", admin.id);
      return reply.send({
        email: admin.username,
        resetToken,
        expiresAt: expiresAt.toISOString(),
        message: "Copy this one-time token now. It will not be shown again.",
      });
    },
  );

  app.post(
    "/v1/admin/auth/reset/confirm",
    { config: { rateLimit: { max: 5, timeWindow: "15 minutes" } } },
    async (request, reply) => {
      const body = parseWith(resetConfirmBody, request.body);
      const row = await db.one<{
        reset_id: string;
        admin_id: string;
        username: string;
        active: boolean;
        expires_at: Date | string;
        used_at: Date | string | null;
      }>(
        `SELECT r.id AS reset_id, r.admin_id, r.expires_at, r.used_at,
                a.username, a.active
         FROM admin_password_resets r
         JOIN admin_users a ON a.id = r.admin_id
         WHERE r.token_hash = $1`,
        [hashSessionToken(body.token)],
      );
      if (
        !row ||
        row.used_at ||
        !row.active ||
        row.username !== body.email ||
        new Date(row.expires_at).getTime() <= Date.now()
      ) {
        throw new HttpError(400, "RESET_TOKEN_INVALID", "This reset link is invalid or expired");
      }
      const passwordError = validatePassword(body.newPassword, 12);
      if (passwordError) throw new HttpError(400, "WEAK_PASSWORD", passwordError);
      const { hash, salt } = await hashPassword(body.newPassword);
      const consumed = await db.one<{ id: string }>(
        `UPDATE admin_password_resets SET used_at = now()
         WHERE id = $1 AND used_at IS NULL RETURNING id`,
        [row.reset_id],
      );
      if (!consumed) {
        throw new HttpError(409, "RESET_TOKEN_USED", "This reset link was already used");
      }
      await db.query(
        `UPDATE admin_users
         SET password_hash = $1, password_salt = $2, failed_attempts = 0, locked_until = NULL
         WHERE id = $3`,
        [hash, salt, row.admin_id],
      );
      await db.query(
        `UPDATE admin_sessions SET revoked_at = now()
         WHERE admin_id = $1 AND revoked_at IS NULL`,
        [row.admin_id],
      );
      await audit(db, row.admin_id, "admin.password_reset.completed", request, "admin", row.admin_id);
      return reply.send({ ok: true });
    },
  );

  app.post("/v1/admin/auth/logout", async (request, reply) => {
    const context = await requireAdmin(request, db, config);
    verifyCsrf(request, context, config);
    await db.query("UPDATE admin_sessions SET revoked_at = now() WHERE id = $1", [
      context.sessionId,
    ]);
    setSessionCookie(reply, "", 0);
    await audit(db, context.admin.id, "admin.logout", request, "admin", context.admin.id);
    return reply.send({ ok: true });
  });

  app.get("/v1/admin/auth/me", async (request, reply) => {
    const context = await requireAdmin(request, db, config);
    return reply.send({
      admin: publicAdmin(context.admin),
      csrfToken: context.csrfToken,
      expiresAt: context.expiresAt,
    });
  });

  app.post("/v1/admin/auth/password", async (request, reply) => {
    const context = await requireAdmin(request, db, config);
    const body = parseWith(changePasswordBody, request.body);
    verifyCsrf(request, context, config);
    const passwordOk = await verifyPassword(
      body.currentPassword,
      context.admin.password_hash,
      context.admin.password_salt,
    );
    if (!passwordOk) {
      throw new HttpError(401, "ADMIN_PASSWORD_INCORRECT", "Current password is incorrect");
    }
    const passwordError = validatePassword(body.newPassword, 12);
    if (passwordError) throw new HttpError(400, "WEAK_PASSWORD", passwordError);
    const { hash, salt } = await hashPassword(body.newPassword);
    await db.query("UPDATE admin_users SET password_hash = $1, password_salt = $2 WHERE id = $3", [
      hash,
      salt,
      context.admin.id,
    ]);
    await db.query(
      `UPDATE admin_sessions SET revoked_at = now()
       WHERE admin_id = $1 AND id <> $2 AND revoked_at IS NULL`,
      [context.admin.id, context.sessionId],
    );
    await audit(db, context.admin.id, "admin.password.changed", request, "admin", context.admin.id);
    return reply.send({ ok: true });
  });

  // Remove an expired cookie left over from an old session.
  app.delete("/v1/admin/auth/session", async (request, reply) => {
    const cookie = parseCookies(request.headers.cookie);
    if (cookie.nexchat_admin_session) setSessionCookie(reply, "", 0);
    return reply.send({ ok: true });
  });
}

export type AdminAuthRequest = FastifyRequest;
