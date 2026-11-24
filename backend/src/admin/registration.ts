import type { AppConfig } from "../config";
import type { Database } from "../db";

const SETTINGS_KEY = "admin_registration";

export interface RegistrationSettings {
  signupEnabled: boolean;
  updatedAt: string | null;
}

export async function loadRegistrationSettings(db: Database): Promise<RegistrationSettings> {
  const row = await db.one<{ value: unknown; updated_at: Date | string | null }>(
    "SELECT value, updated_at FROM app_settings WHERE key = $1",
    [SETTINGS_KEY],
  );
  const raw = typeof row?.value === "string" ? JSON.parse(row.value) : row?.value;
  const value = raw && typeof raw === "object" ? (raw as { signupEnabled?: unknown }) : {};
  return {
    signupEnabled: value.signupEnabled === true,
    updatedAt: row?.updated_at ? new Date(row.updated_at).toISOString() : null,
  };
}

export async function setRegistrationEnabled(
  db: Database,
  adminId: string,
  signupEnabled: boolean,
): Promise<RegistrationSettings> {
  const existing = await db.one<{ version: string | number }>(
    "SELECT version FROM app_settings WHERE key = $1",
    [SETTINGS_KEY],
  );
  const version = (Number(existing?.version) || 0) + 1;
  await db.query(
    `INSERT INTO app_settings (key, value, version, updated_by, updated_at)
     VALUES ($1, $2, $3, $4, now())
     ON CONFLICT (key) DO UPDATE
       SET value = EXCLUDED.value, version = EXCLUDED.version,
           updated_by = EXCLUDED.updated_by, updated_at = now()`,
    [SETTINGS_KEY, JSON.stringify({ signupEnabled }), version, adminId],
  );
  return { signupEnabled, updatedAt: new Date().toISOString() };
}

/**
 * Token-gated signup is only available for the first administrator or when a
 * super admin explicitly re-opens break-glass registration. It is never a
 * public self-service administrator role.
 */
export async function isAdminSignupOpen(
  db: Database,
  config: AppConfig,
  adminCount: number,
): Promise<boolean> {
  if (!config.adminSetupToken) return false;
  if (adminCount === 0) return true;
  return (await loadRegistrationSettings(db)).signupEnabled;
}
