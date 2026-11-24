package com.nchat.app.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Group messages — wire version 1.
 *
 * A message to a group is sealed once, not once per member. Each member has
 * a *chain* for each group they write to: a chain key that moves forward
 * with every message, exactly like the sending chain of a one-to-one
 * session, and a signing key. A member hands the chain to the others — the
 * chain's id, its current number and chain key, and the public half of the
 * signing key ([distribution]) — inside sealed one-to-one messages; that is
 * E2eeClient's part. From then on every message is one envelope:
 *
 * `{"v":1,"c":<chain id>,"n":<number>,"ct":<nonce ‖ ciphertext ‖ tag>,"sig":<signature>}`
 *
 *  - `ct` is AES-256-GCM under the message key of number `n`, over the
 *    content padded to 128 bytes, with the header below as associated data;
 *  - `sig` is ECDSA P-256 over the header and `ct`, by the chain's signing
 *    key. Every member holds the chain key, so without the signature any
 *    member could write as any other; the private half of the signing key
 *    never leaves the phone that made the chain;
 *  - the header is `01 ‖ group id ‖ sender id ‖ chain id ‖ n ‖ what the
 *    envelope is for` (ids with their length in front). A sealed message
 *    therefore opens only as that message of that sender in that group.
 *
 * Whoever is handed the chain at number `n` can derive every later message
 * key and none before it: a new member reads from when they were handed it.
 * A member who must no longer read is not handed anything — the sender
 * starts a new chain (E2eeClient decides when).
 *
 * Messages are opened in any order, each exactly once. As in a one-to-one
 * session, a run of messages that was passed over is kept as its range and
 * the chain key at its start ([E2eeProtocol.Gap] explains why and at what
 * price).
 *
 * [open] checks the signature before it derives anything, so an envelope
 * that is not the owner's costs one signature check and changes nothing.
 */
object E2eeGroup {

    const val VERSION = 1
    const val CHAIN_ID_BYTES = 16

    /**
     * After this many messages a sender starts a new chain. It keeps every
     * message within reach of whoever was handed the chain, however long
     * they were away: opening the newest message first means deriving at
     * most this many chain steps.
     */
    const val MAX_CHAIN_MESSAGES = 2_000

    /**
     * The furthest ahead of what a member has opened that a message may be.
     * Twice what an honest chain holds; it bounds the work the owner of a
     * chain can cost the others with one envelope.
     */
    const val MAX_SKIP = 4_096

    /** Runs of unread messages kept per chain; the oldest fall out first. */
    private const val MAX_GAPS = 256

    private const val KEY_BYTES = 32
    private const val SIGNATURE_BYTES = 64
    private const val DISTRIBUTION_BYTES = 1 + CHAIN_ID_BYTES + 4 + KEY_BYTES + E2eeProtocol.PUBLIC_KEY_BYTES
    private val OWN_MAGIC = "NCG1".toByteArray(Charsets.US_ASCII)
    private val HELD_MAGIC = "NCH1".toByteArray(Charsets.US_ASCII)

    private val random = SecureRandom()

    // ----------------------------------------------------------------- chains

    /**
     * The chain this phone seals its own messages to one group with.
     * [next] is the number the next message gets; [created] is this phone's
     * clock when the chain was made.
     */
    class OwnChain internal constructor(
        internal val id: ByteArray,
        internal var chainKey: ByteArray,
        next: Int,
        internal val signingPrivate: ByteArray,
        signingPublic: ByteArray,
        val created: Long,
    ) {
        var next: Int = next
            internal set

        val signingPublic: ByteArray = signingPublic.copyOf()

        /** The chain's id as envelopes name it. */
        val idText: String get() = encodeBase64(id)

        fun serialize(): ByteArray = framed(OWN_MAGIC) {
            write(id)
            writeInt(next)
            write(chainKey)
            writeLong(created)
            writeInt(signingPrivate.size)
            write(signingPrivate)
            write(signingPublic)
        }

        companion object {
            /** Null for bytes that are not a stored chain, or are damaged. */
            fun deserialize(bytes: ByteArray): OwnChain? = unframed(OWN_MAGIC, bytes) {
                val id = readExactly(CHAIN_ID_BYTES)
                val next = readInt()
                val chainKey = readExactly(KEY_BYTES)
                val created = readLong()
                val privateSize = readInt()
                if (privateSize <= 0 || privateSize > 1024) return@unframed null
                val signingPrivate = readExactly(privateSize)
                val signingPublic = readExactly(E2eeProtocol.PUBLIC_KEY_BYTES)
                if (next < 0 || available() != 0 || !E2eeProtocol.isValidPublicKey(signingPublic)) return@unframed null
                OwnChain(id, chainKey, next, signingPrivate, signingPublic, created)
            }
        }
    }

    /** Messages [from] until [until] that were passed over; [chainKey] is the chain key at [from]. */
    internal class Gap(val from: Int, val until: Int, val chainKey: ByteArray)

