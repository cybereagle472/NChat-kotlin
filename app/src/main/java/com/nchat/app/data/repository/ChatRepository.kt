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
    fun conversation(chatId: String, chatTitle: String): StateFlow<List<Message>>
    fun sendMessage(
        chatId: String,
        text: String,
        replyAuthor: String? = null,
        replyText: String? = null
    )
    fun sendAttachment(chatId: String, kind: MessageKind, label: String)

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
        caption: String? = null
    )
    fun sendVoice(chatId: String, durationLabel: String)
    /** Rewrites the body of my own text message and flags it as edited. */
    fun editMessage(chatId: String, messageId: String, text: String)
    fun toggleReaction(chatId: String, messageId: String, emoji: String)
    fun deleteMessage(chatId: String, messageId: String, forEveryone: Boolean = false)
    fun createGroup(name: String, description: String, memberIds: List<String>): Group
    fun addMembers(groupId: String, memberIds: List<String>)
    fun renameGroup(groupId: String, name: String)
    fun leaveGroup(groupId: String)
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
