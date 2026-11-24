package com.nchat.app.network

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup of what this phone kept of an account's sealed messages.
 *
 * The opened content of a sealed message exists in one place only: the
 * archive of the phone that opened it ([E2eeArchive]), sealed under a key
 * that signing out erases. A backup is that content in one file the user
 * keeps wherever they like — which means in places this app knows nothing
 * about. So the file is sealed under a passphrase only the user knows, and
 * whoever holds it without the passphrase learns how much was kept, not
 * what and not with whom.
 *
 * **What is in it.** For every chat, every entry of the archive: the name
 * the archive gives the message and what the message said, including the
 * pointer to an attached file and the key that opens it. The attached files
 * themselves stay on the server. No encryption keys of the account are in
 * it: a backup brings back what was said, never the ability to read what
 * arrives.
 *
 * **What it cannot do.** The passphrase is all that protects the file, and
 * whoever has the file can try passphrases for as long as they like — the
 * slow derivation below only makes each try expensive. And a restored
 * backup is believed like the phone's own memory: whoever wrote the file
 * wrote the history, so only a file the user made themselves belongs here.
 *
 * Format (version 1), all integers big-endian:
 *
 *     heading   "NCHATBK" '1' | rounds (4) | salt (16) | account tag (16) | key check (16)
 *     pieces    AES-256-GCM, 64 KiB of plaintext each, the last one shorter
 *
 * The key comes from the passphrase by PBKDF2-HMAC-SHA-256 with the file's
 * salt and round count, then HKDF over the heading and the account. The
 * account tag lets a file of another account be turned away at once, and
 * the key check lets a wrong passphrase be told from a damaged file. Every
 * piece is sealed with its number and with whether it is the last one, and
 * with the heading as associated data: pieces cannot be reordered, dropped,
 * repeated, taken from another file, or cut off the end without it showing.
 *
 * Inside, the pieces are one stream of records: a chat (tag 1, its id), then
 * its entries (tag 2, key and payload), each length-prefixed.
 */
object E2eeBackup {

    /** Why a file could not be restored, or not all of it. */
    enum class Problem {
        /** Not a file this app wrote. */
        NotABackup,

        /** Written by a later version of the app, in a format this one does not know. */
        NewerVersion,

        /** A backup of another account. */
        OtherAccount,

        /** The passphrase is not the one the file was written under. */
        WrongPassphrase,

        /** The file was changed or cut short after it was written. */
        Damaged,
    }

    /** [restored]: how many messages were brought back before the problem was met. */
    class Failure(val problem: Problem, val restored: Int = 0) : Exception(problem.name)

    /** What a backup holds. [unreadableChats]: files of the archive that could not be read, and are not in it. */
    class Written(val messages: Int, val chats: Int, val unreadableChats: Int)

    /** What the app asks for: about a second on a phone for the owner, and for every guess of anybody else. */
    const val ROUNDS = 600_000

    /** A file that asks for more than this is not tried: no build writes one, and it would only stall the phone. */
    const val MAX_ROUNDS = 10_000_000

    const val MIN_PASSPHRASE = 10
    private const val MIN_DIFFERENT_CHARACTERS = 5

    private val MAGIC = "NCHATBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION = '1'.code.toByte()
    private const val SALT_BYTES = 16
    private const val TAG_BYTES = 16
    private const val CHECK_BYTES = 16
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BYTES = 16

    /** Magic, version, rounds, salt, account tag — everything the key is made over. */
    private const val KEYED_BYTES = 7 + 1 + 4 + SALT_BYTES + TAG_BYTES
    const val HEADING_BYTES = KEYED_BYTES + CHECK_BYTES

    private const val PIECE_BYTES = 64 * 1024

    /** How long a piece is in the file; the last one may be shorter. */
    const val SEALED_PIECE_BYTES = PIECE_BYTES + GCM_TAG_BYTES

    /** No message is anywhere near this; a record that claims more is not a record. */
    private const val MAX_RECORD_BYTES = 8 * 1024 * 1024

    private const val CHAT = 1
    private const val ENTRY = 2

    /**
     * Null when [passphrase] will do; otherwise what to tell the user. The
     * bar is low on purpose — it stops what is no passphrase at all, and the
     * screen says what a good one looks like.
     */
    fun passphraseProblem(passphrase: CharArray): String? {
        val text = canonical(passphrase)
        return when {
            text.length < MIN_PASSPHRASE -> "Use at least $MIN_PASSPHRASE characters. Several words are best."
            text.toSet().size < MIN_DIFFERENT_CHARACTERS -> "That is too easy to guess. Use several different words."
            else -> null
        }
    }

    /** True when the two are one passphrase — typed twice, to be sure of it. */
    fun samePassphrase(first: CharArray, second: CharArray): Boolean = canonical(first) == canonical(second)

    /**
     * A passphrase as it is used: spaces around it are not part of it (a
     * keyboard adds one after the last word, unseen), and the same letters
     * are the same passphrase however a keyboard composes them.
     */
    private fun canonical(passphrase: CharArray): String = Normalizer.normalize(String(passphrase), Normalizer.Form.NFKC).trim()

