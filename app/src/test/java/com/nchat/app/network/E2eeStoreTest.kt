package com.nchat.app.network

import com.nchat.app.network.E2eeProtocol.PeerBundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Storage under the ratchet: the sealing primitive, the on-disk vault and the
 * session / one-time-prekey records built on them.
 */
class E2eeStoreTest {
    @get:Rule val folder = TemporaryFolder()

    /** In-memory vault that remembers how each entry was last written. */
    private class MemoryVault : SecretVault {
        val entries = LinkedHashMap<String, String>()
        val durable = LinkedHashMap<String, Boolean>()
        var writes = 0

        override fun read(key: String): String? = entries[key]
        override fun contains(key: String): Boolean = entries.containsKey(key)
        override fun write(key: String, value: String?, durable: Boolean) {
            writes++
            if (value == null) entries.remove(key) else entries[key] = value
            this.durable[key] = durable
        }
    }

    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val otherKey = ByteArray(32) { (it * 3 + 5).toByte() }

    private fun newSession(): E2eeProtocol.Session {
        val me = E2eeProtocol.generateIdentity()
        val peer = E2eeProtocol.generateIdentity()
        val prekey = E2eeProtocol.generatePrekeyPair()
        val bundle = PeerBundle(
            identityPublic = peer.identityPublic,
            signedPrekeyPublic = prekey.public,
            signedPrekeySignature = E2eeProtocol.signPrekey(prekey.public, peer.identityPrivate),
            oneTimePrekeyPublic = null,
        )
        return E2eeProtocol.initiate(me, bundle, "me", "peer")
    }

    private fun ByteArray.holds(needle: String): Boolean {
        val wanted = needle.toByteArray(Charsets.UTF_8)
        if (wanted.isEmpty() || wanted.size > size) return false
        for (start in 0..size - wanted.size) {
            var index = 0
            while (index < wanted.size && this[start + index] == wanted[index]) index++
            if (index == wanted.size) return true
        }
        return false
    }

    private fun filesOf(directory: File): List<File> = directory.listFiles().orEmpty().toList()

    // ------------------------------------------------------------ SealedBox

    @Test
    fun sealedBoxRoundTripsAndBindsKeyContextAndBytes() {
        val box = SealedBox(key)
        val secret = "ratchet state".toByteArray(Charsets.UTF_8)
        val sealed = box.seal("purpose", secret)
        assertArrayEquals(secret, box.open("purpose", sealed))
        assertArrayEquals(ByteArray(0), box.open("empty", box.seal("empty", ByteArray(0))))

        assertNull("another context must not open it", box.open("other purpose", sealed))
        assertNull("another key must not open it", SealedBox(otherKey).open("purpose", sealed))
        assertNull(box.open("purpose", sealed.copyOf(sealed.size - 1)))
        assertNull(box.open("purpose", ByteArray(0)))
        for (index in sealed.indices) {
            val damaged = sealed.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertNull("a changed byte at $index must be detected", box.open("purpose", damaged))
        }
        // A fresh nonce every time: sealing the same thing twice never repeats.
        assertFalse(sealed.contentEquals(box.seal("purpose", secret)))
        assertFalse(sealed.holds("ratchet state"))
    }

    @Test
    fun sealedBoxRefusesAKeyOfTheWrongSize() {
        for (size in listOf(0, 16, 31, 33)) {
            try {
                SealedBox(ByteArray(size))
                fail("a $size-byte key must be refused")
            } catch (expected: IllegalArgumentException) {
                // intended
            }
        }
    }

    // ------------------------------------------------------ FileSecretVault

    @Test
    fun vaultRoundTripsAcrossInstancesAndRemoves() {
        val directory = folder.root.resolve("vault")
        val vault = FileSecretVault(directory, key)
        assertNull(vault.read("a"))
        assertFalse(vault.contains("a"))

        vault.write("a", "first", durable = true)
        vault.write("b", "second ✓ ユニコード", durable = false)
        vault.write("a", "replaced", durable = true)
        assertEquals("replaced", vault.read("a"))
        assertTrue(vault.contains("a"))

        val reopened = FileSecretVault(directory, key)
        assertEquals("replaced", reopened.read("a"))
        assertEquals("second ✓ ユニコード", reopened.read("b"))

        reopened.write("a", null, durable = true)
        assertNull(reopened.read("a"))
        assertFalse(reopened.contains("a"))
        reopened.write("never existed", null, durable = true)
        assertEquals("second ✓ ユニコード", FileSecretVault(directory, key).read("b"))
    }

