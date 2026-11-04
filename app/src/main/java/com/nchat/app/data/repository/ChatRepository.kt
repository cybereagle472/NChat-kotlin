package com.nchat.app.data.repository

import com.nchat.app.data.mock.MockDataProvider
import com.nchat.app.data.model.CallRecord
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.Group
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.Reaction
import com.nchat.app.data.model.StatusUpdate
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
 * Contract the Rust service will implement later. The UI only talks to this interface.
 */
interface ChatRepository {
    val chats: StateFlow<List<Chat>>
    fun conversation(chatId: String, chatTitle: String): StateFlow<List<Message>>
    fun sendMessage(chatId: String, text: String)
    fun toggleReaction(chatId: String, messageId: String, emoji: String)
    fun markRead(chatId: String)
}

class InMemoryChatRepository(
    private val seed: MockDataProvider = MockDataProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : ChatRepository {

    private val _chats = MutableStateFlow(seed.chats())
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val conversations = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val messageIds = AtomicLong(0L)

    val profile: UserProfile = MockDataProvider.profile
    val statuses: List<StatusUpdate> = MockDataProvider.statuses()
    val groups: List<Group> = MockDataProvider.groups()
    val friends: List<Friend> = MockDataProvider.friends()
    val calls: List<CallRecord> = MockDataProvider.calls()

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
        stream.update { current ->
            current + Message(
                id = messageId,
                chatId = chatId,
                authorId = "me",
                authorName = profile.fullName,
                text = body,
                timestamp = System.currentTimeMillis(),
                isMine = true,
                state = DeliveryState.Sending
            )
        }
        updatePreview(chatId, body, DeliveryState.Sending)
        advanceState(chatId, messageId)
    }

    private fun advanceState(chatId: String, messageId: String) {
        val stream = conversations[chatId] ?: return
        scope.launch {
            delay(650)
            applyState(stream, messageId, DeliveryState.Sent)
            updatePreviewState(chatId, DeliveryState.Sent)
            delay(1200)
            applyState(stream, messageId, DeliveryState.Read)
            updatePreviewState(chatId, DeliveryState.Read)
        }
    }

    private fun applyState(
        stream: MutableStateFlow<List<Message>>,
        messageId: String,
        state: DeliveryState
    ) {
        stream.update { messages ->
            messages.map { if (it.id == messageId) it.copy(state = state) else it }
        }
    }

    override fun toggleReaction(chatId: String, messageId: String, emoji: String) {
        val stream = conversations[chatId] ?: return
        stream.update { messages ->
            messages.map { message ->
                if (message.id != messageId) message else message.copy(reactions = message.reactions.toggled(emoji))
            }
        }
    }

    override fun markRead(chatId: String) {
        _chats.update { chats ->
            chats.map { if (it.id == chatId) it.copy(unreadCount = 0) else it }
        }
    }

    private fun List<Reaction>.toggled(emoji: String): List<Reaction> {
        val existing = firstOrNull { it.emoji == emoji }
        return when {
            existing == null -> this + Reaction(emoji, 1, reactedByMe = true)
            existing.reactedByMe -> {
                val remaining = existing.count - 1
                if (remaining <= 0) filterNot { it.emoji == emoji }
                else map { if (it.emoji == emoji) it.copy(count = remaining, reactedByMe = false) else it }
            }
            else -> map { if (it.emoji == emoji) it.copy(count = it.count + 1, reactedByMe = true) else it }
        }
    }

    private fun updatePreview(chatId: String, body: String, state: DeliveryState) {
        _chats.update { chats ->
            chats.map { chat ->
                if (chat.id != chatId) chat
                else chat.copy(
                    lastMessage = body,
                    lastMessageAt = System.currentTimeMillis(),
                    lastMessageMine = true,
                    lastMessageState = state,
                    isTyping = false
                )
            }
        }
    }

    private fun updatePreviewState(chatId: String, state: DeliveryState) {
        _chats.update { chats ->
            chats.map { chat ->
                if (chat.id == chatId && chat.lastMessageMine) chat.copy(lastMessageState = state) else chat
            }
        }
    }
}
