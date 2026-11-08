package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.core.util.CrashLog
import com.nchat.app.data.model.Message
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

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
 * - **Atomic replacement.** Written to a unique temporary file in the same directory and renamed;
 *   a failed replacement leaves the previous cache untouched.
 * - **Bounded.** Only the newest [MAX_MESSAGES] per chat are kept; the rest is the server's job.
 * - **Never a source of truth.** The server's answer replaces what is here, message by message.
 */
class MessageStore internal constructor(
    private val root: File,
    private val replace: (File, File) -> Boolean = { source, target -> source.renameTo(target) }
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "cache/messages"))

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Newest [MAX_MESSAGES] messages of one chat; empty when nothing was ever stored. */
    fun load(ownerId: String, chatId: String): List<Message> = synchronized(lock) {
        runCatching {
            val file = fileFor(ownerId, chatId)
            if (!file.exists()) return@synchronized emptyList()
            json.decodeFromString(LIST_SERIALIZER, file.readText())
                .filter { it.chatId.isNotBlank() }
                .sortedBy { it.timestamp }
        }.getOrDefault(emptyList())
    }

    /** Persist even an empty list, so the last deleted message cannot return from old cache. */
    fun save(ownerId: String, chatId: String, messages: List<Message>): Boolean = synchronized(lock) {
        if (ownerId.isBlank() || chatId.isBlank()) return@synchronized false
        val trimmed = messages.sortedBy { it.timestamp }.takeLast(MAX_MESSAGES)
        var temp: File? = null
        try {
            val file = fileFor(ownerId, chatId)
            val parent = file.parentFile ?: throw IOException("Conversation cache has no parent")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create cache directory")
            val candidate = File.createTempFile("message-", ".tmp", parent)
            temp = candidate
            candidate.writeText(json.encodeToString(LIST_SERIALIZER, trimmed))
            // Never delete the last committed cache to work around a failed rename.
            if (!replace(candidate, file)) throw IOException("Could not replace conversation cache")
            true
        } catch (error: Exception) {
            CrashLog.note("conversation cache write failed", error)
            false
        } finally {
            runCatching { temp?.delete() }
        }
    }

    fun clear(ownerId: String, chatId: String) = synchronized(lock) {
        runCatching { fileFor(ownerId, chatId).delete() }
        Unit
    }

    /** Removes every cached conversation of one account. */
    fun clearOwner(ownerId: String) = synchronized(lock) {
        if (ownerId.isNotBlank()) runCatching { File(root, safe(ownerId)).deleteRecursively() }
        Unit
    }

    /** Removes every cached conversation of every account (sign-out, account switch). */
    fun clearAll() = synchronized(lock) {
        runCatching { root.deleteRecursively() }
        Unit
    }

    private fun fileFor(ownerId: String, chatId: String): File =
        File(File(root, safe(ownerId)), safe(chatId) + ".json")

    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "_" }

    private companion object {
        val LIST_SERIALIZER = ListSerializer(Message.serializer())
        const val MAX_MESSAGES = 300
    }
}