    /** A member's chain as another member holds it: enough to open, not enough to write. */
    class Chain internal constructor(
        internal val id: ByteArray,
        internal val signingPublic: ByteArray,
        internal var chainKey: ByteArray,
        internal var next: Int,
    ) {
        /** Oldest first. */
        internal val gaps = ArrayList<Gap>()

        val idText: String get() = encodeBase64(id)

        fun serialize(): ByteArray = framed(HELD_MAGIC) {
            write(id)
            write(signingPublic)
            write(chainKey)
            writeInt(next)
            writeInt(gaps.size)
            for (gap in gaps) {
                writeInt(gap.from)
                writeInt(gap.until)
                write(gap.chainKey)
            }
        }

        companion object {
            /** Null for bytes that are not a stored chain, or are damaged. */
            fun deserialize(bytes: ByteArray): Chain? = unframed(HELD_MAGIC, bytes) {
                val id = readExactly(CHAIN_ID_BYTES)
                val signingPublic = readExactly(E2eeProtocol.PUBLIC_KEY_BYTES)
                val chainKey = readExactly(KEY_BYTES)
                val next = readInt()
                val count = readInt()
                if (next < 0 || count < 0 || count > MAX_GAPS || !E2eeProtocol.isValidPublicKey(signingPublic)) return@unframed null
                val chain = Chain(id, signingPublic, chainKey, next)
                repeat(count) {
                    val from = readInt()
                    val until = readInt()
                    if (from < 0 || until <= from || until > next) return@unframed null
                    chain.gaps += Gap(from, until, readExactly(KEY_BYTES))
                }
                if (available() != 0) return@unframed null
                chain
            }
        }
    }

    /** A new chain with keys of its own. [now] is recorded as its [OwnChain.created]. */
    fun newChain(now: Long): OwnChain {
        val signing = E2eeProtocol.generatePrekeyPair()
        return OwnChain(
            id = ByteArray(CHAIN_ID_BYTES).also { random.nextBytes(it) },
            chainKey = ByteArray(KEY_BYTES).also { random.nextBytes(it) },
            next = 0,
            signingPrivate = signing.private,
            signingPublic = signing.public,
            created = now,
        )
    }

    // ------------------------------------------------------------ handing over

    /**
     * What another member needs to read [chain] from its next message on:
     * `01 ‖ id ‖ number ‖ chain key ‖ signing key (public)`. It opens every
     * later message and none before it. This is key material: it travels in
     * a sealed one-to-one message and nowhere else.
     */
    fun distribution(chain: OwnChain): ByteArray {
        val out = ByteArrayOutputStream(DISTRIBUTION_BYTES)
        DataOutputStream(out).apply {
            writeByte(VERSION)
            write(chain.id)
            writeInt(chain.next)
            write(chain.chainKey)
            write(chain.signingPublic)
        }
        return out.toByteArray()
    }

