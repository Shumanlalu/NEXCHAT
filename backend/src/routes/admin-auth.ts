import { randomUUID } from "node:crypto";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { z } from "zod";
import type { AppConfig } from "../config";
import type { Database } from "../db";
import { HttpError } from "../http";
import { hashPassword, validatePassword, verifyPassword } from "../security/passwords";
import { parseWith } from "../validation";
import { totpQrDataUri } from "../admin/qr";
import {
  ADMIN_SESSION_SECONDS,
  audit,
  csrfFor,
  decryptTotpSecret,
  encryptTotpSecret,
  generateTotpSecret,
  hashSessionToken,
  newSessionToken,
  otpauthUri,
  parseCookies,
  publicAdmin,
  requireAdmin,
  secureEqual,
  serializeAdminCookie,
  verifyCsrf,
  verifyTotp,
  type AdminRow,
} from "../admin/security";

interface Deps {
  db: Database;
  config: AppConfig;
}

const username = z
  .string()
  .trim()
  .transform((value) => value.toLowerCase())
  .pipe(z.string().regex(/^[a-z0-9_.-]{3,32}$/, "Invalid administrator username"));

const loginBody = z.object({
  username,
  password: z.string().min(1).max(128),
  code: z.string().trim().regex(/^\d{6}$/, "Enter the six-digit authenticator code"),
});

const setupBody = z.object({
  username,
  displayName: z.string().trim().min(1).max(60),
  password: z.string().min(12).max(128),
});

const changePasswordBody = z.object({
  currentPassword: z.string().min(1).max(128),
  newPassword: z.string().min(12).max(128),
  code: z.string().trim().regex(/^\d{6}$/),
});

async function adminCount(db: Database): Promise<number> {
  const row = await db.one<{ count: string }>("SELECT count(*)::text AS count FROM admin_users");
  return Number(row?.count ?? 0);
}

