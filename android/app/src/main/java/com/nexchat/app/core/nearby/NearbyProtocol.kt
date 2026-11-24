package com.nexchat.app.core.nearby

import com.nexchat.app.core.network.KeyBundle
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Newline-delimited JSON frames exchanged over Bluetooth RFCOMM.
 *
 * HELLO frames carry only public Signal key material. Routed frames carry a
 * Signal-encrypted routing header; the inner ciphertext is the original
 * end-to-end envelope for the final recipient and is never decrypted by a
 * relay.
 */
internal object NearbyProtocol {
    const val VERSION = 1
    const val TYPE_HELLO = "HELLO"
    const val TYPE_MESSAGE = "MESSAGE"
    const val TYPE_ACK = "ACK"

    const val SERVICE_NAME = "NexChat Nearby"
    val SERVICE_UUID: UUID = UUID.fromString("6b1f6d3e-46a2-4c8f-9e2a-7b0d5c1f4a83")
}

@Serializable
internal data class NearbyHelloFrame(
    val nexId: String,
    val signalDeviceId: Int,
    val deviceName: String? = null,
    val keyBundle: KeyBundle? = null,
)

/**
 * The part of a routed message that is Signal-encrypted for the immediate
 * neighbour. [innerCiphertext] is opaque to every relay.
 */
@Serializable
internal data class NearbyRoutedPayload(
    val packetId: String,
    val type: String = NearbyProtocol.TYPE_MESSAGE,
    val targetNexId: String,
    val targetSignalDeviceId: Int,
    val originNexId: String,
    val originSignalDeviceId: Int,
    val clientId: String,
    val groupId: String? = null,
    val innerCipherType: Int = 0,
    val innerCiphertext: String? = null,
    val createdAt: Long,
    val expiresAt: Long,
    val hopCount: Int = 0,
    val maxHops: Int = NearbyPolicy.DEFAULT_MAX_HOPS,
    /** ACK frames carry the time the final recipient accepted the envelope. */
    val ackAt: Long? = null,
)

/**
 * On-the-wire frame. For routed frames the routing payload above is encrypted
 * with [NearbyRoutedPayload]'s JSON representation as plaintext and transported
 * in [routingCiphertext]; no routing header field is sent in the clear.
 */
@Serializable
internal data class NearbyWireFrame(
    val type: String,
    val v: Int = NearbyProtocol.VERSION,
    val hello: NearbyHelloFrame? = null,
    val routingCiphertext: String? = null,
    val routingCipherType: Int? = null,
) {
    companion object {
        fun hello(value: NearbyHelloFrame): NearbyWireFrame = NearbyWireFrame(
            type = NearbyProtocol.TYPE_HELLO,
            hello = value,
        )

        fun routed(ciphertext: String, cipherType: Int): NearbyWireFrame = NearbyWireFrame(
            type = NearbyProtocol.TYPE_MESSAGE,
            routingCiphertext = ciphertext,
            routingCipherType = cipherType,
        )

        fun ack(ciphertext: String, cipherType: Int): NearbyWireFrame = NearbyWireFrame(
            type = NearbyProtocol.TYPE_ACK,
            routingCiphertext = ciphertext,
            routingCipherType = cipherType,
        )
    }
}

internal object NearbyFrameCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Returns a single newline-terminated frame, or null when it is too large. */
    fun encode(frame: NearbyWireFrame): String? {
        val line = json.encodeToString(frame)
        val bytes = line.toByteArray(Charsets.UTF_8).size + 1
        if (!NearbyPolicy.frameSizeAllowed(bytes)) return null
        return "$line\n"
    }

    fun decode(line: String): NearbyWireFrame? {
        val trimmed = line.trimEnd('\r', '\n')
        if (!NearbyPolicy.frameSizeAllowed(trimmed.toByteArray(Charsets.UTF_8).size)) return null
        return runCatching { json.decodeFromString<NearbyWireFrame>(trimmed) }
            .getOrNull()
            ?.takeIf { it.v == NearbyProtocol.VERSION }
            ?.takeIf { frame ->
                when (frame.type) {
                    NearbyProtocol.TYPE_HELLO -> frame.hello != null
                    NearbyProtocol.TYPE_MESSAGE, NearbyProtocol.TYPE_ACK ->
                        !frame.routingCiphertext.isNullOrBlank() && frame.routingCipherType != null
                    else -> false
                }
            }
    }

    /** Serializes the routing header before it is passed to Signal encryption. */
    fun encodeRouting(payload: NearbyRoutedPayload): String? {
        val value = json.encodeToString(payload)
        return if (NearbyPolicy.routingPayloadAllowed(value.toByteArray(Charsets.UTF_8).size)) value else null
    }

    fun decodeRouting(value: String): NearbyRoutedPayload? =
        runCatching { json.decodeFromString<NearbyRoutedPayload>(value) }
            .getOrNull()
            ?.takeIf { it.packetId.isNotBlank() }
            ?.takeIf { it.targetNexId.isNotBlank() && it.originNexId.isNotBlank() }
            ?.takeIf { it.hopCount >= 0 && it.maxHops > 0 }
            ?.takeIf { payload ->
                when (payload.type) {
                    NearbyProtocol.TYPE_MESSAGE -> !payload.innerCiphertext.isNullOrBlank()
                    NearbyProtocol.TYPE_ACK -> payload.innerCiphertext == null
                    else -> false
                }
            }
}
