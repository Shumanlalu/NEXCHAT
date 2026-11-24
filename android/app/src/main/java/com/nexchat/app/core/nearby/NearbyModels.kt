package com.nexchat.app.core.nearby

import com.nexchat.app.core.network.KeyBundle

/** Public view of a discovered or connected nearby device. */
data class NearbyPeer(
    val nexId: String,
    val signalDeviceId: Int,
    val deviceName: String?,
    val address: String?,
    val connected: Boolean,
    val keyBundle: KeyBundle? = null,
)

/** An envelope that was durably stored for nearby relaying. */
data class NearbyAccepted(
    val clientId: String,
    val recipientNexId: String,
    val recipientSignalDeviceId: Int,
)

/** Observable delivery outcomes for envelopes handed to [NearbyRelayManager.enqueue]. */
sealed interface NearbyDeliveryEvent {
    data class Delivered(val clientId: String, val at: Long) : NearbyDeliveryEvent
    data class Failed(val clientId: String, val message: String) : NearbyDeliveryEvent
    data class Relayed(val packetId: String, val hops: Int) : NearbyDeliveryEvent
}
