package com.nchat.app.network

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * What one sealed message contains once opened: a type tag, a body, for a
 * reply the id of the message it answers, and for a photo, video, voice
 * message or file the pointer to it ([E2eeAttachment.Pointer], with the key
 * that opens it). All of it travels inside the ciphertext, so the server can
 * neither tell a text from anything else nor see or change what a message
 * replies to — and a build that meets a tag it does not know can say "update
 * NChat" instead of misreading it.
 *
 * Encoded as the type byte followed by fields, each `tag (1 byte) || length
 * (4 bytes, big-endian) || value`. A reader skips tags it does not know, so
 * a later build can add fields without breaking this one. The size of an
 * encoded payload is its content plus a few bytes, whatever the content is.
 */
class E2eePayload(val type: Int, val body: String, replyTo: String? = null, attachment: String? = null) {

    /** The id of the message this one replies to. */
    val replyTo: String? = replyTo?.takeIf { it.isNotEmpty() }

    /** Where the file of this message is and the key to it: an encoded [E2eeAttachment.Pointer]. */
    val attachment: String? = attachment?.takeIf { it.isNotEmpty() }

    /** A message whose content is a file; [body] is then its caption. */
    val isAttachment: Boolean get() = type == PHOTO || type == VIDEO || type == VOICE || type == DOCUMENT

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type)
        if (body.isNotEmpty()) out.writeField(BODY, body)
        replyTo?.let { out.writeField(REPLY_TO, it) }
        attachment?.let { out.writeField(ATTACHMENT, it) }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeField(tag: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        write(tag)
        for (shift in intArrayOf(24, 16, 8, 0)) write(bytes.size ushr shift)
        write(bytes, 0, bytes.size)
    }

    companion object {
        const val TEXT = 1

        /** A shared position: `latitude,longitude`. */
        const val LOCATION = 2

        /** A file, with [attachment] pointing at it and [body] as its caption. */
        const val PHOTO = 3
        const val VIDEO = 4
        const val VOICE = 5
        const val DOCUMENT = 6

        /** Content that does not decode. No build ever sends this type. */
        const val UNREADABLE = 0

        private const val BODY = 1
        private const val REPLY_TO = 2
        private const val ATTACHMENT = 3
        private const val FIELD_HEADER_BYTES = 5

        fun text(body: String, replyTo: String? = null) = E2eePayload(TEXT, body, replyTo)

        /** Total: an authenticated plaintext always decodes; an unknown [type] is the reader's to handle. */
        fun decode(bytes: ByteArray): E2eePayload {
            if (bytes.isEmpty()) return E2eePayload(UNREADABLE, "")
            var body = ""
            var replyTo: String? = null
            var attachment: String? = null
            var at = 1
            while (at < bytes.size) {
                if (bytes.size - at < FIELD_HEADER_BYTES) return E2eePayload(UNREADABLE, "")
                val tag = bytes[at].toInt() and 0xff
                var length = 0
                for (index in 1..4) length = (length shl 8) or (bytes[at + index].toInt() and 0xff)
                at += FIELD_HEADER_BYTES
                if (length < 0 || length > bytes.size - at) return E2eePayload(UNREADABLE, "")
                when (tag) {
                    BODY -> body = String(bytes, at, length, Charsets.UTF_8)
                    REPLY_TO -> replyTo = String(bytes, at, length, Charsets.UTF_8)
                    ATTACHMENT -> attachment = String(bytes, at, length, Charsets.UTF_8)
                }
                at += length
            }
            return E2eePayload(bytes[0].toInt() and 0xff, body, replyTo, attachment)
        }
    }
}

/**
 * The opened content of every sealed message this device has sent or
 * received, kept for good.
 *
 * A ratchet key opens a message exactly once. The server only ever holds the
 * envelope, and the conversation cache keeps a few hundred messages per chat,
 * so without this archive a message would turn unreadable the second time it
 * is fetched: on the next refresh, in the chat-list preview, after scrolling
 * back. This is the device's own copy — what the local message database is in
 * every end-to-end encrypted messenger.
 *
 * Layout: one append-only file per chat, one sealed entry per line (base64).
 * Appending never rewrites history and a torn last line costs only itself.
 * [recent] answers from the tail of the file — where previews and fresh
 * messages live — without reading the rest; [find] reads the whole file once
 * and then answers from memory.
 *
 * Every entry is sealed under the caller's key with the chat as context, so
 * an entry copied into another chat's file does not open there.
 */
