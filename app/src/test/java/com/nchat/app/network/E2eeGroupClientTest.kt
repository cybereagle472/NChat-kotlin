package com.nchat.app.network

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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Group messages as the app sends and reads them: who is handed a member's
 * chain and when, what happens when the members of a group change, and that
 * nothing goes out readable in a group that is encrypted.
 *
 * The server here is a stand-in that follows the rules of the real one
 * (worker/test/e2ee-groups.test.ts): it keeps the envelopes that hand out
 * chains, accepts a sealed message only when every other member with keys
 * holds its chain, and refuses one whose chain is held by somebody who left
 * or has other keys now. A member who could not open the envelope they were
 * handed says so, and it tells the sender to hand the chain to them again.
 */
class E2eeGroupClientTest {
    @get:Rule val folder = TemporaryFolder()

    private val group = "g-family"

    private class RosterChanged : RuntimeException("roster_changed")

    private class MemoryVault : SecretVault {
        val entries = LinkedHashMap<String, String>()
        var failWrites = false

        override fun read(key: String): String? = entries[key]

        override fun contains(key: String): Boolean = entries.containsKey(key)

        override fun write(key: String, value: String?, durable: Boolean) {
            if (failWrites) throw IOException("disk full")
            if (value == null) entries.remove(key) else entries[key] = value
        }
    }

    /** A message as the server stores and serves it. */
    private class Row(val id: String, val clientId: String, val sender: String, val envelope: String)

    /** One installation of the app. */
    private inner class Phone(val id: String, installation: String = "1", hasKeys: Boolean = true) {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        val archiveDirectory: File = folder.root.resolve("archive-$id-$installation")
        private val archiveKey = ByteArray(32) { (id.hashCode() + it).toByte() }
        var identity: E2eeProtocol.IdentityKeyPair? = if (hasKeys) E2eeProtocol.generateIdentity() else null
        val prekey: E2eeProtocol.PrekeyPair = E2eeProtocol.generatePrekeyPair()
        val published = ArrayDeque<ByteArray>()
        var now = 1_000L
        var chainMessages = E2eeGroup.MAX_CHAIN_MESSAGES

        init {
            val pairs = List(4) { E2eeProtocol.generateOneTimePrekey() }
            store.addOneTimeKeys(pairs.associate { E2eeProtocol.keyReference(it.public) to it.private })
            pairs.forEach { published.addLast(it.public) }
        }

        val identityKey: String? get() = identity?.let { encodeBase64(it.identityPublic) }

        fun bundle(): PeerBundle? {
            val keys = identity ?: return null
            return PeerBundle(
                identityPublic = keys.identityPublic,
                signedPrekeyPublic = prekey.public,
                signedPrekeySignature = E2eeProtocol.signPrekey(prekey.public, keys.identityPrivate),
                oneTimePrekeyPublic = published.removeFirstOrNull(),
            )
        }

        /** A fresh client over this phone's storage — what an app restart produces. */
        fun client(hub: Hub): E2eeClient = E2eeClient(
            localId = id,
            store = store,
            archive = E2eeArchive(archiveDirectory, archiveKey),
            identity = { identity },
            signedPrekey = { reference -> prekey.takeIf { E2eeProtocol.keyReference(it.public) == reference } },
            fetchBundle = { hub.bundle(it) },
            clock = { now },
            maxChainMessages = chainMessages,
        )
    }

    /** The server: key bundles, who is in the group, the envelopes that hand out chains, the messages. */
    private inner class Hub {
        val phones = HashMap<String, Phone>()
        val members = LinkedHashSet<String>()
        val bundleFetches = AtomicInteger()
        val rosterReads = AtomicInteger()
        val handOvers = ArrayList<List<GroupKey>>()
        val rows = ArrayList<Row>()
        var postFailure: (() -> Throwable?)? = null
        var bundleLies: ((String, PeerBundle) -> PeerBundle)? = null

        /** How often a member told the server that the chain they were handed does not open. */
        var askedAgain = 0

        /** group chain envelopes: (sender, chain, recipient) → (identity it was sealed for, envelope) */
        private val keys = LinkedHashMap<Triple<String, String, String>, Pair<String, String>>()
        private val counter = AtomicInteger()

        fun join(phone: Phone): Phone {
            phones[phone.id] = phone
            members += phone.id
            return phone
        }

        fun bundle(id: String): PeerBundle? {
            bundleFetches.incrementAndGet()
            val bundle = phones[id]?.bundle() ?: return null
            return bundleLies?.invoke(id, bundle) ?: bundle
        }

        fun roster(caller: String, chain: String?): GroupRoster {
            rosterReads.incrementAndGet()
            val held = keys.filterKeys { it.first == caller && it.second == chain }.mapKeys { it.key.third }
            val current = members.filter { it != caller }.associateWith { phones[it]?.identityKey }
            return GroupRoster(
                // An envelope the member said they could not open is kept as an empty one.
                members = current.map { (id, identity) ->
                    val theirs = held[id]?.takeIf { identity != null && it.first == identity }
                    RosterMember(id, identity, hasKey = theirs != null && theirs.second.isNotEmpty(), again = theirs != null && theirs.second.isEmpty())
                },
                stale = held.any { (holder, row) -> current[holder] != row.first },
            )
        }

        fun handOver(sender: String, chain: String, handed: List<GroupKey>) {
            if (sender !in members) throw IllegalStateException("403")
            if (handed.any { it.recipientId !in members || phones[it.recipientId]?.identityKey != it.recipientIdentity }) throw RosterChanged()
            handOvers += handed
            for (key in handed) {
                val name = Triple(sender, chain, key.recipientId)
                val kept = keys[name]
                // The first envelope stays — unless the member said it does not open.
                if (kept == null || (kept.second.isEmpty() && kept.first == key.recipientIdentity)) keys[name] = key.recipientIdentity to key.envelope
            }
        }

        /** A member says the envelope that was to hand them [chain] of [sender] does not open. */
        fun askAgain(recipient: String, sender: String, chain: String): Boolean {
            val name = Triple(sender, chain, recipient)
            val kept = keys[name] ?: return false
            if (kept.second.isEmpty() || kept.first != phones[recipient]?.identityKey) return false
            keys[name] = kept.first to ""
            askedAgain++
            return true
        }

        /** The envelope kept for [recipient] reaches them changed: a server, or a disk, that does not keep what it was given. */
        fun damageKey(recipient: String, sender: String, chain: String) {
            val name = Triple(sender, chain, recipient)
            val kept = keys[name] ?: error("no envelope to damage")
            val at = kept.second.indexOf("\"ct\":\"") + 8
            val other = if (kept.second[at] == 'A') 'B' else 'A'
            keys[name] = kept.first to kept.second.substring(0, at) + other + kept.second.substring(at + 1)
        }

        fun post(sender: String, clientId: String, sealed: GroupSealed): Row {
            rows.firstOrNull { it.sender == sender && it.clientId == clientId }?.let { return it }
            postFailure?.invoke()?.let { throw it }
            val state = roster(sender, sealed.chainId).also { rosterReads.decrementAndGet() }
            if (state.stale || state.members.any { it.identityKey != null && !it.hasKey }) throw RosterChanged()
            return Row("s-${counter.incrementAndGet()}", clientId, sender, sealed.envelope).also { rows += it }
        }

        /** The envelope that hands [recipient] the chain, if there is one sealed for the keys they have now. */
        fun keyFor(recipient: String, sender: String, chain: String): String? =
            keys[Triple(sender, chain, recipient)]?.takeIf { it.first == phones[recipient]?.identityKey }?.second?.takeIf { it.isNotEmpty() }

        fun transport(caller: String) = object : GroupTransport {
            override suspend fun roster(chainId: String?): GroupRoster = this@Hub.roster(caller, chainId)

            override suspend fun handOver(chainId: String, keys: List<GroupKey>) = this@Hub.handOver(caller, chainId, keys)

            override fun rosterChanged(error: Throwable): Boolean = error is RosterChanged
        }
    }

