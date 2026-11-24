package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.core.util.CrashLog
import com.nchat.app.data.model.Message
import com.nchat.app.network.LocalSeal
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
 * - **Sealed.** Every file is sealed for the account that is signed in and for the place it
 *   lies in (see [LocalFiles]): the cache holds the opened text of encrypted messages, and
 *   that must not be readable off the disk. With nothing to seal with, nothing is written.
 * - **Per account.** The owner id is in the path: a second sign-in can never read the previous
 *   account's messages, even if the process was killed before a sign-out could clean up.
 * - **Atomic replacement.** Written to a unique temporary file in the same directory and renamed;
 *   a failed replacement leaves the previous cache untouched.
 * - **Bounded.** Only the newest [MAX_MESSAGES] per chat are kept; the rest is the server's job.
 * - **Never a source of truth.** The server's answer replaces what is here, message by message.
 */
class MessageStore internal constructor(
    private val root: File,
    seal: () -> LocalSeal?,
    private val replace: (File, File) -> Boolean = LocalFiles.RENAME
) {
    constructor(context: Context, seal: () -> LocalSeal?) :
        this(File(context.applicationContext.filesDir, "cache/messages"), seal)

    private val lock = Any()
    private val files = LocalFiles(seal)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Newest [MAX_MESSAGES] messages of one chat; empty when nothing was ever stored — and
     * when what is stored cannot be opened: sealed for another account, damaged, or sealed
     * with nothing at hand to open it. A cache answers "nothing" rather than guess.
     */
    fun load(ownerId: String, chatId: String): List<Message> = synchronized(lock) {
        runCatching {
            val file = fileFor(ownerId, chatId)
            val stored = files.read(file, placeOf(file)) as? Stored.Readable ?: return@synchronized emptyList()
            val rows = json.decodeFromString(LIST_SERIALIZER, stored.text)
                .filter { it.chatId.isNotBlank() }
                .sortedBy { it.timestamp }
            // Written by a build before sealing: read once, sealed from then on.
            if (stored.plain) files.write(file, placeOf(file), stored.text, TEMPORARY_PREFIX, replace = replace)
            rows
        }.getOrDefault(emptyList())
    }

    /**
     * Persist even an empty list, so the last deleted message cannot return from old cache.
     * False when it was not written — also when there is nothing to seal it with: a
     * conversation is never put on disk unsealed.
     */
    fun save(ownerId: String, chatId: String, messages: List<Message>): Boolean = synchronized(lock) {
        if (ownerId.isBlank() || chatId.isBlank()) return@synchronized false
        val trimmed = messages.sortedBy { it.timestamp }.takeLast(MAX_MESSAGES)
        try {
            val file = fileFor(ownerId, chatId)
            // Never delete the last committed cache to work around a failed rename.
            files.write(file, placeOf(file), json.encodeToString(LIST_SERIALIZER, trimmed), TEMPORARY_PREFIX, replace = replace)
        } catch (error: Exception) {
            CrashLog.note("conversation cache write failed", error)
            false
        }
    }

    /**
     * Seals the conversations a build before sealing left on this phone and that nobody has
     * opened since; answers how many. Meant to run once after start-up, off the main thread.
     * With nothing to seal with it does nothing, and the next start tries again.
     *
     * Each file is sealed for the place it lies in, so whoever it belonged to reads it as
     * before — if it is theirs to open at all. Write attempts that died half-way are removed:
     * those of an earlier build were not sealed either.
     */
    fun sealPlainFiles(): Int {
        var count = 0
        val owners = synchronized(lock) { root.listFiles()?.filter { it.isDirectory }.orEmpty() }
        for (owner in owners) {
            val entries = synchronized(lock) { owner.listFiles()?.filter { it.isFile }.orEmpty() }
            for (file in entries) {
                // One file at a time: a conversation being opened does not wait for the sweep.
                if (synchronized(lock) { sealOrRemove(file) }) count++
            }
        }
        return count
    }

    /** True when [file] was a conversation from before sealing and is sealed now. */
    private fun sealOrRemove(file: File): Boolean {
        if (file.name.endsWith(LocalFiles.TEMPORARY_SUFFIX)) {
            runCatching { file.delete() }
            return false
        }
        return file.name.endsWith(EXTENSION) && files.sealPlain(file, placeOf(file), TEMPORARY_PREFIX, replace)
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
        File(File(root, safe(ownerId)), safe(chatId) + EXTENSION)

    /** What a conversation is sealed for: put where another one belongs, it does not open. */
    private fun placeOf(file: File): String =
        "messages/" + file.parentFile?.name.orEmpty() + "/" + file.name.removeSuffix(EXTENSION)

    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "_" }

    private companion object {
        val LIST_SERIALIZER = ListSerializer(Message.serializer())
        const val MAX_MESSAGES = 300
        const val EXTENSION = ".json"
        const val TEMPORARY_PREFIX = "message-"
    }
}
