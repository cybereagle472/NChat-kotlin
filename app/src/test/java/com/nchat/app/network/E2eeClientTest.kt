package com.nchat.app.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Red-phase contract for the seal/open orchestration (Batch 3 wiring):
 * capability-driven sealing (bundle fetch only when no session exists), X3DH
 * receive with one-time-key consume/restore, no-downgrade on bad signatures,
 * and fail-closed opens.
 */
class E2eeClientTest {

    private class MemoryVault : SecretVault {
        val entries = linkedMapOf<String, String>()
        override fun read(key: String): String? = entries[key]
        override fun write(key: String, value: String?) {
            if (value == null) entries.remove(key) else entries[key] = value
        }
    }

    private class Peer {
        val identity = E2eeProtocol.generateIdentity()
        val prekey = E2eeProtocol.generatePrekeyPair()
        val otk = E2eeProtocol.generateOneTimePrekey()
        val store = E2eeStore(MemoryVault())

        init {
            store.putOneTimeKeys(mapOf(7 to otk.private))
        }

        fun bundle() = E2eeProtocol.PeerBundle(
            identityPublic = identity.identityPublic,
            signedPrekeyPublic = prekey.public,
            signedPrekeySignature = E2eeProtocol.signPrekey(prekey.public, identity.identityPrivate),
            oneTimePrekeyId = 7,
            oneTimePrekeyPublic = otk.public,
        )

        fun client(fetch: suspend (String) -> E2eeProtocol.PeerBundle?) = E2eeClient(
            store = store,
            identity = { identity },
            signedPrekey = { prekey },
            fetchBundle = fetch,
        )
    }

    @Test
    fun sealFetchesTheBundleOnceThenReusesTheSession() = runBlocking {
        val bob = Peer()
        var fetches = 0
        val aliceStore = E2eeStore(MemoryVault())
        val alice = E2eeClient(
            store = aliceStore,
            identity = { E2eeProtocol.generateIdentity() },
            signedPrekey = { E2eeProtocol.generatePrekeyPair() },
            fetchBundle = { fetches++; bob.bundle() },
        )

        val first = alice.sealFor("bob", "hello")
        assertNotNull(first)
        assertEquals(1, fetches)
        assertTrue(first!!.contains("\"otk\":7"))

        val second = alice.sealFor("bob", "again")
        assertNotNull(second)
        assertEquals("the session must be reused — no second bundle fetch", 1, fetches)
        assertFalse(second!!.contains("\"otk\"")) // only the first message carries an OTK id

        // Bob opens both with his local material only — receiving needs no network.
        val openedFirst = bob.client { null }.openFrom("alice", first)
        assertEquals("hello", openedFirst)
        val openedSecond = bob.client { null }.openFrom("alice", second)
        assertEquals("again", openedSecond)

        // Replay of the already-consumed first envelope fails closed.
        assertNull(bob.client { null }.openFrom("alice", first))
    }

    @Test
    fun peerWithoutKeysMeansPlaintextNotFailure() = runBlocking {
        val alice = E2eeClient(
            store = E2eeStore(MemoryVault()),
            identity = { E2eeProtocol.generateIdentity() },
            signedPrekey = { E2eeProtocol.generatePrekeyPair() },
            fetchBundle = { null },
        )
        assertNull(alice.sealFor("plain-peer", "hi"))
        assertNull(alice.sealFor("plain-peer", "hi again"))
    }

    @Test
    fun badSignedPrekeyNeverDowngradesToPlaintext() = runBlocking {
        val bob = Peer()
        val forged = bob.bundle().copy(signedPrekeySignature = ByteArray(64) { 7 })
        val alice = E2eeClient(
            store = E2eeStore(MemoryVault()),
            identity = { E2eeProtocol.generateIdentity() },
            signedPrekey = { E2eeProtocol.generatePrekeyPair() },
            fetchBundle = { forged },
        )
        try {
            alice.sealFor("bob", "must not leak")
            fail("a signature that does not verify must throw, not return plaintext")
        } catch (expected: IllegalStateException) {
            // intended: the send surfaces an error instead of silently downgrading
        }
    }

