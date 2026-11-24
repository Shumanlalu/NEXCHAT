import { randomUUID } from "node:crypto";
import type { FastifyInstance } from "fastify";
import type { AppConfig } from "../config";
import type { Database } from "../db";
import { HttpError, requireAuth, type DeviceRow, type UserRow } from "../http";
import { generateNexId } from "../security/ids";
import {
  hashPassword,
  usesLegacyPasswordHash,
  validatePassword,
  verifyPassword,
} from "../security/passwords";
import { hashToken, type TokenService } from "../security/tokens";
import { loginSchema, parseWith, refreshSchema, registerSchema, type deviceSchema } from "../validation";
import type { z } from "zod";
import type { WsHub } from "../ws/hub";

type DeviceInput = Omit<z.infer<typeof deviceSchema>, "platform"> & { platform?: string };

export interface AuthDeps {
  db: Database;
  tokens: TokenService;
  config: AppConfig;
  hub: WsHub;
}

const MIN_SIGNAL_DEVICE_ID = 1;
const MAX_SIGNAL_DEVICE_ID = 127;

function isValidSignalDeviceId(id: number): boolean {
  return Number.isInteger(id) && id >= MIN_SIGNAL_DEVICE_ID && id <= MAX_SIGNAL_DEVICE_ID;
}

/**
 * libsignal only accepts device IDs 1..127. Older Android builds generated
 * 32-bit IDs, so normalize anything outside that range (or colliding with a
 * sibling device) to the lowest free value before it is stored.
 */
async function resolveSignalDeviceId(
  db: Database,
  userId: string,
  requested: number,
  ownDeviceId: string,
): Promise<number> {
  const rows = await db.query<{ signal_device_id: number }>(
    `SELECT signal_device_id FROM devices
     WHERE user_id = $1 AND revoked_at IS NULL AND id <> $2`,
    [userId, ownDeviceId],
  );
  const used = new Set(
    rows.map((row) => row.signal_device_id).filter(isValidSignalDeviceId),
  );
  if (isValidSignalDeviceId(requested) && !used.has(requested)) return requested;
  for (let id = MIN_SIGNAL_DEVICE_ID; id <= MAX_SIGNAL_DEVICE_ID; id += 1) {
    if (!used.has(id)) return id;
  }
  throw new HttpError(409, "NO_DEVICE_ID_AVAILABLE", "This account has too many active devices");
}

