package com.nchat.app.data.repository

import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.FriendRelation
import com.nchat.app.network.FriendRequestDto
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.TokenStore
import com.nchat.app.network.UserDto
import kotlinx.coroutines.CoroutineScope
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
    private val scope: CoroutineScope
) : FriendRepository {

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    override val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _blockedNames = MutableStateFlow<Set<String>>(emptySet())
    override val blockedNames: StateFlow<Set<String>> = _blockedNames.asStateFlow()

    private var incoming: List<FriendRequestDto> = emptyList()
    private var outgoing: List<FriendRequestDto> = emptyList()
    private var blockedUsers: List<UserDto> = emptyList()

    private val myId: String get() = tokens.userId.orEmpty()

    init {
        scope.launch {
            socket.events.collect { event ->
                when (event.type) {
                    "friend:request", "friend:accepted", "friend:declined", "friend:removed" -> refresh()
                }
            }
        }
        refresh()
    }

    fun refresh() {
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
        scope.launch {
            runCatching { api.sendFriendRequest(id) }
            refresh()
        }
    }

    override fun cancelRequest(id: String) {
        val request = outgoing.firstOrNull { it.receiverId == id } ?: return
        scope.launch {
            runCatching { api.cancelRequest(request.id) }
            refresh()
        }
    }

    override fun unfriend(id: String) {
        scope.launch {
            runCatching { api.removeFriend(id) }
            refresh()
        }
    }

    override fun block(name: String) {
        val target = resolveId(name) ?: return
        scope.launch {
            runCatching { api.blockUser(target) }
            refresh()
        }
    }

    override fun unblock(name: String) {
        val target = blockedUsers.firstOrNull { it.displayName() == name.trim() }?.id ?: return
        scope.launch {
            runCatching { api.unblockUser(target) }
            refresh()
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
        blockedUsers = blocked
        _blockedNames.value = blocked.map { it.displayName() }.toSet()

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

    private fun resolveId(name: String): String? {
        val key = name.trim()
        if (key.isEmpty()) return null
        return _friends.value.firstOrNull { it.name == key || it.id == key }?.id
    }

    private fun UserDto.toFriend(relation: FriendRelation, requestedAt: Long? = null) = Friend(
        id = id,
        name = displayName(),
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
