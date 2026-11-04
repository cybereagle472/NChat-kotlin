package com.nchat.app.data.model

/** Message payload types, mirroring the icons used in the reference previews. */
enum class MessageKind { Text, Photo, Video, Voice, Document, Location }

/** Delivery state: single grey tick when sent, double sky tick when read. */
enum class DeliveryState { Sending, Sent, Read }

data class Reaction(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean = false
)

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
    val systemEvent: Boolean = false
)

/** A row in the Chats tab. */
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

/** Friend suggestion / friend row for the Discover tab. */
data class Friend(
    val id: String,
    val name: String,
    val handle: String,
    val isOnline: Boolean,
    val mutualFriends: Int,
    val requestIncoming: Boolean = false
)

/** Call log row for the History tab. */
data class CallRecord(
    val id: String,
    val name: String,
    val isVideo: Boolean,
    val isOutgoing: Boolean,
    val missed: Boolean,
    val timestamp: Long,
    val durationLabel: String? = null,
    val isGroup: Boolean = false
)

data class UserProfile(
    val fullName: String,
    val handle: String,
    val about: String,
    val phoneNumber: String
)
