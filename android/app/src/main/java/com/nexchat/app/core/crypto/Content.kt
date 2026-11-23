package com.nexchat.app.core.crypto

import kotlinx.serialization.Serializable

object ContentKind {
    const val TEXT = "TEXT"
    const val EDIT = "EDIT"
    const val DELETE = "DELETE"
    const val REACTION = "REACTION"
    const val READ = "READ"
    const val TYPING = "TYPING"
    const val GROUP_META = "GROUP_META"
    const val CALL_OFFER = "CALL_OFFER"
    const val CALL_ANSWER = "CALL_ANSWER"
    const val CALL_ICE = "CALL_ICE"
    const val CALL_HANGUP = "CALL_HANGUP"
    const val CALL_REJECT = "CALL_REJECT"
    const val ATTACHMENT_MANIFEST = "ATTACHMENT_MANIFEST"
    const val ATTACHMENT_CHUNK = "ATTACHMENT_CHUNK"
}

@Serializable
data class SecureContent(
    val v: Int = 1,
    val kind: String,
    val clientId: String? = null,
    val senderNexId: String? = null,
    val senderUsername: String? = null,
    val senderDisplayName: String? = null,
    val senderAvatar: String? = null,
    val text: String? = null,
    val replyToId: String? = null,
    val replyPreview: String? = null,
    val targetClientId: String? = null,
    val emoji: String? = null,
    val groupId: String? = null,
    val groupTitle: String? = null,
    val groupMembers: List<GroupMemberInfo> = emptyList(),
    val readClientIds: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val callId: String? = null,
    val sdp: String? = null,
    val sdpType: String? = null,
    // Call signaling is end-to-end encrypted. `video` is included in offers and
    // answers so both peers know whether to negotiate camera media.
    val video: Boolean? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val callEndReason: String? = null,
    // Encrypted attachments travel as one manifest plus a set of small,
    // independently encrypted, durable envelopes. Every field below is inside
    // the Signal-encrypted SecureContent, so the relay only sees ciphertext.
    val attachmentId: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val sha256: String? = null,
    val chunkCount: Int? = null,
    val chunkIndex: Int? = null,
    val chunkData: String? = null,
)

@Serializable
data class GroupMemberInfo(
    val nexId: String,
    val username: String,
    val displayName: String,
    val role: String,
)

@Serializable
data class Reactions(val entries: Map<String, List<String>> = emptyMap())
