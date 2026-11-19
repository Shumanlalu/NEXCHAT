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
import { resolveSignalDeviceId } from "../src/routes/auth";
import { TokenService } from "../src/security/tokens";

const ALICE_USER = "aaaaaaaa-1111-4111-8111-111111111111";
const ALICE_DEVICE = "aaaaaaaa-1111-4111-8111-222222222222";
const BOB_USER = "bbbbbbbb-1111-4111-8111-111111111111";
const BOB_DEVICE = "bbbbbbbb-1111-4111-8111-222222222222";
const BOB_NEX_ID = "NX-BOBB-BOBB";
const CAROL_USER = "cccccccc-1111-4111-8111-111111111111";
const CAROL_DEVICE = "cccccccc-1111-4111-8111-222222222222";
const CAROL_SIBLING = "cccccccc-1111-4111-8111-333333333333";

const LEGACY_DEVICE_ID = 1_708_410_131;
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
let carolToken: string;

function authHeader(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}` };
}

async function insertDevice(
  id: string,
  userId: string,
  name: string,
  signalDeviceId: number,
  registrationId: number,
): Promise<void> {
  await db.query(
    `INSERT INTO devices
       (id, user_id, name, platform, registration_id, signal_device_id, identity_key,
        signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature,
        kyber_pre_key_id, kyber_pre_key_public, kyber_pre_key_signature)
     VALUES ($1, $2, $3, 'android', $4, $5, $6, $4, $6, $6, $4, $6, $6)`,
    [id, userId, name, registrationId, signalDeviceId, KEY],
  );
}

describe("durable delivery and device id normalization", () => {
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
              ($2, $3, 'bob', 'Bob', 'hash', 'salt'),
              ($4, 'NX-CARO-CARO', 'carol', 'Carol', 'hash', 'salt')`,
      [ALICE_USER, BOB_USER, BOB_NEX_ID, CAROL_USER],
    );
    await insertDevice(ALICE_DEVICE, ALICE_USER, "Alice phone", 1, 1);
    await insertDevice(BOB_DEVICE, BOB_USER, "Bob phone", 1, 2);
    await insertDevice(CAROL_DEVICE, CAROL_USER, "Carol legacy", LEGACY_DEVICE_ID, 3);
    await insertDevice(CAROL_SIBLING, CAROL_USER, "Carol tablet", 1, 4);

    const tokens = new TokenService(config);
    aliceToken = await tokens.issueAccessToken(ALICE_USER, ALICE_DEVICE);
    bobToken = await tokens.issueAccessToken(BOB_USER, BOB_DEVICE);
    carolToken = await tokens.issueAccessToken(CAROL_USER, CAROL_DEVICE);
  });

  after(async () => {
    await app?.close();
    await db?.close();
  });

  it("normalizes exported libsignal device ids without colliding with a sibling", async () => {
    const assigned = await resolveSignalDeviceId(
      db,
      CAROL_USER,
      LEGACY_DEVICE_ID,
      CAROL_DEVICE,
    );
    assert.ok(assigned >= 1 && assigned <= 127);
    assert.equal(assigned, 2);
  });

  it("repairs a legacy device through the signal-id endpoint", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/v1/devices/signal-id",
      headers: authHeader(carolToken),
      payload: { signalDeviceId: LEGACY_DEVICE_ID },
    });
    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), { signalDeviceId: 2 });

    const me = await app.inject({
      method: "GET",
      url: "/v1/me",
      headers: authHeader(carolToken),
    });
    assert.equal(
      (me.json() as { device: { signalDeviceId: number } }).device.signalDeviceId,
      2,
    );
  });

  it("accepts, retains and deduplicates an envelope while the recipient is offline", async () => {
    const clientId = randomUUID();
    const payload = {
      messages: [
        {
          recipientNexId: BOB_NEX_ID,
          recipientSignalDeviceId: 1,
          clientId,
          ciphertext: "AAAA",
          cipherType: 3,
        },
      ],
    };

    const first = await app.inject({
      method: "POST",
      url: "/v1/messages",
      headers: authHeader(aliceToken),
      payload,
    });
    assert.equal(first.statusCode, 202);
    const firstBody = first.json() as {
      accepted: Array<{ clientId: string; envelopeId: string; deliveredAt: string | null }>;
      skipped: unknown[];
    };
    assert.equal(firstBody.accepted.length, 1);
    assert.equal(firstBody.accepted[0]?.clientId, clientId);
    assert.equal(firstBody.accepted[0]?.deliveredAt, null);
    assert.deepEqual(firstBody.skipped, []);

    const retry = await app.inject({
      method: "POST",
      url: "/v1/messages",
      headers: authHeader(aliceToken),
      payload,
    });
    assert.equal(retry.statusCode, 202);
    const retryBody = retry.json() as {
      accepted: Array<{ envelopeId: string }>;
    };
    assert.equal(retryBody.accepted[0]?.envelopeId, firstBody.accepted[0]?.envelopeId);

    const count = await db.query<{ count: number }>(
      "SELECT COUNT(*)::int AS count FROM envelopes WHERE client_id = $1",
      [clientId],
    );
    assert.equal(count[0]?.count, 1);

    const pending = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: authHeader(bobToken),
    });
    assert.equal(pending.statusCode, 200);
    const pendingBody = pending.json() as {
      messages: Array<{
        id: string;
        clientId: string;
        senderNexId: string;
        senderSignalDeviceId: number;
        recipientSignalDeviceId: number;
      }>;
    };
    assert.equal(pendingBody.messages.length, 1);
    assert.equal(pendingBody.messages[0]?.clientId, clientId);
    assert.equal(pendingBody.messages[0]?.senderNexId, "NX-ALIC-ALIC");
    assert.equal(pendingBody.messages[0]?.senderSignalDeviceId, 1);
    assert.equal(pendingBody.messages[0]?.recipientSignalDeviceId, 1);

    const envelopeId = pendingBody.messages[0]!.id;
    const ack = await app.inject({
      method: "POST",
      url: "/v1/messages/ack",
      headers: authHeader(bobToken),
      payload: { envelopeIds: [envelopeId] },
    });
    assert.equal(ack.statusCode, 200);
    assert.deepEqual(ack.json(), { acknowledged: 1 });

    // The envelope row is retained as the durable idempotency record, but it
    // must disappear from the pending feed.
    const retained = await db.query<{ delivered_at: Date | string | null }>(
      "SELECT delivered_at FROM envelopes WHERE id = $1",
      [envelopeId],
    );
    assert.equal(retained.length, 1);
    assert.notEqual(retained[0]?.delivered_at, null);

    const pendingAfter = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: authHeader(bobToken),
    });
    assert.deepEqual((pendingAfter.json() as { messages: unknown[] }).messages, []);

    // A duplicate ack must not create a second receipt.
    const duplicateAck = await app.inject({
      method: "POST",
      url: "/v1/messages/ack",
      headers: authHeader(bobToken),
      payload: { envelopeIds: [envelopeId] },
    });
    assert.deepEqual(duplicateAck.json(), { acknowledged: 0 });
    const receipts = await db.query<{ count: number }>(
      "SELECT COUNT(*)::int AS count FROM delivery_receipts WHERE client_id = $1",
      [clientId],
    );
    assert.equal(receipts[0]?.count, 1);

    // A sender retry learns that the message was delivered while it was away.
    const deliveredRetry = await app.inject({
      method: "POST",
      url: "/v1/messages",
      headers: authHeader(aliceToken),
      payload,
    });
    const deliveredBody = deliveredRetry.json() as {
      accepted: Array<{ envelopeId: string; deliveredAt: string | null }>;
    };
    assert.equal(deliveredBody.accepted[0]?.envelopeId, firstBody.accepted[0]?.envelopeId);
    assert.notEqual(deliveredBody.accepted[0]?.deliveredAt, null);
  });

  it("lists durable receipts for the sending device and removes them after ack", async () => {
    const listed = await app.inject({
      method: "GET",
      url: "/v1/messages/receipts",
      headers: authHeader(aliceToken),
    });
    assert.equal(listed.statusCode, 200);
    const body = listed.json() as {
      receipts: Array<{ id: string; clientId: string; deliveredAt: string }>;
    };
    assert.equal(body.receipts.length, 1);
    assert.ok(body.receipts[0]?.deliveredAt);

    const ack = await app.inject({
      method: "POST",
      url: "/v1/messages/receipts/ack",
      headers: authHeader(aliceToken),
      payload: { receiptIds: [body.receipts[0]!.id] },
    });
    assert.equal(ack.statusCode, 200);
    assert.deepEqual(ack.json(), { acknowledged: 1 });

    const after = await app.inject({
      method: "GET",
      url: "/v1/messages/receipts",
      headers: authHeader(aliceToken),
    });
    assert.deepEqual((after.json() as { receipts: unknown[] }).receipts, []);
  });

  it("does not expose another sender device's receipts", async () => {
    const otherDevice = await app.inject({
      method: "GET",
      url: "/v1/messages/receipts",
      headers: authHeader(bobToken),
    });
    assert.equal(otherDevice.statusCode, 200);
    assert.deepEqual((otherDevice.json() as { receipts: unknown[] }).receipts, []);
  });
});
