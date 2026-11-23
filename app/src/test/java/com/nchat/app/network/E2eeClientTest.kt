package com.nchat.app.network

import com.nchat.app.network.E2eeProtocol.Envelope
import com.nchat.app.network.E2eeProtocol.PeerBundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two (sometimes three) devices and a pretend server, exercised the way the
 * app will: seal on one side, reveal on the other, in every order and through
 * every failure the design claims to survive.
 */
class E2eeClientTest {
    @get:Rule val folder = TemporaryFolder()

    private class MemoryVault : SecretVault {
        val entries = LinkedHashMap<String, String>()
        var sessionWrites = 0
        var poolReads = 0
        var failSessionWrites = false
        var failReads = false

        @Synchronized
        override fun read(key: String): String? {
            if (failReads) throw IOException("storage is gone")
            if (key == "e2ee.otk") poolReads++
            return entries[key]
        }

        @Synchronized
        override fun contains(key: String): Boolean = entries.containsKey(key)

        @Synchronized
        override fun write(key: String, value: String?, durable: Boolean) {
            if (key.startsWith("e2ee.session.")) {
                if (failSessionWrites) throw IOException("disk full")
                sessionWrites++
            }
            if (value == null) entries.remove(key) else entries[key] = value
        }
    }

    /** One installation of the app: its keys, its storage, and what the server holds for it. */
    private inner class Device(val id: String, installation: String = "1") {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        val archiveDirectory: File = folder.root.resolve("archive-$id-$installation")
        private val archiveKey = ByteArray(32) { (id.hashCode() + it).toByte() }
        var identity: E2eeProtocol.IdentityKeyPair? = E2eeProtocol.generateIdentity()
        val prekey: E2eeProtocol.PrekeyPair = E2eeProtocol.generatePrekeyPair()

        /** Public one-time prekeys the server still holds for this device. */
        val published = ArrayDeque<ByteArray>()
        val identityLookups = AtomicInteger()

        fun publishOneTimeKeys(count: Int) {
            val pairs = List(count) { E2eeProtocol.generateOneTimePrekey() }
            // Private halves first, exactly as the app does it.
            store.addOneTimeKeys(pairs.associate { E2eeProtocol.keyReference(it.public) to it.private })
            pairs.forEach { published.addLast(it.public) }
        }

        fun bundle(): PeerBundle? {
            val keys = identity ?: return null
            return PeerBundle(
                identityPublic = keys.identityPublic,
                signedPrekeyPublic = prekey.public,
                signedPrekeySignature = E2eeProtocol.signPrekey(prekey.public, keys.identityPrivate),
                oneTimePrekeyPublic = published.removeFirstOrNull(),
            )
        }

        val identityKey: String get() = encodeBase64(identity!!.identityPublic)

        /** A fresh client over this device's storage — what an app restart produces. */
        fun client(server: Server, archiveAt: File = archiveDirectory) = E2eeClient(
            localId = id,
            store = store,
            archive = E2eeArchive(archiveAt, archiveKey),
            identity = {
                identityLookups.incrementAndGet()
                identity
            },
            signedPrekey = { prekey },
            fetchBundle = { server.bundle(it) },
        )
    }

    private inner class Server {
        val devices = HashMap<String, Device>()
        val fetches = AtomicInteger()
        var tamper: ((PeerBundle) -> PeerBundle)? = null

        @Synchronized
        fun bundle(id: String): PeerBundle? {
            fetches.incrementAndGet()
            val bundle = devices[id]?.bundle() ?: return null
            return tamper?.invoke(bundle) ?: bundle
        }

        fun install(id: String, installation: String = "1", oneTimeKeys: Int = 5): Device {
            val device = Device(id, installation)
            device.publishOneTimeKeys(oneTimeKeys)
            devices[id] = device
            return device
        }
    }

    /** A message as the server stores and serves it. */
    private class Wire(val id: String, val clientId: String, val from: String, val to: String, val envelope: String)

    private val counter = AtomicInteger()

    private fun send(client: E2eeClient, from: String, to: String, text: String): Wire {
        val n = counter.incrementAndGet()
        val sealed = runBlocking { client.seal(to, "c-$n", E2eePayload.text(text)) }
        assertNotNull("the peer has keys, so the message must be sealed", sealed)
        return Wire("s-$n", "c-$n", from, to, sealed!!.envelope)
    }

