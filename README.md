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
| Reliability | Durable SQLCipher-backed intent/outbox queues, automatic exponential-backoff retry, relay deduplication, durable delivery receipts, device-list refresh, duplicate-envelope detection, 5-second queued-message polling fallback, 15-minute WorkManager safety net |
| Offline composition | Messages can be written with no connection; encrypted content and the local bubble survive process death and send automatically when the relay is reachable |
| Identity verification | Per-device numeric safety numbers, contact-level verification marker, proactive identity-change detection, manual session reset from the contact screen |
| Attachments | Not implemented in this build; avatar uploads are re-encoded to strip EXIF/GPS metadata |
| Voice calls | One-to-one WebRTC audio with 1.5-second call-signal polling; SDP and ICE travel inside Signal envelopes. Video is not implemented |
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

The release APK is written to `android/app/build/outputs/apk/release/` and talks
only to the HTTPS server baked into that build.

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
  permission on newer Android versions. While a call is active, NEXCHAT polls
  the encrypted queue every 1.5 seconds; the normal receive poll runs every 5
  seconds.
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
