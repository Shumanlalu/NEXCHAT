import { z } from "zod";
import type { Database } from "../db";

/**
 * Settings that can genuinely change Android behaviour without a new APK.
 * Anything requiring new UI, permissions or native code stays out of this
 * model and is documented as release-bound instead.
 */
export const remoteConfigSchema = z.object({
  callsEnabled: z.boolean(),
  videoCallsEnabled: z.boolean(),
  maxCallDurationSeconds: z.number().int().min(60).max(14_400),
  maintenanceMode: z.boolean(),
  maintenanceMessage: z.string().max(500),
  minAppVersion: z.string().regex(/^\d+\.\d+\.\d+$/),
});

export type RemoteConfig = z.infer<typeof remoteConfigSchema> & { version: number };

export const DEFAULT_REMOTE_CONFIG: RemoteConfig = {
  version: 1,
  callsEnabled: true,
  videoCallsEnabled: true,
  maxCallDurationSeconds: 3600,
  maintenanceMode: false,
  maintenanceMessage: "",
  minAppVersion: "1.0.0",
};

export async function loadRemoteConfig(db: Database): Promise<RemoteConfig> {
  const row = await db.one<{ value: unknown; version: string | number }>(
    "SELECT value, version FROM app_settings WHERE key = 'remote_config'",
  );
  if (!row) return { ...DEFAULT_REMOTE_CONFIG };
  const raw = typeof row.value === "string" ? JSON.parse(row.value) : row.value;
  const parsed = remoteConfigSchema.safeParse(raw);
  if (!parsed.success) return { ...DEFAULT_REMOTE_CONFIG, version: Number(row.version) || 1 };
  return { ...parsed.data, version: Number(row.version) || 1 };
}

export async function saveRemoteConfig(
  db: Database,
  config: z.infer<typeof remoteConfigSchema>,
  adminId: string,
): Promise<RemoteConfig> {
  return db.transaction(async (client) => {
    const existing = await client.query<{ value: unknown; version: string | number }>(
      "SELECT value, version FROM app_settings WHERE key = 'remote_config' FOR UPDATE",
    );
    const previous = existing.rows[0]?.value ?? null;
    const nextVersion = (Number(existing.rows[0]?.version) || 0) + 1;
    const value = { ...config, version: nextVersion };
    await client.query(
      `INSERT INTO app_settings (key, value, version, updated_by, updated_at)
       VALUES ('remote_config', $1, $2, $3, now())
       ON CONFLICT (key) DO UPDATE
         SET value = EXCLUDED.value, version = EXCLUDED.version,
             updated_by = EXCLUDED.updated_by, updated_at = now()`,
      [JSON.stringify(value), nextVersion, adminId],
    );
    await client.query(
      `INSERT INTO app_setting_history (key, previous_value, new_value, version, admin_id)
       VALUES ('remote_config', $1, $2, $3, $4)`,
      [
        previous === null ? null : JSON.stringify(previous),
        JSON.stringify(value),
        nextVersion,
        adminId,
      ],
    );
    return value;
  });
}

export async function rollbackRemoteConfig(
  db: Database,
  historyId: string,
  adminId: string,
): Promise<RemoteConfig> {
  const row = await db.one<{ previous_value: unknown }>(
    "SELECT previous_value FROM app_setting_history WHERE id = $1",
    [historyId],
  );
  if (!row) throw Object.assign(new Error("history entry not found"), { statusCode: 404 });
  const raw =
    row.previous_value === null || row.previous_value === undefined
      ? { ...DEFAULT_REMOTE_CONFIG }
      : typeof row.previous_value === "string"
        ? JSON.parse(row.previous_value)
        : row.previous_value;
  const parsed = remoteConfigSchema.safeParse(raw);
  if (!parsed.success) {
    throw Object.assign(new Error("history entry is not a valid configuration"), {
      statusCode: 409,
    });
  }
  return saveRemoteConfig(db, parsed.data, adminId);
}
