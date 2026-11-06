package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.data.model.Message
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Every conversation, on disk.
 *
 * The chat list has been cached since 1.0.24 and the friend graph since 1.0.42, but the
 * messages themselves lived only in memory: close the app and open a chat and the screen was
 * empty until `/messages/with/:id` answered — which, offline or on a bad connection, meant an
 * empty conversation. No messenger works like that. WhatsApp, Telegram and Signal keep the
 * message store on the device and treat the server as a second copy, so a chat always opens
 * with its history, instantly, with or without a network.
 *
 * Layout: `cache/messages/<ownerId>/<chatId>.json`, one file per conversation, so writing one
 * chat never rewrites the others and a corrupt file costs exactly one chat.
 *
 * Rules that matter:
 * - **Per account.** The owner id is in the path: a second sign-in can never read the previous
 *   account's messages, even if the process was killed before a sign-out could clean up.
 * - **Atomic.** Written to `<file>.tmp` and renamed, so a kill (or a cancelled write) leaves the
 *   previous good file rather than a truncated one.
 * - **Bounded.** Only the newest [MAX_MESSAGES] per chat are kept; the rest is the server's job.
 * - **Never a source of truth.** The server's answer replaces what is here, message by message.
 */
class MessageStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "cache/messages")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Newest [MAX_MESSAGES] messages of one chat; empty when nothing was ever stored. */
    fun load(ownerId: String, chatId: String): List<Message> = runCatching {
        val file = fileFor(ownerId, chatId)
        if (!file.exists()) return emptyList()
        json.decodeFromString(LIST_SERIALIZER, file.readText())
            .filter { it.chatId.isNotBlank() }
            .sortedBy { it.timestamp }
    }.getOrDefault(emptyList())

    fun save(ownerId: String, chatId: String, messages: List<Message>) {
        if (ownerId.isBlank() || chatId.isBlank() || messages.isEmpty()) return
        val trimmed = messages.sortedBy { it.timestamp }.takeLast(MAX_MESSAGES)
        runCatching {
            val file = fileFor(ownerId, chatId)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(LIST_SERIALIZER, trimmed))
            // Atomic replace: a half-written file is worse than a missing one.
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    fun clear(ownerId: String, chatId: String) {
        runCatching { fileFor(ownerId, chatId).delete() }
    }

    /** Removes every cached conversation of one account. */
    fun clearOwner(ownerId: String) {
        if (ownerId.isBlank()) return
        runCatching { File(root, safe(ownerId)).deleteRecursively() }
    }

    /** Removes every cached conversation of every account (sign-out, account switch). */
    fun clearAll() {
        runCatching { root.deleteRecursively() }
    }

    private fun fileFor(ownerId: String, chatId: String): File =
        File(File(root, safe(ownerId)), safe(chatId) + ".json")

    /** Ids are uuids or group ids, but a filename is not a place to trust that. */
    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "_" }

    private companion object {
        val LIST_SERIALIZER = ListSerializer(Message.serializer())

        /** ~300 bubbles is months of a normal chat and keeps each file well under a megabyte. */
        const val MAX_MESSAGES = 300
    }
}
