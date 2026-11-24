package com.nexchat.app.core.nearby

import com.nexchat.app.core.network.KeyBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPolicyTest {
    private val bundle = KeyBundle(
        deviceId = "device-1",
        signalDeviceId = 1,
        registrationId = 1,
        identityKey = "public-identity",
        signedPreKeyId = 1,
        signedPreKeyPublic = "public-signed",
        signedPreKeySignature = "signature",
        kyberPreKeyId = 1,
        kyberPreKeyPublic = "public-kyber",
        kyberPreKeySignature = "signature",
    )

    private fun payload(
        hopCount: Int = 1,
        maxHops: Int = 3,
        expiresAt: Long = 10_000L,
    ) = NearbyRoutedPayload(
        packetId = "packet-1",
        type = NearbyProtocol.TYPE_MESSAGE,
        targetNexId = "target",
        targetSignalDeviceId = 3,
        originNexId = "origin",
        originSignalDeviceId = 1,
        clientId = "client-1",
        innerCipherType = 7,
        innerCiphertext = "E2E-CIPHERTEXT",
        createdAt = 1_000L,
        expiresAt = expiresAt,
        hopCount = hopCount,
        maxHops = maxHops,
    )

    @Test
    fun expiryIsInclusiveAndRejectsStalePackets() {
        assertTrue(NearbyPolicy.isExpired(1_000L, 1_000L))
        assertTrue(NearbyPolicy.isExpired(999L, 1_000L))
        assertFalse(NearbyPolicy.isExpired(1_001L, 1_000L))
        assertFalse(NearbyPolicy.shouldProcessPacket(payload(expiresAt = 1_000L), false, 1_000L))
    }

    @Test
    fun hopLimitIsEnforcedBeforeAndAfterRelaying() {
        assertTrue(NearbyPolicy.withinHopLimit(payload(hopCount = 3, maxHops = 3)))
        assertFalse(NearbyPolicy.withinHopLimit(payload(hopCount = 4, maxHops = 3)))
        assertFalse(NearbyPolicy.shouldProcessPacket(payload(hopCount = 4, maxHops = 3), false, 2_000L))
        assertFalse(NearbyPolicy.canRelay(payload(hopCount = 3, maxHops = 3), 2_000L))
        assertFalse(NearbyPolicy.shouldProcessPacket(payload(hopCount = -1), false, 2_000L))
    }

    @Test
    fun dedupeDecisionRejectsProcessedPacketsAndUsesDistinctAckKey() {
        assertTrue(NearbyPolicy.shouldProcessPacket("packet-1", false, 10_000L, 1, 3, 2_000L))
        assertFalse(NearbyPolicy.shouldProcessPacket("packet-1", true, 10_000L, 1, 3, 2_000L))
        assertFalse(NearbyPolicy.shouldProcessPacket("", false, 10_000L, 1, 3, 2_000L))
        val message = payload()
        val ack = NearbyPolicy.ackCopy(message, "local", 2, 2_000L)
        assertEquals("packet-1", NearbyPolicy.dedupeKey(message))
        assertEquals("packet-1|ack", NearbyPolicy.dedupeKey(ack))
        assertNotEquals(NearbyPolicy.dedupeKey(message), NearbyPolicy.dedupeKey(ack))
    }

    @Test
    fun ackTargetSelectionPrefersDestinationAndExcludesSender() {
        val sender = NearbyPeer("origin", 1, null, "aa:aa", true, bundle)
        val relay = NearbyPeer("relay", 2, null, "bb:bb", true, bundle)
        val target = NearbyPeer("target", 3, null, "cc:cc", true, bundle)
        val ack = NearbyPolicy.ackCopy(payload(), "target", 3, 2_000L)

        // The ACK is addressed back to the original sender and never to the
        // peer that originated the ACK.
        assertEquals(
            listOf(sender),
            NearbyPolicy.ackTargets(ack, listOf(sender, relay, target), null),
        )
        assertEquals(
            listOf(relay),
            NearbyPolicy.ackTargets(ack, listOf(sender, relay, target), "aa:aa"),
        )
    }

    @Test
    fun oversizedFramesAreRejectedByCodec() {
        assertTrue(NearbyPolicy.frameSizeAllowed(NearbyPolicy.MAX_FRAME_BYTES))
        assertFalse(NearbyPolicy.frameSizeAllowed(NearbyPolicy.MAX_FRAME_BYTES + 1))
        assertFalse(NearbyPolicy.frameSizeAllowed(0))
        assertEquals(
            null,
            NearbyFrameCodec.encode(
                NearbyWireFrame.routed("x".repeat(NearbyPolicy.MAX_FRAME_BYTES), 1),
            ),
        )
        val encoded = NearbyFrameCodec.encode(NearbyWireFrame.ack("cipher", 2))
        assertNotNull(encoded)
        assertTrue(encoded!!.endsWith("\n"))
        assertEquals(NearbyProtocol.TYPE_ACK, NearbyFrameCodec.decode(encoded)?.type)
        assertEquals(
            null,
            NearbyFrameCodec.decode("x".repeat(NearbyPolicy.MAX_FRAME_BYTES + 1)),
        )
    }

    @Test
    fun relayCopyIncrementsHopsWithoutTouchingInnerCiphertext() {
        val original = payload(hopCount = 2)
        val relayed = NearbyPolicy.relayCopy(original)

        assertEquals(2, original.hopCount)
        assertEquals(3, relayed.hopCount)
        assertSame(original.innerCiphertext, relayed.innerCiphertext)
        assertEquals(original.innerCipherType, relayed.innerCipherType)
        assertEquals(original.clientId, relayed.clientId)
        assertEquals(original.packetId, relayed.packetId)
    }

    @Test
    fun rateLimiterIsPerPeerAndWindowed() {
        val limiter = NearbyPolicy.RateLimiter(maxEvents = 2, windowMs = 1_000L)
        assertTrue(limiter.allow("peer-a", 0L))
        assertTrue(limiter.allow("peer-a", 10L))
        assertFalse(limiter.allow("peer-a", 20L))
        assertTrue(limiter.allow("peer-b", 20L))
        assertTrue(limiter.allow("peer-a", 1_000L))
    }

    @Test
    fun storageIdRecoversClientIdForExpiryFailures() {
        val id = NearbyPolicy.storageId("packet-1", "client-1", localOrigin = true)
        assertEquals("client-1", NearbyPolicy.clientIdFromStorageId(id, "packet-1"))
        assertEquals("packet-1", NearbyPolicy.clientIdFromStorageId("foreign-row", "packet-1"))
    }

    @Test
    fun routedPayloadCodecKeepsTheInnerCiphertextOpaque() {
        val encoded = NearbyFrameCodec.encodeRouting(payload())
        assertNotNull(encoded)
        val decoded = NearbyFrameCodec.decodeRouting(encoded!!)
        assertNotNull(decoded)
        assertEquals("E2E-CIPHERTEXT", decoded!!.innerCiphertext)
        assertEquals(null, NearbyFrameCodec.decodeRouting(encoded.replace("E2E-CIPHERTEXT", "")))
    }
}
