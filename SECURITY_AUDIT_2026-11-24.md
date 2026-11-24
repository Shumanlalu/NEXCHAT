# NEXCHAT Comprehensive Security Audit and Remediation Report

Date: 2026-11-24
Repository: `Shumanlalu/NEXCHAT`
Scope: Android client, Super Admin panel, backend HTTP/WebSocket APIs, database schema/migrations, E2EE/messaging flow, infrastructure, dependencies and secrets.

## 1. Executive summary

The audit covered 51 confirmed findings and 15 potential findings across four domains. The most critical issues were the tracked Android release signing material, arbitrary local message mutation by client-supplied ID, stale authenticated mobile state, public TURN disclosure, device-revocation bypass, refresh-token race, and server-side controls that existed only as advisory UI flags.

Targeted fixes were implemented without replacing working components. All database changes are additive `ALTER TABLE ... IF NOT EXISTS` statements and all admin controls are enforced server-side. Existing API responses, mobile compatibility, database records and unrelated in-progress Android version/DB work were preserved.

**Implemented in this pass**

- Ban/suspend state is now a real account policy enforced by auth, APIs, presence, WebSockets, discovery and long-poll revalidation.
- Admin race conditions for first-run setup and last-super-admin changes are serialized with database row locks.
- Device revocation is durable; revoked UUIDs cannot be revived, refresh tokens are consumed atomically, and live sockets are closed on revocation.
- Public `/v1/config` no longer publishes TURN credentials; remote maintenance/version/call flags are enforced server-side.
- CSV exports are audited and neutralized against spreadsheet formula injection.
- Admin CSRF, malformed-cookie handling, lockout reset and reset-token invalidation were hardened.
- Message priority is bounded server-side, recipient discovery excludes blocked account states, crash/telemetry diagnostics are redacted, and cron bearer comparison is constant-time.
- Android client hardening covers centralized session invalidation, WS revocation codes, EDIT/DELETE ownership, group metadata binding, video-call flag enforcement and release log redaction.

**Residual risk requiring operator action**

- Rotate/remove the tracked Android release signing key and passwords from the repository and history; re-sign and publish a new release with a new keystore.
- Rotate any TURN credentials that were ever deployed through `ICE_SERVERS_JSON`, then supply them through an authenticated short-lived credential service rather than static public config.
- Apply Supabase RLS/grant hardening manually, set `TRUST_PROXY` explicitly, pin dependencies, add CI/dependency automation, and remove local secret/signing artifacts from working directories.
- Several design-level E2EE issues remain: ephemeral ciphertext persistence, metadata not cryptographically bound, unverified identity replacement, attachment quota enforcement, screenshot protection, certificate pinning, and Keystore binding to authenticated user identity.

## 2. Severity model

| Severity | Meaning |
|---|---|
| CRITICAL | Direct compromise of signing identity, account takeover, or unauthorized access to production secrets/data. |
| HIGH | Authentication/authorization bypass, durable session hijack, or large-scale unauthorized mutation. |
| MEDIUM | Meaningful information disclosure, policy bypass, race condition, or audit weakness. |
| LOW | Defense-in-depth gap, enumeration aid, advisory-only control, or operational hygiene issue. |
| INFORMATIONAL | No direct exploit path observed; risk depends on deployment or future changes. |

## 3. Confirmed findings

Columns: **ID**, **Severity**, **Component(s)**, **Root cause**, **Exploitation scenario**, **Impact**, **Count**, **Fix/status**, **Complexity**, **Verification**.

### 3.1 Admin panel and admin crypto

