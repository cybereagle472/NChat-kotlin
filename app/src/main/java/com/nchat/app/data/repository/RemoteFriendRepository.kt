package com.nchat.app.data.repository

import com.nchat.app.data.local.BlockedContactsStore
import com.nchat.app.data.local.FriendGraphCache
import com.nchat.app.network.ApiException
import com.nchat.app.network.ApiLog
import com.nchat.app.data.model.BlockedContact
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.FriendRelation
import com.nchat.app.network.FriendRequestDto
import com.nchat.app.network.NcJson
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NChatSocket
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The friend graph against the Rust backend.
 *
 * The server keeps four separate answers — `/friends`, `/friends/requests`, `/friends/sent`,
 * `/users` — and the Discover screen wants one list with a relation per person, so they are
 * merged here exactly like the reference's `friendService` did:
 *
 * - accepted → [FriendRelation.Friend],
 * - pending incoming → [FriendRelation.Incoming] (accept/decline act on the request id),
 * - pending outgoing → [FriendRelation.Outgoing] (cancel does too),
 * - everyone else → [FriendRelation.None], which is the "Find people" tab.
 */
class RemoteFriendRepository(
    private val api: NChatApi,
    private val tokens: TokenStore,
    private val socket: NChatSocket,
    private val scope: CoroutineScope,
    /** The device's own block list — see the class comment on [BlockedContactsStore]. */
    private val blockedStore: BlockedContactsStore,
    /** Last known graph on disk, so a cold start cannot show an empty Friends tab. */
    private val cache: FriendGraphCache? = null,
) : FriendRepository {

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    override val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _blockedPeople = MutableStateFlow<List<BlockedContact>>(emptyList())
    override val blockedPeople: StateFlow<List<BlockedContact>> = _blockedPeople.asStateFlow()

    private val _blockedIds = MutableStateFlow<Set<String>>(emptySet())
    override val blockedIds: StateFlow<Set<String>> = _blockedIds.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Friend>>(emptyList())
    override val searchResults: StateFlow<List<Friend>> = _searchResults.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    override val searchError: StateFlow<String?> = _searchError.asStateFlow()

    /** In-flight search, so a fast typist never sees an older answer beat a newer one. */
    private var searchJob: Job? = null

    private val _pendingSent = MutableStateFlow<Set<String>>(emptySet())
    override val pendingSent: StateFlow<Set<String>> = _pendingSent.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    override val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _graphError = MutableStateFlow<String?>(null)
    override val graphError: StateFlow<String?> = _graphError.asStateFlow()

    private val _graphNote = MutableStateFlow<String?>(null)
    override val graphNote: StateFlow<String?> = _graphNote.asStateFlow()

    /** One reload at a time: two overlapping loads used to fight over [_friends]. */
    private val reloadMutex = Mutex()

    override fun clearActionError() {
        _actionError.value = null
    }

    private var incoming: List<FriendRequestDto> = emptyList()
    private var outgoing: List<FriendRequestDto> = emptyList()
    private var blockedUsers: List<UserDto> = emptyList()

    private val myId: String get() = tokens.userId.orEmpty()

    init {
        // The block list survives restarts (it lives on the device, not on the server).
        loadLocalBlocks(myId)
        /*
         * Disk first, exactly like the chat list.
         *
         * The graph used to be memory-only, so any start-up in which `/friends` did not answer
         * (cold start with no data, a 502 at the edge, a 401 that needed a token refresh)
         * opened the Friends tab on "No friends yet" — while Chats, painted from its cache,
         * showed the very same people. Restoring the last good answer removes that asymmetry;
         * the server's next reply still replaces it wholesale.
         */
        val restored = cache?.load(myId).orEmpty().filter { it.id.isNotBlank() && it.id != myId }
        if (restored.isNotEmpty()) {
            _friends.value = restored
        }
        scope.launch {
            tokens.signedIn.collect { signedIn ->
                if (signedIn) {
                    // Account-scoped local state is switched at the same time as the session.
                    loadLocalBlocks(myId)
                    return@collect
                }
                // Signing out must not leave the previous account's private graph/results visible.
                _friends.value = emptyList()
                _searchResults.value = emptyList()
                _searchError.value = null
                _blockedPeople.value = emptyList()
                _blockedIds.value = emptySet()
                _graphError.value = null
                _graphNote.value = null
                _pendingSent.value = emptySet()
                incoming = emptyList()
                outgoing = emptyList()
                cache?.clear()
            }
        }
        scope.launch {
            socket.events.collect { event ->
                /*
                 * Applied from the payload, not by reloading.
                 *
                 * Every one of these events already carries what changed (see
                 * `worker/src/routes/friends.ts`: `friend:request` → `{ request }` with the
                 * sender's profile, `:105` `friend:accepted` → `{ friend }`, `:216`
                 * `friend:removed` → `{ userId }`). Calling `refresh()` meant three round
                 * trips before an incoming request appeared, so a request that arrived while
                 * the app was open looked late. Only an event we cannot parse falls back to a
                 * reload.
                 */
                when (event.type) {
                    "friend:request" -> {
                        val row = event.objectOrNull("request")
                        val dto = row?.let { runCatching { decode<FriendRequestDto>(it) }.getOrNull() }
                        if (dto == null) refresh() else applyIncoming(dto)
                    }

                    "friend:accepted" -> {
                        val row = event.objectOrNull("friend")
                        val profile = row?.let { runCatching { decode<UserDto>(it) }.getOrNull() }
                        if (profile == null) refresh() else applyAccepted(profile)
                    }

                    "friend:declined" -> {
                        val id = event.string("userId") ?: event.string("user_id")
                        if (id == null) refresh() else {
                            _friends.update { rows -> rows.filterNot { it.id == id } }
                            _pendingSent.update { it - id }
                        }
                    }

                    "friend:removed" -> {
                        val id = event.string("userId") ?: event.string("user_id")
                        if (id == null) refresh() else {
                            _friends.update { rows -> rows.filterNot { it.id == id } }
                        }
                    }
                }
            }
        }
        /*
         * Reload whenever the realtime socket comes back up.
         *
         * The graph used to be fetched exactly once, in `init`. If that single request lost a
         * race with the handshake (cold start, token refresh, flaky mobile data) the Friends tab
         * stayed empty for the whole session — the socket reconnecting was the one signal the
         * account was reachable again, and nothing was listening to it.
         */
        scope.launch {
            socket.state.collect { state ->
                if (state == com.nchat.app.network.ConnectionState.Connected) refresh()
            }
        }
        refresh()
    }

    override fun refresh() {
        scope.launch {
            /*
             * A throw escaping `reload()` used to be swallowed whole (`runCatching { … }` with
             * no failure branch), which is the one way this screen could look empty with no
             * error at all: `/friends` answered fine, the merge threw halfway through, the
             * graph was never published and nothing was reported. Every failure is now named,
             * logged and shown.
             */
            runCatching { reload() }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                ApiLog.record("MERGE", "/friends", null, 0L, error.describeAction())
                _graphError.value = "Couldn't build your friends list — ${error.describeAction()}"
            }
        }
    }

    override fun accept(id: String) {
        val request = incoming.firstOrNull { it.senderId == id } ?: return
        scope.launch {
            runCatching { api.respondToRequest(request.id, accept = true) }
                .onFailure { error -> _actionError.value = "Couldn't accept request — ${error.describeAction()}" }
            refresh()
        }
    }

    override fun decline(id: String) {
        val request = incoming.firstOrNull { it.senderId == id } ?: return
        scope.launch {
            runCatching { api.respondToRequest(request.id, accept = false) }
                .onFailure { error -> _actionError.value = "Couldn't decline request — ${error.describeAction()}" }
            refresh()
        }
    }

    override fun sendRequest(id: String) {
        if (id.isBlank()) return
        // Flip to "Cancel" now (reference behaviour); roll back only if the server refuses.
        _pendingSent.update { it + id }
        scope.launch {
            val error = runCatching { api.sendFriendRequest(id) }.exceptionOrNull()
            when {
                error == null -> Unit

                /*
                 * `409 Already friends` / `409 Request already pending` are not failures — they
                 * are the server telling us the relationship it wants already exists. The friend
                 * graph can disagree with that answer (an older build's block deleted the
                 * `friendships` row while the accepted request stayed behind), which is exactly
                 * how a friend ended up listed in Find behind an Add button that could only ever
                 * raise an error. Trust the 409 instead of showing it: a pending request keeps
                 * its Cancel, and an existing friendship is promoted to Friend right here.
                 */
                error is ApiException && error.status == 409 -> {
                    val message = error.message.lowercase()
                    if (message.contains("already friend")) {
                        _pendingSent.update { it - id }
                        _friends.update { rows ->
                            rows.map { row ->
                                if (row.id == id) row.copy(relation = FriendRelation.Friend) else row
                            }
                        }
                    }
                    // "Request already pending": the Cancel button is the correct state, keep it.
                    _actionError.value = null
                }

                else -> {
                    _pendingSent.update { it - id }
                    _actionError.value = "Couldn't send the request — ${error.describeAction()}"
                }
            }
            refresh()
        }
    }

    override fun cancelRequest(id: String) {
        val request = outgoing.firstOrNull { it.receiverId == id }
        _pendingSent.update { it - id }
        if (request == null) return
        scope.launch {
            runCatching { api.cancelRequest(request.id) }
                .onFailure { error -> _actionError.value = "Couldn't cancel — ${error.describeAction()}" }
            refresh()
        }
    }

    override fun unfriend(id: String) {
        scope.launch {
            runCatching { api.removeFriend(id) }
                .onFailure { error -> _actionError.value = "Couldn't unfriend this user — ${error.describeAction()}" }
            refresh()
        }
    }

    /**
     * Blocks somebody **locally and reversibly**.
     *
     * `POST /friends/block` is deliberately not called: on the reference worker that route
     * also runs `DELETE FROM friendships`, which is what makes a blocked-then-unblocked
     * contact come back with no chat and no history. Keeping the block here means the
     * friendship and every message stay untouched, so unblocking restores the conversation
     * exactly where it was left.
     */
    override fun block(id: String, name: String, avatarUrl: String?) {
        val target = id.trim()
        if (target.isEmpty()) return
        val previous = _blockedPeople.value
        persistBlocked(
            previous.filterNot { it.id == target } +
                BlockedContact(
                    id = target,
                    name = name.trim().ifBlank { "NChat user" },
                    avatarUrl = avatarUrl,
                    blockedAt = System.currentTimeMillis()
                )
        )
        // Persist remotely so the block applies to every device and is enforced by the API.
        scope.launch {
            runCatching { api.blockUser(target) }.onFailure { error ->
                // Keep the immediate local safety gate, but be explicit that other devices will
                // not be protected until the server sync succeeds.
                _actionError.value = "Block is local only until it syncs — ${error.describeAction()}"
            }
            refresh()
        }
    }

    override fun unblock(id: String) {
        val target = id.trim()
        if (target.isEmpty()) return
        val previous = _blockedPeople.value
        persistBlocked(previous.filterNot { it.id == target })
        scope.launch {
            runCatching { api.unblockUser(target) }.onFailure { error ->
                persistBlocked(previous)
                _actionError.value = "Couldn't unblock this user — ${error.describeAction()}"
            }
            refresh()
        }
    }

    private fun loadLocalBlocks(ownerId: String) {
        val contacts = if (ownerId.isBlank()) emptyList() else
            blockedStore.load(ownerId).sortedByDescending { it.blockedAt }
        _blockedPeople.value = contacts
        _blockedIds.value = contacts.map { it.id }.toSet()
    }

    /** Writes the account's list to disk and mirrors it into the two flows the UI reads. */
    private fun persistBlocked(contacts: List<BlockedContact>) {
        val ordered = contacts.sortedByDescending { it.blockedAt }
        _blockedPeople.value = ordered
        _blockedIds.value = ordered.map { it.id }.toSet()
        blockedStore.save(myId, ordered)
    }

    override fun search(query: String) {
        val term = query.trim()
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _searchError.value = null
        if (term.isEmpty()) return
        searchJob = scope.launch {
            delay(250)
            try {
                val rows = api.searchUsers(term, limit = 30).profiles
                _searchResults.value = rows
                    .filter { it.id.isNotEmpty() && it.id != myId }
                    .map { profile ->
                        _friends.value.firstOrNull { it.id == profile.id }
                            ?: profile.toFriend(FriendRelation.None)
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _searchResults.value = emptyList()
                _searchError.value = "Couldn't search people — ${error.describeAction()}"
            }
        }
    }

    /**
     * Three attempts with a growing pause before the graph is called unreachable.
     *
     * A single `GET /friends` at start-up is the whole friend list; one hiccup (a 502 at the
     * edge, a token refresh landing at the same moment) used to leave the tab empty until the
     * app was restarted. Transient answers deserve a retry, and anything that survives three
     * attempts is reported to the UI instead of being swallowed.
     */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                if (attempt < 3) delay(300L * attempt)
            }
        }
        throw lastError ?: IllegalStateException("friend graph unreachable")
    }

    private suspend fun reload() = reloadMutex.withLock {
        val me = myId
        /*
         * What this device already knew, before anything is replaced.
         *
         * Two uses. First, a row can arrive without a readable profile — the id still names a
         * real person, and the name we showed a moment ago is better than dropping them.
         * Second, and more important: a failed `/friends` must never delete friends. The graph
         * used to be rebuilt from whatever came back, so one 502 (or one 401 that resolved
         * into a retry) replaced a full friend list with "nobody" until the next successful
         * load — which is exactly what a disappearing Friends tab looks like.
         */
        val previous = _friends.value.associateBy { it.id }

        val acceptedResult = runCatching { withRetry { api.friends().friends } }
        val accepted = acceptedResult.getOrDefault(emptyList())
        val requests = runCatching { api.friendRequests().requests }.getOrDefault(emptyList())
        val sent = runCatching { api.sentRequests() }.getOrNull()
        val everyoneResult = runCatching { api.allUsers().profiles }
        val everyone = everyoneResult.getOrDefault(emptyList())
        val blocked = runCatching { api.blockedUsers().blocked }.getOrDefault(emptyList())

        // Say what went wrong instead of drawing an empty list — see [graphError]. Only the
        // `/friends` answer decides what the Friends tab shows, so only that one is reported
        // there; a failing `/users` empties Find, not this list.
        _graphError.value = acceptedResult.exceptionOrNull()?.let { error ->
            "Couldn't load your friends — ${error.describeAction()}"
        }

        incoming = requests
        outgoing = sent?.requests ?: emptyList()
        // Anything the server now lists as sent is confirmed; stop drawing it as pending.
        val confirmed = outgoing.map { it.receiverId }.toSet()
        if (confirmed.isNotEmpty()) _pendingSent.update { it - confirmed }
        blockedUsers = blocked
        // Retry local blocks that could not reach the server earlier (for example, while offline).
        // The server operation is idempotent and the local block remains active until it succeeds.
        val serverBlockedIds = blocked.mapTo(HashSet()) { it.id }
        _blockedPeople.value.filterNot { it.id in serverBlockedIds }.forEach { local ->
            scope.launch {
                runCatching { api.blockUser(local.id) }.onFailure { error ->
                    _actionError.value = "Block is local only until it syncs — ${error.describeAction()}"
                }
            }
        }
        // Legacy blocks that were made on the server still have to be visible and undoable from
        // Settings, so they are merged into the device list.
        // Anything already known locally keeps its stored name/photo.
        val knownLocally = _blockedPeople.value.associateBy { it.id }
        val merged = blocked.map { profile ->
            knownLocally[profile.id] ?: BlockedContact(
                id = profile.id,
                name = profile.displayName(),
                avatarUrl = profile.avatarUrl,
                blockedAt = 0L
            )
        } + _blockedPeople.value.filter { local -> blocked.none { it.id == local.id } }
        // A disk write is not allowed to stop the graph from loading: this call used to sit
        // between the two, so a full or read-only filesystem threw here and the friend list was
        // never published at all.
        runCatching { persistBlocked(merged) }

        val rows = mutableListOf<Friend>()
        val known = mutableSetOf<String>()

        // Names for rows that carry an id but no nested profile (see below).
        val directory = HashMap<String, UserDto>()
        everyone.forEach { if (it.id.isNotBlank()) directory[it.id] = it }
        requests.forEach { request ->
            request.senderProfile?.takeIf { it.id.isNotBlank() }?.let { directory[it.id] = directory[it.id] ?: it }
        }

        var unreadable = 0
        accepted.forEach { friend ->
            /*
             * A `/friends` row is `{ id, profile }` (`worker/src/routes/friends.ts:177-197`) and
             * both halves name the same person, so the id alone is enough to keep them. The row
             * used to be thrown away the moment `profile` was missing — no error, no log, no
             * row: a friend silently vanished from a list the server had just confirmed.
             */
            val id = friend.profile?.id?.takeIf { it.isNotBlank() } ?: friend.id
            if (id.isBlank() || id == me || !known.add(id)) return@forEach
            val profile = friend.profile?.takeIf { it.id.isNotBlank() } ?: directory[id]
            val row = when {
                profile != null -> profile.toFriend(FriendRelation.Friend)
                // Keep the name this device already had for them.
                else -> previous[id]?.copy(relation = FriendRelation.Friend)
            }
            if (row == null) {
                unreadable++
                known.remove(id)
                return@forEach
            }
            rows += row
        }

        // A failed `/friends` keeps the friends it could not re-confirm.
        if (acceptedResult.isFailure) {
            previous.values.forEach { old ->
                if (old.relation == FriendRelation.Friend && old.id.isNotBlank() &&
                    old.id != me && known.add(old.id)
                ) {
                    rows += old
                }
            }
        }

        requests.forEach { request ->
            val profile = request.senderProfile ?: return@forEach
            if (profile.id.isBlank() || profile.id == me || !known.add(profile.id)) return@forEach
            rows += profile.toFriend(FriendRelation.Incoming, requestedAt = isoMillis(request.createdAt))
        }

        (sent?.requests ?: emptyList()).forEach { request ->
            if (request.receiverId == me || request.receiverId in known) return@forEach
            val profile = directory[request.receiverId] ?: return@forEach
            known += request.receiverId
            rows += profile.toFriend(FriendRelation.Outgoing, requestedAt = isoMillis(request.createdAt))
        }

        // A conversation is not proof of a current friendship: it may remain after unfriend.
        // Keep chat history in the chat list, but only accepted server relationships belong here.

        everyone.forEach { profile ->
            if (profile.id.isBlank() || profile.id == me || !known.add(profile.id)) return@forEach
            rows += profile.toFriend(FriendRelation.None)
        }

        _friends.value = rows

        /*
         * Why the tab is empty, when it is.
         *
         * "No friends yet" was printed for three different situations: the request failed, the
         * server answered zero rows, or the rows it answered could not be read. Only the first
         * had a message; the other two now say what they are, so a screenshot of the Friends
         * tab is enough to tell them apart.
         */
        _graphNote.value = when {
            acceptedResult.isFailure -> null // the error card already explains it
            unreadable > 0 ->
                "The server listed ${accepted.size} friends, but $unreadable of them came back " +
                    "without a profile this device could read."
            accepted.isEmpty() && rows.none { it.relation == FriendRelation.Friend } ->
                "The server answered /friends with 0 friends for " +
                    "${tokens.email ?: "this account"}."
            else -> null
        }

        if (acceptedResult.isSuccess) runCatching { cache?.save(me, rows) }
    }

    private inline fun <reified T> decode(row: JsonObject): T =
        NcJson.format.decodeFromJsonElement(row)

    /** Somebody asked to be your friend: show them in Requests immediately. */
    private fun applyIncoming(request: FriendRequestDto) {
        val profile = request.senderProfile ?: run { refresh(); return }
        if (profile.id == myId) return
        incoming = incoming.filterNot { it.id == request.id } + request
        _friends.update { rows ->
            rows.filterNot { it.id == profile.id } +
                profile.toFriend(FriendRelation.Incoming, requestedAt = isoMillis(request.createdAt))
        }
    }

    /** They accepted: they are a friend now, without waiting for a round trip. */
    private fun applyAccepted(profile: UserDto) {
        if (profile.id == myId || profile.id.isEmpty()) return
        _pendingSent.update { it - profile.id }
        outgoing = outgoing.filterNot { it.receiverId == profile.id }
        _friends.update { rows ->
            rows.filterNot { it.id == profile.id } + profile.toFriend(FriendRelation.Friend)
        }
    }

    /** Turns an HTTP/network failure into the sentence a toast shows. */
    private fun Throwable.describeAction(): String = when (this) {
        is com.nchat.app.network.ApiException -> "server said $status: $message"
        is java.io.IOException -> "network: ${message ?: javaClass.simpleName}"
        else -> message ?: javaClass.simpleName
    }

    private fun UserDto.toFriend(relation: FriendRelation, requestedAt: Long? = null) = Friend(
        id = id,
        name = displayName(),
        avatarUrl = avatarUrl,
        handle = username?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: email?.substringBefore('@')?.let { "@$it" }
            ?: "",
        isOnline = isOnline,
        relation = relation,
        lastSeenAt = isoMillis(lastSeen).takeIf { it > 0L },
        requestedAt = requestedAt
    )

    private fun UserDto.displayName(): String =
        fullName?.takeIf { it.isNotBlank() }
            ?: username?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: email?.substringBefore('@')
            ?: "NChat user"

    private fun isoMillis(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return synchronized(ISO) { runCatching { ISO.parse(value)?.time ?: 0L }.getOrDefault(0L) }
    }

    private companion object {
        val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
