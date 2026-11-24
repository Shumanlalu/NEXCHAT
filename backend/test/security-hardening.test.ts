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
import { hashToken, TokenService } from "../src/security/tokens";
import { insertDevice } from "../src/routes/auth";

const ALICE_USER = "dddddddd-2222-4222-8222-111111111111";
const ALICE_DEVICE = "dddddddd-2222-4222-8222-222222222222";
const ALICE_NEX_ID = "NX-DDAL-ICAL";
const BOB_USER = "eeeeeeee-2222-4222-8222-111111111111";
const BOB_DEVICE = "eeeeeeee-2222-4222-8222-222222222222";
const BOB_NEX_ID = "NX-EEBO-BOBE";
const KEY = Buffer.from("hardening-test-key-material").toString("base64");
const SETUP_TOKEN = "hardening-setup-token-for-tests";
const ADMIN_PASSWORD = "HardeningAdmin-123";
const TURN_SHARED_SECRET = "turn-shared-secret-for-tests";

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
  iceServers: [
    { urls: ["stun:stun.example.com:3478"] },
    { urls: "turn:turn.example.com:3478", username: "turn-user", credential: "turn-secret" },
  ],
  turnSharedSecret: TURN_SHARED_SECRET,
  trustProxy: false,
  logLevel: "silent",
  adminSetupToken: SETUP_TOKEN,
  adminSessionHours: 12,
};

let app: FastifyInstance;
let db: Database;
let tokens: TokenService;
let aliceToken: string;
let bobToken: string;
let adminCookie = "";
let csrf = "";

