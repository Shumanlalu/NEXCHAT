# NEXCHAT calling repair — implementation notes

These notes describe the calling fixes applied in this workspace. Messaging code was
left intact; the Signal envelope transport is still used, with call signals now marked
as priority envelopes.

## Root causes fixed

### Android

1. **Incoming-call notification ID collided with the microphone foreground service ID**
   (`NotificationHelper.kt`). Both used `4802`. Accepting a call cancelled the FGS
   notification, which can cause Android to kill `CallAudioService` and lose legal
   microphone access. The incoming notification now uses `4803`.

2. **Signals were processed out of order** (`CallManager.kt`). The offer was handled in
   one coroutine while answer/ICE/hangup were handled synchronously on the collector.
   A cancel or answer could arrive before the offer had created call state and was
   silently dropped, leaving a ghost call. All call signals now go through one FIFO
   channel and one consumer. A terminal signal that arrives before its offer is
   buffered and replayed as soon as the offer creates state.

3. **Ring/connect timers started after blocking network sends** (`CallManager.kt`).
   The ring timer started only after `sendCallSignalFast()` returned, and the connect
   timer only after the answer was created. A stalled POST could leave the UI on
   "Calling…"/"Connecting…" for far longer than intended. Both timers now start
   before the network/SDP work.

4. **Cancel/decline semantics were wrong**. A caller cancellation was shown to the
   callee as "Missed call", and a callee decline reason `canceled` was shown as
   "Call declined". Both are now mapped consistently.

5. **End-of-call signals could be lost or sent late**. All terminal signal sends now
   log failures, and pending offer/ICE rows are deleted when a call finishes so they
   cannot ring the peer after both sides hung up.

6. **WebRTC state changes touched Android UI/audio APIs from WebRTC threads**.
   ICE/connection callbacks are now dispatched to the main thread before starting the
   foreground service, changing audio focus, or taking the proximity wake lock.

7. **No teardown on logout**. `CallManager.teardown()` now runs when the signed-in
   session ends, releasing media, timers, wake lock, notification and foreground
   service.

8. **Long-poll failure backoff was up to 5 seconds** (`WebSocketManager.kt`). A single
   failed serverless request could delay incoming signaling. Backoff is now bounded
   to 500–1000 ms.

9. **ICE configuration was cached for 10 minutes** (`CallManager.kt`). TURN credentials
   can expire sooner. Cache is now 30 seconds and fetch failures are logged.

10. **Stale UI text** claimed calls were not implemented (`DiscoverScreen.kt`,
    `SettingsScreen.kt`). It now describes the actual voice-only implementation.

11. **Maintenance banner text could push content under the bottom navigation**
    (`RemoteConfigUi.kt`). It is now bounded to three lines with ellipsis.

### Backend (Vercel + Neon / self-hosted)

1. **No dedicated call APIs existed.** Calls were ordinary Signal-encrypted envelopes,
   so the server could not prioritize an offer behind older undecryptable envelopes.
   `envelopes` now has a `priority` column; call signals are inserted with
   `priority = true`, and both `/v1/messages/pending` and `/v1/messages/wait` return
   priority envelopes first (`schema.sql`, `validation.ts`, `messages.ts`, Android
   `ApiModels.kt`/`ChatRepository.kt`).

2. **The 5-second call TTL floor did not match the client.** The client clamps near-expiry
   call signals to 5 seconds while the API required at least 10. The API now accepts
   5 seconds and the client keeps a conservative 10-second floor until the backend is
   redeployed.

3. **Concurrent call telemetry inserts could fail with a duplicate-key error**.
   `POST /v1/analytics/calls` now uses `INSERT ... ON CONFLICT DO UPDATE` with a
   participant check and rank guard, so caller and callee can report simultaneously
   (`analytics.ts`).

4. **Ringing call rows were never cleaned up**. The heartbeat job now marks ringing
   sessions older than two minutes as `failed/ring_timeout` and applies retention
   (`analytics.ts`).

5. **Process-local WebSocket pushes could turn a successful envelope commit into a
   500**. `WsHub.sendToDevice` now catches closed-socket send errors, and the socket
   registry handles clients that disconnect during authentication (`hub.ts`,
   `create-app.ts`).

6. **Vercel function duration was not configured**. The earlier attempt to declare
   `maxDuration` with a `functions` pattern broke every deploy because this app has
   no `api/` directory. The invalid mapping is removed; set 60 seconds in Project
   Settings → Functions if longer polls are needed. The long poll asks for 20 seconds.

7. **Cron cleanup was spoofable when `CRON_SECRET` was unset**. In production the
   heartbeat now returns 503 until `CRON_SECRET` is configured.

8. **Admin dashboard "Recent calls" ignored the selected date range**, and admin logout
   did not verify CSRF. Both are fixed (`admin-core.ts`, `admin-auth.ts`).

9. **Super admin sign-in required Google Authenticator/TOTP.** The panel now uses
   email ID and password only. New admin accounts are created with an email ID;
   existing username-only rows keep working so a deployed panel is not locked
   out. CSRF tokens, HttpOnly cookies, lockout, RBAC, and audit logging are
   unchanged (`admin-auth.ts`, `admin-config.ts`, `admin/security.ts`, `ui.html`).

