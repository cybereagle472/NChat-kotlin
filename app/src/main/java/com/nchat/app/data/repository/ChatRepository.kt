package com.nchat.app.data.repository

import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Group
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import kotlinx.coroutines.flow.StateFlow

/**
 * What every screen uses to reach chats, groups and messages. The only implementation is
 * [RemoteChatRepository] (the app has no demo data); the UI never touches HTTP itself.
 */
interface ChatRepository {
    val chats: StateFlow<List<Chat>>
    val groups: StateFlow<List<Group>>

    /**
     * Why the last refresh failed, or null when it worked.
     *
     * A failed request and an empty account both produce an empty list; this is what lets the
     * chat list say "couldn't load chats" instead of quietly showing nothing.
     */
    val syncError: StateFlow<String?>

    /** Reloads chats + groups now (used by the retry button). */
    fun refresh()
    fun conversation(chatId: String, chatTitle: String): StateFlow<List<Message>>
    fun sendMessage(
        chatId: String,
        text: String,
        replyAuthor: String? = null,
        replyText: String? = null
    )

    /**
     * Uploads [bytes] to `POST /media/upload` and sends the resulting message — the real
     * attachment path the reference uses for photos, clips and documents.
     */
    fun sendMedia(
        chatId: String,
        kind: MessageKind,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        caption: String? = null,
        thumbnail: ByteArray? = null
    )
    /**
     * Sends a recorded voice message: the clip is uploaded first and the message carries its
     * `media_url`, which is what makes the other side actually hear something.
     */
    fun sendVoice(
        chatId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        durationLabel: String
    )
    /**
     * Resend a bubble whose send failed for good (the warning icon on the message).
     *
     * Voice/photo/document messages that already uploaded reuse their `media_url`, so pressing
     * the warning is the same request again — no second upload, no duplicate file.
     */
    fun retrySend(chatId: String, messageId: String)

    /** Rewrites the body of my own text message and flags it as edited. */
    fun editMessage(chatId: String, messageId: String, text: String)
    fun toggleReaction(chatId: String, messageId: String, emoji: String)
    fun deleteMessage(chatId: String, messageId: String, forEveryone: Boolean = false)
    fun createGroup(name: String, description: String, memberIds: List<String>): Group
    fun addMembers(groupId: String, memberIds: List<String>)
    fun renameGroup(groupId: String, name: String)
    fun leaveGroup(groupId: String)

    /** Admin actions — `DELETE /groups/:id/members/:userId`, `PATCH .../members/:userId`, `DELETE /groups/:id`. */
    fun removeMember(groupId: String, userId: String)
    fun setMemberRole(groupId: String, userId: String, role: String)
    fun deleteGroup(groupId: String)
    fun setPinned(chatId: String, pinned: Boolean)
    fun setMuted(chatId: String, muted: Boolean)
    fun deleteChat(chatId: String)
    fun forwardMessage(targetChatId: String, source: Message)
    fun markRead(chatId: String)

    /** Existing chat for this person, or a fresh one seeded with a greeting. */
    fun openChat(personId: String, title: String, isOnline: Boolean): Chat

    /**
     * Streams the typing indicator to the other side over the socket.
     */
    fun setTyping(chatId: String, typing: Boolean) = Unit
    fun setRecording(chatId: String, recording: Boolean) = Unit
}
