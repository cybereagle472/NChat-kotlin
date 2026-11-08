package com.nchat.app.data.repository

import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.data.local.ChatListCache
import com.nchat.app.data.local.GroupListCache
import com.nchat.app.data.local.MessageStore
import com.nchat.app.data.local.OutboxStore
import com.nchat.app.data.local.VoiceClipStore
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
import com.nchat.app.network.MediaDto
import com.nchat.app.network.MessageDto
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NcJson
import com.nchat.app.network.ConnectionState
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.RealtimeEvent
import com.nchat.app.network.TokenStore
import com.nchat.app.network.UserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
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
    private val outbox: OutboxStore? = null,
    /**
     * Conversations on disk — see [MessageStore].
     *
     * The app used to keep messages in memory only, so a restart (or a cold start with no
     * network) opened every chat empty. Now each conversation paints from the device first and
     * the server's answer is merged on top, exactly like the chat list and the friend graph.
     */
    private val messages: MessageStore? = null,
    /**
     * Whether the device has a usable connection right now (`NetworkMonitor`).
     *
     * Used to decide between "this failed" and "this is waiting for a network": a send made in
     * a tunnel or in airplane mode is queued, keeps its clock, and goes out the moment the
     * socket comes back — it is never shown as an error.
     */
    private val online: () -> Boolean = { true },
    /** Last known group list (with rosters), painted before the network answers. */
    private val groupCache: GroupListCache? = null,
    /**
     * Voice clips waiting to be uploaded — a recording made offline is queued with its audio
     * instead of dying with the failed request. See [VoiceClipStore].
     */
    private val voiceClips: VoiceClipStore? = null,
    /**
     * A message from somebody else has just landed (socket event). The platform layer turns this
     * into a system notification; the repository stays free of Android notification code.
     */
    private val onIncoming: ((Chat, Message) -> Unit)? = null
) : ChatRepository {


    /** How long a received "typing" indicator survives without a refresh (WhatsApp-like). */
    private val TYPING_TTL_MS = 6_000L

    /** How many messages a conversation keeps in memory; matches the server's page size. */
    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private val conversations = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    /**
     * Whether the last `/messages/chats` + `/groups` answer was complete.
     *
     * The list is mirrored to disk whenever it changes, which would otherwise persist the
     * half-list a failed refresh produces (chats without groups, or groups without chats) and
     * *delete* the cached rows of whichever endpoint failed. Only a complete answer makes the
     * mirror trustworthy.
     */
    @Volatile private var chatsTrusted = false
    @Volatile private var groupsTrusted = false

    /** Pending debounced list writes — see [mirrorLists]. */
    private var chatListWrite: Job? = null
    private var groupListWrite: Job? = null

    /** Pending debounced disk writes, one per conversation — see [mirrorToDisk]. */
    private val writeJobs = mutableMapOf<String, Job>()
    /** The flow observers that trigger those writes, so a forgotten chat stops observing. */
    private val mirrorJobs = mutableMapOf<String, Job>()
    private val requested = mutableSetOf<String>()
    private val groupIds = mutableSetOf<String>()
    private val names = mutableMapOf<String, String>()
    private val typingJobs = mutableMapOf<String, Job>()
    private val deliveryGate = DeliveryGate()

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
        /*
         * Group ids from the cached list, before `/groups` has answered.
         *
         * `isGroup()` decides which endpoint a conversation is read from and whether a queued
         * send goes out as a group message, and it used to be filled only by the network load.
         * Offline at start-up that meant a group chat was treated as a 1:1 chat — the wrong
         * URL, the wrong outbox target — even though the chat itself was on screen from cache.
         */
        /*
         * Groups from disk, for the same reason as the chat list: the tab is a list of things
         * this account already belongs to, and an offline launch must not claim otherwise.
         * Their rosters come with them, which is also what names the authors of group bubbles
         * before `/groups` has answered.
         */
        val cachedGroups = groupCache?.load().orEmpty()
        if (cachedGroups.isNotEmpty()) {
            _groups.value = cachedGroups
            cachedGroups.forEach { group ->
                group.members.forEach { member -> names[member.userId] = member.name }
            }
        }
        synchronized(conversations) {
            groupIds += _chats.value.filter { it.isGroup }.map { it.id }
            groupIds += cachedGroups.map { it.id }
        }
        mirrorLists()
        /*
         * Voice clips left over from a previous run that nothing is waiting for any more —
         * a send that succeeded just before the process died, or one whose bubble the user
         * deleted. Anything the outbox still points at stays; the rest is dead weight on a
         * phone's internal storage.
         */
        scope.launch(Dispatchers.IO) {
            runCatching {
                val keep = outbox?.all().orEmpty().mapNotNull { it.localFile }.toSet()
                voiceClips?.prune(keep)
            }
        }
        scope.launch { socket.events.collect { event -> handleEvent(event) } }
        // Signing out must not leave the previous account's threads in memory: after a new
        // sign-in on the same process they would be "kept" by the merge above and shown to
        // somebody who never had them.
        // Anything left in the outbox from the last run goes out now, and again every time the
        // realtime socket comes back up — the reference does the same on reconnect.
        scope.launch {
            socket.state.collect { state ->
                if (state != ConnectionState.Connected) return@collect
                flushOutbox()
                resyncAfterReconnect()
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
                synchronized(writeJobs) { writeJobs.values.forEach { it.cancel() }; writeJobs.clear() }
                synchronized(mirrorJobs) { mirrorJobs.values.forEach { it.cancel() }; mirrorJobs.clear() }
                chatListWrite?.cancel()
                groupListWrite?.cancel()
                outbox?.clear()
                // The next account must not open a conversation, a chat list or a group and
                // find the previous one's.
                messages?.clearAll()
                cache?.clear()
                groupCache?.clear()
            }
        }
        refresh()
    }

    private val _syncError = MutableStateFlow<String?>(null)
    override val syncError: StateFlow<String?> = _syncError.asStateFlow()

    /**
     * Catches up after the realtime socket was away.
     *
     * A WebSocket is not a mailbox: anything the server broadcast while the connection was
     * down is gone for good. Every messenger solves this the same way — on reconnect, re-read
     * the list and the open conversations — and without it a dropped socket silently cost the
     * user every message sent during the gap until they restarted the app. The first connect
     * of a process is skipped because the initial load already covers it.
     */
    private fun resyncAfterReconnect() {
        if (!hasConnectedBefore) {
            hasConnectedBefore = true
            return
        }
        scope.launch {
            runCatching { reload() }
            val open = synchronized(requested) { requested.toList() }
            open.forEach { chatId -> runCatching { loadConversation(chatId) } }
        }
    }

    /** True once this process has seen the socket come up; see [resyncAfterReconnect]. */
    @Volatile
    private var hasConnectedBefore = false

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

        // Only a complete answer may be written to the list caches — see [chatsTrusted].
        // Set before the flows below are updated, so their emissions are the ones persisted.
        chatsTrusted = directResult.isSuccess && groupResult.isSuccess
        groupsTrusted = groupResult.isSuccess

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

        /*
         * A message that is still queued — or one the server refused — is newer than anything
         * `/messages/chats` can know about, because it has not been delivered. Re-derive those
         * rows from the conversation, or every refresh put the tick back on a send that never
         * happened and reset the row to the previous message.
         */
        val queuedChats = outbox?.all()?.map { it.chatId }?.toSet().orEmpty()
        val stalePreviews = queuedChats.filter { id ->
            val messages = stream(id).value
            messages.isNotEmpty() && messages.any { it.failed || isPending(id, it.id) }
        }
        if (stalePreviews.isNotEmpty()) {
            stalePreviews.forEach { previewFromLast(it) }
            _chats.update { list -> list.sortedByDescending { it.lastMessageAt } }
        }

        // Only persist a complete answer. Saving a half-failed refresh used to overwrite the
        // cache with just the groups, so one bad `/messages/chats` and every 1:1 row was gone
        // from disk for good.
        if (directResult.isSuccess && groupResult.isSuccess) cache?.save(_chats.value)

        // Then, and only after the real list is on screen, look for conversations the server's
        // friendship view forgot. Runs once per process: every peer it finds lands in the list
        // (and in the cache), so the next refresh already knows about them.
        val covered = merged.map { it.id }.toSet() + synchronized(conversations) { groupIds.toSet() }
        val recovered = recoverFromHistory(covered)
        if (recovered.isNotEmpty()) {
            // Append to what is on screen, not to the server's answer: the queued-message
            // previews above were derived from the conversations and must survive recovery.
            val withHistory = (_chats.value + recovered)
                .distinctBy { it.id }
                .sortedByDescending { it.lastMessageAt }
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
                lastMessage = previewOf(dto, profile.id, isGroup = false),
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
        /*
         * `deleted_for` is filtered here as well as on the server.
         *
         * `GET /messages/with/:id` already drops them, but `GET /groups/:id/messages` does not
         * (`worker/src/routes/groups.ts:370-391` maps every row), so a group message deleted
         * "for me" came back on the next refresh — and stayed back, because the client had
         * nothing left to tell it apart.
         */
        val me = myId
        val server = rows.filterNot { me.isNotEmpty() && me in it.deletedFor }.map { it.toModel(chatId) }
        updateMessages(chatId) { local -> mergeMessages(local, server) }
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
            /*
             * Disk first, network second.
             *
             * The order is the whole point: `restoreFromDisk` fills the screen from the last
             * session before a single byte goes over the wire, so opening a chat is instant and
             * an offline open is a normal conversation instead of an empty one. The fetch below
             * then merges the server's rows on top (it never replaces local content).
             */
            runCatching { restoreFromDisk(chatId) }
            runCatching { loadConversation(chatId) }
            restorePending(chatId)
        }
        return stream.asStateFlow()
    }

    /** Paints a conversation from the device, so it opens with history instead of a spinner. */
    private suspend fun restoreFromDisk(chatId: String) {
        val store = messages ?: return
        val owner = tokens.userId.orEmpty()
        if (owner.isBlank() || chatId.isBlank()) return
        val cached = withContext(Dispatchers.IO) {
            runCatching { store.load(owner, chatId) }.getOrDefault(emptyList())
        }
        if (cached.isEmpty()) return
        updateMessages(chatId) { local ->
            if (local.isEmpty()) {
                cached
            } else {
                // Anything already on screen (a socket frame that beat the disk read) wins.
                (local + cached)
                    .distinctBy { it.clientMessageId ?: it.id }
                    .sortedBy { it.timestamp }
                    .takeLast(MESSAGE_PAGE)
            }
        }
    }

    /**
     * Writes a conversation to disk whenever it changes, debounced.
     *
     * Every mutation in this file goes through the state flow returned by [stream] (send,
     * socket frame, read receipt, edit, reaction, delete), so observing the flow is the one
     * place that cannot miss a change — and the one place that does not have to be repeated at
     * twenty call sites. The debounce keeps a burst (a whole refresh, a typing-then-send) to a
     * single file write, and the write itself is atomic inside [MessageStore].
     */
    private fun mirrorToDisk(chatId: String, flow: MutableStateFlow<List<Message>>) {
        val store = messages ?: return
        val job = scope.launch {
            flow.collect { rows ->
                if (rows.isEmpty()) return@collect
                val owner = tokens.userId.orEmpty()
                if (owner.isBlank()) return@collect
                val snapshot = rows
                synchronized(writeJobs) {
                    writeJobs.remove(chatId)?.cancel()
                    writeJobs[chatId] = scope.launch {
                        delay(PERSIST_DELAY_MS)
                        withContext(Dispatchers.IO) {
                            runCatching { store.save(owner, chatId, snapshot) }
                        }
                    }
                }
            }
        }
        synchronized(mirrorJobs) { mirrorJobs.remove(chatId)?.cancel(); mirrorJobs[chatId] = job }
    }

    /**
     * Keeps the chat list and the group list on disk, debounced.
     *
     * The caches used to be written in exactly one place: the end of a successful `reload()`.
     * Everything that changes a list between reloads — a message arriving on the socket, a
     * group renamed, a chat pinned, a chat deleted, a conversation started with somebody the
     * server's friend view does not list — lived in memory only, so killing the app rolled the
     * list back to the last reload. Observed from the flows, no change can be missed, and the
     * debounce keeps a burst to one write.
     *
     * [chatsTrusted] / [groupsTrusted] are what keep this honest: a failed refresh produces a
     * partial list, and writing that would delete the cached half of whichever endpoint did
     * answer.
     */
    private fun mirrorLists() {
        val chatStore = cache
        if (chatStore != null) {
            scope.launch {
                _chats.collect { rows ->
                    if (rows.isEmpty() || !chatsTrusted) return@collect
                    val snapshot = rows
                    chatListWrite?.cancel()
                    chatListWrite = scope.launch {
                        delay(LIST_SAVE_DELAY_MS)
                        runCatching { chatStore.save(snapshot) }
                    }
                }
            }
        }
        val groupStore = groupCache
        if (groupStore != null) {
            scope.launch {
                _groups.collect { rows ->
                    if (rows.isEmpty() || !groupsTrusted) return@collect
                    val snapshot = rows
                    groupListWrite?.cancel()
                    groupListWrite = scope.launch {
                        delay(LIST_SAVE_DELAY_MS)
                        runCatching { groupStore.save(snapshot) }
                    }
                }
            }
        }
    }

    /** Drops one conversation from disk (delete chat, leave/delete group). */
    private fun forgetOnDisk(chatId: String) {
        val store = messages ?: return
        val owner = tokens.userId.orEmpty()
        if (owner.isBlank()) return
        synchronized(writeJobs) { writeJobs.remove(chatId)?.cancel() }
        synchronized(mirrorJobs) { mirrorJobs.remove(chatId)?.cancel() }
        scope.launch(Dispatchers.IO) { runCatching { store.clear(owner, chatId) } }
    }

    override fun refreshConversation(chatId: String) {
        scope.launch { runCatching { loadConversation(chatId) } }
    }

    private fun stream(chatId: String): MutableStateFlow<List<Message>> =
        synchronized(conversations) {
            conversations.getOrPut(chatId) {
                // Explicit type argument: `.also {}` breaks the expected-type inference that
                // `getOrPut` would otherwise give `emptyList()`.
                MutableStateFlow<List<Message>>(emptyList()).also { flow -> mirrorToDisk(chatId, flow) }
            }
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
        replyText: String?,
        replyToId: String?
    ) {
        val body = text.trim()
        if (body.isEmpty()) return
        val localId = newClientId()
        val local = baseMessage(chatId, localId, body).copy(
            replyToId = replyToId,
            replyAuthor = replyAuthor,
            replyText = replyText
        )
        appendLocal(chatId, local)
        preview(chatId, local, DeliveryState.Sending)
        if (!enqueue(
            OutboxEntry(
                clientId = localId,
                chatId = chatId,
                isGroup = isGroup(chatId),
                ownerId = myId,
                text = body,
                replyTo = replyToId
            )
        )) return
        dispatch(chatId, localId) { clientId ->
            if (isGroup(chatId)) {
                api.sendGroupMessage(chatId, body, clientMessageId = clientId, replyTo = replyToId)
            } else {
                api.sendMessage(chatId, body, clientMessageId = clientId, replyTo = replyToId)
            }
        }
    }

    override fun sendLocation(chatId: String, latitude: Double, longitude: Double) {
        val coords = String.format(java.util.Locale.US, "%.6f,%.6f", latitude, longitude)
        val localId = newClientId()
        val local = baseMessage(chatId, localId, coords).copy(kind = MessageKind.Location)
        appendLocal(chatId, local)
        preview(chatId, local, DeliveryState.Sending)
        // Queued like any other message: sharing a position in a tunnel must not be lost.
        if (!enqueue(
            OutboxEntry(
                clientId = localId,
                chatId = chatId,
                isGroup = isGroup(chatId),
                ownerId = myId,
                text = coords,
                type = "location"
            )
        )) return
        dispatch(chatId, localId) { clientId ->
            if (isGroup(chatId)) {
                api.sendGroupMessage(chatId, coords, type = "location", clientMessageId = clientId)
            } else {
                api.sendMessage(chatId, coords, type = "location", clientMessageId = clientId)
            }
        }
    }

    /**
     * A recorded voice note: on disk first, then uploaded, then posted.
     *
     * The old version uploaded before anything was queued, so the only way a voice note could
     * fail was *before* it existed anywhere but memory: in a tunnel the recording was gone, the
     * bubble was red, and the user had to hold the mic again. Now the clip is written to
     * `filesDir/voice-out`, the outbox entry carries its path, and [deliver] uploads it when the
     * phone has a network — which is also what lets the bubble play the pending clip offline.
     */
    override fun sendVoice(
        chatId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        durationLabel: String,
        replyAuthor: String?,
        replyText: String?,
        waveform: String?,
        replyToId: String?
    ) {
        if (bytes.isEmpty()) return
        val localId = newClientId()
        val localFile = voiceClips?.save(localId, bytes, extensionFor(mimeType, fileName))
        // A pending clip is playable by its own author straight away (`MediaPlayer` reads a
        // `file://` path exactly like an https one); the uploaded URL replaces it below.
        val localUri = localFile?.let { "file://$it" }
        val local = baseMessage(chatId, localId, "Voice message").copy(
            kind = MessageKind.Voice,
            durationLabel = durationLabel,
            fileName = fileName,
            // The reference keeps the waveform in `thumbnail_url` (`ChatView.tsx:37-60`),
            // so a voice note recorded here draws a real waveform there too.
            thumbnailUrl = waveform,
            mediaUrl = localUri,
            replyToId = replyToId,
            replyAuthor = replyAuthor,
            replyText = replyText
        )
        appendLocal(chatId, local)
        preview(chatId, local, DeliveryState.Sending)

        val entry = OutboxEntry(
            clientId = localId,
            chatId = chatId,
            isGroup = isGroup(chatId),
            ownerId = myId,
            text = "Voice message",
            // `voice`, not `audio`: the web client only recognises `message.type === 'voice'`
            // (`ChatView.tsx:130`, `:2207`), so an `audio` row arrives there as a text bubble
            // showing the raw URL.
            type = "voice",
            duration = durationSeconds(durationLabel),
            fileName = fileName,
            thumbnailUrl = waveform,
            localFile = localFile,
            mimeType = mimeType,
            replyTo = replyToId
        )
        if (localFile == null) {
            markFailed(chatId, localId, "Couldn't save the recording on this device")
            return
        }
        if (!enqueue(entry)) return
        // Offline is not a failure — the entry above *is* the send, and `flushOutbox()` (on the
        // way back up) finishes it. The bubble keeps its clock, exactly like a queued text.
        if (!online()) return
        scope.launch { deliver(entry) }
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
        val local = baseMessage(chatId, localId, body).copy(
            kind = kind,
            fileName = fileName,
            // The bubble carries a ring from the first byte, so a photo on a slow link looks
            // like something that is happening instead of a message stuck on its clock.
            uploadProgress = 0f
        )
        appendLocal(chatId, local)
        preview(chatId, local, DeliveryState.Sending)

        scope.launch {
            if (!deliveryGate.tryAcquire(localId)) return@launch
            try {
                if (stream(chatId).value.none { it.id == localId }) return@launch
                // 1) upload the file (and its preview), 2) send a message that points at both.
                val media = runCatching {
                    api.uploadMedia(fileName, mimeType, bytes) { fraction ->
                        setUploadProgress(chatId, localId, fraction)
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    setUploadProgress(chatId, localId, null)
                    markFailed(chatId, localId)
                    NcToaster.show("Upload failed — ${error.describeSend()}")
                    return@launch
                }
                // The file is on the server; what is left is a small JSON post, not worth a ring.
                //
                // The bubble switches to the uploaded file immediately instead of waiting for the
                // message row: the sender sees their photo the moment it lands, the same second the
                // ring disappears, rather than a grey gradient until the acknowledgement arrives.
                setUploadProgress(chatId, localId, null)
                if (media.url.isNotBlank()) {
                    updateMessages(chatId) { messages ->
                        messages.map {
                            if (it.id == localId) it.copy(mediaUrl = media.url) else it
                        }
                    }
                }
                val thumb = thumbnail?.let { preview ->
                    // `thumb-clip.mp4` would be served back as `image/jpeg` bytes behind an .mp4
                    // name, which confuses players and image loaders alike; give it a .jpg.
                    val stem = fileName.substringBeforeLast('.', fileName)
                    val result = runCatching { api.uploadMedia("thumb-$stem.jpg", "image/jpeg", preview) }
                    val error = result.exceptionOrNull()
                    if (error is CancellationException) throw error
                    result.getOrNull()?.url
                }
                if (!enqueue(
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
                )) return@launch
                var failure: Throwable? = null
                val created = sendWithRetry(onFailure = { failure = it }) {
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
                    val transient = isTransientSendFailure(failure)
                    if (transient && outbox != null) return@launch
                    if (!transient) failAndDequeue(chatId, localId, failure)
                    else markFailed(chatId, localId, failure?.describeSend())
                    return@launch
                }
                outbox?.remove(localId)
                replaceLocal(chatId, localId, created.toModel(chatId).copy(state = DeliveryState.Sent))
                previewFromLast(chatId)
            } finally {
                deliveryGate.release(localId)
            }
        }
    }

    /**
     * Forwards a message.
     *
     * Text used to be the only thing that survived: a forwarded photo arrived as an empty
     * bubble, a forwarded voice note as the word "Voice message", because this copied
     * `source.text` and called it a day. The reference re-sends `content`, `type`, `media_url`,
     * `thumbnail_url`, `fileName` and `duration` untouched (`chatService.forwardToUsers`,
     * `:697-737`), which is the only way the recipient gets a photo and not a caption.
     */
    override fun forwardMessage(targetChatId: String, source: Message) {
        // A tombstone has nothing to forward — it would arrive as the words "This message was
        // deleted", which is the one thing forwarding must never do.
        if (source.deleted) return
        val media = source.mediaUrl?.takeIf { it.isRemoteUrl() }
        if (source.kind != MessageKind.Text && media == null) {
            // The file is not on the server (a clip whose upload never finished): forward what
            // can be forwarded instead of a bubble with nothing behind it.
            sendMessage(targetChatId, source.snippet().ifBlank { "Forwarded message" })
            return
        }
        val localId = newClientId()
        val local = baseMessage(targetChatId, localId, source.text).copy(
            kind = source.kind,
            fileName = source.fileName,
            mediaUrl = media,
            thumbnailUrl = source.thumbnailUrl,
            durationLabel = source.durationLabel,
            forwarded = true
        )
        appendLocal(targetChatId, local)
        preview(targetChatId, local, DeliveryState.Sending)
        val entry = OutboxEntry(
            clientId = localId,
            chatId = targetChatId,
            isGroup = isGroup(targetChatId),
            ownerId = myId,
            text = source.text,
            type = source.kind.wireType(),
            mediaUrl = media,
            thumbnailUrl = source.thumbnailUrl,
            duration = source.durationLabel?.let { durationSeconds(it) },
            fileName = source.fileName
        )
        if (!enqueue(entry)) return
        if (!online()) return
        scope.launch { deliver(entry) }
    }

    /** Persist before dispatch; never show an unsaved entry as safely queued. */
    private fun enqueue(entry: OutboxEntry): Boolean {
        if (outbox != null && !outbox.add(entry)) {
            markFailed(entry.chatId, entry.clientId, "Couldn't save the message on this device")
            return false
        }
        return true
    }

    /** Sends the queued row, then swaps the optimistic bubble for the server's. */
    private fun dispatch(
        chatId: String,
        localId: String,
        send: suspend (clientId: String) -> com.nchat.app.network.MessageEnvelope
    ) {
        scope.launch {
            if (!deliveryGate.tryAcquire(localId)) return@launch
            try {
                // A reconnect flush or deletion may have removed this entry already.
                if (outbox != null && outbox.find(localId) == null) return@launch
                /*
                 * Offline is not a failure.
                 *
                 * The entry is already in the outbox (see `sendMessage`), so the message exists:
                 * it just has to wait. Trying three times against a radio that is off, and then
                 * colouring the bubble red with "Message not sent", is the opposite of what a
                 * messenger does — WhatsApp keeps the clock and sends it when the phone is back.
                 * The same rule applies to a transient answer (5xx / 429 / I/O): the queue retries
                 * it, and only the queue's own give-up (MAX_OUTBOX_ATTEMPTS) or a real 4xx marks
                 * the bubble as failed.
                 */
                if (!online()) return@launch

                var failure: Throwable? = null
                val created = sendWithRetry(onFailure = { error -> failure = error }) { send(localId) }
                if (created == null) {
                    val transient = isTransientSendFailure(failure)
                    if (transient && outbox != null) return@launch
                    // A permanent refusal must not remain queued and be sent later
                    // when the connection returns. The failed bubble stays retryable.
                    if (!transient) failAndDequeue(chatId, localId, failure)
                    else markFailed(chatId, localId, failure?.describeSend())
                    return@launch
                }
                outbox?.remove(localId)
                replaceLocal(chatId, localId, created.toModel(chatId).copy(state = DeliveryState.Sent))
                previewFromLast(chatId)
            } finally {
                deliveryGate.release(localId)
            }
        }
    }

    /**
     * Three attempts with a growing pause — the reference's `retry({maxRetries: 3, backoff})`.
     *
     * Only transient answers are retried: timeouts, rate limits, server errors and I/O failures.
     * Other client errors are refusals, and repeating them delays the warning the user needs.
     */
    private suspend fun sendWithRetry(
        onFailure: (Throwable?) -> Unit = {},
        send: suspend () -> com.nchat.app.network.MessageEnvelope?
    ): MessageDto? {
        var attempt = 0
        while (true) {
            val result = runCatching { send() }
            result.getOrNull()?.message?.let { return it }
            if (result.isSuccess) return null
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            val transient = isTransientSendFailure(error)
            attempt += 1
            if (!transient || attempt >= SEND_ATTEMPTS) {
                // The caller decides whether this is a real failure or "wait for a network".
                onFailure(error)
                return null
            }
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

    /**
     * Sends one queued entry, uploading its clip first when it has one that never made it.
     *
     * A permanent refusal is surfaced immediately; transient failures stay queued.
     */
    private suspend fun deliver(entry: OutboxEntry) {
        if (!deliveryGate.tryAcquire(entry.clientId)) return
        try {
            val store = outbox
            // The flush snapshot can be stale after a successful send or deletion.
            var row = if (store != null) store.find(entry.clientId) ?: return else entry
            if (row.ownerId.isNotEmpty() && row.ownerId != myId) return
            /*
             * A voice note recorded without a network arrives here with its audio on disk and no
             * URL: the upload is part of delivery, not a condition for queueing. Once it succeeds
             * the entry is rewritten with the URL, so a failed post afterwards re-sends the message
             * rather than uploading the same clip again.
             */
            if (row.mediaUrl.isNullOrBlank() && !row.localFile.isNullOrBlank()) {
                val upload = uploadClip(row)
                val media = upload.getOrNull()
                setUploadProgress(row.chatId, row.clientId, null)
                if (media == null) {
                    val error = upload.exceptionOrNull()
                    if (error is CancellationException) throw error
                    onDeliveryFailure(row, error)
                    return
                }
                row = row.copy(
                    mediaUrl = media.url,
                    fileSize = media.fileSize?.toString() ?: row.fileSize
                )
                if (store != null && !store.replaceIfPresent(row)) {
                    if (store.find(row.clientId) != null) {
                        NcToaster.show("Couldn't save the uploaded recording; check device storage")
                    }
                    return
                }
                updateMessages(row.chatId) { messages ->
                    messages.map { if (it.id == row.clientId) it.copy(mediaUrl = media.url) else it }
                }
            }
            // A queued media row is replayed exactly like the live send: the URL travels in
            // `content` as well as `media_url`.
            val content = row.mediaUrl?.takeIf { it.isNotBlank() } ?: row.text
            var failure: Throwable? = null
            val response = runCatching {
                if (row.isGroup) {
                    api.sendGroupMessage(
                        row.chatId,
                        content,
                        type = row.type,
                        clientMessageId = row.clientId,
                        replyTo = row.replyTo,
                        mediaUrl = row.mediaUrl,
                        thumbnailUrl = row.thumbnailUrl,
                        duration = row.duration
                    )
                } else {
                    api.sendMessage(
                        row.chatId,
                        content,
                        type = row.type,
                        clientMessageId = row.clientId,
                        replyTo = row.replyTo,
                        mediaUrl = row.mediaUrl,
                        thumbnailUrl = row.thumbnailUrl,
                        duration = row.duration,
                        fileName = row.fileName,
                        fileSize = row.fileSize
                    )
                }
            }.also { failure = it.exceptionOrNull() }
            val created = response.getOrNull()?.message
            if (created != null) {
                store?.remove(row.clientId)
                // The server owns the message now; the local clip is only a fallback.
                voiceClips?.delete(row.localFile)
                replaceLocal(
                    row.chatId,
                    row.clientId,
                    created.toModel(row.chatId).copy(
                        mediaUrl = created.mediaUrl ?: row.mediaUrl,
                        state = DeliveryState.Sent
                    )
                )
                preview(row.chatId, previewOf(row), DeliveryState.Sent)
                return
            }
            val deliveryError = failure
            if (deliveryError is CancellationException) throw deliveryError
            onDeliveryFailure(row, deliveryError)
        } finally {
            deliveryGate.release(entry.clientId)
        }
    }

    /** Uploads the clip an entry still holds locally. [MediaDto] carries the public URL. */
    private suspend fun uploadClip(entry: OutboxEntry): Result<MediaDto> {
        val bytes = voiceClips?.read(entry.localFile)
            ?: return Result.failure(IllegalStateException("The recording is no longer on this device"))
        if (bytes.isEmpty()) {
            return Result.failure(IllegalStateException("The recording is empty"))
        }
        return runCatching {
            api.uploadMedia(
                entry.fileName ?: "voice.m4a",
                entry.mimeType ?: "audio/mp4",
                bytes
            ) { fraction -> setUploadProgress(entry.chatId, entry.clientId, fraction) }
        }.mapCatching { media ->
            if (media.url.isBlank()) throw IOException("The upload returned no file URL")
            media
        }
    }

    /**
     * One policy for everything that did not reach the server.
     *
     * Offline and transient answers are not failures: the entry stays queued (and its clip
     * stays on disk) so the next flush — a reconnect, a foreground, the next launch — finishes
     * the job. Only a real refusal, or the queue's own give-up, marks the bubble and says why.
     */
    private fun onDeliveryFailure(entry: OutboxEntry, error: Throwable?) {
        val store = outbox
        // A dead radio is not the message's fault, and burning its attempts on it would turn
        // "waiting for a network" into "failed" for somebody who was simply in a lift.
        if (!online()) return
        val transient = isTransientSendFailure(error)
        // Retrying a permanent refusal on every reconnect cannot make it succeed.
        if (!transient) {
            failAndDequeue(entry.chatId, entry.clientId, error)
            return
        }
        val attempts = store?.bumpAttempts(entry.clientId) ?: MAX_OUTBOX_ATTEMPTS
        if (attempts == 0 && store?.find(entry.clientId) != null) {
            NcToaster.show("Couldn't update the message queue; check device storage")
        } else if (attempts >= MAX_OUTBOX_ATTEMPTS) {
            failAndDequeue(entry.chatId, entry.clientId, error)
        }
    }

    /** A failed removal leaves the old entry eligible for a later retry; don't hide that fact. */
    private fun failAndDequeue(chatId: String, clientId: String, error: Throwable?) {
        val reason = if (outbox != null && !outbox.remove(clientId)) {
            "couldn't update the queue; this message may retry later"
        } else {
            error?.describeSend()
        }
        markFailed(chatId, clientId, reason)
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
                thumbnailUrl = entry.thumbnailUrl,
                // Not uploaded yet: play it from the phone. The author hears their own note
                // straight away, offline included, instead of tapping a bubble with no audio.
                mediaUrl = entry.mediaUrl ?: entry.localFile?.let { "file://$it" },
                // A queued reply keeps its quote across a restart; `linkQuotes` fills the text.
                replyToId = entry.replyTo,
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

    /**
     * Marks a pending bubble as failed and says so — a stuck clock with no message is the bug.
     *
     * [detail] is the server's own words when there is one ("server said 403: blocked"), because
     * "not sent" alone leaves the user guessing whether it was the network, the file, or the
     * person on the other end.
     */
    private fun markFailed(chatId: String, localId: String, detail: String? = null) {
        updateMessages(chatId) { messages ->
            // A failed send has no upload in flight: the ring must not survive the failure.
            messages.map { if (it.id == localId) it.copy(failed = true, uploadProgress = null) else it }
        }
        // The chat list quotes this message; it has to say "not sent" too.
        previewFromLast(chatId)
        val reason = detail?.takeIf { it.isNotBlank() }
        NcToaster.show(
            if (reason != null) "Message not sent — $reason"
            else "Message not sent — tap the warning on it to try again"
        )
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
        // The echo carries the server's row: re-derive the list line from the conversation so
        // a voice note keeps its length and a group keeps its "You:".
        previewFromLast(chatId)
    }

    /**
     * Resend a bubble the server never accepted.
     *
     * Voice and media already uploaded keep their URL, so the retry is just the message again;
     * a clip whose *upload* failed has no file to point at and is reported instead of pretending.
     */
    override fun retrySend(chatId: String, messageId: String) {
        if (!deliveryGate.tryAcquire(messageId)) {
            NcToaster.show("Message is still being sent — try again shortly")
            return
        }
        val entry: OutboxEntry
        try {
            val message = stream(chatId).value.firstOrNull { it.id == messageId } ?: return
            if (!message.failed) return
            // An older failed send may still have its original queued metadata (for
            // example a generated thumbnail or file size). Preserve it on retry.
            val previous = outbox?.find(messageId)?.takeIf {
                it.chatId == chatId && (it.ownerId.isEmpty() || it.ownerId == myId)
            }
            val mediaUrl = previous?.mediaUrl ?: message.mediaUrl?.takeIf { it.isRemoteUrl() }
            val localClip = previous?.localFile ?: message.mediaUrl
                ?.takeIf { it.startsWith("file://") }
                ?.removePrefix("file://")
            if (message.kind !in setOf(MessageKind.Text, MessageKind.Location) &&
                mediaUrl == null && localClip == null) {
                NcToaster.show("The attachment is no longer available to retry")
                return
            }
            entry = previous?.copy(ownerId = myId, attempts = 0) ?: OutboxEntry(
                clientId = message.id,
                chatId = chatId,
                isGroup = isGroup(chatId),
                ownerId = myId,
                text = message.text,
                type = message.kind.wireType(),
                mediaUrl = mediaUrl,
                thumbnailUrl = message.thumbnailUrl,
                duration = message.durationLabel?.let { durationSeconds(it) },
                fileName = message.fileName,
                replyTo = message.replyToId,
                localFile = localClip,
                mimeType = localClip?.let { mimeTypeOf(it) },
                createdAt = message.timestamp
            )
            if (!enqueue(entry)) return
            updateMessages(chatId) { list ->
                list.map { if (it.id == messageId) it.copy(failed = false) else it }
            }
            previewFromLast(chatId)
        } finally {
            deliveryGate.release(messageId)
        }
        if (online()) scope.launch { deliver(entry) }
    }

    // ------------------------------------------------------------ editing etc.

    override fun editMessage(chatId: String, messageId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val original = stream(chatId).value.firstOrNull { it.id == messageId } ?: return
        if (!original.isMine || original.deleted || original.kind != MessageKind.Text) return

        if (original.clientMessageId == original.id) {
            // A lost acknowledgement means even a queued message may already be on the server.
            // Editing its retry payload would not edit the existing server row.
            NcToaster.show("Wait until the message is sent before editing it")
            return
        }

        // The existing backend contract has no edit endpoint for sent group messages.
        if (isGroup(chatId)) {
            NcToaster.show("Editing sent group messages isn't supported yet")
            return
        }
        updateMessages(chatId) { messages ->
            messages.map { if (it.id == messageId) it.copy(text = body, isEdited = true) else it }
        }
        previewFromLast(chatId)
        scope.launch {
            val response = runCatching { api.editMessage(messageId, body) }
            val error = response.exceptionOrNull()
            if (error is CancellationException) throw error
            val updated = response.getOrNull()?.message
            if (updated == null) {
                // Don't overwrite a newer change that arrived while this request was in flight.
                updateMessages(chatId) { messages ->
                    messages.map {
                        if (it.id == messageId && it.text == body) {
                            it.copy(text = original.text, isEdited = original.isEdited)
                        } else it
                    }
                }
                previewFromLast(chatId)
                NcToaster.show("Couldn't confirm the edit — ${error?.describeSend() ?: "try again"}")
                return@launch
            }
            updateMessages(chatId) { messages ->
                messages.map { if (it.id == messageId) updated.toModel(chatId) else it }
            }
            previewFromLast(chatId)
            NcToaster.show("Message edited")
        }
    }

    override fun toggleReaction(chatId: String, messageId: String, emoji: String) {
        // Optimistic toggle first — the bubble reacts under the finger, no round trip.
        val before = stream(chatId).value.firstOrNull { it.id == messageId }?.reactions ?: emptyList()
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
            }
            val updated = response.getOrNull()
            if (updated == null) {
                // The server refused (the message is gone, or the group no longer exists):
                // put the reaction back instead of showing one nobody else can see.
                updateMessages(chatId) { messages ->
                    messages.map { message ->
                        if (message.id != messageId) message
                        else message.copy(reactions = before)
                    }
                }
                NcToaster.show("Couldn't react — ${response.exceptionOrNull()?.describeSend()}")
                return@launch
            }
            updateMessages(chatId) { messages ->
                messages.map { message ->
                    if (message.id != messageId) message
                    else message.copy(reactions = updated.reactions.toModel())
                }
            }
        }
    }

    override fun deleteMessage(chatId: String, messageId: String, forEveryone: Boolean) {
        val target = stream(chatId).value.firstOrNull { it.id == messageId } ?: return
        /*
         * A bubble that never reached the server is deleted here and nowhere else — but its
         * outbox entry has to go with it. It used to be left behind, so the very next flush
         * (a reconnect, a foreground) delivered the message the user had just deleted.
         */
        if (isPending(chatId, messageId)) {
            // Once a request has started we cannot guarantee the server hasn't received it.
            // Avoid promising deletion while that outcome is still unknown.
            if (!deliveryGate.tryAcquire(messageId)) {
                NcToaster.show("Message is being sent — try deleting it when sending finishes")
                return
            }
            try {
                val queued = outbox?.find(messageId)
                if (outbox != null && !outbox.remove(messageId)) {
                    NcToaster.show("Couldn't remove the queued message from this device")
                    return
                }
                voiceClips?.delete(queued?.localFile)
                updateMessages(chatId) { messages -> messages.filterNot { it.id == messageId } }
                previewFromLast(chatId)
                NcToaster.show(if (forEveryone) "Message deleted" else "Message deleted for you")
            } finally {
                deliveryGate.release(messageId)
            }
            return
        }
        updateMessages(chatId) { messages ->
            if (forEveryone) messages.map { if (it.id == messageId) it.tombstone() else it }
            else messages.filterNot { it.id == messageId }
        }
        previewFromLast(chatId)
        scope.launch {
            val response = runCatching {
                when {
                    isGroup(chatId) && forEveryone -> api.deleteGroupMessageForEveryone(messageId)
                    isGroup(chatId) -> api.deleteGroupMessageForMe(messageId)
                    forEveryone -> api.deleteMessage(messageId)
                    else -> api.deleteMessageForMe(messageId)
                }
            }
            val error = response.exceptionOrNull() ?: run {
                NcToaster.show(if (forEveryone) "Message deleted for everyone" else "Message deleted for you")
                return@launch
            }
            /*
             * The server said no — a group message older than the worker's one-hour window
             * (`groups.ts:513-521`), or a row that is not mine. Put the message back exactly as
             * it was and say why: a tombstone that a refresh silently undoes is worse than a
             * delete that never happened.
             */
            updateMessages(chatId) { messages ->
                if (forEveryone) messages.map { if (it.id == messageId) target else it }
                else (messages + target).sortedBy { it.timestamp }
            }
            previewFromLast(chatId)
            NcToaster.show("Couldn't delete — ${error.describeSend()}")
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
        forgetOnDisk(groupId)
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
        forgetOnDisk(groupId)
        scope.launch {
            runCatching { api.deleteGroup(groupId) }
            refresh()
        }
    }

    override fun deleteChat(chatId: String) {
        _chats.update { list -> list.filterNot { it.id == chatId } }
        synchronized(conversations) { conversations.remove(chatId) }
        synchronized(requested) { requested -= chatId }
        // Deleting a chat means deleting its history, on the device as well as on the screen.
        forgetOnDisk(chatId)
    }

    /**
     * When each chat was last cleared on *this* device.
     *
     * The badge is a server number, but the user's act of reading is local and instantaneous. If
     * a list refresh lands between the tap and `POST /messages/read`, the server still reports the
     * old count and the badge pops back — the "I read it, went back, still unread" report. Anything
     * at or before the mark is treated as read no matter what the server says yet.
     */
    private val readMarks = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The badge to show: the server's count, minus anything already read here. */
    private fun badgeCount(chatId: String, serverCount: Int, lastMessageAt: Long): Int {
        val mark = readMarks[chatId] ?: return serverCount
        return if (lastMessageAt <= mark) 0 else serverCount
    }

    override fun markRead(chatId: String) {
        // Remembered locally: the server round trip is asynchronous, and a refresh that raced it
        // used to bring the unread badge straight back after a back gesture.
        readMarks[chatId] = System.currentTimeMillis()
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
                notifyIncoming(chatId, dto)
            }

            "message:edit" -> {
                val row = event.objectOrNull("message") ?: return
                val dto = decodeMessage(row)
                val chatId = if (dto.senderId == myId) dto.receiverId.orEmpty() else dto.senderId
                replaceMessage(chatId, dto)
                previewFromLast(chatId)
            }

            "message:delete" -> {
                /*
                 * A message deleted for everyone keeps its slot with a tombstone, exactly like
                 * the row the next history fetch answers with (`is_deleted` → `deleted = true`).
                 * Removing the bubble here instead made the two views disagree: the live screen
                 * closed the gap, and a second later a refresh put an empty tombstone in it.
                 */
                val id = event.string("messageId") ?: return
                val sender = event.string("sender_id").orEmpty()
                val receiver = event.string("receiver_id").orEmpty()
                val chatId = if (sender == myId) receiver else sender
                tombstoneMessage(chatId, id)
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
                if (typing) scheduleTypingExpiry(sender) else cancelTypingExpiry(sender)
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
                notifyIncoming(chatId, dto)
            }

            "group-message:delete" -> {
                val id = event.string("message_id") ?: return
                val groupId = event.string("group_id") ?: return
                tombstoneMessage(groupId, id)
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
                val who = event.string("sender_id")?.takeIf { it.isNotBlank() && it != myId }
                affectChat(groupId) { it.copy(isTyping = typing, typingUserId = who) }
                if (typing) scheduleTypingExpiry(groupId) else cancelTypingExpiry(groupId)
            }

            "group-recording" -> {
                val groupId = event.string("group_id") ?: return
                val recording = event.string("is_recording") == "true"
                affectChat(groupId) { it.copy(isRecording = recording) }
            }

            /*
             * Group traffic, applied straight from the payload.
             *
             * The worker already sends the whole object — `group:created` / `group:updated`
             * carry `{ group }` (`worker/src/routes/groups.ts:137`, `:347`) — so a full
             * `refresh()` here was pure latency: 2–3 round trips before a new group (or a
             * rename, or a new member) showed up. Applying the object is instant and, because
             * the existing row's preview/unread state is kept, cannot lose local state.
             */
            "group:created", "group:updated" -> {
                val group = event.objectOrNull("group")?.let { decodeGroup(it) } ?: return
                applyGroup(group)
            }

            "group-member:joined", "group-member:left", "group-member:updated" -> {
                val groupId = event.string("group_id") ?: return
                // The member list arrives without the group's own row, so re-read just that
                // one group (a single GET) instead of reloading every chat and group.
                scope.launch {
                    runCatching { api.group(groupId).group }.getOrNull()?.let { applyGroup(it) }
                }
            }

            "group:deleted" -> {
                val groupId = event.string("group_id") ?: event.string("id") ?: return
                dropGroup(groupId)
            }
        }
    }

    /** Inserts or replaces one group, keeping the local preview/unread state of its chat row. */
    private fun applyGroup(dto: GroupDto) {
        dto.members.forEach { member -> rememberName(member.userId, member.profile) }
        synchronized(conversations) { groupIds += dto.id }
        val group = dto.toGroup()
        _groups.update { list -> list.filterNot { it.id == group.id } + group }
        _chats.update { list ->
            val existing = list.firstOrNull { it.id == dto.id }
            val fresh = dto.toChat()
            val row = if (existing == null) fresh else fresh.copy(
                lastMessage = existing.lastMessage.ifBlank { fresh.lastMessage },
                lastMessageAt = existing.lastMessageAt.takeIf { it > 0L } ?: fresh.lastMessageAt,
                lastMessageKind = if (existing.lastMessage.isBlank()) fresh.lastMessageKind else existing.lastMessageKind,
                lastMessageMine = existing.lastMessageMine,
                lastMessageState = existing.lastMessageState,
                unreadCount = existing.unreadCount,
                pinned = existing.pinned,
                muted = existing.muted,
                isTyping = existing.isTyping,
                isRecording = existing.isRecording
            )
            (list.filterNot { it.id == dto.id } + row).sortedByDescending { it.lastMessageAt }
        }
    }

    /** Removes a group the user is no longer in (or that was deleted for everyone). */
    private fun dropGroup(groupId: String) {
        synchronized(conversations) {
            groupIds -= groupId
            conversations.remove(groupId)
            requested.remove(groupId)
        }
        _groups.update { list -> list.filterNot { it.id == groupId } }
        _chats.update { list -> list.filterNot { it.id == groupId } }
    }

    private fun decodeGroup(row: JsonObject): GroupDto? =
        runCatching { NcJson.format.decodeFromJsonElement<GroupDto>(row) }.getOrNull()

    private fun ingest(chatId: String, dto: MessageDto) {
        val model = dto.toModel(chatId)
        if (stream(chatId).value.any { it.id == model.id }) return
        updateMessages(chatId) { (it + model).sortedBy { message -> message.timestamp } }
    }

    /**
     * Sets (or clears) the upload percentage on one bubble.
     *
     * Called from OkHttp's writer thread through `ApiClient.upload`; the conversation flow is a
     * `StateFlow`, so the value crosses threads safely and Compose collects it on the main one.
     */
    private fun setUploadProgress(chatId: String, messageId: String, value: Float?) {
        updateMessages(chatId) { messages ->
            messages.map { message ->
                if (message.id == messageId) message.copy(uploadProgress = value) else message
            }
        }
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

    /** "Deleted for everyone", applied to the bubble that is on screen right now. */
    private fun tombstoneMessage(chatId: String, messageId: String) {
        if (chatId.isEmpty()) return
        var hit = false
        updateMessages(chatId) { messages ->
            messages.map { message ->
                if (message.id == messageId && !message.deleted) {
                    hit = true
                    message.tombstone()
                } else {
                    message
                }
            }
        }
        if (hit) previewFromLast(chatId)
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
        updateMessages(chatId) { it + message }
    }

    private fun replaceLocal(chatId: String, localId: String, message: Message) {
        updateMessages(chatId) { messages ->
            val withoutLocal = messages.filterNot { it.id == localId }
            /*
             * The server has no `forwarded` column (0001_init.sql), so the flag only ever
             * exists on the device that did the forwarding — keep it across the swap, or the
             * label vanishes the moment the send is acknowledged.
             */
            val wasForwarded = messages.firstOrNull { it.id == localId }?.forwarded == true
            val merged = if (wasForwarded && !message.forwarded) message.copy(forwarded = true) else message
            (withoutLocal + merged).distinctBy { it.id }.sortedBy { it.timestamp }
        }
    }

    /**
     * The single funnel every conversation mutation goes through.
     *
     * Quotes are resolved here rather than at each call site, so it does not matter whether a
     * message arrived over the socket, from history, from disk or from the composer: a reply
     * always ends up with the author and text of the message it points at.
     */
    private fun updateMessages(chatId: String, transform: (List<Message>) -> List<Message>) {
        if (chatId.isEmpty()) return
        stream(chatId).update { current -> linkQuotes(transform(current)) }
    }

    /**
     * Fills in the quote each reply points at.
     *
     * A reply carries only `reply_to` (an id). The conversation it belongs to is right here, so
     * the author and a one-line version of the quoted message can be filled in locally — that is
     * what makes a reply readable instead of an empty "Replying to" strip. A quote whose target
     * is outside the loaded page stays unresolved and renders as a plain "Replied message".
     */
    private fun linkQuotes(messages: List<Message>): List<Message> {
        if (messages.none { it.replyToId != null && it.replyText == null }) return messages
        val byId = messages.associateBy { it.id }
        return messages.map { message ->
            val id = message.replyToId ?: return@map message
            if (message.replyText != null) return@map message
            val quoted = byId[id] ?: return@map message
            message.copy(replyAuthor = quoted.authorName, replyText = quoted.previewBody())
        }
    }

    /**
     * The chat-list line for one of my own sends.
     *
     * Everything the list shows is derived from the *message* now, not from a hand-written
     * string per call site. That is what stops a photo reading "https://…", a voice note
     * saying nothing about its length, and a group never saying who spoke.
     */
    /**
     * Hands a freshly received message to [onIncoming].
     *
     * The chat is read from the list the same event just updated, so the notification carries the
     * real title and the mute flag without another lookup. A message that already arrived as part
     * of a full reload (offline catch-up) never comes through here — that is deliberate, or every
     * reconnect would ring for the whole backlog.
     */
    private fun notifyIncoming(chatId: String, dto: MessageDto) {
        val callback = onIncoming ?: return
        val chat = _chats.value.firstOrNull { it.id == chatId }
        if (chat == null) {
            /*
             * The first message of a conversation this device has never listed (a new friend, or
             * a phone that was closed for a week). Refreshing brings the row in, so the *second*
             * message notifies; without it the chat stayed invisible to the notifier until the
             * app was opened by hand, which is exactly the case a background connection exists to
             * cover.
             */
            scope.launch { runCatching { refresh() } }
            return
        }
        runCatching { callback(chat, dto.toModel(chatId)) }
    }

    private fun preview(chatId: String, message: Message, state: DeliveryState) {
        val (sender, _) = previewParts(message, isGroup(chatId))
        val line = previewLine(message, isGroup(chatId))
        _chats.update { list ->
            list.map { chat ->
                if (chat.id != chatId) chat
                else chat.copy(
                    lastMessage = line,
                    lastMessageSender = sender,
                    lastMessageAt = System.currentTimeMillis(),
                    lastMessageKind = message.kind,
                    lastMessageMine = true,
                    lastMessageState = state,
                    lastMessageFailed = false,
                    isTyping = false,
                    isRecording = false
                )
            }
        }
    }

    /** The chat-list line for a message row that arrived over the socket or in a reload. */
    private fun previewOf(dto: MessageDto, chatId: String, isGroup: Boolean): String =
        previewLine(dto.toModel(chatId), isGroup)

    /** An outbox row replayed from disk, as a message, so previews read the same as live ones. */
    private fun previewOf(entry: OutboxEntry): Message = Message(
        id = entry.clientId,
        chatId = entry.chatId,
        authorId = myId,
        authorName = myName,
        text = entry.text,
        timestamp = entry.createdAt,
        isMine = true,
        kind = kindOf(entry.type),
        durationLabel = durationLabelOf(entry.duration),
        fileName = entry.fileName,
        mediaUrl = entry.mediaUrl,
        thumbnailUrl = entry.thumbnailUrl,
        clientMessageId = entry.clientId
    )

    /**
     * One line of text for the chat list.
     *
     * The row already draws a type icon (`KindIcon`), so the text carries only what the icon
     * cannot say: a voice note's length, a photo's caption, a document's name. Groups are
     * prefixed with who spoke — "You" for our own — the way every messenger does.
     */
    private fun previewLine(message: Message, isGroup: Boolean): String {
        val (sender, body) = previewParts(message, isGroup)
        return if (sender == null) body else "$sender: $body"
    }

    /**
     * The chat-list line, split into who and what.
     *
     * Groups needs both halves separately so the row can draw the name in its own colour; 1:1
     * chats and system rows have no "who" (the title already says it), hence a null sender.
     */
    private fun previewParts(message: Message, isGroup: Boolean): Pair<String?, String> {
        val body = message.previewBody()
        if (!isGroup || message.systemEvent) return null to body
        val sender = if (message.isMine) "You" else message.authorName
        return sender to body
    }

    /** The message's own text, without the group prefix — the model owns the wording. */
    private fun Message.previewBody(): String = snippet()

    /**
     * Rebuilds a chat's list row from its newest remaining message.
     *
     * Deleting or editing the message the list was quoting used to leave the row describing
     * something that no longer exists — the conversation said "This message was deleted"
     * while the list still showed what had been said.
     */
    private fun previewFromLast(chatId: String) {
        val chat = _chats.value.firstOrNull { it.id == chatId } ?: return
        val newest = stream(chatId).value.maxByOrNull { it.timestamp }
        if (newest == null) {
            _chats.update { list ->
                list.map {
                    if (it.id != chatId) it
                    else it.copy(
                        lastMessage = "",
                        lastMessageKind = MessageKind.Text,
                        lastMessageMine = false,
                        lastMessageState = null,
                        lastMessageFailed = false
                    )
                }
            }
            return
        }
        val line = previewLine(newest, chat.isGroup)
        _chats.update { list ->
            list.map {
                if (it.id != chatId) it
                else it.copy(
                    lastMessage = line,
                    lastMessageAt = newest.timestamp,
                    lastMessageKind = newest.kind,
                    lastMessageMine = newest.isMine,
                    lastMessageState = if (newest.isMine) newest.state else null,
                    lastMessageFailed = newest.isMine && newest.failed
                )
            }
        }
    }

    private fun bumpPreview(chatId: String, dto: MessageDto) {
        val isGroupChat = isGroup(chatId)
        val (sender, _) = previewParts(dto.toModel(chatId), isGroupChat)
        val line = previewOf(dto, chatId, isGroupChat)
        _chats.update { list ->
            if (list.none { it.id == chatId }) {
                // A message from somebody the server does not list as a friend still deserves
                // a row — `/messages/chats` only knows friends, so without this an incoming
                // chat from anyone else never appeared in the list at all.
                list + Chat(
                    id = chatId,
                    title = nameOf(chatId),
                    isGroup = isGroupChat,
                    lastMessage = line,
                    lastMessageSender = sender,
                    lastMessageAt = isoMillis(dto.createdAt),
                    lastMessageMine = false,
                    lastMessageKind = dto.kind(),
                    unreadCount = 1
                )
            } else {
                list.map { chat ->
                    if (chat.id != chatId) chat
                    else chat.copy(
                        lastMessage = line,
                        lastMessageSender = sender,
                        lastMessageAt = isoMillis(dto.createdAt),
                        lastMessageMine = false,
                        lastMessageState = null,
                        lastMessageKind = dto.kind(),
                        unreadCount = chat.unreadCount + 1,
                        isTyping = false,
                        isRecording = false
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

    /** Live "typing" rows, so a stale one can be cleared when the peer goes quiet. */
    private val typingExpiry = mutableMapOf<String, Job>()

    /**
     * "…is typing" that turns itself off.
     *
     * The server relays each `typing` frame as-is and never sends an expiry, so a sender whose
     * phone sleeps mid-sentence (or whose "stopped typing" frame is lost — WebSockets are not
     * reliable) leaves the indicator on forever. WhatsApp expires it for the same reason:
     * the *absence* of new typing frames is what ends it.
     */
    private fun scheduleTypingExpiry(chatId: String) {
        val job = scope.launch {
            delay(TYPING_TTL_MS)
            affectChat(chatId) { it.copy(isTyping = false, isRecording = false) }
        }
        val previous = synchronized(typingExpiry) { typingExpiry.put(chatId, job) }
        previous?.cancel()
    }

    private fun cancelTypingExpiry(chatId: String) {
        synchronized(typingExpiry) { typingExpiry.remove(chatId) }?.cancel()
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
            lastMessage = last?.let { previewOf(it, userId, isGroup = false) }.orEmpty(),
            lastMessageSender = null,
            lastMessageAt = isoMillis(last?.createdAt),
            lastMessageKind = last?.kind() ?: MessageKind.Text,
            lastMessageMine = last?.senderId == myId,
            lastMessageState = when {
                last == null || last.senderId != myId -> null
                last.isRead -> DeliveryState.Read
                else -> DeliveryState.Sent
            },
            unreadCount = badgeCount(userId, unreadCount, isoMillis(last?.createdAt))
        )
    }

    private fun GroupDto.toChat(): Chat = Chat(
        id = id,
        title = name,
        avatarEmoji = "👥",
        avatarUrl = avatarUrl,
        isGroup = true,
        lastMessage = lastMessage?.let { previewOf(it, id, isGroup = true) }.orEmpty(),
        lastMessageSender = lastMessage?.let { previewParts(it.toModel(id), true).first },
        lastMessageAt = isoMillis(lastMessage?.createdAt ?: createdAt),
        lastMessageKind = lastMessage?.kind() ?: MessageKind.Text,
        lastMessageMine = lastMessage?.senderId == myId,
        lastMessageState = when {
            lastMessage == null || lastMessage.senderId != myId -> null
            lastMessage.isRead -> DeliveryState.Read
            else -> DeliveryState.Sent
        },
        unreadCount = badgeCount(id, unreadCount, isoMillis(lastMessage?.createdAt ?: createdAt)),
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
            lastActivity = lastMessage?.let { previewOf(it, id, isGroup = true) }.orEmpty(),
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
            // `reply_to` used to be decoded and thrown away, which is why a reply sent from the
            // web arrived here as a bare message. It is kept and resolved against the
            // conversation (`linkQuotes`), so the quote renders on every device.
            replyToId = replyTo,
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

    /** One short sentence for a toast: what the server said, or why the network did not. */
    private fun Throwable.describeSend(): String = when (this) {
        is ApiException -> "server said $status: $message"
        is IOException -> "no connection"
        else -> message ?: javaClass.simpleName
    }

    /**
     * File extension for a recorded clip, from its MIME type where possible.
     *
     * The worker stores the object under a key built from the file name's extension and serves
     * it back with the recorded Content-Type, so an `.m4a` name with `audio/mp4` is the pair
     * players and browsers both recognise.
     */
    private fun extensionFor(mimeType: String, fileName: String): String {
        val fromName = fileName.substringAfterLast('.', "").lowercase()
        if (fromName in setOf("m4a", "mp4", "aac", "ogg", "opus", "webm", "wav", "3gp", "amr")) {
            return fromName
        }
        return when {
            mimeType.contains("mp4", true) || mimeType.contains("aac", true) -> "m4a"
            mimeType.contains("ogg", true) -> "ogg"
            mimeType.contains("opus", true) -> "opus"
            mimeType.contains("webm", true) -> "webm"
            mimeType.contains("wav", true) -> "wav"
            else -> "m4a"
        }
    }

    /** The reverse, for a clip whose extension is all a retry has to go on. */
    private fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4a", "aac" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "webm" -> "audio/webm"
        "wav" -> "audio/wav"
        "3gp" -> "audio/3gpp"
        "amr" -> "audio/amr"
        else -> "audio/mp4"
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
        // `voice`, never `audio`: the web client keys its whole voice bubble off
        // `message.type === 'voice'` (`ChatView.tsx:130`, `:2207`), and an `audio` row falls
        // through to the text branch, which prints the media URL as the message body.
        MessageKind.Voice -> "voice"
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
        /**
         * Messages kept per conversation in memory.
         *
         * The server pages 100 at a time, but the disk cache holds 300 — a merge that clamped
         * back to 100 would throw away history the user could see a moment earlier, which is
         * the opposite of what a local store is for.
         */
        const val MESSAGE_PAGE = 300
        /** How long a change waits before it is written, so a burst costs one file write. */
        const val PERSIST_DELAY_MS = 400L
        /** Same idea for the two list caches, which change less often. */
        const val LIST_SAVE_DELAY_MS = 700L
        const val SEND_ATTEMPTS = 3
        /** The reference gives up on a queued message after five tries. */
        const val MAX_OUTBOX_ATTEMPTS = 5

        val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
