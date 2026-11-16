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
    const kyber = await db.one<{ count: string | number }>(
      "SELECT COUNT(*) AS count FROM kyber_prekeys WHERE device_id = $1 AND used_at IS NULL",
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
        await client.query(
          `INSERT INTO kyber_prekeys (device_id, key_id, public_key, signature)
           VALUES ($1,$2,$3,$4) ON CONFLICT (device_id, key_id) DO NOTHING`,
          [auth.device.id, body.kyberPreKeyId, body.kyberPreKeyPublic, body.kyberPreKeySignature],
        );
      }
    });

    return reply.send({ ok: true });
  });
}

export async function registerKeyRoutes(app: FastifyInstance, deps: Deps): Promise<void> {
  const { db, tokens } = deps;

  app.get("/v1/keys/:nexId", {
    config: { rateLimit: { max: 60, timeWindow: "1 minute" } },
  }, async (request, reply) => {
    await requireAuth(request, db, tokens);
    const { nexId } = request.params as { nexId: string };
    const target = await getTargetUser(db, nexId);
    const devices = await db.query<DeviceRow>(
      `SELECT * FROM devices WHERE user_id = $1 AND revoked_at IS NULL ORDER BY created_at`,
      [target.id],
    );
    if (devices.length === 0) {
      throw new HttpError(409, "NO_ACTIVE_DEVICES", "This user has no active devices");
    }

    const bundles: Array<Record<string, unknown>> = [];
    const unavailableDeviceIds: string[] = [];

    for (const device of devices) {
      const bundle = await db.transaction(async (client) => {
        const preKeyRow = await client.query<{
          key_id: number;
          public_key: string;
        }>(
          `SELECT key_id, public_key FROM one_time_prekeys
           WHERE device_id = $1 AND used_at IS NULL
           ORDER BY key_id ASC LIMIT 1 FOR UPDATE`,
          [device.id],
        );
        const preKey = preKeyRow.rows[0];
        if (preKey) {
          await client.query(
            "UPDATE one_time_prekeys SET used_at = now() WHERE device_id = $1 AND key_id = $2",
            [device.id, preKey.key_id],
          );
        }

        const kyberRow = await client.query<{
          key_id: number;
          public_key: string;
          signature: string;
        }>(
          `SELECT key_id, public_key, signature FROM kyber_prekeys
           WHERE device_id = $1 AND used_at IS NULL
           ORDER BY key_id ASC LIMIT 1 FOR UPDATE`,
          [device.id],
        );
        const kyber = kyberRow.rows[0];
        if (kyber) {
          await client.query(
            "UPDATE kyber_prekeys SET used_at = now() WHERE device_id = $1 AND key_id = $2",
            [device.id, kyber.key_id],
          );
        }

        if (!preKey || !kyber || device.kyber_pre_key_id == null) return null;
        return {
          deviceId: device.id,
          signalDeviceId: device.signal_device_id,
          registrationId: device.registration_id,
          identityKey: device.identity_key,
          signedPreKeyId: device.signed_pre_key_id,
          signedPreKeyPublic: device.signed_pre_key_public,
          signedPreKeySignature: device.signed_pre_key_signature,
          oneTimePreKeyId: preKey.key_id,
          oneTimePreKeyPublic: preKey.public_key,
          kyberPreKeyId: kyber.key_id,
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
        "The recipient must reopen NEXCHAT to replenish encryption prekeys",
      );
    }

    return reply.send({
      user: publicUser(target),
      devices: bundles,
      unavailableDeviceIds,
    });
  });
}
