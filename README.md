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
| Message states | Pending, sent, delivered, read, failed; queued in an encrypted outbox |
| Reliability | Retry loop, prekey replenishment, duplicate-envelope detection |
| Identity verification | Per-device numeric safety numbers, contact-level verification marker |
| Attachments | Not implemented in this build |
| Voice/video calls | Not implemented in this build |
| Bluetooth LE nearby messaging | Not implemented in this build |
| QR device linking | Not implemented; sign in separately on each device |
| Push notifications | Not implemented; a foreground WebSocket service keeps the connection alive |

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
check.

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
- Online messaging requires the app process or foreground service to maintain a
  WebSocket. There is no push-notification service; aggressive battery
  optimizations can delay messages.
- Calls, Bluetooth LE nearby delivery, attachments, and QR device linking are
  intentionally absent and reported as unavailable in the UI.
- Safety-number verification is currently stored per contact rather than
  independently per peer device. The screen lists the fingerprint for each known
  device; a changed identity clears the contact's verified marker.
- If a contact reinstalls the app (or otherwise changes their Signal identity),
  the old pairwise session is rejected and this build has no in-chat "accept new
  identity" action. Signing out and back in on both devices, which wipes local
  history and creates fresh identities, restores messaging. Treat an unexpected
  safety-number change as a security warning rather than an automatic refresh.
- Group delivery is pairwise fan-out, not sender-key group encryption. It costs
  more ciphertext for large groups and is capped by the server at 100 members.
- There is no automatic message backup. Signing out or uninstalling destroys
  local message history and keys.
- The server address is compiled into each APK. Pointing the app at a different
  server requires a new build and installation.
- Server deployment must provide TLS. The included Caddyfile and Docker stack
  are examples, not a turnkey production deployment.

## Third-party licenses

libsignal is licensed under AGPL-3.0. See the libsignal project and its notices
before distributing a modified build. Other dependencies retain their respective
licenses. No project-wide license has been selected in this workspace.
