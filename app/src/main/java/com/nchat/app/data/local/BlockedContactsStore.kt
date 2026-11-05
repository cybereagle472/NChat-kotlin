package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.data.model.BlockedContact
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The block list, kept on the device on purpose.
 *
 * WhatsApp, Telegram and Messenger all treat a block as a *pause*, never a deletion: the
 * contact stays in your list (marked), the chat and its entire history stay exactly where you
 * left them, and unblocking resumes the conversation from that same point.
 *
 * The reference worker cannot express that. Its `POST /friends/block` runs
 *
 * ```sql
 * DELETE FROM friendships WHERE (user1_id = ? AND user2_id = ?) OR (user1_id = ? AND user2_id = ?)
 * ```
 *
 * so asking the server to block somebody destroys the friendship row — and `POST
 * /friends/unblock` only clears `blocked_users`, it never puts the friendship back. The chat
 * list is built from `friendships`, so a blocked-then-unblocked contact would come back with
 * no chat at all.
 *
 * So the app keeps the block itself: `blockedIds` drives the UI (hidden from Friends/Find,
 * composer replaced by an "unblock" banner, no new messages) while the friendship and both
 * sides of the history stay untouched on the server. Unblocking is then a pure local removal
 * and everything reappears exactly as it was. `POST /friends/unblock` is still sent when a
 * *legacy* server-side block exists, because that call is harmless — it only deletes the row
 * in `blocked_users`.
 */
class BlockedContactsStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "blocked.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<BlockedContact> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(LIST_SERIALIZER, file.readText())
    }.getOrDefault(emptyList())

    fun save(contacts: List<BlockedContact>) {
        runCatching {
            if (contacts.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(LIST_SERIALIZER, contacts))
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        val LIST_SERIALIZER = ListSerializer(BlockedContact.serializer())
    }
}
