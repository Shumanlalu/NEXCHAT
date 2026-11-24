# NEXCHAT unified online/offline architecture

Date: 2026-11-24
Scope: existing `S:\nexchat` Android app, admin panel, backend and database. This is an additive integration design, not a new application.

## 1. Existing architecture confirmed

| Layer | Existing implementation |
|---|---|
| Android UI | Jetpack Compose with Navigation Compose, Material 3, ViewModels. |
| Local storage | Room 2.6.1 over SQLCipher 4.6.1, database `nexchat_v1.db`, Keystore-backed passphrase. |
| Crypto | `org.signal:libsignal-android:0.86.5`; X3DH/PQXDH and Double Ratchet via `SignalCryptoManager`/`NexSignalStore`. Pairwise sessions already exist for one-to-one and group fan-out. |
| Online relay | Fastify/TypeScript backend; opaque `EnvelopeDto` ciphertext; WebSocket plus long-poll; durable outbox/receipts. |
| Device identity | Relay-authoritative device ID, canonicalized to libsignal's 1..127 protocol range; relay does not hold private keys. |
| Attachments | Chunked Signal-encrypted manifests and chunks stored locally before relay acceptance. |
| Calling | WebRTC audio/video signaling carried inside Signal envelopes through the online relay. |
| Offline/nearby | `DiscoverScreen` explicitly states nearby delivery is not implemented. `relay_packets` table and DAO exist but are only used by cleanup/sign-out. |
| Admin sync | Remote config, bans/suspensions, device revocation, maintenance/min-version/call flags are enforced server-side and reflected on reconnect. |

## 2. Transport decision

Bluetooth Classic RFCOMM is the primary nearby transport for this integration.

Reasons:

- Available on the supported Android device class without requiring Google Play Services.
- Supports a small socket stream suitable for bounded JSON frames and encrypted blobs.
- Works in Airplane Mode when Bluetooth remains enabled.
- Can provide direct 1:1 links and a connected component from which multi-hop forwarding is possible.

BLE is useful for battery-efficient discovery but cannot reliably carry the encrypted payloads and attachment chunks required here; it will be used only as a low-power discovery/advertising aid where available. Wi-Fi Direct is not used as the primary transport in this release because Android 13+ platform restrictions, device-specific behaviour and companion-network rules make it less reliable and more invasive than Bluetooth for this installation base. This is a documented limitation, not a claim of universal nearby compatibility.

## 3. Nearby protocol

All nearby traffic is framed newline-delimited UTF-8 JSON over an authenticated RFCOMM socket. A frame never contains plaintext message content.

### 3.1 Hello and key bundle

On connection both peers exchange:

```json
{
  "v": 1,
  "type": "HELLO",
  "nexId": "...",
  "signalDeviceId": 1,
  "registrationId": 123,
  "identityKey": "base64",
  "signedPreKeyId": 1,
  "signedPreKeyPublic": "base64",
  "signedPreKeySignature": "base64",
  "kyberPreKeyId": 1,
  "kyberPreKeyPublic": "base64",
  "kyberPreKeySignature": "base64",
  "oneTimePreKeys": [{"id": 1, "publicKey": "base64"}]
}
```

Only public key material is sent. libsignal establishes pairwise sessions from the bundle. Trust-on-first-use is recorded inside the SQLCipher Signal store; later identity changes raise `IdentityChangedException`, clear the verified marker and require explicit safety-number verification.

### 3.2 Routed packet

The routing header is wrapped in a Signal session with the immediate neighbour, so only that neighbour can read the header. The inner payload remains the original Signal ciphertext addressed to the final recipient; intermediate relays never decrypt it.

```json
{
  "v": 1,
  "type": "MESSAGE",
  "packetId": "uuid",
  "targetNexId": "...",
  "targetSignalDeviceId": 1,
  "originNexId": "...",
  "originSignalDeviceId": 1,
  "clientId": "...",
  "groupId": null,
  "innerCipherType": 3,
  "innerCiphertext": "base64",
  "createdAt": 0,
  "expiresAt": 0,
  "hopCount": 0,
  "maxHops": 4
}
```

`ACK` packets use the same envelope with `type = "ACK"` and no inner payload. `HELLO` is exchanged before routed packets and is never forwarded.

### 3.3 Relay behaviour