    /** The chain a [distribution] hands over, or null when it is not one this build can use. */
    fun accept(distribution: ByteArray): Chain? = try {
        if (distribution.size != DISTRIBUTION_BYTES || distribution[0] != VERSION.toByte()) {
            null
        } else {
            val input = DataInputStream(ByteArrayInputStream(distribution, 1, distribution.size - 1))
            val id = input.readExactly(CHAIN_ID_BYTES)
            val next = input.readInt()
            val chainKey = input.readExactly(KEY_BYTES)
            val signingPublic = input.readExactly(E2eeProtocol.PUBLIC_KEY_BYTES)
            // A chain never gets further than this before its owner starts another.
            if (next < 0 || next > MAX_CHAIN_MESSAGES || !E2eeProtocol.isValidPublicKey(signingPublic)) null
            else Chain(id, signingPublic, chainKey, next)
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------- seal

    /**
     * Seals [plaintext] as the next message of [chain], written by
     * [senderId] to [groupId]. [target] says what the envelope is for (a new
     * message by its client id, or the edit of a stored one); it is
     * authenticated, not transmitted. The chain moves on only once the
     * envelope exists.
     */
    fun seal(chain: OwnChain, groupId: String, senderId: String, plaintext: ByteArray, target: ByteArray): String {
        val number = chain.next
        val (messageKey, nextChainKey) = E2eeProtocol.chainKdf(chain.chainKey)
        val header = header(groupId, senderId, chain.id, number, target)
        val ciphertext = E2eeProtocol.gcmEncrypt(messageKey, header, E2eeProtocol.pad(plaintext))
        java.util.Arrays.fill(messageKey, 0)
        val signature = E2eeProtocol.signPrekey(header + ciphertext, chain.signingPrivate)
        chain.chainKey = nextChainKey
        chain.next = number + 1
        return buildJsonObject {
            put("v", VERSION)
            put("c", encodeBase64(chain.id))
            put("n", number)
            put("ct", encodeBase64(ciphertext))
            put("sig", encodeBase64(signature))
        }.toString()
    }

    /** The id of the chain [envelope] was sealed with, or null when it is not a group envelope. */
    fun chainId(envelope: String): String? = parse(envelope)?.let { encodeBase64(it.chain) }

    // ------------------------------------------------------------------- open

    /**
     * Opens [envelope] with [chain]. Null — and [chain] untouched — when it
     * is not the chain's, not signed by its owner, not that message of that
     * sender in that group, already opened, or too far ahead. Never throws.
     */
    fun open(chain: Chain, groupId: String, senderId: String, envelope: String, target: ByteArray): ByteArray? = try {
        openChecked(chain, groupId, senderId, envelope, target)
    } catch (_: Throwable) {
        null
    }

    private fun openChecked(chain: Chain, groupId: String, senderId: String, envelope: String, target: ByteArray): ByteArray? {
        val parsed = parse(envelope) ?: return null
        if (!parsed.chain.contentEquals(chain.id)) return null
        val header = header(groupId, senderId, chain.id, parsed.number, target)
        // The owner's signature first: nothing is derived for an envelope that is not theirs.
        if (!E2eeProtocol.verifyPrekeySignature(header + parsed.ciphertext, parsed.signature, chain.signingPublic)) return null

        val number = parsed.number
        // A message that was passed over earlier.
        val index = chain.gaps.indexOfFirst { number >= it.from && number < it.until }
        if (index >= 0) {
            val gap = chain.gaps[index]
            val (messageKey, after) = E2eeProtocol.chainKdf(E2eeProtocol.advance(gap.chainKey, number - gap.from))
            val padded = E2eeProtocol.gcmDecrypt(messageKey, header, parsed.ciphertext) ?: return null
            val plaintext = E2eeProtocol.unpad(padded) ?: return null
            // The message leaves the run; what remains of it lies before and after.
            chain.gaps.removeAt(index)
            var at = index
            if (number > gap.from) chain.gaps.add(at++, Gap(gap.from, number, gap.chainKey))
            if (number + 1 < gap.until) chain.gaps.add(at, Gap(number + 1, gap.until, after))
            trim(chain)
            return plaintext
        }

        if (number < chain.next) return null // opened before
        if (number - chain.next > MAX_SKIP) return null
        val at = E2eeProtocol.advance(chain.chainKey, number - chain.next)
        val (messageKey, after) = E2eeProtocol.chainKdf(at)
        val padded = E2eeProtocol.gcmDecrypt(messageKey, header, parsed.ciphertext) ?: return null
        val plaintext = E2eeProtocol.unpad(padded) ?: return null
        // Authenticated and opened: only now does the chain move.
        if (number > chain.next) chain.gaps += Gap(chain.next, number, chain.chainKey)
        chain.chainKey = after
        chain.next = number + 1
        trim(chain)
        return plaintext
    }

    private fun trim(chain: Chain) {
        while (chain.gaps.size > MAX_GAPS) java.util.Arrays.fill(chain.gaps.removeAt(0).chainKey, 0)
    }

    // --------------------------------------------------------------- envelope

    private class Parsed(val chain: ByteArray, val number: Int, val ciphertext: ByteArray, val signature: ByteArray)

    private fun parse(envelope: String): Parsed? = try {
        val fields = Json.parseToJsonElement(envelope) as? JsonObject
        val version = fields?.number("v")
        val chain = fields?.text("c")?.let { decodeBase64(it) }
        val number = fields?.number("n")
        val ciphertext = fields?.text("ct")?.let { decodeBase64(it) }
        val signature = fields?.text("sig")?.let { decodeBase64(it) }
        if (version != VERSION || chain?.size != CHAIN_ID_BYTES || number == null || number < 0 ||
            ciphertext == null || ciphertext.isEmpty() || signature?.size != SIGNATURE_BYTES
        ) {
            null
        } else {
            Parsed(chain, number, ciphertext, signature)
        }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(name: String): Int? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    /** `01 ‖ group id ‖ sender id ‖ chain id ‖ number ‖ target`, the two ids with their length in front. */
    private fun header(groupId: String, senderId: String, chain: ByteArray, number: Int, target: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeByte(VERSION)
            for (id in arrayOf(groupId, senderId)) {
                val bytes = id.toByteArray(Charsets.UTF_8)
                writeInt(bytes.size)
                write(bytes)
            }
            write(chain)
            writeInt(number)
            write(target)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- storage

    /** `magic ‖ SHA-256(payload) ‖ payload`: a stored chain that was cut or changed is told apart from one that was not. */
    private fun framed(magic: ByteArray, fill: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply(fill).flush()
        val payload = out.toByteArray()
        return magic + MessageDigest.getInstance("SHA-256").digest(payload) + payload
    }

    private fun <T> unframed(magic: ByteArray, bytes: ByteArray, take: DataInputStream.() -> T?): T? = try {
        if (bytes.size < magic.size + 32 || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
            null
        } else {
            val payload = bytes.copyOfRange(magic.size + 32, bytes.size)
            val digest = bytes.copyOfRange(magic.size, magic.size + 32)
            if (!MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(payload))) null
            else take(DataInputStream(ByteArrayInputStream(payload)))
        }
    } catch (_: Exception) {
        null
    }

    private fun DataInputStream.readExactly(length: Int): ByteArray = ByteArray(length).also { readFully(it) }
}
