package com.nchat.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the Rust backend (`server/`), which is a port of the reference worker.
 *
 * The JSON is snake_case with a couple of deliberate legacy camelCase keys
 * (`fileName`, `fileSize`, `lastMessage`, `unreadCount`, `receiverIds`) — those are copied
 * from the reference front end and must stay character-for-character, so they appear here
 * exactly as the server sends them.
 *
 * Every field is nullable-or-defaulted on purpose: the client must survive a server that
 * grows a field or omits an empty one (the backend omits nulls entirely).
 */

// ---------------------------------------------------------------- auth & users

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: UserDto? = null,
)

@Serializable
data class UserDto(
    val id: String = "",
    val email: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("username_last_changed") val usernameLastChanged: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class UserEnvelope(val user: UserDto? = null)

@Serializable
data class ProfileEnvelope(val profile: UserDto? = null)

@Serializable
data class ProfilesEnvelope(val profiles: List<UserDto> = emptyList())

@Serializable
data class BlockedEnvelope(val blocked: List<UserDto> = emptyList())

@Serializable
data class AvailabilityEnvelope(val available: Boolean = false)

@Serializable
data class CheckEmailEnvelope(val valid: Boolean = false, val exists: Boolean = false)

// ---------------------------------------------------------------- friends

@Serializable
data class FriendDto(val id: String = "", val profile: UserDto? = null)

@Serializable
data class FriendsEnvelope(val friends: List<FriendDto> = emptyList())

@Serializable
data class FriendRequestDto(
    val id: String = "",
    @SerialName("sender_id") val senderId: String = "",
    @SerialName("receiver_id") val receiverId: String = "",
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("sender_profile") val senderProfile: UserDto? = null,
)

@Serializable
data class RequestsEnvelope(val requests: List<FriendRequestDto> = emptyList())

@Serializable
data class SentEnvelope(
    @SerialName("receiverIds") val receiverIds: List<String> = emptyList(),
    val requests: List<FriendRequestDto> = emptyList(),
)

// ---------------------------------------------------------------- messages

@Serializable
data class MessageDto(
    val id: String = "",
    @SerialName("sender_id") val senderId: String = "",
    @SerialName("receiver_id") val receiverId: String? = null,
    @SerialName("group_id") val groupId: String? = null,
    val content: String = "",
    val type: String = "text",
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("thumbnail_url") @Serializable(with = LenientString::class) val thumbnailUrl: String? = null,
    @SerialName("fileName") val fileName: String? = null,
    @SerialName("fileSize") val fileSize: String? = null,
    @Serializable(with = LenientString::class) val duration: String? = null,
    @SerialName("client_message_id") val clientMessageId: String? = null,
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("is_edited") val isEdited: Boolean = false,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("deleted_for") val deletedFor: List<String> = emptyList(),
    val reactions: Map<String, List<String>> = emptyMap(),
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class MessagesEnvelope(val messages: List<MessageDto> = emptyList())

@Serializable
data class MessageEnvelope(val message: MessageDto? = null)

@Serializable
data class ChatDto(
    @SerialName("user_id") val userId: String = "",
    val status: String = "accepted",
    val profile: UserDto? = null,
    @SerialName("lastMessage") val lastMessage: MessageDto? = null,
    @SerialName("unreadCount") val unreadCount: Int = 0,
)

@Serializable
data class ChatsEnvelope(val chats: List<ChatDto> = emptyList())

@Serializable
data class ReactionsEnvelope(val reactions: Map<String, List<String>> = emptyMap())

// ---------------------------------------------------------------- groups

@Serializable
data class GroupDto(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val members: List<GroupMemberDto> = emptyList(),
    @SerialName("lastMessage") val lastMessage: MessageDto? = null,
    @SerialName("unreadCount") val unreadCount: Int = 0,
)

@Serializable
data class GroupMemberDto(
    @SerialName("group_id") val groupId: String = "",
    @SerialName("user_id") val userId: String = "",
    val role: String = "member",
    @SerialName("is_muted") val isMuted: Boolean = false,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("last_read_at") val lastReadAt: String? = null,
    val profile: UserDto? = null,
)

@Serializable
data class GroupsEnvelope(val groups: List<GroupDto> = emptyList())

@Serializable
data class GroupEnvelope(val group: GroupDto? = null)

@Serializable
data class MembersEnvelope(val members: List<GroupMemberDto> = emptyList())

// ---------------------------------------------------------------- statuses

@Serializable
data class StatusDto(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("content_type") val contentType: String = "text",
    @SerialName("content_url") val contentUrl: String? = null,
    @SerialName("text_content") val textContent: String? = null,
    @SerialName("background_color") val backgroundColor: String? = null,
    @SerialName("font_style") val fontStyle: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val duration: Long? = null,
    val caption: String? = null,
    val privacy: String = "contacts",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("view_count") val viewCount: Int = 0,
    @SerialName("viewed_by_me") val viewedByMe: Boolean = false,
    // `GET /status/feed` flattens the author onto the row instead of nesting a profile.
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class StatusesEnvelope(val statuses: List<StatusDto> = emptyList())

@Serializable
data class StatusEnvelope(val status: StatusDto? = null)

@Serializable
data class StatusViewDto(
    val id: String = "",
    @SerialName("status_id") val statusId: String = "",
    @SerialName("viewer_id") val viewerId: String = "",
    @SerialName("viewed_at") val viewedAt: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class ViewsEnvelope(val views: List<StatusViewDto> = emptyList())

/** `GET /status/muted` answers with bare user ids, not profiles. */
@Serializable
data class MutedEnvelope(val muted: List<String> = emptyList())

// ---------------------------------------------------------------- calls

@Serializable
data class CallDto(
    val id: String = "",
    @SerialName("caller_id") val callerId: String = "",
    @SerialName("receiver_id") val receiverId: String = "",
    @SerialName("call_type") val callType: String = "audio",
    val status: String = "ringing",
    val duration: Long? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("ended_at") val endedAt: String? = null,
    /**
     * The SDP that belongs to this call, as either an object (`call:incoming` carries what the
     * caller posted) or a JSON string (`GET /calls/history` reads it back out of the database).
     * Both shapes land here so the media layer never has to care which one it got.
     */
    @SerialName("offer_sdp") val offerSdp: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("answer_sdp") val answerSdp: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("caller_profile") val callerProfile: UserDto? = null,
    @SerialName("receiver_profile") val receiverProfile: UserDto? = null,
    @SerialName("other_profile") val otherProfile: UserDto? = null,
)

@Serializable
data class CallEnvelope(val call: CallDto? = null)

@Serializable
data class HistoryEnvelope(val history: List<CallDto> = emptyList())

@Serializable
data class IceServersEnvelope(
    @SerialName("iceServers") val iceServers: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class GroupCallInfoDto(
    val id: String = "",
    @SerialName("group_id") val groupId: String = "",
    @SerialName("caller_id") val callerId: String? = null,
    @SerialName("call_type") val callType: String = "audio",
    val status: String = "active",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("group_avatar_url") val groupAvatarUrl: String? = null,
)

@Serializable
data class GroupCallParticipantDto(
    @SerialName("user_id") val userId: String = "",
    val state: String = "joined",
    val profile: UserDto? = null,
)

@Serializable
data class GroupCallEnvelope(
    val call: GroupCallInfoDto? = null,
    val participants: List<GroupCallParticipantDto> = emptyList(),
)

// ---------------------------------------------------------------- media & privacy

@Serializable
data class MediaDto(
    val id: String = "",
    val url: String = "",
    val key: String = "",
    @SerialName("media_type") val mediaType: String = "document",
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
)

/** The server sends `read_receipts` / `online_status_enabled` as 0/1, like D1 did. */
@Serializable
data class PrivacySettingsDto(
    @SerialName("user_id") val userId: String = "",
    @SerialName("last_seen_visibility") val lastSeenVisibility: String = "everyone",
    @SerialName("profile_photo_visibility") val profilePhotoVisibility: String = "everyone",
    @SerialName("about_visibility") val aboutVisibility: String = "everyone",
    @SerialName("status_visibility") val statusVisibility: String = "contacts",
    @SerialName("read_receipts") val readReceipts: Int = 1,
    @SerialName("online_status_enabled") val onlineStatusEnabled: Int = 1,
) {
    val showsReadReceipts: Boolean get() = readReceipts != 0
    val showsOnlineStatus: Boolean get() = onlineStatusEnabled != 0
}

@Serializable
data class SettingsEnvelope(val settings: PrivacySettingsDto? = null)

@Serializable
data class SuccessEnvelope(val success: Boolean = false)
