package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.core.util.CrashLog
import com.nchat.app.data.model.OutboxEntry
import com.nchat.app.network.LocalSeal
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The outgoing-message queue, on disk. All mutations are serialized in this instance and use a
 * same-directory temporary file, so an interrupted write does not truncate the committed queue.
 * Each entry carries its owning account; the caller filters before delivery.
 *
 * The queue is sealed for the account that is signed in (see [LocalFiles]): an entry holds the
 * text of a message that has not been encrypted for its recipient yet, and the address of a
 * sealed file together with its key. With nothing to seal with, nothing is queued — [add]
 * answers false and the message is shown as not sent, never written down unsealed.
 *
 * It outlives a log-out that keeps what the phone holds, and waits for its account; [clear] is
 * for "Log out and erase" and for a phone that starts anew for an account (see `LocalState`).
 */
class OutboxStore internal constructor(
    private val file: File,
    seal: () -> LocalSeal?,
    private val replace: (File, File) -> Boolean = LocalFiles.RENAME
) {
    constructor(context: Context, seal: () -> LocalSeal?) :
        this(File(context.applicationContext.filesDir, "outbox.json"), seal)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()
    private val files = LocalFiles(seal)

    /** Guarded by [lock]: a damaged queue is reported once, not at every look. */
    private var damageReported = false

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
                .filter { it.name.startsWith(TEMPORARY_PREFIX) && it.name.endsWith(LocalFiles.TEMPORARY_SUFFIX) }
            val leftoversRemoved = leftovers.map { it.delete() }.all { it }
            committedRemoved && leftoversRemoved
        }.getOrDefault(false)
    }

    fun clearExcept(ownerId: String): Boolean = synchronized(lock) {
        val rows = read() ?: return@synchronized false
        write(rows.filter { it.ownerId == ownerId })
    }

    /**
     * Null is a queue that cannot be read — never an empty queue to overwrite. That is a file
     * that is damaged, and one that is sealed while there is nothing to open it with.
     *
     * A queue sealed with another key is a different matter: it was another account's, or this
     * one's before it was erased from the phone. It is nobody's to send any more and will never
     * open here, so it counts as empty and the next write replaces it. Otherwise whoever signs
     * in next could not queue a message at all.
     *
     * The queue of an account that only logged out is neither: it cannot be read while the
     * account is away, opens again when it is back, and what was waiting is sent then.
     */
    private fun read(): List<OutboxEntry>? = runCatching {
        when (val stored = files.read(file, PLACE)) {
            Stored.Absent, Stored.Foreign -> emptyList()
            Stored.Locked -> null
            Stored.Damaged -> {
                if (!damageReported) {
                    damageReported = true
                    CrashLog.note("outbox read failed", IOException("the sealed queue does not open"))
                }
                null
            }
            is Stored.Readable -> {
                val rows = json.decodeFromString(SERIALIZER, stored.text)
                    .filterNot { LEGACY_ID.matches(it.clientId) }
                // Written by a build before sealing: read once, sealed from then on.
                if (stored.plain) write(rows)
                rows
            }
        }
    }.onFailure { CrashLog.note("outbox read failed", it) }.getOrNull()

    /** False also when there is nothing to seal with: the queue is never put on disk unsealed. */
    private fun write(rows: List<OutboxEntry>): Boolean = try {
        // Durable: an entry that is reported as queued must survive the phone losing power.
        files.write(file, PLACE, json.encodeToString(SERIALIZER, rows), TEMPORARY_PREFIX, durable = true, replace = replace)
    } catch (error: Exception) {
        CrashLog.note("outbox write failed", error)
        false
    }

    private companion object {
        val SERIALIZER = ListSerializer(OutboxEntry.serializer())
        val LEGACY_ID = Regex("^local-.*")
        const val PLACE = "outbox"
        const val TEMPORARY_PREFIX = "outbox-"
    }
}