| ID | Sev | Component(s) | Root cause | Exploitation | Impact | Count | Fix/status | Complexity | Verification |
|---|---|---|---|---|---|---|---|---|---|
| C1 | MEDIUM | `routes/messages.ts`, Android outbox | Ephemeral ciphertext is persisted in the relay/outbox so it can survive handoff. | A recipient who later gains database or device access can recover sealed short-lived payloads. | Loss of true forward secrecy for ephemeral messages. | 2 | **Residual** – requires an in-memory relay design or expiry job; not changed to avoid delivery regressions. | High | Confirm by inspecting envelope rows for `ephemeral = TRUE` after delivery. |
| C2 | MEDIUM | `routes/admin-auth.ts`, `admin_password_resets` | Successful password reset did not invalidate other unused reset tokens. | Request two reset links, use one, then use the second. | Repeated unauthorized password changes. | 2 | **Fixed** – `invalidatePendingResets()` after reset and password change. | Low | `admin.test.ts` asserts a second outstanding token is marked used after the first reset succeeds. |
| C3 | MEDIUM | `admin/security.ts`, `admin_audit_logs` | Audit write failures were swallowed; no tamper-evident hash chain. | An attacker with DB write access can delete/alter rows without an integrity signal. | Audit trail cannot be trusted as evidence. | 2 | **Partial** – write failures are logged; hash chaining deferred because it needs a migration and transaction ordering design. | High | Force an audit insert failure and confirm `audit log write failed` log; no hash-chain assertion yet. |
| C4 | MEDIUM | `routes/admin-core.ts`, export routes | Bulk CSV exports were not audited. | An admin with export permission silently exfiltrates user/call/crash/audit data. | No attribution for bulk data export. | 4 | **Fixed** – all four exports write `report.exported` with range and row count. | Low | `security-hardening.test.ts` export audit assertion. |
| C5 | MEDIUM | `routes/admin-core.ts`, `csvEscape()` | Cells beginning with `= + - @ tab CR LF` are interpreted as formulas by spreadsheets. | A username/display name containing `=IMPORTXML(...)` executes when an admin opens the CSV. | Admin workstation data exfiltration / formula execution. | 1 | **Fixed** – leading apostrophe neutralization. | Low | `security-hardening.test.ts` asserts `'=IMPORTXML` in export body. |
| C6 | MEDIUM | `routes/admin-config.ts` | Last-super-admin count check was a check-then-write race. | Two concurrent demotions/deletions remove all active super admins. | Administrative lockout. | 2 | **Fixed** – `withSuperAdminGuard()` serializes via an `app_settings` row lock. | Medium | `admin.test.ts` asserts last-super-admin demotion returns `LAST_SUPER_ADMIN`; guarded transaction inspected. |
| C7 | MEDIUM | `routes/admin-auth.ts`, lockout | Expired lockout counter was not reset before incrementing. | After a 15-minute lock, one wrong password re-locks the account indefinitely. | Persistent denial of service for an admin account. | 1 | **Fixed** – expired counter resets to zero. | Low | Manual sequence: lock, wait/expire row, one bad login, then correct login succeeds. |
| C8 | MEDIUM | `routes/messages.ts`, skip reasons | Distinct skip reasons disclose recipient/device/group existence. | Send envelopes to guessed NEX IDs and compare `RECIPIENT_NOT_FOUND`, `DEVICE_NOT_ACTIVE`, etc. | User/device/group enumeration. | 1 | **Residual** – client relies on these codes for outbox decisions; generic reason requires coordinated mobile change and rate-limit tuning. | Medium | Compare responses for known/unknown NEX IDs. |
| C9 | MEDIUM | `routes/admin-auth.ts`, first setup | Count check then insert allowed concurrent first-run setup. | Two simultaneous `/v1/admin/setup` requests create two super admins. | Duplicate root accounts / setup race. | 1 | **Fixed** – atomic `admin_setup_claim` insert inside a transaction with deterministic first-admin ID as a collision backstop. | Medium | `admin-race.test.ts` sends two concurrent setups and asserts one `201` plus one `409`; `admin.test.ts` asserts later setup is closed. |
| C10 | MEDIUM | `routes/admin-auth.ts`, `DELETE /v1/admin/auth/session` | Session-clearing mutation had no auth/CSRF guard. | Cross-site or unauthenticated request clears an admin cookie. | Logout CSRF / session confusion. | 1 | **Fixed** – `requireAdmin()` + `verifyCsrf()`. | Low | `security-hardening.test.ts` asserts 403 without CSRF and 200 with CSRF. |
| C11 | LOW | `admin/security.ts`, `parseCookies()` | `decodeURIComponent()` on attacker-controlled cookie could throw `URIError`. | Send `Cookie: nexchat_admin_session=%ZZ`. | 500 on auth endpoint; noisy error path. | 1 | **Fixed** – malformed cookies ignored. | Low | `security-hardening.test.ts` asserts 401, not 500. |
| C12 | LOW | Admin audit flow | Phantom audit entries could be produced by unauthenticated or failed flows. | Trigger rejected setup/login paths and inspect audit list. | Noisy/incorrect audit trail. | 1 | **Residual** – requires audit-event taxonomy review; no auth bypass remains. | Medium | Compare audit rows against successful state transitions. |

### 3.2 Admin/crypto potential findings

| ID | Sev | Component(s) | Root cause | Exploitation | Impact | Count | Fix/status | Complexity | Verification |
|---|---|---|---|---|---|---|---|---|---|
| P1 | MEDIUM | Envelope schema, crypto | Envelope metadata is not cryptographically bound to ciphertext. | Relay or DB writer swaps sender/group/clientId fields while leaving ciphertext intact. | Metadata spoofing despite E2EE. | 1 | **Residual** – needs an AAD design and Android release. | High | Review envelope insert and decryption AAD inputs. |
| P2 | MEDIUM | `routes/messages.ts`, priority flag | Client could set `priority` on any envelope. | Send long-lived ordinary messages marked priority to starve call signaling. | Queue unfairness / signaling starvation. | 2 | **Fixed** – priority requires short TTL and a 30-per-minute per-device budget. | Medium | `wait.test.ts` asserts short-lived priority wins and long-lived priority does not. |
| P3 | LOW | `routes/messages.ts`, `/v1/config` | Server accepted a larger ciphertext than advertised by `maxEnvelopeBytes`. | Advertised limit and enforcement diverge. | Client/server mismatch, storage pressure. | 2 | **Fixed** – configured cap enforced per envelope. | Low | Unit review of `ENVELOPE_TOO_LARGE` skip path. |
| P4 | MEDIUM | Group membership flow | Group membership changes rely on client metadata without consent enforcement. | A member adds another NEX ID without consent. | Unwanted group inclusion / metadata spoofing. | 1 | **Partial** – Android now binds metadata to relay envelope and checks sender membership; server still cannot verify consent. | High | Android metadata tests/build; group add flow review. |
| P5 | MEDIUM | Analytics, Android logs | Crash context and release logs contained identifiers/secrets. | Read logcat or uploaded crash report to harvest NEX IDs/tokens. | Privacy leak, reconnaissance. | 3 | **Fixed/partial** – server redacts crash/event text; Android release log gating and redaction implemented. | Medium | `security-hardening.test.ts` crash redaction assertion; Android JVM redactor tests. |
| P6 | LOW | Admin UI sinks | Raw HTML sinks are currently fed escaped values but are not structurally guarded. | A future change passes unescaped data to `innerHTML`. | Stored XSS in admin panel. | 1 | **Residual** – add a template sanitizer or DOM-based rendering in a later refactor. | Medium | Grep `innerHTML` sinks and confirm current `h()` usage. |

