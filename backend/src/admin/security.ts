import { createCipheriv, createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import type { FastifyRequest } from "fastify";
import type { AppConfig } from "../config";
import type { Database } from "../db";
import { HttpError } from "../http";

export const ADMIN_COOKIE = "nexchat_admin_session";
export const ADMIN_SESSION_SECONDS = 12 * 60 * 60;
export const ADMIN_SESSION_MILLIS = ADMIN_SESSION_SECONDS * 1000;

export type AdminRole = "super_admin" | "admin" | "analyst" | "support";

export type AdminPermission =
  | "dashboard.read"
  | "users.read"
  | "users.write"
  | "calls.read"
  | "monitoring.read"
  | "config.read"
  | "config.write"
  | "announcements.write"
  | "audit.read"
  | "reports.export"
  | "admins.manage";

const ROLE_PERMISSIONS: Record<AdminRole, ReadonlySet<AdminPermission>> = {
  super_admin: new Set<AdminPermission>([
    "dashboard.read",
    "users.read",
    "users.write",
    "calls.read",
    "monitoring.read",
    "config.read",
    "config.write",
    "announcements.write",
    "audit.read",
    "reports.export",
    "admins.manage",
  ]),
  admin: new Set<AdminPermission>([
    "dashboard.read",
    "users.read",
    "users.write",
    "calls.read",
    "monitoring.read",
    "config.read",
    "config.write",
    "announcements.write",
    "audit.read",
    "reports.export",
  ]),
  analyst: new Set<AdminPermission>([
    "dashboard.read",
    "users.read",
    "calls.read",
    "monitoring.read",
    "config.read",
    "reports.export",
  ]),
  support: new Set<AdminPermission>([
    "dashboard.read",
    "users.read",
    "users.write",
    "calls.read",
    "monitoring.read",
  ]),
};

export interface AdminRow {
  id: string;
  username: string;
  display_name: string;
  password_hash: string;
  password_salt: string;
  totp_secret_enc: string;
  role: AdminRole;
  active: boolean;
  failed_attempts: number;
  locked_until: Date | string | null;
  last_login_at: Date | string | null;
  created_at: Date | string;
}

export interface AdminSessionRow {
  session_id: string;
  admin_id: string;
  token_hash: string;
  created_at: Date | string;
  last_seen_at: Date | string;
  expires_at: Date | string;
  revoked_at: Date | string | null;
}

export interface AdminContext {
  admin: AdminRow;
  sessionId: string;
  csrfToken: string;
  expiresAt: string;
}

export function hasPermission(role: AdminRole, permission: AdminPermission): boolean {
  return ROLE_PERMISSIONS[role]?.has(permission) ?? false;
}

export function publicAdmin(admin: AdminRow): Record<string, unknown> {
  return {
    id: admin.id,
    username: admin.username,
    // Admin accounts are email-based now; keep both names for older clients.
    email: admin.username,
    displayName: admin.display_name,
    role: admin.role,
    active: admin.active,
    lastLoginAt: admin.last_login_at ? new Date(admin.last_login_at).toISOString() : null,
    createdAt: new Date(admin.created_at).toISOString(),
  };
}

export function parseCookies(header: string | undefined): Record<string, string> {
  const result: Record<string, string> = {};
  if (!header) return result;
  for (const part of header.split(";")) {
    const index = part.indexOf("=");
    if (index < 0) continue;
    const name = part.slice(0, index).trim();
    const value = part.slice(index + 1).trim();
    if (name) result[name] = decodeURIComponent(value);
  }
  return result;
}

export function serializeAdminCookie(value: string, maxAgeSeconds: number): string {
  const secure = process.env.NODE_ENV === "production" ? "; Secure" : "";
  return `${ADMIN_COOKIE}=${encodeURIComponent(value)}; Path=/; HttpOnly${secure}; SameSite=Strict; Max-Age=${maxAgeSeconds}`;
}

export function newSessionToken(): string {
  return randomBytes(32).toString("base64url");
}

export function hashSessionToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

export function csrfFor(token: string, jwtSecret: string): string {
  return createHmac("sha256", jwtSecret)
    .update(`nexchat-admin-csrf:${token}`)
    .digest("base64url");
}

function equalStrings(left: string, right: string): boolean {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}

export function secureEqual(left: string, right: string): boolean {
  return equalStrings(left, right);
}

export function verifyCsrf(request: FastifyRequest, context: AdminContext, config: AppConfig): void {
  if (request.method === "GET" || request.method === "HEAD" || request.method === "OPTIONS") return;
  const supplied = request.headers["x-admin-csrf"];
  const value = Array.isArray(supplied) ? supplied[0] : supplied;
  if (!value || !equalStrings(value, context.csrfToken)) {
    throw new HttpError(403, "CSRF_REJECTED", "The request could not be verified");
  }
  // The cookie itself is SameSite=Strict; Origin is checked as defence in depth.
  const origin = request.headers.origin;
  if (origin) {
    const host = request.headers["x-forwarded-host"] ?? request.headers.host;
    const sameOrigin = host ? `${request.protocol}://${host}` : config.corsOrigin;
    const allowed = new Set([
      sameOrigin.replace(/\/$/, ""),
      config.corsOrigin.replace(/\/$/, ""),
    ]);
    if (!allowed.has(origin.replace(/\/$/, ""))) {
      throw new HttpError(403, "ORIGIN_REJECTED", "Cross-origin admin request rejected");
    }
  }
}

export async function requireAdmin(
  request: FastifyRequest,
  db: Database,
  config: AppConfig,
  permission?: AdminPermission,
): Promise<AdminContext> {
  const token = parseCookies(request.headers.cookie)[ADMIN_COOKIE];
  if (!token) throw new HttpError(401, "ADMIN_AUTH_REQUIRED", "Administrator sign-in required");
  const row = await db.one<AdminSessionRow & AdminRow>(
    `SELECT s.id AS session_id, s.expires_at, s.revoked_at, a.*
     FROM admin_sessions s JOIN admin_users a ON a.id = s.admin_id
     WHERE s.token_hash = $1`,
    [hashSessionToken(token)],
  );
  if (!row || row.revoked_at || !row.active || new Date(row.expires_at).getTime() <= Date.now()) {
    throw new HttpError(401, "ADMIN_SESSION_EXPIRED", "Administrator session expired");
  }
  if (permission && !hasPermission(row.role, permission)) {
    throw new HttpError(403, "ADMIN_FORBIDDEN", "This role cannot perform that action");
  }
  const context: AdminContext = {
    admin: {
      id: row.id,
      username: row.username,
      display_name: row.display_name,
      password_hash: row.password_hash,
      password_salt: row.password_salt,
      totp_secret_enc: row.totp_secret_enc,
      role: row.role,
      active: row.active,
      failed_attempts: row.failed_attempts,
      locked_until: row.locked_until,
      last_login_at: row.last_login_at,
      created_at: row.created_at,
    },
    sessionId: row.session_id,
    csrfToken: csrfFor(token, config.jwtSecret),
    expiresAt: new Date(row.expires_at).toISOString(),
  };
  // Keep a coarse last-seen for session management without a write per request.
  await db
    .query(
      `UPDATE admin_sessions SET last_seen_at = now()
       WHERE id = $1 AND last_seen_at < now() - interval '5 minutes'`,
      [row.session_id],
    )
    .catch(() => undefined);
  return context;
}

export async function audit(
  db: Database,
  adminId: string | null,
  action: string,
  request: FastifyRequest | null,
  targetType?: string,
  targetId?: string,
  details?: Record<string, unknown>,
): Promise<void> {
  await db
    .query(
      `INSERT INTO admin_audit_logs
         (admin_id, action, target_type, target_id, details, ip, user_agent)
       VALUES ($1,$2,$3,$4,$5,$6,$7)`,
      [
        adminId,
        action,
        targetType ?? null,
        targetId ?? null,
        details ? JSON.stringify(details) : null,
        request?.ip ?? null,
        (request?.headers["user-agent"] ?? "").slice(0, 300) || null,
      ],
    )
    .catch(() => undefined);
}

// ---------------------------------------------------------------------------
// TOTP (RFC 6238) with SHA-1, six digits and a 30-second period. Implemented
// locally so the admin panel has no additional runtime dependency.
// ---------------------------------------------------------------------------

const BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

export function base32Encode(input: Buffer): string {
  let bits = 0;
  let value = 0;
  let output = "";
  for (const byte of input) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      output += BASE32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) output += BASE32_ALPHABET[(value << (5 - bits)) & 31];
  return output;
}

// Only used to fill the legacy NOT NULL column. It is never displayed,
// verified, or required at sign-in.
export function generateTotpSecret(): string {
  return base32Encode(randomBytes(20));
}

function encryptionKey(config: AppConfig): Buffer {
  return createHash("sha256")
    .update(`${config.jwtSecret}:nexchat-admin-totp:v1`)
    .digest();
}

export function encryptTotpSecret(secret: string, config: AppConfig): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", encryptionKey(config), iv);
  const ciphertext = Buffer.concat([cipher.update(secret, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return `v1:${iv.toString("base64")}:${tag.toString("base64")}:${ciphertext.toString("base64")}`;
}