async function insertDevice(
  db: Database,
  userId: string,
  device: DeviceInput,
  allowReplace = false,
): Promise<void> {
  const signalDeviceId = await resolveSignalDeviceId(
    db,
    userId,
    device.signalDeviceId,
    device.deviceId,
  );
  const existing = await db.one<{ id: string; user_id: string }>(
    "SELECT id, user_id FROM devices WHERE id = $1",
    [device.deviceId],
  );
  if (existing && (!allowReplace || existing.user_id !== userId)) {
    throw Object.assign(new Error("device already registered"), { code: "23505" });
  }
  await db.transaction(async (client) => {
    if (existing) {
      await client.query(
        `DELETE FROM one_time_prekeys WHERE device_id = $1;
         DELETE FROM kyber_prekeys WHERE device_id = $1;
         UPDATE devices SET
           name = $2, platform = $3, registration_id = $4, signal_device_id = $5,
           identity_key = $6, signed_pre_key_id = $7, signed_pre_key_public = $8,
           signed_pre_key_signature = $9, kyber_pre_key_id = $10,
           kyber_pre_key_public = $11, kyber_pre_key_signature = $12,
           revoked_at = NULL, last_seen_at = now()
         WHERE id = $1`,
        [
          device.deviceId,
          device.name,
          device.platform ?? "android",
          device.registrationId,
          signalDeviceId,
          device.identityKey,
          device.signedPreKeyId,
          device.signedPreKeyPublic,
          device.signedPreKeySignature,
          device.kyberPreKeyId,
          device.kyberPreKeyPublic,
          device.kyberPreKeySignature,
        ],
      );
      for (const key of device.oneTimePreKeys) {
        await client.query(
          `INSERT INTO one_time_prekeys (device_id, key_id, public_key) VALUES ($1,$2,$3)
           ON CONFLICT (device_id, key_id) DO NOTHING`,
          [device.deviceId, key.id, key.publicKey],
        );
      }
      if (
        device.kyberPreKeyId != null &&
        device.kyberPreKeyPublic != null &&
        device.kyberPreKeySignature != null
      ) {
        await client.query(
          `INSERT INTO kyber_prekeys (device_id, key_id, public_key, signature)
           VALUES ($1,$2,$3,$4) ON CONFLICT (device_id, key_id) DO NOTHING`,
          [device.deviceId, device.kyberPreKeyId, device.kyberPreKeyPublic, device.kyberPreKeySignature],
        );
      }
      return;
    }
    await client.query(
      `INSERT INTO devices
        (id, user_id, name, platform, registration_id, signal_device_id, identity_key,
         signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature,
         kyber_pre_key_id, kyber_pre_key_public, kyber_pre_key_signature)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)`,
      [
        device.deviceId,
        userId,
        device.name,
        device.platform ?? "android",
        device.registrationId,
        signalDeviceId,
        device.identityKey,
        device.signedPreKeyId,
        device.signedPreKeyPublic,
        device.signedPreKeySignature,
        device.kyberPreKeyId,
        device.kyberPreKeyPublic,
        device.kyberPreKeySignature,
      ],
    );
    for (const key of device.oneTimePreKeys) {
      await client.query(
        `INSERT INTO one_time_prekeys (device_id, key_id, public_key) VALUES ($1,$2,$3)
         ON CONFLICT (device_id, key_id) DO NOTHING`,
        [device.deviceId, key.id, key.publicKey],
      );
    }
    if (
      device.kyberPreKeyId != null &&
      device.kyberPreKeyPublic != null &&
      device.kyberPreKeySignature != null
    ) {
      await client.query(
        `INSERT INTO kyber_prekeys (device_id, key_id, public_key, signature)
         VALUES ($1,$2,$3,$4) ON CONFLICT (device_id, key_id) DO NOTHING`,
        [device.deviceId, device.kyberPreKeyId, device.kyberPreKeyPublic, device.kyberPreKeySignature],
      );
    }
  });
}

async function issueSession(
  db: Database,
  tokens: TokenService,
  user: UserRow,
  device: DeviceRow,
): Promise<{ token: string; refreshToken: string }> {
  const token = await tokens.issueAccessToken(user.id, device.id);
  const refresh = tokens.createRefreshToken();
  await db.query(
    `INSERT INTO refresh_tokens (id, user_id, device_id, token_hash, expires_at)
     VALUES ($1,$2,$3,$4,$5)`,
    [randomUUID(), user.id, device.id, refresh.hash, tokens.refreshExpiry()],
  );
  return { token, refreshToken: refresh.token };
}

function publicProfile(user: UserRow) {
  return {
    id: user.id,
    nexId: user.nex_id,
    username: user.username,
    displayName: user.display_name,
    avatar: user.avatar,
    createdAt: new Date(user.created_at).toISOString(),
  };
}