### 3.3 Backend API and transport

| ID | Sev | Component(s) | Root cause | Exploitation | Impact | Count | Fix/status | Complexity | Verification |
|---|---|---|---|---|---|---|---|---|---|
| C-01 | HIGH | `routes/messages.ts`, `/v1/config`, `ICE_SERVERS_JSON` | Static TURN username/credential published by a public endpoint. | Anonymous GET `/v1/config` harvests long-lived TURN credentials. | Relay abuse, bandwidth theft, attribution to owner. | 2 | **Fixed** – public config returns only `urls`; credentials remain server-side. Rotate deployed TURN credentials. | Low | `security-hardening.test.ts` asserts no `username`/`credential`. |
| C-02 | MEDIUM | `routes/admin-core.ts`, CSV | Spreadsheet formula injection in exports. | See C5. | Admin workstation compromise. | 1 | **Fixed** with C5. | Low | Export assertion. |
| C-03 | MEDIUM | `routes/analytics.ts`, call sessions | Any authenticated user could report a one-sided call with an arbitrary peer. | Fabricate call records for analytics/admin dashboards. | Polluted telemetry, false audit/call history. | 1 | **Partial** – peer must be active/non-banned, mode flags and duration limit enforced; one-sided reports still possible because signaling is encrypted. | High | Test call flag enforcement; residual noted. |
| C-04 | HIGH | `routes/auth.ts`, `insertDevice()` | Revoked device UUID was revived on login via `revoked_at = NULL`. | Reuse an old device ID after remote revoke to regain a session. | Device revocation bypass. | 1 | **Fixed** – revoked UUIDs rejected; replacement no longer clears `revoked_at`. | Medium | `security-hardening.test.ts` revoke + re-register rejection. |
| C-05 | MEDIUM | Admin export routes | Bulk exports were unaudited. | See C4. | No export attribution. | 4 | **Fixed** with C4. | Low | Export audit assertion. |
| C-06 | MEDIUM | `create-app.ts`, presence route | Presence used manual token verification and ignored suspension/ban. | Suspended user still queries presence of other accounts. | Policy bypass / account-state disclosure. | 1 | **Fixed** – presence uses `requireAuth()` and excludes blocked target accounts. | Low | `admin.test.ts` suspension blocks authenticated API; code review of presence query. |
| C-07 | MEDIUM | Admin lockout | See C7. | See C7. | Admin DoS. | 1 | **Fixed** with C7. | Low | Lockout review. |
| C-08 | HIGH | `routes/auth.ts`, refresh flow | Refresh token was read then revoked in a separate statement. | Two concurrent refreshes both succeed and mint two access/refresh pairs. | Refresh-token reuse / durable session hijack. | 1 | **Fixed** – conditional atomic `UPDATE ... RETURNING`. | Medium | `security-hardening.test.ts` concurrent refresh returns 200+401. |
| C-09 | LOW | `routes/auth.ts`, `/v1/auth/check-username` | Endpoint disclosed registration state. | Enumerate registered usernames. | Account enumeration. | 1 | **Fixed** – validates shape only, returns `available: true`, rate-limited. | Low | Code review; registration remains authoritative. |
| C-10 | LOW | `routes/analytics.ts`, cron route | Cron bearer compared with `!==`. | Timing side channel could infer secret prefix. | Low-probability secret disclosure. | 1 | **Fixed** – `secureEqual()` constant-time comparison. | Low | Code review; cron auth still rejects wrong secret. |

### 3.4 Backend potential findings

