import type { FastifyInstance } from "fastify";
import type { AppConfig } from "../config";
import type { Database } from "../db";
import { HttpError, requireAuth, type DeviceRow, type UserRow } from "../http";
import { publicUser } from "../security/ids";
import type { TokenService } from "../security/tokens";
import { parseWith, rotateKeysSchema, updateProfileSchema } from "../validation";

interface Deps {
  db: Database;
  tokens: TokenService;
  config: AppConfig;
}

async function getTargetUser(db: Database, nexId: string): Promise<UserRow> {
  const user = await db.one<UserRow>(
    "SELECT * FROM users WHERE nex_id = $1 AND deleted_at IS NULL",
    [nexId],
  );
  if (!user) throw new HttpError(404, "USER_NOT_FOUND", "No NEXCHAT user has that NEX ID");
  return user;
}

export async function registerUserRoutes(app: FastifyInstance, deps: Deps): Promise<void> {
  const { db, tokens } = deps;

  app.get("/v1/me", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    return reply.send({
      user: publicUser(auth.user),
      device: {
        id: auth.device.id,
        name: auth.device.name,
        platform: auth.device.platform,
        signalDeviceId: auth.device.signal_device_id,
      },
    });
  });

  app.patch("/v1/me/privacy", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const body = request.body as { sharePresence?: unknown };
    if (typeof body?.sharePresence !== "boolean") {
      throw new HttpError(400, "INVALID_PRIVACY_SETTING", "sharePresence must be a boolean");
    }
    const updated = await db.one<UserRow>(
      "UPDATE users SET share_presence = $1 WHERE id = $2 RETURNING *",
      [body.sharePresence, auth.user.id],
    );
    if (!updated) throw new HttpError(404, "USER_NOT_FOUND", "User no longer exists");
    return reply.send({ sharePresence: updated.share_presence });
  });

  app.patch("/v1/me", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const body = parseWith(updateProfileSchema, request.body);
    if (body.displayName === undefined && body.avatar === undefined) {
      throw new HttpError(400, "EMPTY_UPDATE", "No profile fields were supplied");
    }
    const displayName = body.displayName ?? auth.user.display_name;
    const avatar = body.avatar === undefined ? auth.user.avatar : body.avatar;
    const updated = await db.one<UserRow>(
      `UPDATE users SET display_name = $1, avatar = $2 WHERE id = $3 RETURNING *`,
      [displayName, avatar, auth.user.id],
    );
    if (!updated) throw new HttpError(404, "USER_NOT_FOUND", "User no longer exists");
    return reply.send({ user: publicUser(updated) });
  });

  app.get("/v1/users/search", async (request, reply) => {
    await requireAuth(request, db, tokens);
    const query = (request.query as { q?: unknown }).q;
    if (typeof query !== "string" || query.trim().length < 2 || query.trim().length > 64) {
      throw new HttpError(400, "INVALID_QUERY", "Search query must be 2-64 characters");
    }
    const normalized = query.trim();
    const looksLikeNex = normalized.toUpperCase().startsWith("NX-");
    const rows = await db.query<UserRow>(
      looksLikeNex
        ? "SELECT * FROM users WHERE nex_id = $1 AND deleted_at IS NULL LIMIT 1"
        : `SELECT * FROM users
           WHERE deleted_at IS NULL AND (username LIKE $1 ESCAPE '\\' OR display_name ILIKE $2 ESCAPE '\\')
           ORDER BY username LIMIT 20`,
      looksLikeNex
        ? [normalized.toUpperCase()]
        : [
            `${normalized.toLowerCase().replace(/[%_\\]/g, "\\$&")}%`,
            `%${normalized.replace(/[%_\\]/g, "\\$&")}%`,
          ],
    );
    return reply.send({ users: rows.map(publicUser) });
  });

  app.get("/v1/users/:nexId", async (request, reply) => {
    await requireAuth(request, db, tokens);
    const { nexId } = request.params as { nexId: string };
    const user = await getTargetUser(db, nexId);
    return reply.send({ user: publicUser(user) });
  });

  app.get("/v1/devices", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const rows = await db.query<DeviceRow>(
      `SELECT id, user_id, name, platform, registration_id, signal_device_id, identity_key,
              signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature,
              kyber_pre_key_id, kyber_pre_key_public, kyber_pre_key_signature,
              created_at, last_seen_at, revoked_at
       FROM devices WHERE user_id = $1 AND revoked_at IS NULL ORDER BY created_at`,
      [auth.user.id],
    );
    return reply.send({
      devices: rows.map((device) => ({
        id: device.id,
        name: device.name,
        platform: device.platform,
        createdAt: new Date(device.created_at).toISOString(),
        current: device.id === auth.device.id,
      })),
    });
  });

  app.delete("/v1/devices/:deviceId", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const { deviceId } = request.params as { deviceId: string };
    if (!/^[0-9a-f-]{36}$/i.test(deviceId)) {
      throw new HttpError(400, "INVALID_DEVICE", "Invalid device identifier");
    }
    const device = await db.one<DeviceRow>(
      "SELECT * FROM devices WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL",
      [deviceId, auth.user.id],
    );
    if (!device) throw new HttpError(404, "DEVICE_NOT_FOUND", "Device not found");
    await db.query("UPDATE devices SET revoked_at = now() WHERE id = $1", [deviceId]);
    await db.query(
      "UPDATE refresh_tokens SET revoked_at = now() WHERE device_id = $1 AND revoked_at IS NULL",
      [deviceId],
    );
    await db.query("DELETE FROM one_time_prekeys WHERE device_id = $1", [deviceId]);
    await db.query("DELETE FROM kyber_prekeys WHERE device_id = $1", [deviceId]);
    await db.query("DELETE FROM envelopes WHERE recipient_device_id = $1", [deviceId]);
    return reply.send({ ok: true });
  });

  app.get("/v1/devices/keys/count", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const oneTime = await db.one<{ count: string | number }>(
      "SELECT COUNT(*) AS count FROM one_time_prekeys WHERE device_id = $1 AND used_at IS NULL",
      [auth.device.id],
    );
    // Kyber prekeys are distributed as a reusable last-resort key so a fresh
    // sender can always complete PQXDH even while the recipient is offline.
    // Count the currently published key rather than filtering on used_at.
    const kyber = await db.one<{ count: string | number }>(
      `SELECT COUNT(*) AS count
       FROM kyber_prekeys k
       JOIN devices d ON d.id = k.device_id AND d.kyber_pre_key_id = k.key_id
       WHERE k.device_id = $1`,
      [auth.device.id],
    );
    return reply.send({
      oneTimePreKeys: Number(oneTime?.count ?? 0),
      kyberPreKeys: Number(kyber?.count ?? 0),
    });
  });

  app.post("/v1/devices/keys", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const body = parseWith(rotateKeysSchema, request.body);

    await db.transaction(async (client) => {
      await client.query(
        `UPDATE devices
         SET signed_pre_key_id = $1, signed_pre_key_public = $2,
             signed_pre_key_signature = $3, kyber_pre_key_id = $4,
             kyber_pre_key_public = $5, kyber_pre_key_signature = $6
         WHERE id = $7 AND user_id = $8 AND revoked_at IS NULL`,
        [
          body.signedPreKeyId,
          body.signedPreKeyPublic,
          body.signedPreKeySignature,
          body.kyberPreKeyId,
          body.kyberPreKeyPublic,
          body.kyberPreKeySignature,
          auth.device.id,
          auth.user.id,
        ],
      );
      for (const key of body.oneTimePreKeys) {
        await client.query(
          `INSERT INTO one_time_prekeys (device_id, key_id, public_key, used_at)
           VALUES ($1,$2,$3,NULL)
           ON CONFLICT (device_id, key_id) DO NOTHING`,
          [auth.device.id, key.id, key.publicKey],
        );
      }
      if (
        body.kyberPreKeyId != null &&
        body.kyberPreKeyPublic != null &&
        body.kyberPreKeySignature != null
      ) {
        // The device row points at exactly one current last-resort Kyber key.
        // Remove superseded rows so a bundle can never be built from an old key.
        await client.query(
          "DELETE FROM kyber_prekeys WHERE device_id = $1 AND key_id <> $2",
          [auth.device.id, body.kyberPreKeyId],
        );
        await client.query(
          `INSERT INTO kyber_prekeys (device_id, key_id, public_key, signature)
           VALUES ($1,$2,$3,$4)
           ON CONFLICT (device_id, key_id) DO UPDATE
             SET public_key = EXCLUDED.public_key, signature = EXCLUDED.signature, used_at = NULL`,
          [auth.device.id, body.kyberPreKeyId, body.kyberPreKeyPublic, body.kyberPreKeySignature],
        );
      } else {
        await client.query("DELETE FROM kyber_prekeys WHERE device_id = $1", [auth.device.id]);
      }
    });

    return reply.send({ ok: true });
  });
}

