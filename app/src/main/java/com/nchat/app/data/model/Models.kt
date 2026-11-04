package com.nchat.app.data.model

import androidx.compose.runtime.Immutable

/** Message payload types, mirroring the icons used in the reference previews. */
enum class MessageKind { Text, Photo, Video, Voice, Document, Location }

/** Delivery state: single grey tick when sent, double sky tick when read. */
enum class DeliveryState { Sending, Sent, Read }

@Immutable
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
    val reactions: List<Reaction> = emptyList(),
    val replyAuthor: String? = null,
    val replyText: String? = null,
    val isEdited: Boolean = false,
    val forwarded: Boolean = false,
    val systemEvent: Boolean = false,
    /** Deleted for everyone: the bubble keeps its slot with a tombstone label. */
    val deleted: Boolean = false
)

/** A row in the Chats tab. */
@Immutable
data class Chat(
    val id: String,
    val title: String,
    val avatarEmoji: String? = null,
    val isOnline: Boolean = false,
    val isGroup: Boolean = false,
    val lastMessage: String = "",
    val lastMessageAt: Long = 0L,
    val lastMessageKind: MessageKind = MessageKind.Text,
    val lastMessageMine: Boolean = false,
    val lastMessageState: DeliveryState? = null,
    val unreadCount: Int = 0,
    val isTyping: Boolean = false,
    val isRecording: Boolean = false
)

/** Status (24h story) entry. */
@Immutable
data class StatusUpdate(
    val id: String,
    val authorName: String,
    val avatarEmoji: String? = null,
    val caption: String,
    val timestamp: Long,
    val viewed: Boolean,
    val isMine: Boolean = false
)

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
    val lastActivityAt: Long
)

/** Where a person sits in the friend graph, driving the Discover action buttons. */
enum class FriendRelation { Friend, Incoming, Outgoing, None }

@Immutable
data class Friend(
    val id: String,
    val name: String,
    val handle: String,
    val isOnline: Boolean,
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
    val phoneNumber: String
)
