package com.nchat.app.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app keeps on the phone beside its keys — cached conversations,
 * previews, the outbox, drafts — is sealed with [LocalSeal]. These pin down
 * what "sealed" has to mean for a file somebody copies off the phone, puts
 * in another's place, or alters.
 */
class LocalSealTest {
    private val key = ByteArray(32) { (it * 5 + 1).toByte() }
    private val text = "[{\"text\":\"meet me at seven\"}]".toByteArray(Charsets.UTF_8)

    @Test
    fun whatIsSealedOpensAgainAndSaysNothingMeanwhile() {
        val seal = LocalSeal(key)
        val stored = seal.seal("messages/alice/bob", text)
        assertTrue(LocalSeal.isSealed(stored))
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("seven"))
        assertArrayEquals(text, seal.open("messages/alice/bob", stored))
        // Another seal made from the same key — the app after a restart — opens it too.
        assertArrayEquals(text, LocalSeal(key.copyOf()).open("messages/alice/bob", stored))
        // The same content sealed twice does not look the same: nothing says "unchanged".
        assertNotEquals(stored.toList(), seal.seal("messages/alice/bob", text).toList())
        // Nothing to seal is still sealed, and comes back as nothing.
        assertArrayEquals(ByteArray(0), seal.open("drafts", seal.seal("drafts", ByteArray(0))))
    }

    @Test
    fun itOpensOnlyInItsOwnPlaceUnderItsOwnKeyAndWhole() {
        val seal = LocalSeal(key)
        val stored = seal.seal("messages/alice/bob", text)
        assertNull("put in another conversation's place", seal.open("messages/alice/carol", stored))
        assertNull("put in the outbox's place", seal.open("outbox", stored))
        assertNull("another account's key", LocalSeal(ByteArray(32) { 9 }).open("messages/alice/bob", stored))
        for (index in stored.indices) {
            val altered = stored.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertNull("byte $index altered", seal.open("messages/alice/bob", altered))
        }
        assertNull("cut short", seal.open("messages/alice/bob", stored.copyOf(stored.size - 1)))
        assertNull("only the mark", seal.open("messages/alice/bob", stored.copyOf(4)))
        // Cut off inside its heading: it looks sealed, and is nobody's.
        for (length in 5..13) {
            assertNull("first $length bytes", seal.open("messages/alice/bob", stored.copyOf(length)))
            assertFalse("first $length bytes", seal.sealedHere(stored.copyOf(length)) && length < 13)
        }
        assertNull(seal.open("messages/alice/bob", ByteArray(0)))
    }

    @Test
    fun itTellsAnotherKeysFileFromADamagedOne() {
        val seal = LocalSeal(key)
        val stored = seal.seal("outbox", text)
        val other = LocalSeal(ByteArray(32) { 9 })
        // Sealed for another account, or before a sign-out: it will never open here.
        assertTrue(LocalSeal.isSealed(stored))
        assertFalse(other.sealedHere(stored))
        // Sealed with this key: if it does not open, it is damaged or in the wrong place.
        assertTrue(seal.sealedHere(stored))
        val damaged = stored.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertTrue(seal.sealedHere(damaged))
        assertNull(seal.open("outbox", damaged))
        assertTrue(seal.sealedHere(stored))
        assertNull(seal.open("drafts", stored))
        // The mark that says which key it was says nothing else: two keys, two marks, neither the key.
        val mark = stored.copyOfRange(5, 13).toList()
        assertNotEquals(mark, other.seal("outbox", text).copyOfRange(5, 13).toList())
        assertFalse(key.toList().windowed(8).any { it == mark })
        assertEquals("the same key always marks alike", mark, LocalSeal(key.copyOf()).seal("drafts", ByteArray(0)).copyOfRange(5, 13).toList())
        assertFalse(seal.sealedHere("[]".toByteArray()))
    }

    @Test
    fun aFileFromBeforeSealingIsNeverTakenForASealedOne() {
        // Every file the stores wrote before was JSON text.
        for (plain in listOf("[]", "{}", "[{\"id\":\"1\"}]", " [1]", "\n{\"a\":1}", "", "N", "NCS", "null", "NCSx")) {
            val bytes = plain.toByteArray(Charsets.UTF_8)
            assertFalse(plain, LocalSeal.isSealed(bytes))
            assertNull(plain, LocalSeal(key).open("drafts", bytes))
        }
    }

    @Test
    fun aKeyOfTheWrongSizeIsRefused() {
        for (size in listOf(0, 16, 31, 33)) {
            try {
                LocalSeal(ByteArray(size))
                throw AssertionError("a $size-byte key was accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
