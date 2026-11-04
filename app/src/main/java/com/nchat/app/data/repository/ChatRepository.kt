package com.nchat.app.data.repository

import com.nchat.app.data.mock.MockDataProvider
import com.nchat.app.data.model.CallRecord
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.Group
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
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
    val groups: StateFlow<List<Group>>
    fun conversation(chatId: String, chatTitle: String): StateFlow<List<Message>>
    fun sendMessage(
        chatId: String,
        text: String,
        replyAuthor: String? = null,
        replyText: String? = null
    )
    fun sendAttachment(chatId: String, kind: MessageKind, label: String)
    fun sendVoice(chatId: String, durationLabel: String)
    fun toggleReaction(chatId: String, messageId: String, emoji: String)
    fun deleteMessage(chatId: String, messageId: String)
    fun createGroup(name: String, description: String, memberCount: Int): Group
    fun addMembers(groupId: String, count: Int)
    fun renameGroup(groupId: String, name: String)
    fun leaveGroup(groupId: String)
    fun forwardMessage(targetChatId: String, source: Message)
    fun markRead(chatId: String)

    /** Existing chat for this person, or a fresh one seeded with a greeting. */
    fun openChat(personId: String, title: String, isOnline: Boolean): Chat
}

class InMemoryChatRepository(
    private val seed: MockDataProvider = MockDataProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : ChatRepository {

    private val _chats = MutableStateFlow(seed.chats())
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _groups = MutableStateFlow(seed.groups())
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private val conversations = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val messageIds = AtomicLong(0L)

    val profile: UserProfile = MockDataProvider.profile
    val statuses: List<StatusUpdate> = MockDataProvider.statuses()
    val friends: List<Friend> = MockDataProvider.friends()
    val calls: List<CallRecord> = MockDataProvider.calls()

    override fun openChat(personId: String, title: String, isOnline: Boolean): Chat {
        val existing = _chats.value.firstOrNull { it.id == personId }
        if (existing != null) return existing
        val chat = Chat(
            id = personId,
            title = title,
            isOnline = isOnline,
            lastMessage = "You are now friends — say hello 👋",
            lastMessageAt = System.currentTimeMillis()
        )
        _chats.update { current -> current + chat }
        return chat
    }

    override fun conversation(chatId: String, chatTitle: String): StateFlow<List<Message>> =
        synchronized(conversations) {
            conversations.getOrPut(chatId) {
                MutableStateFlow(seed.conversation(chatId, chatTitle))
            }.asStateFlow()
        }

    override fun sendMessage(
        chatId: String,
        text: String,
        replyAuthor: String?,
        replyText: String?
    ) {
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
                state = DeliveryState.Sending,
                replyAuthor = replyAuthor,
                replyText = replyText
            )
        }
        updatePreview(chatId, body, DeliveryState.Sending)
        advanceState(chatId, messageId)
    }

    override fun sendVoice(chatId: String, durationLabel: String) {
        val stream = conversations[chatId] ?: return
        val messageId = "local-${messageIds.incrementAndGet()}"
        stream.update { current ->
            current + Message(
                id = messageId,
                chatId = chatId,
                authorId = "me",
                authorName = profile.fullName,
                text = "Voice message",
                timestamp = System.currentTimeMillis(),
                isMine = true,
                kind = MessageKind.Voice,
                state = DeliveryState.Sending,
                durationLabel = durationLabel
            )
        }
        updatePreview(chatId, "Voice message ($durationLabel)", DeliveryState.Sending)
        advanceState(chatId, messageId)
    }

    override fun sendAttachment(chatId: String, kind: MessageKind, label: String) {
        val stream = conversations[chatId] ?: return
        val messageId = "local-${messageIds.incrementAndGet()}"
        stream.update { current ->
            current + Message(
                id = messageId,
                chatId = chatId,
                authorId = "me",
                authorName = profile.fullName,
                text = label,
                timestamp = System.currentTimeMillis(),
                isMine = true,
                kind = kind,
                state = DeliveryState.Sending,
                fileName = if (kind == MessageKind.Document) "document.pdf" else null
            )
        }
        updatePreview(chatId, label, DeliveryState.Sending)
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

    override fun deleteMessage(chatId: String, messageId: String) {
        val stream = conversations[chatId] ?: return
        stream.update { messages -> messages.filterNot { it.id == messageId } }
        val remaining = stream.value.lastOrNull()
        _chats.update { chats ->
            chats.map { chat ->
                if (chat.id != chatId) {
                    chat
                } else {
                    chat.copy(
                        lastMessage = remaining?.text ?: "No messages yet",
                        lastMessageAt = remaining?.timestamp ?: chat.lastMessageAt,
                        lastMessageMine = remaining?.isMine ?: false,
                        lastMessageKind = remaining?.kind ?: MessageKind.Text,
                        lastMessageState = remaining?.state,
                        isTyping = false
                    )
                }
            }
        }
    }

    override fun createGroup(name: String, description: String, memberCount: Int): Group {
        val group = Group(
            id = "group-${messageIds.incrementAndGet()}",
            name = name,
            description = description,
            memberCount = memberCount + 1,
            avatarEmoji = "👥",
            lastActivity = "Group created",
            lastActivityAt = System.currentTimeMillis()
        )
        _groups.update { current -> listOf(group) + current }
        val chat = Chat(
            id = group.id,
            title = group.name,
            avatarEmoji = group.avatarEmoji,
            isGroup = true,
            lastMessage = "Group created",
            lastMessageAt = group.lastActivityAt
        )
        _chats.update { current -> listOf(chat) + current }
        return group
    }

    override fun addMembers(groupId: String, count: Int) {
        _groups.update { groups ->
            groups.map { group ->
                if (group.id == groupId) {
                    group.copy(memberCount = group.memberCount + count)
                } else {
                    group
                }
            }
        }
    }

    override fun renameGroup(groupId: String, name: String) {
        _groups.update { groups ->
            groups.map { if (it.id == groupId) it.copy(name = name) else it }
        }
        _chats.update { chats ->
            chats.map { if (it.id == groupId) it.copy(title = name) else it }
        }
    }

    override fun leaveGroup(groupId: String) {
        _groups.update { groups -> groups.filterNot { it.id == groupId } }
        _chats.update { chats -> chats.filterNot { it.id == groupId } }
        synchronized(conversations) { conversations.remove(groupId) }
    }

    override fun forwardMessage(targetChatId: String, source: Message) {
        val stream = conversations[targetChatId] ?: return
        val messageId = "local-${messageIds.incrementAndGet()}"
        stream.update { current ->
            current + source.copy(
                id = messageId,
                chatId = targetChatId,
                authorId = "me",
                authorName = profile.fullName,
                timestamp = System.currentTimeMillis(),
                isMine = true,
                state = DeliveryState.Sending,
                forwarded = true,
                reactions = emptyList(),
                replyAuthor = null,
                replyText = null
            )
        }
        updatePreview(targetChatId, source.text.ifBlank { "Forwarded message" }, DeliveryState.Sending)
        advanceState(targetChatId, messageId)
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
