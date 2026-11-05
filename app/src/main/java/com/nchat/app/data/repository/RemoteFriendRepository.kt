package com.nchat.app.data.repository

import com.nchat.app.data.local.BlockedContactsStore
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
            runCatching { api.sendFriendRequest(id) }
                .onFailure { error ->
                    _pendingSent.update { it - id }
                    _actionError.value = "Couldn't send the request — ${error.describeAction()}"
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

    private suspend fun reload() {
        val accepted = runCatching { api.friends().friends }.getOrDefault(emptyList())
        val requests = runCatching { api.friendRequests().requests }.getOrDefault(emptyList())
        val sent = runCatching { api.sentRequests() }.getOrNull()
        val everyone = runCatching { api.allUsers().profiles }.getOrDefault(emptyList())
        val blocked = runCatching { api.blockedUsers().blocked }.getOrDefault(emptyList())

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