| ID | Sev | Component(s) | Root cause | Exploitation | Impact | Count | Fix/status | Complexity | Verification |
|---|---|---|---|---|---|---|---|---|---|
| P-01 | MEDIUM | `create-app.ts`, rate-limit/proxy config | Rate limits are per instance; `TRUST_PROXY` defaults false. | Distributed clients bypass per-instance limits. | Brute-force/DoS tolerance. | 2 | **Residual** – set `TRUST_PROXY=true` behind a trusted proxy and use shared rate limiting. | Medium | Config review; load-test in staging. |
| P-02 | MEDIUM | Remote config, Android flags | Flags were advisory; server accepted traffic regardless. | Disable calls/maintenance in admin but clients/API continue. | Admin control bypass. | 3 | **Fixed/partial** – maintenance/min-version enforced for mobile APIs, call telemetry enforces call flags; encrypted audio/video signal content cannot be classified server-side. | Medium | `security-hardening.test.ts` policy and call-flag tests. |
| P-03 | MEDIUM | Mobile auth flow | No mobile account lockout after repeated failed logins. | Password guessing against user accounts. | Account takeover risk. | 1 | **Residual** – needs per-account mobile lockout with privacy-safe responses. | Medium | Attempt repeated logins in staging. |
| P-04 | MEDIUM | User search/profile/message recipients | Suspended users remained discoverable/contactable. | Harass suspended accounts or probe their device IDs. | Policy bypass / privacy. | 4 | **Fixed** – search, profile, recipients, analytics peers and presence exclude suspended/banned users. | Medium | `security-hardening.test.ts` ban discovery assertion. |
| P-05 | MEDIUM | Shared-secret routes | Optional shared secrets (`CRON_SECRET`, setup token) can be weak if operators choose short values. | Guess a weak shared secret. | Internal endpoint abuse. | 2 | **Residual** – enforce minimum entropy in config validation. | Low | Config review. |
| P-06 | MEDIUM | `admin/security.ts`, CSRF | CSRF origin check trusted `X-Forwarded-Host`. | Spoof header to satisfy origin comparison when proxy is untrusted. | CSRF bypass. | 1 | **Fixed** – uses validated `Host` header only. | Low | `admin.test.ts` CSRF rejection; header review. |
| P-07 | MEDIUM | `/v1/ws`, `WsHub` | WebSocket authorization occurred only at connect. | Keep an authorized socket open after suspension/device revocation. | Continued real-time access. | 2 | **Fixed** – heartbeat rechecks state; admin actions close sockets; device revoke closes socket; 4403 emitted. | Medium | Code review; WS close test not automated against a live socket. |
| P-08 | LOW | Presence hub | Ephemeral presence is process-local. | Multi-instance deployments report inconsistent online state. | Incorrect presence. | 1 | **Residual** – needs shared pub/sub. | High | Deploy two instances and compare presence. |
| P-09 | LOW | Local `.env`, deployment | Secret file exists in local working directory. | Local machine compromise exposes config. | Secret disclosure. | 1 | **Residual** – remove from working tree and use platform env only. | Low | Filesystem review; never print file contents. |

### 3.5 Android client