class E2eeArchive internal constructor(
    private val directory: File,
    key: ByteArray,
    private val tailBytes: Long,
    private val maxLoadedEntries: Int,
) {

    constructor(directory: File, key: ByteArray) : this(directory, key, TAIL_BYTES, MAX_LOADED_ENTRIES)

    private class Index(val entries: HashMap<String, E2eePayload>, var complete: Boolean)

    private val box = SealedBox(key)
    private val lock = Any()

    /** Chats in memory, least recently used first. */
    private val loaded = LinkedHashMap<String, Index>(16, 0.75f, true)
    private var loadedEntries = 0

    /** Chats with appended entries that have not been synced yet. */
    private val unsynced = LinkedHashSet<String>()

    /** Looks among the newest entries of the chat only — cheap, and where a fresh message would be. */
    fun recent(chatId: String, key: String): E2eePayload? = synchronized(lock) { indexFor(chatId).entries[key] }

    /** Looks through the whole chat; the first call per chat reads its file. */
    fun find(chatId: String, key: String): E2eePayload? = synchronized(lock) {
        val index = indexFor(chatId)
        index.entries[key]?.let { return@synchronized it }
        if (index.complete) return@synchronized null
        read(chatId, index, whole = true)
        trim(chatId)
        index.entries[key]
    }

    /** Appends an entry. It is on stable storage only after [flush]. Throws on failure. */
    fun put(chatId: String, key: String, payload: E2eePayload) {
        synchronized(lock) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("could not create the archive directory")
            val entry = buildJsonObject {
                put("k", key)
                put("y", payload.type)
                put("t", payload.body)
                payload.replyTo?.let { put("r", it) }
                payload.attachment?.let { put("a", it) }
            }.toString()
            val line = encodeBase64(box.seal(CONTEXT + chatId, entry.toByteArray(Charsets.UTF_8)))
            // The leading newline closes a line that a crash left unfinished.
            FileOutputStream(fileFor(chatId), true).use { it.write(("\n" + line + "\n").toByteArray(Charsets.US_ASCII)) }
            unsynced += chatId
            val index = loaded[chatId] ?: return
            if (index.entries.put(key, payload) == null) loadedEntries++
            trim(chatId)
        }
    }

    /** Forces every appended entry to stable storage. Throws when that cannot be guaranteed. */
    fun flush() {
        synchronized(lock) {
            val pending = unsynced.iterator()
            while (pending.hasNext()) {
                // fsync covers the file's data whichever descriptor wrote it.
                RandomAccessFile(fileFor(pending.next()), "rw").use { it.fd.sync() }
                pending.remove()
            }
        }
    }

    /** Forgets everything — the account is leaving this device. */
    fun clear() {
        synchronized(lock) {
            loaded.clear()
            loadedEntries = 0
            unsynced.clear()
            directory.deleteRecursively()
        }
    }

    private fun indexFor(chatId: String): Index {
        loaded[chatId]?.let { return it }
        val index = Index(HashMap(), complete = false)
        loaded[chatId] = index
        read(chatId, index, whole = false)
        trim(chatId)
        return index
    }

    /** Reads the tail of the chat's file into [index], or all of it when [whole]. */
    private fun read(chatId: String, index: Index, whole: Boolean) {
        val before = index.entries.size
        try {
            val file = fileFor(chatId)
            val length = if (file.isFile) file.length() else 0L
            val start = if (whole) 0L else maxOf(0L, length - tailBytes)
            if (length > start) {
                val bytes = ByteArray((length - start).toInt())
                RandomAccessFile(file, "r").use { input ->
                    input.seek(start)
                    input.readFully(bytes)
                }
                val lines = String(bytes, Charsets.US_ASCII).split('\n')
                // A read that starts mid-file starts mid-line: that fragment is not an entry.
                for (line in if (start > 0L) lines.drop(1) else lines) parse(chatId, line, index.entries)
            }
            index.complete = start == 0L
        } catch (_: Exception) {
            // An unreadable file reads as empty; new entries still append to it.
            index.complete = true
        }
        loadedEntries += index.entries.size - before
    }

    private fun parse(chatId: String, line: String, into: HashMap<String, E2eePayload>) {
        if (line.isBlank()) return
        try {
            val sealed = decodeBase64(line.trim()) ?: return
            val opened = box.open(CONTEXT + chatId, sealed) ?: return
            val entry = Json.parseToJsonElement(String(opened, Charsets.UTF_8)).jsonObject
            val key = (entry["k"] as? JsonPrimitive)?.content ?: return
            val type = (entry["y"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return
            val body = (entry["t"] as? JsonPrimitive)?.content ?: return
            into[key] = E2eePayload(type, body, (entry["r"] as? JsonPrimitive)?.content, (entry["a"] as? JsonPrimitive)?.content)
        } catch (_: Exception) {
            // A torn or damaged line costs exactly one entry.
        }
    }

    /** Keeps memory bounded: least recently used chats leave first, never [current]. */
    private fun trim(current: String) {
        val eldest = loaded.entries.iterator()
        while (loadedEntries > maxLoadedEntries && eldest.hasNext()) {
            val candidate = eldest.next()
            if (candidate.key == current) continue
            loadedEntries -= candidate.value.entries.size
            eldest.remove()
        }
    }

    private fun fileFor(chatId: String): File = File(directory, hashedName(chatId) + ".log")

    private companion object {
        const val CONTEXT = "nchat-archive:"

        /** A couple of hundred recent messages — what previews and new arrivals ask for. */
        const val TAIL_BYTES = 64L * 1024L
        const val MAX_LOADED_ENTRIES = 12_000
    }
}
