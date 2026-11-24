package com.nexchat.app.core.nearby

/**
 * Pure, Android-free policy for nearby routing. Keeping these decisions here
 * makes them unit-testable without a device and prevents transport code from
 * scattering expiry/hop/dedupe rules across several call sites.
 */
internal object NearbyPolicy {
    const val MAX_FRAME_BYTES: Int = 512 * 1024
    const val MAX_ROUTING_BYTES: Int = 320 * 1024
    const val DEFAULT_MAX_HOPS: Int = 6
    const val MAX_STORED_PACKETS: Int = 128
    const val DISCOVERY_WINDOW_MS: Long = 8_000L
    const val DISCOVERY_MIN_INTERVAL_MS: Long = 10_000L
    const val RATE_LIMIT_PER_PEER: Int = 90
    const val RATE_LIMIT_WINDOW_MS: Long = 60_000L
    const val MAX_PACKET_TTL_SECONDS: Int = 7 * 24 * 60 * 60

    private const val KEY_SEPARATOR = '\u001f'
    private const val ACK_DEDUPE_SUFFIX = "|ack"

    fun frameSizeAllowed(byteCount: Int): Boolean = byteCount > 0 && byteCount <= MAX_FRAME_BYTES

    fun routingPayloadAllowed(byteCount: Int): Boolean = byteCount > 0 && byteCount <= MAX_ROUTING_BYTES

    fun isExpired(expiresAt: Long, now: Long): Boolean = expiresAt <= now

    fun withinHopLimit(hopCount: Int, maxHops: Int): Boolean =
        hopCount >= 0 && maxHops > 0 && hopCount <= maxHops

    fun withinHopLimit(payload: NearbyRoutedPayload): Boolean =
        withinHopLimit(payload.hopCount, payload.maxHops)

    /** ACKs reuse the message packet ID, so they need a separate dedupe key. */
    fun dedupeKey(payload: NearbyRoutedPayload): String =
        if (payload.type == NearbyProtocol.TYPE_ACK) {
            payload.packetId + ACK_DEDUPE_SUFFIX
        } else {
            payload.packetId
        }

    fun shouldProcessPacket(
        packetId: String,
        alreadyProcessed: Boolean,
        expiresAt: Long,
        hopCount: Int,
        maxHops: Int,
        now: Long,
    ): Boolean = packetId.isNotBlank() &&
        !alreadyProcessed &&
        !isExpired(expiresAt, now) &&
        withinHopLimit(hopCount, maxHops)

    fun shouldProcessPacket(payload: NearbyRoutedPayload, alreadyProcessed: Boolean, now: Long): Boolean =
        shouldProcessPacket(
            packetId = payload.packetId,
            alreadyProcessed = alreadyProcessed,
            expiresAt = payload.expiresAt,
            hopCount = payload.hopCount,
            maxHops = payload.maxHops,
            now = now,
        )

    fun canRelay(payload: NearbyRoutedPayload, now: Long): Boolean =
        !isExpired(payload.expiresAt, now) && withinHopLimit(relayCopy(payload))

    /** Returns the next relay copy. The inner ciphertext is never transformed. */
    fun relayCopy(payload: NearbyRoutedPayload): NearbyRoutedPayload =
        payload.copy(hopCount = payload.hopCount + 1)

    fun ackCopy(
        payload: NearbyRoutedPayload,
        localNexId: String,
        localSignalDeviceId: Int,
        at: Long,
    ): NearbyRoutedPayload = payload.copy(
        type = NearbyProtocol.TYPE_ACK,
        targetNexId = payload.originNexId,
        targetSignalDeviceId = payload.originSignalDeviceId,
        originNexId = localNexId,
        originSignalDeviceId = localSignalDeviceId,
        groupId = null,
        innerCipherType = 0,
        innerCiphertext = null,
        createdAt = at,
        expiresAt = minOf(payload.expiresAt, at + 60_000L),
        hopCount = 0,
        maxHops = payload.maxHops,
        ackAt = at,
    )

    /** Recovers a client ID for expiry failures, including after process death. */
    fun storageId(packetId: String, clientId: String, localOrigin: Boolean): String =
        listOf(if (localOrigin) "local" else "relay", clientId, packetId)
            .joinToString(KEY_SEPARATOR.toString())

    fun clientIdFromStorageId(storageId: String, packetId: String): String {
        val parts = storageId.split(KEY_SEPARATOR)
        return if (parts.size >= 3 && parts.last() == packetId && parts[1].isNotBlank()) {
            parts[1]
        } else {
            packetId
        }
    }

    private fun eligiblePeers(
        payload: NearbyRoutedPayload,
        peers: List<NearbyPeer>,
        excludedAddress: String?,
    ): List<NearbyPeer> = peers.filter { peer ->
        peer.connected &&
            peer.nexId.isNotBlank() &&
            peer.keyBundle != null &&
            peer.address != excludedAddress &&
            peer.nexId != payload.originNexId
    }

    /**
     * Prefer a connected device that exactly matches the routed destination.
     * Otherwise flood only to peers that have exchanged a HELLO, except the
     * peer that sent this packet back to us.
     */
    fun relayTargets(
        payload: NearbyRoutedPayload,
        peers: List<NearbyPeer>,
        excludedAddress: String?,
    ): List<NearbyPeer> {
        val eligible = eligiblePeers(payload, peers, excludedAddress)
        val direct = eligible.filter {
            it.nexId == payload.targetNexId && it.signalDeviceId == payload.targetSignalDeviceId
        }
        return direct.ifEmpty { eligible }
    }

    /** ACKs travel toward the original sender, again avoiding the sender hop. */
    fun ackTargets(
        payload: NearbyRoutedPayload,
        peers: List<NearbyPeer>,
        excludedAddress: String?,
    ): List<NearbyPeer> {
        val eligible = eligiblePeers(payload, peers, excludedAddress)
        val direct = eligible.filter {
            it.nexId == payload.targetNexId && it.signalDeviceId == payload.targetSignalDeviceId
        }
        return direct.ifEmpty { eligible }
    }

    /** A fixed-window per-peer frame budget; no per-message plaintext is kept. */
    class RateLimiter(
        private val maxEvents: Int = RATE_LIMIT_PER_PEER,
        private val windowMs: Long = RATE_LIMIT_WINDOW_MS,
    ) {
        private val events = LinkedHashMap<String, ArrayDeque<Long>>()

        @Synchronized
        fun allow(key: String, now: Long): Boolean {
            val queue = events.getOrPut(key) { ArrayDeque() }
            while (queue.isNotEmpty() && now - queue.first() >= windowMs) queue.removeFirst()
            if (queue.size >= maxEvents) return false
            queue.addLast(now)
            return true
        }

        @Synchronized
        fun reset() {
            events.clear()
        }
    }
}
