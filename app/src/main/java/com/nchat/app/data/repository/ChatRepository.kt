package com.nchat.app.data.repository

import com.nchat.app.data.mock.MockDataProvider
import com.nchat.app.data.model.CallEntry
import com.nchat.app.data.model.Channel
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Community
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageStatus
import com.nchat.app.data.model.Reaction
import com.nchat.app.data.model.Story
import com.nchat.app.data.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth for messaging data. The contract is intentionally backend
 * agnostic: the Rust service can implement it later without touching the UI layer.
 */
interface ChatRepository {
    val chats: StateFlow<List<Chat>>
    fun conversation(chatId: String, chatTitle: String): StateFlow<List<Message>>
    fun sendMessage(chatId: String, text: String)
    fun toggleReaction(chatId: String, messageId: String, emoji: String)
    fun markRead(chatId: String)
    fun togglePin(chatId: String)
    fun toggleMute(chatId: String)
    fun saveDraft(chatId: String, draft: String)
}

/**
 * In-memory implementation backed by [MockDataProvider]. Delivery status advances
 * asynchronously so the UI can show the real sent -> delivered -> read tick sequence.
 */
class InMemoryChatRepository(
    private val seed: MockDataProvider = MockDataProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : ChatRepository {

    private val _chats = MutableStateFlow(seed.chats())
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val conversations = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val messageIds = AtomicLong(0L)

    val profile: UserProfile = MockDataProvider.profile
    val calls: List<CallEntry> = MockDataProvider.calls()
    val stories: List<Story> = MockDataProvider.stories()
    val channels: List<Channel> = MockDataProvider.channels()
    val communities: List<Community> = MockDataProvider.communities()

    override fun conversation(chatId: String, chatTitle: String): StateFlow<List<Message>> =
        synchronized(conversations) {
            conversations.getOrPut(chatId) {
                MutableStateFlow(seed.conversation(chatId, chatTitle))
            }.asStateFlow()
        }

    override fun sendMessage(chatId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return

        val stream = conversations[chatId] ?: return
        val chat = _chats.value.firstOrNull { it.id == chatId } ?: return
        val messageId = "local-${messageIds.incrementAndGet()}"
        val outgoing = Message(
            id = messageId,
            chatId = chatId,
            authorName = profile.fullName,
            text = body,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            status = MessageStatus.Sending
        )
        stream.update { it + outgoing }
        updateChatPreview(chat, body, MessageStatus.Sending, clearDraft = true)
        advanceStatus(chatId, messageId)
    }

    private fun advanceStatus(chatId: String, messageId: String) {
        val stream = conversations[chatId] ?: return
        scope.launch {
            delay(700)
            setStatus(stream, messageId, MessageStatus.Sent)
            updateChatStatus(chatId, MessageStatus.Sent)
            delay(900)
            setStatus(stream, messageId, MessageStatus.Delivered)
            updateChatStatus(chatId, MessageStatus.Delivered)
            delay(1400)
            setStatus(stream, messageId, MessageStatus.Read)
            updateChatStatus(chatId, MessageStatus.Read)
        }
    }

    private fun setStatus(
        stream: MutableStateFlow<List<Message>>,
        messageId: String,
        status: MessageStatus
    ) {
        stream.update { messages ->
            messages.map { message ->
                if (message.id == messageId) message.copy(status = status) else message
            }
        }
    }

    override fun toggleReaction(chatId: String, messageId: String, emoji: String) {
        val stream = conversations[chatId] ?: return
        stream.update { messages ->
            messages.map { message ->
                if (message.id != messageId) {
                    message
                } else {
                    message.copy(reactions = message.reactions.toggled(emoji))
                }
            }
        }
    }

    private fun List<Reaction>.toggled(emoji: String): List<Reaction> {
        val existing = firstOrNull { it.emoji == emoji }
        return when {
            existing == null -> this + Reaction(emoji, count = 1, reactedByMe = true)
            existing.reactedByMe -> {
                val updatedCount = existing.count - 1
                if (updatedCount <= 0) {
                    filterNot { it.emoji == emoji }
                } else {
                    map { if (it.emoji == emoji) it.copy(count = updatedCount, reactedByMe = false) else it }
                }
            }
            else -> map {
                if (it.emoji == emoji) it.copy(count = it.count + 1, reactedByMe = true) else it
            }
        }
    }

    override fun markRead(chatId: String) {
        _chats.update { chats ->
            chats.map { if (it.id == chatId) it.copy(unreadCount = 0) else it }
        }
    }

    override fun togglePin(chatId: String) {
        _chats.update { chats ->
            chats.map { if (it.id == chatId) it.copy(isPinned = !it.isPinned) else it }
        }
    }

    override fun toggleMute(chatId: String) {
        _chats.update { chats ->
            chats.map { if (it.id == chatId) it.copy(isMuted = !it.isMuted) else it }
        }
    }

    override fun saveDraft(chatId: String, draft: String) {
        _chats.update { chats ->
            chats.map {
                if (it.id == chatId) it.copy(draft = draft.ifBlank { null }) else it
            }
        }
    }

    private fun updateChatPreview(
        chat: Chat,
        body: String,
        status: MessageStatus,
        clearDraft: Boolean
    ) {
        _chats.update { chats ->
            chats.map { current ->
                if (current.id != chat.id) {
                    current
                } else {
                    current.copy(
                        lastMessagePreview = body,
                        lastMessageAt = System.currentTimeMillis(),
                        lastMessageFromMe = true,
                        lastMessageStatus = status,
                        draft = if (clearDraft) null else current.draft
                    )
                }
            }
        }
    }

    private fun updateChatStatus(chatId: String, status: MessageStatus) {
        _chats.update { chats ->
            chats.map {
                if (it.id == chatId && it.lastMessageFromMe) {
                    it.copy(lastMessageStatus = status)
                } else {
                    it
                }
            }
        }
    }
}
