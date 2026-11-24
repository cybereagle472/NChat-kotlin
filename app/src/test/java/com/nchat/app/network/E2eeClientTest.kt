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
        fun client(server: Server, archiveAt: File = archiveDirectory, prepare: suspend () -> Unit = {}) = E2eeClient(
            localId = id,
            store = store,
            archive = E2eeArchive(archiveAt, archiveKey),
            identity = {
                identityLookups.incrementAndGet()
                identity
            },
            signedPrekey = { prekey },
            fetchBundle = { server.bundle(it) },
            prepare = prepare,
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
        assertEquals("hello bob", b.reveal("alice", "s-first", "c-first", mine = false, envelope = sealed.envelope)?.body)

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
    fun aDeviceWithoutRegisteredKeysSendsPlaintextOnlyWhereNothingWasEverEncrypted() {
        // Until this device's own keys are registered nobody can seal for it,
        // so it does not seal either — the app keeps working exactly as it
        // did before encryption existed. A chat that HAS been encrypted is
        // different: it never goes back.
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        server.install("carol")
        send(alice.client(server), "alice", "bob", "sealed")

        alice.identity = null
        val a = alice.client(server)
        val fetches = server.fetches.get()
        assertNull(runBlocking { a.seal("carol", "c-plain", E2eePayload.text("hi carol")) })
        assertEquals("a bundle this device cannot use must not cost the peer a one-time prekey", fetches, server.fetches.get())
        assertNull(alice.store.loadPeer("carol"))

        // An established session needs no identity key: it keeps sealing.
        assertEquals("still sealed", read(bob.client(server), send(a, "alice", "bob", "still sealed")))
        // Without a usable session, and without keys to start one: an error, never plaintext.
        a.retireSessions("bob")
        sealFails(a, "bob", "a chat that was encrypted must not fall back to plaintext")
        assertEquals(fetches, server.fetches.get())
    }

    @Test
    fun registrationGetsItsChanceBeforeTheFirstSeal() {
        // Right after sign-in the keys may still be on their way to the server.
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val keys = alice.identity
        alice.identity = null
        var prepared = 0
        val a = alice.client(server, prepare = {
            prepared++
            alice.identity = keys
        })
        val first = send(a, "alice", "bob", "sealed from the first message")
        assertEquals(1, prepared)
        assertEquals("sealed from the first message", read(bob.client(server), first))
        // Sealing on an existing session is local work: no waiting, no registration check.
        send(a, "alice", "bob", "again")
        assertEquals(1, prepared)
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
        val opened = b.reveal("alice", "s-1", "c-1", mine = false, envelope = sealed.envelope)
        assertEquals(42, opened?.type)
        assertEquals("{\"future\":true}", opened?.body)
        assertEquals(42, a.reveal("bob", "s-1", "c-1", mine = true, envelope = sealed.envelope)?.type)

        // A shared position travels as a sealed payload of its own type.
        val position = runBlocking { a.seal("bob", "c-2", E2eePayload(E2eePayload.LOCATION, "24.860700,67.001100")) }!!
        val seen = b.reveal("alice", "s-2", "c-2", mine = false, envelope = position.envelope)
        assertEquals(E2eePayload.LOCATION, seen?.type)
        assertEquals("24.860700,67.001100", seen?.body)
        assertFalse(position.envelope.contains("24.86"))
        assertNotEquals(E2eePayload.TEXT, E2eePayload.LOCATION)
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
        assertNull(b.reveal("alice", "s-replay-1", second.clientId, mine = false, envelope = second.envelope))
        assertNull(b.reveal("alice", "s-replay-2", first.clientId, mine = false, envelope = first.envelope))
        assertNull(bob.client(server).reveal("alice", "s-replay-3", first.clientId, mine = false, envelope = first.envelope))
        // …and as messages from somebody else.
        server.install("carol")
        assertNull(b.reveal("carol", first.id, first.clientId, mine = false, envelope = first.envelope))
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
        assertNull(bob.client(server).reveal("alice", "s-replay-1", first.clientId, mine = false, envelope = first.envelope))

        // Even when the session it created is long gone, the handshake is remembered.
        val record = bob.store.loadPeer("alice")!!
        record.sessions.clear()
        bob.store.savePeer("alice", record, durable = true)
        assertNull(bob.client(server).reveal("alice", "s-replay-2", first.clientId, mine = false, envelope = first.envelope))
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
            assertNull("hostile envelope #$index must not open", b.reveal("alice", "s-h$index", genuine.clientId, mine = false, envelope = envelope))
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

    // ------------------------------------------- bound to its own message

    @Test
    fun envelopeMovedToAnotherMessageDoesNotOpen() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        val one = send(a, "alice", "bob", "one")
        val two = send(a, "alice", "bob", "two")
        assertEquals("one", read(b, one))

        // The server serves message two's envelope in a row it made up, in a
        // row without a client id, and as an "edit" of message one.
        val writes = bob.vault.sessionWrites
        assertNull(b.reveal("alice", "s-made-up", "c-made-up", mine = false, envelope = two.envelope))
        assertNull(b.reveal("alice", "s-no-client-id", null, mine = false, envelope = two.envelope))
        assertNull(b.reveal("alice", one.id, one.clientId, mine = false, envelope = two.envelope))
        assertEquals("a mis-slotted envelope must not spend its key", writes, bob.vault.sessionWrites)

        assertEquals("two", read(b, two))
        assertEquals("one", read(b, one))
    }

    @Test
    fun anEditOpensOnlyAsTheContentOfTheMessageItReplaces() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        val original = send(a, "alice", "bob", "see you at nien")
        assertEquals("see you at nien", read(b, original))

        // The row keeps its ids; only its envelope is replaced.
        val edit = runBlocking { a.seal("bob", original.clientId, E2eePayload.text("see you at nine"), editOf = original.id) }!!
        assertNotEquals(original.envelope, edit.envelope)
        // Served as a message of its own — with or without the original's client id — it is nothing.
        assertNull(b.reveal("alice", "s-another", original.clientId, mine = false, envelope = edit.envelope))
        assertNull(b.reveal("alice", "s-another", null, mine = false, envelope = edit.envelope))
        assertEquals("see you at nine", b.reveal("alice", original.id, original.clientId, mine = false, envelope = edit.envelope)?.body)
        assertEquals("see you at nine", a.reveal("bob", original.id, original.clientId, mine = true, envelope = edit.envelope)?.body)

        // A new message cannot pass for an edit, even when its client id is chosen to look like a server id.
        val lookalike = runBlocking { a.seal("bob", original.id, E2eePayload.text("not an edit")) }!!
        assertNull(b.reveal("alice", original.id, original.clientId, mine = false, envelope = lookalike.envelope))

        // Edits go through the same send step as new messages.
        val again = runBlocking {
            a.sendSealed("bob", original.clientId, E2eePayload.text("see you at nine sharp"), { false }, editOf = original.id) { it }
        }!!
        assertEquals("see you at nine sharp", b.reveal("alice", original.id, original.clientId, mine = false, envelope = again.envelope)?.body)

        // Every version stays readable after a restart, each under its own envelope.
        val restartedB = bob.client(server)
        val restartedA = alice.client(server)
        assertEquals("see you at nine", restartedB.reveal("alice", original.id, original.clientId, mine = false, envelope = edit.envelope)?.body)
        assertEquals("see you at nien", read(restartedB, original))
        assertEquals("see you at nine", restartedA.reveal("bob", original.id, original.clientId, mine = true, envelope = edit.envelope)?.body)
        assertEquals("see you at nien", readOwn(restartedA, original))
    }

    @Test
    fun whatAMessageRepliesToTravelsInsideTheEnvelope() {
        // The server stores no reply link for a sealed message: it can neither read nor rewrite it.
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        val question = send(a, "alice", "bob", "nine or ten?")
        assertEquals("nine or ten?", read(b, question))

        val sealed = runBlocking { b.seal("alice", "c-answer", E2eePayload.text("nine", replyTo = question.id)) }!!
        assertFalse("the id of the quoted message must not be readable on the wire", sealed.envelope.contains(question.id))
        val answer = Wire("s-answer", "c-answer", "bob", "alice", sealed.envelope)
        for (reader in listOf(a, alice.client(server))) {
            val shown = reader.reveal("bob", answer.id, answer.clientId, mine = false, envelope = answer.envelope)!!
            assertEquals("nine", shown.body)
            assertEquals(question.id, shown.replyTo)
        }
        for (author in listOf(b, bob.client(server))) {
            assertEquals(question.id, author.reveal("alice", answer.id, answer.clientId, mine = true, envelope = answer.envelope)?.replyTo)
        }
        assertNull(a.reveal("bob", question.id, question.clientId, mine = true, envelope = question.envelope)?.replyTo)
    }

    // ------------------------------------------------------- seal and send

    private class KeysChanged : Exception("the recipient has registered different keys")

    @Test
    fun sendSealedDoesNotSendWhenThePeerHasNoKeys() {
        val server = Server()
        val a = server.install("alice").client(server)
        var calls = 0
        val result = runBlocking {
            a.sendSealed("old-client", "c-1", E2eePayload.text("hi"), { it is KeysChanged }) {
                calls++
                "stored"
            }
        }
        assertNull("null tells the caller to send plaintext", result)
        assertEquals(0, calls)
    }

    @Test
    fun sendSealedStartsANewSessionWhenThePeersKeysHaveChanged() {
        val server = Server()
        val alice = server.install("alice")
        val oldBob = server.install("bob")
        val a = alice.client(server)
        assertEquals("first", read(oldBob.client(server), send(a, "alice", "bob", "first")))
        val newBob = server.install("bob", installation = "2")

        val attempts = ArrayList<E2eeClient.Sealed>()
        val result = runBlocking {
            a.sendSealed("bob", "c-after", E2eePayload.text("after the reinstall"), { it is KeysChanged }) { sealed ->
                attempts += sealed
                // What the server does: refuse an envelope sealed for keys the recipient no longer has.
                if (sealed.recipientIdentity != newBob.identityKey) throw KeysChanged()
                "stored"
            }
        }
        assertEquals("stored", result)
        assertEquals(2, attempts.size)
        assertEquals(oldBob.identityKey, attempts[0].recipientIdentity)
        assertEquals(newBob.identityKey, attempts[1].recipientIdentity)
        val stored = attempts[1].envelope
        assertEquals("after the reinstall", newBob.client(server).reveal("alice", "s-after", "c-after", mine = false, envelope = stored)?.body)
        assertEquals("after the reinstall", a.reveal("bob", "s-after", "c-after", mine = true, envelope = stored)?.body)

        // From here on it is an ordinary session: one attempt per message.
        attempts.clear()
        runBlocking { a.sendSealed("bob", "c-next", E2eePayload.text("next"), { it is KeysChanged }) { attempts += it } }
        assertEquals(1, attempts.size)
    }

    @Test
    fun sendSealedTriesAgainOnceAndOnlyForChangedKeys() {
        val server = Server()
        val alice = server.install("alice")
        server.install("bob")
        val a = alice.client(server)
        send(a, "alice", "bob", "first")

        // A server that keeps refusing: two attempts, then the failure is the caller's.
        var attempts = 0
        try {
            runBlocking {
                a.sendSealed("bob", "c-1", E2eePayload.text("x"), { it is KeysChanged }) {
                    attempts++
                    throw KeysChanged()
                }
            }
            fail("a second refusal must reach the caller")
        } catch (expected: KeysChanged) {
            // intended
        }
        assertEquals(2, attempts)

        // Any other failure is not a reason to throw sessions away or to try again.
        val fetches = server.fetches.get()
        attempts = 0
        try {
            runBlocking {
                a.sendSealed("bob", "c-2", E2eePayload.text("x"), { it is KeysChanged }) {
                    attempts++
                    throw IOException("no connection")
                }
            }
            fail("a transport failure must reach the caller")
        } catch (expected: IOException) {
            // intended
        }
        assertEquals(1, attempts)
        send(a, "alice", "bob", "the session is still in use")
        assertEquals("no new session was started", fetches, server.fetches.get())
    }

    // -------------------------------------------------------------- closing

    // ------------------------------------------- history as the app meets it

    @Test
    fun aWeekOfUnreadMessagesOpensFromTheNewestPageBackwards() {
        // The chat list shows the newest message; opening the chat loads the newest
        // page; scrolling back loads older ones. The app may restart at any point.
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val sent = (0 until 650).map { send(a, "alice", "bob", "message $it") }

        var b = bob.client(server)
        assertEquals("message 649", read(b, sent[649]))
        for (page in 6 downTo 0) {
            if (page % 2 == 0) b = bob.client(server)
            val rows = sent.subList(page * 100, minOf(page * 100 + 100, 650))
            val shown = b.batch { rows.map { read(b, it) } }
            assertEquals(rows.indices.map { "message ${page * 100 + it}" }, shown)
        }
        // From now on everything is answered from the phone's own copy, in any order, without the ratchet.
        val later = bob.client(server)
        val writes = bob.vault.sessionWrites
        for (index in listOf(649, 0, 333, 100, 99)) assertEquals("message $index", read(later, sent[index]))
        assertEquals(writes, bob.vault.sessionWrites)
        // And the conversation continues in both directions.
        val reply = send(later, "bob", "alice", "read them all")
        assertEquals("read them all", read(a, reply))
        assertEquals("good", read(later, send(a, "alice", "bob", "good")))
    }

    @Test
    fun theLongestTextStillFitsOnceItIsSealed() {
        // The server takes 65,536 bytes of text and 131,072 bytes of envelope
        // (worker/src/lib/input.ts): whatever may be sent in the clear must fit sealed.
        val server = Server()
        val longest = listOf("x".repeat(65_536), "😀".repeat(16_384), "\u0000\"\\".repeat(21_845) + "x")
        for ((index, text) in longest.withIndex()) {
            assertEquals(65_536, text.toByteArray(Charsets.UTF_8).size)
            val alice = server.install("alice-$index")
            val bob = server.install("bob-$index")
            // The first message of a conversation carries the handshake: the largest envelope there is.
            val sealed = runBlocking {
                alice.client(server).seal("bob-$index", "c".repeat(64), E2eePayload.text(text, replyTo = "m".repeat(64)))
            }!!
            val size = sealed.envelope.toByteArray(Charsets.UTF_8).size
            assertTrue("the envelope is $size bytes", size <= 131_072)
            val opened = bob.client(server).reveal("alice-$index", "s-1", "c".repeat(64), mine = false, envelope = sealed.envelope)
            assertEquals(text, opened?.body)
        }
    }

    // --------------------------------------------------------- sealed once

    private fun number(envelope: String): Int = Regex("\"n\":(\\d+)").find(envelope)!!.groupValues[1].toInt()

    /** One attempt to send: [arrives] says whether the request reaches the server. */
    private fun attempt(client: E2eeClient, to: String, clientId: String, text: String, arrives: Boolean, editOf: String? = null): String {
        var sent = ""
        try {
            runBlocking {
                client.sendSealed(to, clientId, E2eePayload.text(text), { false }, editOf) { sealed ->
                    sent = sealed.envelope
                    if (!arrives) throw IOException("connection lost")
                    "stored"
                }
            }
        } catch (expected: IOException) {
            assertFalse(arrives)
        }
        return sent
    }

    @Test
    fun aRetrySendsTheSameEnvelopeInsteadOfSealingAgain() {
        // Every sealing uses up a message number, and a peer refuses a message
        // that is too many numbers ahead: retries must not eat numbers.
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        val hello = send(a, "alice", "bob", "hello")
        assertEquals("hello", read(b, hello))

        val first = attempt(a, "bob", "c-flaky", "are you there?", arrives = false)
        repeat(5) { assertEquals(first, attempt(a, "bob", "c-flaky", "are you there?", arrives = false)) }
        // The app is killed and started again: still the same envelope.
        val restarted = alice.client(server)
        assertEquals(first, attempt(restarted, "bob", "c-flaky", "are you there?", arrives = false))
        assertEquals(first, attempt(restarted, "bob", "c-flaky", "are you there?", arrives = true))
        assertEquals("are you there?", b.reveal("alice", "s-flaky", "c-flaky", mine = false, envelope = first)?.body)
        assertEquals("are you there?", restarted.reveal("bob", "s-flaky", "c-flaky", mine = true, envelope = first)?.body)

        // Eight attempts cost one number: the next message is the very next one.
        val next = attempt(restarted, "bob", "c-next", "good", arrives = true)
        assertEquals(number(first) + 1, number(next))
        assertEquals("good", b.reveal("alice", "s-next", "c-next", mine = false, envelope = next)?.body)
    }

    @Test
    fun aKeptEnvelopeIsOnlyUsedForTheSameContentOfTheSameMessage() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        val original = send(a, "alice", "bob", "see you at nien")
        assertEquals("see you at nien", read(b, original))

        // An edit that did not get through, then a different correction of the same message.
        val lost = attempt(a, "bob", original.clientId, "see you at nine", arrives = false, editOf = original.id)
        val second = attempt(a, "bob", original.clientId, "see you at 9", arrives = true, editOf = original.id)
        assertNotEquals(lost, second)
        assertEquals("see you at 9", b.reveal("alice", original.id, original.clientId, mine = false, envelope = second)?.body)

        // Another message with the same words is another message.
        val one = attempt(a, "bob", "c-one", "ok", arrives = false)
        val two = attempt(a, "bob", "c-two", "ok", arrives = false)
        assertNotEquals(one, two)
        // …and a reply is not the same content as the bare text.
        val bare = runBlocking { a.seal("bob", "c-three", E2eePayload.text("ok")) }!!.envelope
        val reply = runBlocking { a.seal("bob", "c-three", E2eePayload.text("ok", replyTo = original.id)) }!!.envelope
        assertNotEquals(bare, reply)

        // Once the server has an envelope it is not kept: nothing would ever ask for it again.
        val delivered = attempt(a, "bob", "c-four", "done", arrives = true)
        assertNotEquals(delivered, attempt(a, "bob", "c-four", "done", arrives = true))
    }

    @Test
    fun aKeptEnvelopeIsNotSentToKeysThePeerNoLongerHas() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val hello = send(a, "alice", "bob", "hello")
        assertEquals("hello", read(bob.client(server), hello))
        val waiting = attempt(a, "bob", "c-waiting", "still there?", arrives = false)

        // Bob reinstalls; the server refuses what was sealed for his old keys.
        val reinstalled = server.install("bob", installation = "2")
        val current = reinstalled.identityKey
        val offered = ArrayList<E2eeClient.Sealed>()
        runBlocking {
            a.sendSealed("bob", "c-waiting", E2eePayload.text("still there?"), { it is KeysChanged }) { sealed ->
                offered += sealed
                if (sealed.recipientIdentity != current) throw KeysChanged()
                "stored"
            }
        }
        assertEquals(listOf(waiting, offered[1].envelope), offered.map { it.envelope })
        assertNotEquals(waiting, offered[1].envelope)
        assertEquals(current, offered[1].recipientIdentity)
        assertEquals(
            "still there?",
            reinstalled.client(server).reveal("alice", "s-waiting", "c-waiting", mine = false, envelope = offered[1].envelope)?.body,
        )

        // The other way round: the peer's new installation writes first. What was
        // waiting for the old keys is sealed again for the new ones, unprompted.
        val carol = server.install("carol")
        val c = carol.client(server)
        val dave = server.install("dave")
        val toDave = send(c, "carol", "dave", "hi dave")
        assertEquals("hi dave", read(dave.client(server), toDave))
        val stale = attempt(c, "dave", "c-stale", "lunch?", arrives = false)
        val newDave = server.install("dave", installation = "2")
        val fromNewDave = send(newDave.client(server), "dave", "carol", "new phone")
        assertEquals("new phone", read(c, fromNewDave))
        val fresh = attempt(c, "dave", "c-stale", "lunch?", arrives = true)
        assertNotEquals(stale, fresh)
        assertEquals("lunch?", newDave.client(server).reveal("carol", "s-stale", "c-stale", mine = false, envelope = fresh)?.body)
    }

    @Test
    fun keptEnvelopesAreBounded() {
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        assertEquals("hello", read(bob.client(server), send(a, "alice", "bob", "hello")))

        val envelopes = (1..20).map { attempt(a, "bob", "c-queued-$it", "message $it", arrives = false) }
        // The newest are still there after a restart …
        val restarted = alice.client(server)
        for (index in 5..20) {
            assertEquals(envelopes[index - 1], attempt(restarted, "bob", "c-queued-$index", "message $index", arrives = false))
        }
        // … the oldest were let go, and are simply sealed again.
        assertNotEquals(envelopes[0], attempt(restarted, "bob", "c-queued-1", "message 1", arrives = false))

        // Size counts too: one very long message does not push the record past its limit.
        val long = "x".repeat(60_000)
        repeat(6) { attempt(restarted, "bob", "c-long-$it", long, arrives = false) }
        val stored = alice.vault.entries.filterKeys { it.startsWith("e2ee.session.") }.values.sumOf { it.length }
        assertTrue("the session record grew to $stored characters", stored < 400_000)
    }

    @Test
    fun aKeptEnvelopeStillComesWithTheSendersOwnCopy() {
        // The archive is the only place the sender can read its own message from.
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        assertEquals("hello", read(bob.client(server), send(a, "alice", "bob", "hello")))
        val first = attempt(a, "bob", "c-flaky", "kept", arrives = false)

        // The archive is lost between the attempts (a restore, a cleaner app).
        val elsewhere = alice.client(server, archiveAt = folder.newFolder())
        assertEquals(first, attempt(elsewhere, "bob", "c-flaky", "kept", arrives = true))
        assertEquals("kept", elsewhere.reveal("bob", "s-flaky", "c-flaky", mine = true, envelope = first)?.body)
    }

    @Test
    fun aClosedClientReadsNothingAndWritesNothing() {
        // Signing out wipes the account's keys and history; a client that is
        // still referenced somewhere must not write any of it back.
        val server = Server()
        val alice = server.install("alice")
        val bob = server.install("bob")
        val a = alice.client(server)
        val b = bob.client(server)
        val opened = send(a, "alice", "bob", "opened")
        assertEquals("opened", read(b, opened))
        val unopened = send(a, "alice", "bob", "unopened")

        b.close()
        val entries = LinkedHashMap(bob.vault.entries)
        val files = bob.archiveDirectory.listFiles().orEmpty().associate { it.name to it.length() }
        assertNull(read(b, opened))
        assertNull(read(b, unopened))
        try {
            runBlocking { b.seal("alice", "c-closed", E2eePayload.text("x")) }
            fail("a closed client must not seal")
        } catch (expected: IllegalStateException) {
            // intended
        }
        b.retireSessions("alice")
        assertEquals("x", b.batch { "x" })
        assertEquals(entries, bob.vault.entries)
        assertEquals(files, bob.archiveDirectory.listFiles().orEmpty().associate { it.name to it.length() })

        // Closing is about the object, not the data: a new client over the same storage carries on.
        val reopened = bob.client(server)
        assertEquals("opened", read(reopened, opened))
        assertEquals("unopened", read(reopened, unopened))
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
        assertEquals("welcome back", newB.reveal("alice", "s-welcome", "c-welcome", mine = false, envelope = welcome.envelope)?.body)

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
        assertNull(newBob.client(server).reveal("alice", "s-stale", "c-retry", mine = false, envelope = stale.envelope))

        a.retireSessions("bob")
        val fresh = runBlocking { a.seal("bob", "c-retry", E2eePayload.text("are you there?")) }!!
        assertEquals(newBob.identityKey, fresh.recipientIdentity)
        val newB = newBob.client(server)
        assertEquals("are you there?", newB.reveal("alice", "s-fresh", "c-retry", mine = false, envelope = fresh.envelope)?.body)
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