| ID | Sev | Component(s) | Root cause | Exploitation | Impact | Count | Fix/status | Complexity | Verification |
|---|---|---|---|---|---|---|---|---|---|
| A-01 | CRITICAL | Android release signing keystore/passwords | Release signing material is tracked in the repository/history. | Anyone with repo access signs a malicious APK with the production identity. | Supply-chain compromise, user compromise. | 1 | **Residual – operator action required.** Rotate keystore, purge history, re-release. | High | Search repository for keystore/password files; confirm new release signed with rotated key. |
| A-02 | HIGH | `ChatRepository.handleContent()`, `MessageDao` | EDIT/DELETE trusted client-supplied `targetClientId`. | Any peer sends a control envelope to edit/delete another user's local message. | Arbitrary local message mutation. | 2 | **Fixed** – ownership checks against envelope sender/conversation/group membership; DAO WHERE clauses tightened. | Medium | Android unit/build verification; manual two-device send test recommended. |
| A-03 | HIGH | `ApiClient`, `AuthRepository`, `WebSocketManager` | Every 401 threw `SessionExpiredException`, but only WS called the full wipe; refresh failure cleared tokens only. | Ban/suspend/revoke leaves stale local profile/data and UI state. | Continued apparent authenticated state; data exposure on shared device. | 3 | **Fixed** – central idempotent invalidation path; HTTP/refresh/WS revocation codes route to one wipe. | Medium | Android JVM tests for classification and dispatcher; build verification. |
| A-04 | MEDIUM | `ChatRepository.handleGroupMeta()` | Group metadata accepted `content.groupId` and wall-clock updates without ordering or membership checks. | Stale/spoofed GROUP_META changes group title/members. | Group hijack / confusion. | 1 | **Fixed** – metadata bound to relay envelope group ID and timestamp/id watermark; sender membership required. | Medium | Android build/tests; manual group rename/member test recommended. |
| A-05 | MEDIUM | `RemoteConfigStore`, `CallManager`, `ChatScreen` | `videoCallsEnabled` was parsed but never enforced. | Admin disables video; installed client still starts/accepts video calls. | Admin policy bypass. | 3 | **Fixed** – accessor + outgoing/incoming/camera/UI enforcement. | Medium | Android build/tests; admin flag flip test recommended. |
| A-06 | MEDIUM | `CallManager.log()`, `Telemetry`, `RemoteConfigStore` | Release logs and crash payloads contained raw identifiers/error text. | Read logcat or uploaded crash reports to collect NEX IDs, UUIDs, tokens. | Privacy leak / reconnaissance. | 3 | **Fixed/partial** – release gating, redactor, crash sanitization; default uncaught handler still logs raw throwable. | Medium | Android redactor tests; backend crash assertion. |
| A-07 | MEDIUM | `ChatRepository.upsertSender()` | Peer-controlled identity fields replace local contact identity before verification. | Malicious peer impersonates a trusted display name/avatar. | Spoofing/social engineering. | 1 | **Residual** – needs verified identity binding. | High | Review sender upsert path. |
| A-08 | MEDIUM | Network stack | No certificate pinning. | MITM with installed CA can intercept TLS if device is compromised. | Traffic interception. | 1 | **Residual** – pinning requires release planning and backup pin. | High | Network security config review. |
| A-09 | MEDIUM | Keystore usage | Keys are not bound to authenticated user identity. | Another local user/profile can access app keys. | Local key misuse. | 1 | **Residual** – bind aliases to user ID and enforce auth. | High | Keystore alias audit. |
| A-10 | MEDIUM | Identity key flow | Identity is replaced before verification completes. | MITM or malicious peer forces identity reset. | Identity confusion. | 1 | **Residual** – require explicit verification before replacement. | High | Identity rotation flow review. |
| A-11 | LOW | Attachment handling | No client-side quota matching `maxAttachmentBytes`. | Large attachments consume storage/bandwidth. | Resource exhaustion. | 1 | **Partial** – server config advertises limit; client enforcement still needed. | Medium | Compare attachment path with `/v1/config` limits. |
| A-12 | LOW | Screenshot protection | `FLAG_SECURE` off by default. | Screenshots/recents reveal message content. | Local privacy leak. | 1 | **Residual** – opt-in setting or default-secure for chat screens. | Low | UI review. |
| A-13 | LOW | Registration ID range | Small registration-ID range and legacy repair logic. | Collisions can misroute protocol addresses. | Message/call delivery failures. | 1 | **Partial** – backend normalizes legacy IDs; Android long-term allocation review remains. | Medium | `db.ts` repair test; Android registration ID test. |
| A-14 | LOW | Call UI names | Peer-controlled display names are used in call UI. | Impersonation during ringing/call screens. | Confusion/spoofing. | 1 | **Residual** – use verified contact labels. | Medium | Call screen code review. |
| A-15 | LOW | WebSocket close handling | Close code 4003 was treated as reconnectable. | Admin ban/suspension triggers reconnect loop with stale state. | Persistent ghost session. | 1 | **Fixed** with A03. | Low | Android close-code tests. |
| A-16 | LOW | `build.gradle.kts` release config | Release logs not stripped by minify. | Sensitive logcat output in production. | Privacy leak. | 1 | **Partial** – application log calls gated/redacted; default handler remains; no build-file edit made because of unrelated in-progress version bump. | Low | Release build logcat review. |
| A-17 | LOW | Android manifest | Exported components/receivers/provider paths need periodic review. | Potential intent/path traversal exposure. | Local component abuse. | 1 | **Residual** – no new exploit path confirmed in this pass. | Medium | Manifest review. |

### 3.6 Infrastructure, dependencies and secrets

