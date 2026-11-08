package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.core.util.CrashLog
import com.nchat.app.data.model.OutboxEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The outgoing-message queue, on disk. All mutations are serialized in this instance and use a
 * same-directory temporary file, so an interrupted write does not truncate the committed queue.
 * Each entry carries its owning account; the caller filters before delivery.
 */
class OutboxStore internal constructor(
    private val file: File,
    private val replace: (File, File) -> Boolean = { source, target -> source.renameTo(target) }
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "outbox.json"))

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    fun all(): List<OutboxEntry> = synchronized(lock) { read() ?: emptyList() }

    fun find(clientId: String): OutboxEntry? = synchronized(lock) {
        read()?.firstOrNull { it.clientId == clientId }
    }

    /** Update an existing entry only; never resurrect one deleted during an upload. */
    fun replaceIfPresent(entry: OutboxEntry): Boolean = synchronized(lock) {
        val rows = read() ?: return@synchronized false
        if (rows.none { it.clientId == entry.clientId }) return@synchronized false
        write(rows.map { if (it.clientId == entry.clientId) entry else it })
    }

    /** False means the entry was not saved; callers must not pretend it is queued. */
    fun add(entry: OutboxEntry): Boolean = synchronized(lock) {
        val rows = read() ?: return@synchronized false
        write(rows.filterNot { it.clientId == entry.clientId } + entry)
    }

    /** False means the queue could not be read or the removal could not be persisted. */
    fun remove(clientId: String): Boolean = synchronized(lock) {
        val rows = read() ?: return@synchronized false
        val remaining = rows.filterNot { it.clientId == clientId }
        if (remaining.size == rows.size) return@synchronized true
        write(remaining)
    }

    /** Removes queued sends belonging to one conversation/account before that chat is cleared. */
    fun removeConversation(chatId: String, ownerId: String): List<OutboxEntry>? = synchronized(lock) {
        val rows = read() ?: return@synchronized null
        val removed = rows.filter { it.chatId == chatId && it.ownerId == ownerId }
        if (removed.isNotEmpty() && !write(rows.filterNot { it.chatId == chatId && it.ownerId == ownerId })) {
            return@synchronized null
        }
        removed
    }

    /** Records a failed attempt and returns its persisted count, or zero if it could not be saved. */
    fun bumpAttempts(clientId: String): Int = synchronized(lock) {
        val rows = read() ?: return@synchronized 0
        var attempts = 0
        val next = rows.map { row ->
            if (row.clientId == clientId) row.copy(attempts = row.attempts + 1).also { attempts = it.attempts }
            else row
        }
        if (attempts == 0 || !write(next)) 0 else attempts
    }

    fun clear(): Boolean = synchronized(lock) {
        runCatching {
            val committedRemoved = !file.exists() || file.delete()
            // A process death during a write can leave an uncommitted copy behind.
            val leftovers = file.absoluteFile.parentFile?.listFiles().orEmpty()
                .filter { it.name.startsWith("outbox-") && it.name.endsWith(".tmp") }
            val leftoversRemoved = leftovers.map { it.delete() }.all { it }
            committedRemoved && leftoversRemoved
        }.getOrDefault(false)
    }

    fun clearExcept(ownerId: String): Boolean = synchronized(lock) {
        val rows = read() ?: return@synchronized false
        write(rows.filter { it.ownerId == ownerId })
    }

    /** Null is an unreadable/corrupt queue, never an empty queue to overwrite. */
    private fun read(): List<OutboxEntry>? = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(SERIALIZER, file.readText())
            .filterNot { LEGACY_ID.matches(it.clientId) }
    }.onFailure { CrashLog.note("outbox read failed", it) }.getOrNull()

    private fun write(rows: List<OutboxEntry>): Boolean {
        var temporary: File? = null
        return try {
            val parent = file.absoluteFile.parentFile ?: throw IOException("Outbox has no parent directory")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create outbox directory")
            val temp = File.createTempFile("outbox-", ".tmp", parent)
            temporary = temp
            FileOutputStream(temp).use { stream ->
                stream.write(json.encodeToString(SERIALIZER, rows).toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            // On Android's internal filesystem this is a same-directory atomic rename.
            // Never delete the committed file as a fallback if replacement fails.
            if (!replace(temp, file.absoluteFile)) throw IOException("Could not replace outbox file")
            true
        } catch (error: Exception) {
            CrashLog.note("outbox write failed", error)
            false
        } finally {
            runCatching { temporary?.delete() }
        }
    }

    private companion object {
        val SERIALIZER = ListSerializer(OutboxEntry.serializer())
        val LEGACY_ID = Regex("^local-.*")
    }
}