    private val sequence = AtomicInteger()

    /** Sends through the client's own path; null when it decided the message goes out unsealed. */
    private fun send(hub: Hub, phone: Phone, text: String, client: E2eeClient = phone.client(hub), clientId: String = "c-${sequence.incrementAndGet()}"): Row? =
        runBlocking {
            client.sendGroupSealed(group, clientId, E2eePayload.text(text), hub.transport(phone.id)) { sealed -> hub.post(phone.id, clientId, sealed) }
        }

    /** Reads a row the way the app does: asks for the chains it lacks, takes them in, then opens. */
    private fun read(hub: Hub, phone: Phone, row: Row, client: E2eeClient = phone.client(hub)): String? {
        for (wanted in client.missingGroupKeys(group, listOf(GroupRow(row.sender, row.envelope)))) {
            val envelope = hub.keyFor(phone.id, wanted.senderId, wanted.chainId)
            when {
                envelope == null -> client.noGroupKey(group, wanted.senderId, wanted.chainId)
                client.acceptGroupKey(group, wanted.senderId, wanted.chainId, envelope) -> Unit
                else -> runBlocking {
                    client.askGroupKeyAgain(group, wanted.senderId, wanted.chainId) { hub.askAgain(phone.id, wanted.senderId, wanted.chainId) }
                }
            }
        }
        return client.revealGroup(group, row.sender, row.id, row.clientId, mine = row.sender == phone.id, envelope = row.envelope)?.body
    }

    private fun family(): Triple<Hub, List<Phone>, Unit> {
        val hub = Hub()
        val phones = listOf("alice", "bob", "carol").map { hub.join(Phone(it)) }
        return Triple(hub, phones, Unit)
    }

    // ------------------------------------------------------------ happy path

    @Test
    fun aGroupConversesAndARestartLosesNothing() {
        val (hub, phones) = family()
        val (alice, bob, carol) = phones

        val hello = send(hub, alice, "hello family")!!
        assertFalse(hello.envelope.contains("hello"))
        assertEquals("hello family", read(hub, bob, hello))
        assertEquals("hello family", read(hub, carol, hello))
        assertEquals("the sender reads their own from what the phone kept", "hello family", read(hub, alice, hello))

        val reply = send(hub, bob, "hello alice")!!
        assertEquals("hello alice", read(hub, alice, reply))
        assertEquals("hello alice", read(hub, carol, reply))

        // Every read above used a new client: a restart between any two steps. Once more, all of it.
        for (phone in phones) {
            assertEquals("hello family", read(hub, phone, hello))
            assertEquals("hello alice", read(hub, phone, reply))
        }
        // Nothing readable is stored on the phone.
        assertFalse(alice.vault.entries.values.any { it.contains("hello") })
    }

    @Test
    fun theChainIsHandedOverOnceAndLaterMessagesCostNothingExtra() {
        val (hub, phones) = family()
        val (alice, bob, carol) = phones
        val a = alice.client(hub)

        val first = send(hub, alice, "one", a)!!
        assertEquals(listOf(listOf("bob", "carol")), hub.handOvers.map { handed -> handed.map { it.recipientId } })
        assertEquals("a session with each member, started once", 2, hub.bundleFetches.get())
        assertEquals(1, hub.rosterReads.get())

        val more = (2..5).map { send(hub, alice, "message $it", a)!! }
        assertEquals("nobody is handed anything again", 1, hub.handOvers.size)
        assertEquals("and the roster is not read again", 1, hub.rosterReads.get())
        assertEquals(2, hub.bundleFetches.get())
        assertEquals("one chain", 1, (listOf(first) + more).map { E2eeGroup.chainId(it.envelope) }.toSet().size)
        // The same after a restart.
        val later = send(hub, alice, "after a restart")!!
        assertEquals(1, hub.handOvers.size)
        assertEquals(E2eeGroup.chainId(first.envelope), E2eeGroup.chainId(later.envelope))

        assertEquals("message 5", read(hub, bob, more.last()))
        assertEquals("one", read(hub, bob, first))
        assertEquals("after a restart", read(hub, carol, later))
        // Each envelope that hands the chain over was sealed for one member under their own keys.
        val handed = hub.handOvers.single()
        assertEquals(bob.identityKey, handed.first { it.recipientId == "bob" }.recipientIdentity)
        assertNotEquals(handed[0].envelope, handed[1].envelope)
    }

