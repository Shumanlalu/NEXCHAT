import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import Fastify, { type FastifyInstance } from "fastify";
import { newDb } from "pg-mem";
import type { Pool } from "pg";
import type { AppConfig } from "../src/config";
import { buildApp } from "../src/create-app";
import { Database } from "../src/db";

const SETUP_TOKEN = "race-setup-token-for-tests-only";

const config: AppConfig = {
  env: "test",
  host: "127.0.0.1",
  port: 8080,
  databaseUrl: "postgres://unused",
  dbPoolMax: 4,
  dbSsl: false,
  jwtSecret: "test-secret-test-secret-test-secret-12",
  corsOrigin: "http://localhost",
  accessTokenTtlSeconds: 3600,
  refreshTokenTtlDays: 30,
  envelopeTtlDays: 7,
  maxEnvelopeBytes: 131_072,
  maxAttachmentBytes: 524_288,
  iceServers: [],
  trustProxy: false,
  logLevel: "silent",
  adminSetupToken: SETUP_TOKEN,
  adminSessionHours: 12,
};

describe("admin race guards", () => {
  let app: FastifyInstance;
  let db: Database;

  before(async () => {
    const mem = newDb();
    const schema = readFileSync(path.join(__dirname, "..", "src", "schema.sql"), "utf8");
    mem.public.none(schema);
    const memPg = mem.adapters.createPg();
    db = new Database("postgres://unused");
    (db as unknown as { pool: Pool }).pool = new memPg.Pool();
    app = Fastify({ logger: false });
    await buildApp({ config, db }, app);
  });

  after(async () => {
    await app?.close();
    await db?.close();
  });

  it("creates exactly one super admin for concurrent first-run setup requests", async () => {
    const payload = {
      displayName: "Race Admin",
      email: "race-admin@example.com",
      password: "RaceAdminPassword-123",
      confirmPassword: "RaceAdminPassword-123",
    };
    const responses = await Promise.all([
      app.inject({
        method: "POST",
        url: "/v1/admin/setup",
        headers: { "x-setup-token": SETUP_TOKEN },
        payload,
      }),
      app.inject({
        method: "POST",
        url: "/v1/admin/setup",
        headers: { "x-setup-token": SETUP_TOKEN },
        payload,
      }),
    ]);
    assert.deepEqual(
      responses.map((response) => response.statusCode).sort(),
      [201, 409],
    );

    const row = await db.one<{ count: unknown }>(
      "SELECT count(*) AS count FROM admin_users WHERE role = 'super_admin'",
    );
    assert.equal(Number(row?.count ?? 0), 1);
  });

  it("keeps setup closed after the first administrator exists", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/v1/admin/setup",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload: {
        displayName: "Late Admin",
        email: "late-admin@example.com",
        password: "LateAdminPassword-123",
        confirmPassword: "LateAdminPassword-123",
      },
    });
    assert.equal(response.statusCode, 409);
    assert.equal((response.json() as { error: { code: string } }).error.code, "SETUP_CLOSED");
  });
});