1. `packetId` is checked against `processed_envelopes`; duplicates are discarded.
2. `expiresAt`, `hopCount <= maxHops`, frame size and per-peer rate limits are enforced before storage.
3. A packet for the local device is unwrapped to the original `EnvelopeDto` and handed to `ChatRepository.receiveEnvelope(..., acknowledge = false)`.
4. A packet for another device is stored in `relay_packets`, incremented by one hop, re-wrapped for each eligible neighbour except the sender, and forwarded.
5. The final recipient emits an `ACK` addressed to `originNexId`/`originSignalDeviceId`. ACKs are routed with the same algorithm and advance the local message to `DELIVERED`.
6. Expired packets are purged by `CleanupWorker` and a bounded in-process sweeper. A failure event is emitted so the origin can show `Failed` instead of pretending delivery occurred.

This is authenticated flooding with TTL, hop limit, deduplication and per-peer throttling. It does not require a stable routing table and tolerates peers entering/leaving the connected component. Relay devices see routing metadata and ciphertext length, not private content.

## 4. Online/offline path selection

1. When the online relay accepts an envelope, existing online behaviour is unchanged.
2. When the online relay is unreachable or explicitly rejects delivery because the recipient is unavailable, due outbox rows are handed to the nearby transport.
3. The nearby transport persists packets in `relay_packets` before attempting sockets, so process death does not lose ciphertext.
4. If no nearby route is available, packets remain in `relay_packets` and the nearby transport retries on discovery, app foregrounding and WorkManager cycles.
5. If a packet expires without ACK, the origin message is marked failed with a clear error. No `DELIVERED` state is set without an ACK.

Statuses remain compatible with the existing database values:

- `PENDING` — composed, encrypted and queued.
- `SENT` — accepted by the online relay or stored by the nearby relay layer.
- `DELIVERED` — online receipt or nearby ACK received.
- `READ` — existing read receipt received.
- `FAILED` — expired or permanently rejected.

A nearby `Relayed` state is tracked in the nearby layer and surfaced in the nearby UI rather than changing the shared message schema.

## 5. Groups and files

- Existing group fan-out already creates one Signal ciphertext per member device. Those envelopes are routed unchanged through the nearby layer, preserving group binding and sender authentication.
- Group metadata envelopes use the existing relay-envelope binding and watermark. Nearby delivery supplies the same envelope fields, so spoofed or stale GROUP_META is rejected by the existing checks.
- Attachment manifests and chunks are already Signal-encrypted and independently addressed. The nearby router treats them as ordinary routed packets; encrypted chunks may arrive in any order and are reassembled by the existing attachment store after SHA-256 verification.
- Large transfers are bounded by frame size, per-peer quotas, TTL and relay storage caps.

## 6. Identity verification

- Numeric safety-number generation already exists in `SignalCryptoManager`.
- The integration adds a contact-facing safety-number screen with copy/share and QR display. QR scanning, where available, compares the scanned numeric string; manual comparison remains available on devices without a scanner.
- Identity change warnings reuse the existing `ChatEvent.Security` path. No automatic trust is granted after an identity change.

## 7. Admin/mobile synchronization

Existing server-side enforcement remains authoritative:

- Ban/suspend/revoke is checked on login, refresh, API calls, presence, long-poll and WebSocket.
- Device revocation closes the live socket and prevents a fresh access token.
- Maintenance mode, minimum app version and call flags are enforced in the global API preHandler.
- Nearby delivery cannot bypass these restrictions for online sessions; nearby packets are transport-only and carry no authority to change account state. Offline peers receive updated restrictions when they next authenticate with the relay.

## 8. Permissions and battery

Manifest additions:

- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` on Android 12+.
- `ACCESS_FINE_LOCATION` only for Android 11 and below, where classic discovery requires it.
- `android:usesPermissionFlags="neverForLocation"` where supported.

Discovery is bounded and event-driven:

- On-demand from the Nearby screen.
- Automatically when an offline outbox has a due packet and the user is logged in.
- On reconnect of the foreground messaging service, with a cooldown.
- No continuous high-duty scanning while idle.

## 9. Metadata still visible

Nearby peers and relays can observe: device Bluetooth address, packet size, timing, hop path, target NEX ID/device ID in the hop wrapper, and whether a packet is a message/ACK. The online relay can observe authenticated device identity, IP, connection timing and envelope sizes. Encryption does not hide this metadata.

## 10. Release gates

A coordinated release is not called production-ready until:

- backend tests, typecheck and build pass;
- Android unit tests, lint and release assemble pass;
- two-device online/offline integration checks pass;
- at least three-device A→B→C relay check passes;
- tamper, replay, duplicate, expiry and unauthorized relay checks pass;
- both Vercel checks and public endpoints are verified after push;
- real-device limitations are explicitly listed in release notes.

Automated tests in this workspace cannot prove real Bluetooth, Airplane Mode, Wi-Fi Direct or three-device behaviour. Those results must be recorded separately by an operator with physical devices.

## 11. Dependency review

Checked against official GitHub repository metadata on 2026-11-24:

| Dependency | Repository status | License | Assessment |
|---|---|---|---|
| `org.signal:libsignal-android:0.86.5` | `signalapp/libsignal`, active, pushed 2026-11-24, AGPL project | AGPL-3.0 | Maintained, independently reviewed protocol implementation. Existing dependency; no custom cryptography added. AGPL obligations already apply to the existing app. |
| `com.google.zxing:core:3.5.3` (new) | `zxing/zxing`, active, pushed 2026-11-23 | Apache-2.0 | Maintained OSS QR encoder used only for safety-number display. No paid service. |
| `net.zetetic:sqlcipher-android:4.6.1` | `sqlcipher/sqlcipher-android`, pushed 2026-10-27, metadata license `NOASSERTION` | Dual/community-commercial; operator review required | Existing dependency. No new cost introduced, but the operator should confirm the chosen licensing tier. |
| `io.github.webrtc-sdk:android:125.6422.07` | `webrtc-sdk/android`, pushed 2026-11-19 | MIT | Maintained prebuilt WebRTC Android artifacts; existing dependency. |
| `fastify@4.28.1` | `fastify/fastify`, pushed 2026-11-24 | MIT | Actively maintained backend framework; existing dependency. |

No mandatory paid messaging API, encryption service, subscription or AI dependency is introduced by this design. Vercel/Neon hosting and domain costs remain operator-owned and unchanged by this work.

## 12. Implementation status (2026-11-24)

The design above is now implemented in the working tree. The coordinated operational
details are in `RELEASE_2026-11-24.md`.

| Area | Implemented behaviour |
|---|---|
| Online transport | Existing authenticated Signal-envelope relay, WebSocket plus long-poll, durable outbox and receipts remain the primary path. |
| Nearby transport | `NearbyRelayManager` uses Bluetooth Classic RFCOMM; BLE remains discovery-only; no plaintext crosses a nearby socket. |
| Nearby routing | Signal-wrapped routing header for the immediate neighbour; inner ciphertext stays E2EE for the final recipient; relays never decrypt inner content. |
| Multi-hop safety | Flooding with `processed_envelopes` dedupe, TTL, hop limit, frame cap, per-peer rate limit, bounded `relay_packets`, ACKs and store-and-forward retries. |
| Path selection | Online first; nearby is used only for retryable online failures and for queued offline sends, then online sync resumes on reconnect. |
| Groups | Outgoing content is stamped with `conversation.groupId` at the send chokepoints so relay-side membership checks run; incoming group membership is rechecked locally. |
| Identity | Existing Signal identity keys/safety numbers are reused; QR display and copy were added with ZXing; identity changes clear verification and surface an in-app security event. |
| Attachments | Signal-encrypted chunks are bound to a validated manifest/conversation, bounded, and orphan chunks expire. |
| Calling | WebRTC signaling remains inside Signal envelopes; terminal REJECT/HANGUP rows survive concurrent cleanup until their short TTL; TURN credentials are minted on demand. |
| Admin control | Server-side version/maintenance enforcement, suspended/banned enforcement, device revocation, session expiry handling, remote-config race guard and CSV correctness are implemented. |
| Backend operations | Rate-limit counters moved to Postgres; Supabase migration parity added; Node runtime aligned to 24.x. |

Automated verification completed: backend typecheck, 63 Node tests, backend build,
admin inline-script parse, Android JVM unit tests, `assembleDebug` and `lintDebug`.
Real-device Bluetooth, Airplane Mode and three-device relay verification remain
operator responsibilities and are explicitly not claimed here.