    @Test
    fun aWeekOfMessagesOpensNewestPageFirst() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        val a = alice.client(hub)
        val sent = (0 until 120).map { send(hub, alice, "message $it", a)!! }
        val b = bob.client(hub)
        b.batch {
            for (index in 119 downTo 80) assertEquals("message $index", read(hub, bob, sent[index], b))
        }
        val later = bob.client(hub)
        for (index in listOf(40, 79, 0, 41)) assertEquals("message $index", read(hub, bob, sent[index], later))
        assertEquals("and again from what was kept", "message 119", read(hub, bob, sent[119]))
    }

    // ----------------------------------------------------------- no downgrade

    @Test
    fun aGroupWithAMemberWhoHasNoKeysStaysAsItWasUntilEveryoneHasThem() {
        val hub = Hub()
        val alice = hub.join(Phone("alice"))
        val bob = hub.join(Phone("bob"))
        val dave = hub.join(Phone("dave", hasKeys = false))

        // Dave's app is an older one. Sealing would shut him out of his own group: it goes as before.
        val stored = LinkedHashMap(alice.vault.entries)
        assertNull(send(hub, alice, "everyone can read this"))
        assertEquals("nothing is remembered about a group that is not encrypted", stored, alice.vault.entries)
        assertNull(alice.client(hub).groupSecurity(group))
        assertEquals("nobody's prekeys were spent on it", 0, hub.bundleFetches.get())

        // Dave updates.
        dave.identity = E2eeProtocol.generateIdentity()
        val sealed = send(hub, alice, "now it is sealed")!!
        assertEquals("now it is sealed", read(hub, dave, sealed))
        assertEquals("now it is sealed", read(hub, bob, sealed))
        assertNotNull(alice.client(hub).groupSecurity(group))
    }

    @Test
    fun onceAGroupIsEncryptedOnThisPhoneNothingGoesOutReadable() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        val first = send(hub, alice, "sealed")!!
        assertEquals("sealed", read(hub, bob, first))

        // Somebody joins whose app has no keys. The others keep sealing; the newcomer cannot read.
        val erin = hub.join(Phone("erin", hasKeys = false))
        val next = send(hub, alice, "still sealed")!!
        assertEquals("still sealed", read(hub, bob, next))
        assertNull(read(hub, erin, next))
        // Bob only ever received in this group; that is enough for his phone to know it is encrypted.
        val fromBob = send(hub, bob, "sealed too")!!
        assertEquals("sealed too", read(hub, alice, fromBob))

        // A phone that cannot reach its own keys for the moment, but holds its chain: what it
        // writes is sealed as before. The chain is all that sealing takes.
        val bobsKeys = bob.identity
        bob.identity = null
        assertEquals("still sealed by bob", read(hub, alice, send(hub, bob, "still sealed by bob")!!))
        bob.identity = bobsKeys

        // Carol has only ever read here, so she has no chain yet, and her phone has no keys to
        // make one with. It says so. It does not answer "unsealed is fine".
        val carol = phones[2]
        assertEquals("sealed", read(hub, carol, first))
        val carolsKeys = carol.identity
        carol.identity = null
        try {
            send(hub, carol, "must not leave readable")
            fail("sent, or allowed unsealed, without keys in an encrypted group")
        } catch (refused: IllegalStateException) {
            assertEquals(E2eeText.NOT_READY, refused.message)
        }
        carol.identity = carolsKeys
        // A phone without keys in a group that was never encrypted on it: as before.
        val frank = hub.join(Phone("frank", hasKeys = false))
        assertNull(send(hub, frank, "an old app writes as it always did"))
    }

    @Test
    fun beforeAFileForAGroupIsUploadedItIsKnownWhetherItWillBeSealed() {
        val hub = Hub()
        val alice = hub.join(Phone("alice"))
        val bob = hub.join(Phone("bob"))
        val dave = hub.join(Phone("dave", hasKeys = false))
        val a = alice.client(hub)

        // Somebody has no keys and nothing was ever sealed here: a file goes as it always did.
        assertFalse(runBlocking { a.willSealGroup(group, hub.transport("alice")) })
        assertTrue(hub.handOvers.isEmpty())
        // Everyone has keys: the chain the file's message will be sealed with is made and handed over now.
        dave.identity = E2eeProtocol.generateIdentity()
        assertTrue(runBlocking { a.willSealGroup(group, hub.transport("alice")) })
        assertEquals(listOf(listOf("bob", "dave")), hub.handOvers.map { handed -> handed.map { it.recipientId } })
        // Asking again, and the message itself, hand nothing over a second time.
        assertTrue(runBlocking { alice.client(hub).willSealGroup(group, hub.transport("alice")) })
        val row = send(hub, alice, "the photo's message")!!
        assertEquals(1, hub.handOvers.size)
        assertEquals("the photo's message", read(hub, bob, row))
        assertEquals("the photo's message", read(hub, dave, row))
    }

    // ------------------------------------------------- when the members change

    @Test
    fun aMemberWhoLeavesCannotReadWhatFollows() {
        val (hub, phones) = family()
        val (alice, bob, carol) = phones
        val a = alice.client(hub)
        val before = send(hub, alice, "while carol was here", a)!!
        assertEquals("while carol was here", read(hub, carol, before))
        val chain = E2eeGroup.chainId(before.envelope)

        hub.members -= "carol"
        val after = send(hub, alice, "after carol left", a)!!
        assertNotEquals("a new chain: the old one is in carol's hands", chain, E2eeGroup.chainId(after.envelope))
        assertEquals("after carol left", read(hub, bob, after))
        assertEquals("handed to who is left, and only to them", listOf("bob"), hub.handOvers.last().map { it.recipientId })
        // Carol still has the old chain and gets hold of the new message all the same.
        assertNull(read(hub, carol, after))
        assertNull(carol.client(hub).revealGroup(group, "alice", after.id, after.clientId, mine = false, envelope = after.envelope))
        // What she could read before, she still can.
        assertEquals("while carol was here", read(hub, carol, before))
    }

    @Test
    fun aNewMemberReadsFromWhenTheyJoined() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        val a = alice.client(hub)
        val early = (1..3).map { send(hub, alice, "early $it", a)!! }
        val chain = E2eeGroup.chainId(early[0].envelope)

        val dan = hub.join(Phone("dan"))
        val late = send(hub, alice, "welcome dan", a)!!
        assertEquals("the same chain, handed on", chain, E2eeGroup.chainId(late.envelope))
        assertEquals(listOf("dan"), hub.handOvers.last().map { it.recipientId })
        assertEquals("welcome dan", read(hub, dan, late))
        for (row in early) assertNull("sent before dan was in the group", read(hub, dan, row))
        assertEquals("welcome dan", read(hub, bob, late))
        assertEquals("early 2", read(hub, bob, early[1]))
    }

    @Test
    fun aMemberOnANewPhoneIsHandedANewChainAndTheOldPhoneNothing() {
        val (hub, phones) = family()
        val (alice, oldBob, carol) = phones
        val a = alice.client(hub)
        val before = send(hub, alice, "to bob's first phone", a)!!
        assertEquals("to bob's first phone", read(hub, oldBob, before))

        val newBob = hub.join(Phone("bob", installation = "2"))
        val after = send(hub, alice, "to bob's new phone", a)!!
        assertNotEquals(E2eeGroup.chainId(before.envelope), E2eeGroup.chainId(after.envelope))
        assertEquals("to bob's new phone", read(hub, newBob, after))
        assertEquals("to bob's new phone", read(hub, carol, after))
        // The phone bob had: it holds the old chain, and is handed nothing for the new one.
        assertNull(oldBob.client(hub).revealGroup(group, "alice", after.id, after.clientId, mine = false, envelope = after.envelope))
        assertNull("the history of the old phone stays with the old phone", read(hub, newBob, before))
        // Alice is told that bob's keys changed, as in a chat with him alone.
        assertTrue(alice.client(hub).security("bob")!!.changed)
    }

    @Test
    fun aVerifiedMemberWhoseKeysChangedHoldsTheGroupUntilTheUserHasLooked() {
        val (hub, phones) = family()
        val (alice, oldBob, carol) = phones
        val a = alice.client(hub)
        send(hub, alice, "before", a)!!
        assertTrue(a.setVerified("bob", oldBob.identityKey!!, true))

        val newBob = hub.join(Phone("bob", installation = "2"))
        val posted = hub.rows.size
        val handed = hub.handOvers.size
        try {
            send(hub, alice, "must wait", a)
            fail("a chain was handed to keys the user had not looked at")
        } catch (held: E2eeClient.MemberHeld) {
            assertEquals("bob", held.memberId)
            assertEquals(E2eeText.MEMBER_CODE_CHANGED, held.message)
        }
        assertEquals("nothing was posted", posted, hub.rows.size)
        assertEquals("and nobody was handed the new chain", handed, hub.handOvers.size)
        assertTrue(a.security("bob")!!.held)

        assertTrue(a.acceptChange("bob", newBob.identityKey!!))
        val after = send(hub, alice, "now it goes", a)!!
        assertEquals("now it goes", read(hub, newBob, after))
        assertEquals("now it goes", read(hub, carol, after))
    }

    @Test
    fun aRosterThatKeepsChangingIsAnsweredOnceAndThenGivenUpOn() {
        val (hub, phones) = family()
        val (alice) = phones
        val a = alice.client(hub)
        send(hub, alice, "first", a)!!
        val reads = hub.rosterReads.get()

        hub.postFailure = { RosterChanged() }
        try {
            send(hub, alice, "never arrives", a)
            fail("kept trying")
        } catch (_: RosterChanged) {
        }
        assertEquals("the roster was read once more, not over and over", reads + 1, hub.rosterReads.get())
        hub.postFailure = null
        assertNotNull(send(hub, alice, "arrives", a))
    }

    // ------------------------------------------------------------ sealed once

    @Test
    fun aMessageIsSealedOnceAndARetrySendsTheSameEnvelope() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        val a = alice.client(hub)
        send(hub, alice, "first", a)!!

        val attempts = ArrayList<String>()
        fun attempt(client: E2eeClient, arrives: Boolean): Row? = runBlocking {
            try {
                client.sendGroupSealed(group, "c-retry", E2eePayload.text("the plan"), hub.transport("alice")) { sealed ->
                    attempts += sealed.envelope
                    if (!arrives) throw IOException("no connection")
                    hub.post("alice", "c-retry", sealed)
                }
            } catch (_: IOException) {
                null
            }
        }
        assertNull(attempt(a, arrives = false))
        assertNull(attempt(a, arrives = false))
        assertNull("also after a restart", attempt(alice.client(hub), arrives = false))
        // Until it has arrived, the envelope is kept with the group's record…
        val signature = Regex("\"sig\":\"([^\"]+)\"").find(attempts.first())!!.groupValues[1]
        assertTrue(alice.vault.entries.values.any { it.contains(signature) })
        val row = attempt(alice.client(hub), arrives = true)!!
        assertEquals("the same envelope every time", 1, attempts.toSet().size)
        // …and once the server has it, no longer.
        assertFalse(alice.vault.entries.values.any { it.contains(signature) })
        assertEquals("the plan", read(hub, bob, row))
        // The number the retries would have used up is the next message's.
        val next = send(hub, alice, "next")!!
        assertEquals("next", read(hub, bob, next))
        // Other words under the same id are other content: sealed anew, never passed off as the first.
        val drafts = ArrayList<String>()
        for (words in listOf("draft one", "draft two")) {
            try {
                runBlocking {
                    a.sendGroupSealed<Row>(group, "c-draft", E2eePayload.text(words), hub.transport("alice")) { sealed ->
                        drafts += sealed.envelope
                        throw IOException("no connection")
                    }
                }
            } catch (_: IOException) {
            }
        }
        assertEquals(2, drafts.toSet().size)
    }

    // ------------------------------------------------------- bound to its place

    @Test
    fun anEnvelopeOpensOnlyAsThatMembersMessageInThatGroup() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        val row = send(hub, alice, "for the family")!!
        val b = bob.client(hub)
        for (wanted in b.missingGroupKeys(group, listOf(GroupRow("alice", row.envelope)))) {
            assertTrue(b.acceptGroupKey(group, wanted.senderId, wanted.chainId, hub.keyFor("bob", wanted.senderId, wanted.chainId)!!))
        }
        assertNull("as carol's", b.revealGroup(group, "carol", row.id, row.clientId, mine = false, envelope = row.envelope))
        assertNull("in another group", b.revealGroup("g-other", "alice", row.id, row.clientId, mine = false, envelope = row.envelope))
        assertNull("as another message", b.revealGroup(group, "alice", "s-999", "c-999", mine = false, envelope = row.envelope))
        assertNull("as a message to bob alone", b.reveal("alice", row.id, row.clientId, mine = false, envelope = row.envelope))
        assertNull(b.revealGroup(group, "alice", "", row.clientId, mine = false, envelope = row.envelope))
        assertNull(b.revealGroup("", "alice", row.id, row.clientId, mine = false, envelope = row.envelope))
        assertEquals("for the family", b.revealGroup(group, "alice", row.id, row.clientId, mine = false, envelope = row.envelope)?.body)

        // Opened and kept — and still alice's. The same row served later under another member's
        // name, or as bob's own, is not answered from what the phone kept for alice's.
        for (client in listOf(b, bob.client(hub))) {
            assertNull("as carol's, once opened", client.revealGroup(group, "carol", row.id, row.clientId, mine = false, envelope = row.envelope))
            assertNull("as bob's own", client.revealGroup(group, "bob", row.id, row.clientId, mine = true, envelope = row.envelope))
            assertEquals("for the family", client.revealGroup(group, "alice", row.id, row.clientId, mine = false, envelope = row.envelope)?.body)
        }
    }

    @Test
    fun theEnvelopeThatHandsOverAChainIsNotAMessageAndTheOtherWayRound() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        val row = send(hub, alice, "group message")!!
        val chain = E2eeGroup.chainId(row.envelope)!!
        val key = hub.keyFor("bob", "alice", chain)!!

        // The server puts the key envelope into the chat between alice and bob — under any id,
        // also one made up of exactly what the envelope was sealed for.
        val b = bob.client(hub)
        assertNull(b.reveal("alice", "s-1", "c-1", mine = false, envelope = key))
        assertNull(b.reveal("alice", "$group:$chain", "$group:$chain", mine = false, envelope = key))
        assertNull(b.reveal("alice", "k:$group:$chain", "k:$group:$chain", mine = false, envelope = key))
        // Or hands it out as the key of another chain, another group, another sender.
        assertFalse(b.acceptGroupKey(group, "alice", encodeBase64(ByteArray(16) { 7 }), key))
        assertFalse(b.acceptGroupKey("g-other", "alice", chain, key))
        assertFalse(b.acceptGroupKey(group, "carol", chain, key))
        assertFalse(b.acceptGroupKey(group, "alice", chain, "{}"))
        assertFalse(b.acceptGroupKey(group, "alice", "", key))
        // A sealed message between the two of them, passed off as the envelope that hands over the chain.
        val direct = runBlocking { alice.client(hub).seal("bob", "c-direct", E2eePayload.text("just for bob")) }!!
        assertFalse(b.acceptGroupKey(group, "alice", chain, direct.envelope))
        assertNull("nothing came of any of that", b.revealGroup(group, "alice", row.id, row.clientId, mine = false, envelope = row.envelope))
        // None of it used the envelope up.
        assertTrue(b.acceptGroupKey(group, "alice", chain, key))
        assertEquals("group message", b.revealGroup(group, "alice", row.id, row.clientId, mine = false, envelope = row.envelope)?.body)
        // And the message between the two of them is still theirs to open.
        assertEquals("just for bob", bob.client(hub).reveal("alice", "s-direct", "c-direct", mine = false, envelope = direct.envelope)?.body)
    }

    @Test
    fun takingInAChainTwiceChangesNothing() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        val a = alice.client(hub)
        val rows = (1..4).map { send(hub, alice, "message $it", a)!! }
        val chain = E2eeGroup.chainId(rows[0].envelope)!!
        val key = hub.keyFor("bob", "alice", chain)!!

        val b = bob.client(hub)
        assertTrue(b.acceptGroupKey(group, "alice", chain, key))
        assertEquals("message 3", read(hub, bob, rows[2], b))
        // The same envelope again — in the same run, and after a restart.
        assertTrue(b.acceptGroupKey(group, "alice", chain, key))
        assertTrue(bob.client(hub).acceptGroupKey(group, "alice", chain, key))
        // The chain is where it was: the rest still opens, and what was opened is spent — the same
        // envelope served again as another row finds nothing left to open it with.
        assertEquals("message 1", read(hub, bob, rows[0]))
        assertEquals("message 4", read(hub, bob, rows[3]))
        assertEquals("message 3", read(hub, bob, rows[2]))
        assertNull(bob.client(hub).revealGroup(group, "alice", "s-served-again", rows[2].clientId, mine = false, envelope = rows[2].envelope))
    }

    // ------------------------------------------------- deleted, and replaced

    @Test
    fun aGroupMessageThatWasDeletedOrReplacedIsHandledLikeAnyOther() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        val a = alice.client(hub)
        val kept = send(hub, alice, "keep", a)!!
        val dropped = send(hub, alice, "delete me", a)!!
        assertEquals("keep", read(hub, bob, kept))
        assertEquals("delete me", read(hub, bob, dropped))

        val b = bob.client(hub)
        // What the phone opened under that id, by the member who wrote it — and by nobody else.
        assertEquals("keep", b.keptInGroup(group, "alice", kept.id, kept.clientId, mine = false)?.body)
        assertNull("not as carol's", b.keptInGroup(group, "carol", kept.id, kept.clientId, mine = false))
        assertNull(b.keptInGroup("g-other", "alice", kept.id, kept.clientId, mine = false))
        assertEquals("keep", alice.client(hub).keptInGroup(group, "alice", kept.id, kept.clientId, mine = true)?.body)

        b.forgetInGroup(group, "alice", dropped.id, dropped.clientId)
        assertNull(bob.client(hub).keptInGroup(group, "alice", dropped.id, dropped.clientId, mine = false))
        assertNull("its place in the chain is spent", read(hub, bob, dropped))
        assertEquals("keep", read(hub, bob, kept))
        // The sender's own copy goes the same way.
        a.forgetInGroup(group, "alice", dropped.id, dropped.clientId)
        assertNull(read(hub, alice, dropped))
        assertEquals("keep", read(hub, alice, kept))
        // Forgetting under another member's name removes nothing of alice's.
        bob.client(hub).forgetInGroup(group, "carol", kept.id, kept.clientId)
        assertEquals("keep", bob.client(hub).keptInGroup(group, "alice", kept.id, kept.clientId, mine = false)?.body)
    }

    // ----------------------------------------------------- asking for chains

    @Test
    fun aChainThisPhoneLacksIsAskedForOnceAndNotAgainWhenThereIsNone() {
        val (hub, phones) = family()
        val (alice, bob, carol) = phones
        val fromAlice = (1..3).map { send(hub, alice, "a$it")!! }
        val fromCarol = send(hub, carol, "c1")!!
        val mine = send(hub, bob, "b1")!!
        val page = (fromAlice + fromCarol + mine).map { GroupRow(it.sender, it.envelope) } + GroupRow("alice", "not an envelope")

        val b = bob.client(hub)
        val wanted = b.missingGroupKeys(group, page)
        assertEquals("each chain once, and not bob's own", setOf("alice", "carol"), wanted.map { it.senderId }.toSet())
        assertEquals(2, wanted.size)
        assertEquals(E2eeGroup.chainId(fromAlice[0].envelope), wanted.first { it.senderId == "alice" }.chainId)

        // Alice's arrives; carol's was sealed before bob was in the group, say: the server has none for him.
        assertTrue(b.acceptGroupKey(group, "alice", wanted.first { it.senderId == "alice" }.chainId, hub.keyFor("bob", "alice", wanted.first { it.senderId == "alice" }.chainId)!!))
        b.noGroupKey(group, "carol", wanted.first { it.senderId == "carol" }.chainId)
        assertEquals(emptyList<GroupKeyRef>(), b.missingGroupKeys(group, page))
        assertNull(b.revealGroup(group, "carol", fromCarol.id, fromCarol.clientId, mine = false, envelope = fromCarol.envelope))
        // A new process asks once more — the answer may have changed — and for alice's not at all.
        assertEquals(listOf("carol"), bob.client(hub).missingGroupKeys(group, page).map { it.senderId })
        assertEquals(emptyList<GroupKeyRef>(), b.missingGroupKeys("g-other", emptyList()))
    }

    // -------------------------------------------------------- a chain's life

    @Test
    fun aChainIsReplacedAfterSoManyMessagesAndAfterAMonth() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        alice.chainMessages = 3
        val a = alice.client(hub)
        val rows = (1..7).map { send(hub, alice, "message $it", a)!! }
        val chains = rows.map { E2eeGroup.chainId(it.envelope) }
        assertEquals("three messages to a chain", listOf(3, 3, 1), chains.distinct().map { chain -> chains.count { it == chain } })
        // Newest first, as the app meets a conversation: by the time the older messages are
        // opened, bob holds the chains that came after theirs as well.
        for (index in rows.indices.reversed()) assertEquals("message ${index + 1}", read(hub, bob, rows[index]))

        alice.chainMessages = E2eeGroup.MAX_CHAIN_MESSAGES
        val fresh = alice.client(hub)
        val today = send(hub, alice, "today", fresh)!!
        alice.now += 29L * 24 * 60 * 60 * 1000
        val soon = send(hub, alice, "four weeks on", fresh)!!
        assertEquals(E2eeGroup.chainId(today.envelope), E2eeGroup.chainId(soon.envelope))
        alice.now += 2L * 24 * 60 * 60 * 1000
        val later = send(hub, alice, "a month on", fresh)!!
        assertNotEquals("a chain is not kept for ever", E2eeGroup.chainId(soon.envelope), E2eeGroup.chainId(later.envelope))
        assertEquals("a month on", read(hub, bob, later))
        // A message is opened with the chain it names, not with whichever one arrived last.
        assertEquals("today", read(hub, bob, today))
        assertEquals("four weeks on", read(hub, bob, soon))
    }

    @Test
    fun aPhoneKnowsSinceWhenAGroupIsEncryptedOnIt() {
        val (hub, phones) = family()
        val (alice, bob, carol) = phones
        assertNull(alice.client(hub).groupSecurity(group))
        alice.now = 5_000L
        val row = send(hub, alice, "first")!!
        assertEquals(5_000L, alice.client(hub).groupSecurity(group)!!.since)
        // For a member who reads, it begins with the first message they open.
        assertNull(bob.client(hub).groupSecurity(group))
        bob.now = 9_000L
        assertEquals("first", read(hub, bob, row))
        assertEquals(9_000L, bob.client(hub).groupSecurity(group)!!.since)
        // It does not move afterwards — not for who writes, not for who reads.
        alice.now = 50_000L
        val second = send(hub, alice, "second")!!
        assertEquals(5_000L, alice.client(hub).groupSecurity(group)!!.since)
        bob.now = 60_000L
        assertEquals("second", read(hub, bob, second))
        assertEquals(9_000L, bob.client(hub).groupSecurity(group)!!.since)
        assertNull(carol.client(hub).groupSecurity("g-other"))
    }

    // ------------------------------------------------------- what can go wrong

    @Test
    fun aRosterAndABundleThatDisagreeHandNothingOver() {
        val (hub, phones) = family()
        val (alice) = phones
        // The roster names bob's keys; the bundle that is served for him is somebody else's.
        val stranger = Phone("stranger")
        hub.bundleLies = { id, bundle -> if (id == "bob") stranger.bundle()!! else bundle }
        try {
            send(hub, alice, "must not be handed to the stranger")
            fail("a chain was handed to keys the roster did not name")
        } catch (_: IOException) {
            // intended: it may be put right by asking again; it is never sent on regardless
        }
        assertTrue(hub.handOvers.isEmpty())
        assertTrue(hub.rows.isEmpty())
        // And nothing was built on the keys that were not asked for: no session, and no record
        // that would later make bob's real keys look like a change.
        assertNull(alice.client(hub).security("bob"))
        hub.bundleLies = null
        val row = send(hub, alice, "to the real bob")!!
        assertEquals("to the real bob", read(hub, phones[1], row))
        assertFalse(alice.client(hub).security("bob")!!.changed)
    }

    @Test
    fun whenThePhoneCannotStoreTheChainNothingIsSent() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        val a = alice.client(hub)
        val first = send(hub, alice, "first", a)!!
        alice.vault.failWrites = true
        try {
            send(hub, alice, "lost if it went", a)
            fail("sent a message whose place in the chain was not stored")
        } catch (_: IOException) {
        }
        assertEquals(1, hub.rows.size)
        alice.vault.failWrites = false
        // What is stored is still the truth: the next message is the next number, and opens.
        val next = send(hub, alice, "second", a)!!
        assertEquals("second", read(hub, bob, next))
        assertEquals("first", read(hub, bob, first))
    }

    @Test
    fun aGroupRecordThatCannotBeReadStillForbidsReadableMessages() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        send(hub, alice, "sealed")!!
        // What the phone kept about the group is damaged — and somebody without keys has joined.
        val entry = alice.vault.entries.keys.single { it.startsWith("e2ee.group.") }
        alice.vault.entries[entry] = "not what was stored"
        hub.join(Phone("erin", hasKeys = false))

        val next = send(hub, alice, "sealed all the same")
        assertNotNull("a phone that cannot read its record does not conclude the group was never encrypted", next)
        assertEquals("sealed all the same", read(hub, bob, next!!))
    }

    @Test
    fun aClosedClientNeitherSendsNorReadsNorTakesInChains() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        val row = send(hub, alice, "before")!!
        val chain = E2eeGroup.chainId(row.envelope)!!
        val b = bob.client(hub)
        assertEquals("before", read(hub, bob, row, b))
        b.close()
        assertNull(b.revealGroup(group, "alice", row.id, row.clientId, mine = false, envelope = row.envelope))
        assertFalse(b.acceptGroupKey(group, "alice", chain, hub.keyFor("bob", "alice", chain)!!))
        assertEquals(emptyList<GroupKeyRef>(), b.missingGroupKeys(group, listOf(GroupRow("alice", row.envelope))))
        assertNull(b.groupSecurity(group))
        try {
            send(hub, bob, "after", b)
            fail("a closed client sent")
        } catch (refused: IllegalStateException) {
            assertEquals(E2eeText.SIGNED_OUT, refused.message)
        }
    }

    // ------------------------------------------- a chain that could not be opened

    @Test
    fun aMemberWhoLostTheirSessionAsksForTheChainAgainAndIsHandedItOnANewOne() {
        val (hub, phones) = family()
        val (alice, bob, carol) = phones
        // Alice and Bob have both written here, so the session between them is an established
        // one: what Alice seals for Bob from now on carries nothing to start a session from.
        val one = send(hub, alice, "one")!!
        assertEquals("one", read(hub, bob, one))
        assertEquals("from bob", read(hub, alice, send(hub, bob, "from bob")!!))
        // A month on Alice's chain is replaced, and handed out on the sessions there are.
        alice.now += 31L * 24 * 60 * 60 * 1000
        val two = send(hub, alice, "two")!!
        val chain = E2eeGroup.chainId(two.envelope)!!
        assertNotEquals(E2eeGroup.chainId(one.envelope), chain)

        // Bob's phone has lost what it knew of its session with Alice.
        assertNotNull(bob.vault.entries.remove("e2ee.session.alice"))
        val b = bob.client(hub)
        assertNull("the envelope that hands over the new chain does not open", read(hub, bob, two, b))
        assertEquals("so the phone tells the server", 1, hub.askedAgain)
        assertNull(read(hub, bob, two, b))
        assertEquals("once", 1, hub.askedAgain)
        assertEquals("nobody else is affected", "two", read(hub, carol, two))

        // Alice's next message. The server refuses it, she finds that Bob asked, and hands him
        // the chain on a session started anew from his key bundle — the old one is what failed.
        val handOvers = hub.handOvers.size
        val fetches = hub.bundleFetches.get()
        val three = send(hub, alice, "three")!!
        assertEquals("the same chain: nobody is to stop reading", chain, E2eeGroup.chainId(three.envelope))
        assertEquals(listOf(listOf("bob")), hub.handOvers.drop(handOvers).map { handed -> handed.map { it.recipientId } })
        assertEquals("a new session with Bob", fetches + 1, hub.bundleFetches.get())
        assertEquals("the phone that asked looks again without a restart", "three", read(hub, bob, three, b))
        assertEquals("three", read(hub, carol, three))
        // What was sealed before the chain reached him stays closed; it is not shown as anything else.
        assertNull(read(hub, bob, two, b))

        // From here on it costs nothing extra, on either phone.
        val four = send(hub, alice, "four")!!
        assertEquals(handOvers + 1, hub.handOvers.size)
        assertEquals(fetches + 1, hub.bundleFetches.get())
        assertEquals("four", read(hub, bob, four))
        assertEquals(1, hub.askedAgain)
        // The session that was started anew is the one the two of them use from here on, in
        // a chat with each other as well, and in both directions.
        val direct = runBlocking { b.sendSealed("alice", "c-direct", E2eePayload.text("just to you"), { false }) { it.envelope } }!!
        assertEquals("just to you", alice.client(hub).reveal("bob", "s-direct", "c-direct", mine = false, envelope = direct)?.body)
        val answer = runBlocking { alice.client(hub).sendSealed("bob", "c-answer", E2eePayload.text("and to you"), { false }) { it.envelope } }!!
        assertEquals("and to you", bob.client(hub).reveal("alice", "s-answer", "c-answer", mine = false, envelope = answer)?.body)
        assertEquals("nobody's keys were fetched for that", fetches + 1, hub.bundleFetches.get())
    }

    @Test
    fun anEnvelopeThatArrivesDamagedIsAskedForAgainAndASecondOneIsNotChasedForever() {
        val (hub, phones) = family()
        val (alice, bob, carol) = phones
        val one = send(hub, alice, "one")!!
        val chain = E2eeGroup.chainId(one.envelope)!!
        hub.damageKey("bob", "alice", chain)

        val b = bob.client(hub)
        assertNull(read(hub, bob, one, b))
        assertEquals(1, hub.askedAgain)
        assertEquals("one", read(hub, carol, one))

        // Handed again — and that envelope arrives damaged too.
        val two = send(hub, alice, "two")!!
        hub.damageKey("bob", "alice", chain)
        assertNull(read(hub, bob, two, b))
        assertEquals("a phone asks once for a chain while it runs", 1, hub.askedAgain)
        // And stops fetching what it has seen will not open.
        assertEquals(emptyList<GroupKeyRef>(), b.missingGroupKeys(group, listOf(GroupRow("alice", two.envelope))))

        // After a restart it may ask once more; this time what it is handed is whole.
        val restarted = bob.client(hub)
        assertNull(read(hub, bob, two, restarted))
        assertEquals(2, hub.askedAgain)
        val three = send(hub, alice, "three")!!
        assertEquals("three", read(hub, bob, three, restarted))
        assertEquals("three", read(hub, bob, three))
        assertEquals(2, hub.askedAgain)
    }

    @Test
    fun askingAgainIsOnlyForAnEnvelopeThatWasThereAndDidNotOpen() {
        val (hub, phones) = family()
        val (alice, bob) = phones
        val one = send(hub, alice, "one")!!
        val chain = E2eeGroup.chainId(one.envelope)!!
        val b = bob.client(hub)
        val page = listOf(GroupRow("alice", one.envelope))
        var asked = 0
        val ask: suspend () -> Unit = { asked++ }

        // Nothing was tried yet: there is nothing to ask again for.
        assertFalse(runBlocking { b.askGroupKeyAgain(group, "alice", chain, ask) })
        // The chain is looked for, and the server has nothing of it: absent, not unopenable.
        assertEquals(listOf(GroupKeyRef("alice", chain)), b.missingGroupKeys(group, page))
        assertFalse("looked for, and no envelope has failed yet", runBlocking { b.askGroupKeyAgain(group, "alice", chain, ask) })
        b.noGroupKey(group, "alice", chain)
        assertFalse(runBlocking { b.askGroupKeyAgain(group, "alice", chain, ask) })
        assertEquals(0, asked)

        // The envelope does not open. The request does not get through: it is not counted as made.
        val c = bob.client(hub)
        assertEquals(listOf(GroupKeyRef("alice", chain)), c.missingGroupKeys(group, page))
        hub.damageKey("bob", "alice", chain)
        assertFalse(c.acceptGroupKey(group, "alice", chain, hub.keyFor("bob", "alice", chain)!!))
        try {
            runBlocking { c.askGroupKeyAgain(group, "alice", chain) { throw IOException("offline") } }
            fail("a request that failed was swallowed")
        } catch (_: IOException) {
        }
        assertTrue(runBlocking { c.askGroupKeyAgain(group, "alice", chain, ask) })
        assertEquals(1, asked)
        assertFalse("and not a second time", runBlocking { c.askGroupKeyAgain(group, "alice", chain, ask) })
        assertEquals(1, asked)
        // While the answer is on its way, "nothing there yet" does not make the phone stop looking
        // — not for the very message it first met the chain at, and not however often it is said.
        c.noGroupKey(group, "alice", chain)
        assertEquals(listOf(GroupKeyRef("alice", chain)), c.missingGroupKeys(group, page))
        c.noGroupKey(group, "alice", chain)
        assertEquals(listOf(GroupKeyRef("alice", chain)), c.missingGroupKeys(group, page))

        // A chain that was taken in is not asked for again, whatever is said about it afterwards.
        val carol = phones[2]
        val d = carol.client(hub)
        assertEquals("one", read(hub, carol, one, d))
        assertFalse(runBlocking { d.askGroupKeyAgain(group, "alice", chain, ask) })
        // Nor does a client that has been signed out ask anything.
        val e = bob.client(hub)
        assertFalse(e.acceptGroupKey(group, "alice", chain, hub.keyFor("bob", "alice", chain)!!))
        e.close()
        assertFalse(runBlocking { e.askGroupKeyAgain(group, "alice", chain, ask) })
        assertEquals(1, asked)
    }

    @Test
    fun theSameEnvelopeSeenTwiceIsNotASecondOne() {
        // Two readers of one page, or a page read again before the sender has answered: the
        // envelope that did not open is met a second time. That is not the "second envelope
        // that does not open either", after which a phone stops asking.
        val (hub, phones) = family()
        val (alice, bob) = phones
        val one = send(hub, alice, "one")!!
        val chain = E2eeGroup.chainId(one.envelope)!!
        val page = listOf(GroupRow("alice", one.envelope))
        hub.damageKey("bob", "alice", chain)
        val damaged = hub.keyFor("bob", "alice", chain)!!

        val b = bob.client(hub)
        assertEquals(listOf(GroupKeyRef("alice", chain)), b.missingGroupKeys(group, page))
        assertFalse(b.acceptGroupKey(group, "alice", chain, damaged))
        assertTrue(runBlocking { b.askGroupKeyAgain(group, "alice", chain) { hub.askAgain("bob", "alice", chain) } })
        assertFalse("the same one, once more", b.acceptGroupKey(group, "alice", chain, damaged))

        // Still looked for — and what the sender hands over next is taken in and opens.
        assertEquals(listOf(GroupKeyRef("alice", chain)), b.missingGroupKeys(group, page))
        val two = send(hub, alice, "two")!!
        assertEquals("two", read(hub, bob, two, b))
        assertEquals(1, hub.askedAgain)
    }

    @Test
    fun aChainThatCameAfterAllIsNotAskedAbout() {
        // The envelope arrived damaged once and whole the next time. The chain is held: there
        // is nothing to tell the sender, who would otherwise seal it all over again.
        val (hub, phones) = family()
        val (alice, bob) = phones
        val one = send(hub, alice, "one")!!
        val chain = E2eeGroup.chainId(one.envelope)!!
        val whole = hub.keyFor("bob", "alice", chain)!!
        hub.damageKey("bob", "alice", chain)

        val b = bob.client(hub)
        var asked = 0
        assertFalse(b.acceptGroupKey(group, "alice", chain, hub.keyFor("bob", "alice", chain)!!))
        assertTrue(b.acceptGroupKey(group, "alice", chain, whole))
        assertFalse(runBlocking { b.askGroupKeyAgain(group, "alice", chain) { asked++ } })
        assertEquals(0, asked)
        assertEquals("one", b.revealGroup(group, "alice", one.id, one.clientId, mine = false, envelope = one.envelope)?.body)
    }

    @Test
    fun anAbsentChainIsAskedForOnceForEachNewMessageNotOnEveryPage() {
        val (hub, phones) = family()
        val (alice) = phones
        val a = alice.client(hub)
        val early = (1..4).map { send(hub, alice, "early $it", a)!! }
        val chain = E2eeGroup.chainId(early[0].envelope)!!
        val dan = hub.join(Phone("dan"))
        val d = dan.client(hub)
        fun page(vararg rows: Row) = rows.map { GroupRow("alice", it.envelope) }

        // The first page: asked for, and the server has nothing for Dan.
        assertEquals(listOf(GroupKeyRef("alice", chain)), d.missingGroupKeys(group, page(early[0])))
        d.noGroupKey(group, "alice", chain)
        assertEquals(emptyList<GroupKeyRef>(), d.missingGroupKeys(group, page(early[0])))
        // A page with later messages of the chain: asked once more, since the chain may have
        // been handed on in the meantime…
        assertEquals(listOf(GroupKeyRef("alice", chain)), d.missingGroupKeys(group, page(early[1], early[2])))
        d.noGroupKey(group, "alice", chain)
        // …and then not again for that page, nor for anything before it,
        assertEquals(emptyList<GroupKeyRef>(), d.missingGroupKeys(group, page(early[1], early[2])))
        assertEquals(emptyList<GroupKeyRef>(), d.missingGroupKeys(group, page(early[0], early[2])))
        // but for the message after it.
        assertEquals(listOf(GroupKeyRef("alice", chain)), d.missingGroupKeys(group, page(early[3])))
    }

    @Test
    fun aMemberWhoAskedAgainIsHandedAReplacedChainOnANewSessionAsWell() {
        val (hub, phones) = family()
        val (alice, bob, carol) = phones
        val one = send(hub, alice, "one")!!
        assertEquals("one", read(hub, bob, one))
        assertEquals("from bob", read(hub, alice, send(hub, bob, "from bob")!!))
        alice.now += 31L * 24 * 60 * 60 * 1000
        val two = send(hub, alice, "two")!!
        assertNotNull(bob.vault.entries.remove("e2ee.session.alice"))
        val b = bob.client(hub)
        assertNull(read(hub, bob, two, b))
        assertEquals(1, hub.askedAgain)

        // Before Alice writes again somebody leaves, so her chain is replaced altogether. Bob is
        // handed the new one like everybody who stays — on a new session, since the old one failed.
        hub.members -= "carol"
        val fetches = hub.bundleFetches.get()
        val three = send(hub, alice, "three")!!
        assertNotEquals(E2eeGroup.chainId(two.envelope), E2eeGroup.chainId(three.envelope))
        assertEquals(fetches + 1, hub.bundleFetches.get())
        assertEquals("three", read(hub, bob, three, b))
        assertNull(read(hub, carol, three))
    }

    @Test
    fun aNewMemberWhoLookedAtTheHistoryFirstStillReadsWhatComesAfter() {
        val (hub, phones) = family()
        val (alice) = phones
        val a = alice.client(hub)
        val early = (1..3).map { send(hub, alice, "early $it", a)!! }

        // Dan joins and opens the group. Its history was sealed before he was in it: the
        // server has no chain for him, and his phone remembers that.
        val dan = hub.join(Phone("dan"))
        val d = dan.client(hub)
        for (row in early) assertNull("sent before dan was in the group", read(hub, dan, row, d))

        // Alice writes again, and her chain — the same one — is handed on to Dan. His app has
        // been running all along; it must look for the chain again and read the message.
        val late = send(hub, alice, "welcome dan", a)!!
        assertEquals(E2eeGroup.chainId(early[0].envelope), E2eeGroup.chainId(late.envelope))
        assertEquals("welcome dan", read(hub, dan, late, d))
        assertEquals("and the next", read(hub, dan, send(hub, alice, "and the next", a)!!, d))
        // What came before stays closed.
        for (row in early) assertNull(read(hub, dan, row, d))
    }
}
