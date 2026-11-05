package com.nchat.app.data.repository

import com.nchat.app.data.local.BlockedContactsStore
import com.nchat.app.network.ApiException
import com.nchat.app.data.model.BlockedContact
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.FriendRelation
import com.nchat.app.network.FriendRequestDto
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
    private val blockedStore: BlockedContactsStore
) : FriendRepository {

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    override val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _blockedPeople = MutableStateFlow<List<BlockedContact>>(emptyList())
    override val blockedPeople: StateFlow<List<BlockedContact>> = _blockedPeople.asStateFlow()

    private val _blockedIds = MutableStateFlow<Set<String>>(emptySet())
    override val blockedIds: StateFlow<Set<String>> = _blockedIds.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Friend>>(emptyList())
    override val searchResults: StateFlow<List<Friend>> = _searchResults.asStateFlow()

    /** In-flight search, so a fast typist never sees an older answer beat a newer one. */
    private var searchJob: Job? = null

    private val _pendingSent = MutableStateFlow<Set<String>>(emptySet())
    override val pendingSent: StateFlow<Set<String>> = _pendingSent.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    override val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _graphError = MutableStateFlow<String?>(null)
    override val graphError: StateFlow<String?> = _graphError.asStateFlow()

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
        _blockedPeople.value = blockedStore.load().sortedByDescending { it.blockedAt }
        _blockedIds.value = _blockedPeople.value.map { it.id }.toSet()
        scope.launch {
            socket.events.collect { event ->
                when (event.type) {
                    "friend:request", "friend:accepted", "friend:declined", "friend:removed" -> refresh()
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
        scope.launch { runCatching { reload() } }
    }

    override fun accept(id: String) {
        val request = incoming.firstOrNull { it.senderId == id } ?: return
        scope.launch {
            runCatching { api.respondToRequest(request.id, accept = true) }
            refresh()
        }
    }

    override fun decline(id: String) {
        val request = incoming.firstOrNull { it.senderId == id } ?: return
        scope.launch {
            runCatching { api.respondToRequest(request.id, accept = false) }
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
        persistBlocked(
            _blockedPeople.value
                .filterNot { it.id == target } +
                BlockedContact(
                    id = target,
                    name = name.trim().ifBlank { "NChat user" },
                    avatarUrl = avatarUrl,
                    blockedAt = System.currentTimeMillis()
                )
        )
        // Their chat keeps its place in the list; the conversation screen shows the block and
        // offers Unblock, and the socket stops appending their new messages.
    }

    override fun unblock(id: String) {
        val target = id.trim()
        if (target.isEmpty()) return
        persistBlocked(_blockedPeople.value.filterNot { it.id == target })
        // Clear a legacy server-side block too — `/friends/unblock` only deletes the row in
        // `blocked_users`, so this can never touch the friendship or the history.
        if (blockedUsers.any { it.id == target }) {
            scope.launch { runCatching { api.unblockUser(target) } }
        }
    }

    /** Writes the list to disk and mirrors it into the two flows the UI reads. */
    private fun persistBlocked(contacts: List<BlockedContact>) {
        val ordered = contacts.sortedByDescending { it.blockedAt }
        _blockedPeople.value = ordered
        _blockedIds.value = ordered.map { it.id }.toSet()
        blockedStore.save(ordered)
    }

    override fun search(query: String) {
        val term = query.trim()
        searchJob?.cancel()
        if (term.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = scope.launch {
            // Same ~250 ms debounce the reference search bars use; short enough to feel live,
            // long enough that typing a handle is one request, not eight.
            delay(250)
            val rows = runCatching { api.searchUsers(term, limit = 30).profiles }
                .getOrDefault(emptyList())
            _searchResults.value = rows
                .filter { it.id.isNotEmpty() && it.id != myId }
                .map { profile ->
                    // Anyone already in the graph keeps their real relation (friend / incoming /
                    // outgoing) so the row shows Accept / Cancel / Message instead of "Add".
                    _friends.value.firstOrNull { it.id == profile.id }
                        ?: profile.toFriend(FriendRelation.None)
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
        // Legacy blocks that were made on the server (before blocking became local) still have
        // to be visible and undoable from Settings, so they are merged into the device list.
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
        persistBlocked(merged)

        val rows = mutableListOf<Friend>()
        val known = mutableSetOf<String>()

        accepted.forEach { friend ->
            val profile = friend.profile
            if (profile == null || profile.id == myId) return@forEach
            known += profile.id
            rows += profile.toFriend(FriendRelation.Friend)
        }

        requests.forEach { request ->
            val profile = request.senderProfile ?: return@forEach
            if (profile.id == myId || profile.id in known) return@forEach
            known += profile.id
            rows += profile.toFriend(FriendRelation.Incoming, requestedAt = isoMillis(request.createdAt))
        }

        (sent?.requests ?: emptyList()).forEach { request ->
            if (request.receiverId == myId || request.receiverId in known) return@forEach
            val profile = everyone.firstOrNull { it.id == request.receiverId } ?: return@forEach
            known += request.receiverId
            rows += profile.toFriend(FriendRelation.Outgoing, requestedAt = isoMillis(request.createdAt))
        }

        everyone.forEach { profile ->
            if (profile.id == myId || profile.id in known) return@forEach
            known += profile.id
            rows += profile.toFriend(FriendRelation.None)
        }

        _friends.value = rows
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
