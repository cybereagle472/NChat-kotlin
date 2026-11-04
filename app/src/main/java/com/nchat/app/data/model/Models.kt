package com.nchat.app.data.model

/** Delivery state of a message sent by the signed in user. */
enum class MessageStatus { Sending, Sent, Delivered, Read }

/** Attachment carried by a message. [None] means a plain text bubble. */
enum class AttachmentKind { None, Photo, Video, Voice, Document, Location, Contact, Sticker }

data class Reaction(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean = false
)

data class Message(
    val id: String,
    val chatId: String,
    val authorName: String,
    val text: String,
    val timestamp: Long,
    val isMine: Boolean,
    val status: MessageStatus = MessageStatus.Read,
    val attachment: AttachmentKind = AttachmentKind.None,
    val attachmentLabel: String? = null,
    val durationLabel: String? = null,
    val reactions: List<Reaction> = emptyList(),
    val replyAuthor: String? = null,
    val replyText: String? = null,
    val isStarred: Boolean = false,
    val systemEvent: Boolean = false
)

data class Chat(
    val id: String,
    val title: String,
    val lastMessagePreview: String,
    val lastMessageAt: Long,
    val unreadCount: Int = 0,
    val isGroup: Boolean = false,
    val isVerified: Boolean = false,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isOnline: Boolean = false,
    val isTyping: Boolean = false,
    val hasStory: Boolean = false,
    val storySeen: Boolean = true,
    val lastMessageFromMe: Boolean = false,
    val lastMessageStatus: MessageStatus? = null,
    val memberCount: Int? = null,
    val draft: String? = null,
    val avatarEmoji: String? = null,
    val isArchived: Boolean = false
)

enum class CallType { Voice, Video }

enum class CallOutcome { Answered, Missed, Declined }

data class CallEntry(
    val id: String,
    val contactName: String,
    val type: CallType,
    val outcome: CallOutcome,
    val isOutgoing: Boolean,
    val timestamp: Long,
    val durationLabel: String? = null,
    val isGroup: Boolean = false,
    val participantCount: Int? = null
)

data class Story(
    val id: String,
    val authorName: String,
    val caption: String,
    val timestamp: Long,
    val viewed: Boolean,
    val avatarEmoji: String? = null,
    val isMine: Boolean = false
)

data class Channel(
    val id: String,
    val name: String,
    val lastUpdate: String,
    val timestamp: Long,
    val followerLabel: String,
    val verified: Boolean,
    val unreadCount: Int = 0,
    val isMuted: Boolean = false
)

data class Community(
    val id: String,
    val name: String,
    val description: String,
    val memberLabel: String,
    val unreadCount: Int,
    val groupCount: Int,
    val announcement: String,
    val avatarEmoji: String? = null
)

data class UserProfile(
    val fullName: String,
    val handle: String,
    val about: String,
    val phoneNumber: String,
    val email: String
)
