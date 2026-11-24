package com.nchat.app.network

import java.security.MessageDigest

/**
 * The number two people compare to make sure their conversation is encrypted
 * for each other and for nobody in between.
 *
 * The server hands out everybody's public keys, so a server that wanted to
 * read a conversation would hand each side a key of its own. The two phones
 * would then hold different keys for the same conversation — and show
 * different numbers here. Comparing the number, in person or over a call, is
 * the only check that does not go through the server.
 *
 * The construction is the one Signal and WhatsApp use for their safety
 * number / security code, on this app's keys:
 *
 *     half(account, key) = 30 digits of
 *         h = 00 01 ‖ key ‖ account          (version, the 91-byte key, the account id in UTF-8)
 *         5200 times: h = SHA-512(h ‖ key)
 *         six times: the next 5 bytes of h as a number, modulo 100000, as 5 digits
 *     code = the two halves, the smaller first
 *
 * Ordering the halves rather than putting "mine" first is what makes both
 * phones show the same sixty digits. The account id is part of each half so
 * that a key registered under another account gives another code. The
 * repeated hashing makes searching for a key with a chosen half 5200 times
 * as expensive; each half carries just under 100 bits.
 */
object SecurityCode {
    /** Format version, the first two bytes that are hashed. A change here changes every code. */
    private const val VERSION = 1
    private const val ITERATIONS = 5200
    private const val CHUNKS_PER_HALF = 6
    private const val CHUNK_BYTES = 5
    private const val CHUNK_DIGITS = 5
    private const val CHUNK_MODULUS = 100_000L

    /** Digits in a code. */
    const val DIGITS = 2 * CHUNKS_PER_HALF * CHUNK_DIGITS

    /**
     * The code for the conversation between two accounts, given the identity
     * key each of them uses (canonical P-256 SubjectPublicKeyInfo). Which of
     * the two is "local" makes no difference to the result.
     *
     * Throws [IllegalArgumentException] for an empty account id or anything
     * that is not such a key: no code is better than a code for the wrong thing.
     */
    fun of(localId: String, localIdentity: ByteArray, peerId: String, peerIdentity: ByteArray): String {
        val mine = half(localId, localIdentity)
        val theirs = half(peerId, peerIdentity)
        return if (mine <= theirs) mine + theirs else theirs + mine
    }

    /** [code] as it is shown: groups of five digits. */
    fun grouped(code: String): List<String> = code.chunked(CHUNK_DIGITS)

    // ------------------------------------------------------------ as a QR code

    /**
     * What a QR code of [code] says: a few characters that name it, then the
     * sixty digits. Scanning then compares exactly what two people would
     * compare by eye, and nothing else travels in the picture.
     *
     * Capitals, digits, a hyphen and a colon only — the characters a QR code
     * packs densely, so the picture stays small.
     */
    fun qrText(code: String): String {
        require(isCode(code)) { "not a security code" }
        return QR_PREFIX + code
    }

    /** The code a scanned QR code carries; null when it is not a security code, or one of a version this build does not know. */
    fun fromQr(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(QR_PREFIX)) return null
        return trimmed.substring(QR_PREFIX.length).takeIf(::isCode)
    }

    /** What scanning the other person's QR code showed. */
    enum class Scanned {
        /** Their phone shows the code this phone shows: nobody is in between. */
        Same,

        /** A security code, and not this one: the wrong chat — or somebody in between. */
        Different,

        /** Some other QR code. */
        NotACode,
    }

    /** Compares what was scanned with [own], the code this phone shows for the same chat. */
    fun scanned(text: String, own: String): Scanned {
        val theirs = fromQr(text) ?: return Scanned.NotACode
        return if (isCode(own) && theirs == own) Scanned.Same else Scanned.Different
    }

    /** Sixty of the digits 0 to 9 — not the digits of another script, which [Char.isDigit] would let through. */
    private fun isCode(value: String): Boolean = value.length == DIGITS && value.all { it in '0'..'9' }

    private const val QR_PREFIX = "NCHAT-SC1:"

    private fun half(accountId: String, identity: ByteArray): String {
        require(accountId.isNotBlank()) { "an account id is required" }
        require(E2eeProtocol.isValidPublicKey(identity)) { "not a P-256 public key" }
        val digest = MessageDigest.getInstance("SHA-512")
        var hash = byteArrayOf((VERSION shr 8).toByte(), VERSION.toByte()) + identity + accountId.toByteArray(Charsets.UTF_8)
        repeat(ITERATIONS) {
            digest.update(hash)
            hash = digest.digest(identity)
        }
        val digits = StringBuilder(CHUNKS_PER_HALF * CHUNK_DIGITS)
        for (chunk in 0 until CHUNKS_PER_HALF) {
            var value = 0L
            for (index in 0 until CHUNK_BYTES) value = (value shl 8) or (hash[chunk * CHUNK_BYTES + index].toLong() and 0xff)
            // Not String.format: that follows the phone's language, and two people must be able to compare digit by digit.
            digits.append((value % CHUNK_MODULUS).toString().padStart(CHUNK_DIGITS, '0'))
        }
        return digits.toString()
    }
}
