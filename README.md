# NEXCHAT

NEXCHAT is a self-hostable, end-to-end encrypted Android messenger. It uses the
Signal protocol (X3DH/PQXDH and the Double Ratchet) for every message. The
server relays encrypted envelopes and never receives message plaintext.

The Android client is built with Kotlin and Jetpack Compose, with a silver-glass
interface, online messaging, private groups, contact search, delivery and read
receipts, replies, reactions, editing, deletion, typing indicators, safety
numbers, and per-device sessions.

> **Build status:** the Android client and backend are source-complete for the
> features listed below, but have not been exercised on a physical Android
> device or emulator in this workspace. See [Limitations](#limitations).

## What works

| Area | Status |
| --- | --- |
| Online one-to-one messaging | Implemented with per-device Signal sessions |
| Private groups | Implemented as pairwise encrypted fan-out to every member device |
| Encrypted local storage | SQLCipher; key protected with Android Keystore AES-GCM |
| Message states | Pending, sent, delivered, read, failed; sent only after the relay accepts ciphertext, delivered only after the recipient device acknowledges it |
| Reliability | Durable SQLCipher-backed intent/outbox queues, automatic exponential-backoff retry, relay deduplication, durable delivery receipts, device-list refresh, duplicate-envelope detection, server long-poll for near-instant delivery plus a 5-second queued-message fallback, 15-minute WorkManager safety net |
| Offline composition | Messages can be written with no connection; encrypted content and the local bubble survive process death and send automatically when the relay is reachable |
| Identity verification | Per-device numeric safety numbers, contact-level verification marker, proactive identity-change detection, manual session reset from the contact screen |
| Attachments | Not implemented in this build; avatar uploads are re-encoded to strip EXIF/GPS metadata |
| Voice calls | One-to-one WebRTC audio with a dedicated low-latency call outbox and long-poll delivery; SDP and ICE travel inside Signal envelopes. Video is not implemented |
| Bluetooth LE nearby messaging | Not implemented in this build |
| Offline voice-note transcription | Not implemented; there is no audio recording or attachment pipeline |
| QR device linking | Not implemented; sign in separately on each device |
| Push notifications | Not implemented; a foreground WebSocket service keeps the connection alive |

Sending is offline-first. A composed message is written to the encrypted local
queue before any network call. If the relay is unreachable, the message stays
**pending** and is retried with exponential backoff while the process is alive.
WorkManager provides a 15-minute background safety net after a process kill. The
relay deduplicates `(sender device, recipient device, client message ID)`, so a
retry after a lost response cannot create a second message.

## Security model

- Message content is encrypted on the sender device with libsignal and decrypted
  on the recipient device. The server receives ciphertext, routing metadata, and
  group membership, but not plaintext.
- Each installation has its own Signal identity, registration ID, and device ID.
  Multi-device delivery is performed by encrypting a separate copy for each
  active device.
- Group messages use separate pairwise Signal ciphertexts for each member
  device. Group titles and membership updates are carried inside encrypted
  content. The server necessarily sees group IDs and member account IDs.
- The SQLCipher passphrase, access token, and refresh token are wrapped with an
  Android Keystore AES-GCM key. Private keys are never stored as plaintext.
- Signing out wipes the local database, Signal sessions, and device identity.
  Signing in as another account also wipes the previous account's local state.
- The release Android build requires HTTPS/WSS. Cleartext HTTP is enabled only in
  the debug build for local emulator development.
- Screenshot blocking is available as an opt-in setting.
- One-to-one voice calls use WebRTC with DTLS-SRTP for media. Call offers,
  answers, and ICE candidates are sent as ordinary encrypted Signal content, so
  the relay sees only ciphertext and the same envelope metadata it already sees
  for messages. Call history is stored in the encrypted local database.
- Calls are audio only and limited to direct chats. The server publishes STUN
  configuration; deployments that need to traverse restrictive or symmetric NATs
  must also configure a TURN service in `ICE_SERVERS_JSON`.

No custom cipher is used. Do not treat the server operator as trusted for
confidentiality: they can observe traffic metadata and can withhold or delay
messages. Safety numbers should be compared over a separate trusted channel.

## Repository layout

```
backend/    Fastify + TypeScript API, WebSocket relay, PostgreSQL schema
android/    Android application (Kotlin, Compose, Room, SQLCipher, libsignal)
Caddyfile   Example TLS reverse proxy
docker-compose.yml
```

## Run the server

### Docker (recommended)

1. Copy the environment template and set strong values:

   ```powershell
   Copy-Item backend/.env.example .env
   ```

   At minimum set `POSTGRES_PASSWORD`, `JWT_SECRET`, and the public CORS origin.
   Generate a JWT secret with:

   ```powershell
   node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
   ```

2. For a real deployment, edit `Caddyfile` to use your DNS name and place the
   stack behind HTTPS. For local testing, the server is exposed at
   `http://127.0.0.1:8080` (the Caddy service is optional).
3. Start the stack:

   ```powershell
   docker compose up -d --build
   ```

4. Check health:

   ```powershell
   Invoke-RestMethod http://127.0.0.1:8080/healthz
   ```

The server applies `backend/src/schema.sql` on startup and serves the API and
WebSocket endpoint on the same port.

### Local development

Requirements: Node.js 20+, PostgreSQL 16, and npm.

```powershell
Set-Location backend
Copy-Item .env.example .env
# Edit .env with your DATABASE_URL and JWT_SECRET
npm ci
npm run build
npm start
```

Use `npm run dev` for TypeScript watch mode and `npm run typecheck` for a static
check. `npm test` runs the prekey-distribution integration tests against an
in-memory PostgreSQL database (`pg-mem`); it does not touch production data.

### Managed deployment (Vercel + Neon)

A live backend is deployed at:

```
https://nexchat-three.vercel.app
```

It runs the Fastify server as a Vercel Node.js Function and stores data in a Neon
PostgreSQL database provisioned through the Vercel Marketplace. The Vercel
project is `shuman2/nexchat`; the database and all secrets are production-only
environment variables and are not in this repository. For a self-hosted
alternative that keeps a persistent process, use the Docker stack above.

Vercel Functions have a maximum WebSocket lifetime (300 seconds on the Hobby
plan). The Android client reconnects with exponential backoff and polls the
encrypted-envelope queue every 5 seconds while a chat is visible, so messages
still arrive if a socket expires or a push reaches a different function
instance. For guaranteed low-latency realtime fan-out at scale, deploy the
Docker server on a host that keeps one process running, or add a shared pub/sub
layer.

### Super Admin Panel

The operations panel is served by the same Fastify process at `/admin`; there is
no separate frontend host and no CDN dependency. It uses only first-party HTML,
CSS and JavaScript with a nonce-based Content Security Policy.

Features:

- Email ID and password sign-in, scrypt password hashing, HttpOnly
  `SameSite=Strict` session cookies, HMAC CSRF tokens, and lockout after repeated
  failures. No Google Authenticator, TOTP code, or QR scan is required. Existing
  username-only admin rows keep working so an already-deployed panel is not
  locked out by this change.
- Role-based access: `super_admin`, `admin`, `analyst`, and `support`. Every
  sensitive action is written to an append-only audit log.
- Real dashboard metrics from PostgreSQL: registrations, daily active users,
  message volume, call outcomes and connect time, crashes, platform/version
  breakdown, database latency and size. There are no sample or fabricated
  numbers.
- User administration with suspend, restore, and soft-delete. Suspension blocks
  authenticated HTTP, login, refresh, and WebSocket access immediately.
- Remote configuration with version history and rollback, plus in-app
  announcements. Android 1.0.4 and later honour these settings, cache the last
  response, and refresh every five minutes.
- CSV exports for users, calls, crashes, and audit entries; printable views.
- Monitoring of crash reports, failed-call reasons, service heartbeats, and
  database health.

Configure the panel on Vercel (or in `.env` for Docker/local):

| Variable | Purpose |
| --- | --- |
| `ADMIN_SETUP_TOKEN` | One-time secret for `POST /v1/admin/setup` and the break-glass `POST /v1/admin/signup`. Empty disables both. Setup closes permanently once an administrator exists. |
| `ADMIN_SESSION_HOURS` | Admin session lifetime, default `12`, maximum `72`. |
| `CRON_SECRET` | Bearer token for `POST /v1/internal/heartbeat` (uptime sample plus retention cleanup). Vercel Cron sends it automatically. |

Bootstrap on a fresh database:

```powershell
# From the repository root (the Vercel project root directory is backend/)
npx vercel env add ADMIN_SETUP_TOKEN production
npx vercel env add CRON_SECRET production
npx vercel --prod --yes
```

Open `https://nexchat-three.vercel.app/admin`, enter the setup token, choose an
email ID and password, then sign in with them. Nothing is shown to scan or enter
in an authenticator app. After the first administrator exists, setup returns
`409` even if the bootstrap token remains configured; you can remove the
variable and redeploy.

### Creating another admin / recovering a locked-out account

The first-run setup page closes permanently, so a sole admin who loses their
password can use the token-gated signup page instead:

1. Open `https://nexchat-three.vercel.app/admin/signup` (also linked as
   "Lost access?" from the sign-in page).
2. Enter the `ADMIN_SETUP_TOKEN` value, choose an email ID and password.
3. Sign in with that same email ID and password.

`POST /v1/admin/signup` is rate limited and writes an `admin.signup.completed`
audit entry. It only **adds** a super admin; it does not reset or disable an
existing one. Treat `ADMIN_SETUP_TOKEN` as a break-glass credential and remove it
from Vercel once you are back in if you no longer need it. Because the password
is now the only factor, use a long, unique password and keep the setup token
private.

Vercel Hobby allows one cron run per day, so `backend/vercel.json` schedules the
heartbeat and retention job daily at 03:00 UTC. On Vercel Pro, change the
schedule to `*/5 * * * *` for five-minute monitoring. On any plan, an external
scheduler (for example a GitHub Action or UptimeRobot) can call
`POST /v1/internal/heartbeat` with `Authorization: Bearer <CRON_SECRET>` more
frequently.

Android telemetry is deliberately metadata-only. The app reports app version,
platform, feature events, crash message/stack, and call lifecycle outcomes. It
never uploads message plaintext, encryption keys, call audio/video, passwords,
or tokens. Message counts are derived server-side from envelope inserts.

### Supabase database

`supabase/` contains a deployable schema, RLS lockdown, and storage policies for
running the relay against a Supabase project that you own. It has **not** been
applied to a project yet because no Supabase project reference or authorized
session is available in this workspace. See
[SUPABASE_DEPLOYMENT.md](SUPABASE_DEPLOYMENT.md) for the exact owner actions and
rollback plan. The production relay remains on Vercel + Neon until that cutover
is verified.

## Build the Android app

Requirements: JDK 17, Android SDK platform 35, and an internet connection for
the first dependency download. The project includes the Gradle wrapper.

```powershell
Set-Location android
$env:ANDROID_HOME = "C:\path\to\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat assembleDebug
```

The debug APK is written to:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

The debug application ID is `com.nexchat.app.debug` and defaults to the emulator
host address `http://10.0.2.2:8080`.

Release builds have the server address compiled in; there is no server field in
the app. Add your public HTTPS address to `android/local.properties` (which is
ignored by git):

```properties
NEXCHAT_SERVER_URL=https://chat.example.com
```

Alternatively, set the `NEXCHAT_SERVER_URL` environment variable when building.
For a signed release build, also create `android/keystore.properties` with the
`storeFile`, `storePassword`, `keyAlias`, and `keyPassword` properties and place
the referenced keystore under `android/`. Keep both files private; they are
ignored by version control. Then run:

```powershell
.\gradlew.bat assembleRelease
```

The release build produces one APK per ABI (`app-arm64-v8a-release.apk`,
`app-armeabi-v7a-release.apk`, `app-x86_64-release.apk`) because the Signal
crypto library alone is about 74 MB per architecture uncompressed. Native
libraries are stored compressed, and there is deliberately no universal APK so
the download stays under 50 MB. Ship the `arm64-v8a` APK to modern phones; use
`armeabi-v7a` only for older 32-bit devices and `x86_64` only for emulators.

## First use

1. Start the backend and confirm `/healthz` responds.
2. Install the APK and open NEXCHAT. The app connects to the server address that
   was compiled into the APK.
3. Choose **Create your NEX ID**.
4. Share your NEX ID or username with a contact. Search for someone under
   **Contacts → Find people**, then start a chat.
5. Compare the safety numbers shown on the contact screen in person or over a
   trusted channel before relying on the identity.
6. To test messaging between two installations, use two devices or two
   emulator instances with separate accounts and devices.

## API overview

- `POST /v1/auth/register`, `POST /v1/auth/login`, `POST /v1/auth/refresh`
- Device key upload and prekey-bundle retrieval
- User search and presence
- Envelope send/fetch/acknowledge endpoints
- WebSocket message events with authenticated sessions
- Groups, membership, and group metadata endpoints
- Profile, device listing/revocation, and account deletion

## Limitations

- No Android emulator or physical device is available in this workspace, so the
  APK has not been runtime-tested. Build success is not the same as device
  verification.
- Offline composition is durable: the encrypted intent and outbox rows survive
  process death and retry with exponential backoff, and WorkManager provides a
  15-minute background safety net. Without a push-notification service, a
  killed app can still wait up to that WorkManager interval before it wakes to
  fetch incoming messages.
- On the Vercel deployment, realtime presence and delivery-receipt pushes are
  best-effort because a socket may be held by a different function instance from
  the HTTP request. Queued ciphertext is still fetched by polling. Delivery is
  recorded in durable server receipts and synced on reconnect; a message is
  never marked delivered before the recipient device acknowledges it.
- Bluetooth LE nearby delivery, attachments, offline voice-note transcription,
  and QR device linking are intentionally absent and reported as unavailable in
  the UI. There is no WebGL/3D renderer; the silver interface is drawn natively
  with Jetpack Compose.
- Voice calls are one-to-one audio only; there is no video, group calling, or
  call recording. Both people must have the app process or foreground service
  connected. Without push notifications, a killed app cannot receive a call.
  Incoming-call alerts also depend on notification and full-screen-intent
  permission on newer Android versions. Call signalling has its own durable
  outbox with fast retries, and the client holds a dedicated long-poll so an
  offer, answer, or ICE candidate is delivered as soon as the relay stores it.
  The normal receive poll remains a 5-second fallback.
- The default deployment configures public Google STUN servers but no TURN
  relay. Calls between peers behind restrictive or symmetric NATs can therefore
  fail to connect until the operator sets `ICE_SERVERS_JSON` to include a TURN
  service. Call quality, echo cancellation, and NAT traversal have not been
  verified on physical devices in this workspace.
- Safety-number verification is currently stored per contact rather than
  independently per peer device. The screen lists the fingerprint for each known
  device; a changed identity clears the contact's verified marker.
- If a contact reinstalls the app (or otherwise changes their Signal identity),
  the client now detects the changed public identity key before sending. Use
  **Reset security session** on the contact screen after verifying the new safety
  number. Signing out and back in on both devices, which wipes local history and
  creates fresh identities, also restores messaging.
- Group delivery is pairwise fan-out, not sender-key group encryption. It costs
  more ciphertext for large groups and is capped by the server at 100 members.
- There is no automatic message backup. Signing out or uninstalling destroys
  local message history and keys.
- The server address is compiled into each APK. Pointing the app at a different
  server requires a new build and installation.
- Server deployment must provide TLS. The included Caddyfile and Docker stack
  are examples, not a turnkey production deployment.

## License

NEXCHAT is licensed under the GNU Affero General Public License, version 3
(`AGPL-3.0-only`). The full text is in [`LICENSE`](LICENSE). Because the app
includes libsignal, which is also AGPL-3.0, anyone who distributes a modified
APK or operates a modified public server must make the corresponding source
available under the same license.

Other dependencies retain their respective licenses. See
[`third_party/libsignal-notice.md`](third_party/libsignal-notice.md) for the
libsignal notice.
