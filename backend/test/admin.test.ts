import assert from "node:assert/strict";
import { randomBytes, randomUUID, scryptSync } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import Fastify, { type FastifyInstance } from "fastify";
import { newDb } from "pg-mem";
import type { Pool } from "pg";
import type { AppConfig } from "../src/config";
import { buildApp, fastifyOptions } from "../src/create-app";
import { Database } from "../src/db";
import { hashSessionToken } from "../src/admin/security";
import { TokenService } from "../src/security/tokens";

const USER = "cccccccc-2222-4222-8222-111111111111";
const DEVICE = "cccccccc-2222-4222-8222-222222222222";
const NEX_ID = "NX-CCCC-CCCC";
const KEY = Buffer.from("admin-test-key-material").toString("base64");
const SETUP_TOKEN = "admin-setup-token-for-tests-only";
const TEST_PASSWORD = "AdminPassword-123";
const NEW_PASSWORD = "FreshAdminPassword-456";

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
let legacyCookie = "";
let legacyCsrf = "";

function cookiePair(setCookie: string | string[] | undefined): string {
  const value = Array.isArray(setCookie) ? setCookie[0] : setCookie;
  assert.ok(value, "expected a Set-Cookie header");
  return value.split(";")[0]!;
}

function sessionToken(cookiePairValue: string): string {
  const separator = cookiePairValue.indexOf("=");
  assert.ok(separator >= 0, "expected a session cookie value");
  return decodeURIComponent(cookiePairValue.slice(separator + 1));
}

function adminHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return { cookie, "x-admin-csrf": csrf, ...extra };
}