    /** The recipient's view of [wire]. */
    private fun read(client: E2eeClient, wire: Wire): String? =
        client.reveal(wire.from, wire.id, wire.clientId, mine = false, envelope = wire.envelope)?.body

    /** The sender's own view of [wire]. */
    private fun readOwn(client: E2eeClient, wire: Wire): String? =
        client.reveal(wire.to, wire.id, wire.clientId, mine = true, envelope = wire.envelope)?.body

    private fun sealFails(client: E2eeClient, to: String, why: String) {
        try {
            runBlocking { client.seal(to, "c-${counter.incrementAndGet()}", E2eePayload.text("must never leave unsealed")) }
            fail(why)
        } catch (expected: IllegalStateException) {
            // intended: an error, never a silent fallback to plaintext
        }
    }

    // ------------------------------------------------------------ happy path

    @Test
    fun firstMessageEstablishesAndBothSidesConverse() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)

        val sealed = runBlocking { a.seal("bob", "c-first", E2eePayload.text("hello bob")) }!!
        assertFalse(sealed.envelope.contains("hello bob"))
        assertEquals("the envelope says whose keys it was sealed for", bob.identityKey, sealed.recipientIdentity)
        assertEquals("hello bob", b.reveal("alice", "s-first", null, mine = false, envelope = sealed.envelope)?.body)

        for (round in 0 until 5) {
            assertEquals("b$round", read(a, send(b, "bob", "alice", "b$round")))
            assertEquals("a$round", read(b, send(a, "alice", "bob", "a$round")))
        }
        assertEquals("one bundle for the whole conversation", 1, server.fetches.get())
        assertEquals("the server gave out one of bob's one-time prekeys", 4, bob.published.size)
        assertEquals("and bob forgot its private half once the session was stored", 4, bob.store.oneTimeKeyCount())
    }

    @Test
    fun peerWithoutKeysMeansPlaintextAndNoState() {
        val server = Server()
        val alice = server.install("alice")
        val a = alice.client(server)
        assertNull(runBlocking { a.seal("old-client", "c-1", E2eePayload.text("hi")) })
        assertNull(runBlocking { a.seal("old-client", "c-2", E2eePayload.text("hi again")) })
        // Asked every time: the peer may have updated since the last message.
        assertEquals(2, server.fetches.get())
        assertNull(alice.store.loadPeer("old-client"))
        assertTrue(alice.vault.entries.keys.none { it.startsWith("e2ee.session.") })
    }

    @Test
    fun withoutAnIdentityNothingIsFetchedAndNothingIsSent() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        alice.identity = null
        sealFails(alice.client(server), "bob", "a device without an identity key cannot seal")
        assertEquals("a bundle we cannot use must not be fetched", 0, server.fetches.get())
        assertEquals(5, bob.published.size)
    }

    @Test
    fun blankIdsAreRefused() {
        val server = Server()
        val a = server.install("alice").client(server)
        for ((peer, clientId) in listOf("" to "c-1", "bob" to "", " " to "c-1")) {
            try {
                runBlocking { a.seal(peer, clientId, E2eePayload.text("x")) }
                fail("blank ids must be refused")
            } catch (expected: IllegalArgumentException) {
                // intended
            }
        }
        assertEquals(0, server.fetches.get())
    }

    @Test
    fun unknownPayloadTypesSurviveTheWire() {
        val server = Server()
        val a = server.install("alice").client(server)
        val b = server.install("bob").client(server)
        val sealed = runBlocking { a.seal("bob", "c-1", E2eePayload(42, "{\"future\":true}")) }!!
        val opened = b.reveal("alice", "s-1", null, mine = false, envelope = sealed.envelope)
        assertEquals(42, opened?.type)
        assertEquals("{\"future\":true}", opened?.body)
        assertEquals(42, a.reveal("bob", "s-1", "c-1", mine = true, envelope = sealed.envelope)?.type)
    }

    // ---------------------------------------------------------- no downgrade

    @Test
    fun onceEncryptedNeverPlaintextAgain() {
        val server = Server()
        val alice = server.install("alice")
        server.install("bob")
        val a = alice.client(server)
        send(a, "alice", "bob", "sealed")

        // The server now claims bob has no keys. A working session does not even ask.
        server.devices.remove("bob")
        val before = server.fetches.get()
        send(a, "alice", "bob", "still sealed")
        assertEquals(before, server.fetches.get())

        // With the session retired a bundle is needed — and "no keys" is refused, not obeyed.
        a.retireSessions("bob")
        sealFails(a, "bob", "a chat that was encrypted must never fall back to plaintext")
        sealFails(alice.client(server), "bob", "…also after a restart")
    }

    @Test
    fun damagedSessionRecordStillForbidsPlaintext() {
        val server = Server()
        val alice = server.install("alice")
        server.install("bob")
        send(alice.client(server), "alice", "bob", "sealed")
        val entry = alice.vault.entries.keys.single { it.startsWith("e2ee.session.") }
        alice.vault.entries[entry] = "damaged beyond repair"

        server.devices.remove("bob")
        sealFails(alice.client(server), "bob", "a damaged record is still proof the chat was encrypted")

        // When bob's keys are available, a new session simply replaces the damaged one.
        val bob = server.install("bob", installation = "2")
        val a = alice.client(server)
        assertEquals("recovered", read(bob.client(server), send(a, "alice", "bob", "recovered")))
    }

    @Test
    fun bundleThatDoesNotVerifyThrowsAndLeavesNothingBehind() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val mallory = E2eeProtocol.generateIdentity()

        server.tamper = { it.copy(signedPrekeySignature = ByteArray(64) { 1 }) }
        sealFails(a, "bob", "a forged signature must be an error")
        server.tamper = { it.copy(identityPublic = mallory.identityPublic) }
        sealFails(a, "bob", "a substituted identity must be an error")
        server.tamper = { it.copy(signedPrekeyPublic = E2eeProtocol.generatePrekeyPair().public) }
        sealFails(a, "bob", "a substituted prekey must be an error")
        assertNull(alice.store.loadPeer("bob"))

        server.tamper = null
        assertEquals("genuine", read(bob.client(server), send(a, "alice", "bob", "genuine")))
    }

    // ------------------------------------------------------------- re-reading

    @Test
    fun everyMessageStaysReadableInBothDirections() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        val first = send(a, "alice", "bob", "first")
        val reply = send(b.also { assertEquals("first", read(it, first)) }, "bob", "alice", "reply")
        assertEquals("reply", read(a, reply))

        repeat(3) {
            assertEquals("first", read(b, first))
            assertEquals("first", readOwn(a, first))
            assertEquals("reply", read(a, reply))
            assertEquals("reply", readOwn(b, reply))
        }

        // After a restart on both phones nothing has to go through the ratchet again.
        val a2 = alice.client(server)
        val b2 = bob.client(server)
        val aliceWrites = alice.vault.sessionWrites
        val bobWrites = bob.vault.sessionWrites
        repeat(3) {
            assertEquals("first", read(b2, first))
            assertEquals("first", readOwn(a2, first))
            assertEquals("reply", read(a2, reply))
            assertEquals("reply", readOwn(b2, reply))
        }
        assertEquals("re-reading must not touch ratchet state", aliceWrites, alice.vault.sessionWrites)
        assertEquals(bobWrites, bob.vault.sessionWrites)
    }

    @Test
    fun ownMessageIsReadableOnlyWhereItWasSent() {
        val server = Server()
        val alice = server.install("alice")
        server.install("bob")
        val a = alice.client(server)
        val wire = send(a, "alice", "bob", "mine")
        assertEquals("mine", readOwn(a, wire))

        // The same account on another phone has the envelope but never had the content.
        val otherPhone = Device("alice", installation = "2")
        assertNull(readOwn(otherPhone.client(server), wire))
        // Without the client id there is nothing to look the message up by.
        assertNull(a.reveal("bob", wire.id, null, mine = true, envelope = wire.envelope))
        assertNull(a.reveal("", wire.id, wire.clientId, mine = true, envelope = wire.envelope))
        // The right id with a different envelope is a different message.
        val other = send(a, "alice", "bob", "another")
        assertNull(a.reveal("bob", wire.id, wire.clientId, mine = true, envelope = other.envelope))
    }

    @Test
    fun restartingBetweenEveryStepLosesNothing() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val history = ArrayList<Pair<Wire, String>>()
        for (round in 0 until 12) {
            val out = send(alice.client(server), "alice", "bob", "a$round")
            history += out to "a$round"
            assertEquals("a$round", read(bob.client(server), out))
            if (round % 3 != 0) {
                val back = send(bob.client(server), "bob", "alice", "b$round")
                assertEquals("b$round", read(alice.client(server), back))
            }
        }
        assertEquals(1, server.fetches.get())
        val a = alice.client(server)
        val b = bob.client(server)
        for ((wire, text) in history) {
            assertEquals(text, read(b, wire))
            assertEquals(text, readOwn(a, wire))
        }
    }

    // ----------------------------------------------------- replay and tamper

    @Test
    fun replayedEnvelopesAreRefused() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        val first = send(a, "alice", "bob", "first")
        assertEquals("first", read(b, first))
        assertEquals("reply", read(a, send(b, "bob", "alice", "reply")))
        val second = send(a, "alice", "bob", "second")
        assertEquals("second", read(b, second))

        // The server (or anyone who can inject rows) presents old envelopes as new messages.
        assertNull(b.reveal("alice", "s-replay-1", null, mine = false, envelope = second.envelope))
        assertNull(b.reveal("alice", "s-replay-2", null, mine = false, envelope = first.envelope))
        assertNull(bob.client(server).reveal("alice", "s-replay-3", null, mine = false, envelope = first.envelope))
        // …and as messages from somebody else.
        server.install("carol")
        assertNull(b.reveal("carol", first.id, null, mine = false, envelope = first.envelope))
        // The originals are untouched.
        assertEquals("first", read(b, first))
        assertEquals("second", read(b, second))
    }

    @Test
    fun handshakeWithoutAOneTimeKeyCannotBeReplayed() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob", oneTimeKeys = 0)
        val a = alice.client(server)
        val first = send(a, "alice", "bob", "first")
        assertFalse("this handshake has no one-time prekey to burn", first.envelope.contains("\"otk\""))
        assertEquals("first", read(bob.client(server), first))
        assertNull(bob.client(server).reveal("alice", "s-replay-1", null, mine = false, envelope = first.envelope))

        // Even when the session it created is long gone, the handshake is remembered.
        val record = bob.store.loadPeer("alice")!!
        record.sessions.clear()
        bob.store.savePeer("alice", record, durable = true)
        assertNull(bob.client(server).reveal("alice", "s-replay-2", null, mine = false, envelope = first.envelope))
        assertTrue(bob.store.loadPeer("alice")!!.sessions.isEmpty())
    }

    @Test
    fun tamperedOrHostileEnvelopesChangeNothing() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        val genuine = send(a, "alice", "bob", "genuine")

        val at = genuine.envelope.indexOf("\"ct\":\"") + 6
        val flipped = genuine.envelope.substring(0, at) +
            (if (genuine.envelope[at] == 'A') 'B' else 'A') + genuine.envelope.substring(at + 1)
        val hostile = listOf(
            "", " ", "{}", "null", "[1,2]", "not json", "{\"v\":1}", "{\"v\":1,\"ct\":7}",
            genuine.envelope.dropLast(2),
            genuine.envelope.replace("\"n\":0", "\"n\":1"),
            genuine.envelope.replace("\"v\":1", "\"v\":2"),
            flipped,
            "x".repeat(200_000),
        )
        for ((index, envelope) in hostile.withIndex()) {
            assertNull("hostile envelope #$index must not open", b.reveal("alice", "s-h$index", null, mine = false, envelope = envelope))
        }
        assertEquals("junk must not spend a one-time prekey", 5, bob.store.oneTimeKeyCount())
        assertNull("junk must not create state", bob.store.loadPeer("alice"))
        assertEquals("genuine", read(b, genuine))
        assertEquals(4, bob.store.oneTimeKeyCount())
    }

    @Test
    fun storageFailuresNeverThrowOutOfReveal() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val wire = send(alice.client(server), "alice", "bob", "hello")
        bob.vault.failReads = true
        assertNull(read(bob.client(server), wire))
        bob.vault.failReads = false
        assertEquals("hello", read(bob.client(server), wire))
    }

    // --------------------------------------------------- interrupted writes

    @Test
    fun oneTimeKeyIsForgottenOnlyAfterTheSessionIsStored() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val zero = send(a, "alice", "bob", "zero")
        val one = send(a, "alice", "bob", "one")

        bob.vault.failSessionWrites = true
        assertEquals("readable even though the state could not be stored", "zero", read(bob.client(server), zero))
        assertEquals("the one-time prekey must outlive a session that was never stored", 5, bob.store.oneTimeKeyCount())
        assertNull(bob.store.loadPeer("alice"))

        bob.vault.failSessionWrites = false
        val b = bob.client(server)
        assertEquals("zero", read(b, zero))
        assertEquals("the handshake still works: nothing was spent", "one", read(b, one))
        assertEquals(4, bob.store.oneTimeKeyCount())
        assertNotNull(bob.store.loadPeer("alice")?.sendable())
        assertEquals("back", read(alice.client(server), send(b, "bob", "alice", "back")))
    }

    @Test
    fun archiveFailureNeverSpendsAMessageKey() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val zero = send(a, "alice", "bob", "zero")

        // Bob's archive cannot be written: its path is occupied by a file.
        val blocked = folder.root.resolve("blocked").also { it.writeText("not a directory") }
        val broken = bob.client(server, archiveAt = blocked)
        assertEquals("the content is shown…", "zero", read(broken, zero))
        assertNull("…but ratchet state that would spend its key is not stored", bob.store.loadPeer("alice"))
        assertEquals(5, bob.store.oneTimeKeyCount())
        assertEquals("zero", read(broken, zero))

        // Storage works again: the very same message opens and is kept for good.
        val b = bob.client(server)
        assertEquals("zero", read(b, zero))
        assertEquals("zero", read(bob.client(server), zero))
        assertEquals(4, bob.store.oneTimeKeyCount())

        // The same on an established session, and for the sender: a seal that cannot be archived is refused.
        val next = send(a, "alice", "bob", "next")
        val writes = bob.vault.sessionWrites
        assertEquals("next", read(broken, next))
        assertEquals(writes, bob.vault.sessionWrites)
        assertEquals("next", read(bob.client(server), next))
        try {
            runBlocking { broken.seal("alice", "c-blocked", E2eePayload.text("cannot be archived")) }
            fail("a message whose content cannot be kept must not be sealed")
        } catch (expected: IOException) {
            // intended
        }
        assertEquals("after", read(a, send(bob.client(server), "bob", "alice", "after")))
    }

    // ----------------------------------------------------- delivery disorder

    @Test
    fun anyMessageOfTheFirstFlightEstablishesTheSession() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val flight = (0 until 4).map { send(a, "alice", "bob", "m$it") }
        assertEquals("one bundle for the whole flight", 1, server.fetches.get())

        val b = bob.client(server)
        for (index in listOf(2, 0, 3, 1)) assertEquals("m$index", read(b, flight[index]))
        assertEquals("one handshake, one one-time prekey", 4, bob.store.oneTimeKeyCount())

        // The first message never arrives at all: the next one is enough.
        val carol = server.install("carol")
        val lost = send(a, "alice", "carol", "lost on the way")
        val arrives = send(a, "alice", "carol", "arrives")
        val c = carol.client(server)
        assertEquals("arrives", read(c, arrives))
        assertEquals("answer", read(a, send(c, "carol", "alice", "answer")))
        assertEquals("lost on the way", read(c, lost))
    }

    @Test
    fun bothSidesStartingAtOnceStillConverge() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        val fromAlice = send(a, "alice", "bob", "a0")
        val fromBob = send(b, "bob", "alice", "b0")
        assertEquals(2, server.fetches.get())
        assertEquals("b0", read(a, fromBob))
        assertEquals("a0", read(b, fromAlice))

        // Messages keep crossing: each side seals before it has seen the other's latest.
        for (round in 1..6) {
            val x = send(a, "alice", "bob", "a$round")
            val y = send(b, "bob", "alice", "b$round")
            assertEquals("b$round", read(a, y))
            assertEquals("a$round", read(b, x))
        }
        for (round in 7..10) {
            assertEquals("a$round", read(b, send(a, "alice", "bob", "a$round")))
            assertEquals("b$round", read(a, send(b, "bob", "alice", "b$round")))
        }
        assertEquals("no further bundles were needed", 2, server.fetches.get())
        assertEquals("a0", read(bob.client(server), fromAlice))
    }

    // -------------------------------------------------------------- reinstall

    @Test
    fun reinstalledPeerWritingFirstMovesTheConversationToTheNewKeys() {
        val server = Server()
        val alice = server.install("alice")
        val oldBob = server.install("bob")
        val a = alice.client(server)
        val oldB = oldBob.client(server)
        val first = send(a, "alice", "bob", "first")
        assertEquals("first", read(oldB, first))
        val seen = send(oldB, "bob", "alice", "seen")
        assertEquals("seen", read(a, seen))
        val late = send(oldB, "bob", "alice", "sent just before the reinstall")

        val newBob = server.install("bob", installation = "2")
        val newB = newBob.client(server)
        assertEquals("new phone", read(a, send(newB, "bob", "alice", "new phone")))
        assertEquals("the old session still opens what was already on its way", "sent just before the reinstall", read(a, late))

        val welcome = runBlocking { a.seal("bob", "c-welcome", E2eePayload.text("welcome back")) }!!
        assertEquals("alice now seals for the new installation", newBob.identityKey, welcome.recipientIdentity)
        assertEquals("welcome back", newB.reveal("alice", "s-welcome", null, mine = false, envelope = welcome.envelope)?.body)

        // Nothing alice had is lost; nothing the old phone had is readable on the new one.
        assertEquals("first", readOwn(a, first))
        assertEquals("seen", read(a, seen))
        assertNull(read(newB, first))
        assertNull(readOwn(newB, seen))
    }

    @Test
    fun writingToAReinstalledPeerRecoversOnceTheServerRefusesTheOldKeys() {
        val server = Server()
        val alice = server.install("alice")
        val oldBob = server.install("bob")
        val a = alice.client(server)
        val first = send(a, "alice", "bob", "first")
        assertEquals("first", read(oldBob.client(server), first))
        val newBob = server.install("bob", installation = "2")

        // Alice does not know yet. What she seals names the keys it is for — the server compares and refuses.
        val stale = runBlocking { a.seal("bob", "c-retry", E2eePayload.text("are you there?")) }!!
        assertEquals(oldBob.identityKey, stale.recipientIdentity)
        assertNotEquals(newBob.identityKey, stale.recipientIdentity)
        assertNull(newBob.client(server).reveal("alice", "s-stale", null, mine = false, envelope = stale.envelope))

        a.retireSessions("bob")
        val fresh = runBlocking { a.seal("bob", "c-retry", E2eePayload.text("are you there?")) }!!
        assertEquals(newBob.identityKey, fresh.recipientIdentity)
        val newB = newBob.client(server)
        assertEquals("are you there?", newB.reveal("alice", "s-fresh", null, mine = false, envelope = fresh.envelope)?.body)
        assertEquals("yes", read(a, send(newB, "bob", "alice", "yes")))

        // Her own message is found under the envelope the server actually stored, and history is intact.
        assertEquals("are you there?", a.reveal("bob", "s-fresh", "c-retry", mine = true, envelope = fresh.envelope)?.body)
        assertEquals("first", readOwn(alice.client(server), first))
        a.retireSessions("nobody-we-know")
    }

    // --------------------------------------------------------------- batching

    @Test
    fun aBatchSharesOneWritePerPeerAndStillOpensEverything() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        assertEquals("hi", read(b, send(a, "alice", "bob", "hi")))
        assertEquals("yo", read(a, send(b, "bob", "alice", "yo")))

        val burst = (0 until 20).map { send(a, "alice", "bob", "burst $it") }
        val before = bob.vault.sessionWrites
        val texts = b.batch { burst.map { read(b, it) } }
        assertEquals((0 until 20).map { "burst $it" }, texts)
        assertEquals("twenty messages, one stored state", before + 1, bob.vault.sessionWrites)

        // Outside a batch every message is its own unit of work.
        val more = (0 until 5).map { send(a, "alice", "bob", "more $it") }
        val middle = bob.vault.sessionWrites
        more.forEach { assertNotNull(read(b, it)) }
        assertEquals(middle + 5, bob.vault.sessionWrites)

        // Nested batches commit once, when the outermost one ends.
        val nested = (0 until 4).map { send(a, "alice", "bob", "nested $it") }
        val start = bob.vault.sessionWrites
        b.batch {
            b.batch {
                assertEquals("nested 0", read(b, nested[0]))
                assertEquals("nested 1", read(b, nested[1]))
            }
            assertEquals(start, bob.vault.sessionWrites)
            assertEquals("nested 2", read(b, nested[2]))
            assertEquals("nested 3", read(b, nested[3]))
        }
        assertEquals(start + 1, bob.vault.sessionWrites)

        // A batch that fails half-way still keeps what it opened.
        val last = (0 until 2).map { send(a, "alice", "bob", "last $it") }
        try {
            b.batch {
                assertEquals("last 0", read(b, last[0]))
                throw IllegalArgumentException("the caller's own failure")
            }
        } catch (expected: IllegalArgumentException) {
            // intended
        }
        val restarted = bob.client(server)
        assertEquals("last 0", read(restarted, last[0]))
        assertEquals("last 1", read(restarted, last[1]))
        (burst + more + nested).forEachIndexed { index, wire -> assertNotNull("message $index", read(restarted, wire)) }
    }

    @Test
    fun unreadableMessagesAreNotRetriedUntilSomethingChanges() {
        val server = Server()
        val alice = server.install("alice")
        server.install("bob")
        val a = alice.client(server)
        val toOldPhone = send(a, "alice", "bob", "for the old phone")

        // Bob reinstalled: this handshake targets keys he no longer has.
        val newBob = server.install("bob", installation = "2")
        val b = newBob.client(server)
        val poolReads = newBob.vault.poolReads
        assertNull(read(b, toOldPhone))
        val afterFirstTry = newBob.identityLookups.get()
        assertTrue("the first attempt really tried", afterFirstTry > 0)
        assertEquals("a handshake for a prekey we do not hold is dismissed without reading the pool", poolReads, newBob.vault.poolReads)
        repeat(5) { assertNull(read(b, toOldPhone)) }
        assertEquals("further passes must not repeat the key work", afterFirstTry, newBob.identityLookups.get())

        // Something changes for this peer — a message from alice opens — so one more try is fair.
        a.retireSessions("bob")
        assertEquals("fresh", read(b, send(a, "alice", "bob", "fresh")))
        val afterProgress = newBob.identityLookups.get()
        assertNull(read(b, toOldPhone))
        assertTrue(newBob.identityLookups.get() > afterProgress)
        val settled = newBob.identityLookups.get()
        repeat(3) { assertNull(read(b, toOldPhone)) }
        assertEquals(settled, newBob.identityLookups.get())
    }

    // ------------------------------------------------------------ concurrency

    @Test
    fun messagesSealedAndOpenedFromManyThreadsAllSurvive() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        assertEquals("hi", read(b, send(a, "alice", "bob", "hi")))
        assertEquals("yo", read(a, send(b, "bob", "alice", "yo")))

        val pool = Executors.newFixedThreadPool(6)
        try {
            val total = 60
            val sent = pool.invokeAll((0 until total).map { n -> Callable { send(a, "alice", "bob", "t$n") to "t$n" } })
                .map { it.get() }
            // No two messages may ever share a message key: every counter on the chain is distinct.
            val counters = sent.map { Envelope.fromJson(it.first.envelope)!!.n }
            assertEquals(total, counters.toSet().size)

            val opened = pool.invokeAll(sent.shuffled().map { (wire, text) -> Callable { text to read(b, wire) } })
                .map { it.get() }
            for ((expected, actual) in opened) assertEquals(expected, actual)

            // Sealing and opening at the same time, in both directions.
            val mixed = pool.invokeAll(
                (0 until 20).map { n ->
                    Callable {
                        val out = send(a, "alice", "bob", "x$n")
                        val back = send(b, "bob", "alice", "y$n")
                        Triple(out, back, n)
                    }
                },
            ).map { it.get() }
            val results = pool.invokeAll(
                mixed.map { (out, back, n) -> Callable { Triple(n, read(b, out), read(a, back)) } },
            ).map { it.get() }
            for ((n, atBob, atAlice) in results) {
                assertEquals("x$n", atBob)
                assertEquals("y$n", atAlice)
            }

            val restarted = bob.client(server)
            for ((wire, text) in sent) assertEquals(text, read(restarted, wire))
        } finally {
            pool.shutdownNow()
        }
    }
}
