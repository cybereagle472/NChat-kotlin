package com.nchat.app.network

/**
 * Seals what the app keeps on the phone beside its keys and its encrypted
 * history: cached conversations, the chat and group lists with their
 * previews, messages waiting to be sent, drafts.
 *
 * It is made from the account's storage key (see [EndToEnd.localSeal]), so
 * it exists exactly as long as that key does: signing out, or another
 * account signing in, leaves whatever was sealed unopenable.
 *
 * A sealed file is `mark (4) || version (1) || key id (8) || SealedBox`.
 *  - The mark begins with a zero byte. Every file these stores wrote before
 *    was JSON text, which cannot begin with one — so a file from an earlier
 *    build is never taken for a sealed one, nor the other way round.
 *  - The key id says which key sealed the file and nothing about that key.
 *    It tells "sealed for another account, or before a sign-out" (it will
 *    never open here) from "sealed with this key and still does not open"
 *    (damaged, or put in another file's place).
 *  - The place a file was sealed for is authenticated with it: a
 *    conversation copied over another one, or over the outbox, does not open.
 */
class LocalSeal(key: ByteArray) {

    private val box: SealedBox
    private val keyId: ByteArray

    init {
        require(key.size == SealedBox.KEY_BYTES) { "sealing key must be ${SealedBox.KEY_BYTES} bytes" }
        // Never the storage key itself: that one seals the keys and the encrypted history.
        box = SealedBox(E2eeProtocol.hkdf(key, SALT, "seal".toByteArray(Charsets.UTF_8), SealedBox.KEY_BYTES))
        keyId = E2eeProtocol.hkdf(key, SALT, "key id".toByteArray(Charsets.UTF_8), KEY_ID_BYTES)
    }

    fun seal(place: String, content: ByteArray): ByteArray = MARK + VERSION + keyId + box.seal(place, content)

    /** The content, or null for anything that is not exactly what [seal] produced with this key for [place]. */
    fun open(place: String, stored: ByteArray): ByteArray? {
        if (!sealedHere(stored)) return null
        return box.open(place, stored.copyOfRange(HEADER_BYTES, stored.size))
    }

    /**
     * True when [stored] was sealed with this very key. If it then does not
     * [open], it is damaged or in the wrong place; when this is false it was
     * sealed with another key and will never open here.
     */
    fun sealedHere(stored: ByteArray): Boolean {
        if (!isSealed(stored) || stored.size < HEADER_BYTES) return false
        var difference = 0
        for (index in 0 until KEY_ID_BYTES) difference = difference or (stored[MARK.size + 1 + index].toInt() xor keyId[index].toInt())
        return difference == 0
    }

    companion object {
        private val MARK = byteArrayOf(0, 'N'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte())
        private const val VERSION: Byte = 1
        private const val KEY_ID_BYTES = 8
        private val HEADER_BYTES = MARK.size + 1 + KEY_ID_BYTES
        private val SALT = "nchat local seal v1".toByteArray(Charsets.UTF_8)

        /** How much of a file [isSealed] needs to see. */
        val MARK_BYTES: Int = MARK.size + 1

        /** True when [stored] begins like a sealed file — as opposed to one written before sealing. */
        fun isSealed(stored: ByteArray): Boolean {
            if (stored.size < MARK_BYTES) return false
            for (index in MARK.indices) if (stored[index] != MARK[index]) return false
            return stored[MARK.size] == VERSION
        }
    }
}
