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
import { totpAt } from "../src/admin/security";

const USER = "cccccccc-2222-4222-8222-111111111111";
const DEVICE = "cccccccc-2222-4222-8222-222222222222";
const NEX_ID = "NX-CCCC-CCCC";
const KEY = Buffer.from("admin-test-key-material").toString("base64");
const SETUP_TOKEN = "admin-setup-token-for-tests-only";

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
  adminSetupToken: SETUP_TOKEN,
  adminSessionHours: 12,
  cronSecret: "cron-test-secret",
};

let app: FastifyInstance;
let db: Database;
let userToken: string;
let cookie = "";
let csrf = "";
let totpSecret = "";

function cookiePair(setCookie: string | string[] | undefined): string {
  const value = Array.isArray(setCookie) ? setCookie[0] : setCookie;
  assert.ok(value, "expected a Set-Cookie header");
  return value.split(";")[0]!;
}

function adminHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return { cookie, "x-admin-csrf": csrf, ...extra };
}

describe("super admin panel", () => {
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
       VALUES ($1, $2, 'adminuser', 'Admin Test User', 'hash', 'salt')`,
      [USER, NEX_ID],
    );
    await db.query(
      `INSERT INTO devices
         (id, user_id, name, platform, registration_id, signal_device_id, identity_key,
          signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature,
          kyber_pre_key_id, kyber_pre_key_public, kyber_pre_key_signature)
       VALUES ($1,$2,'Test phone','android',7,1,$3,7,$3,$3,7,$3,$3)`,
      [DEVICE, USER, KEY],
    );
    const tokens = new TokenService(config);
    userToken = await tokens.issueAccessToken(USER, DEVICE);
  });

  after(async () => {
    await app?.close();
    await db?.close();
  });

  it("requires the bootstrap token and creates exactly one super admin", async () => {
    const bootstrap = await app.inject({ method: "GET", url: "/v1/admin/bootstrap" });
    assert.equal(bootstrap.statusCode, 200);
    assert.deepEqual(bootstrap.json(), {
      setupRequired: true,
      setupEnabled: true,
      signupEnabled: true,
    });

    const rejected = await app.inject({
      method: "POST",
      url: "/v1/admin/setup",
      headers: { "x-setup-token": "wrong" },
      payload: { username: "rootadmin", displayName: "Root Admin", password: "AdminPassword-123" },
    });
    assert.equal(rejected.statusCode, 403);

    const created = await app.inject({
      method: "POST",
      url: "/v1/admin/setup",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload: { username: "rootadmin", displayName: "Root Admin", password: "AdminPassword-123" },
    });
    assert.equal(created.statusCode, 201);
    const body = created.json() as { totpSecret: string; otpauthUri: string; qrDataUrl: string };
    assert.match(body.totpSecret, /^[A-Z2-7]{16,}$/);
    assert.match(body.otpauthUri, /^otpauth:\/\/totp\//);
    assert.match(body.qrDataUrl, /^data:image\/png;base64,/);
    totpSecret = body.totpSecret;

    const again = await app.inject({
      method: "POST",
      url: "/v1/admin/setup",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload: { username: "second", displayName: "Second", password: "AdminPassword-123" },
    });
    assert.equal(again.statusCode, 409);
  });

  it("logs in with password plus TOTP and sets a hardened cookie", async () => {
    const wrong = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: { username: "rootadmin", password: "AdminPassword-123", code: "000000" },
    });
    assert.equal(wrong.statusCode, 401);

    const response = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: {
        username: "rootadmin",
        password: "AdminPassword-123",
        code: totpAt(totpSecret),
      },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json() as { csrfToken: string; admin: { role: string } };
    assert.equal(body.admin.role, "super_admin");
    assert.ok(body.csrfToken.length > 20);
    csrf = body.csrfToken;
    const setCookie = response.headers["set-cookie"];
    const raw = Array.isArray(setCookie) ? setCookie[0]! : String(setCookie);
    assert.match(raw, /HttpOnly/);
    assert.match(raw, /SameSite=Strict/);
    cookie = cookiePair(setCookie);

    const me = await app.inject({
      method: "GET",
      url: "/v1/admin/auth/me",
      headers: { cookie },
    });
    assert.equal(me.statusCode, 200);
    assert.equal((me.json() as { admin: { username: string } }).admin.username, "rootadmin");
  });

  it("rejects a mutation without the CSRF token", async () => {
    const response = await app.inject({
      method: "PATCH",
      url: "/v1/admin/config",
      headers: { cookie },
      payload: {
        callsEnabled: false,
        videoCallsEnabled: false,
        maxCallDurationSeconds: 3600,
        maintenanceMode: false,
        maintenanceMessage: "",
        minAppVersion: "1.0.0",
      },
    });
    assert.equal(response.statusCode, 403);
  });

  it("publishes remote config and returns it through the public endpoint", async () => {
    const response = await app.inject({
      method: "PATCH",
      url: "/v1/admin/config",
      headers: adminHeaders(),
      payload: {
        callsEnabled: false,
        videoCallsEnabled: false,
        maxCallDurationSeconds: 1800,
        maintenanceMode: true,
        maintenanceMessage: "Maintenance in progress",
        minAppVersion: "1.0.5",
      },
    });
    assert.equal(response.statusCode, 200);
    const publicConfig = await app.inject({ method: "GET", url: "/v1/config" });
    const body = publicConfig.json() as {
      remote: { callsEnabled: boolean; maxCallDurationSeconds: number; maintenanceMode: boolean };
      features: { audioCalls: string };
    };
    assert.equal(body.remote.callsEnabled, false);
    assert.equal(body.remote.maxCallDurationSeconds, 1800);
    assert.equal(body.remote.maintenanceMode, true);
    assert.equal(body.features.audioCalls, "disabled");
  });

  it("publishes an announcement that the public endpoint exposes", async () => {
    const created = await app.inject({
      method: "POST",
      url: "/v1/admin/announcements",
      headers: adminHeaders(),
      payload: {
        title: "Scheduled maintenance",
        body: "Calling will be briefly unavailable.",
        severity: "warning",
        active: true,
      },
    });
    assert.equal(created.statusCode, 201);
    const publicConfig = await app.inject({ method: "GET", url: "/v1/config" });
    const body = publicConfig.json() as { announcement: { title: string } | null };
    assert.equal(body.announcement?.title, "Scheduled maintenance");
  });

  it("suspends a user and immediately blocks authenticated API access", async () => {
    const before = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: { authorization: `Bearer ${userToken}` },
    });
    assert.equal(before.statusCode, 200);

    const suspended = await app.inject({
      method: "PATCH",
      url: `/v1/admin/users/${USER}`,
      headers: adminHeaders(),
      payload: { action: "suspend", reason: "Automated test" },
    });
    assert.equal(suspended.statusCode, 200);

    const blocked = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: { authorization: `Bearer ${userToken}` },
    });
    assert.equal(blocked.statusCode, 403);
    assert.equal((blocked.json() as { error: { code: string } }).error.code, "ACCOUNT_SUSPENDED");

    const restored = await app.inject({
      method: "PATCH",
      url: `/v1/admin/users/${USER}`,
      headers: adminHeaders(),
      payload: { action: "restore" },
    });
    assert.equal(restored.statusCode, 200);
    const allowed = await app.inject({
      method: "GET",
      url: "/v1/messages/pending",
      headers: { authorization: `Bearer ${userToken}` },
    });
    assert.equal(allowed.statusCode, 200);
  });

  it("records sensitive actions in the audit log", async () => {
    const response = await app.inject({
      method: "GET",
      url: "/v1/admin/audit?range=7d&pageSize=100",
      headers: { cookie },
    });
    assert.equal(response.statusCode, 200);
    const logs = (response.json() as { logs: Array<{ action: string }> }).logs;
    const actions = logs.map((log) => log.action);
    assert.ok(actions.includes("admin.login.succeeded"));
    assert.ok(actions.includes("config.updated"));
    assert.ok(actions.includes("user.suspend"));
    assert.ok(actions.includes("announcement.created"));
  });

  it("enforces role permissions for an analyst", async () => {
    const created = await app.inject({
      method: "POST",
      url: "/v1/admin/admins",
      headers: adminHeaders(),
      payload: {
        username: "analyst1",
        displayName: "Analyst",
        password: "AnalystPassword-123",
        role: "analyst",
      },
    });
    assert.equal(created.statusCode, 201);
    const analystSecret = (created.json() as { totpSecret: string }).totpSecret;
    const login = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: {
        username: "analyst1",
        password: "AnalystPassword-123",
        code: totpAt(analystSecret),
      },
    });
    assert.equal(login.statusCode, 200);
    const loginBody = login.json() as { csrfToken: string };
    const analystCookie = cookiePair(login.headers["set-cookie"]);
    const configWrite = await app.inject({
      method: "PATCH",
      url: "/v1/admin/config",
      headers: { cookie: analystCookie, "x-admin-csrf": loginBody.csrfToken },
      payload: {
        callsEnabled: true,
        videoCallsEnabled: false,
        maxCallDurationSeconds: 3600,
        maintenanceMode: false,
        maintenanceMessage: "",
        minAppVersion: "1.0.0",
      },
    });
    assert.equal(configWrite.statusCode, 403);
    const dashboard = await app.inject({
      method: "GET",
      url: "/v1/admin/dashboard?range=7d",
      headers: { cookie: analystCookie },
    });
    assert.equal(dashboard.statusCode, 200, dashboard.body);
  });

  it("deletes an administrator and protects the acting account", async () => {
    const list = await app.inject({
      method: "GET",
      url: "/v1/admin/admins",
      headers: { cookie },
    });
    assert.equal(list.statusCode, 200);
    const admins = (list.json() as { admins: Array<{ id: string; username: string }> }).admins;
    const analyst = admins.find((admin) => admin.username === "analyst1");
    assert.ok(analyst, "analyst should exist before deletion");

    const deleted = await app.inject({
      method: "DELETE",
      url: `/v1/admin/admins/${analyst.id}`,
      headers: adminHeaders(),
    });
    assert.equal(deleted.statusCode, 200);

    const after = await app.inject({
      method: "GET",
      url: "/v1/admin/admins",
      headers: { cookie },
    });
    const remaining = (after.json() as { admins: Array<{ username: string }> }).admins;
    assert.equal(remaining.some((admin) => admin.username === "analyst1"), false);

    const relogin = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: {
        username: "analyst1",
        password: "AnalystPassword-123",
        code: "000000",
      },
    });
    assert.equal(relogin.statusCode, 401);

    const self = await app.inject({
      method: "GET",
      url: "/v1/admin/auth/me",
      headers: { cookie },
    });
    const selfId = (self.json() as { admin: { id: string } }).admin.id;
    const selfDelete = await app.inject({
      method: "DELETE",
      url: `/v1/admin/admins/${selfId}`,
      headers: adminHeaders(),
    });
    assert.equal(selfDelete.statusCode, 409);
    assert.equal((selfDelete.json() as { error: { code: string } }).error.code, "SELF_DELETE");

    const audit = await app.inject({
      method: "GET",
      url: "/v1/admin/audit?range=7d&pageSize=100",
      headers: { cookie },
    });
    const actions = (audit.json() as { logs: Array<{ action: string }> }).logs.map(
      (log) => log.action,
    );
    assert.ok(actions.includes("admin.deleted"));
  });

  it("serves the admin SPA with a nonce-based CSP", async () => {
    const response = await app.inject({ method: "GET", url: "/admin" });
    assert.equal(response.statusCode, 200);
    const csp = String(response.headers["content-security-policy"]);
    assert.match(csp, /script-src 'nonce-[A-Za-z0-9+/=]+'/);
    assert.match(response.body, /NEXCHAT Super Admin/);
    assert.match(response.body, /nonce="[A-Za-z0-9+/=]+"/);
    assert.doesNotMatch(response.body, /__CSP_NONCE__/);
  });

  it("creates an additional admin through the token-gated signup page", async () => {
    const payload = {
      username: "shuman",
      displayName: "Shuman",
      password: "AdminPassword-123",
    };
    const rejected = await app.inject({
      method: "POST",
      url: "/v1/admin/signup",
      headers: { "x-setup-token": "wrong" },
      payload,
    });
    assert.equal(rejected.statusCode, 403);

    const created = await app.inject({
      method: "POST",
      url: "/v1/admin/signup",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload,
    });
    assert.equal(created.statusCode, 201);
    const body = created.json() as { username: string; totpSecret: string; qrDataUrl: string };
    assert.equal(body.username, "shuman");
    assert.match(body.qrDataUrl, /^data:image\/png;base64,/);
    const row = await db.one<{ role: string }>(
      "SELECT role FROM admin_users WHERE username = $1",
      ["shuman"],
    );
    assert.equal(row?.role, "super_admin");
  });
});