    @Test
    fun vaultFilesRevealNeitherNamesNorValues() {
        val directory = folder.root.resolve("vault")
        val vault = FileSecretVault(directory, key)
        vault.write("e2ee.session.user-1234", "TOP-SECRET-STATE", durable = true)
        val files = filesOf(directory)
        assertEquals(1, files.size)
        assertFalse(files[0].name.contains("user-1234"))
        assertFalse(files[0].name.contains("session"))
        val bytes = files[0].readBytes()
        assertFalse(bytes.holds("TOP-SECRET-STATE"))
        assertFalse(bytes.holds("user-1234"))
    }

    @Test
    fun vaultEntryUnderAnotherNameOrKeyDoesNotOpen() {
        val directory = folder.root.resolve("vault")
        val vault = FileSecretVault(directory, key)
        vault.write("alice", "alice's session", durable = true)
        val aliceFile = filesOf(directory).single()
        vault.write("bob", "bob's session", durable = true)
        val bobFile = filesOf(directory).single { it != aliceFile }

        // One peer's state copied over another's: present, but it must not load.
        aliceFile.copyTo(bobFile, overwrite = true)
        assertNull(vault.read("bob"))
        assertTrue("a damaged entry is still an entry", vault.contains("bob"))
        assertEquals("alice's session", vault.read("alice"))

        val stranger = FileSecretVault(directory, otherKey)
        assertNull(stranger.read("alice"))
        assertTrue(stranger.contains("alice"))
    }

    @Test
    fun vaultLeavesNoTempFilesAndClearsStaleOnes() {
        val directory = folder.root.resolve("vault")
        directory.mkdirs()
        val stale = File(directory, "entry-crashed.tmp").also { it.writeText("half a write") }
        val vault = FileSecretVault(directory, key)
        assertFalse("a temp file from an interrupted write is removed", stale.exists())
        repeat(5) { vault.write("k$it", "v$it", durable = it % 2 == 0) }
        assertTrue(filesOf(directory).none { it.name.endsWith(".tmp") })
        assertEquals(5, filesOf(directory).size)

        vault.clear()
        assertFalse(directory.exists())
        assertNull(vault.read("k0"))
        vault.write("after", "clear", durable = true)
        assertEquals("clear", vault.read("after"))
    }

    // ------------------------------------------------------------- sessions

    @Test
    fun peerRecordRoundTripsOrderRetirementAndHandshakes() {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        assertNull(store.loadPeer("bob"))

        val first = newSession()
        val second = newSession()
        val record = E2eeStore.PeerRecord()
        record.sessions += E2eeStore.StoredSession(first, retired = true)
        record.sessions += E2eeStore.StoredSession(second, retired = false)
        record.seenHandshakes += listOf("h-one", "h-two")
        store.savePeer("bob", record, durable = true)

        val loaded = store.loadPeer("bob")
        assertNotNull(loaded)
        assertEquals(2, loaded!!.sessions.size)
        assertArrayEquals(first.serialize(), loaded.sessions[0].session.serialize())
        assertTrue(loaded.sessions[0].retired)
        assertArrayEquals(second.serialize(), loaded.sessions[1].session.serialize())
        assertFalse(loaded.sessions[1].retired)
        assertEquals(listOf("h-one", "h-two"), loaded.seenHandshakes.toList())
        // The first session that is not retired is the one to send on.
        assertArrayEquals(second.serialize(), loaded.sendable()!!.session.serialize())

        assertNull(store.loadPeer("carol"))
        assertNull(store.loadPeer(""))
        assertFalse(vault.entries.values.any { it.contains("bob") })
    }

    @Test
    fun whatIsKnownAboutThePeersKeysTravelsWithTheRecord() {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        val identity = E2eeProtocol.generateIdentity().identityPublic
        val record = E2eeStore.PeerRecord()
        record.sessions += E2eeStore.StoredSession(newSession(), retired = false)
        record.trust = E2eeStore.Trust(identity, verified = true, changed = true, held = true, since = 1_700_000_000_123L)
        store.savePeer("bob", record, durable = true)

        val loaded = store.loadPeer("bob")!!.trust
        assertNotNull(loaded)
        assertArrayEquals(identity, loaded!!.identity)
        assertTrue(loaded.verified && loaded.changed && loaded.held)
        assertEquals(1_700_000_000_123L, loaded.since)

        // Each fact on its own, so that none is read from another's place.
        for (facts in listOf(Triple(true, false, false), Triple(false, true, false), Triple(false, false, true), Triple(false, false, false))) {
            record.trust = E2eeStore.Trust(identity, verified = facts.first, changed = facts.second, held = facts.third, since = 0L)
            store.savePeer("bob", record, durable = true)
            val again = store.loadPeer("bob")!!.trust!!
            assertEquals(facts, Triple(again.verified, again.changed, again.held))
            assertEquals(0L, again.since)
        }
    }