| ID | Sev | Component(s) | Root cause | Exploitation | Impact | Count | Fix/status | Complexity | Verification |
|---|---|---|---|---|---|---|---|---|---|
| NX-01 | HIGH | Handover artifact / plaintext password | Admin password was present in plaintext in an operator handover artifact. | Anyone with the artifact signs in as admin. | Full admin compromise. | 1 | **Residual – rotate password immediately.** Do not store passwords in docs/chat. | Low | Confirm password rotated; no value reproduced in this report. |
| NX-02 | MEDIUM | `backend/package.json` | Production dependencies are outdated. | Known CVEs in transitive packages. | Server compromise risk. | 10+ | **Residual** – run `npm audit`, upgrade pinned deps in a separate reviewed change. | Medium | `npm audit --omit=dev`. |
| NX-03 | HIGH | Supabase/Postgres grants and RLS | RLS/grant omissions allow broader direct table access than the API intends. | Direct PostgREST/SQL access bypasses app policy. | Data disclosure/modification. | 1 | **Residual** – apply least-privilege roles/RLS in production DB. | Medium | DB role/RLS inspection by operator. |
| NX-04 | LOW | Vercel build install | Dev toolchain advisories are installed during build. | Supply-chain exposure in CI. | Build compromise risk. | 1 | **Residual** – separate build deps and patch lockfile. | Medium | Dependency audit output. |
| NX-05 | HIGH | `/v1/config`, ICE JSON | Public TURN disclosure. | See C-01. | Relay abuse. | 1 | **Fixed** – credentials filtered; rotate deployed credentials. | Low | Live `/v1/config` check after deploy. |
| NX-06 | MEDIUM | `create-app.ts`, proxy config | `TRUST_PROXY` defaults false behind Vercel. | Rate limits use the proxy IP instead of client IP. | Rate-limit bypass. | 1 | **Residual** – set explicitly in Vercel env; verify trusted proxy behavior. | Low | Env review. |
| NX-07 | LOW | Untracked backup directories | `CALL_FIX_BACKUP_20261123/` and `FULL_PROJECT_BACKUP/` are untracked and may contain stale config/secrets. | Accidental commit or local exposure. | Secret leak / confusion. | 2 | **Residual** – keep uncommitted; move outside repo or add ignore after explicit confirmation. | Low | `git status` confirms not staged. |
| NX-08 | MEDIUM | Local keystore/.env artifacts | Secret/signing files exist locally. | Local machine compromise. | Secret disclosure. | 1 | **Residual** – remove from working tree, rotate exposed values. | Low | Filesystem review; values not printed. |
| NX-09 | MEDIUM | Supabase policy SQL | Policies were not idempotent. | Re-running migration fails or duplicates grants. | Deployment fragility. | 1 | **Residual** – use `CREATE POLICY IF NOT EXISTS`/`DROP POLICY IF EXISTS` with operator review. | Medium | Re-run policy script in staging. |
| NX-10 | LOW | `routes/analytics.ts`, cron auth | Non-constant-time bearer compare. | See C-10. | Timing side channel. | 1 | **Fixed** with C-10. | Low | Code review. |
| NX-11 | LOW | Deployment config | Root directory/env drift between local and Vercel. | Fixes deployed with wrong env or missing config. | Broken security controls. | 1 | **Residual** – keep Vercel root `backend`; verify env after deploy. | Low | Live endpoint verification after deploy. |
| NX-12 | LOW | CI/CD | No automated dependency or security scanning. | Vulnerabilities remain undetected. | Delayed remediation. | 1 | **Residual** – add Dependabot/npm audit/SARIF checks. | Medium | GitHub Actions review. |

## 4. Potential findings not confirmed exploitable

The following were observed but not confirmed with a safe non-destructive test: envelope AAD binding (P1), group consent semantics (P4), raw HTML sink hygiene (P6), mobile lockout (P-03), shared-secret entropy (P-05), multi-instance presence (P-08), screenshot/attachment/certificate-pinning design gaps (A-08 through A-14), Supabase policy idempotency (NX-09), and deployment drift (NX-11). They remain in the roadmap and are listed above with their current status.

## 5. Implemented changes by file

### Backend

| File | Change |
|---|---|
| `backend/src/schema.sql` | Additive `banned_at` / `banned_reason` columns on `users`; existing `suspended_at` behavior preserved. |
| `backend/src/http.ts` | `UserRow` ban fields; `requireAuth()` rejects banned accounts; new `assertActiveSession()` for long-lived requests/sockets. |
| `backend/src/create-app.ts` | Presence uses `requireAuth()`; WS handshake/heartbeat rechecks ban/suspend/revocation; server-side maintenance and minimum-version policy; socket hub passed to user/key routes. |
| `backend/src/routes/admin-auth.ts` | Lockout counter reset; pending reset invalidation; CSRF/auth on session-clearing DELETE; atomic first-admin claim; malformed-cookie-safe parsing. |
| `backend/src/routes/admin-config.ts` | Atomic guard for first/super-admin changes; guarded admin update/delete transactions. |
| `backend/src/routes/admin-core.ts` | Ban/unban actions and filters; CSV formula neutralization; export auditing; device-count query rewritten for portability; user status mapping. |
| `backend/src/routes/auth.ts` | Ban checks on login/refresh; durable device revocation; atomic refresh consumption; username check no longer leaks availability. |
| `backend/src/routes/messages.ts` | Short-TTL bounded priority with per-device budget; configured envelope cap; blocked recipients excluded; public ICE credential filtering; long-poll revalidation. |
| `backend/src/routes/users.ts` | Search/profile exclude banned/suspended users; device deletion closes live socket. |
| `backend/src/routes/analytics.ts` | Call feature flags and duration limit enforced; active peer required; crash/event redaction; constant-time cron comparison. |
| `backend/src/admin/security.ts` | Malformed cookie tolerance; `Host`-only CSRF origin check; audit failures logged. |
| `backend/src/admin/ui.html` | Banned status filter/pill; ban/unban actions with reason input. |
| `backend/test/*` | Added `security-hardening.test.ts` and `admin-race.test.ts`; extended `admin.test.ts` and `wait.test.ts`. |

### Android

