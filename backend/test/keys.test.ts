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
import { TokenService } from "../src/security/tokens";

const ALICE_USER = "11111111-1111-4111-8111-111111111111";
const ALICE_DEVICE = "11111111-1111-4111-8111-222222222222";
const BOB_USER = "33333333-3333-4333-8333-333333333333";
const BOB_DEVICE = "33333333-3333-4333-8333-444444444444";
const BOB_DEVICE_2 = "33333333-3333-4333-8333-555555555555";
const BOB_NEX_ID = "NX-BOBB-BOBB";

const KEY = Buffer.from("integration-test-key-material").toString("base64");

const config: AppConfig = {
  env: "test",
  host: "127.0.0.1",
  port: 8080,
  databaseUrl: "postgres://unused",
  dbPoolMax: 2,
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
};

let app: FastifyInstance;
let db: Database;
let aliceToken: string;
let bobToken: string;

function authHeader(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}` };
}

describe("prekey distribution", () => {
  before(async () => {
    const mem = newDb();
    const schema = readFileSync(path.join(__dirname, "..", "src", "schema.sql"), "utf8");
    mem.public.none(schema);
    const memPg = mem.adapters.createPg();

    db = new Database("postgres://unused");
    (db as unknown as { pool: Pool }).pool = new memPg.Pool();

    app = Fastify({ logger: false });
    await buildApp({ config, db }, app);

    await db.query(
      `INSERT INTO users (id, nex_id, username, display_name, password_hash, password_salt)
       VALUES ($1, 'NX-ALIC-ALIC', 'alice', 'Alice', 'hash', 'salt'),
              ($2, $3, 'bob', 'Bob', 'hash', 'salt')`,
      [ALICE_USER, BOB_USER, BOB_NEX_ID],
    );
    await db.query(
      `INSERT INTO devices
         (id, user_id, name, platform, registration_id, signal_device_id, identity_key,
          signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature,
          kyber_pre_key_id, kyber_pre_key_public, kyber_pre_key_signature)
       VALUES
         ($1, $2, 'Alice phone', 'android', 1, 1, $6, 1, $6, $6, 11, $6, $6),
         ($3, $4, 'Bob phone', 'android', 2, 1, $6, 2, $6, $6, 22, $6, $6),
         ($5, $4, 'Bob tablet', 'android', 3, 2, $6, 3, $6, $6, 23, $6, $6)`,
      [ALICE_DEVICE, ALICE_USER, BOB_DEVICE, BOB_USER, BOB_DEVICE_2, KEY],
    );
    await db.query(
      `INSERT INTO kyber_prekeys (device_id, key_id, public_key, signature)
       VALUES ($1, 22, $3, $3), ($2, 23, $3, $3)`,
      [BOB_DEVICE, BOB_DEVICE_2, KEY],
    );
    await db.query(
      `INSERT INTO one_time_prekeys (device_id, key_id, public_key)
       VALUES ($1, 1, $2), ($1, 2, $2), ($3, 3, $2)`,
      [BOB_DEVICE, KEY, BOB_DEVICE_2],
    );

    const tokens = new TokenService(config);
    aliceToken = await tokens.issueAccessToken(ALICE_USER, ALICE_DEVICE);
    bobToken = await tokens.issueAccessToken(BOB_USER, BOB_DEVICE);
  });

  after(async () => {
    await app?.close();
    await db?.close();
  });

  it("lists active devices without consuming any prekeys", async () => {
    const response = await app.inject({
      method: "GET",
      url: `/v1/keys/${BOB_NEX_ID}/devices`,
      headers: authHeader(aliceToken),
    });

    assert.equal(response.statusCode, 200);
    const body = response.json() as {
      devices: Array<{ deviceId: string; signalDeviceId: number; identityKey: string }>;
    };
    assert.deepEqual(
      body.devices.map((device) => device.signalDeviceId),
      [1, 2],
    );
    assert.equal(body.devices[0]?.identityKey, KEY);

    const unused = await db.query<{ count: number }>(
      "SELECT COUNT(*)::int AS count FROM one_time_prekeys WHERE used_at IS NULL",
    );
    assert.equal(unused[0]?.count, 3);
  });

  it("consumes one one-time prekey per bundle but keeps the Kyber key reusable", async () => {
    const first = await app.inject({
      method: "GET",
      url: `/v1/keys/${BOB_NEX_ID}?devices=1`,
      headers: authHeader(aliceToken),
    });
    assert.equal(first.statusCode, 200);
    const firstBundle = (first.json() as { devices: Array<Record<string, unknown>> }).devices[0];
    assert.equal(firstBundle?.oneTimePreKeyId, 1);
    assert.equal(firstBundle?.kyberPreKeyId, 22);

    const second = await app.inject({
      method: "GET",
      url: `/v1/keys/${BOB_NEX_ID}?devices=1`,
      headers: authHeader(aliceToken),
    });
    assert.equal(second.statusCode, 200);
    const secondBundle = (second.json() as { devices: Array<Record<string, unknown>> }).devices[0];
    assert.equal(secondBundle?.oneTimePreKeyId, 2);
    assert.equal(secondBundle?.kyberPreKeyId, 22);

    const kyber = await db.query<{ used_at: Date | null }>(
      "SELECT used_at FROM kyber_prekeys WHERE device_id = $1 AND key_id = 22",
      [BOB_DEVICE],
    );
    assert.equal(kyber[0]?.used_at, null);
  });

  it("returns a bundle without a one-time prekey when the pool is empty", async () => {
    // Both one-time keys for Bob's phone were consumed by the previous test.
    const response = await app.inject({
      method: "GET",
      url: `/v1/keys/${BOB_NEX_ID}?devices=1`,
      headers: authHeader(aliceToken),
    });

    assert.equal(response.statusCode, 200);
    const bundle = (response.json() as { devices: Array<Record<string, unknown>> }).devices[0];
    assert.equal(bundle?.oneTimePreKeyId, null);
    assert.equal(bundle?.oneTimePreKeyPublic, null);
    assert.equal(bundle?.kyberPreKeyId, 22);
  });

  it("does not consume keys for a device that cannot produce a bundle", async () => {
    await db.query("UPDATE devices SET kyber_pre_key_id = NULL WHERE id = $1", [BOB_DEVICE_2]);
    const before = await db.query<{ used_at: Date | null }>(
      "SELECT used_at FROM one_time_prekeys WHERE device_id = $1 AND key_id = 3",
      [BOB_DEVICE_2],
    );
    assert.equal(before[0]?.used_at, null);

    const response = await app.inject({
      method: "GET",
      url: `/v1/keys/${BOB_NEX_ID}?devices=2`,
      headers: authHeader(aliceToken),
    });

    assert.equal(response.statusCode, 409);
    assert.equal(
      (response.json() as { error: { code: string } }).error.code,
      "PREKEYS_EXHAUSTED",
    );
    const afterRow = await db.query<{ used_at: Date | null }>(
      "SELECT used_at FROM one_time_prekeys WHERE device_id = $1 AND key_id = 3",
      [BOB_DEVICE_2],
    );
    assert.equal(afterRow[0]?.used_at, null);
  });

  it("replaces superseded Kyber keys when a device rotates", async () => {
    const rotated = await app.inject({
      method: "POST",
      url: "/v1/devices/keys",
      headers: authHeader(bobToken),
      payload: {
        signedPreKeyId: 4,
        signedPreKeyPublic: KEY,
        signedPreKeySignature: KEY,
        kyberPreKeyId: 99,
        kyberPreKeyPublic: KEY,
        kyberPreKeySignature: KEY,
        oneTimePreKeys: [{ id: 50, publicKey: KEY }],
      },
    });
    assert.equal(rotated.statusCode, 200);

    const rows = await db.query<{ key_id: number }>(
      "SELECT key_id FROM kyber_prekeys WHERE device_id = $1 ORDER BY key_id",
      [BOB_DEVICE],
    );
    assert.deepEqual(
      rows.map((row) => row.key_id),
      [99],
    );

    const count = await app.inject({
      method: "GET",
      url: "/v1/devices/keys/count",
      headers: authHeader(bobToken),
    });
    assert.deepEqual(count.json(), { oneTimePreKeys: 1, kyberPreKeys: 1 });

    const bundle = await app.inject({
      method: "GET",
      url: `/v1/keys/${BOB_NEX_ID}?devices=1`,
      headers: authHeader(aliceToken),
    });
    assert.equal(bundle.statusCode, 200);
    const rotatedBundle = (bundle.json() as { devices: Array<Record<string, unknown>> }).devices[0];
    assert.equal(rotatedBundle?.kyberPreKeyId, 99);
    assert.equal(rotatedBundle?.oneTimePreKeyId, 50);
  });

  it("rejects a malformed device filter", async () => {
    const response = await app.inject({
      method: "GET",
      url: `/v1/keys/${BOB_NEX_ID}?devices=abc`,
      headers: authHeader(aliceToken),
    });
    assert.equal(response.statusCode, 400);
    assert.equal(
      (response.json() as { error: { code: string } }).error.code,
      "INVALID_DEVICE_FILTER",
    );
  });
});