    @Test
    fun aRecordThatSaysNothingAboutThePeersKeysLoadsWithoutInventingAny() {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        store.savePeer("bob", E2eeStore.PeerRecord().apply {
            sessions += E2eeStore.StoredSession(newSession(), retired = false)
        }, durable = true)
        assertNull(store.loadPeer("bob")!!.trust)
        val entry = vault.entries.keys.single()
        val written = vault.entries[entry]!!

        // A note that is not a key is no note: better "unknown" than a key nobody ever presented.
        val identity = encodeBase64(E2eeProtocol.generateIdentity().identityPublic)
        val notes = listOf(
            "7",
            "{}",
            "{\"s\":5}",
            "{\"i\":\"AAAA\",\"s\":5}",
            "{\"i\":\"not base64!\",\"s\":5}",
            "{\"i\":\"${identity.dropLast(4)}\",\"s\":5}",
        )
        for (note in notes) {
            vault.entries[entry] = "{\"t\":$note," + written.substring(1)
            val loaded = store.loadPeer("bob")!!
            assertNull("a note of $note", loaded.trust)
            assertEquals("the sessions are still there", 1, loaded.sessions.size)
        }
        vault.entries[entry] = "{\"t\":{\"i\":\"$identity\"}," + written.substring(1)
        val sparse = store.loadPeer("bob")!!.trust!!
        assertFalse(sparse.verified || sparse.changed || sparse.held)
        assertEquals(0L, sparse.since)
    }

    @Test
    fun peerRecordIsBoundedAndKeepsTheNewest() {
        val store = E2eeStore(MemoryVault())
        val record = E2eeStore.PeerRecord()
        val sessions = List(E2eeStore.MAX_SESSIONS_PER_PEER + 2) { newSession() }
        sessions.forEach { record.sessions += E2eeStore.StoredSession(it, retired = false) }
        repeat(E2eeStore.MAX_SEEN_HANDSHAKES + 10) { record.seenHandshakes += "h$it" }
        store.savePeer("bob", record, durable = false)

        val loaded = store.loadPeer("bob")!!
        assertEquals(E2eeStore.MAX_SESSIONS_PER_PEER, loaded.sessions.size)
        // Sessions are kept from the front (the sending session first)…
        assertArrayEquals(sessions[0].serialize(), loaded.sessions[0].session.serialize())
        // …and handshakes from the back (the most recent ones).
        assertEquals(E2eeStore.MAX_SEEN_HANDSHAKES, loaded.seenHandshakes.size)
        assertEquals("h10", loaded.seenHandshakes.first())
        assertEquals("h${E2eeStore.MAX_SEEN_HANDSHAKES + 9}", loaded.seenHandshakes.last())
    }

    @Test
    fun neverStoredAndDamagedAreDifferentFacts() {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        store.savePeer("bob", E2eeStore.PeerRecord().apply {
            sessions += E2eeStore.StoredSession(newSession(), retired = false)
        }, durable = true)
        val entry = vault.entries.keys.single()

        // Unparseable, structurally wrong, or holding a session blob that no longer loads:
        // each still says "this chat was encrypted before" — a record, with nothing to send on.
        for (damage in listOf("not json", "[]", "{\"s\":7}", "{\"s\":[{\"r\":false,\"b\":\"AAAA\"}]}", "{\"s\":[5,{}]}")) {
            vault.entries[entry] = damage
            val loaded = store.loadPeer("bob")
            assertNotNull("damaged record ($damage) must not read as never-encrypted", loaded)
            assertTrue(loaded!!.sessions.isEmpty())
            assertNull(loaded.sendable())
        }

        // An entry that exists but cannot be read at all (wrong key, torn file).
        val unreadable = object : SecretVault {
            override fun read(key: String): String? = null
            override fun contains(key: String): Boolean = true
            override fun write(key: String, value: String?, durable: Boolean) = Unit
        }
        assertNotNull(E2eeStore(unreadable).loadPeer("bob"))
    }