| File | Change |
|---|---|
| `android/app/src/main/java/com/nexchat/app/core/network/SessionRevocation.kt` | Pure allow-list of session-revocation codes, excluding policy denials and failed logins. |
| `android/app/src/main/java/com/nexchat/app/core/network/ApiClient.kt` | Single central dispatcher; first-error classification before refresh; refresh-failure invalidation. |
| `android/app/src/main/java/com/nexchat/app/data/AuthRepository.kt` | Atomic, idempotent `invalidateSession()`; `sessionExpired()` wrapper; guard reset after successful auth. |
| `android/app/src/main/java/com/nexchat/app/net/WsClosePolicy.kt` | Pure close-code classification for `4001`/`4003`/`4403` invalidate and `4401` refresh-once. |
| `android/app/src/main/java/com/nexchat/app/net/WebSocketManager.kt` | Routes close codes through the central dispatcher; removes duplicate local invalidation. |
| `android/app/src/main/java/com/nexchat/app/core/db/Daos.kt` | Tightens EDIT/DELETE SQL with conversation, sender and local-outgoing conditions. |
| `android/app/src/main/java/com/nexchat/app/data/ChatRepository.kt` | EDIT/DELETE ownership checks, group binding, metadata watermark/member check, outgoing ownership validation. |
| `android/app/src/main/java/com/nexchat/app/core/settings/GroupMetadataWatermarkStore.kt` | SharedPreferences-based GROUP_META ordering watermark; no Room schema change. |
| `android/app/src/main/java/com/nexchat/app/core/config/RemoteConfigStore.kt` | `videoCallsEnabled()` accessor, fail-closed fresh default, stable cached default. |
| `android/app/src/main/java/com/nexchat/app/call/CallManager.kt` | Enforces video flag for outgoing/incoming/camera; debug-gated redacted logging. |
| `android/app/src/main/java/com/nexchat/app/ui/screens/ChatScreen.kt` | Video button and guards respect the admin flag with a user-facing message. |
| `android/app/src/main/java/com/nexchat/app/core/security/LogRedactor.kt` | Pure redaction of NEX IDs, UUIDs, phones, tokens, ciphertext-like blobs and URL queries. |
| `android/app/src/main/java/com/nexchat/app/core/telemetry/Telemetry.kt` | Debug-gated warnings; sanitizes crash message, stack, context and event metadata at enqueue/persist/upload. |
| `android/app/src/main/java/com/nexchat/app/NexChatApp.kt` | Wires the dispatcher and watermark store; resets revocation guard on login. |
| `android/app/src/test/java/com/nexchat/app/core/network/SessionRevocationTest.kt` | JVM tests for revocation-code classification. |
| `android/app/src/test/java/com/nexchat/app/core/security/LogRedactorTest.kt` | JVM tests for redaction patterns. |
| `android/app/src/test/java/com/nexchat/app/net/WsClosePolicyTest.kt` | JVM tests for WebSocket close-code actions. |

No changes were made by this audit pass to `android/app/build.gradle.kts`, `android/app/src/main/java/com/nexchat/app/core/db/NexDatabase.kt`, generated Room schema JSON, resources, or backup directories beyond their pre-existing working-tree state.

## 6. Prioritized remediation roadmap

### P0 – immediately (operator action)

1. Rotate the Android release signing keystore and all associated passwords; purge tracked signing material from history and publish a new signed APK.
2. Rotate the admin password from the handover artifact and any TURN credentials that were ever published/configured.
3. Set Vercel `TRUST_PROXY=true` only if the platform proxy is trusted; otherwise keep false and use shared rate limiting.
4. Apply Supabase least-privilege roles/RLS and idempotent policy scripts.

### P1 – this release (implemented where noted)

1. Server-side account/device policy: ban/suspend, durable revocation, atomic refresh, WS/socket revocation, presence policy. **Implemented.**
2. Admin race guards, CSRF/cookie hardening, audited and formula-safe exports. **Implemented.**
3. Public config credential filtering and server-side remote policy. **Implemented.**
4. Android session invalidation, EDIT/DELETE ownership, group metadata binding, video flag enforcement, log redaction. **Implemented pending build verification.**
5. Add mobile lockout and verified identity binding before re-enabling high-risk flows.

### P2 – next release

1. Hash-chained audit log with integrity verification endpoint.
2. AAD-binding for envelope metadata and content IDs.
3. In-memory/expiry-bound ephemeral relay transport.
4. Attachment quota enforcement on device and server.
5. Certificate pinning with backup pin and user-auth-bound Keystore aliases.

### P3 – ongoing hygiene

1. Dependency upgrades and CI security scanning.
2. Shared pub/sub presence and shared rate limits.
3. Generic skip reasons with coordinated mobile semantics.
4. Screenshot protection setting and verified contact labels.
5. Remove local secret/signing artifacts and backup directories from the repository workspace after explicit confirmation.

## 7. Issue checklist