export async function registerAuthRoutes(app: FastifyInstance, deps: AuthDeps): Promise<void> {
  const { db, tokens, config, hub } = deps;

  app.post("/v1/auth/register", {
    config: { rateLimit: { max: 5, timeWindow: "10 minutes" } },
  }, async (request, reply) => {
    const body = parseWith(registerSchema, request.body);
    const passwordError = validatePassword(body.password);
    if (passwordError) throw new HttpError(400, "WEAK_PASSWORD", passwordError);

    const existing = await db.one<{ id: string }>(
      "SELECT id FROM users WHERE username = $1",
      [body.username],
    );
    if (existing) throw new HttpError(409, "USERNAME_TAKEN", "That username is already in use");

    const { hash, salt } = await hashPassword(body.password);
    const userId = randomUUID();

    let insertedUser: UserRow | null = null;
    for (let attempt = 0; attempt < 8 && !insertedUser; attempt += 1) {
      const nexId = generateNexId();
      try {
        insertedUser = await db.one<UserRow>(
          `INSERT INTO users (id, nex_id, username, display_name, password_hash, password_salt)
           VALUES ($1,$2,$3,$4,$5,$6)
           RETURNING *`,
          [userId, nexId, body.username, body.displayName, hash, salt],
        );
      } catch (error) {
        const code = (error as { code?: string }).code;
        if (code === "23505") {
          const taken = await db.one<{ id: string }>(
            "SELECT id FROM users WHERE username = $1",
            [body.username],
          );
          if (taken) throw new HttpError(409, "USERNAME_TAKEN", "That username is already in use");
          continue;
        }
        throw error;
      }
    }
    if (!insertedUser) {
      throw new HttpError(503, "ID_ALLOCATION_FAILED", "Could not allocate a unique NEX ID");
    }

    try {
      await insertDevice(db, insertedUser.id, body.device);
    } catch (error) {
      await db.query("DELETE FROM users WHERE id = $1", [insertedUser.id]).catch(() => undefined);
      const code = (error as { code?: string }).code;
      if (code === "23505") {
        throw new HttpError(409, "DEVICE_ALREADY_REGISTERED", "That device is already registered");
      }
      throw error;
    }

    const device = await db.one<DeviceRow>("SELECT * FROM devices WHERE id = $1", [body.device.deviceId]);
    if (!device) throw new HttpError(500, "REGISTRATION_FAILED", "Device registration failed");
    const session = await issueSession(db, tokens, insertedUser, device);

    return reply.code(201).send({
      ...session,
      user: publicProfile(insertedUser),
      device: {
        id: device.id,
        name: device.name,
        platform: device.platform,
        signalDeviceId: device.signal_device_id,
      },
    });
  });

  app.post("/v1/auth/login", {
    config: { rateLimit: { max: 10, timeWindow: "10 minutes" } },
  }, async (request, reply) => {
    const body = parseWith(loginSchema, request.body);
    const user = await db.one<UserRow>(
      "SELECT * FROM users WHERE username = $1 AND deleted_at IS NULL",
      [body.username],
    );
    if (!user) {
      // Perform a dummy derivation to reduce the username-enumeration timing signal.
      await hashPassword(body.password);
      throw new HttpError(401, "INVALID_CREDENTIALS", "Incorrect username or password");
    }
    if (user.suspended_at) {
      throw new HttpError(403, "ACCOUNT_SUSPENDED", "This account is suspended");
    }
    const valid = await verifyPassword(body.password, user.password_hash, user.password_salt);
    if (!valid) throw new HttpError(401, "INVALID_CREDENTIALS", "Incorrect username or password");
    if (usesLegacyPasswordHash(user.password_hash, user.password_salt)) {
      const upgraded = await hashPassword(body.password);
      await db.query("UPDATE users SET password_hash = $1, password_salt = $2 WHERE id = $3", [
        upgraded.hash,
        upgraded.salt,
        user.id,
      ]);
    }

    try {
      await insertDevice(db, user.id, body.device, true);
    } catch (error) {
      if ((error as { code?: string }).code === "23505") {
        throw new HttpError(409, "DEVICE_ALREADY_REGISTERED", "That device is already registered");
      }
      throw error;
    }

    const device = await db.one<DeviceRow>("SELECT * FROM devices WHERE id = $1", [body.device.deviceId]);
    if (!device) throw new HttpError(500, "LOGIN_FAILED", "Device registration failed");
    const session = await issueSession(db, tokens, user, device);

    return reply.send({
      ...session,
      user: publicProfile(user),
      device: {
        id: device.id,
        name: device.name,
        platform: device.platform,
        signalDeviceId: device.signal_device_id,
      },
    });
  });

  app.post("/v1/auth/refresh", async (request, reply) => {
    const body = parseWith(refreshSchema, request.body);
    const tokenHash = hashToken(body.refreshToken);
    const row = await db.one<{
      id: string;
      user_id: string;
      device_id: string;
      expires_at: Date | string;
      revoked_at: Date | string | null;
    }>("SELECT * FROM refresh_tokens WHERE token_hash = $1", [tokenHash]);
    if (!row || row.revoked_at || new Date(row.expires_at).getTime() <= Date.now()) {
      throw new HttpError(401, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
    }

    const user = await db.one<UserRow>(
      "SELECT * FROM users WHERE id = $1 AND deleted_at IS NULL",
      [row.user_id],
    );
    if (user?.suspended_at) {
      throw new HttpError(403, "ACCOUNT_SUSPENDED", "This account is suspended");
    }
    const device = await db.one<DeviceRow>(
      "SELECT * FROM devices WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL",
      [row.device_id, row.user_id],
    );
    if (!user || !device) {
      throw new HttpError(401, "SESSION_REVOKED", "This session has been revoked");
    }

    await db.query("UPDATE refresh_tokens SET revoked_at = now() WHERE id = $1", [row.id]);
    const session = await issueSession(db, tokens, user, device);
    return reply.send(session);
  });

  app.post("/v1/auth/logout", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const body = request.body as { refreshToken?: unknown } | undefined;
    if (body && typeof body.refreshToken === "string") {
      await db.query(
        "UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = $1 AND user_id = $2",
        [hashToken(body.refreshToken), auth.user.id],
      );
    }
    // The Android client wipes its local Signal keys when signing out. Revoke
    // this device as well so senders do not keep encrypting to unreachable keys.
    await db.query(
      `UPDATE refresh_tokens SET revoked_at = now()
       WHERE device_id = $1 AND user_id = $2 AND revoked_at IS NULL`,
      [auth.device.id, auth.user.id],
    );
    await db.query(
      "UPDATE devices SET revoked_at = now() WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL",
      [auth.device.id, auth.user.id],
    );
    await db.query("DELETE FROM one_time_prekeys WHERE device_id = $1", [auth.device.id]);
    await db.query("DELETE FROM kyber_prekeys WHERE device_id = $1", [auth.device.id]);
    await db.query("DELETE FROM envelopes WHERE recipient_device_id = $1", [auth.device.id]);
    hub.closeDevice(auth.device.id);
    return reply.send({ ok: true });
  });

  app.delete("/v1/account", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const body = request.body as { password?: unknown } | undefined;
    if (!body || typeof body.password !== "string") {
      throw new HttpError(400, "PASSWORD_REQUIRED", "Password confirmation is required");
    }
    const valid = await verifyPassword(
      body.password,
      auth.user.password_hash,
      auth.user.password_salt,
    );
    if (!valid) throw new HttpError(401, "INVALID_CREDENTIALS", "Incorrect password");

    const userDevices = await db.query<{ id: string }>(
      "SELECT id FROM devices WHERE user_id = $1",
      [auth.user.id],
    );
    await db.query("DELETE FROM users WHERE id = $1", [auth.user.id]);
    for (const device of userDevices) hub.closeDevice(device.id);
    return reply.send({ ok: true });
  });

  app.post("/v1/auth/check-username", async (request, reply) => {
    const body = request.body as { username?: unknown };
    const schema = registerSchema.shape.username;
    const username = parseWith(schema, body?.username);
    const existing = await db.one<{ id: string }>(
      "SELECT id FROM users WHERE username = $1",
      [username],
    );
    return reply.send({ available: !existing });
  });
}

export { insertDevice, issueSession, publicProfile, resolveSignalDeviceId };