function setSessionCookie(reply: FastifyReply, token: string, maxAgeSeconds: number): void {
  reply.header("set-cookie", serializeAdminCookie(token, maxAgeSeconds));
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
    ADMIN_SESSION_SECONDS * 6,
  );

  app.get("/v1/admin/bootstrap", async (_request, reply) => {
    const count = await adminCount(db);
    return reply.send({
      setupRequired: count === 0,
      setupEnabled: count === 0 && Boolean(config.adminSetupToken),
      // The signup page stays reachable while locked out. It is gated by the
      // same bootstrap token, so it is only advertised when that token exists.
      signupEnabled: Boolean(config.adminSetupToken),
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
      const supplied = request.headers["x-setup-token"];
      const token = Array.isArray(supplied) ? supplied[0] : supplied;
      if (!token || !secureEqual(token, expectedToken)) {
        await audit(db, null, "admin.setup.rejected", request, "admin", undefined, {
          reason: "bad setup token",
        });
        throw new HttpError(403, "SETUP_TOKEN_INVALID", "The setup token is incorrect");
      }
      const body = parseWith(setupBody, request.body);
      const passwordError = validatePassword(body.password);
      if (passwordError) throw new HttpError(400, "WEAK_PASSWORD", passwordError);
      const { hash, salt } = await hashPassword(body.password);
      const secret = generateTotpSecret();
      const id = randomUUID();
      await db.query(
        `INSERT INTO admin_users
           (id, username, display_name, password_hash, password_salt, totp_secret_enc, role)
         VALUES ($1,$2,$3,$4,$5,$6,'super_admin')`,
        [id, body.username, body.displayName, hash, salt, encryptTotpSecret(secret, config)],
      );
      await audit(db, id, "admin.setup.completed", request, "admin", id, {
        username: body.username,
      });
      const uri = otpauthUri(body.username, secret);
      return reply.code(201).send({
        id,
        username: body.username,
        totpSecret: secret,
        otpauthUri: uri,
        qrDataUrl: await totpQrDataUri(uri),
        message:
          "Add this secret to an authenticator now. It is shown only once, and sign-in requires it.",
      });
    },
  );

  // Break-glass enrollment. The normal setup route closes permanently once an
  // admin exists, which leaves a sole admin locked out if their password or
  // authenticator is lost. This route requires the same ADMIN_SETUP_TOKEN and
  // only *adds* a super admin; it never resets or disables an existing one.
  // It is rate limited and written to the audit log like every admin change.
  app.post(
    "/v1/admin/signup",
    { config: { rateLimit: { max: 5, timeWindow: "15 minutes" } } },
    async (request, reply) => {
      const expectedToken = config.adminSetupToken ?? "";
      if (!expectedToken) {
        throw new HttpError(503, "SETUP_DISABLED", "Administrator signup is disabled");
      }
      const supplied = request.headers["x-setup-token"];
      const token = Array.isArray(supplied) ? supplied[0] : supplied;
      if (!token || !secureEqual(token, expectedToken)) {
        await audit(db, null, "admin.signup.rejected", request, "admin", undefined, {
          reason: "bad setup token",
        });
        throw new HttpError(403, "SETUP_TOKEN_INVALID", "The setup token is incorrect");
      }
      const body = parseWith(setupBody, request.body);
      const passwordError = validatePassword(body.password);
      if (passwordError) throw new HttpError(400, "WEAK_PASSWORD", passwordError);
      const existing = await db.one<{ id: string }>(
        "SELECT id FROM admin_users WHERE username = $1",
        [body.username],
      );
      if (existing) throw new HttpError(409, "ADMIN_EXISTS", "That administrator already exists");

      const { hash, salt } = await hashPassword(body.password);
      const secret = generateTotpSecret();
      const id = randomUUID();
      await db.query(
        `INSERT INTO admin_users
           (id, username, display_name, password_hash, password_salt, totp_secret_enc, role)
         VALUES ($1,$2,$3,$4,$5,$6,'super_admin')`,
        [id, body.username, body.displayName, hash, salt, encryptTotpSecret(secret, config)],
      );
      await audit(db, id, "admin.signup.completed", request, "admin", id, {
        username: body.username,
      });
      const uri = otpauthUri(body.username, secret);
      return reply.code(201).send({
        id,
        username: body.username,
        totpSecret: secret,
        otpauthUri: uri,
        qrDataUrl: await totpQrDataUri(uri),
        message: "Scan the QR code now. It is shown only once.",
      });
    },
  );

  app.post(
    "/v1/admin/auth/login",
    { config: { rateLimit: { max: 10, timeWindow: "15 minutes" } } },
    async (request, reply) => {
      const body = parseWith(loginBody, request.body);
      const admin = await db.one<AdminRow>(
        "SELECT * FROM admin_users WHERE username = $1",
        [body.username],
      );
      // Always perform a password derivation to keep timing uniform.
      if (!admin) {
        await hashPassword(body.password);
        throw new HttpError(401, "ADMIN_LOGIN_FAILED", "Incorrect credentials or code");
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
      let codeOk = false;
      if (passwordOk) {
        try {
          codeOk = verifyTotp(decryptTotpSecret(admin.totp_secret_enc, config), body.code);
        } catch {
          codeOk = false;
        }
      }
      if (!admin.active || !passwordOk || !codeOk) {
        await recordLockout(db, admin);
        await audit(db, admin.id, "admin.login.failed", request, "admin", admin.id, {
          passwordOk,
          codeOk,
        });
        throw new HttpError(401, "ADMIN_LOGIN_FAILED", "Incorrect credentials or code");
      }

      const token = newSessionToken();
      const sessionId = randomUUID();
      const expiresAt = new Date(Date.now() + sessionSeconds * 1000);
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
      setSessionCookie(reply, token, sessionSeconds);
      await audit(db, admin.id, "admin.login.succeeded", request, "admin", admin.id);
      return reply.send({
        admin: publicAdmin({ ...admin, last_login_at: new Date() }),
        csrfToken: csrfFor(token, config.jwtSecret),
        expiresAt: expiresAt.toISOString(),
      });
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
    let codeOk = false;
    try {
      codeOk = verifyTotp(
        decryptTotpSecret(context.admin.totp_secret_enc, config),
        body.code,
      );
    } catch {
      codeOk = false;
    }
    if (!codeOk) throw new HttpError(401, "ADMIN_CODE_INVALID", "Authenticator code is incorrect");
    const passwordError = validatePassword(body.newPassword);
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
