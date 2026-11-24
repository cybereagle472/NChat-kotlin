package com.nchat.app.network

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Key/value storage for secrets. Production is [FileSecretVault]; tests use an
 * in-memory map, which keeps everything above this interface runnable on the
 * plain JVM.
 */
interface SecretVault {
    /** The stored value, or null when the entry is absent or cannot be read. */
    fun read(key: String): String?

    /** True when an entry exists, readable or not — "nothing stored" and "damaged" are different facts. */
    fun contains(key: String): Boolean

    /**
     * Stores [value], or removes the entry when it is null. A [durable] write
     * is on stable storage before this returns — required for state that a
     * ciphertext about to leave the device depends on. Throws on failure.
     */
    fun write(key: String, value: String?, durable: Boolean)
}

/**
 * AES-256-GCM with a random nonce per message and a caller-supplied context
 * as associated data: a value sealed for one purpose never opens for another.
 * Output: `format (1 byte) || nonce (12) || ciphertext || tag (16)`; the
 * format byte is authenticated too, so the layout can change later.
 */
internal class SealedBox(key: ByteArray) {

    private val secret: SecretKeySpec
    private val random = SecureRandom()

    init {
        require(key.size == KEY_BYTES) { "sealing key must be $KEY_BYTES bytes" }
        secret = SecretKeySpec(key.copyOf(), "AES")
    }

    fun seal(context: String, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData(context))
        return byteArrayOf(FORMAT) + nonce + cipher.doFinal(plaintext)
    }

    /** Null for anything that is not exactly what [seal] produced under this key and context. */
    fun open(context: String, sealed: ByteArray): ByteArray? {
        return try {
            if (sealed.size < HEADER_BYTES + TAG_BITS / 8 || sealed[0] != FORMAT) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, sealed, 1, NONCE_BYTES))
            cipher.updateAAD(associatedData(context))
            cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
        } catch (_: Exception) {
            null
        }
    }

    private fun associatedData(context: String): ByteArray = byteArrayOf(FORMAT) + context.toByteArray(Charsets.UTF_8)

    companion object {
        const val KEY_BYTES = 32
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT: Byte = 1
        private const val NONCE_BYTES = 12
        private const val HEADER_BYTES = 1 + NONCE_BYTES
        private const val TAG_BITS = 128
    }
}

