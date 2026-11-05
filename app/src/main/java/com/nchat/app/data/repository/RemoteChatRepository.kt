package com.nchat.app.data.repository

import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.data.local.ChatListCache
import com.nchat.app.data.local.OutboxStore
import com.nchat.app.data.model.OutboxEntry
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.GroupMember
import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Group
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import com.nchat.app.data.model.Reaction
import com.nchat.app.network.ApiException
import com.nchat.app.network.ChatDto
import com.nchat.app.network.GroupDto
import com.nchat.app.network.MessageDto
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NcJson
import com.nchat.app.network.ConnectionState
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.RealtimeEvent
import com.nchat.app.network.TokenStore
import com.nchat.app.network.UserDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.UUID
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
    /** Ids this device has blocked; their live messages are held back, never deleted. */
    private val blockedIds: () -> Set<String> = { emptySet() },
    /** Last known chat list, painted before the network answers. */
    private val cache: ChatListCache? = null,
    /** Queue of messages that still have to reach the server — see [OutboxStore]. */
    private val outbox: OutboxStore? = null
) : ChatRepository {


    /** How many messages a conversation keeps in memory; matches the server's page size. */
    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private val conversations = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val requested = mutableSetOf<String>()
    private val groupIds = mutableSetOf<String>()
    private val names = mutableMapOf<String, String>()
    private val typingJobs = mutableMapOf<String, Job>()

    /**
     * Conversations found in the message history itself, and whether the search has run.
     *
     * `/messages/chats` is a *friends-only* view (`SELECT … FROM friendships`), so anyone whose
     * friendship row is missing — an older build's block deleted it server-side while the
     * accepted request stayed behind — had a full history and no row in the list: the
     * conversation simply vanished. The relationship is not what makes a conversation; the
     * messages are. So the peers below are recovered from `GET /messages/with/:id?limit=1`.
     */
    private var historyPeers: List<Chat> = emptyList()

    /** Which account [historyPeers] belong to; a different sign-in must probe again. */
    private var historyProbedFor: String? = null

    /** Pins and mutes are client-side, exactly like the reference (no server column). */
    private val pinned = mutableSetOf<String>()
    private val localMutes = mutableSetOf<String>()

    /**
     * A fresh id for one send — a UUID, exactly what the reference's `generateClientMessageId()`
     * (src/utils/offlineStorage.ts:118) returns.
     *
     * This id is `client_message_id`, and the worker treats it as an **idempotency key**
     * (`worker/src/routes/messages.ts:147-152`):
     *
     * ```sql
     * SELECT * FROM messages WHERE sender_id = ? AND client_message_id = ?
     * ```
     *
     * If it matches anything, the server answers with that old row, stores nothing and
     * broadcasts nothing. The app used to number sends from 1 again on every launch
     * ("local-1", "local-2", …), so from the second session onwards *every* send matched an
     * older message from the same account: the server returned an old message, the recipient
     * never got anything, and the bubble flipped to whatever that old row was — messages that
     * simply never "went". Group sends collide less often because their key also includes the
     * group id, which is why groups kept working longer than 1:1.
     */
    private fun newClientId(): String =
        runCatching { "c-" + UUID.randomUUID().toString() }
            .getOrElse { "c-${System.currentTimeMillis()}-${(0..1_000_000).random()}" }

    private val myId: String get() = tokens.userId.orEmpty()
    private val myName: String get() = tokens.fullName ?: "You"

    init {
        // Disk first (instant list), then the server's copy replaces it.
        _chats.value = cache?.load().orEmpty()
        scope.launch { socket.events.collect { event -> handleEvent(event) } }
        // Signing out must not leave the previous account's threads in memory: after a new
        // sign-in on the same process they would be "kept" by the merge above and shown to
        // somebody who never had them.
        // Anything left in the outbox from the last run goes out now, and again every time the
        // realtime socket comes back up — the reference does the same on reconnect.
        scope.launch {
            socket.state.collect { state ->
                if (state == ConnectionState.Connected) flushOutbox()
            }
        }
        flushOutbox()
        scope.launch {
            tokens.signedIn.collect { signedIn ->
                if (signedIn) return@collect
                _chats.value = emptyList()
                _groups.value = emptyList()
                names.clear()
                historyPeers = emptyList()
                historyProbedFor = null
                synchronized(conversations) {
                    conversations.clear()
                    requested.clear()
                    groupIds.clear()
                }
                outbox?.clear()
            }
        }
        refresh()
    }

    private val _syncError = MutableStateFlow<String?>(null)
    override val syncError: StateFlow<String?> = _syncError.asStateFlow()

    /** Reloads the chat list and the groups list from the server. */
    override fun refresh() {
        scope.launch { runCatching { reload() } }
    }

    // ------------------------------------------------------------- loading

    private suspend fun reload() {
        val directResult = runCatching { api.chats().chats }
        val groupResult = runCatching { api.groups().groups }
        val direct = directResult.getOrDefault(emptyList())
        val groupRows = groupResult.getOrDefault(emptyList())

        // Surface the reason instead of an empty screen (Settings ▸ Sync check has the rest).
        _syncError.value = when {
            directResult.isFailure -> "Couldn't load your chats — ${directResult.describe()}"
            groupResult.isFailure -> "Couldn't load your groups — ${groupResult.describe()}"
            else -> null
        }

        synchronized(conversations) {
            groupIds.clear()
            groupRows.forEach { groupIds += it.id }
        }

        direct.forEach { rememberName(it.userId, it.profile) }

        // Group bubbles name their author from this map; without seeding it every other member
        // of a group chat was labelled "NChat user".
        groupRows.forEach { group -> group.members.forEach { member -> rememberName(member.userId, member.profile) } }
        _groups.value = groupRows.map { it.toGroup() }

        val dmChats = direct.map { it.toChat() }
        val groupChats = groupRows.map { it.toChat() }

        // Keep 1:1 threads the app already knows about but the server does not list.
        // `/messages/chats` only ever returns *friends*, so without this an ongoing
        // conversation with someone the friendship table does not know about vanished from
        // the list on the very next refresh (it used to be replaced wholesale).
        val knownDmIds = dmChats.map { it.id }.toSet()
        val keptDms = _chats.value.filter { chat ->
            !chat.isGroup &&
                chat.id !in knownDmIds &&
                chat.id !in groupIds &&
                chat.lastMessage.isNotBlank()
        }

        val merged = (dmChats + keptDms + groupChats)
            .map { chat ->
                chat.copy(
                    pinned = chat.id in pinned,
                    muted = chat.muted || chat.id in localMutes
                )
            }
            .sortedByDescending { it.lastMessageAt }
        _chats.value = merged
        // Only persist a complete answer. Saving a half-failed refresh used to overwrite the
        // cache with just the groups, so one bad `/messages/chats` and every 1:1 row was gone
        // from disk for good.
        if (directResult.isSuccess && groupResult.isSuccess) cache?.save(merged)

        // Then, and only after the real list is on screen, look for conversations the server's
        // friendship view forgot. Runs once per process: every peer it finds lands in the list
        // (and in the cache), so the next refresh already knows about them.
        val covered = merged.map { it.id }.toSet() + synchronized(conversations) { groupIds.toSet() }
        val recovered = recoverFromHistory(covered)
        if (recovered.isNotEmpty()) {
            val withHistory = (merged + recovered).sortedByDescending { it.lastMessageAt }
            _chats.value = withHistory
            cache?.save(withHistory)
        }
    }

    /**
     * Rebuilds chat rows from the message history of accounts the friend view does not cover.
     *
     * WhatsApp, Telegram and Messenger all keep a thread because the messages exist, never
     * because a "friendship" row does — this is the same rule: ask `GET /messages/with/:id`
     * (one message, the newest) for every account the app can already see and keep the ones
     * that answer with something. Blocked contacts are skipped: their thread is in the list
     * already and their messages are being held back on purpose.
     */
    private suspend fun recoverFromHistory(knownIds: Set<String>): List<Chat> {
        val me = myId
        if (historyProbedFor == me) return historyPeers.filterNot { it.id in knownIds }
        val usersResult = runCatching { api.allUsers().profiles }
        // A failed directory call must not burn the single attempt: try again next refresh.
        val everyone = usersResult.getOrDefault(emptyList())
        if (usersResult.isFailure) return emptyList()
        historyProbedFor = me
        val candidates = everyone.filter { profile ->
            profile.id.isNotEmpty() && profile.id != me && profile.id !in knownIds &&
                profile.id !in blockedIds()
        }
        if (candidates.isEmpty()) return emptyList()

        val found = coroutineScope {
            candidates.map { profile ->
                async {
                    val last = runCatching { api.conversation(profile.id, limit = 1).messages.lastOrNull() }
                        .getOrNull()
                    profile to last
                }
            }.awaitAll()
        }

        historyPeers = found.mapNotNull { (profile, dto) ->
            if (dto == null) return@mapNotNull null
            rememberName(profile.id, profile)
            Chat(
                id = profile.id,
                title = profile.displayName(),
                avatarUrl = profile.avatarUrl,
                isOnline = profile.isOnline,
                lastMessage = dto.previewText(),
                lastMessageAt = isoMillis(dto.createdAt),
                lastMessageKind = dto.kind(),
                lastMessageMine = dto.senderId == me,
                lastMessageState = if (dto.senderId == me) {
                    if (dto.isRead) DeliveryState.Read else DeliveryState.Sent
                } else {
                    null
                }
            )
        }
        return historyPeers.filterNot { it.id in knownIds }
    }

    private suspend fun loadConversation(chatId: String) {
        val isGroup = synchronized(conversations) { chatId in groupIds }
        val rows = runCatching {
            if (isGroup) api.groupMessages(chatId, limit = 100).messages
            else api.conversation(chatId, limit = 100).messages
        }.getOrDefault(emptyList())

        // Merge, never replace — the reference's `mergeMessages`. Swapping the whole list for
        // the server's snapshot is what made a message the user had just sent disappear again:
        // the fetch was answered *before* the send landed, so the pending bubble was thrown away
        // and the chat looked frozen until the next reload.
        val server = rows.map { it.toModel(chatId) }
        stream(chatId).update { local -> mergeMessages(local, server) }
    }

    /**
     * Local-first merge of two message lists, keyed by `clientMessageId || id` exactly like the
     * reference: the server's row wins (it has the real id, read state and reactions) while a
     * message only the client knows about — anything still in flight — is kept instead of being
     * silently dropped.
     */
    private fun mergeMessages(local: List<Message>, server: List<Message>): List<Message> {
        if (server.isEmpty()) return local
        val merged = LinkedHashMap<String, Message>()
        local.forEach { message -> merged[message.clientMessageId ?: message.id] = message }
        server.forEach { row ->
            val key = row.clientMessageId ?: row.id
            val pending = merged[key]
            merged[key] = if (pending == null || row.id.isNotEmpty()) {
                // Keep a locally known media URL when the server row carries none.
                row.copy(
                    mediaUrl = row.mediaUrl ?: pending?.mediaUrl,
                    thumbnailUrl = row.thumbnailUrl ?: pending?.thumbnailUrl,
                    failed = false
                )
            } else {
                pending
            }
        }
        return merged.values.sortedBy { it.timestamp }.takeLast(MESSAGE_PAGE)
    }

    // ---------------------------------------------------------- conversation

    override fun conversation(chatId: String, chatTitle: String): StateFlow<List<Message>> {
        val stream = stream(chatId)
        val first = synchronized(requested) { requested.add(chatId) }
        if (first) scope.launch {
            runCatching { loadConversation(chatId) }
            restorePending(chatId)
        }
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
        val localId = newClientId()
        appendLocal(
            chatId = chatId,
            message = baseMessage(chatId, localId, body).copy(
                replyAuthor = replyAuthor,
                replyText = replyText
            )
        )
        preview(chatId, body, DeliveryState.Sending)
        outbox?.add(
            OutboxEntry(
                clientId = localId,
                chatId = chatId,
                isGroup = isGroup(chatId),
                ownerId = myId,
                text = body
            )
        )
        dispatch(chatId, localId, body) { clientId ->
            if (isGroup(chatId)) {
                api.sendGroupMessage(chatId, body, clientMessageId = clientId, replyTo = null)
            } else {
                api.sendMessage(chatId, body, clientMessageId = clientId, replyTo = null)
            }
        }
    }

    override fun sendVoice(
        chatId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        durationLabel: String
    ) {
        val localId = newClientId()
        appendLocal(
            chatId = chatId,
            message = baseMessage(chatId, localId, "Voice message").copy(
                kind = MessageKind.Voice,
                durationLabel = durationLabel,
                fileName = fileName
            )
        )
        preview(chatId, "Voice message ($durationLabel)", DeliveryState.Sending)

        scope.launch {
            // Upload first: without a `media_url` the receiver gets an audio row with no file,
            // which is exactly the "it arrives but it is silent" bug.
            val media = runCatching { api.uploadMedia(fileName, mimeType, bytes) }.getOrElse { error ->
                // Say so. A silent failure here looked like "voice messages don't work": the
                // recording UI finished and no bubble ever became playable.
                markFailed(chatId, localId)
                NcToaster.show("Voice message not uploaded — ${error.describeSend()}")
                return@launch
            }
            // The sender can play their own clip straight away from the uploaded URL.
            replaceLocal(
                chatId,
                localId,
                baseMessage(chatId, localId, "Voice message").copy(
                    kind = MessageKind.Voice,
                    durationLabel = durationLabel,
                    fileName = fileName,
                    mediaUrl = media.url,
                    state = DeliveryState.Sending
                )
            )
            // `duration` travels as whole seconds, the same field the reference sends, so the
            // bubble shows the right length on either client.
            val seconds = durationSeconds(durationLabel)
            outbox?.add(
                OutboxEntry(
                    clientId = localId,
                    chatId = chatId,
                    isGroup = isGroup(chatId),
                    ownerId = myId,
                    text = "Voice message",
                    type = "voice",
                    mediaUrl = media.url,
                    duration = seconds,
                    fileName = fileName,
                    fileSize = media.fileSize?.toString()
                )
            )
            val created = sendWithRetry {
                // `content` carries the URL as well: the reference's `sendVoiceMessage` sends
                // only `content` (`ChatView.tsx:1911-1918`) and every client reads
                // `media_url || content`, so both fields are filled here.
                if (isGroup(chatId)) {
                    api.sendGroupMessage(
                        chatId,
                        media.url,
                        type = "voice",
                        clientMessageId = localId,
                        mediaUrl = media.url,
                        duration = seconds
                    )
                } else {
                    api.sendMessage(
                        chatId,
                        media.url,
                        type = "voice",
                        clientMessageId = localId,
                        mediaUrl = media.url,
                        duration = seconds,
                        fileName = fileName,
                        fileSize = media.fileSize?.toString()
                    )
                }
            }
            if (created == null) {
                markFailed(chatId, localId)
                return@launch
            }
            outbox?.remove(localId)
            replaceLocal(
                chatId,
                localId,
                created.toModel(chatId).copy(
                    // A server row that lost the URL still has to be playable locally.
                    mediaUrl = created.mediaUrl ?: media.url,
                    state = DeliveryState.Sent
                )
            )
            preview(chatId, "Voice message ($durationLabel)", DeliveryState.Sent)
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
        val localId = newClientId()
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
            val media = runCatching { api.uploadMedia(fileName, mimeType, bytes) }
                .getOrElse { error ->
                    markFailed(chatId, localId)
                    NcToaster.show("Upload failed — ${error.describeSend()}")
                    return@launch
                }
            val thumb = thumbnail?.let { preview ->
                // `thumb-clip.mp4` would be served back as `image/jpeg` bytes behind an .mp4
                // name, which confuses players and image loaders alike; give it a .jpg.
                val stem = fileName.substringBeforeLast('.', fileName)
                runCatching { api.uploadMedia("thumb-$stem.jpg", "image/jpeg", preview) }
                    .getOrNull()
                    ?.url
            }
            outbox?.add(
                OutboxEntry(
                    clientId = localId,
                    chatId = chatId,
                    isGroup = isGroup(chatId),
                    ownerId = myId,
                    text = body,
                    type = kind.wireType(),
                    mediaUrl = media.url,
                    thumbnailUrl = thumb,
                    fileName = fileName,
                    fileSize = media.fileSize?.toString()
                )
            )
            val created = sendWithRetry {
                // Same as voice: the URL goes in `content` too, so a client that only reads
                // `content` (the reference web app) still finds the file.
                val wireBody = media.url
                if (isGroup(chatId)) {
                    api.sendGroupMessage(
                        chatId,
                        wireBody,
                        type = kind.wireType(),
                        clientMessageId = localId,
                        mediaUrl = media.url,
                        thumbnailUrl = thumb,
                        duration = null
                    )
                } else {
                    api.sendMessage(
                        chatId,
                        wireBody,
                        type = kind.wireType(),
                        clientMessageId = localId,
                        mediaUrl = media.url,
                        thumbnailUrl = thumb,
                        fileName = fileName,
                        fileSize = media.fileSize?.toString()
                    )
                }
            }
            if (created == null) {
                markFailed(chatId, localId)
                return@launch
            }
            outbox?.remove(localId)
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
            val created = sendWithRetry { send(localId) }
            if (created == null) {
                markFailed(chatId, localId)
                return@launch
            }
            outbox?.remove(localId)
            replaceLocal(chatId, localId, created.toModel(chatId).copy(state = DeliveryState.Sent))
            preview(chatId, body, DeliveryState.Sent)
        }
    }

    /**
     * Three attempts with a growing pause — the reference's `retry({maxRetries: 3, backoff})`.
     *
     * Only transient answers are retried: a 4xx is the server saying no (bad receiver, blocked,
     * rate-limited for real), and repeating it just delays the warning the user needs to see.
     */
    private suspend fun sendWithRetry(
        send: suspend () -> com.nchat.app.network.MessageEnvelope?
    ): MessageDto? {
        var attempt = 0
        while (true) {
            val result = runCatching { send() }
            result.getOrNull()?.message?.let { return it }
            if (result.isSuccess) return null
            val error = result.exceptionOrNull()
            val transient = when (error) {
                is ApiException -> error.status >= 500 || error.status == 429
                is IOException -> true
                else -> true
            }
            attempt += 1
            if (!transient || attempt >= SEND_ATTEMPTS) return null
            delay(1_000L * attempt)
        }
    }

    /**
     * Sends everything still queued for this account.
     *
     * Safe to call at any time: the worker de-duplicates by `client_message_id`, so a message
     * that actually arrived but whose answer was lost is simply acknowledged, not duplicated.
     */
    fun flushOutbox() {
        val store = outbox ?: return
        val me = myId
        if (me.isEmpty()) return
        scope.launch {
            store.all().filter { it.ownerId == me || it.ownerId.isEmpty() }.forEach { entry -> deliver(entry) }
        }
    }

    private suspend fun deliver(entry: OutboxEntry) {
        val store = outbox ?: return
        // A queued media row is replayed exactly like the live send: the URL travels in
        // `content` as well as `media_url`.
        val content = entry.mediaUrl?.takeIf { it.isNotBlank() } ?: entry.text
        val response = runCatching {
            if (entry.isGroup) {
                api.sendGroupMessage(
                    entry.chatId,
                    content,
                    type = entry.type,
                    clientMessageId = entry.clientId,
                    replyTo = entry.replyTo,
                    mediaUrl = entry.mediaUrl,
                    thumbnailUrl = entry.thumbnailUrl,
                    duration = entry.duration
                )
            } else {
                api.sendMessage(
                    entry.chatId,
                    content,
                    type = entry.type,
                    clientMessageId = entry.clientId,
                    replyTo = entry.replyTo,
                    mediaUrl = entry.mediaUrl,
                    thumbnailUrl = entry.thumbnailUrl,
                    duration = entry.duration,
                    fileName = entry.fileName,
                    fileSize = entry.fileSize
                )
            }
        }
        val created = response.getOrNull()?.message
        if (created != null) {
            store.remove(entry.clientId)
            replaceLocal(entry.chatId, entry.clientId, created.toModel(entry.chatId).copy(state = DeliveryState.Sent))
            preview(entry.chatId, entry.text, DeliveryState.Sent)
            return
        }
        if (store.bumpAttempts(entry.clientId) >= MAX_OUTBOX_ATTEMPTS) {
            store.remove(entry.clientId)
            markFailed(entry.chatId, entry.clientId)
        }
    }

    /** Puts queued messages back on screen when a conversation is opened. */
    private fun restorePending(chatId: String) {
        val store = outbox ?: return
        val pending = store.all().filter { it.chatId == chatId && (it.ownerId == myId || it.ownerId.isEmpty()) }
        if (pending.isEmpty()) return
        val known = stream(chatId).value.mapNotNull { it.clientMessageId }.toSet()
        val restored = pending.filterNot { it.clientId in known }.map { entry ->
            Message(
                id = entry.clientId,
                chatId = chatId,
                authorId = myId,
                authorName = myName,
                text = entry.text,
                timestamp = entry.createdAt,
                isMine = true,
                kind = kindOf(entry.type),
                state = DeliveryState.Sending,
                durationLabel = durationLabelOf(entry.duration),
                fileName = entry.fileName,
                mediaUrl = entry.mediaUrl,
                clientMessageId = entry.clientId
            )
        }
        if (restored.isNotEmpty()) {
            updateMessages(chatId) { messages -> (messages + restored).sortedBy { it.timestamp } }
        }
    }

    /**
     * True while a bubble exists only on this device.
     *
     * A pending message's `id` *is* the id we minted for the send (`clientMessageId`); once the
     * server answers, the bubble carries the server's id and the client id separately. Edit /
     * react / delete must not be sent to the API before that.
     */
    private fun isPending(chatId: String, messageId: String): Boolean {
        val message = stream(chatId).value.firstOrNull { it.id == messageId } ?: return true
        return message.clientMessageId == message.id
    }

    /** Marks a pending bubble as failed and says so — a stuck clock with no message is the bug. */
    private fun markFailed(chatId: String, localId: String) {
        updateMessages(chatId) { messages ->
            messages.map { if (it.id == localId) it.copy(failed = true) else it }
        }
        NcToaster.show("Message not sent — tap the warning on it to try again")
    }

    /**
     * Reconciles our own message coming back over the socket with the bubble already on screen.
     *
     * Keyed by `client_message_id`, so the echo replaces the pending row instead of appearing as
     * a second copy — and a send whose HTTP answer was lost still ends up looking delivered.
     */
    private fun reconcileOwn(chatId: String, dto: MessageDto) {
        if (chatId.isEmpty()) return
        dto.clientMessageId?.let { outbox?.remove(it) }
        val model = dto.toModel(chatId).copy(state = DeliveryState.Sent, failed = false)
        val key = dto.clientMessageId
        updateMessages(chatId) { messages ->
            val index = messages.indexOfFirst { message ->
                message.id == dto.id || (key != null && message.clientMessageId == key)
            }
            when {
                index >= 0 -> messages.toMutableList().also { it[index] = model }.sortedBy { it.timestamp }
                else -> (messages + model).sortedBy { it.timestamp }
            }
        }
        preview(chatId, model.text, DeliveryState.Sent)
    }

    /**
     * Resend a bubble the server never accepted.
     *
     * Voice and media already uploaded keep their URL, so the retry is just the message again;
     * a clip whose *upload* failed has no file to point at and is reported instead of pretending.
     */
    override fun retrySend(chatId: String, messageId: String) {
        val message = stream(chatId).value.firstOrNull { it.id == messageId } ?: return
        if (!message.failed) return
        val localId = message.id
        val resendUrl = message.mediaUrl
        updateMessages(chatId) { list -> list.map { if (it.id == localId) it.copy(failed = false) else it } }
        // A manual retry of an already-uploaded message puts it back in the queue too, so a
        // second failure still gets the automatic flush.
        if (resendUrl != null) {
            outbox?.add(
                OutboxEntry(
                    clientId = localId,
                    chatId = chatId,
                    isGroup = isGroup(chatId),
                    ownerId = myId,
                    text = message.text,
                    type = message.kind.wireType(),
                    mediaUrl = resendUrl,
                    thumbnailUrl = message.thumbnailUrl,
                    duration = message.durationLabel?.let { durationSeconds(it) },
                    fileName = message.fileName
                )
            )
        }
        scope.launch {
            val created = sendWithRetry {
                when {
                    resendUrl != null -> api.sendMessage(
                        chatId,
                        message.text,
                        type = message.kind.wireType(),
                        clientMessageId = localId,
                        mediaUrl = resendUrl,
                        thumbnailUrl = message.thumbnailUrl,
                        duration = message.durationLabel?.let { durationSeconds(it) },
                        fileName = message.fileName
                    )
                    message.kind == MessageKind.Voice || message.kind == MessageKind.Document -> {
                        // Nothing was uploaded, so there is nothing to re-send.
                        markFailed(chatId, localId)
                        return@sendWithRetry null
                    }
                    isGroup(chatId) -> api.sendGroupMessage(
                        chatId,
                        message.text,
                        type = message.kind.wireType(),
                        clientMessageId = localId
                    )
                    else -> api.sendMessage(chatId, message.text, clientMessageId = localId)
                }
            }
            if (created == null) {
                if (stream(chatId).value.firstOrNull { it.id == localId }?.failed != true) markFailed(chatId, localId)
                return@launch
            }
            outbox?.remove(localId)
            replaceLocal(chatId, localId, created.toModel(chatId).copy(state = DeliveryState.Sent))
            preview(chatId, message.text, DeliveryState.Sent)
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
        if (isPending(chatId, messageId)) return
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
        if (isPending(chatId, messageId)) return
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
        if (isPending(chatId, messageId)) return
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
            id = newClientId(),
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

    override fun removeMember(groupId: String, userId: String) {
        scope.launch {
            runCatching { api.removeGroupMember(groupId, userId) }
            refresh()
        }
    }

    override fun setMemberRole(groupId: String, userId: String, role: String) {
        scope.launch {
            runCatching { api.setMemberRole(groupId, userId, role) }
            refresh()
        }
    }

    override fun deleteGroup(groupId: String) {
        _chats.update { list -> list.filterNot { it.id == groupId } }
        _groups.update { list -> list.filterNot { it.id == groupId } }
        synchronized(conversations) {
            conversations.remove(groupId)
            groupIds.remove(groupId)
        }
        scope.launch {
            runCatching { api.deleteGroup(groupId) }
            refresh()
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
                // Our own message coming back over the socket is the server's acknowledgement
                // (the worker broadcasts to both participants). The reference uses exactly this
                // to flip a pending bubble to sent, so a lost or slow HTTP answer can no longer
                // leave the message stuck on the clock.
                if (dto.senderId == myId) {
                    reconcileOwn(dto.receiverId.orEmpty(), dto)
                    return
                }
                // A blocked contact's messages are not shown while the block lasts. Nothing is
                // deleted — the message stays on the server and is there again after Unblock.
                if (dto.senderId in blockedIds()) return
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

            "group-message:read" -> {
                // Somebody in the group read up to `read_at`: flip my own ticks past that point.
                val groupId = event.string("group_id") ?: return
                val reader = event.string("reader_id").orEmpty()
                if (reader == myId) return
                updateMessages(groupId) { messages ->
                    messages.map { if (it.isMine) it.copy(state = DeliveryState.Read) else it }
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
                val chatId = dto.groupId ?: return
                if (dto.senderId == myId) {
                    reconcileOwn(chatId, dto)
                    return
                }
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
        state = DeliveryState.Sending,
        clientMessageId = id
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
            if (list.none { it.id == chatId }) {
                // A message from somebody the server does not list as a friend still deserves
                // a row — `/messages/chats` only knows friends, so without this an incoming
                // chat from anyone else never appeared in the list at all.
                val isGroupChat = synchronized(conversations) { chatId in groupIds }
                list + Chat(
                    id = chatId,
                    title = nameOf(chatId),
                    isGroup = isGroupChat,
                    lastMessage = dto.previewText(),
                    lastMessageAt = isoMillis(dto.createdAt),
                    lastMessageKind = dto.kind(),
                    unreadCount = 1
                )
            } else {
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

        // Newly created rows have no profile yet; fetch one so the row is not "NChat user".
        if (names[chatId] == null) {
            scope.launch {
                val profile = runCatching { api.user(chatId).profile }.getOrNull() ?: return@launch
                rememberName(chatId, profile)
                _chats.update { list ->
                    list.map { chat ->
                        if (chat.id != chatId) chat
                        else chat.copy(
                            title = profile.displayName(),
                            avatarUrl = profile.avatarUrl
                        )
                    }
                }
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

    private fun GroupDto.toGroup(): Group {
        val roster = members.map { member ->
            GroupMember(
                userId = member.userId,
                name = member.profile?.let { it.fullName ?: it.username ?: it.email ?: "Member" }
                    ?: "Member",
                avatarUrl = member.profile?.avatarUrl,
                role = member.role,
                isMuted = member.isMuted
            )
        }
        return Group(
            id = id,
            name = name,
            description = description.orEmpty(),
            memberCount = if (roster.isEmpty()) 1 else roster.size,
            unreadCount = unreadCount,
            avatarEmoji = "👥",
            lastActivity = lastMessage?.previewText().orEmpty(),
            lastActivityAt = isoMillis(lastMessage?.createdAt ?: createdAt),
            // `/groups` already returns every member with their profile; the UI used to
            // ignore it and guess the roster from the friend list instead.
            members = roster,
            createdBy = createdBy,
            myRole = members.firstOrNull { it.userId == myId }?.role
        )
    }

    private fun MessageDto.toModel(chatId: String): Message {
        val mine = senderId == myId
        val kind = kind()
        /*
         * Where the file lives.
         *
         * The reference front end never stores a `media_url`: `handleFileUpload` and
         * `sendVoiceMessage` put the uploaded URL in **`content`** and send the type alongside it
         * (`ChatView.tsx:1449-1455`, `:1911-1918`), so every bubble it renders reads
         * `message.media_url || message.content` (`ChatView.tsx:432`, `:2184`, `:2194`).
         *
         * This client writes `media_url` *and* `content`, but a photo / video / voice note that
         * came from the web app carries the URL only in `content`. Taking `media_url` alone left
         * `mediaUrl` null for exactly those messages, which is what painted the empty gradient
         * tile for their photos, a poster-less video and a voice note with nothing to play.
         */
        val media = mediaUrl?.takeIf { it.isNotBlank() }
            ?: content.trim().takeIf { kind != MessageKind.Text && it.isRemoteUrl() }
        // When `content` *is* the URL there is no caption to print under the bubble.
        val caption = when {
            isDeleted -> "This message was deleted"
            media != null && content.trim() == media -> ""
            else -> content
        }
        return Message(
            id = id,
            chatId = chatId,
            authorId = senderId,
            authorName = if (mine) myName else nameOf(senderId),
            text = caption,
            timestamp = isoMillis(createdAt),
            isMine = mine,
            kind = kind,
            state = when {
                !mine -> DeliveryState.Read
                isRead -> DeliveryState.Read
                else -> DeliveryState.Sent
            },
            durationLabel = durationLabelOf(duration).takeIf { kind == MessageKind.Voice } ?: duration,
            fileName = fileName,
            mediaUrl = media,
            thumbnailUrl = thumbnailUrl,
            reactions = reactions.toModel(),
            replyText = null,
            isEdited = isEdited,
            clientMessageId = clientMessageId,
            deleted = isDeleted
        )
    }

    private fun kindOf(type: String): MessageKind = when (type.lowercase()) {
        "image", "photo" -> MessageKind.Photo
        "video" -> MessageKind.Video
        "audio", "voice" -> MessageKind.Voice
        "document", "file" -> MessageKind.Document
        "location" -> MessageKind.Location
        else -> MessageKind.Text
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

    /** One short sentence for a toast: what the server said, or why the network did not. */
    private fun Throwable.describeSend(): String = when (this) {
        is ApiException -> "server said $status: $message"
        is IOException -> "no connection"
        else -> message ?: javaClass.simpleName
    }

    /** `02:07` → `"127"`. The wire carries seconds, like the reference's `duration`. */
    private fun durationSeconds(label: String): String {
        val parts = label.split(":")
        val seconds = if (parts.size == 2) {
            (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
        } else {
            parts.lastOrNull()?.toIntOrNull() ?: 0
        }
        return seconds.toString()
    }

    /** Wire seconds → the `m:ss` label the bubble prints. */
    private fun durationLabelOf(value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (value.contains(':')) return value
        val seconds = value.toIntOrNull() ?: return value
        return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
    }

    /** Whether this string is something a media player / image loader can actually open. */
    private fun String.isRemoteUrl(): Boolean =
        startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true) ||
            startsWith("file://", ignoreCase = true) ||
            startsWith("content://", ignoreCase = true)

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

    /** Human summary of a failed call, for the banner above the list. */
    private fun Result<*>.describe(): String {
        val error = exceptionOrNull() ?: return "unknown error"
        return when (error) {
            is com.nchat.app.network.ApiException -> "server said ${error.status}: ${error.message}"
            else -> error.message ?: error.javaClass.simpleName
        }
    }

    private companion object {
        /** Messages kept per conversation in memory; matches the page the server returns. */
        const val MESSAGE_PAGE = 100
        const val SEND_ATTEMPTS = 3
        /** The reference gives up on a queued message after five tries. */
        const val MAX_OUTBOX_ATTEMPTS = 5

        val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