function parseDeviceFilter(raw: unknown): Set<number> | null {
  if (raw === undefined) return null;
  if (typeof raw !== "string" || raw.trim() === "") {
    throw new HttpError(400, "INVALID_DEVICE_FILTER", "devices must be a comma-separated list");
  }
  const parts = raw.split(",").map((value) => Number(value.trim()));
  if (
    parts.length === 0 ||
    parts.length > 100 ||
    parts.some((id) => !Number.isInteger(id) || id < 1 || id > 2_147_483_647)
  ) {
    throw new HttpError(400, "INVALID_DEVICE_FILTER", "devices must contain valid signal device IDs");
  }
  return new Set(parts);
}

export async function registerKeyRoutes(app: FastifyInstance, deps: Deps): Promise<void> {
  const { db, tokens } = deps;

  app.get("/v1/keys/:nexId/devices", async (request, reply) => {
    await requireAuth(request, db, tokens);
    const { nexId } = request.params as { nexId: string };
    const target = await getTargetUser(db, nexId);
    const devices = await db.query<DeviceRow>(
      `SELECT * FROM devices WHERE user_id = $1 AND revoked_at IS NULL ORDER BY created_at`,
      [target.id],
    );
    return reply.send({
      user: publicUser(target),
      devices: devices.map((device) => ({
        deviceId: device.id,
        signalDeviceId: device.signal_device_id,
        // Public identity key only. Clients compare it with the identity bound
        // to an existing session so a reinstalled device is detected before a
        // message is encrypted to a stale ratchet.
        identityKey: device.identity_key,
      })),
    });
  });

  app.get("/v1/keys/:nexId", {
    config: { rateLimit: { max: 60, timeWindow: "1 minute" } },
  }, async (request, reply) => {
    await requireAuth(request, db, tokens);
    const { nexId } = request.params as { nexId: string };
    const target = await getTargetUser(db, nexId);
    const filter = parseDeviceFilter((request.query as { devices?: unknown }).devices);
    const activeDevices = await db.query<DeviceRow>(
      `SELECT * FROM devices WHERE user_id = $1 AND revoked_at IS NULL ORDER BY created_at`,
      [target.id],
    );
    if (activeDevices.length === 0) {
      throw new HttpError(409, "NO_ACTIVE_DEVICES", "This user has no active devices");
    }
    const devices = filter
      ? activeDevices.filter((device) => filter.has(device.signal_device_id))
      : activeDevices;

    const bundles: Array<Record<string, unknown>> = [];
    const unavailableDeviceIds: string[] = [];

    for (const device of devices) {
      const bundle = await db.transaction(async (client) => {
        // Serialize prekey distribution for this device. The Kyber key is a
        // reusable last resort, so it is never marked used here. The one-time
        // EC prekey is optional in X3DH and is consumed only when the whole
        // bundle can actually be returned.
        const locked = await client.query<DeviceRow>(
          "SELECT * FROM devices WHERE id = $1 AND revoked_at IS NULL FOR UPDATE",
          [device.id],
        );
        const current = locked.rows[0];
        if (!current || current.kyber_pre_key_id == null) return null;

        const kyberRow = await client.query<{
          public_key: string;
          signature: string;
        }>(
          `SELECT public_key, signature FROM kyber_prekeys
           WHERE device_id = $1 AND key_id = $2`,
          [current.id, current.kyber_pre_key_id],
        );
        const kyber = kyberRow.rows[0];
        if (!kyber) return null;

        const preKeyRow = await client.query<{
          key_id: number;
          public_key: string;
        }>(
          `SELECT key_id, public_key FROM one_time_prekeys
           WHERE device_id = $1 AND used_at IS NULL
           ORDER BY key_id ASC LIMIT 1 FOR UPDATE`,
          [current.id],
        );
        const preKey = preKeyRow.rows[0];
        if (preKey) {
          await client.query(
            "UPDATE one_time_prekeys SET used_at = now() WHERE device_id = $1 AND key_id = $2",
            [current.id, preKey.key_id],
          );
        }

        return {
          deviceId: current.id,
          signalDeviceId: current.signal_device_id,
          registrationId: current.registration_id,
          identityKey: current.identity_key,
          signedPreKeyId: current.signed_pre_key_id,
          signedPreKeyPublic: current.signed_pre_key_public,
          signedPreKeySignature: current.signed_pre_key_signature,
          oneTimePreKeyId: preKey?.key_id ?? null,
          oneTimePreKeyPublic: preKey?.public_key ?? null,
          kyberPreKeyId: current.kyber_pre_key_id,
          kyberPreKeyPublic: kyber.public_key,
          kyberPreKeySignature: kyber.signature,
        };
      });
      if (bundle) bundles.push(bundle);
      else unavailableDeviceIds.push(device.id);
    }

    if (bundles.length === 0) {
      throw new HttpError(
        409,
        "PREKEYS_EXHAUSTED",
        "The recipient must reopen NEXCHAT to publish encryption keys",
      );
    }

    return reply.send({
      user: publicUser(target),
      devices: bundles,
      unavailableDeviceIds,
    });
  });
}
