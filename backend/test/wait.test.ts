import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
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

const ALICE_USER = "aaaaaaaa-2222-4222-8222-111111111111";
const ALICE_DEVICE = "aaaaaaaa-2222-4222-8222-222222222222";
const BOB_USER = "bbbbbbbb-2222-4222-8222-111111111111";
const BOB_DEVICE = "bbbbbbbb-2222-4222-8222-222222222222";
const BOB_NEX_ID = "NX-BOBW-BOBW";
const KEY = Buffer.from("wait-test-key-material").toString("base64");

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

async function enqueueCiphertext(clientId = randomUUID()): Promise<void> {
  const response = await app.inject({
    method: "POST",
    url: "/v1/messages",
    headers: authHeader(aliceToken),
    payload: {
      messages: [
        {
          recipientNexId: BOB_NEX_ID,
          recipientSignalDeviceId: 1,
          clientId,
          ciphertext: "AAAA",
          cipherType: 3,
        },
      ],
    },
  });
  assert.equal(response.statusCode, 202);
}

describe("long-poll delivery", () => {
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
       VALUES ($1, 'NX-ALIC-ALIC', 'alicew', 'Alice', 'hash', 'salt'),
              ($2, $3, 'bobw', 'Bob', 'hash', 'salt')`,
      [ALICE_USER, BOB_USER, BOB_NEX_ID],
    );
    for (const [id, userId, name, registrationId] of [
      [ALICE_DEVICE, ALICE_USER, "Alice phone", 1],
      [BOB_DEVICE, BOB_USER, "Bob phone", 2],
    ] as const) {
      await db.query(
        `INSERT INTO devices
           (id, user_id, name, platform, registration_id, signal_device_id, identity_key,
            signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature,
            kyber_pre_key_id, kyber_pre_key_public, kyber_pre_key_signature)
         VALUES ($1, $2, $3, 'android', $4, 1, $5, $4, $5, $5, $4, $5, $5)`,
        [id, userId, name, registrationId, KEY],
      );
    }

    const tokens = new TokenService(config);
    aliceToken = await tokens.issueAccessToken(ALICE_USER, ALICE_DEVICE);
    bobToken = await tokens.issueAccessToken(BOB_USER, BOB_DEVICE);
  });

  after(async () => {
    await app?.close();
    await db?.close();
  });

  it("requires authentication", async () => {
    const response = await app.inject({
      method: "GET",
      url: "/v1/messages/wait?timeoutMs=1000",
    });
    assert.equal(response.statusCode, 401);
  });

  it("returns already-pending ciphertext without waiting for the timeout", async () => {
    await enqueueCiphertext();
    const started = Date.now();
    const response = await app.inject({
      method: "GET",
      url: "/v1/messages/wait?timeoutMs=5000",
      headers: authHeader(bobToken),
    });
    assert.equal(response.statusCode, 200);
    const messages = (response.json() as { messages: unknown[] }).messages;
    assert.equal(messages.length, 1);
    assert.ok(Date.now() - started < 2_000);
  });

  it("wakes up as soon as a new envelope arrives", async () => {
    // Clear the previous envelope first so the wait genuinely has to block.
    const pending = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: authHeader(bobToken),
    });
    const ids = (pending.json() as { messages: Array<{ id: string }> }).messages.map(
      (message) => message.id,
    );
    await app.inject({
      method: "POST",
      url: "/v1/messages/ack",
      headers: authHeader(bobToken),
      payload: { envelopeIds: ids },
    });

    const started = Date.now();
    const waiting = app.inject({
      method: "GET",
      url: "/v1/messages/wait?timeoutMs=8000",
      headers: authHeader(bobToken),
    });
    await new Promise((resolve) => setTimeout(resolve, 350));
    await enqueueCiphertext();
    const response = await waiting;
    assert.equal(response.statusCode, 200);
    const messages = (response.json() as { messages: unknown[] }).messages;
    assert.equal(messages.length, 1);
    const elapsed = Date.now() - started;
    assert.ok(elapsed < 6_000, `long poll took ${elapsed}ms`);
  });

  it("returns an empty feed after the timeout", async () => {
    // The wake-up test intentionally leaves its envelope unacknowledged.
    // Clear the queue so this test measures only the timeout behaviour.
    const pending = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: authHeader(bobToken),
    });
    const ids = (pending.json() as { messages: Array<{ id: string }> }).messages.map(
      (message) => message.id,
    );
    if (ids.length > 0) {
      await app.inject({
        method: "POST",
        url: "/v1/messages/ack",
        headers: authHeader(bobToken),
        payload: { envelopeIds: ids },
      });
    }

    const started = Date.now();
    const response = await app.inject({
      method: "GET",
      url: "/v1/messages/wait?timeoutMs=1000",
      headers: authHeader(bobToken),
    });
    assert.equal(response.statusCode, 200);
    assert.deepEqual((response.json() as { messages: unknown[] }).messages, []);
    assert.ok(Date.now() - started >= 900);
  });

  async function drainBob(): Promise<void> {
    const pending = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: authHeader(bobToken),
    });
    const ids = (pending.json() as { messages: Array<{ id: string }> }).messages.map(
      (message) => message.id,
    );
    if (ids.length === 0) return;
    await app.inject({
      method: "POST",
      url: "/v1/messages/ack",
      headers: authHeader(bobToken),
      payload: { envelopeIds: ids },
    });
  }

  it("serves priority call signaling before older delivered envelopes", async () => {
    await drainBob();
    const ordinaryClientId = randomUUID();
    const callClientId = randomUUID();
    for (const [clientId, priority] of [
      [ordinaryClientId, false],
      [callClientId, true],
    ] as const) {
      const response = await app.inject({
        method: "POST",
        url: "/v1/messages",
        headers: authHeader(aliceToken),
        payload: {
          messages: [
            {
              recipientNexId: BOB_NEX_ID,
              recipientSignalDeviceId: 1,
              clientId,
              ciphertext: "AAAA",
              cipherType: 3,
              priority,
            },
          ],
        },
      });
      assert.equal(response.statusCode, 202);
    }
    const pending = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: authHeader(bobToken),
    });
    const messages = (pending.json() as { messages: Array<{ clientId: string }> }).messages;
    assert.equal(messages[0]?.clientId, callClientId);
  });

  it("records simultaneous caller and callee call reports without a duplicate-key failure", async () => {
    const callId = randomUUID();
    const responses = await Promise.all([
      app.inject({
        method: "POST",
        url: "/v1/analytics/calls",
        headers: authHeader(aliceToken),
        payload: {
          callId,
          role: "caller",
          peerNexId: BOB_NEX_ID,
          status: "ringing",
        },
      }),
      app.inject({
        method: "POST",
        url: "/v1/analytics/calls",
        headers: authHeader(bobToken),
        payload: {
          callId,
          role: "callee",
          peerNexId: "NX-ALIC-ALIC",
          status: "ringing",
        },
      }),
    ]);
    for (const response of responses) assert.equal(response.statusCode, 200);
    const row = await db.one<{ caller_user_id: string; callee_user_id: string }>(
      "SELECT caller_user_id, callee_user_id FROM call_sessions WHERE call_id = $1",
      [callId],
    );
    assert.equal(row?.caller_user_id, ALICE_USER);
    assert.equal(row?.callee_user_id, BOB_USER);
  });
});