    @Test
    fun oneDamagedSessionDoesNotCostTheOthers() {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        val good = newSession()
        store.savePeer("bob", E2eeStore.PeerRecord().apply {
            sessions += E2eeStore.StoredSession(good, retired = false)
        }, durable = true)
        val entry = vault.entries.keys.single()
        val blob = encodeBase64(good.serialize())
        vault.entries[entry] = "{\"s\":[{\"r\":false,\"b\":\"AAAA\"},{\"r\":true,\"b\":\"$blob\"}],\"h\":[\"x\",7,null]}"
        val loaded = store.loadPeer("bob")!!
        assertEquals(1, loaded.sessions.size)
        assertTrue(loaded.sessions[0].retired)
        assertArrayEquals(good.serialize(), loaded.sessions[0].session.serialize())
    }

    @Test
    fun sessionWritesHonourTheDurabilityTheCallerAskedFor() {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        val record = E2eeStore.PeerRecord().apply { sessions += E2eeStore.StoredSession(newSession(), retired = false) }
        store.savePeer("bob", record, durable = false)
        assertEquals(false, vault.durable.values.single())
        store.savePeer("bob", record, durable = true)
        assertEquals(true, vault.durable.values.single())
        try {
            store.savePeer(" ", record, durable = true)
            fail("a blank peer id must be refused")
        } catch (expected: IllegalArgumentException) {
            // intended
        }
    }

    // ----------------------------------------------------- one-time prekeys

    @Test
    fun oneTimeKeysStayUntilTheyAreRemoved() {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        assertEquals(0, store.oneTimeKeyCount())
        assertNull(store.oneTimeKey("ref-a"))

        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6, 7)
        store.addOneTimeKeys(mapOf("ref-a" to a, "ref-b" to b))
        assertEquals(2, store.oneTimeKeyCount())
        // Looking a key up does not spend it: a handshake that fails must cost nothing.
        assertArrayEquals(a, store.oneTimeKey("ref-a"))
        assertArrayEquals(a, store.oneTimeKey("ref-a"))
        assertEquals(2, store.oneTimeKeyCount())

        store.removeOneTimeKeys(listOf("ref-a"))
        assertNull(store.oneTimeKey("ref-a"))
        assertArrayEquals(b, store.oneTimeKey("ref-b"))
        assertEquals(1, store.oneTimeKeyCount())
        assertArrayEquals(b, E2eeStore(vault).oneTimeKey("ref-b"))

        // Nothing to do means nothing written.
        val writes = vault.writes
        store.removeOneTimeKeys(listOf("ref-a", "unknown"))
        store.removeOneTimeKeys(emptyList())
        store.addOneTimeKeys(emptyMap())
        assertEquals(writes, vault.writes)
        assertTrue("the pool must always be written durably", vault.durable.values.all { it })
    }

    @Test
    fun oneTimeKeyPoolIsBoundedOldestFirst() {
        val store = E2eeStore(MemoryVault())
        val extra = 50
        val total = E2eeStore.MAX_LOCAL_ONE_TIME_KEYS + extra
        store.addOneTimeKeys((0 until 100).associate { "ref-$it" to byteArrayOf(it.toByte()) })
        store.addOneTimeKeys((100 until total).associate { "ref-$it" to byteArrayOf(it.toByte()) })
        assertEquals(E2eeStore.MAX_LOCAL_ONE_TIME_KEYS, store.oneTimeKeyCount())
        assertNull(store.oneTimeKey("ref-0"))
        assertNull(store.oneTimeKey("ref-${extra - 1}"))
        assertNotNull(store.oneTimeKey("ref-$extra"))
        assertNotNull(store.oneTimeKey("ref-${total - 1}"))
    }

    @Test
    fun damagedPoolReadsAsEmptyAndRecovers() {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        store.addOneTimeKeys(mapOf("ref-a" to byteArrayOf(1)))
        val entry = vault.entries.keys.single()
        for (damage in listOf("not json", "[]", "7")) {
            vault.entries[entry] = damage
            assertEquals(0, store.oneTimeKeyCount())
            assertNull(store.oneTimeKey("ref-a"))
        }
        store.addOneTimeKeys(mapOf("ref-b" to byteArrayOf(2)))
        assertEquals(1, store.oneTimeKeyCount())
        assertArrayEquals(byteArrayOf(2), store.oneTimeKey("ref-b"))
    }
}