/** First 160 bits of SHA-256, hex — a file name that says nothing about [value]. */
internal fun hashedName(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .copyOf(20)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * [SecretVault] on disk: one file per entry, sealed under a 256-bit key that
 * the caller keeps in the Android Keystore-backed `SecurePrefs`.
 *
 * Ratchet state changes with every message, so it is sealed in software with
 * that key instead of making a Keystore round-trip per message. The entry
 * name is the sealing context — an entry copied over another one (one peer's
 * session over another's) fails authentication instead of loading. File names
 * are hashes, so the directory listing names no contact.
 */
class FileSecretVault(private val directory: File, key: ByteArray) : SecretVault {

    private val box = SealedBox(key)
    private val lock = Any()

    init {
        // A write that died before its rename leaves only a temp file behind.
        directory.listFiles()?.forEach { file -> if (file.name.endsWith(TEMP_SUFFIX)) file.delete() }
    }

    override fun read(key: String): String? = synchronized(lock) {
        try {
            val file = fileFor(key)
            if (!file.isFile) return@synchronized null
            box.open(CONTEXT + key, file.readBytes())?.let { String(it, Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        }
    }

    override fun contains(key: String): Boolean = synchronized(lock) { fileFor(key).isFile }

    override fun write(key: String, value: String?, durable: Boolean) {
        synchronized(lock) {
            val file = fileFor(key)
            if (value == null) {
                if (file.exists() && !file.delete()) throw IOException("could not remove a vault entry")
                return
            }
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("could not create the vault directory")
            val sealed = box.seal(CONTEXT + key, value.toByteArray(Charsets.UTF_8))
            // Same-directory temp file, then rename: a reader sees the old entry or the new one, never half.
            val temp = File.createTempFile("entry-", TEMP_SUFFIX, directory)
            try {
                FileOutputStream(temp).use { out ->
                    out.write(sealed)
                    if (durable) out.fd.sync()
                }
                if (!temp.renameTo(file)) throw IOException("could not replace a vault entry")
            } finally {
                temp.delete()
            }
        }
    }

    /** Removes every entry — the account is leaving this device. */
    fun clear() {
        synchronized(lock) { directory.deleteRecursively() }
    }

    private fun fileFor(key: String): File = File(directory, hashedName(key) + ".bin")

    private companion object {
        const val CONTEXT = "nchat-vault:"
        const val TEMP_SUFFIX = ".tmp"
    }
}

/**
 * Ratchet state at rest: per-peer session records and the private halves of
 * our one-time prekeys. Stateless on purpose — every call goes through the
 * [vault], and the caller ([E2eeClient]) decides what to keep in memory.
 */
class E2eeStore(private val vault: SecretVault) {

    /**
     * One session with a peer. A [retired] session still opens late messages
     * but is never used to seal: the peer's keys are known to have changed.
     */
    class StoredSession(val session: E2eeProtocol.Session, var retired: Boolean)

    /**
     * Everything held about one peer. The record existing at all — even with
     * no usable session left — means this conversation has been end-to-end
     * encrypted before, and from then on it is never sent in the clear.
     */
    class PeerRecord {
        /** Sending session first; older ones stay for messages still in flight. */
        val sessions: MutableList<StoredSession> = mutableListOf()

        /**
         * References of handshake keys already accepted from this peer. A
         * handshake is good for one session: a recorded one presented again
         * is a replay, even after the session it created has been dropped.
         */
        val seenHandshakes: LinkedHashSet<String> = LinkedHashSet()

        /**
         * Envelopes sealed but not known to have reached the server, oldest
         * first, by what each was sealed for. A retry sends the same envelope
         * again: sealing the content a second time would use up another
         * message number, and a peer can only skip so many that never arrive.
         */
        val unsent: LinkedHashMap<String, Unsent> = LinkedHashMap()

        /**
         * What is known about the peer's identity key. Null in a record that
         * says nothing about it: one written before this was kept, or one
         * that could not be read.
         */
        var trust: Trust? = null

        fun sendable(): StoredSession? = sessions.firstOrNull { !it.retired }
    }

    /**
     * The peer's identity key as this phone last saw it, and what the user
     * has been told and has said about it.
     *
     * [verified]: the user compared the security code for [identity] with
     * the other person. [changed]: [identity] took the place of another key
     * and the user has not acknowledged that yet. [held]: it took the place
     * of a key that was verified — nothing is sealed for it until the user
     * has looked. [since]: when encryption with this peer began, by this
     * phone's clock (0: as far back as anybody knows).
     */
    class Trust(
        var identity: ByteArray,
        var verified: Boolean = false,
        var changed: Boolean = false,
        var held: Boolean = false,
        val since: Long = 0L,
    )

    /** [content] identifies what was sealed (a digest); [recipientIdentity] is the key it was sealed for. */
    class Unsent(val content: String, val envelope: String, val recipientIdentity: String)

    // --------------------------------------------------------------- sessions

    /** Null only when no session with this peer has ever been stored on this device. */
    fun loadPeer(peerId: String): PeerRecord? {
        if (peerId.isBlank()) return null
        val entry = SESSION_PREFIX + peerId
        val record = PeerRecord()
        val stored = vault.read(entry) ?: return if (vault.contains(entry)) record else null
        try {
            val root = Json.parseToJsonElement(stored).jsonObject
            record.trust = (root["t"] as? JsonObject)?.let(::readTrust)
            for (element in root["s"] as? JsonArray ?: JsonArray(emptyList())) {
                val item = element as? JsonObject ?: continue
                val blob = (item["b"] as? JsonPrimitive)?.content?.let { decodeBase64(it) } ?: continue
                val session = E2eeProtocol.deserialize(blob) ?: continue
                record.sessions += StoredSession(session, (item["r"] as? JsonPrimitive)?.content == "true")
            }
            for (element in root["h"] as? JsonArray ?: JsonArray(emptyList())) {
                (element as? JsonPrimitive)?.content?.let { record.seenHandshakes += it }
            }
            for (element in root["u"] as? JsonArray ?: JsonArray(emptyList())) {
                val item = element as? JsonObject ?: continue
                val target = (item["k"] as? JsonPrimitive)?.content ?: continue
                val content = (item["c"] as? JsonPrimitive)?.content ?: continue
                val envelope = (item["e"] as? JsonPrimitive)?.content ?: continue
                val identity = (item["i"] as? JsonPrimitive)?.content ?: continue
                record.unsent[target] = Unsent(content, envelope, identity)
            }
        } catch (_: Exception) {
            // A damaged record still means "encrypted before": the caller
            // starts a new session, it never falls back to plaintext.
        }
        return record
    }

    /** Null unless the note names a real key: "unknown" is better than a key nobody ever presented. */
    private fun readTrust(note: JsonObject): Trust? {
        val identity = (note["i"] as? JsonPrimitive)?.content?.let { decodeBase64(it) } ?: return null
        if (!E2eeProtocol.isValidPublicKey(identity)) return null
        fun flag(name: String): Boolean = (note[name] as? JsonPrimitive)?.content == "true"
        return Trust(
            identity = identity,
            verified = flag("v"),
            changed = flag("c"),
            held = flag("h"),
            since = (note["s"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
        )
    }

    fun savePeer(peerId: String, record: PeerRecord, durable: Boolean) {
        require(peerId.isNotBlank()) { "peer id is required" }
        while (record.sessions.size > MAX_SESSIONS_PER_PEER) record.sessions.removeAt(record.sessions.size - 1)
        val handshakes = record.seenHandshakes.iterator()
        while (record.seenHandshakes.size > MAX_SEEN_HANDSHAKES && handshakes.hasNext()) {
            handshakes.next()
            handshakes.remove()
        }
        val unsent = record.unsent.values.iterator()
        var unsentBytes = record.unsent.values.sumOf { it.envelope.length }
        while ((record.unsent.size > MAX_UNSENT_PER_PEER || unsentBytes > MAX_UNSENT_BYTES_PER_PEER) && unsent.hasNext()) {
            unsentBytes -= unsent.next().envelope.length
            unsent.remove()
        }
        val encoded = buildJsonObject {
            put(
                "s",
                buildJsonArray {
                    for (stored in record.sessions) {
                        add(
                            buildJsonObject {
                                put("r", stored.retired)
                                put("b", encodeBase64(stored.session.serialize()))
                            },
                        )
                    }
                },
            )
            put("h", buildJsonArray { for (reference in record.seenHandshakes) add(JsonPrimitive(reference)) })
            put(
                "u",
                buildJsonArray {
                    for ((target, kept) in record.unsent) {
                        add(
                            buildJsonObject {
                                put("k", target)
                                put("c", kept.content)
                                put("e", kept.envelope)
                                put("i", kept.recipientIdentity)
                            },
                        )
                    }
                },
            )
            record.trust?.let { trust ->
                put(
                    "t",
                    buildJsonObject {
                        put("i", encodeBase64(trust.identity))
                        put("v", trust.verified)
                        put("c", trust.changed)
                        put("h", trust.held)
                        put("s", trust.since)
                    },
                )
            }
        }
        vault.write(SESSION_PREFIX + peerId, encoded.toString(), durable)
    }

    // ------------------------------------------------------ one-time prekeys

    /**
     * Stores private halves under the [E2eeProtocol.keyReference] of their
     * public halves. Call BEFORE uploading the public halves: a key the
     * server hands out without its private half here would strand every
     * session started against it.
     */
    fun addOneTimeKeys(keys: Map<String, ByteArray>) {
        if (keys.isEmpty()) return
        val pool = readPool()
        for ((reference, privateKey) in keys) pool[reference] = encodeBase64(privateKey)
        // The server hands keys out oldest first, so the oldest entries here
        // are the likeliest to have been spent without a message ever arriving.
        val eldest = pool.entries.iterator()
        while (pool.size > MAX_LOCAL_ONE_TIME_KEYS && eldest.hasNext()) {
            eldest.next()
            eldest.remove()
        }
        writePool(pool)
    }

    /** The private half for [reference]. It stays in the pool until [removeOneTimeKeys]. */
    fun oneTimeKey(reference: String): ByteArray? = readPool()[reference]?.let { decodeBase64(it) }

    /** Forgets keys whose handshake has been accepted AND whose session is stored. */
    fun removeOneTimeKeys(references: Collection<String>) {
        if (references.isEmpty()) return
        val pool = readPool()
        var changed = false
        for (reference in references) if (pool.remove(reference) != null) changed = true
        if (changed) writePool(pool)
    }

    fun oneTimeKeyCount(): Int = readPool().size

    private fun readPool(): LinkedHashMap<String, String> {
        val pool = LinkedHashMap<String, String>()
        try {
            val stored = vault.read(POOL_KEY) ?: return pool
            for ((reference, value) in Json.parseToJsonElement(stored).jsonObject) {
                pool[reference] = (value as? JsonPrimitive)?.content ?: continue
            }
        } catch (_: Exception) {
            // A damaged pool reads as empty; the next key sync publishes fresh keys.
            pool.clear()
        }
        return pool
    }

    private fun writePool(pool: Map<String, String>) {
        val encoded = buildJsonObject { for ((reference, key) in pool) put(reference, key) }
        vault.write(POOL_KEY, encoded.toString(), durable = true)
    }

    companion object {
        /** The sending session plus a few older ones for messages still in flight. */
        const val MAX_SESSIONS_PER_PEER = 4

        const val MAX_SEEN_HANDSHAKES = 64

        /** Unsent envelopes kept per peer; beyond either limit the oldest is sealed anew if it is ever retried. */
        const val MAX_UNSENT_PER_PEER = 16
        const val MAX_UNSENT_BYTES_PER_PEER = 256 * 1024

        /** The server keeps at most 100 unconsumed; the rest is room for keys spent while this device was away. */
        const val MAX_LOCAL_ONE_TIME_KEYS = 400

        private const val SESSION_PREFIX = "e2ee.session."
        private const val POOL_KEY = "e2ee.otk"
    }
}
