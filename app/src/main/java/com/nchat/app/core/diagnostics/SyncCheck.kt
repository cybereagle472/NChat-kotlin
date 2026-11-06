package com.nchat.app.core.diagnostics

import com.nchat.app.network.ApiException
import com.nchat.app.network.NChatApi
import java.io.IOException

/** One endpoint and what it answered. */
data class CheckRow(
    val name: String,
    val path: String,
    val ok: Boolean,
    val detail: String,
    val millis: Long
)

/**
 * Asks every endpoint the app depends on the same question it asks while building a screen —
 * and reports the answer instead of hiding it.
 *
 * Every repository turns a failure into an empty list so a screen never shows a red box; the
 * cost is that "my chats are gone" and "the request failed" look identical. This runs the
 * calls one by one and keeps the HTTP status, the server's own error text and the item count,
 * which is what tells the two apart in one look.
 */
object SyncCheck {

    suspend fun run(api: NChatApi): List<CheckRow> {
        val rows = mutableListOf<CheckRow>()

        suspend fun probe(name: String, path: String, block: suspend () -> String) {
            val started = System.currentTimeMillis()
            val detail = try {
                block()
            } catch (error: ApiException) {
                "HTTP ${error.status}: ${error.message}"
            } catch (error: IOException) {
                "network: ${error.message ?: error.javaClass.simpleName}"
            } catch (error: Throwable) {
                // A decode failure is the interesting one: the server answered, the shape
                // was not what the client expected.
                "${error.javaClass.simpleName}: ${error.message ?: "no message"}"
            }
            val ok = !detail.startsWith("HTTP ") &&
                !detail.startsWith("network:") &&
                !detail.contains("Exception")
            rows += CheckRow(
                name = name,
                path = path,
                ok = ok,
                detail = detail,
                millis = System.currentTimeMillis() - started
            )
        }

        probe("Account", "/auth/me") {
            val user = api.me().user ?: return@probe "no user in the answer"
            "${user.fullName ?: "?"} · ${user.email ?: "?"}"
        }
        probe("My profile", "/users/me") {
            val profile = api.myProfile().profile ?: return@probe "no profile in the answer"
            "avatar ${if (profile.avatarUrl.isNullOrBlank()) "none" else "set"} · " +
                "@${profile.username ?: "-"} · id ${profile.id.take(8)}"
        }
        probe("Chats", "/messages/chats") {
            val chats = api.chats().chats
            "${chats.size} conversations · ${chats.sumOf { it.unreadCount }} unread"
        }
        probe("Friends", "/friends") {
            val rows = api.friends().friends
            val readable = rows.count { !it.profile?.id.isNullOrBlank() }
            val fromId = rows.size - readable
            if (fromId == 0) {
                "${rows.size} friends"
            } else {
                "${rows.size} friends · $readable with a profile, $fromId only by id"
            }
        }
        probe("Friend requests", "/friends/requests") {
            "${api.friendRequests().requests.size} incoming"
        }
        probe("People (Find)", "/users") {
            "${api.allUsers().profiles.size} people"
        }
        probe("Search (Find)", "/users/search?q=a") {
            "${api.searchUsers("a").profiles.size} matches for \"a\""
        }
        /*
         * The two views of the same relationship, side by side.
         *
         * `/friends` and `/messages/chats` are the same `friendships` JOIN on the worker
         * (`routes/friends.ts:177` / `routes/messages.ts:83`), so they must name the same
         * people. Any conversation missing from `/friends` is the exact shape of "my chats are
         * there but my friends are not" — print the ids instead of making the next report guess.
         */
        probe("Friends vs chats", "/messages/chats") {
            val chatPeers = runCatching { api.chats().chats }
                .getOrDefault(emptyList())
                .map { it.userId }
                .filter { it.isNotBlank() }
                .toSet()
            val friendIds = runCatching { api.friends().friends }
                .getOrDefault(emptyList())
                .mapNotNull { it.profile?.id?.takeIf { id -> id.isNotBlank() } ?: it.id.takeIf { id -> id.isNotBlank() } }
                .toSet()
            val missing = chatPeers - friendIds
            when {
                chatPeers.isEmpty() -> "no 1:1 conversations to compare"
                missing.isEmpty() -> "all ${chatPeers.size} conversations are in /friends"
                else -> "${missing.size} conversation(s) NOT in /friends: " +
                    missing.take(3).joinToString { it.take(8) }
            }
        }
        probe("Groups", "/groups") {
            "${api.groups().groups.size} groups"
        }
        probe("Status feed", "/status/feed") {
            "${api.statusFeed().statuses.size} updates"
        }
        probe("Call history", "/calls/history") {
            "${api.callHistory(limit = 20).history.size} calls"
        }

        // ---------------------------------------------------------------- audit
        //
        // Everything above answers "did the request work". These answer "is the data under
        // *this* account", which is the question that decides whether a fix belongs in the app
        // at all: a second row for the same email means the friends/messages live under an id
        // this session is not signed into, and a message count for someone who is no longer in
        // `/friends` means only the friendship link was lost — the history is still there.
        val me = runCatching { api.myProfile().profile }.getOrNull()
        val everyone = runCatching { api.allUsers().profiles }.getOrDefault(emptyList())
        val friendIds = runCatching { api.friends().friends.map { it.id } }
            .getOrDefault(emptyList())
            .toSet()

        probe("Account duplication", "/users") {
            val myEmail = me?.email?.lowercase()?.takeIf { it.isNotBlank() }
            val myHandle = me?.username?.lowercase()?.takeIf { it.isNotBlank() }
            val twins = everyone.filter { person ->
                person.id != me?.id && (
                    (myEmail != null && person.email?.lowercase() == myEmail) ||
                        (myHandle != null && person.username?.lowercase() == myHandle)
                    )
            }
            if (twins.isEmpty()) {
                "one account only (${everyone.size} other people on the server)"
            } else {
                twins.joinToString("; ") { twin ->
                    "SECOND account ${twin.id} · ${twin.fullName ?: twin.email ?: "?"}"
                }
            }
        }

        val calls = runCatching { api.callHistory(limit = 20).history }.getOrDefault(emptyList())
        val counterparts = calls
            .mapNotNull { call -> call.otherProfile?.let { it.id to it } }
            .distinctBy { it.first }
            .take(3)

        // A plain loop, not forEach: `probe` suspends, so its call site has to be suspend too.
        for ((otherId, profile) in counterparts) {
            val label = profile.fullName ?: profile.username ?: otherId.take(8)
            probe("Old chat · $label", "/messages/with/${otherId.take(8)}") {
                val count = api.conversation(otherId, limit = 200).messages.size
                val linked = if (otherId in friendIds) "still a friend" else "NOT in /friends"
                "$count messages · $linked"
            }
        }

        return rows
    }
}
