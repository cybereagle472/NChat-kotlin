package com.nchat.app.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group messages. Every member seals what they write to a group with a chain
 * of their own, and hands the chain to the other members over the one-to-one
 * sessions (that part is E2eeClient's). These tests are about the chain: a
 * message opens for whoever holds the chain, once, in any order; nobody else
 * can open it; and holding a chain is not enough to write under it.
 */
class E2eeGroupTest {
    private val group = "group-1"
    private val alice = "alice"

    private fun text(value: String) = value.toByteArray(Charsets.UTF_8)

    private fun target(id: String) = "m:$id".toByteArray(Charsets.UTF_8)

    /** A member who was handed [chain] as it stands now. */
    private fun member(chain: E2eeGroup.OwnChain): E2eeGroup.Chain = E2eeGroup.accept(E2eeGroup.distribution(chain))!!

    private fun send(chain: E2eeGroup.OwnChain, id: String, body: String): String =
        E2eeGroup.seal(chain, group, alice, text(body), target(id))

    private fun read(chain: E2eeGroup.Chain, id: String, envelope: String): String? =
        E2eeGroup.open(chain, group, alice, envelope, target(id))?.let { String(it, Charsets.UTF_8) }

    private fun field(envelope: String, name: String): String =
        (Json.parseToJsonElement(envelope).jsonObject[name] as JsonPrimitive).content

    private fun with(envelope: String, name: String, value: String, quoted: Boolean = true): String {
        val old = field(envelope, name)
        val shown = if (quoted) "\"$old\"" else old
        val replaced = if (quoted) "\"$value\"" else value
        assertTrue(envelope.contains("\"$name\":$shown"))
        return envelope.replace("\"$name\":$shown", "\"$name\":$replaced")
    }

    // ---------------------------------------------------------------- basics

    @Test
    fun whatAMemberSealsEveryHolderOfTheChainOpens() {
        val chain = E2eeGroup.newChain(now = 1_000L)
        val bob = member(chain)
        val carol = member(chain)
        val first = send(chain, "c1", "hello group")
        val second = send(chain, "c2", "and again")

        assertEquals("hello group", read(bob, "c1", first))
        assertEquals("and again", read(bob, "c2", second))
        assertEquals("one sealing serves every member", "hello group", read(carol, "c1", first))
        assertEquals(2, chain.next)
        assertEquals(1_000L, chain.created)

        // What the server stores: which chain, which number, and nothing readable.
        assertEquals("1", field(first, "v"))
        assertEquals(chain.idText, field(first, "c"))
        assertEquals("0", field(first, "n"))
        assertEquals("1", field(second, "n"))
        assertEquals(chain.idText, E2eeGroup.chainId(first))
        assertEquals(E2eeGroup.CHAIN_ID_BYTES, decodeBase64(chain.idText)!!.size)
        assertFalse(first.contains("hello"))
        assertEquals("a signature of 64 bytes", 64, decodeBase64(field(first, "sig"))!!.size)
        assertEquals(setOf("v", "c", "n", "ct", "sig"), Json.parseToJsonElement(first).jsonObject.keys)
    }

    @Test
    fun theSameWordsSealedTwiceAreNotTheSameBytesAndHideTheirLength() {
        val chain = E2eeGroup.newChain(0L)
        val short = send(chain, "c1", "hi")
        val longer = send(chain, "c2", "a rather longer sentence than hi")
        assertEquals("anything up to the next 128 bytes looks alike", field(short, "ct").length, field(longer, "ct").length)
        val a = E2eeGroup.newChain(0L)
        val b = E2eeGroup.newChain(0L)
        assertNotEquals(a.idText, b.idText)
        // Every chain has a key of its own, made at random: what is handed over for one says nothing about another.
        val keyOfA = E2eeGroup.distribution(a).copyOfRange(21, 53)
        val keyOfB = E2eeGroup.distribution(b).copyOfRange(21, 53)
        assertFalse(keyOfA.contentEquals(keyOfB))
        assertFalse(keyOfA.all { it == 0.toByte() })
        assertNotEquals(field(send(a, "c1", "same"), "ct"), field(send(b, "c1", "same"), "ct"))
        assertFalse(a.signingPublic.contentEquals(b.signingPublic))
    }

    @Test
    fun messagesOpenInAnyOrderAndEachOnlyOnce() {
        val chain = E2eeGroup.newChain(0L)
        val bob = member(chain)
        val sent = (0 until 12).map { send(chain, "c$it", "message $it") }
        // The newest page first, as the app meets a conversation; then what came before.
        for (index in listOf(11, 10, 9, 3, 0, 5, 4, 8, 1, 2, 7, 6)) {
            assertEquals("message $index", read(bob, "c$index", sent[index]))
        }
        for (index in sent.indices) assertNull("a second time: $index", read(bob, "c$index", sent[index]))
        // And the chain goes on from where it is.
        assertEquals("next", read(bob, "c12", send(chain, "c12", "next")))
    }

    @Test
    fun whoIsHandedTheChainLaterReadsFromThereOnAndNothingBefore() {
        val chain = E2eeGroup.newChain(0L)
        val early = member(chain)
        val before = (0 until 3).map { send(chain, "c$it", "before $it") }
        val late = member(chain)
        val after = send(chain, "c3", "after")

        assertEquals("after", read(late, "c3", after))
        for (index in before.indices) assertNull("sent before they had the chain", read(late, "c$index", before[index]))
        assertEquals("before 1", read(early, "c1", before[1]))
        assertEquals("after", read(early, "c3", after))
    }

    // -------------------------------------------------- bound to its place

    @Test
    fun theGroupTheSenderAndTheMessageArePartOfTheSeal() {
        val chain = E2eeGroup.newChain(0L)
        val bob = member(chain)
        val envelope = send(chain, "c1", "for this group")

        assertNull("another group", E2eeGroup.open(bob, "group-2", alice, envelope, target("c1")))
        assertNull("another sender", E2eeGroup.open(bob, group, "mallory", envelope, target("c1")))
        assertNull("another message", E2eeGroup.open(bob, group, alice, envelope, target("c2")))
        assertNull("an edit of another message", E2eeGroup.open(bob, group, alice, envelope, "e:c1".toByteArray()))
        // Ids are not glued together: "group-1" + "alice" is not "group-" + "1alice".
        assertNull(E2eeGroup.open(bob, "group-", "1alice", envelope, target("c1")))
        // None of that used the message up.
        assertEquals("for this group", read(bob, "c1", envelope))
    }

    @Test
    fun holdingTheChainIsNotEnoughToWriteUnderIt() {
        val chain = E2eeGroup.newChain(0L)
        val bob = member(chain)
        val genuine = send(chain, "c1", "genuine")

        // Mallory is a member: she holds everything bob holds — the chain's id, its key, its
        // number. What she does not hold is the half of the signing key that never left alice's
        // phone, so she signs with one of her own.
        val handed = E2eeGroup.distribution(chain)
        val id = handed.copyOfRange(1, 1 + E2eeGroup.CHAIN_ID_BYTES)
        val number = handed.copyOfRange(17, 21).fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xff) }
        val chainKey = handed.copyOfRange(21, 53)
        assertEquals("version, id, number, chain key, signing key", 1 + 16 + 4 + 32 + E2eeProtocol.PUBLIC_KEY_BYTES, handed.size)
        assertEquals(1, number)
        val own = E2eeProtocol.generatePrekeyPair()
        val mallory = E2eeGroup.OwnChain(id, chainKey, number, own.private, own.public, created = 0L)
        val forgery = E2eeGroup.seal(mallory, group, alice, text("alice says: pay mallory"), target("c2"))
        assertNotNull("the test's forgery is well-formed", E2eeGroup.chainId(forgery))
        assertEquals(chain.idText, E2eeGroup.chainId(forgery))
        assertNull("not signed by the chain's owner", read(bob, "c2", forgery))
        // Nor with the genuine signature moved onto other content.
        assertNull(read(bob, "c2", with(forgery, "sig", field(genuine, "sig"))))
        // The real message still opens: the forgery cost nothing.
        assertEquals("genuine", read(bob, "c1", genuine))
        assertEquals("real second", read(bob, "c2", send(chain, "c2", "real second")))
    }

    @Test
    fun anyChangedPartIsRefusedAndChangesNothing() {
        val chain = E2eeGroup.newChain(0L)
        val bob = member(chain)
        val envelope = send(chain, "c1", "intact")
        val other = E2eeGroup.newChain(0L)

        fun flipped(base64: String, at: Int): String {
            val bytes = decodeBase64(base64)!!
            bytes[at] = (bytes[at].toInt() xor 1).toByte()
            return encodeBase64(bytes)
        }
        val damaged = listOf(
            with(envelope, "ct", flipped(field(envelope, "ct"), 0)),
            with(envelope, "ct", flipped(field(envelope, "ct"), 40)),
            with(envelope, "sig", flipped(field(envelope, "sig"), 0)),
            with(envelope, "sig", flipped(field(envelope, "sig"), 63)),
            with(envelope, "n", "1", quoted = false),
            with(envelope, "c", other.idText),
            with(envelope, "v", "2", quoted = false),
            with(envelope, "sig", encodeBase64(ByteArray(64))),
            with(envelope, "sig", encodeBase64(ByteArray(10))),
            with(envelope, "ct", ""),
            "{}", "", "not json", "[1,2]",
        )
        for (bad in damaged) assertNull(bad.take(60), read(bob, "c1", bad))
        assertEquals("intact", read(bob, "c1", envelope))
    }

    // ---------------------------------------------------------------- bounds

    @Test
    fun aMessageTooFarAheadIsRefusedWithoutBeingWorkedOn() {
        val chain = E2eeGroup.newChain(0L)
        val bob = member(chain)
        val sent = ArrayList<String>()
        repeat(E2eeGroup.MAX_SKIP + 20) { sent += send(chain, "c$it", "m$it") }

        assertNull("further ahead than a chain is ever used for", read(bob, "c${sent.size - 1}", sent.last()))
        assertEquals("nothing moved", "m0", read(bob, "c0", sent[0]))
        val reachable = E2eeGroup.MAX_SKIP
        assertEquals("m$reachable", read(bob, "c$reachable", sent[reachable]))
        assertTrue("a sender starts a new chain long before that", E2eeGroup.MAX_CHAIN_MESSAGES * 2 <= E2eeGroup.MAX_SKIP)
    }

    @Test
    fun runsOfUnreadMessagesAreKeptWithinALimitAndTheOldestGoFirst() {
        val chain = E2eeGroup.newChain(0L)
        val bob = member(chain)
        val sent = (0 until 620).map { send(chain, "c$it", "m$it") }
        // Every second message: more than three hundred runs of one unread message each.
        for (index in 1 until 620 step 2) assertEquals("m$index", read(bob, "c$index", sent[index]))

        assertNull("the oldest runs were let go", read(bob, "c0", sent[0]))
        assertNull(read(bob, "c40", sent[40]))
        assertEquals("the recent ones are all there", "m618", read(bob, "c618", sent[618]))
        assertEquals("m300", read(bob, "c300", sent[300]))
        // What is kept stays small however long the chain: a stored chain is a few kilobytes.
        assertTrue(bob.serialize().size < 16 * 1024)
    }

    // --------------------------------------------------------------- storage

    @Test
    fun chainsGoOnAfterBeingStored() {
        val chain = E2eeGroup.newChain(77L)
        val bob = member(chain)
        val sent = (0 until 6).map { send(chain, "c$it", "m$it") }
        assertEquals("m4", read(bob, "c4", sent[4]))

        val stored = E2eeGroup.OwnChain.deserialize(chain.serialize())!!
        assertEquals(chain.idText, stored.idText)
        assertEquals(6, stored.next)
        assertEquals(77L, stored.created)
        assertArrayEquals(chain.signingPublic, stored.signingPublic)
        val restored = E2eeGroup.Chain.deserialize(bob.serialize())!!
        assertEquals(chain.idText, restored.idText)

        // The run before message 4 is still open, message 4 is spent, and what comes next opens.
        assertEquals("m1", read(restored, "c1", sent[1]))
        assertNull(read(restored, "c4", sent[4]))
        assertEquals("m5", read(restored, "c5", sent[5]))
        assertEquals("later", read(restored, "c6", send(stored, "c6", "later")))

        // Stored bytes that are not a chain are not a chain.
        val bytes = chain.serialize()
        assertNull(E2eeGroup.OwnChain.deserialize(bytes.copyOf(bytes.size - 1)))
        assertNull(E2eeGroup.OwnChain.deserialize(bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2].toInt() xor 1).toByte() }))
        assertNull(E2eeGroup.OwnChain.deserialize(ByteArray(0)))
        val held = bob.serialize()
        assertNull(E2eeGroup.Chain.deserialize(held.copyOf(held.size - 1)))
        assertNull(E2eeGroup.Chain.deserialize(held.copyOf().also { it[held.size / 2] = (it[held.size / 2].toInt() xor 1).toByte() }))
        assertNull(E2eeGroup.Chain.deserialize(bytes))
    }

    // ---------------------------------------------------------- handing over

    @Test
    fun whatIsHandedOverIsCheckedBeforeItIsBelieved() {
        val chain = E2eeGroup.newChain(0L)
        val good = E2eeGroup.distribution(chain)
        assertNotNull(E2eeGroup.accept(good))
        assertEquals(chain.idText, E2eeGroup.accept(good)!!.idText)

        assertNull(E2eeGroup.accept(ByteArray(0)))
        assertNull(E2eeGroup.accept(good.copyOf(good.size - 1)))
        assertNull(E2eeGroup.accept(good + byteArrayOf(0)))
        assertNull("another version", E2eeGroup.accept(good.copyOf().also { it[0] = 9 }))
        // The key that messages are checked against must be a point on the curve.
        val offCurve = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(E2eeGroup.accept(offCurve))
        // A starting number no chain ever reaches.
        val start = 1 + E2eeGroup.CHAIN_ID_BYTES
        val farAhead = good.copyOf().also { it[start] = 0x7f }
        assertNull(E2eeGroup.accept(farAhead))
        val negative = good.copyOf().also { it[start] = 0x80.toByte() }
        assertNull(E2eeGroup.accept(negative))
    }

    @Test
    fun whatIsHandedOverHoldsNothingOfThePast() {
        val chain = E2eeGroup.newChain(0L)
        val start = E2eeGroup.distribution(chain)
        repeat(5) { send(chain, "c$it", "m$it") }
        val later = E2eeGroup.distribution(chain)
        assertFalse("the chain key moved on", start.contentEquals(later))
        assertEquals(start.size, later.size)
        // Handing the chain over does not use it up or change it.
        assertArrayEquals(later, E2eeGroup.distribution(chain))
        assertEquals(5, chain.next)
    }
}