- [x] C2 stale reset tokens invalidated
- [x] C4/C-05 CSV exports audited
- [x] C5/C-02 spreadsheet formula injection neutralized
- [x] C6 last-super-admin race serialized
- [x] C7/C-07 lockout counter reset
- [x] C9 first-run setup race closed atomically
- [x] C10 unauthenticated session-clearing DELETE guarded
- [x] C11 malformed cookie handled
- [x] P2 client-controlled priority bounded server-side
- [x] P3 configured envelope cap enforced
- [x] P-06 `X-Forwarded-Host` CSRF weakness removed
- [x] P-07 WS authorization revalidated
- [x] C-01/NX-05 TURN credentials removed from public config
- [x] C-03 call telemetry partially hardened (peer/flags/duration)
- [x] C-04 revoked device resurrection blocked
- [x] C-06 presence bypass fixed
- [x] C-08 refresh-token race fixed
- [x] C-09 username enumeration response fixed
- [x] C-10/NX-10 cron compare made constant-time
- [x] P-02 server-side maintenance/version/call enforcement
- [x] P-04 banned/suspended accounts hidden from discovery and messaging
- [x] P5 crash/telemetry redaction (server)
- [x] A-02 EDIT/DELETE ownership enforced
- [x] A-03 stale authenticated state centrally invalidated
- [x] A-04 group metadata bound to relay envelope
- [x] A-05 video-call flag enforced
- [x] A-06/A-15 release log and WS close handling hardened
- [ ] C1 ephemeral ciphertext persistence
- [ ] C3 audit hash chain
- [ ] C8 enumeration via skip reasons
- [ ] C12 phantom audit entries
- [ ] P1 envelope AAD binding
- [ ] P4 group consent semantics
- [ ] P6 raw HTML sink guard
- [ ] P-01 shared/proxy rate limits
- [ ] P-03 mobile lockout
- [ ] P-05 shared-secret entropy
- [ ] P-08 multi-instance presence
- [ ] P-09 local env secret file
- [ ] A-01 release signing key rotation (**operator action**)
- [ ] A-07/A-08/A-09/A-10 identity/TLS/Keystore hardening
- [ ] A-11/A-12/A-13/A-14 attachment, screenshot, registration ID and call-name hardening
- [ ] NX-01/NX-02/NX-03/NX-04/NX-06/NX-07/NX-08/NX-09/NX-11/NX-12 operational hardening

## 8. Test evidence

Backend verification performed in this pass:

- `npm run typecheck` — passed
- `npm test` — 56/56 passed, 8 suites
- `npm run build` — passed
- Admin inline script `new Function(...)` syntax check — passed
- New suites: `backend/test/security-hardening.test.ts`, `backend/test/admin-race.test.ts`
- Extended suites: `backend/test/admin.test.ts`, `backend/test/wait.test.ts`

Android verification performed in this pass:

- `.\\gradlew.bat testDebugUnitTest assembleDebug --no-daemon` from `S:\\nexchat\\android` — `BUILD SUCCESSFUL`
- 15 JVM tests, 0 failures, 0 errors (12 new tests for revocation classification, WebSocket close codes and log redaction)
- No Room schema, resource, backup-directory or version-file changes were introduced by the audit fixes.

No destructive tests were run. No third-party systems were accessed. No real user data, passwords or tokens were used, printed or stored.

## 9. Deployment and live verification

Pushed commit `0084626664aea6d482077fe7ec91fd5942354371` to `main`. Verified after deployment:

- `Vercel – backend` → success
- `Vercel – nexchat` → success
- `GET /admin` → 200 with nonce CSP, `frame-ancestors 'none'`, no public signup controls
- `GET /v1/admin/bootstrap` → `setupRequired:false`, `setupEnabled:false`, `signupEnabled:false`
- `GET /v1/config` → 200 with STUN-only `iceServers`; no TURN `username` or `credential`; `turnConfigured:false`
- `GET /v1/admin/signup` → 404
- `GET /v1/admin/auth/me` unauthenticated → 401

No destructive live requests were made.

## 10. Remaining uncertainties

1. **Signing key history** – rotation cannot be performed safely from this workspace without an operator-managed new keystore and history rewrite.
2. **Supabase direct access** – RLS/grants must be verified by the database owner; no destructive SQL was executed.
3. **TURN credentials** – the current live config reports `turnConfigured: false`; if credentials were previously deployed, they must still be rotated.
4. **Encrypted signaling classification** – the relay cannot distinguish audio/video call payloads without breaking E2EE; server-side call-flag enforcement covers telemetry and priority, while true signaling enforcement requires a new protocol.
5. **Group consent** – server cannot prove that a member consented to addition; Android now rejects spoofed/stale metadata but cross-client semantics still need a protocol decision.
6. **Audit tamper evidence** – failures are logged but rows are not yet hash-chained.
7. **Skip-reason enumeration** – mobile outbox behavior currently depends on granular reasons, so generic reasons require a coordinated release.
8. **Android runtime behavior** – JVM tests cover pure helpers; two-device manual verification is recommended after installing the rebuilt APK.
9. **Vercel status** – final commit statuses must be checked after push; no CLI credentials were used here.

## 11. Implementation log

- Backend implementation completed first; `npm run typecheck`, `npm test` (56/56), `npm run build` and admin UI syntax check all passed.
- Android implementation completed in parallel; `testDebugUnitTest` and `assembleDebug` passed with 15 JVM tests and no failures.
- Only intended files were staged. Pre-existing `android/app/build.gradle.kts`, `NexDatabase.kt`, generated schema, resource and backup changes remained unstaged.
- Commit `0084626664aea6d482077fe7ec91fd5942354371` was pushed to `main`; final Vercel and live-endpoint verification is recorded in section 9.
