package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.data.model.OutboxEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The outgoing-message queue, on disk.
 *
 * Mirrors the reference's `queuePendingMessage` / `getPendingMessages` / `removePendingMessage`
 * (see `src/utils/offlineStorage.ts` in `nchat-friends`) with the file system standing in for
 * IndexedDB: an entry is written before the request goes out and deleted when the server confirms
 * it, so a message survives a dead network, a backgrounded app or the process being killed. The
 * queue is flushed on startup and whenever the realtime socket comes back up.
 *
 * Every entry carries the account it belongs to, so signing in as somebody else can never send
 * the previous user's pending text.
 */
class OutboxStore internal constructor(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "outbox.json"))

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    fun all(): List<OutboxEntry> = synchronized(lock) { read() }

    fun find(clientId: String): OutboxEntry? = synchronized(lock) {
        read().firstOrNull { it.clientId == clientId }
    }

    /** Update an existing entry only; never resurrect one deleted during an upload. */
    fun replaceIfPresent(entry: OutboxEntry): Boolean = synchronized(lock) {
        val rows = read()
        if (rows.none { it.clientId == entry.clientId }) return@synchronized false
        write(rows.map { if (it.clientId == entry.clientId) entry else it })
        true
    }

    fun add(entry: OutboxEntry) = synchronized(lock) {
        val rows = read().filterNot { it.clientId == entry.clientId } + entry
        write(rows)
    }

    fun remove(clientId: String) = synchronized(lock) {
        write(read().filterNot { it.clientId == clientId })
    }

    /** Records a failed attempt and returns the new count. */
    fun bumpAttempts(clientId: String): Int = synchronized(lock) {
        var attempts = 0
        write(
            read().map { row ->
                if (row.clientId == clientId) row.copy(attempts = row.attempts + 1).also { attempts = it.attempts }
                else row
            }
        )
        attempts
    }

    fun clear() = synchronized(lock) { runCatching { file.delete() } }

    fun clearExcept(ownerId: String) = synchronized(lock) {
        write(read().filter { it.ownerId == ownerId })
    }

    private fun read(): List<OutboxEntry> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(SERIALIZER, file.readText())
            // Builds before 1.0.37 numbered sends per launch ("local-1", "local-2", …), which
            // collide with the worker's `client_message_id` idempotency key and would be
            // answered with an old message instead of being delivered. Those entries are dead
            // weight; dropping them is safer than replaying them.
            .filterNot { LEGACY_ID.matches(it.clientId) }
    }.getOrDefault(emptyList())

    private fun write(rows: List<OutboxEntry>) {
        runCatching {
            if (rows.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(SERIALIZER, rows))
        }
    }

    private companion object {
        val SERIALIZER = ListSerializer(OutboxEntry.serializer())
        val LEGACY_ID = Regex("^local-.*")
    }
}
