import type { FastifyRequest } from "fastify";
import type { Database } from "./db";
import type { TokenService } from "./security/tokens";

export interface UserRow {
  id: string;
  nex_id: string;
  username: string;
  display_name: string;
  password_hash: string;
  password_salt: string;
  avatar: string | null;
  share_presence: boolean;
  created_at: Date | string;
  deleted_at: Date | string | null;
  suspended_at?: Date | string | null;
  suspended_reason?: string | null;
}

export interface DeviceRow {
  id: string;
  user_id: string;
  name: string;
  platform: string;
  registration_id: number;
  signal_device_id: number;
  identity_key: string;
  signed_pre_key_id: number;
  signed_pre_key_public: string;
  signed_pre_key_signature: string;
  kyber_pre_key_id: number | null;
  kyber_pre_key_public: string | null;
  kyber_pre_key_signature: string | null;
  created_at: Date | string;
  last_seen_at: Date | string | null;
  revoked_at: Date | string | null;
}

export interface EnvelopeRow {
  id: string;
  sender_user_id: string;
  sender_device_id: string;
  recipient_user_id: string;
  recipient_device_id: string;
  group_id: string | null;
  client_id: string;
  cipher_type: number;
  ciphertext: string;
  ephemeral: boolean;
  priority: boolean;
  created_at: Date | string;
  expires_at: Date | string;
  delivered_at: Date | string | null;
}

export interface AuthContext {
  user: UserRow;
  device: DeviceRow;
}

export class HttpError extends Error {
  constructor(
    readonly statusCode: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export async function requireAuth(
  request: FastifyRequest,
  db: Database,
  tokens: TokenService,
): Promise<AuthContext> {
  const header = request.headers.authorization;
  if (!header?.startsWith("Bearer ")) {
    throw new HttpError(401, "UNAUTHORIZED", "Authentication required");
  }
  const token = header.slice("Bearer ".length).trim();
  let userId: string;
  let deviceId: string;
  try {
    const claims = await tokens.verifyAccessToken(token);
    userId = claims.sub;
    deviceId = claims.did;
  } catch {
    throw new HttpError(401, "INVALID_TOKEN", "Access token is invalid or expired");
  }

  type AuthRow = UserRow & {
    device_id: string;
    device_user_id: string;
    device_name: string;
    device_platform: string;
    device_registration_id: number;
    device_signal_device_id: number;
    device_identity_key: string;
    device_signed_pre_key_id: number;
    device_signed_pre_key_public: string;
    device_signed_pre_key_signature: string;
    device_kyber_pre_key_id: number | null;
    device_kyber_pre_key_public: string | null;
    device_kyber_pre_key_signature: string | null;
    device_created_at: Date | string;
    device_last_seen_at: Date | string | null;
    device_revoked_at: Date | string | null;
    suspended_at: Date | string | null;
    suspended_reason: string | null;
  };
  const rows = await db.query<AuthRow>(
    `SELECT u.id, u.nex_id, u.username, u.display_name, u.password_hash, u.password_salt,
            u.avatar, u.share_presence, u.created_at, u.deleted_at,
            u.suspended_at, u.suspended_reason,
            d.id AS device_id, d.user_id AS device_user_id, d.name AS device_name,
            d.platform AS device_platform, d.registration_id AS device_registration_id,
            d.signal_device_id AS device_signal_device_id,
            d.identity_key AS device_identity_key, d.signed_pre_key_id AS device_signed_pre_key_id,
            d.signed_pre_key_public AS device_signed_pre_key_public,
            d.signed_pre_key_signature AS device_signed_pre_key_signature,
            d.kyber_pre_key_id AS device_kyber_pre_key_id,
            d.kyber_pre_key_public AS device_kyber_pre_key_public,
            d.kyber_pre_key_signature AS device_kyber_pre_key_signature,
            d.created_at AS device_created_at, d.last_seen_at AS device_last_seen_at,
            d.revoked_at AS device_revoked_at
     FROM users u
     JOIN devices d ON d.user_id = u.id
     WHERE u.id = $1 AND d.id = $2 AND u.deleted_at IS NULL AND d.revoked_at IS NULL`,
    [userId, deviceId],
  );
  const row = rows[0];
  if (!row) {
    throw new HttpError(401, "SESSION_REVOKED", "This session has been revoked");
  }
  if (row.suspended_at) {
    throw new HttpError(
      403,
      "ACCOUNT_SUSPENDED",
      row.suspended_reason
        ? `This account is suspended: ${row.suspended_reason}`
        : "This account is suspended",
    );
  }

  // Presence for the admin dashboard. The conditional predicate keeps this a
  // no-op write for most requests while still refreshing at most every 2 min.
  await db
    .query(
      `UPDATE devices SET last_seen_at = now()
       WHERE id = $1 AND (last_seen_at IS NULL OR last_seen_at < now() - interval '2 minutes')`,
      [row.device_id],
    )
    .catch(() => undefined);

  return {
    user: {
      id: row.id,
      nex_id: row.nex_id,
      username: row.username,
      display_name: row.display_name,
      password_hash: row.password_hash,
      password_salt: row.password_salt,
      avatar: row.avatar,
      share_presence: row.share_presence,
      created_at: row.created_at,
      deleted_at: row.deleted_at,
      suspended_at: row.suspended_at,
      suspended_reason: row.suspended_reason,
    },
    device: {
      id: row.device_id,
      user_id: row.device_user_id,
      name: row.device_name,
      platform: row.device_platform,
      registration_id: row.device_registration_id,
      signal_device_id: row.device_signal_device_id,
      identity_key: row.device_identity_key,
      signed_pre_key_id: row.device_signed_pre_key_id,
      signed_pre_key_public: row.device_signed_pre_key_public,
      signed_pre_key_signature: row.device_signed_pre_key_signature,
      kyber_pre_key_id: row.device_kyber_pre_key_id,
      kyber_pre_key_public: row.device_kyber_pre_key_public,
      kyber_pre_key_signature: row.device_kyber_pre_key_signature,
      created_at: row.device_created_at,
      last_seen_at: row.device_last_seen_at,
      revoked_at: row.device_revoked_at,
    },
  };
}

export function getBearerToken(request: FastifyRequest): string | null {
  const header = request.headers.authorization;
  if (!header?.startsWith("Bearer ")) return null;
  return header.slice("Bearer ".length).trim() || null;
}
