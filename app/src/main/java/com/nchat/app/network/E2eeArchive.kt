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

        /**
         * Not a message: the chain a member seals their group messages with
         * ([E2eeGroup.distribution], base64, in [body]), handed to another
         * member. It is never shown and never kept as content.
         */
        const val GROUP_KEY = 16

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
 *
 * A file is named by a hash of its chat, so the directory names nobody —
 * and so nothing but the chat itself leads to its file. For a backup, which
 * needs every chat there is, each file also carries one sealed line that
 * says which chat it is of ([chats]).
 */
class E2eeArchive internal constructor(
    private val directory: File,
    key: ByteArray,
    private val tailBytes: Long,
    private val maxLoadedEntries: Int,
) {

    constructor(directory: File, key: ByteArray) : this(directory, key, TAIL_BYTES, MAX_LOADED_ENTRIES)

    /**
     * [newest]: per message — the part of a key up to and including its `#` — the key of the
     * entry written last. Entries are read in the order they were written, so the last one seen
     * is the newest form of the message (its latest edit).
     */
    private class Index(val entries: HashMap<String, E2eePayload>, val newest: HashMap<String, String>, var complete: Boolean)

    private val box = SealedBox(key)
    private val lock = Any()

    /** Chats in memory, least recently used first. */
    private val loaded = LinkedHashMap<String, Index>(16, 0.75f, true)
    private var loadedEntries = 0

    /** Chats with appended entries that have not been synced yet. */
    private val unsynced = LinkedHashSet<String>()

    /** Chats whose file is known, in this run of the app, to carry the line that names it. */
    private val named = HashSet<String>()

    /** The chats this archive keeps. [unreadable]: files that hold something and do not say whose it is. */
    class Chats(val ids: List<String>, val unreadable: Int)

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

    /**
     * What this device kept of the message [reference] names, in its newest form — without the
     * envelope, which [find] needs. [reference] is the part of a key up to and including its `#`.
     * Reads the whole chat when the message is not among the recent ones.
     */
    fun latest(chatId: String, reference: String): E2eePayload? = synchronized(lock) {
        val index = indexFor(chatId)
        index.newest[reference]?.let { return@synchronized index.entries[it] }
        if (index.complete) return@synchronized null
        read(chatId, index, whole = true)
        trim(chatId)
        index.newest[reference]?.let { index.entries[it] }
    }

    /**
     * Takes every entry of the messages [references] name out of the chat's file — the message
     * as first kept, and each edit of it. The file is written again without them, so their
     * bytes are no longer in it; lines that could not be read anyway go with them. Returns how
     * many entries were removed. Throws when the file cannot be rewritten; it is then unchanged.
     */
    fun remove(chatId: String, references: List<String>): Int {
        if (references.isEmpty()) return 0
        synchronized(lock) {
            val file = fileFor(chatId)
            if (!file.isFile) return 0
            // Asked often for messages that were never kept here: answered from memory.
            val index = indexFor(chatId)
            if (!index.complete) {
                read(chatId, index, whole = true)
                trim(chatId)
            }
            if (references.none { it in index.newest }) return 0

            var removed = 0
            val kept = ArrayList<String>()
            for (line in file.readText(Charsets.US_ASCII).split('\n')) {
                val text = line.trim()
                if (text.isEmpty()) continue
                val key = keyOf(chatId, text) ?: continue
                if (references.any { key.startsWith(it) }) removed++ else kept += text
            }
            if (removed == 0) return 0
            rewrite(chatId, kept)
            return removed
        }
    }

    /**
     * Every chat that has a file here, for a backup. A file that holds
     * something and has lost the line that names its chat is counted, not
     * passed over: what is in it cannot be read without knowing whose it is.
     */
    fun chats(): Chats = synchronized(lock) {
        val ids = ArrayList<String>()
        var unreadable = 0
        val files = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(LOG) }.sortedBy { it.name }
        for (file in files) {
            val name = nameIn(file)
            when {
                name != null -> ids += name.also { named += it }
                holdsSomething(file) -> unreadable++
            }
        }
        Chats(ids, unreadable)
    }

    /**
     * Everything kept of a chat, in the order it was written — which is what
     * makes the last form of a message its newest. Lines that cannot be read
     * are not among them. Reads the whole file.
     */
    fun entries(chatId: String): List<Pair<String, E2eePayload>> = synchronized(lock) {
        val file = fileFor(chatId)
        if (!file.isFile) return@synchronized emptyList()
        val found = LinkedHashMap<String, E2eePayload>()
        file.forEachLine(Charsets.US_ASCII) { line ->
            val entry = entryIn(chatId, line) ?: return@forEachLine
            // Written twice under one key: the later one counts, where it stands.
            found.remove(entry.first)
            found[entry.first] = entry.second
        }
        found.map { it.key to it.value }
    }

    /**
     * Puts back what a backup holds of a chat and this archive does not;
     * returns how many entries that was. What is kept here already is left
     * as it is — an entry under the same key is not replaced.
     *
     * The restored entries go in before everything kept here: a backup is
     * older than what the phone has kept by itself since, and the entry of a
     * message written last is its newest form. On stable storage before this
     * returns. Throws when the file cannot be written; it is then unchanged.
     */
    fun restore(chatId: String, entries: List<Pair<String, E2eePayload>>): Int {
        if (entries.isEmpty()) return 0
        synchronized(lock) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("could not create the archive directory")
            val file = fileFor(chatId)
            val kept = ArrayList<String>()
            val have = HashSet<String>()
            if (file.isFile) {
                file.forEachLine(Charsets.US_ASCII) { line ->
                    val text = line.trim()
                    val key = (if (text.isEmpty()) null else keyOf(chatId, text)) ?: return@forEachLine
                    kept += text
                    have += key
                }
            }
            val missing = LinkedHashMap<String, E2eePayload>()
            for ((key, payload) in entries) {
                if (key in have) continue
                missing.remove(key)
                missing[key] = payload
            }
            if (missing.isEmpty()) return 0
            rewrite(chatId, missing.map { seal(chatId, it.key, it.value) } + kept)
            return missing.size
        }
    }

    /** Appends an entry. It is on stable storage only after [flush]. Throws on failure. */
    fun put(chatId: String, key: String, payload: E2eePayload) {
        synchronized(lock) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("could not create the archive directory")
            val file = fileFor(chatId)
            // A new file says first which chat it is of; so does one that has lost that line.
            val naming = if (chatId in named || nameIn(file) == chatId) "" else "\n" + nameLine(chatId)
            // The leading newline closes a line that a crash left unfinished.
            FileOutputStream(file, true).use { it.write((naming + "\n" + seal(chatId, key, payload) + "\n").toByteArray(Charsets.US_ASCII)) }
            named += chatId
            unsynced += chatId
            val index = loaded[chatId] ?: return
            if (index.entries.put(key, payload) == null) loadedEntries++
            index.newest[referenceOf(key)] = key
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
            named.clear()
            directory.deleteRecursively()
        }
    }

    private fun indexFor(chatId: String): Index {
        loaded[chatId]?.let { return it }
        val index = Index(HashMap(), HashMap(), complete = false)
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
                for (line in if (start > 0L) lines.drop(1) else lines) parse(chatId, line, index)
            }
            index.complete = start == 0L
        } catch (_: Exception) {
            // An unreadable file reads as empty; new entries still append to it.
            index.complete = true
        }
        loadedEntries += index.entries.size - before
    }

    private fun parse(chatId: String, line: String, into: Index) {
        val (key, payload) = entryIn(chatId, line) ?: return
        into.entries[key] = payload
        into.newest[referenceOf(key)] = key
    }

    /** The entry [line] holds — its key and what was kept under it — or null when the line is not one that can be read. */
    private fun entryIn(chatId: String, line: String): Pair<String, E2eePayload>? {
        if (line.isBlank()) return null
        return try {
            val opened = decodeBase64(line.trim())?.let { box.open(CONTEXT + chatId, it) } ?: return null
            val entry = Json.parseToJsonElement(String(opened, Charsets.UTF_8)).jsonObject
            val key = (entry["k"] as? JsonPrimitive)?.content ?: return null
            val type = (entry["y"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            val body = (entry["t"] as? JsonPrimitive)?.content ?: return null
            key to E2eePayload(type, body, (entry["r"] as? JsonPrimitive)?.content, (entry["a"] as? JsonPrimitive)?.content)
        } catch (_: Exception) {
            // A torn or damaged line costs exactly one entry.
            null
        }
    }

    /** An entry as the line of a chat's file. */
    private fun seal(chatId: String, key: String, payload: E2eePayload): String {
        val entry = buildJsonObject {
            put("k", key)
            put("y", payload.type)
            put("t", payload.body)
            payload.replyTo?.let { put("r", it) }
            payload.attachment?.let { put("a", it) }
        }.toString()
        return encodeBase64(box.seal(CONTEXT + chatId, entry.toByteArray(Charsets.UTF_8)))
    }

    /** The line that says which chat a file is of. */
    private fun nameLine(chatId: String): String = encodeBase64(box.seal(NAME_CONTEXT, chatId.toByteArray(Charsets.UTF_8)))

    /**
     * The chat [file] says it is of, or null when no line of it says so. The
     * line is the first of a file as this class writes one, and this reads
     * no further than the line it finds; only a file without one is read to
     * its end. A name that is not the one the file is called by — a line
     * taken from another chat's file — does not count.
     */
    private fun nameIn(file: File): String? {
        if (!file.isFile) return null
        return try {
            file.useLines(Charsets.US_ASCII) { lines ->
                lines.mapNotNull { line ->
                    val text = line.trim()
                    val opened = if (text.isEmpty()) null else decodeBase64(text)?.let { box.open(NAME_CONTEXT, it) }
                    opened?.let { String(it, Charsets.UTF_8) }?.takeIf { hashedName(it) + LOG == file.name }
                }.firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun holdsSomething(file: File): Boolean = try {
        file.useLines(Charsets.US_ASCII) { lines -> lines.any { it.isNotBlank() } }
    } catch (_: Exception) {
        true
    }

    /**
     * Writes the file of [chatId] anew: the line that names the chat, then
     * [lines]. On stable storage before this returns. Throws when the file
     * cannot be replaced; it is then unchanged.
     */
    private fun rewrite(chatId: String, lines: List<String>) {
        val file = fileFor(chatId)
        val rewritten = File(directory, file.name + ".tmp")
        try {
            FileOutputStream(rewritten).use { out ->
                out.write((nameLine(chatId) + "\n").toByteArray(Charsets.US_ASCII))
                for (line in lines) out.write((line + "\n").toByteArray(Charsets.US_ASCII))
                out.fd.sync()
            }
            if (!rewritten.renameTo(file)) throw IOException("could not replace the archive file")
        } finally {
            rewritten.delete()
        }
        named += chatId
        unsynced.remove(chatId)
        // What is in memory was read from the old file: read again when it is next asked for.
        loaded.remove(chatId)?.let { loadedEntries -= it.entries.size }
    }

    /** The key of the entry in [line], or null when the line cannot be read. */
    private fun keyOf(chatId: String, line: String): String? = try {
        val opened = decodeBase64(line)?.let { box.open(CONTEXT + chatId, it) }
        opened?.let { (Json.parseToJsonElement(String(it, Charsets.UTF_8)).jsonObject["k"] as? JsonPrimitive)?.content }
    } catch (_: Exception) {
        null
    }

    /** The part of a key that names the message: everything up to and including the last `#`. */
    private fun referenceOf(key: String): String = key.substring(0, key.lastIndexOf('#') + 1)

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

    private fun fileFor(chatId: String): File = File(directory, hashedName(chatId) + LOG)

    private companion object {
        const val CONTEXT = "nchat-archive:"

        /** What the line that names a chat is sealed for. No chat's entries are: theirs begin with [CONTEXT]. */
        const val NAME_CONTEXT = "nchat-archive-chat"
        const val LOG = ".log"

        /** A couple of hundred recent messages — what previews and new arrivals ask for. */
        const val TAIL_BYTES = 64L * 1024L
        const val MAX_LOADED_ENTRIES = 12_000
    }
}
