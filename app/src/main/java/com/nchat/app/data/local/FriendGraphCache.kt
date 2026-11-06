package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.FriendRelation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The last known friend graph, kept on disk.
 *
 * The chat list already worked like this ([ChatListCache]): paint from the device, then let
 * the server's answer replace it. The friend graph did not — it lived in memory only, so a cold
 * start with a dead network, a 500 from `/friends`, or a token refresh landing at the same
 * moment opened the Friends tab on "No friends yet" while the Chats tab happily showed the very
 * same people from its cache. That asymmetry is what made "my friends disappeared" possible at
 * all.
 *
 * Only the relationships the server actually gave us are stored ([FriendRelation.Friend],
 * `Incoming`, `Outgoing`). The `None` rows are the whole user directory — re-fetched on every
 * load, and useless (and long) on disk.
 *
 * It is a *cache*, never a source of truth: the next successful `/friends` replaces it
 * wholesale, a corrupt file is ignored, and every entry carries the account it belongs to so a
 * second sign-in can never read somebody else's friends.
 */
class FriendGraphCache(context: Context) {

    private val file = File(context.applicationContext.filesDir, "cache/friends.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Snapshot(val owner: String = "", val friends: List<Friend> = emptyList())

    /**
     * Everything worth restoring, for [ownerId] only; empty when the file is missing, stale or
     * unreadable. A blank [ownerId] means "not signed in yet" and reads nothing on purpose.
     */
    fun load(ownerId: String): List<Friend> = runCatching {
        if (ownerId.isBlank() || !file.exists()) return emptyList()
        val snapshot = json.decodeFromString(SNAPSHOT_SERIALIZER, file.readText())
        if (snapshot.owner != ownerId) return emptyList()
        snapshot.friends.filter { it.id.isNotBlank() }
    }.getOrDefault(emptyList())

    /**
     * Writes the graph, skipping the rows that carry no relationship.
     *
     * An empty list is not written: that would turn "the server answered nothing just now" into
     * "this account has no friends" for the next start-up, which is the exact confusion this
     * file exists to prevent.
     */
    fun save(ownerId: String, friends: List<Friend>) {
        if (ownerId.isBlank()) return
        val worthKeeping = friends.filter { it.id.isNotBlank() && it.relation != FriendRelation.None }
        if (worthKeeping.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(SNAPSHOT_SERIALIZER, Snapshot(ownerId, worthKeeping)))
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        val SNAPSHOT_SERIALIZER = Snapshot.serializer()
    }
}
