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
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val callEndReason: String? = null,
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