function bearer(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}` };
}

function cookiePair(setCookie: string | string[] | undefined): string {
  const value = Array.isArray(setCookie) ? setCookie[0] : setCookie;
  assert.ok(value, "expected a Set-Cookie header");
  return value.split(";")[0]!;
}

function adminHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return { cookie: adminCookie, "x-admin-csrf": csrf, ...extra };
}

async function patchConfig(values: Record<string, unknown>): Promise<void> {
  const response = await app.inject({
    method: "PATCH",
    url: "/v1/admin/config",
    headers: adminHeaders(),
    payload: {
      callsEnabled: true,
      videoCallsEnabled: true,
      maxCallDurationSeconds: 3600,
      maintenanceMode: false,
      maintenanceMessage: "",
      minAppVersion: "1.0.0",
      ...values,
    },
  });
  assert.equal(response.statusCode, 200, response.body);
}

describe("security hardening", () => {
  before(async () => {
    const mem = newDb();
    const schema = readFileSync(path.join(__dirname, "..", "src", "schema.sql"), "utf8");
    mem.public.none(schema);
    const memPg = mem.adapters.createPg();
    db = new Database("postgres://unused");
    (db as unknown as { pool: Pool }).pool = new memPg.Pool();
    app = Fastify({ logger: false });
    await buildApp({ config, db }, app);

    for (const [id, nexId, username, name] of [
      [ALICE_USER, ALICE_NEX_ID, "alicehard", "Alice Hardening"],
      [BOB_USER, BOB_NEX_ID, "bobhard", "Bob Hardening"],
    ] as const) {
      await db.query(
        `INSERT INTO users (id, nex_id, username, display_name, password_hash, password_salt)
         VALUES ($1, $2, $3, $4, 'hash', 'salt')`,
        [id, nexId, username, name],
      );
    }
    for (const [id, userId, signalId] of [
      [ALICE_DEVICE, ALICE_USER, 1],
      [BOB_DEVICE, BOB_USER, 2],
    ] as const) {
      await db.query(
        `INSERT INTO devices
           (id, user_id, name, platform, registration_id, signal_device_id, identity_key,
            signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature,
            kyber_pre_key_id, kyber_pre_key_public, kyber_pre_key_signature)
         VALUES ($1,$2,'Test phone','android',$3,$3,$4,$3,$4,$4,$3,$4,$4)`,
        [id, userId, signalId, KEY],
      );
    }
    tokens = new TokenService(config);
    aliceToken = await tokens.issueAccessToken(ALICE_USER, ALICE_DEVICE);
    bobToken = await tokens.issueAccessToken(BOB_USER, BOB_DEVICE);

    const setup = await app.inject({
      method: "POST",
      url: "/v1/admin/setup",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload: {
        email: "hardening@example.com",
        displayName: "Hardening Admin",
        password: ADMIN_PASSWORD,
        confirmPassword: ADMIN_PASSWORD,
      },
    });
    assert.equal(setup.statusCode, 201, setup.body);
    const login = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: { email: "hardening@example.com", password: ADMIN_PASSWORD },
    });
    assert.equal(login.statusCode, 200, login.body);
    adminCookie = cookiePair(login.headers["set-cookie"]);
    csrf = (login.json() as { csrfToken: string }).csrfToken;
  });

  after(async () => {
    await app?.close();
    await db?.close();
  });

  it("removes TURN credentials from the public configuration", async () => {
    const response = await app.inject({ method: "GET", url: "/v1/config" });
    assert.equal(response.statusCode, 200);
    const body = response.json() as { iceServers: Array<Record<string, unknown>> };
    assert.deepEqual(body.iceServers, [
      { urls: ["stun:stun.example.com:3478"] },
      { urls: "turn:turn.example.com:3478" },
    ]);
    assert.doesNotMatch(response.body, /turn-user|turn-secret|turn-shared-secret-for-tests/);
  });

  it("mints short-lived authenticated TURN credentials only for signed-in users", async () => {
    const unauthenticated = await app.inject({
      method: "GET",
      url: "/v1/turn-credentials",
    });
    assert.equal(unauthenticated.statusCode, 401);

    const response = await app.inject({
      method: "GET",
      url: "/v1/turn-credentials",
      headers: bearer(aliceToken),
    });
    assert.equal(response.statusCode, 200, response.body);
    const body = response.json() as {
      available: boolean;
      username: string;
      credential: string;
      ttlSeconds: number;
      expiresAt: string;
      iceServers: Array<Record<string, unknown>>;
    };
    assert.equal(body.available, true);
    assert.ok(body.username.startsWith(`${ALICE_USER}:`));
    assert.ok(body.credential.length > 20);
    assert.equal(body.ttlSeconds, 3600);
    assert.ok(new Date(body.expiresAt).getTime() > Date.now());
    assert.equal(body.iceServers.length, 1);
    assert.equal(body.iceServers[0]?.username, body.username);
    assert.equal(body.iceServers[0]?.credential, body.credential);
    assert.doesNotMatch(response.body, /turn-shared-secret-for-tests/);
  });

  it("bans an account across API access and discovery, then allows unban", async () => {
    const banned = await app.inject({
      method: "PATCH",
      url: `/v1/admin/users/${ALICE_USER}`,
      headers: adminHeaders(),
      payload: { action: "ban", reason: "Automated test" },
    });
    assert.equal(banned.statusCode, 200, banned.body);

    const blocked = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: bearer(aliceToken),
    });
    assert.equal(blocked.statusCode, 403);
    assert.equal((blocked.json() as { error: { code: string } }).error.code, "ACCOUNT_BANNED");

    const search = await app.inject({
      method: "GET",
      url: `/v1/users/search?q=${ALICE_NEX_ID}`,
      headers: bearer(bobToken),
    });
    assert.equal(search.statusCode, 200);
    assert.deepEqual((search.json() as { users: unknown[] }).users, []);

    const profile = await app.inject({
      method: "GET",
      url: `/v1/users/${ALICE_NEX_ID}`,
      headers: bearer(bobToken),
    });
    assert.equal(profile.statusCode, 404);

    const exported = await app.inject({
      method: "GET",
      url: "/v1/admin/reports/users/export.csv?range=30d&status=banned",
      headers: adminHeaders(),
    });
    assert.equal(exported.statusCode, 200, exported.body);
    assert.match(exported.body, new RegExp(`${ALICE_NEX_ID},[^\\r\\n]*,banned,`));
    assert.doesNotMatch(exported.body, new RegExp(BOB_NEX_ID));

    const unbanned = await app.inject({
      method: "PATCH",
      url: `/v1/admin/users/${ALICE_USER}`,
      headers: adminHeaders(),
      payload: { action: "unban" },
    });
    assert.equal(unbanned.statusCode, 200, unbanned.body);
    const allowed = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: bearer(aliceToken),
    });
    assert.equal(allowed.statusCode, 200);
  });

  it("enforces maintenance and minimum-version policy on mobile API calls", async () => {
    await patchConfig({ maintenanceMode: true, maintenanceMessage: "Back soon" });
    const maintenance = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: bearer(aliceToken),
    });
    assert.equal(maintenance.statusCode, 503);
    assert.equal((maintenance.json() as { error: { code: string } }).error.code, "MAINTENANCE_MODE");
    const publicConfig = await app.inject({ method: "GET", url: "/v1/config" });
    assert.equal(publicConfig.statusCode, 200);

    await patchConfig({ maintenanceMode: false, minAppVersion: "1.2.0" });
    const outdated = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: { ...bearer(aliceToken), "x-app-version": "1.1.9" },
    });
    assert.equal(outdated.statusCode, 426);
    assert.equal((outdated.json() as { error: { code: string } }).error.code, "APP_UPDATE_REQUIRED");

    // Real Android clients send their version in User-Agent, not x-app-version.
    const outdatedAgent = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: { ...bearer(aliceToken), "user-agent": "NEXCHAT-Android/1.1.9" },
    });
    assert.equal(outdatedAgent.statusCode, 426);
    assert.equal(
      (outdatedAgent.json() as { error: { code: string } }).error.code,
      "APP_UPDATE_REQUIRED",
    );

    const current = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: { ...bearer(aliceToken), "x-app-version": "1.2.0" },
    });
    assert.equal(current.statusCode, 200);
    await patchConfig({});
  });

  it("enforces admin call feature flags on telemetry writes", async () => {
    await patchConfig({ callsEnabled: false, videoCallsEnabled: false });
    const response = await app.inject({
      method: "POST",
      url: "/v1/analytics/calls",
      headers: bearer(aliceToken),
      payload: {
        callId: randomUUID(),
        role: "caller",
        peerNexId: BOB_NEX_ID,
        mode: "audio",
        status: "ringing",
      },
    });
    assert.equal(response.statusCode, 403);
    assert.equal((response.json() as { error: { code: string } }).error.code, "CALL_DISABLED");
    await patchConfig({});
  });

  it("keeps device revocation durable and closes the revoked session", async () => {
    const revoked = await app.inject({
      method: "DELETE",
      url: `/v1/devices/${BOB_DEVICE}`,
      headers: bearer(bobToken),
    });
    assert.equal(revoked.statusCode, 200, revoked.body);
    const blocked = await app.inject({
      method: "GET",
      url: "/v1/me",
      headers: bearer(bobToken),
    });
    assert.equal(blocked.statusCode, 401);
    assert.equal((blocked.json() as { error: { code: string } }).error.code, "SESSION_REVOKED");

    await assert.rejects(
      insertDevice(db, BOB_USER, {
        deviceId: BOB_DEVICE,
        name: "Reinstalled",
        platform: "android",
        registrationId: 2,
        signalDeviceId: 2,
        identityKey: KEY,
        signedPreKeyId: 2,
        signedPreKeyPublic: KEY,
        signedPreKeySignature: KEY,
        kyberPreKeyId: 2,
        kyberPreKeyPublic: KEY,
        kyberPreKeySignature: KEY,
        oneTimePreKeys: [],
      }, true),
      /device revoked/,
    );
  });

  it("consumes a refresh token atomically under concurrent use", async () => {
    const refresh = tokens.createRefreshToken();
    await db.query(
      `INSERT INTO refresh_tokens (id, user_id, device_id, token_hash, expires_at)
       VALUES ($1,$2,$3,$4,$5)`,
      [randomUUID(), ALICE_USER, ALICE_DEVICE, refresh.hash, tokens.refreshExpiry()],
    );
    const [first, second] = await Promise.all([
      app.inject({
        method: "POST",
        url: "/v1/auth/refresh",
        payload: { refreshToken: refresh.token },
      }),
      app.inject({
        method: "POST",
        url: "/v1/auth/refresh",
        payload: { refreshToken: refresh.token },
      }),
    ]);
    const statuses = [first.statusCode, second.statusCode].sort();
    assert.deepEqual(statuses, [200, 401]);
    const row = await db.one<{ revoked_at: Date | string | null }>(
      "SELECT revoked_at FROM refresh_tokens WHERE token_hash = $1",
      [hashToken(refresh.token)],
    );
    assert.ok(row?.revoked_at);
  });

  it("neutralizes spreadsheet formulas in exports and audits the export", async () => {
    await db.query("UPDATE users SET display_name = $1 WHERE id = $2", [
      "=IMPORTXML(\"http://example.com\",\"//a\")",
      ALICE_USER,
    ]);
    const response = await app.inject({
      method: "GET",
      url: "/v1/admin/reports/users/export.csv?range=7d",
      headers: { cookie: adminCookie },
    });
    assert.equal(response.statusCode, 200, response.body);
    assert.match(response.body, /'=IMPORTXML/);

    const audit = await app.inject({
      method: "GET",
      url: "/v1/admin/audit?range=7d&pageSize=100",
      headers: { cookie: adminCookie },
    });
    assert.equal(audit.statusCode, 200);
    const actions = (audit.json() as { logs: Array<{ action: string; details: unknown }> }).logs;
    const exported = actions.find((log) => log.action === "report.exported");
    assert.ok(exported, "expected a report.exported audit entry");
    assert.match(JSON.stringify(exported.details), /rowCount/);
  });

  it("redacts identifiers and secrets in uploaded crash reports", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/v1/analytics/crashes",
      headers: bearer(aliceToken),
      payload: {
        message: `Failed for ${ALICE_NEX_ID} with token abc.def.ghi`,
        stack: `at com.example.Call(${ALICE_NEX_ID})`,
        context: { authToken: "do-not-store", note: ALICE_NEX_ID },
      },
    });
    assert.equal(response.statusCode, 201, response.body);
    const row = await db.one<{ message: string; stack: string; context: unknown }>(
      "SELECT message, stack, context FROM crash_reports ORDER BY created_at DESC LIMIT 1",
    );
    assert.ok(row);
    assert.doesNotMatch(row.message, /NX-/);
    assert.doesNotMatch(row.stack ?? "", /NX-/);
    assert.doesNotMatch(JSON.stringify(row.context), /do-not-store|NX-/);
  });

  it("rejects malformed cookies and requires CSRF for session clearing", async () => {
    const malformed = await app.inject({
      method: "GET",
      url: "/v1/admin/auth/me",
      headers: { cookie: "nexchat_admin_session=%ZZ" },
    });
    assert.equal(malformed.statusCode, 401);
    assert.notEqual(malformed.statusCode, 500);

    const withoutCsrf = await app.inject({
      method: "DELETE",
      url: "/v1/admin/auth/session",
      headers: { cookie: adminCookie },
    });
    assert.equal(withoutCsrf.statusCode, 403);

    const withCsrf = await app.inject({
      method: "DELETE",
      url: "/v1/admin/auth/session",
      headers: adminHeaders(),
    });
    assert.equal(withCsrf.statusCode, 200);
  });
});
