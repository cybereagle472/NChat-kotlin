package com.nchat.app.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red-phase contract for the encrypted-state store (Batch 3 wiring): session
 * persistence with fail-closed reads, one-time prekey consume/restore, and a
 * bounded sent-plaintext cache so our own sealed bubbles can be re-rendered
 * from server history.
 */
class E2eeStoreTest {

    private class MemoryVault : SecretVault {
        val entries = linkedMapOf<String, String>()
        override fun read(key: String): String? = entries[key]
        override fun write(key: String, value: String?) {
            if (value == null) entries.remove(key) else entries[key] = value
        }
    }

    private fun sampleSession(): E2eeProtocol.Session {
        val alice = E2eeProtocol.generateIdentity()
        val bob = E2eeProtocol.generateIdentity()
        val bobPrekey = E2eeProtocol.generatePrekeyPair()
        val otk = E2eeProtocol.generateOneTimePrekey()
        return E2eeProtocol.initiate(
            alice,
            E2eeProtocol.PeerBundle(
                identityPublic = bob.identityPublic,
                signedPrekeyPublic = bobPrekey.public,
                signedPrekeySignature = E2eeProtocol.signPrekey(bobPrekey.public, bob.identityPrivate),
                oneTimePrekeyId = 3,
                oneTimePrekeyPublic = otk.public,
            ),
        )
    }

    @Test
    fun sessionsRoundTripThroughTheVault() {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        val session = sampleSession()
        store.saveSession("peer-1", session)

        val loaded = store.loadSession("peer-1")
        assertNotNull(loaded)
        assertArrayEquals(session.serialize(), loaded!!.serialize())

        // Isolation: another peer never sees this session.
        assertNull(store.loadSession("peer-2"))
    }

    @Test
    fun corruptSessionBytesFailClosed() {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        store.saveSession("peer-1", sampleSession())
        val key = vault.entries.keys.single { it.startsWith("e2ee.session.") }
        val corrupted = E2eeProtocol.encodeBase64(
            E2eeProtocol.decodeBase64(vault.entries[key]!!)!!.also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() },
        )
        vault.entries[key] = corrupted
        assertNull(store.loadSession("peer-1"))
    }

    @Test
    fun oneTimeKeysAreConsumedOnceAndCanBeRestored() {
        val store = E2eeStore(MemoryVault())
        val first = ByteArray(91) { 1 }
        val second = ByteArray(91) { 2 }
        store.putOneTimeKeys(mapOf(11 to first, 12 to second))
        assertEquals(2, store.oneTimeKeyCount())

        assertArrayEquals(first, store.takeOneTimeKey(11))
        assertEquals(1, store.oneTimeKeyCount())
        assertNull(store.takeOneTimeKey(11)) // single use

        store.restoreOneTimeKey(11, first)
        assertEquals(2, store.oneTimeKeyCount())
        assertArrayEquals(first, store.takeOneTimeKey(11))
        assertNull(store.takeOneTimeKey(99))
    }

    @Test
    fun sentPlaintextCacheIsBoundedFifo() {
        val store = E2eeStore(MemoryVault())
        val total = 520
        for (index in 0 until total) store.rememberSentPlaintext("client-$index", "text-$index")
        assertEquals("text-${total - 1}", store.sentPlaintext("client-${total - 1}"))
        assertNull(store.sentPlaintext("client-0")) // oldest evicted
        assertEquals(E2eeStore.MAX_CACHED_SENT, store.sentPlaintextCount())
    }

    @Test
    fun vaultValuesAreStringsNotRawJsonArrays() {
        // Regression: binding a ByteArray into structured storage once produced a
        // JSON array of numbers — everything must travel as canonical strings.
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        store.putOneTimeKeys(mapOf(5 to ByteArray(91) { 9 }))
        val blob = vault.entries.getValue("e2ee.otk")
        assertTrue(blob.startsWith("{"))
        assertTrue(E2eeProtocol.decodeBase64(blob.substringAfter('"').substringBefore('"'))!!.isNotEmpty())
    }
}