function legacyScryptHash(password: string): { hash: string; salt: string } {
  const salt = randomBytes(16);
  const derived = scryptSync(password.normalize("NFKC"), salt, 64, {
    N: 32768,
    r: 8,
    p: 2,
    maxmem: 64 * 1024 * 1024,
  });
  return {
    hash: derived.toString("base64"),
    salt: Buffer.concat([salt, Buffer.from([1])]).toString("base64"),
  };
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
      signupEnabled: false,
      sessionHours: 12,
      rememberDays: 30,
    });

    const rejected = await app.inject({
      method: "POST",
      url: "/v1/admin/setup",
      headers: { "x-setup-token": "wrong" },
      payload: {
        displayName: "Root Admin",
        email: "rootadmin@example.com",
        password: TEST_PASSWORD,
        confirmPassword: TEST_PASSWORD,
      },
    });
    assert.equal(rejected.statusCode, 403);

    const created = await app.inject({
      method: "POST",
      url: "/v1/admin/setup",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload: {
        displayName: "Root Admin",
        email: "rootadmin@example.com",
        password: TEST_PASSWORD,
        confirmPassword: TEST_PASSWORD,
      },
    });
    assert.equal(created.statusCode, 201);
    const body = created.json() as { email: string; username: string };
    assert.equal(body.email, "rootadmin@example.com");
    assert.equal(body.username, "rootadmin@example.com");

    const row = await db.one<{ display_name: string; role: string; password_salt: string }>(
      "SELECT display_name, role, password_salt FROM admin_users WHERE username = $1",
      ["rootadmin@example.com"],
    );
    assert.equal(row?.display_name, "Root Admin");
    assert.equal(row?.role, "super_admin");
    assert.equal(row?.password_salt, "bcrypt-sha256:v1");

    const again = await app.inject({
      method: "POST",
      url: "/v1/admin/setup",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload: {
        displayName: "Second",
        email: "second@example.com",
        password: TEST_PASSWORD,
        confirmPassword: TEST_PASSWORD,
      },
    });
    assert.equal(again.statusCode, 409);
    assert.equal((again.json() as { error: { code: string } }).error.code, "SETUP_CLOSED");

    const afterSetup = await app.inject({ method: "GET", url: "/v1/admin/bootstrap" });
    assert.deepEqual(afterSetup.json(), {
      setupRequired: false,
      setupEnabled: false,
      signupEnabled: false,
      sessionHours: 12,
      rememberDays: 30,
    });
  });

  it("logs in with email and password and sets a hardened cookie", async () => {
    const wrong = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: { email: "rootadmin@example.com", password: "WrongPassword-123" },
    });
    assert.equal(wrong.statusCode, 401);
    assert.equal((wrong.json() as { error: { code: string } }).error.code, "ADMIN_LOGIN_FAILED");

    const response = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: {
        email: "rootadmin@example.com",
        password: TEST_PASSWORD,
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
    assert.match(raw, /Max-Age=43200/);
    cookie = cookiePair(setCookie);

    const me = await app.inject({
      method: "GET",
      url: "/v1/admin/auth/me",
      headers: { cookie },
    });
    assert.equal(me.statusCode, 200);
    assert.equal((me.json() as { admin: { username: string } }).admin.username, "rootadmin@example.com");
  });

  it("extends the cookie lifetime when remember me is selected", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: {
        email: "rootadmin@example.com",
        password: TEST_PASSWORD,
        remember: true,
      },
    });
    assert.equal(response.statusCode, 200);
    const raw = Array.isArray(response.headers["set-cookie"])
      ? response.headers["set-cookie"]![0]!
      : String(response.headers["set-cookie"]);
    assert.match(raw, /Max-Age=2592000/);
    const expiresAt = (response.json() as { expiresAt: string }).expiresAt;
    assert.ok(new Date(expiresAt).getTime() > Date.now() + 29 * 86_400_000);
  });

  it("upgrades legacy scrypt admins to bcrypt while keeping legacy usernames usable", async () => {
    const legacy = legacyScryptHash(TEST_PASSWORD);
    await db.query(
      `INSERT INTO admin_users
         (id, username, display_name, password_hash, password_salt, totp_secret_enc, role)
       VALUES ($1,$2,$3,$4,$5,'not-used','admin')`,
      [randomUUID(), "legacyadmin", "Legacy Admin", legacy.hash, legacy.salt],
    );
    const login = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: { email: "legacyadmin", password: TEST_PASSWORD },
    });
    assert.equal(login.statusCode, 200);
    legacyCookie = cookiePair(login.headers["set-cookie"]);
    legacyCsrf = (login.json() as { csrfToken: string }).csrfToken;
    const row = await db.one<{ password_hash: string; password_salt: string }>(
      "SELECT password_hash, password_salt FROM admin_users WHERE username = $1",
      ["legacyadmin"],
    );
    assert.equal(row?.password_salt, "bcrypt-sha256:v1");
    assert.match(row?.password_hash ?? "", /^\$2[aby]\$/);
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
    assert.equal((response.json() as { error: { code: string } }).error.code, "CSRF_REJECTED");
  });

  it("blocks unauthenticated and non-super-admin access", async () => {
    const anonymous = await app.inject({ method: "GET", url: "/v1/admin/dashboard?range=7d" });
    assert.equal(anonymous.statusCode, 401);

    const bearerOnly = await app.inject({
      method: "GET",
      url: "/v1/admin/admins",
      headers: { authorization: `Bearer ${userToken}` },
    });
    assert.equal(bearerOnly.statusCode, 401);

    const createAsUser = await app.inject({
      method: "POST",
      url: "/v1/admin/admins",
      headers: { authorization: `Bearer ${userToken}` },
      payload: {
        displayName: "Illegal Admin",
        email: "illegal@example.com",
        password: TEST_PASSWORD,
        confirmPassword: TEST_PASSWORD,
        role: "super_admin",
      },
    });
    assert.equal(createAsUser.statusCode, 401);

    const createAsAdmin = await app.inject({
      method: "POST",
      url: "/v1/admin/admins",
      headers: { cookie: legacyCookie, "x-admin-csrf": legacyCsrf },
      payload: {
        displayName: "Another Illegal Admin",
        email: "illegal2@example.com",
        password: TEST_PASSWORD,
        confirmPassword: TEST_PASSWORD,
        role: "super_admin",
      },
    });
    assert.equal(createAsAdmin.statusCode, 403);
    assert.equal((createAsAdmin.json() as { error: { code: string } }).error.code, "ADMIN_FORBIDDEN");
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
        displayName: "Analyst One",
        email: "analyst1@example.com",
        password: "AnalystPassword-123",
        confirmPassword: "AnalystPassword-123",
        role: "analyst",
      },
    });
    assert.equal(created.statusCode, 201);
    const login = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: {
        email: "analyst1@example.com",
        password: "AnalystPassword-123",
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
    const analyst = admins.find((admin) => admin.username === "analyst1@example.com");
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
    assert.equal(remaining.some((admin) => admin.username === "analyst1@example.com"), false);

    const relogin = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: {
        email: "analyst1@example.com",
        password: "AnalystPassword-123",
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

  it("serves the admin SPA with the requested sign-in controls and nonce CSP", async () => {
    const response = await app.inject({ method: "GET", url: "/admin" });
    assert.equal(response.statusCode, 200);
    const csp = String(response.headers["content-security-policy"]);
    assert.match(csp, /script-src 'nonce-[A-Za-z0-9+/=]+'/);
    assert.match(response.body, /NEXCHAT Super Admin/);
    assert.match(response.body, /nonce="[A-Za-z0-9+/=]+"/);
    assert.doesNotMatch(response.body, /__CSP_NONCE__/);
    assert.match(response.body, /id="login-remember"/);
    assert.match(response.body, /Forgot password\?/);
    assert.match(response.body, /data-password-toggle/);
    assert.match(response.body, /Create Account/);
    assert.doesNotMatch(response.body, /id="signup-|showSignup|v1\/admin\/signup/);
    assert.doesNotMatch(response.body, /Sign in with Google|Google Sign-In|google-auth-library/i);
  });

  it("closes setup permanently and exposes no public administrator signup", async () => {
    const duplicateSetup = await app.inject({
      method: "POST",
      url: "/v1/admin/setup",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload: {
        displayName: "Duplicate",
        email: "duplicate@example.com",
        password: TEST_PASSWORD,
        confirmPassword: TEST_PASSWORD,
      },
    });
    assert.equal(duplicateSetup.statusCode, 409);

    const duplicateAdmin = await app.inject({
      method: "POST",
      url: "/v1/admin/admins",
      headers: adminHeaders(),
      payload: {
        displayName: "Duplicate Admin",
        email: "rootadmin@example.com",
        password: TEST_PASSWORD,
        confirmPassword: TEST_PASSWORD,
        role: "admin",
      },
    });
    assert.equal(duplicateAdmin.statusCode, 409);
    assert.equal((duplicateAdmin.json() as { error: { code: string } }).error.code, "ADMIN_EXISTS");

    const signupEndpoint = await app.inject({
      method: "POST",
      url: "/v1/admin/signup",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload: {
        displayName: "Public Admin",
        email: "public@example.com",
        password: TEST_PASSWORD,
        confirmPassword: TEST_PASSWORD,
      },
    });
    assert.equal(signupEndpoint.statusCode, 404);

    const signupPage = await app.inject({ method: "GET", url: "/admin/signup" });
    assert.equal(signupPage.statusCode, 404);

    const registrationRead = await app.inject({
      method: "GET",
      url: "/v1/admin/registration",
      headers: { cookie },
    });
    assert.equal(registrationRead.statusCode, 404);

    const registrationWrite = await app.inject({
      method: "PUT",
      url: "/v1/admin/registration",
      headers: adminHeaders(),
      payload: { signupEnabled: true },
    });
    assert.equal(registrationWrite.statusCode, 404);
  });

  it("never exposes the setup token in responses or logs", async () => {
    const redact = (fastifyOptions(config).logger as { redact: { paths: string[] } }).redact;
    assert.ok(redact.paths.includes("req.headers.x-setup-token"));
    assert.ok(redact.paths.includes("req.body.resetToken"));
    assert.ok(redact.paths.includes("res.headers.set-cookie"));

    for (const url of ["/v1/admin/bootstrap", "/admin", "/admin/forgot"]) {
      const response = await app.inject({ method: "GET", url });
      assert.doesNotMatch(response.body, new RegExp(SETUP_TOKEN));
    }
  });

  it("resets a forgotten password with the setup token and revokes old sessions", async () => {
    // The first setup uses rootadmin; create a dedicated recovery account.
    const recoveryAdmin = await app.inject({
      method: "POST",
      url: "/v1/admin/admins",
      headers: adminHeaders(),
      payload: {
        displayName: "Recovery Admin",
        email: "recovery@example.com",
        password: TEST_PASSWORD,
        confirmPassword: TEST_PASSWORD,
        role: "super_admin",
      },
    });
    assert.equal(recoveryAdmin.statusCode, 201);

    const login = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: { email: "recovery@example.com", password: TEST_PASSWORD },
    });
    assert.equal(login.statusCode, 200);
    const oldCookie = cookiePair(login.headers["set-cookie"]);

    const wrongToken = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/reset/request",
      headers: { "x-setup-token": "wrong" },
      payload: { email: "recovery@example.com" },
    });
    assert.equal(wrongToken.statusCode, 403);

    const missingAdmin = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/reset/request",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload: { email: "missing-admin@example.com" },
    });
    assert.equal(missingAdmin.statusCode, 404);

    const request = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/reset/request",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload: { email: "recovery@example.com" },
    });
    assert.equal(request.statusCode, 200);
    const requested = request.json() as { email: string; resetToken: string; expiresAt: string };
    assert.equal(requested.email, "recovery@example.com");
    assert.ok(requested.resetToken.length >= 20);
    assert.doesNotMatch(request.body, new RegExp(SETUP_TOKEN));
    const expiresInMs = new Date(requested.expiresAt).getTime() - Date.now();
    assert.ok(expiresInMs > 14 * 60 * 1000 && expiresInMs <= 15 * 60 * 1000);

    const wrongOwner = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/reset/confirm",
      payload: {
        token: requested.resetToken,
        email: "rootadmin@example.com",
        newPassword: NEW_PASSWORD,
        confirmPassword: NEW_PASSWORD,
      },
    });
    assert.equal(wrongOwner.statusCode, 400);
    assert.equal((wrongOwner.json() as { error: { code: string } }).error.code, "RESET_TOKEN_INVALID");

    const mismatch = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/reset/confirm",
      payload: {
        token: requested.resetToken,
        email: "recovery@example.com",
        newPassword: NEW_PASSWORD,
        confirmPassword: "DifferentPassword-789",
      },
    });
    assert.equal(mismatch.statusCode, 400);

    const confirmed = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/reset/confirm",
      payload: {
        token: requested.resetToken,
        email: "recovery@example.com",
        newPassword: NEW_PASSWORD,
        confirmPassword: NEW_PASSWORD,
      },
    });
    assert.equal(confirmed.statusCode, 200);

    const revoked = await app.inject({
      method: "GET",
      url: "/v1/admin/auth/me",
      headers: { cookie: oldCookie },
    });
    assert.equal(revoked.statusCode, 401);

    const reused = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/reset/confirm",
      payload: {
        token: requested.resetToken,
        email: "recovery@example.com",
        newPassword: NEW_PASSWORD,
        confirmPassword: NEW_PASSWORD,
      },
    });
    assert.equal(reused.statusCode, 400);

    const oldPassword = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: { email: "recovery@example.com", password: TEST_PASSWORD },
    });
    assert.equal(oldPassword.statusCode, 401);

    const newPassword = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: { email: "recovery@example.com", password: NEW_PASSWORD },
    });
    assert.equal(newPassword.statusCode, 200);
  });

  it("rejects an expired one-time reset token", async () => {
    const request = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/reset/request",
      headers: { "x-setup-token": SETUP_TOKEN },
      payload: { email: "rootadmin@example.com" },
    });
    assert.equal(request.statusCode, 200);
    const { resetToken } = request.json() as { resetToken: string };

    await db.query("UPDATE admin_password_resets SET expires_at = $1 WHERE token_hash = $2", [
      new Date(Date.now() - 1000),
      hashSessionToken(resetToken),
    ]);

    const response = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/reset/confirm",
      payload: {
        token: resetToken,
        email: "rootadmin@example.com",
        newPassword: NEW_PASSWORD,
        confirmPassword: NEW_PASSWORD,
      },
    });
    assert.equal(response.statusCode, 400);
    assert.equal((response.json() as { error: { code: string } }).error.code, "RESET_TOKEN_INVALID");

    const login = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: { email: "rootadmin@example.com", password: TEST_PASSWORD },
    });
    assert.equal(login.statusCode, 200);
  });

  it("logs out by revoking only the current session", async () => {
    const login = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: { email: "recovery@example.com", password: NEW_PASSWORD },
    });
    assert.equal(login.statusCode, 200);
    const sessionCookie = cookiePair(login.headers["set-cookie"]);
    const sessionCsrf = (login.json() as { csrfToken: string }).csrfToken;

    const logout = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/logout",
      headers: { cookie: sessionCookie, "x-admin-csrf": sessionCsrf },
    });
    assert.equal(logout.statusCode, 200);
    const afterLogout = await app.inject({
      method: "GET",
      url: "/v1/admin/auth/me",
      headers: { cookie: sessionCookie },
    });
    assert.equal(afterLogout.statusCode, 401);

    const mainStillValid = await app.inject({
      method: "GET",
      url: "/v1/admin/auth/me",
      headers: { cookie },
    });
    assert.equal(mainStillValid.statusCode, 200);
  });

  it("refuses expired admin sessions", async () => {
    const login = await app.inject({
      method: "POST",
      url: "/v1/admin/auth/login",
      payload: { email: "recovery@example.com", password: NEW_PASSWORD },
    });
    assert.equal(login.statusCode, 200);
    const expiredCookie = cookiePair(login.headers["set-cookie"]);

    await db.query("UPDATE admin_sessions SET expires_at = $1 WHERE token_hash = $2", [
      new Date(Date.now() - 1000),
      hashSessionToken(sessionToken(expiredCookie)),
    ]);

    const response = await app.inject({
      method: "GET",
      url: "/v1/admin/auth/me",
      headers: { cookie: expiredCookie },
    });
    assert.equal(response.statusCode, 401);
    assert.equal((response.json() as { error: { code: string } }).error.code, "ADMIN_SESSION_EXPIRED");
  });

  it("rate limits repeated break-glass reset attempts", async () => {
    let last;
    for (let attempt = 0; attempt < 4; attempt += 1) {
      last = await app.inject({
        method: "POST",
        url: "/v1/admin/auth/reset/request",
        headers: { "x-setup-token": "wrong" },
        payload: { email: "rootadmin@example.com" },
      });
    }
    assert.equal(last?.statusCode, 429);
    assert.equal((last!.json() as { error: { code: string } }).error.code, "RATE_LIMITED");
  });
});