    @Test
    fun openConsumesTheOneTimeKeyOnlyOnSuccess() = runBlocking {
        val bob = Peer()
        val alice = E2eeClient(
            store = E2eeStore(MemoryVault()),
            identity = { E2eeProtocol.generateIdentity() },
            signedPrekey = { E2eeProtocol.generatePrekeyPair() },
            fetchBundle = { bob.bundle() },
        )
        val envelope = alice.sealFor("bob", "real")!!

        // Tampered header (valid envelope, wrong message key): the one-time key
        // must go back — spam must not drain the pool.
        val tampered = envelope.replaceFirst("\"n\":0", "\"n\":1")
        val before = bob.store.oneTimeKeyCount()
        assertNull(bob.client { null }.openFrom("alice", tampered))
        assertEquals(before, bob.store.oneTimeKeyCount())

        // Garbage that is not even an envelope never touches the pool.
        assertNull(bob.client { null }.openFrom("alice", "{not json"))
        assertEquals(before, bob.store.oneTimeKeyCount())

        // The genuine envelope consumes it exactly once and yields plaintext.
        assertEquals("real", bob.client { null }.openFrom("alice", envelope))
        assertEquals(before - 1, bob.store.oneTimeKeyCount())
    }

    @Test
    fun sentPlaintextLookupSupportsHistoryRenderOfOwnMessages() {
        val client = E2eeClient(
            store = E2eeStore(MemoryVault()),
            identity = { null },
            signedPrekey = { null },
            fetchBundle = { null },
        )
        client.rememberSent("client-1", "mine")
        assertEquals("mine", client.sentPlaintext("client-1"))
        assertNull(client.sentPlaintext("client-unknown"))
    }

    @Test
    fun missingIdentityNeverSealsOrOpens() = runBlocking {
        val noKeys = E2eeClient(
            store = E2eeStore(MemoryVault()),
            identity = { null },
            signedPrekey = { null },
            fetchBundle = { throw IllegalStateException("must not be called without identity keys") },
        )
        assertNull(noKeys.sealFor("peer", "hi"))
        assertNull(noKeys.openFrom("peer", """{"v":1,"ik":"AA==","dh":"AA==","n":0,"pn":0,"ct":"AA=="}"""))
    }

    @Test
    fun openFirstWithoutOneTimePrekeySupportsThreeDh() = runBlocking {
        // Peer's bundle had no one-time prekeys: envelope carries no otk id.
        val bobIdentity = E2eeProtocol.generateIdentity()
        val bobPrekey = E2eeProtocol.generatePrekeyPair()
        val bobStore = E2eeStore(MemoryVault())
        val alice = E2eeClient(
            store = E2eeStore(MemoryVault()),
            identity = { E2eeProtocol.generateIdentity() },
            signedPrekey = { E2eeProtocol.generatePrekeyPair() },
            fetchBundle = {
                E2eeProtocol.PeerBundle(
                    identityPublic = bobIdentity.identityPublic,
                    signedPrekeyPublic = bobPrekey.public,
                    signedPrekeySignature = E2eeProtocol.signPrekey(bobPrekey.public, bobIdentity.identityPrivate),
                    oneTimePrekeyId = null,
                    oneTimePrekeyPublic = null,
                )
            },
        )
        val envelope = alice.sealFor("bob", "no otk")!!
        assertFalse(envelope.contains("\"otk\""))

        val bob = E2eeClient(
            store = bobStore,
            identity = { bobIdentity },
            signedPrekey = { bobPrekey },
            fetchBundle = { null },
        )
        assertEquals("no otk", bob.openFrom("alice", envelope))
        assertEquals(0, bobStore.oneTimeKeyCount())
    }
}
