package com.nchat.app.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Message payload types, mirroring the icons used in the reference previews. */
@Serializable
enum class MessageKind { Text, Photo, Video, Voice, Document, Location }

/** Delivery state: single grey tick when sent, double sky tick when read. */
@Serializable
enum class DeliveryState { Sending, Sent, Read }

@Immutable
@Serializable
data class Reaction(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean = false
)

@Immutable
data class Message(
    val id: String,
    val chatId: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val timestamp: Long,
    val isMine: Boolean,
    val kind: MessageKind = MessageKind.Text,
    val state: DeliveryState = DeliveryState.Read,
    val durationLabel: String? = null,
    val fileName: String? = null,
    /** `media_url` from the backend — the uploaded file this bubble points at. */
    val mediaUrl: String? = null,
    val thumbnailUrl: String? = null,
    val reactions: List<Reaction> = emptyList(),
    val replyAuthor: String? = null,
    val replyText: String? = null,
    val isEdited: Boolean = false,
    val forwarded: Boolean = false,
    val systemEvent: Boolean = false,
    /**
     * The id this client generated for the send (`client_message_id` on the wire).
     *
     * It is what reconciles the optimistic bubble with the row the server stored: the HTTP
     * answer, the realtime echo of our own message and a later history fetch all carry it, so
     * the same message can never be shown twice or dropped by a refresh.
     */
    val clientMessageId: String? = null,
    /** The send failed for good (after retries); tapping the bubble's warning resends it. */
    val failed: Boolean = false,
    /** Deleted for everyone: the bubble keeps its slot with a tombstone label. */
    val deleted: Boolean = false
)

/** Where a call on screen currently is. */
enum class CallState {
    /** The other side's phone is ringing (or ours is). */
    Ringing,

    /** Answered and negotiated, waiting for ICE/DTLS to finish — "Connecting…". */
    Connecting,

    /** Media is actually flowing: this is the only state where audio exists. */
    Live,

    /** Over, for one of the reasons in [ActiveCall.note]. */
    Ended
}

/**
 * One call, as the call screen shows it.
 *
 * Every field is decided by the server or by the media engine — the id comes from
 * `POST /calls`, [state] moves on `call:answered` / `call:ended` / ICE, and [startedAt] is the
 * moment media connected, so the timer counts from the truth rather than from a local
 * animation. [localVideo] / [remoteVideo] are the engine's, not the intent: a video call whose
 * camera failed says so instead of showing a black rectangle.
 */
@Immutable
data class ActiveCall(
    val id: String,
    val peerId: String,
    val peerName: String,
    val peerAvatarUrl: String? = null,
    val isVideo: Boolean = false,
    /** True when the other person called us — the screen offers Answer/Decline. */
    val incoming: Boolean = false,
    val state: CallState = CallState.Ringing,
    /** `System.currentTimeMillis()` of the media connect; 0 until [CallState.Live]. */
    val startedAt: Long = 0L,
    /** How it finished ("Declined", "Missed", "Couldn't connect"), shown before closing. */
    val note: String? = null,
    val micMuted: Boolean = false,
    val speakerOn: Boolean = false,
    /** The engine is publishing our camera. */
    val localVideo: Boolean = false,
    /** The far end's camera is arriving. */
    val remoteVideo: Boolean = false,
    val frontCamera: Boolean = true,
    /** Why media is missing, when it is — the call still runs as signalling-only. */
    val mediaError: String? = null
) {
    val answered: Boolean get() = state == CallState.Connecting || state == CallState.Live
}

/** A row in the Chats tab. *//** A row in the Chats tab. */
@Immutable
@Serializable
data class Chat(
    val id: String,
    val title: String,
    val avatarEmoji: String? = null,
    /** Server-hosted profile photo, when the account has one. */
    val avatarUrl: String? = null,
    val isOnline: Boolean = false,
    val isGroup: Boolean = false,
    val lastMessage: String = "",
    val lastMessageAt: Long = 0L,
    val lastMessageKind: MessageKind = MessageKind.Text,
    val lastMessageMine: Boolean = false,
    val lastMessageState: DeliveryState? = null,
    val unreadCount: Int = 0,
    val isTyping: Boolean = false,
    val isRecording: Boolean = false,
    /**
     * Who is typing, for group chats.
     *
     * The worker sends it (`groups.ts:575` → `{ type:'group-typing', group_id, sender_id,
     * is_typing }`) but only the boolean was kept, so the UI had to *guess* the name — it
     * credited whoever spoke last. Null for 1:1 chats, where the other side is implicit.
     */
    val typingUserId: String? = null,
    val pinned: Boolean = false,
    val muted: Boolean = false
)

/** Status (24h story) entry. */
@Immutable
data class StatusUpdate(
    val id: String,
    val authorName: String,
    val avatarEmoji: String? = null,
    /** Server-hosted profile photo of the author, when they have one. */
    val avatarUrl: String? = null,
    /** Author's user id — needed to mute them (`POST /status/mute`). */
    val authorId: String = "",
    val caption: String,
    val timestamp: Long,
    val viewed: Boolean,
    val isMine: Boolean = false,
    /** `text`, `image` or `video`, straight from the server row. */
    val contentType: String = "text",
    /** The picture itself, when this is a photo status (`content_url`). */
    val contentUrl: String? = null,
    /** How many other people have seen it — only ever non-zero for my own. */
    val viewCount: Int = 0
)

