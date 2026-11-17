package com.nexchat.app.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorBody(val error: ApiError)

@Serializable
data class ApiError(val code: String, val message: String)

@Serializable
data class AuthUser(
    val id: String,
    @SerialName("nexId") val nexId: String,
    val username: String,
    val displayName: String,
    val avatar: String? = null,
    val createdAt: String = "",
)

@Serializable
data class AuthDevice(
    val id: String,
    val name: String,
    val platform: String = "android",
    val signalDeviceId: Int,
)

@Serializable
data class AuthResponse(
    val token: String,
    val refreshToken: String,
    val user: AuthUser,
    val device: AuthDevice,
)

@Serializable
data class RefreshResponse(val token: String, val refreshToken: String)

@Serializable
data class MeResponse(val user: AuthUser, val device: AuthDevice)

@Serializable
data class DeviceKeysRequest(
    val deviceId: String,
    val name: String,
    val platform: String = "android",
    val registrationId: Int,
    val signalDeviceId: Int,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySignature: String,
    val kyberPreKeyId: Int?,
    val kyberPreKeyPublic: String?,
    val kyberPreKeySignature: String?,
    val oneTimePreKeys: List<PreKeyDto>,
)

@Serializable
data class PreKeyDto(val id: Int, val publicKey: String)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val displayName: String,
    val device: DeviceKeysRequest,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val device: DeviceKeysRequest,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class CheckUsernameRequest(val username: String)

@Serializable
data class CheckUsernameResponse(val available: Boolean)

@Serializable
data class UsersResponse(val users: List<AuthUser>)

@Serializable
data class PublicUserResponse(val user: AuthUser)

@Serializable
data class PresenceResponse(val online: Boolean, val hidden: Boolean = false)

@Serializable
data class PrivacyRequest(val sharePresence: Boolean)

@Serializable
data class PrivacyResponse(val sharePresence: Boolean)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val avatar: String? = null,
)

@Serializable
data class ProfileResponse(val user: AuthUser)

@Serializable
data class DevicesResponse(val devices: List<DeviceSummary>)

@Serializable
data class DeviceSummary(
    val id: String,
    val name: String,
    val platform: String,
    val createdAt: String? = null,
    val current: Boolean = false,
)

@Serializable
data class KeyBundleResponse(
    val user: AuthUser,
    val devices: List<KeyBundle>,
    val unavailableDeviceIds: List<String> = emptyList(),
)

@Serializable
data class KeyBundle(
    val deviceId: String,
    val signalDeviceId: Int,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySignature: String,
    // X3DH remains valid without a one-time prekey. The server omits these
    // fields when its one-time pool is empty instead of failing the handshake.
    val oneTimePreKeyId: Int? = null,
    val oneTimePreKeyPublic: String? = null,
    val kyberPreKeyId: Int,
    val kyberPreKeyPublic: String,
    val kyberPreKeySignature: String,
)

@Serializable
data class KeyDeviceDto(
    val deviceId: String,
    val signalDeviceId: Int,
    val identityKey: String,
)

@Serializable
data class KeyDevicesResponse(
    val user: AuthUser,
    val devices: List<KeyDeviceDto>,
)

@Serializable
data class RotateKeysRequest(
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySignature: String,
    val kyberPreKeyId: Int?,
    val kyberPreKeyPublic: String?,
    val kyberPreKeySignature: String?,
    val oneTimePreKeys: List<PreKeyDto>,
)

@Serializable
data class PreKeyCountResponse(val oneTimePreKeys: Int, val kyberPreKeys: Int)

@Serializable
data class EnvelopeDto(
    val id: String,
    val senderNexId: String,
    val senderDeviceId: String,
    val senderSignalDeviceId: Int = 1,
    val recipientDeviceId: String,
    val recipientSignalDeviceId: Int = 1,
    val groupId: String? = null,
    val clientId: String,
    val cipherType: Int,
    val ciphertext: String,
    val createdAt: String,
    val expiresAt: String,
    val ephemeral: Boolean = false,
)

@Serializable
data class PendingResponse(val messages: List<EnvelopeDto>)

@Serializable
data class OutgoingEnvelope(
    val recipientNexId: String,
    val recipientSignalDeviceId: Int,
    val clientId: String,
    val ciphertext: String,
    val cipherType: Int,
    val ephemeral: Boolean = false,
    val expiresInSeconds: Int = 604800,
    val groupId: String? = null,
)

@Serializable
data class SendMessagesRequest(val messages: List<OutgoingEnvelope>)

@Serializable
data class AcceptedEnvelope(
    val clientId: String,
    val recipientSignalDeviceId: Int,
    val recipientDeviceId: String = "",
    val envelopeId: String,
    val createdAt: String,
)

@Serializable
data class SkippedEnvelope(val clientId: String, val reason: String)

@Serializable
data class SendMessagesResponse(
    val accepted: List<AcceptedEnvelope> = emptyList(),
    val skipped: List<SkippedEnvelope> = emptyList(),
)

@Serializable
data class AckRequest(val envelopeIds: List<String>)

@Serializable
data class AckResponse(val acknowledged: Int)

@Serializable
data class IceServerDto(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class ServerConfigResponse(
    val iceServers: List<IceServerDto> = emptyList(),
    val limits: LimitDto? = null,
    val features: FeatureDto? = null,
)

@Serializable
data class LimitDto(
    val maxEnvelopeBytes: Int = 131072,
    val envelopeTtlDays: Int = 7,
    val maxGroupMembers: Int = 100,
)

@Serializable
data class FeatureDto(
    val e2ee: String = "libsignal",
    val groupEncryption: String = "pairwise-libsignal",
    val nearbyRelay: String = "unavailable",
    val audioCalls: String = "unavailable",
    val pushNotifications: Boolean = false,
)

@Serializable
data class GroupMemberDto(
    val userId: String,
    val nexId: String,
    val username: String,
    val displayName: String,
    val avatar: String? = null,
    val role: String,
    val joinedAt: String = "",
)

@Serializable
data class GroupDto(
    val id: String,
    val createdBy: String,
    val createdAt: String = "",
    val members: List<GroupMemberDto> = emptyList(),
)

@Serializable
data class GroupResponse(val group: GroupDto)

@Serializable
data class GroupsResponse(val groups: List<GroupDto>)

@Serializable
data class CreateGroupRequest(val memberNexIds: List<String>)

@Serializable
data class AddMembersRequest(val memberNexIds: List<String>)

@Serializable
data class CreateGroupResponse(val group: GroupDto)
