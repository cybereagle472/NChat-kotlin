package com.nchat.app.data.repository

import com.nchat.app.data.local.ChatListCache
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Group
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import com.nchat.app.data.model.Reaction
import com.nchat.app.network.ChatDto
import com.nchat.app.network.GroupDto
import com.nchat.app.network.MessageDto
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NcJson
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.RealtimeEvent
import com.nchat.app.network.TokenStore
import com.nchat.app.network.UserDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * `ChatRepository` against the Rust backend.
 *
 * Reads come from `GET /messages/chats` and `GET /groups`, conversations from
 * `/messages/with/:id` or `/groups/:id/messages`, and every live change arrives on the
 * realtime socket (the server fans events to each member's `user:<id>` room, so one socket
 * carries direct messages, group traffic, typing and receipts).
 *
 * Sends are optimistic in the reference's sense: the bubble appears immediately with
 * [DeliveryState.Sending], then the server's row replaces it (real id + timestamp + state).
 */
class RemoteChatRepository(
    private val api: NChatApi,
    private val tokens: TokenStore,
    private val socket: NChatSocket,
    private val scope: CoroutineScope,
    /** Last known chat list, painted before the network answers. */
    private val cache: ChatListCache? = null
) : ChatRepository {

    private val localIds = AtomicLong(0L)

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private val conversations = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val requested = mutableSetOf<String>()
    private val groupIds = mutableSetOf<String>()
    private val names = mutableMapOf<String, String>()
    private val typingJobs = mutableMapOf<String, Job>()

    /** Pins and mutes are client-side, exactly like the reference (no server column). */
    private val pinned = mutableSetOf<String>()
    private val localMutes = mutableSetOf<String>()

    private val myId: String get() = tokens.userId.orEmpty()
    private val myName: String get() = tokens.fullName ?: "You"

    init {
        // Disk first (instant list), then the server's copy replaces it.
        _chats.value = cache?.load().orEmpty()
        scope.launch { socket.events.collect { event -> handleEvent(event) } }
        refresh()
    }

    /** Reloads the chat list and the groups list from the server. */
    fun refresh() {
        scope.launch { runCatching { reload() } }
    }

    // ------------------------------------------------------------- loading

    private suspend fun reload() {
        val direct = runCatching { api.chats().chats }.getOrDefault(emptyList())
        val groupRows = runCatching { api.groups().groups }.getOrDefault(emptyList())

        synchronized(conversations) {
            groupIds.clear()
            groupRows.forEach { groupIds += it.id }
        }

        direct.forEach { rememberName(it.userId, it.profile) }

        _groups.value = groupRows.map { it.toGroup() }

        val dmChats = direct.map { it.toChat() }
        val groupChats = groupRows.map { it.toChat() }
        val merged = (dmChats + groupChats)
            .map { chat ->
                chat.copy(
                    pinned = chat.id in pinned,
                    muted = chat.muted || chat.id in localMutes
                )
            }
            .sortedByDescending { it.lastMessageAt }
        _chats.value = merged
        cache?.save(merged)
    }

    private suspend fun loadConversation(chatId: String) {
        val isGroup = synchronized(conversations) { chatId in groupIds }
        val rows = runCatching {
            if (isGroup) api.groupMessages(chatId, limit = 100).messages
            else api.conversation(chatId, limit = 100).messages
        }.getOrDefault(emptyList())

        stream(chatId).value = rows.map { it.toModel(chatId) }
    }

    // ---------------------------------------------------------- conversation

    override fun conversation(chatId: String, chatTitle: String): StateFlow<List<Message>> {
        val stream = stream(chatId)
        val first = synchronized(requested) { requested.add(chatId) }
        if (first) scope.launch { runCatching { loadConversation(chatId) } }
        return stream.asStateFlow()
    }

    private fun stream(chatId: String): MutableStateFlow<List<Message>> =
        synchronized(conversations) {
            conversations.getOrPut(chatId) { MutableStateFlow(emptyList()) }
        }

    override fun openChat(personId: String, title: String, isOnline: Boolean): Chat {
        val existing = _chats.value.firstOrNull { it.id == personId }
        if (existing != null) return existing
        val chat = Chat(
            id = personId,
            title = title,
            isOnline = isOnline,
            lastMessage = "",
            lastMessageAt = 0L
        )
        _chats.update { current -> current + chat }
        return chat
    }

    // --------------------------------------------------------------- sending

    override fun sendMessage(
        chatId: String,
        text: String,
        replyAuthor: String?,
        replyText: String?
    ) {
        val body = text.trim()
        if (body.isEmpty()) return
        val localId = "local-${localIds.incrementAndGet()}"
        appendLocal(
            chatId = chatId,
            message = baseMessage(chatId, localId, body).copy(
                replyAuthor = replyAuthor,
                replyText = replyText
            )
        )
        preview(chatId, body, DeliveryState.Sending)
        dispatch(chatId, localId, body) { clientId ->
            if (isGroup(chatId)) {
                api.sendGroupMessage(chatId, body, clientMessageId = clientId, replyTo = null)
            } else {
                api.sendMessage(chatId, body, clientMessageId = clientId, replyTo = null)
            }
        }
    }

    override fun sendVoice(chatId: String, durationLabel: String) {
        val localId = "local-${localIds.incrementAndGet()}"
        appendLocal(
            chatId = chatId,
            message = baseMessage(chatId, localId, "Voice message").copy(
                kind = MessageKind.Voice,
                durationLabel = durationLabel
            )
        )
        preview(chatId, "Voice message ($durationLabel)", DeliveryState.Sending)
        dispatch(chatId, localId, "Voice message") { clientId ->
            if (isGroup(chatId)) {
                api.sendGroupMessage(
                    chatId,
                    "Voice message",
                    type = "audio",
                    clientMessageId = clientId,
                    duration = durationLabel
                )
            } else {
                api.sendMessage(
                    chatId,
                    "Voice message",
                    type = "audio",
                    clientMessageId = clientId,
                    duration = durationLabel
                )
            }
        }
    }

    override fun sendAttachment(chatId: String, kind: MessageKind, label: String) {
        val localId = "local-${localIds.incrementAndGet()}"
        appendLocal(
            chatId = chatId,
            message = baseMessage(chatId, localId, label).copy(
                kind = kind,
                fileName = if (kind == MessageKind.Document) "document.pdf" else null
            )
        )
        preview(chatId, label, DeliveryState.Sending)
        dispatch(chatId, localId, label) { clientId ->
            val type = kind.wireType()
            if (isGroup(chatId)) {
                api.sendGroupMessage(chatId, label, type = type, clientMessageId = clientId)
            } else {
                api.sendMessage(chatId, label, type = type, clientMessageId = clientId)
            }
        }
    }

    override fun sendMedia(
        chatId: String,
        kind: MessageKind,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        caption: String?,
        thumbnail: ByteArray?
    ) {
        val localId = "local-${localIds.incrementAndGet()}"
        val body = caption?.trim().orEmpty()
        appendLocal(
            chatId = chatId,
            message = baseMessage(chatId, localId, body).copy(
                kind = kind,
                fileName = fileName
            )
        )
        preview(chatId, body.ifBlank { fileName }, DeliveryState.Sending)

        scope.launch {
            // 1) upload the file (and its preview), 2) send a message that points at both.
            val media = runCatching { api.uploadMedia(fileName, mimeType, bytes) }.getOrNull()
                ?: return@launch
            val thumb = thumbnail?.let { preview ->
                runCatching { api.uploadMedia("thumb-$fileName", "image/jpeg", preview) }
                    .getOrNull()
                    ?.url
            }
            val response = runCatching {
                if (isGroup(chatId)) {
                    api.sendGroupMessage(
                        chatId,
                        body,
                        type = kind.wireType(),
                        clientMessageId = localId,
                        mediaUrl = media.url,
                        thumbnailUrl = thumb,
                        duration = null
                    )
                } else {
                    api.sendMessage(
                        chatId,
                        body,
                        type = kind.wireType(),
                        clientMessageId = localId,
                        mediaUrl = media.url,
                        thumbnailUrl = thumb,
                        fileName = fileName,
                        fileSize = media.fileSize?.toString()
                    )
                }
            }.getOrNull()
            val created = response?.message ?: return@launch
            replaceLocal(chatId, localId, created.toModel(chatId).copy(state = DeliveryState.Sent))
            preview(chatId, body.ifBlank { fileName }, DeliveryState.Sent)
        }
    }

    override fun forwardMessage(targetChatId: String, source: Message) {
        val body = source.text.ifBlank { "Forwarded message" }
        sendMessage(targetChatId, body)
    }

    /** Sends [body], then swaps the optimistic row for the server's. */
    private fun dispatch(
        chatId: String,
        localId: String,
        body: String,
        send: suspend (clientId: String) -> com.nchat.app.network.MessageEnvelope
    ) {
        scope.launch {
            val response = runCatching { send(localId) }.getOrNull()
            val created = response?.message
            if (created == null) {
                // Keep the bubble; it stays in the "sending" state until the next reload,
                // which is what the reference shows when a send fails.
                return@launch
            }
            replaceLocal(chatId, localId, created.toModel(chatId).copy(state = DeliveryState.Sent))
            preview(chatId, body, DeliveryState.Sent)
        }
    }

    // ------------------------------------------------------------ editing etc.

    override fun editMessage(chatId: String, messageId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        updateMessages(chatId) { messages ->
            messages.map { message ->
                if (message.id != messageId || !message.isMine || message.deleted) message
                else message.copy(text = body, isEdited = true)
            }
        }
        if (messageId.startsWith("local-")) return
        scope.launch {
            val response = runCatching { api.editMessage(messageId, body) }.getOrNull()
            val updated = response?.message ?: return@launch
            updateMessages(chatId) { messages ->
                messages.map { if (it.id == messageId) updated.toModel(chatId) else it }
            }
        }
    }

    override fun toggleReaction(chatId: String, messageId: String, emoji: String) {
        // Optimistic toggle first — the bubble reacts under the finger, no round trip.
        updateMessages(chatId) { messages ->
            messages.map { message ->
                if (message.id != messageId) message
                else message.copy(reactions = message.reactions.toggle(emoji))
            }
        }
        if (messageId.startsWith("local-")) return
        scope.launch {
            val response = runCatching {
                if (isGroup(chatId)) api.reactToGroupMessage(messageId, emoji)
                else api.reactToMessage(messageId, emoji)
            }.getOrNull() ?: return@launch
            updateMessages(chatId) { messages ->
                messages.map { message ->
                    if (message.id != messageId) message
                    else message.copy(reactions = response.reactions.toModel())
                }
            }
        }
    }

    override fun deleteMessage(chatId: String, messageId: String, forEveryone: Boolean) {
        updateMessages(chatId) { messages ->
            if (forEveryone) {
                messages.map { if (it.id == messageId) it.tombstone() else it }
            } else {
                messages.filterNot { it.id == messageId }
            }
        }
        if (messageId.startsWith("local-")) return
        scope.launch {
            runCatching {
                when {
                    isGroup(chatId) && forEveryone -> api.deleteGroupMessageForEveryone(messageId)
                    isGroup(chatId) -> api.deleteGroupMessageForMe(messageId)
                    forEveryone -> api.deleteMessage(messageId)
                    else -> api.deleteMessageForMe(messageId)
                }
            }
        }
    }

    // ---------------------------------------------------------------- groups

    override fun createGroup(name: String, description: String, memberIds: List<String>): Group {
        val placeholder = Group(
            id = "local-group-${localIds.incrementAndGet()}",
            name = name,
            description = description,
            memberCount = memberIds.size + 1,
            avatarEmoji = "👥",
            lastActivity = "Group created",
            lastActivityAt = System.currentTimeMillis()
        )
        _groups.update { listOf(placeholder) + it }
        scope.launch {
            val created = runCatching {
                api.createGroup(name, description.ifBlank { null }, memberIds).group
            }.getOrNull() ?: return@launch
            synchronized(conversations) { groupIds += created.id }
            _groups.update { groups ->
                groups.map { if (it.id == placeholder.id) created.toGroup() else it }
            }
            _chats.update { list ->
                list.map { if (it.id == placeholder.id) created.toChat() else it }
            }
            refresh()
        }
        return placeholder
    }

    override fun addMembers(groupId: String, memberIds: List<String>) {
        if (memberIds.isEmpty()) return
        _groups.update { groups ->
            groups.map {
                if (it.id == groupId) it.copy(memberCount = it.memberCount + memberIds.size) else it
            }
        }
        scope.launch { runCatching { api.addGroupMembers(groupId, memberIds) } }
    }

    override fun renameGroup(groupId: String, name: String) {
        _groups.update { groups -> groups.map { if (it.id == groupId) it.copy(name = name) else it } }
        _chats.update { list -> list.map { if (it.id == groupId) it.copy(title = name) else it } }
        scope.launch { runCatching { api.updateGroup(groupId, name = name) } }
    }

    override fun leaveGroup(groupId: String) {
        _groups.update { groups -> groups.filterNot { it.id == groupId } }
        _chats.update { list -> list.filterNot { it.id == groupId } }
        synchronized(conversations) {
            conversations.remove(groupId)
            groupIds -= groupId
        }
        synchronized(requested) { requested -= groupId }
        scope.launch { runCatching { api.leaveGroup(groupId) } }
    }

    // ------------------------------------------------------------ local state

    override fun setPinned(chatId: String, pinnedValue: Boolean) {
        if (pinnedValue) pinned += chatId else pinned -= chatId
        _chats.update { list ->
            list.map { if (it.id == chatId) it.copy(pinned = pinnedValue) else it }
        }
    }

    override fun setMuted(chatId: String, muted: Boolean) {
        if (muted) localMutes += chatId else localMutes -= chatId
        _chats.update { list ->
            list.map { if (it.id == chatId) it.copy(muted = muted) else it }
        }
        if (isGroup(chatId)) {
            scope.launch { runCatching { api.muteGroup(chatId, muted) } }
        }
    }

    override fun deleteChat(chatId: String) {
        _chats.update { list -> list.filterNot { it.id == chatId } }
        synchronized(conversations) { conversations.remove(chatId) }
        synchronized(requested) { requested -= chatId }
    }

    override fun markRead(chatId: String) {
        _chats.update { list ->
            list.map { if (it.id == chatId) it.copy(unreadCount = 0) else it }
        }
        scope.launch {
            runCatching {
                if (isGroup(chatId)) api.markGroupRead(chatId)
                else api.markConversationRead(chatId)
            }
        }
    }

    // ------------------------------------------------------- typing/recording

    override fun setTyping(chatId: String, typing: Boolean) {
        if (!typing) {
            typingJobs.remove(chatId)?.cancel()
            scope.launch {
                runCatching {
                    if (isGroup(chatId)) api.sendGroupTyping(chatId, false)
                    else api.sendTyping(chatId, false)
                }
            }
            return
        }
        if (typingJobs[chatId]?.isActive == true) return
        typingJobs[chatId] = scope.launch {
            runCatching {
                if (isGroup(chatId)) api.sendGroupTyping(chatId, true) else api.sendTyping(chatId, true)
            }
            // Typing indicators are ephemeral; re-arm after the debounce window.
            delay(3_000)
        }
    }

    override fun setRecording(chatId: String, recording: Boolean) {
        scope.launch {
            runCatching {
                if (isGroup(chatId)) api.sendGroupRecording(chatId, recording)
                else api.sendRecording(chatId, recording)
            }
        }
    }

    // --------------------------------------------------------------- events

    private fun handleEvent(event: RealtimeEvent) {
        when (event.type) {
            "message:new" -> {
                val row = event.objectOrNull("message") ?: return
                val dto = decodeMessage(row)
                if (dto.senderId == myId) return
                val chatId = dto.senderId
                ingest(chatId, dto)
                bumpPreview(chatId, dto)
            }

            "message:edit" -> {
                val row = event.objectOrNull("message") ?: return
                val dto = decodeMessage(row)
                val chatId = if (dto.senderId == myId) dto.receiverId.orEmpty() else dto.senderId
                replaceMessage(chatId, dto)
            }

            "message:delete" -> {
                val id = event.string("messageId") ?: return
                val chatId = event.string("sender_id").orEmpty()
                removeMessage(chatId, id)
                removeMessage(event.string("receiver_id").orEmpty(), id)
            }

            "message:reaction" -> {
                val id = event.string("messageId") ?: return
                val reactions = event.objectOrNull("reactions") ?: return
                val chatId = otherSide(event)
                updateMessages(chatId) { messages ->
                    messages.map {
                        if (it.id == id) it.copy(reactions = decodeReactions(reactions)) else it
                    }
                }
            }

            "message:read" -> {
                val reader = event.string("reader_id") ?: return
                updateMessages(reader) { messages ->
                    messages.map { if (it.isMine) it.copy(state = DeliveryState.Read) else it }
                }
                _chats.update { list ->
                    list.map {
                        if (it.id == reader) it.copy(lastMessageState = DeliveryState.Read) else it
                    }
                }
            }

            "typing" -> {
                val sender = event.string("sender_id") ?: return
                val typing = event.string("is_typing") == "true"
                affectChat(sender) { it.copy(isTyping = typing) }
            }

            "recording" -> {
                val sender = event.string("sender_id") ?: return
                val recording = event.string("is_recording") == "true"
                affectChat(sender) { it.copy(isRecording = recording) }
            }

            "group-message:new" -> {
                val row = event.objectOrNull("message") ?: return
                val dto = decodeMessage(row)
                if (dto.senderId == myId) return
                val chatId = dto.groupId ?: return
                ingest(chatId, dto)
                bumpPreview(chatId, dto)
            }

            "group-message:delete" -> {
                val id = event.string("message_id") ?: return
                val groupId = event.string("group_id") ?: return
                removeMessage(groupId, id)
            }

            "group-message:reaction" -> {
                val id = event.string("message_id") ?: return
                val groupId = event.string("group_id") ?: return
                val reactions = event.objectOrNull("reactions") ?: return
                updateMessages(groupId) { messages ->
                    messages.map {
                        if (it.id == id) it.copy(reactions = decodeReactions(reactions)) else it
                    }
                }
            }

            "group-typing" -> {
                val groupId = event.string("group_id") ?: return
                val typing = event.string("is_typing") == "true"
                affectChat(groupId) { it.copy(isTyping = typing) }
            }

            "group-recording" -> {
                val groupId = event.string("group_id") ?: return
                val recording = event.string("is_recording") == "true"
                affectChat(groupId) { it.copy(isRecording = recording) }
            }

            "group:created", "group:updated", "group:deleted", "group-member:joined",
            "group-member:left" -> refresh()
        }
    }

    private fun ingest(chatId: String, dto: MessageDto) {
        val stream = stream(chatId)
        val model = dto.toModel(chatId)
        if (stream.value.any { it.id == model.id }) return
        stream.update { (it + model).sortedBy { message -> message.timestamp } }
    }

    private fun replaceMessage(chatId: String, dto: MessageDto) {
        if (chatId.isEmpty()) return
        val model = dto.toModel(chatId)
        updateMessages(chatId) { messages ->
            if (messages.none { it.id == model.id }) messages else {
                messages.map { if (it.id == model.id) model else it }
            }
        }
    }

    private fun removeMessage(chatId: String, messageId: String) {
        if (chatId.isEmpty()) return
        updateMessages(chatId) { messages -> messages.filterNot { it.id == messageId } }
    }

    // --------------------------------------------------------------- helpers

    private fun isGroup(chatId: String): Boolean =
        synchronized(conversations) { chatId in groupIds }

    private fun baseMessage(chatId: String, id: String, text: String) = Message(
        id = id,
        chatId = chatId,
        authorId = myId,
        authorName = myName,
        text = text,
        timestamp = System.currentTimeMillis(),
        isMine = true,
        state = DeliveryState.Sending
    )

    private fun appendLocal(chatId: String, message: Message) {
        stream(chatId).update { it + message }
    }

    private fun replaceLocal(chatId: String, localId: String, message: Message) {
        updateMessages(chatId) { messages ->
            val withoutLocal = messages.filterNot { it.id == localId }
            (withoutLocal + message).distinctBy { it.id }.sortedBy { it.timestamp }
        }
    }

    private fun updateMessages(chatId: String, transform: (List<Message>) -> List<Message>) {
        if (chatId.isEmpty()) return
        stream(chatId).update(transform)
    }

    private fun preview(chatId: String, body: String, state: DeliveryState) {
        _chats.update { list ->
            list.map { chat ->
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

    private fun bumpPreview(chatId: String, dto: MessageDto) {
        _chats.update { list ->
            list.map { chat ->
                if (chat.id != chatId) chat
                else chat.copy(
                    lastMessage = dto.previewText(),
                    lastMessageAt = isoMillis(dto.createdAt),
                    lastMessageMine = false,
                    lastMessageState = null,
                    lastMessageKind = dto.kind(),
                    unreadCount = chat.unreadCount + 1
                )
            }
        }
    }

    private fun affectChat(chatId: String, transform: (Chat) -> Chat) {
        _chats.update { list -> list.map { if (it.id == chatId) transform(it) else it } }
    }

    private fun rememberName(userId: String, profile: UserDto?) {
        val name = profile?.displayName()
        if (!name.isNullOrBlank()) names[userId] = name
    }

    /** The conversation id (the other user) for a DM event regardless of which side we are. */
    private fun otherSide(event: RealtimeEvent): String {
        val sender = event.string("sender_id").orEmpty()
        val receiver = event.string("receiver_id").orEmpty()
        return if (sender == myId) receiver else sender
    }

    private fun nameOf(userId: String): String =
        names[userId] ?: run {
            val chat = _chats.value.firstOrNull { it.id == userId }
            chat?.title ?: "NChat user"
        }

    private fun decodeMessage(row: JsonObject): MessageDto =
        runCatching { NcJson.format.decodeFromJsonElement<MessageDto>(row) }
            .getOrNull() ?: MessageDto()

    private fun decodeReactions(row: JsonObject): List<Reaction> =
        runCatching { NcJson.format.decodeFromJsonElement<Map<String, List<String>>>(row) }
            .getOrNull()?.toModel() ?: emptyList()

    // --------------------------------------------------------------- mapping

    private fun ChatDto.toChat(): Chat {
        val profileName = profile?.displayName() ?: nameOf(userId)
        rememberName(userId, profile)
        val last = lastMessage
        return Chat(
            id = userId,
            title = profileName,
            avatarUrl = profile?.avatarUrl,
            isOnline = profile?.isOnline == true,
            isGroup = false,
            lastMessage = last?.previewText().orEmpty(),
            lastMessageAt = isoMillis(last?.createdAt),
            lastMessageKind = last?.kind() ?: MessageKind.Text,
            lastMessageMine = last?.senderId == myId,
            lastMessageState = when {
                last == null || last.senderId != myId -> null
                last.isRead -> DeliveryState.Read
                else -> DeliveryState.Sent
            },
            unreadCount = unreadCount
        )
    }

    private fun GroupDto.toChat(): Chat = Chat(
        id = id,
        title = name,
        avatarEmoji = "👥",
        avatarUrl = avatarUrl,
        isGroup = true,
        lastMessage = lastMessage?.previewText().orEmpty(),
        lastMessageAt = isoMillis(lastMessage?.createdAt ?: createdAt),
        lastMessageKind = lastMessage?.kind() ?: MessageKind.Text,
        lastMessageMine = lastMessage?.senderId == myId,
        lastMessageState = when {
            lastMessage == null || lastMessage.senderId != myId -> null
            lastMessage.isRead -> DeliveryState.Read
            else -> DeliveryState.Sent
        },
        unreadCount = unreadCount,
        muted = members.firstOrNull { it.userId == myId }?.isMuted == true
    )

    private fun GroupDto.toGroup(): Group = Group(
        id = id,
        name = name,
        description = description.orEmpty(),
        memberCount = if (members.isEmpty()) 1 else members.size,
        unreadCount = unreadCount,
        avatarEmoji = "👥",
        lastActivity = lastMessage?.previewText().orEmpty(),
        lastActivityAt = isoMillis(lastMessage?.createdAt ?: createdAt)
    )

    private fun MessageDto.toModel(chatId: String): Message {
        val mine = senderId == myId
        return Message(
            id = id,
            chatId = chatId,
            authorId = senderId,
            authorName = if (mine) myName else nameOf(senderId),
            text = if (isDeleted) "This message was deleted" else content,
            timestamp = isoMillis(createdAt),
            isMine = mine,
            kind = kind(),
            state = when {
                !mine -> DeliveryState.Read
                isRead -> DeliveryState.Read
                else -> DeliveryState.Sent
            },
            durationLabel = duration,
            fileName = fileName,
            mediaUrl = mediaUrl,
            thumbnailUrl = thumbnailUrl,
            reactions = reactions.toModel(),
            replyText = null,
            isEdited = isEdited,
            deleted = isDeleted
        )
    }

    private fun MessageDto.kind(): MessageKind = when (type.lowercase()) {
        "image", "photo" -> MessageKind.Photo
        "video" -> MessageKind.Video
        "audio", "voice" -> MessageKind.Voice
        "document", "file" -> MessageKind.Document
        "location" -> MessageKind.Location
        else -> MessageKind.Text
    }

    private fun MessageDto.previewText(): String = when {
        isDeleted -> "This message was deleted"
        type.equals("image", true) || type.equals("photo", true) -> "📷 Photo"
        type.equals("video", true) -> "🎥 Video"
        type.equals("audio", true) || type.equals("voice", true) -> "🎤 Voice message"
        type.equals("document", true) || type.equals("file", true) -> "📎 ${fileName ?: "Document"}"
        type.equals("location", true) -> "📍 Location"
        else -> content
    }

    private fun MessageKind.wireType(): String = when (this) {
        MessageKind.Photo -> "image"
        MessageKind.Video -> "video"
        MessageKind.Voice -> "audio"
        MessageKind.Document -> "document"
        MessageKind.Location -> "location"
        MessageKind.Text -> "text"
    }

    private fun Map<String, List<String>>.toModel(): List<Reaction> = entries
        .filter { it.value.isNotEmpty() }
        .map { (emoji, users) ->
            Reaction(emoji = emoji, count = users.size, reactedByMe = myId in users)
        }

    private fun List<Reaction>.toggle(emoji: String): List<Reaction> {
        val existing = firstOrNull { it.emoji == emoji }
        return when {
            existing == null -> this + Reaction(emoji, 1, reactedByMe = true)
            existing.reactedByMe -> {
                val remaining = existing.count - 1
                if (remaining <= 0) filterNot { it.emoji == emoji }
                else map {
                    if (it.emoji == emoji) it.copy(count = remaining, reactedByMe = false) else it
                }
            }

            else -> map {
                if (it.emoji == emoji) it.copy(count = it.count + 1, reactedByMe = true) else it
            }
        }
    }

    /** The reference's tombstone: the row stays so the conversation keeps its shape. */
    private fun Message.tombstone(): Message = copy(
        text = "This message was deleted",
        kind = MessageKind.Text,
        reactions = emptyList(),
        replyAuthor = null,
        replyText = null,
        fileName = null,
        durationLabel = null,
        forwarded = false,
        deleted = true
    )

    private fun UserDto.displayName(): String =
        fullName?.takeIf { it.isNotBlank() }
            ?: username?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: email?.substringBefore('@')
            ?: "NChat user"

    private fun isoMillis(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return synchronized(ISO) {
            runCatching { ISO.parse(value)?.time ?: 0L }.getOrDefault(0L)
        }
    }

    private companion object {
        val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