    /**
     * PBKDF2 with HMAC-SHA-256 (RFC 8018), one block of output. Written out
     * here because Android offers it only from version 8, and this app runs
     * on 7.
     */
    internal fun stretch(passphrase: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        require(rounds >= 1) { "at least one round" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(String(passphrase).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(salt)
        var block = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val key = block.copyOf()
        for (round in 2..rounds) {
            block = mac.doFinal(block)
            for (index in key.indices) key[index] = (key[index].toInt() xor block[index].toInt()).toByte()
        }
        return key
    }

    private class Keys(val seal: SecretKeySpec, val check: ByteArray)

    /** [keyed]: the heading up to the key check. The account itself goes in as well, not only its tag. */
    private fun keys(passphrase: String, rounds: Int, keyed: ByteArray, account: String): Keys {
        val salt = keyed.copyOfRange(KEYED_BYTES - TAG_BYTES - SALT_BYTES, KEYED_BYTES - TAG_BYTES)
        val master = stretch(passphrase.toCharArray(), salt, rounds)
        val info = "nchat-backup-v1".toByteArray(Charsets.US_ASCII) + keyed + account.toByteArray(Charsets.UTF_8)
        val derived = E2eeProtocol.hkdf(master, salt, info, KEY_BYTES + CHECK_BYTES)
        return Keys(SecretKeySpec(derived.copyOf(KEY_BYTES), "AES"), derived.copyOfRange(KEY_BYTES, KEY_BYTES + CHECK_BYTES))
    }

    /** Says whose backup a file is to somebody who already knows the account, and to nobody else. */
    private fun accountTag(salt: ByteArray, account: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(("nchat-backup-account:$account").toByteArray(Charsets.UTF_8)).copyOf(TAG_BYTES)
    }

    /** The cipher of piece [index]; [last] is part of what it is sealed with, so the end of a file cannot be moved. */
    private fun cipher(mode: Int, key: SecretKeySpec, index: Long, last: Boolean, heading: ByteArray): Cipher {
        val nonce = ByteArray(NONCE_BYTES)
        for (byte in 0 until 8) nonce[NONCE_BYTES - 2 - byte] = (index ushr (8 * byte)).toByte()
        nonce[NONCE_BYTES - 1] = if (last) 1 else 0
        return Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(mode, key, GCMParameterSpec(GCM_TAG_BYTES * 8, nonce))
            it.updateAAD(heading)
        }
    }

    private fun int(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    /**
     * Writes a backup to [out]: [chat] for a chat, [entry] for each of its
     * messages in the order the archive kept them, and [finish] — without
     * which the file is not a backup, and does not restore.
     *
     * Throws [IllegalArgumentException] — before anything is written — for a
     * passphrase that [passphraseProblem] turns down, and whatever [out]
     * throws.
     */
    class Writer(private val out: OutputStream, account: String, passphrase: CharArray, rounds: Int = ROUNDS) {
        private val heading: ByteArray
        private val key: SecretKeySpec
        private val piece = ByteArray(PIECE_BYTES)
        private var filled = 0
        private var index = 0L
        private var inChat = false
        private var finished = false

        /** How many messages were written. */
        var entries = 0
            private set

        init {
            passphraseProblem(passphrase)?.let { throw IllegalArgumentException(it) }
            require(rounds in 1..MAX_ROUNDS) { "rounds out of range" }
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val keyed = MAGIC + VERSION + int(rounds) + salt + accountTag(salt, account)
            val keys = keys(canonical(passphrase), rounds, keyed, account)
            heading = keyed + keys.check
            key = keys.seal
            out.write(heading)
        }

        fun chat(chatId: String) {
            check(!finished) { "the backup is finished" }
            write(byteArrayOf(CHAT.toByte()))
            block(chatId.toByteArray(Charsets.UTF_8))
            inChat = true
        }

        fun entry(key: String, payload: E2eePayload) {
            check(!finished) { "the backup is finished" }
            check(inChat) { "an entry belongs to a chat" }
            write(byteArrayOf(ENTRY.toByte()))
            block(key.toByteArray(Charsets.UTF_8))
            block(payload.encode())
            entries++
        }

        /** Seals what is left as the last piece: the file now says where it ends. */
        fun finish() {
            check(!finished) { "the backup is finished" }
            finished = true
            seal(last = true)
            out.flush()
        }

        private fun block(bytes: ByteArray) {
            write(int(bytes.size))
            write(bytes)
        }

        private fun write(bytes: ByteArray) {
            var from = 0
            while (from < bytes.size) {
                // A full piece goes out only when more follows it: the last one is sealed as the last.
                if (filled == piece.size) seal(last = false)
                val count = minOf(bytes.size - from, piece.size - filled)
                System.arraycopy(bytes, from, piece, filled, count)
                filled += count
                from += count
            }
        }

        private fun seal(last: Boolean) {
            out.write(cipher(Cipher.ENCRYPT_MODE, key, index, last, heading).doFinal(piece, 0, filled))
            index++
            filled = 0
        }
    }

    /**
     * Reads a backup of [account] from [input], handing each message to
     * [entry] in the order it was written. Throws [Failure] with what is
     * wrong; whatever was handed over before that is what the file held —
     * nothing is handed over that was not sealed under the passphrase.
     * Throws what [input] throws.
     */
    fun read(input: InputStream, account: String, passphrase: CharArray, entry: (chat: String, key: String, payload: E2eePayload) -> Unit) {
        val heading = ByteArray(HEADING_BYTES)
        if (fill(input, heading) != HEADING_BYTES || !heading.copyOf(MAGIC.size).contentEquals(MAGIC)) throw Failure(Problem.NotABackup)
        if (heading[MAGIC.size] != VERSION) throw Failure(Problem.NewerVersion)
        var rounds = 0
        for (byte in 0 until 4) rounds = (rounds shl 8) or (heading[MAGIC.size + 1 + byte].toInt() and 0xff)
        if (rounds !in 1..MAX_ROUNDS) throw Failure(Problem.Damaged)
        val keyed = heading.copyOf(KEYED_BYTES)
        val salt = keyed.copyOfRange(KEYED_BYTES - TAG_BYTES - SALT_BYTES, KEYED_BYTES - TAG_BYTES)
        val tag = keyed.copyOfRange(KEYED_BYTES - TAG_BYTES, KEYED_BYTES)
        if (!MessageDigest.isEqual(tag, accountTag(salt, account))) throw Failure(Problem.OtherAccount)
        // No backup is written under no passphrase at all.
        val secret = canonical(passphrase).ifEmpty { throw Failure(Problem.WrongPassphrase) }
        val keys = keys(secret, rounds, keyed, account)
        if (!MessageDigest.isEqual(keys.check, heading.copyOfRange(KEYED_BYTES, HEADING_BYTES))) throw Failure(Problem.WrongPassphrase)

        val stream = DataInputStream(Pieces(input, keys.seal, heading))
        var chat: String? = null
        while (true) {
            val record = stream.read()
            if (record < 0) return
            var key: String? = null
            var payload: E2eePayload? = null
            try {
                when (record) {
                    CHAT -> chat = String(block(stream), Charsets.UTF_8)
                    ENTRY -> {
                        key = String(block(stream), Charsets.UTF_8)
                        payload = E2eePayload.decode(block(stream))
                    }
                    else -> throw Failure(Problem.Damaged)
                }
            } catch (_: EOFException) {
                // The pieces are whole and the last record is not: written that way, or not by this app.
                throw Failure(Problem.Damaged)
            }
            if (key != null && payload != null) entry(chat ?: throw Failure(Problem.Damaged), key, payload)
        }
    }

    private fun block(stream: DataInputStream): ByteArray {
        val length = stream.readInt()
        if (length < 0 || length > MAX_RECORD_BYTES) throw Failure(Problem.Damaged)
        return ByteArray(length).also { stream.readFully(it) }
    }

    /** Reads until [into] is full or the input ends; how many bytes were read. */
    private fun fill(input: InputStream, into: ByteArray): Int {
        var filled = 0
        while (filled < into.size) {
            val count = input.read(into, filled, into.size - filled)
            if (count < 0) break
            filled += count
        }
        return filled
    }

    /**
     * The pieces of a file as one stream of what they hold. A piece is
     * opened — which is where it is found to be what was written, in its
     * place — before a byte of it is given out.
     */
    private class Pieces(private val input: InputStream, private val key: SecretKeySpec, private val heading: ByteArray) : InputStream() {
        private var sealed = ByteArray(SEALED_PIECE_BYTES)
        private var sealedLength = fill(input, sealed)

        /** The piece after [sealed]: only by finding nothing there is a piece known to be the last. */
        private var ahead = ByteArray(SEALED_PIECE_BYTES)
        private var opened = ByteArray(0)
        private var position = 0
        private var index = 0L
        private var ended = false

        override fun read(): Int = if (more()) opened[position++].toInt() and 0xff else -1

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!more()) return -1
            val count = minOf(length, opened.size - position)
            System.arraycopy(opened, position, target, offset, count)
            position += count
            return count
        }

        /** True when a byte is there to be read; opens the next piece when this one is used up. */
        private fun more(): Boolean {
            while (position >= opened.size) {
                if (ended) return false
                val aheadLength = fill(input, ahead)
                val last = aheadLength == 0
                opened = try {
                    cipher(Cipher.DECRYPT_MODE, key, index, last, heading).doFinal(sealed, 0, sealedLength)
                } catch (_: Exception) {
                    throw Failure(Problem.Damaged)
                }
                position = 0
                index++
                ended = last
                val used = sealed
                sealed = ahead
                sealedLength = aheadLength
                ahead = used
            }
            return true
        }
    }
}