/** One person who watched a status (`GET /status/:id/views`). */
data class StatusViewer(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val viewedAt: Long = 0L
)

/**
 * Somebody this account has blocked.
 *
 * Blocking is deliberately **non-destructive** here: the friendship row and both sides of the
 * message history stay exactly where they are, and unblocking puts everything back the way it
 * was — the WhatsApp/Telegram/Messenger model, where a block pauses a relationship instead of
 * deleting it. (The reference worker's `POST /friends/block` also runs
 * `DELETE FROM friendships`, which is why this app keeps the block on the device and never
 * calls that route.)
 */
@Immutable
@Serializable
data class BlockedContact(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    /** When the block was applied; used only to keep the list stable in the UI. */
    val blockedAt: Long = 0L
)

/**
 * A message that still has to reach the server.
 *
 * The reference keeps exactly this queue in IndexedDB (`queuePendingMessage` /
 * `processPendingQueue`): a send is written down *before* it goes out, retried a few times with
 * backoff, and flushed again when the socket reconnects. Without it a send that lands in a dead
 * spot — the app is backgrounded, the token is being refreshed, the network blips — is simply
 * lost, which is what "my messages aren't going" means in practice.
 *
 * `client_message_id` makes every retry idempotent: the worker returns the existing row instead
 * of creating a second one, so resending the same entry is always safe.
 */
@Immutable
@Serializable
data class OutboxEntry(
    /** The id this device generated; becomes `client_message_id`. */
    val clientId: String,
    /** DM peer id, or group id when [isGroup]. */
    val chatId: String,
    val isGroup: Boolean = false,
    /** The account this message belongs to; never delivered from another sign-in. */
    val ownerId: String = "",
    val text: String,
    val type: String = "text",
    val mediaUrl: String? = null,
    val thumbnailUrl: String? = null,
    val duration: String? = null,
    val fileName: String? = null,
    val fileSize: String? = null,
    val replyTo: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** How many delivery attempts have failed so far. */
    val attempts: Int = 0
)

/** One row of a group's member list, straight from `GET /groups`'s `members[]`. */
@Immutable
data class GroupMember(
    val userId: String,
    val name: String,
    val avatarUrl: String? = null,
    /** `admin` or `member`. */
    val role: String = "member",
    val isMuted: Boolean = false
) {
    val isAdmin: Boolean get() = role == "admin"
}

/** Group entry for the Groups tab. */
@Immutable
data class Group(
    val id: String,
    val name: String,
    val description: String,
    val memberCount: Int,
    val unreadCount: Int = 0,
    val avatarEmoji: String? = null,
    val lastActivity: String,
    val lastActivityAt: Long,
    /** The real member list the server sends — not a guess derived from the friend list. */
    val members: List<GroupMember> = emptyList(),
    /** Who created the group; only they may delete it. */
    val createdBy: String? = null,
    /** This account's role in the group, so the UI knows whether to offer admin actions. */
    val myRole: String? = null
) {
    val iAmAdmin: Boolean get() = myRole == "admin"
}

/** Where a person sits in the friend graph, driving the Discover action buttons. */
@Serializable
enum class FriendRelation { Friend, Incoming, Outgoing, None }

/**
 * One row of the friend graph, as the Friends / Requests / Find tabs render it.
 *
 * Serializable because the graph is now cached on disk (`FriendGraphCache`): the Friends tab
 * has to survive a cold start with no network the same way the chat list does.
 */
@Immutable
@Serializable
data class Friend(
    val id: String,
    val name: String,
    val handle: String,
    val isOnline: Boolean,
    /** Server-hosted profile photo, when the account has one. */
    val avatarUrl: String? = null,
    val mutualFriends: Int = 0,
    val relation: FriendRelation = FriendRelation.None,
    val lastSeenAt: Long? = null,
    val requestedAt: Long? = null
) {
    /** Convenience used by the groups picker, which only cares about pending invites. */
    val requestIncoming: Boolean get() = relation == FriendRelation.Incoming
}

/** Call log row for the History tab. */
@Immutable
data class CallRecord(
    val id: String,
    val name: String,
    val personId: String = name.lowercase().replace(" ", "-"),
    val isVideo: Boolean,
    val isOutgoing: Boolean,
    val missed: Boolean,
    val timestamp: Long,
    val durationLabel: String? = null,
    val isGroup: Boolean = false
)

@Immutable
data class UserProfile(
    val fullName: String,
    val handle: String,
    val about: String,
    val phoneNumber: String,
    /**
     * The account's own photo, exactly as `GET /users/me` returns it.
     *
     * The picked-in-this-session bitmap ([com.nchat.app.data.repository.ProfileRepository.avatar])
     * only exists until the process dies, so without this the app fell back to initials on every
     * launch even though the server had the picture all along.
     */
    val avatarUrl: String? = null
)
