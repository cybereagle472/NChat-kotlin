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
