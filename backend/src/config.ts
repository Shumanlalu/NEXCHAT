import "dotenv/config";
import { z } from "zod";

const booleanString = z
  .enum(["true", "false"])
  .default("false")
  .transform((v) => v === "true");

const envSchema = z.object({
  NODE_ENV: z.enum(["development", "test", "production"]).default("development"),
  HOST: z.string().default("0.0.0.0"),
  PORT: z.coerce.number().int().positive().default(8080),
  DATABASE_URL: z.string().min(1),
  DB_POOL_MAX: z.coerce.number().int().positive().default(10),
  DB_SSL: booleanString,
  JWT_SECRET: z.string().min(32),
  CORS_ORIGIN: z.string().default("https://nexchat.example.com"),
  ACCESS_TOKEN_TTL_SECONDS: z.coerce.number().int().positive().default(3600),
  REFRESH_TOKEN_TTL_DAYS: z.coerce.number().int().positive().default(30),
  ENVELOPE_TTL_DAYS: z.coerce.number().int().positive().default(7),
  MAX_ENVELOPE_BYTES: z.coerce.number().int().positive().default(131_072),
  MAX_ATTACHMENT_BYTES: z.coerce.number().int().positive().default(524_288),
  ICE_SERVERS_JSON: z.string().default('[{"urls":["stun:stun.l.google.com:19302"]}]'),
  // coturn REST API shared secret. Never sent to clients; the authenticated
  // /v1/turn-credentials endpoint mints time-limited credentials from it.
  TURN_SHARED_SECRET: z.string().default(""),
  TRUST_PROXY: booleanString,
  LOG_LEVEL: z.string().default("info"),
  // One-time bootstrap secret for the first administrator. Setup is disabled
  // when this is empty, and permanently disabled once an admin exists.
  ADMIN_SETUP_TOKEN: z.string().default(""),
  ADMIN_SESSION_HOURS: z.coerce.number().int().min(1).max(72).default(12),
  CRON_SECRET: z.string().default(""),
});

export type AppConfig = {
  env: "development" | "test" | "production";
  host: string;
  port: number;
  databaseUrl: string;
  dbPoolMax: number;
  dbSsl: boolean;
  jwtSecret: string;
  corsOrigin: string;
  accessTokenTtlSeconds: number;
  refreshTokenTtlDays: number;
  envelopeTtlDays: number;
  maxEnvelopeBytes: number;
  maxAttachmentBytes: number;
  iceServers: Array<Record<string, unknown>>;
  turnSharedSecret?: string;
  trustProxy: boolean;
  logLevel: string;
  adminSetupToken?: string;
  adminSessionHours?: number;
  cronSecret?: string;
};

export function loadConfig(overrides: Partial<AppConfig> = {}): AppConfig {
  const parsed = envSchema.parse(process.env);
  if (parsed.NODE_ENV === "production" && parsed.JWT_SECRET.startsWith("replace-with")) {
    throw new Error("Refusing to start in production with the example JWT_SECRET");
  }

  let iceServers: Array<Record<string, unknown>>;
  try {
    const value: unknown = JSON.parse(parsed.ICE_SERVERS_JSON);
    if (!Array.isArray(value) || !value.every((v) => typeof v === "object" && v !== null)) {
      throw new Error("ICE_SERVERS_JSON must be a JSON array of objects");
    }
    iceServers = value as Array<Record<string, unknown>>;
  } catch (error) {
    throw new Error(`Invalid ICE_SERVERS_JSON: ${(error as Error).message}`);
  }

  return {
    env: parsed.NODE_ENV,
    host: parsed.HOST,
    port: parsed.PORT,
    databaseUrl: parsed.DATABASE_URL,
    dbPoolMax: parsed.DB_POOL_MAX,
    dbSsl: parsed.DB_SSL,
    jwtSecret: parsed.JWT_SECRET,
    corsOrigin: parsed.CORS_ORIGIN,
    accessTokenTtlSeconds: parsed.ACCESS_TOKEN_TTL_SECONDS,
    refreshTokenTtlDays: parsed.REFRESH_TOKEN_TTL_DAYS,
    envelopeTtlDays: parsed.ENVELOPE_TTL_DAYS,
    maxEnvelopeBytes: parsed.MAX_ENVELOPE_BYTES,
    maxAttachmentBytes: parsed.MAX_ATTACHMENT_BYTES,
    iceServers,
    turnSharedSecret: parsed.TURN_SHARED_SECRET,
    trustProxy: parsed.TRUST_PROXY,
    logLevel: parsed.LOG_LEVEL,
    adminSetupToken: parsed.ADMIN_SETUP_TOKEN,
    adminSessionHours: parsed.ADMIN_SESSION_HOURS,
    cronSecret: parsed.CRON_SECRET,
    ...overrides,
  };
}