10. **The admin auth UX and password storage needed a full upgrade.** The panel
   now has show/hide passwords, Remember me (30-day session), a setup-token
   forgot-password flow, full name and confirm password fields, and responsive
   light/dark animations. Public admin signup was removed completely: first-run
   setup closes permanently, and later admins are created only by an
   authenticated super admin with CSRF and `admins.manage`. New passwords use
   bcrypt with a SHA-256 prehash; legacy scrypt hashes verify and upgrade on
   login. Setup tokens, reset tokens, passwords and session cookies are redacted
   from logs. No Google OAuth, Google Sign-In dependency, TOTP, or QR enrollment
   exists in the live backend (`admin-auth.ts`, `admin-config.ts`, `ui.ts`,
   `passwords.ts`, `schema.sql`, `ui.html`, `create-app.ts`).

## Modified files

### Android
- `app/src/main/java/com/nexchat/app/call/CallManager.kt`
- `app/src/main/java/com/nexchat/app/service/NotificationHelper.kt`
- `app/src/main/java/com/nexchat/app/net/WebSocketManager.kt`
- `app/src/main/java/com/nexchat/app/core/network/ApiModels.kt`
- `app/src/main/java/com/nexchat/app/data/ChatRepository.kt`
- `app/src/main/java/com/nexchat/app/NexChatApp.kt`
- `app/src/main/java/com/nexchat/app/ui/screens/DiscoverScreen.kt`
- `app/src/main/java/com/nexchat/app/ui/screens/SettingsScreen.kt`
- `app/src/main/java/com/nexchat/app/ui/components/RemoteConfigUi.kt`

### Backend
- `backend/src/schema.sql`
- `backend/src/http.ts`
- `backend/src/validation.ts`
- `backend/src/routes/messages.ts`
- `backend/src/routes/analytics.ts`
- `backend/src/routes/admin-auth.ts`
- `backend/src/routes/admin-config.ts`
- `backend/src/routes/admin-core.ts`
- `backend/src/admin/security.ts`
- `backend/src/admin/ui.ts`
- `backend/src/admin/ui.html`
- `backend/src/admin/qr.ts` (removed with the QR dependency)
- `backend/src/security/passwords.ts`
- `backend/src/ws/hub.ts`
- `backend/src/create-app.ts`
- `backend/vercel.json`
- `backend/package.json` / `backend/package-lock.json`
- `backend/scripts/generate-admin-setup-token.js`
- `backend/test/admin.test.ts`
- `backend/test/passwords.test.ts`
- `backend/test/setup-token.test.ts`
- `backend/test/wait.test.ts`

A pre-change copy of these files is in `CALL_FIX_BACKUP_20261123/`.

## Deployment required before testing

The backend **must be redeployed**; the APK alone is not enough.

1. Set `CRON_SECRET` in the Vercel project (a long random value) and redeploy.
2. Confirm the Vercel project's function duration setting is at least 60 seconds.
   The `vercel.json` `maxDuration` entry requests this; some Vercel plans require
   setting it in Project Settings → Functions as well.
3. Confirm `ICE_SERVERS_JSON` contains a TURN server for restrictive/symmetric NATs.
   STUN-only calls can fail to connect even when signaling works.
4. Redeploy: `npx vercel --prod --yes` (or your usual pipeline).
5. Install the newly built ABI-specific release APK on the phone.

No credentials or secret values are stored in this repository.

## Test results in this workspace

Run here:

- `npm run typecheck` — passed.
- `npm test` — 30/30 passed, including:
  - priority call envelopes are delivered before older envelopes;
  - simultaneous caller/callee call reports create one session row;
  - email/password-only admin login and legacy username compatibility.
- `./gradlew.bat assembleRelease` — see the final build output/status in this session.

Not possible in this workspace:

- Two-way audio, microphone routing, echo cancellation, NAT traversal, full-screen
  incoming calls, background/killed-app behavior, and real-device Accept/Decline.
  These require physical Android devices and a real TURN deployment.

## Practical test plan with one phone + Windows laptop

The laptop can exercise the backend and observe signaling, but it cannot be a WebRTC
peer with the current app (the WebRTC implementation is Android-only).

1. **Backend health and config**: from the laptop,
   `Invoke-RestMethod https://<your-deployment>/healthz` and
   `Invoke-RestMethod https://<your-deployment>/v1/config`. Confirm `audioCalls` is
   `webrtc-audio-1to1` and note `turnConfigured`.
2. **Long poll**: run `node backend/wsprobe.cjs` only as a WebSocket upgrade probe;
   it is not a call test. For API-level verification use the backend tests above.
3. **Single-phone UI checks**: install the APK, sign in, verify the call button is
   present, grant the microphone permission, and start a call to another account.
   The outgoing screen should show "Calling…" and reach "No answer" after ~45 s
   instead of sticking on "Connecting…".
4. **Admin checks**: open `/admin`, confirm call rows appear and stale ringing rows
   are moved to `failed/ring_timeout` after the heartbeat runs.

## Tests that require two physical Android devices

- Immediate incoming call delivery on Device B.
- Answer → both devices reach Connected.
- Decline → both devices end immediately with no ghost call.
- Two-way audio, mute and speakerphone.
- Caller cancellation while Device B is still ringing.
- Network drop and reconnect during a call.
- Repeated calls and duplicate/stale incoming-call prevention.
- Background/killed-app incoming call behavior (limited without push notifications).
- Calls across restrictive NAT (requires TURN).

Without push notifications, a killed app can still miss a call entirely: the offer
expires after 45 seconds and WorkManager wakes only every 15 minutes. This is a
platform limitation, not a signaling bug.
