package com.nchat.app.network

import com.nchat.app.network.E2eeProtocol.Envelope
import com.nchat.app.network.E2eeProtocol.PeerBundle
import com.nchat.app.network.E2eeProtocol.Session
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.X509EncodedKeySpec

/**
 * Contract of the X3DH + Double Ratchet core (wire v1).
 *
 * Every test here is a property the wiring depends on: any first-flight
 * message can establish a session, out-of-order delivery works within and
 * across ratchet steps, a rejected envelope changes nothing and costs a
 * bounded amount of work, and nothing an attacker controls can make the
 * protocol throw.
 */
class E2eeProtocolTest {

    private class Party(val id: String) {
        val identity = E2eeProtocol.generateIdentity()
        val prekey = E2eeProtocol.generatePrekeyPair()
        val otk = E2eeProtocol.generateOneTimePrekey()

        fun bundle(withOneTimeKey: Boolean = true) = PeerBundle(
            identityPublic = identity.identityPublic,
            signedPrekeyPublic = prekey.public,
            signedPrekeySignature = E2eeProtocol.signPrekey(prekey.public, identity.identityPrivate),
            oneTimePrekeyPublic = if (withOneTimeKey) otk.public else null,
        )
    }

    /** A structurally perfect P-256 SubjectPublicKeyInfo whose point (x, y) is whatever the test says. */
    private fun spki(x: BigInteger, y: BigInteger): ByteArray {
        fun fixed(value: BigInteger): ByteArray {
            val raw = value.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()
            return ByteArray(32 - raw.size) + raw
        }
        return E2eeProtocol.SPKI_PREFIX + fixed(x) + fixed(y)
    }

    private val offCurve: ByteArray get() = spki(BigInteger.ONE, BigInteger.ONE)

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)

    /** The message id an envelope is bound to; most tests do not care which. */
    private val messageId = bytes("c-message")

    private fun text(value: ByteArray?) = value?.let { String(it, Charsets.UTF_8) }

    private fun say(session: Session, value: String): Envelope = E2eeProtocol.seal(session, bytes(value), messageId)

    private fun hear(session: Session, envelope: Envelope): String? = text(E2eeProtocol.open(session, envelope, messageId))

    /** Alice initiates towards Bob and Bob accepts her first message. */
    private fun established(alice: Party = Party("alice"), bob: Party = Party("bob")): Pair<Session, Session> {
        val aliceSession = E2eeProtocol.initiate(alice.identity, bob.bundle(), alice.id, bob.id)
        val first = say(aliceSession, "hello")
        val accepted = E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first, bob.id, alice.id, messageId)
        assertNotNull(accepted)
        assertEquals("hello", text(accepted!!.plaintext))
        return aliceSession to accepted.session
    }

    @Test
    fun handshakeEstablishesAndConversationSurvivesManyRatchetTurns() {
        val (alice, bob) = established()
        for (round in 0 until 6) {
            assertEquals("b$round", hear(alice, say(bob, "b$round")))
            assertEquals("b$round again", hear(alice, say(bob, "b$round again")))
            assertEquals("a$round", hear(bob, say(alice, "a$round")))
        }
    }

    @Test
    fun handshakeRidesOnEveryMessageUntilTheFirstReply() {
        val alice = Party("alice")
        val bob = Party("bob")
        val session = E2eeProtocol.initiate(alice.identity, bob.bundle(), alice.id, bob.id)
        val first = say(session, "one")
        val second = say(session, "two")
        for (envelope in listOf(first, second)) {
            assertTrue(envelope.carriesHandshake)
            assertEquals(E2eeProtocol.keyReference(bob.otk.public), envelope.otk)
            assertEquals(E2eeProtocol.keyReference(bob.prekey.public), envelope.spk)
            assertArrayEquals(alice.identity.identityPublic, envelope.ik)
        }

        val bobSession = E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first, bob.id, alice.id, messageId)!!.session
        assertEquals("reply", hear(session, say(bobSession, "reply")))

        val third = say(session, "three")
        assertFalse("the peer has the session — the handshake is no longer attached", third.carriesHandshake)
        assertNull(third.ik)
        assertNull(third.spk)
        assertNull(third.otk)
        assertFalse(third.toJson().contains("\"ik\""))
        // Bob's replies never carry a handshake: he did not initiate.
        assertFalse(say(bobSession, "again").carriesHandshake)
    }

    @Test
    fun anyFirstFlightMessageCanEstablishTheSession() {
        // The first message may be lost, retried or simply arrive later: the
        // session must come up from whichever message is processed first.
        val alice = Party("alice")
        val bob = Party("bob")
        val session = E2eeProtocol.initiate(alice.identity, bob.bundle(), alice.id, bob.id)
        val flight = (0 until 4).map { say(session, "m$it") }

        val accepted = E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, flight[2], bob.id, alice.id, messageId)
        assertNotNull(accepted)
        assertEquals("m2", text(accepted!!.plaintext))
        val bobSession = accepted.session
        assertEquals("m0", hear(bobSession, flight[0]))
        assertEquals("m3", hear(bobSession, flight[3]))
        assertEquals("m1", hear(bobSession, flight[1]))
        // …and each of them exactly once.
        flight.forEach { assertNull(hear(bobSession, it)) }
    }

    @Test
    fun outOfOrderMessagesOpenAcrossARatchetStep() {
        // The newest message is often processed first (the chat list previews
        // it before the conversation loads) — older messages of the previous
        // chain must still open afterwards.
        val (alice, bob) = established()
        val b0 = say(bob, "b0")
        val b1 = say(bob, "b1")
        val b2 = say(bob, "b2")
        assertEquals("b0", hear(alice, b0))
        assertEquals("a1", hear(bob, say(alice, "a1")))
        val c0 = say(bob, "c0")
        val c1 = say(bob, "c1")

        assertEquals("c1", hear(alice, c1))
        assertEquals("b2", hear(alice, b2))
        assertEquals("c0", hear(alice, c0))
        assertEquals("b1", hear(alice, b1))
        for (replayed in listOf(b0, b1, b2, c0, c1)) assertNull("replay must be rejected", hear(alice, replayed))
    }

    @Test
    fun rejectedEnvelopeLeavesTheSessionUntouched() {
        val (alice, bob) = established()
        val genuine = say(bob, "genuine")
        val before = alice.serialize()

        val flipped = genuine.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val forgeries = listOf(
            genuine.copy(ciphertext = flipped),
            genuine.copy(n = genuine.n + 1),
            genuine.copy(pn = genuine.pn + 1),
            genuine.copy(dh = E2eeProtocol.generatePrekeyPair().public),
        )
        for (forged in forgeries) {
            assertNull(E2eeProtocol.open(alice, forged, messageId))
            assertArrayEquals("a rejected envelope must not change session state", before, alice.serialize())
        }
        assertEquals("genuine", hear(alice, genuine))
    }

    @Test
    fun envelopeOpensOnlyUnderTheMessageIdItWasSealedFor() {
        // The server stores envelopes in rows it controls. Moving one into
        // another row, or presenting an old one as the edit of another
        // message, must fail — and must not spend the key, so the envelope
        // still opens in the row it belongs to.
        val alice = Party("alice")
        val bob = Party("bob")
        val session = E2eeProtocol.initiate(alice.identity, bob.bundle(), alice.id, bob.id)
        val first = E2eeProtocol.seal(session, bytes("first"), bytes("c-1"))
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first, bob.id, alice.id, bytes("c-2")))
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first, bob.id, alice.id, ByteArray(0)))
        val bobSession = E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first, bob.id, alice.id, bytes("c-1"))!!.session

        val second = E2eeProtocol.seal(session, bytes("second"), bytes("c-2"))
        val third = E2eeProtocol.seal(session, bytes("third"), bytes("c-3"))
        val before = bobSession.serialize()
        for (wrong in listOf("c-3", "c-1", "", "c-2 ", "C-2")) {
            assertNull(E2eeProtocol.open(bobSession, second, bytes(wrong)))
            assertArrayEquals("a mis-slotted envelope must not change session state", before, bobSession.serialize())
        }
        // Out of order: the third message opens first, so the second is opened from a stored key.
        assertEquals("third", text(E2eeProtocol.open(bobSession, third, bytes("c-3"))))
        val stashed = bobSession.serialize()
        assertNull(E2eeProtocol.open(bobSession, second, bytes("c-3")))
        assertArrayEquals("…and a wrong id must not use that stored key up", stashed, bobSession.serialize())
        assertEquals("second", text(E2eeProtocol.open(bobSession, second, bytes("c-2"))))
    }

    @Test
    fun forgedCountersCostBoundedWork() {
        val (alice, bob) = established()
        // Alice needs a receiving chain for the "previous chain length" bound to apply.
        assertEquals("first", hear(alice, say(bob, "first")))
        val genuine = say(bob, "genuine")
        val before = alice.serialize()
        val started = System.nanoTime()
        assertNull(E2eeProtocol.open(alice, genuine.copy(n = Int.MAX_VALUE), messageId))
        assertNull(E2eeProtocol.open(alice, genuine.copy(n = 5_000_000), messageId))
        assertNull(E2eeProtocol.open(alice, genuine.copy(dh = E2eeProtocol.generatePrekeyPair().public, n = 0, pn = Int.MAX_VALUE), messageId))
        assertNull(E2eeProtocol.open(alice, genuine.copy(dh = E2eeProtocol.generatePrekeyPair().public, n = 5_000_000, pn = 0), messageId))
        val millis = (System.nanoTime() - started) / 1_000_000
        // Unbounded, the 5,000,000 case alone is seconds of HMAC and Int.MAX_VALUE is most of an hour.
        assertTrue("forged counters cost $millis ms of key derivation", millis < 3_000)
        assertArrayEquals(before, alice.serialize())

        // The largest jump that IS allowed, forged twenty times over (a page of history from a
        // hostile server): each costs the full derivation and still changes nothing.
        //
        // What that derivation may cost is known — one chain step for every message passed
        // over — and is measured here, on the machine the test runs on, instead of being
        // written down in seconds. A number of seconds says how fast the machine is: 8 s held
        // on a desktop and on an Android 14 emulator, and an Android 7 emulator, which needs
        // about 28 µs for a step, took 11 s for exactly the same, bounded, work.
        val steps = 40 * 10_000
        val stepping = System.nanoTime()
        E2eeProtocol.advance(ByteArray(32) { 7 }, steps)
        val steppingMillis = (System.nanoTime() - stepping) / 1_000_000
        val allowed = System.nanoTime()
        repeat(20) {
            assertNull(E2eeProtocol.open(alice, genuine.copy(n = genuine.n + 10_000), messageId))
            assertNull(E2eeProtocol.open(alice, genuine.copy(dh = E2eeProtocol.generatePrekeyPair().public, n = 10_000, pn = 0), messageId))
        }
        val allowedMillis = (System.nanoTime() - allowed) / 1_000_000
        assertTrue(
            "forty forged jumps of the largest allowed size cost $allowedMillis ms; the $steps chain steps they may take cost $steppingMillis ms here",
            allowedMillis < 2 * steppingMillis + 2_000
        )
        assertArrayEquals(before, alice.serialize())
        assertEquals("genuine", hear(alice, genuine))
    }

    @Test
    fun largeLegitimateGapStillOpens() {
        val (alice, bob) = established()
        val sent = (0 until 300).map { say(bob, "m$it") }
        assertEquals("m299", hear(alice, sent[299]))
        assertEquals("m0", hear(alice, sent[0]))
        assertEquals("m150", hear(alice, sent[150]))
    }

    @Test
    fun aLongAbsenceIsReadNewestFirstAPageAtATime() {
        // How the app meets history: the chat list shows the newest message,
        // opening the chat loads the newest page, scrolling back loads older
        // pages. Nothing arrives in order and most of it arrives late.
        val (alice, bob) = established()
        assertEquals("before", hear(alice, say(bob, "before")))
        val sent = (0 until 3_500).map { say(bob, "m$it") }
        var session = alice

        assertEquals("the preview in the chat list", "m3499", hear(session, sent[3499]))
        // What is remembered about 3,499 messages that have not arrived is a few bytes, not a key each.
        assertTrue("the session grew to ${session.serialize().size} bytes", session.serialize().size < 1_500)

        // Alice answers: Bob's next messages are a new chain, and the old one stays readable.
        val reply = say(session, "back")
        assertEquals("back", hear(bob, reply))
        val later = (0 until 150).map { say(bob, "later$it") }
        assertEquals("later149", hear(session, later[149]))

        val opened = HashSet<Int>()
        for (page in 34 downTo 0) {
            // The app may be restarted between any two pages.
            if (page % 7 == 0) session = E2eeProtocol.deserialize(session.serialize())!!
            for (index in page * 100 until minOf(page * 100 + 100, 3_499)) {
                assertEquals("m$index", hear(session, sent[index]))
                opened += index
            }
            assertTrue("the session grew to ${session.serialize().size} bytes", session.serialize().size < 1_500)
        }
        assertEquals(3_499, opened.size)
        for (index in 148 downTo 0) assertEquals("later$index", hear(session, later[index]))

        // Each of them opened exactly once.
        for (index in listOf(0, 1, 99, 100, 1_750, 3_498, 3_499)) assertNull("replay of m$index", hear(session, sent[index]))
        for (index in listOf(0, 75, 149)) assertNull("replay of later$index", hear(session, later[index]))
        // …and the conversation simply goes on.
        assertEquals("caught up", hear(bob, say(session, "caught up")))
        assertEquals("good", hear(session, say(bob, "good")))
    }

    @Test
    fun aMessagePassedOverOpensOnceAndOnlyAsItself() {
        val (alice, bob) = established()
        val sent = (0 until 12).map { E2eeProtocol.seal(bob, bytes("m$it"), bytes("id-$it")) }
        assertEquals("m11", text(E2eeProtocol.open(alice, sent[11], bytes("id-11"))))
        val before = alice.serialize()

        // Inside the gap, nothing but the genuine envelope under its own id opens — and a failed try uses nothing up.
        val genuine = sent[5]
        val flipped = genuine.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        for (forged in listOf(genuine.copy(ciphertext = flipped), genuine.copy(n = 6), genuine.copy(n = 4), genuine.copy(pn = genuine.pn + 1))) {
            assertNull(E2eeProtocol.open(alice, forged, bytes("id-5")))
            assertArrayEquals(before, alice.serialize())
        }
        assertNull(E2eeProtocol.open(alice, genuine, bytes("id-6")))
        assertArrayEquals(before, alice.serialize())

        assertEquals("m5", text(E2eeProtocol.open(alice, genuine, bytes("id-5"))))
        assertNull("its key is gone with it", E2eeProtocol.open(alice, genuine, bytes("id-5")))
        // Its neighbours are untouched, on both sides, after a restart too.
        val restored = E2eeProtocol.deserialize(alice.serialize())!!
        for (index in listOf(4, 6, 0, 10)) assertEquals("m$index", text(E2eeProtocol.open(restored, sent[index], bytes("id-$index"))))
        for (index in listOf(4, 6, 0, 10, 5, 11)) assertNull(E2eeProtocol.open(restored, sent[index], bytes("id-$index")))
    }

    @Test
    fun theLongestJumpIsTenThousandMessages() {
        val (alice, bob) = established()
        assertEquals("first", hear(alice, say(bob, "first")))
        val sent = (0..10_001).map { say(bob, "m$it") }
        val before = alice.serialize()
        assertNull("10,001 messages ahead is refused", hear(alice, sent[10_001]))
        assertArrayEquals(before, alice.serialize())
        assertEquals("m10000", hear(alice, sent[10_000]))
        assertEquals("m10001", hear(alice, sent[10_001]))
        assertEquals("m0", hear(alice, sent[0]))
        assertEquals("m9999", hear(alice, sent[9_999]))
        assertEquals("m5000", hear(alice, sent[5_000]))
    }

    @Test
    fun gapsAreBoundedAndTheOldestGoFirst() {
        // Reading every other message leaves a gap per message: the worst pattern there is.
        val (alice, bob) = established()
        val sent = (0 until 700).map { say(bob, "m$it") }
        for (index in 1 until 700 step 2) assertEquals("m$index", hear(alice, sent[index]))
        assertTrue("the session grew to ${alice.serialize().size} bytes", alice.serialize().size < 45_000)

        val restored = E2eeProtocol.deserialize(alice.serialize())!!
        val stillOpen = (0 until 700 step 2).filter { hear(restored, sent[it]) != null }
        assertEquals("the newest 256 gaps are kept", 256, stillOpen.size)
        assertEquals((188 until 700 step 2).toList(), stillOpen)
    }

    @Test
    fun attackerControlledKeysNeverThrow() {
        val alice = Party("alice")
        val bob = Party("bob")
        val (aliceSession, bobSession) = established(alice, bob)
        val genuine = say(bobSession, "genuine")
        val garbage = ByteArray(E2eeProtocol.PUBLIC_KEY_BYTES) { 5 }
        val before = aliceSession.serialize()

        for (hostile in listOf(garbage, ByteArray(3), offCurve)) {
            assertNull(E2eeProtocol.open(aliceSession, genuine.copy(dh = hostile), messageId))
        }
        assertArrayEquals(before, aliceSession.serialize())

        val first = say(E2eeProtocol.initiate(alice.identity, bob.bundle(), alice.id, bob.id), "hi")
        for (hostile in listOf(garbage, offCurve)) {
            assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first.copy(ik = hostile), bob.id, alice.id, messageId))
            assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first.copy(ek = hostile), bob.id, alice.id, messageId))
            assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first.copy(dh = hostile), bob.id, alice.id, messageId))
        }
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, garbage, first, bob.id, alice.id, messageId))
        // The genuine envelope still opens after all of that.
        assertNotNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first, bob.id, alice.id, messageId))
        // A handshake-less envelope can never establish a session.
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, null, genuine, bob.id, alice.id, messageId))
    }

    @Test
    fun handshakeIsBoundToBothAccountIds() {
        // Unknown-key-share: Mallory republishes Alice's bundle as her own. A
        // message Bob seals "to Mallory" must not open for Alice as if Bob had
        // written to her — the account ids are part of the session secret.
        val alice = Party("alice")
        val bob = Party("bob")
        val toMallory = say(E2eeProtocol.initiate(bob.identity, alice.bundle(), bob.id, "mallory"), "for mallory")
        assertNull(E2eeProtocol.openFirst(alice.identity, alice.prekey, alice.otk.private, toMallory, alice.id, bob.id, messageId))
        // Relayed under Mallory's account it still does not open.
        assertNull(E2eeProtocol.openFirst(alice.identity, alice.prekey, alice.otk.private, toMallory, alice.id, "mallory", messageId))

        val toAlice = say(E2eeProtocol.initiate(bob.identity, alice.bundle(), bob.id, alice.id), "for alice")
        assertNull(E2eeProtocol.openFirst(alice.identity, alice.prekey, alice.otk.private, toAlice, alice.id, "mallory", messageId))
        assertNotNull(E2eeProtocol.openFirst(alice.identity, alice.prekey, alice.otk.private, toAlice, alice.id, bob.id, messageId))
    }

    @Test
    fun oneTimePrekeyMustMatchTheHandshake() {
        val alice = Party("alice")
        val bob = Party("bob")
        val withOtk = say(E2eeProtocol.initiate(alice.identity, bob.bundle(), alice.id, bob.id), "hi")
        val other = E2eeProtocol.generateOneTimePrekey()
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, other.private, withOtk, bob.id, alice.id, messageId))
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, null, withOtk, bob.id, alice.id, messageId))

        // A bundle without one-time prekeys still works (three agreements).
        val without = say(E2eeProtocol.initiate(alice.identity, bob.bundle(withOneTimeKey = false), alice.id, bob.id), "no otk")
        assertNull(without.otk)
        assertTrue(without.carriesHandshake)
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, without, bob.id, alice.id, messageId))
        val accepted = E2eeProtocol.openFirst(bob.identity, bob.prekey, null, without, bob.id, alice.id, messageId)
        assertEquals("no otk", text(accepted?.plaintext))
    }

    @Test
    fun handshakeNamesTheSignedPrekeyItWasBuiltFor() {
        // After a prekey rotation the recipient must be able to tell, without
        // any trial decryption, that a handshake targets a different prekey.
        val alice = Party("alice")
        val bob = Party("bob")
        val first = say(E2eeProtocol.initiate(alice.identity, bob.bundle(), alice.id, bob.id), "hi")
        val rotated = E2eeProtocol.generatePrekeyPair()
        assertNull(E2eeProtocol.openFirst(bob.identity, rotated, bob.otk.private, first, bob.id, alice.id, messageId))
        assertNull(
            E2eeProtocol.openFirst(
                bob.identity, bob.prekey, bob.otk.private,
                first.copy(spk = E2eeProtocol.keyReference(rotated.public)), bob.id, alice.id, messageId,
            ),
        )
        assertNotNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first, bob.id, alice.id, messageId))
    }

    @Test
    fun publicKeyValidationMatchesThePlatformCurve() {
        // The hand-written constants are checked against the provider's own P-256.
        val sample = E2eeProtocol.generatePrekeyPair().public
        val platform = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(sample)) as ECPublicKey
        val curve = platform.params.curve
        assertEquals(E2eeProtocol.CURVE_P, (curve.field as ECFieldFp).p)
        assertEquals(E2eeProtocol.CURVE_B, curve.b)
        assertEquals(E2eeProtocol.CURVE_P.subtract(BigInteger.valueOf(3)), curve.a)

        repeat(25) {
            val key = E2eeProtocol.generatePrekeyPair().public
            assertEquals(E2eeProtocol.PUBLIC_KEY_BYTES, key.size)
            assertArrayEquals(E2eeProtocol.SPKI_PREFIX, key.copyOf(E2eeProtocol.SPKI_PREFIX.size))
            assertTrue(E2eeProtocol.isValidPublicKey(key))
        }

        val x = platform.w.affineX
        val y = platform.w.affineY
        assertTrue(E2eeProtocol.isValidPublicKey(spki(x, y)))
        // The mirrored point (x, p − y) is on the curve too; anything else with this x is not.
        assertTrue(E2eeProtocol.isValidPublicKey(spki(x, E2eeProtocol.CURVE_P.subtract(y))))
        assertFalse(E2eeProtocol.isValidPublicKey(spki(x, y.add(BigInteger.ONE).mod(E2eeProtocol.CURVE_P))))
        assertFalse(E2eeProtocol.isValidPublicKey(offCurve))
        assertFalse(E2eeProtocol.isValidPublicKey(spki(BigInteger.ZERO, BigInteger.ZERO)))
        // Coordinates must be reduced: x + p names the same residue but is not canonical.
        val unreduced = E2eeProtocol.SPKI_PREFIX + ByteArray(32) { 0xff.toByte() } + ByteArray(32) { 0xff.toByte() }
        assertFalse(E2eeProtocol.isValidPublicKey(unreduced))
        assertFalse(E2eeProtocol.isValidPublicKey(sample.copyOf(90)))
        assertFalse(E2eeProtocol.isValidPublicKey(sample + byteArrayOf(0)))
        assertFalse(E2eeProtocol.isValidPublicKey(sample.copyOf().also { it[26] = 0x02 }))
        assertFalse(E2eeProtocol.isValidPublicKey(sample.copyOf().also { it[10] = (it[10] + 1).toByte() }))
    }

    @Test
    fun forgedOrInconsistentBundlesAreRefused() {
        val alice = Party("alice")
        val bob = Party("bob")
        val mallory = E2eeProtocol.generateIdentity()
        val bundles = listOf(
            bob.bundle().copy(signedPrekeySignature = ByteArray(64) { 7 }),
            bob.bundle().copy(identityPublic = mallory.identityPublic),
            bob.bundle().copy(signedPrekeyPublic = E2eeProtocol.generatePrekeyPair().public),
            bob.bundle().copy(oneTimePrekeyPublic = ByteArray(12)),
            bob.bundle().copy(oneTimePrekeyPublic = offCurve),
            bob.bundle().copy(identityPublic = ByteArray(40)),
            bob.bundle().copy(identityPublic = offCurve),
            bob.bundle().copy(signedPrekeyPublic = offCurve),
        )
        for (bundle in bundles) {
            try {
                E2eeProtocol.initiate(alice.identity, bundle, alice.id, bob.id)
                fail("a bundle that does not verify must throw — never a silent downgrade")
            } catch (expected: IllegalStateException) {
                // intended
            }
        }
    }

    @Test
    fun sessionsSurviveSerializationAtEveryStep() {
        var (alice, bob) = established()
        fun reload(session: Session): Session {
            val restored = E2eeProtocol.deserialize(session.serialize())
            assertNotNull(restored)
            assertArrayEquals(session.serialize(), restored!!.serialize())
            return restored
        }
        for (round in 0 until 3) {
            alice = reload(alice)
            bob = reload(bob)
            val late = say(bob, "late $round")
            bob = reload(bob)
            val reply = say(bob, "b$round")
            alice = reload(alice)
            assertEquals("b$round", hear(alice, reply))
            alice = reload(alice)
            assertEquals("late $round", hear(alice, late))
            alice = reload(alice)
            val message = say(alice, "a$round")
            bob = reload(bob)
            assertEquals("a$round", hear(bob, message))
        }
    }

    @Test
    fun reloadedSessionKeepsItsRatchetKey() {
        // No new remote key arrived, so consecutive seals stay on one chain —
        // also after the session went through storage.
        val (_, bob) = established()
        val first = say(bob, "r0")
        val second = say(E2eeProtocol.deserialize(bob.serialize())!!, "r1")
        assertArrayEquals(first.dh, second.dh)
        assertEquals(first.n + 1, second.n)
    }

    @Test
    fun corruptOrForeignSessionBytesFailClosed() {
        val (alice, _) = established()
        val blob = alice.serialize()
        assertNull(E2eeProtocol.deserialize(ByteArray(0)))
        assertNull(E2eeProtocol.deserialize(blob.copyOf(blob.size - 1)))
        assertNull(E2eeProtocol.deserialize(blob + byteArrayOf(0)))
        for (index in listOf(0, 5, 40, blob.size / 2, blob.size - 1)) {
            val damaged = blob.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertNull("damage at $index must be detected", E2eeProtocol.deserialize(damaged))
        }
    }

    @Test
    fun envelopeJsonRoundTripsAndParsesStrictly() {
        val alice = Party("alice")
        val bob = Party("bob")
        val session = E2eeProtocol.initiate(alice.identity, bob.bundle(), alice.id, bob.id)
        val handshake = say(session, "hi")
        val json = handshake.toJson()
        val parsed = Envelope.fromJson(json)
        assertNotNull(parsed)
        assertEquals(json, parsed!!.toJson())
        assertEquals(E2eeProtocol.keyReference(bob.otk.public), parsed.otk)
        assertEquals(E2eeProtocol.keyReference(bob.prekey.public), parsed.spk)
        assertArrayEquals(handshake.ciphertext, parsed.ciphertext)

        val (_, bobSession) = established(alice, bob)
        val plain = say(bobSession, "plain").toJson()
        assertEquals(plain, Envelope.fromJson(plain)!!.toJson())

        val key = encodeBase64(handshake.dh)
        val ct = encodeBase64(handshake.ciphertext)
        val ref = E2eeProtocol.keyReference(bob.prekey.public)
        val rejected = listOf(
            "",
            "not json",
            "[]",
            """{"v":2,"dh":"$key","n":0,"pn":0,"ct":"$ct"}""",
            """{"v":"1","dh":"$key","n":0,"pn":0,"ct":"$ct"}""",
            """{"v":1,"dh":"$key","n":"0","pn":0,"ct":"$ct"}""",
            """{"v":1,"dh":"$key","n":-1,"pn":0,"ct":"$ct"}""",
            """{"v":1,"dh":"$key","n":0,"pn":-4,"ct":"$ct"}""",
            """{"v":1,"dh":"$key","n":0,"pn":0}""",
            """{"v":1,"dh":"$key","n":0,"pn":0,"ct":"AAAA"}""",
            """{"v":1,"dh":"AAAA","n":0,"pn":0,"ct":"$ct"}""",
            """{"v":1,"dh":"$key","n":4294967296,"pn":0,"ct":"$ct"}""",
            """{"v":1,"ik":"$key","dh":"$key","n":0,"pn":0,"ct":"$ct"}""",
            """{"v":1,"ek":"$key","dh":"$key","n":0,"pn":0,"ct":"$ct"}""",
            """{"v":1,"ik":"$key","ek":"$key","dh":"$key","n":0,"pn":0,"ct":"$ct"}""",
            """{"v":1,"spk":"$ref","dh":"$key","n":0,"pn":0,"ct":"$ct"}""",
            """{"v":1,"otk":"$ref","dh":"$key","n":0,"pn":0,"ct":"$ct"}""",
            """{"v":1,"ik":"$key","ek":"AAAA","spk":"$ref","dh":"$key","n":0,"pn":0,"ct":"$ct"}""",
            """{"v":1,"ik":"$key","ek":"$key","spk":"short","dh":"$key","n":0,"pn":0,"ct":"$ct"}""",
            """{"v":1,"ik":"$key","ek":"$key","spk":"$ref","otk":3,"dh":"$key","n":0,"pn":0,"ct":"$ct"}""",
            """{"v":1,"ik":"$key","ek":"$key","spk":"$ref","otk":"","dh":"$key","n":0,"pn":0,"ct":"$ct"}""",
        )
        // The same handshake with everything in place does parse.
        assertNotNull(Envelope.fromJson("""{"v":1,"ik":"$key","ek":"$key","spk":"$ref","otk":"$ref","dh":"$key","n":0,"pn":0,"ct":"$ct"}"""))
        for (candidate in rejected) assertNull("must be rejected: $candidate", Envelope.fromJson(candidate))
    }

    @Test
    fun ciphertextLengthHidesThePlaintextLength() {
        val (alice, bob) = established()
        val short = say(bob, "k")
        val longer = say(bob, "see you at the usual place at nine, bring the documents")
        assertEquals(short.ciphertext.size, longer.ciphertext.size)
        assertEquals("k", hear(alice, short))

        // Boundaries: empty, exactly one block, one byte over.
        for (size in listOf(0, 1, 127, 128, 129, 5000)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            assertArrayEquals(payload, E2eeProtocol.open(alice, E2eeProtocol.seal(bob, payload, messageId), messageId))
        }
        // Trailing zero bytes are content, not padding.
        val zeros = ByteArray(40)
        assertArrayEquals(zeros, E2eeProtocol.open(alice, E2eeProtocol.seal(bob, zeros, messageId), messageId))
    }

    @Test
    fun restoredStateNeverReusesANonce() {
        // A device that falls back to an older copy of its state seals with
        // the same message key again; the random nonce keeps GCM safe.
        val (_, bob) = established()
        val snapshot = bob.serialize()
        val first = say(E2eeProtocol.deserialize(snapshot)!!, "one")
        val second = say(E2eeProtocol.deserialize(snapshot)!!, "one")
        assertEquals(first.n, second.n)
        assertFalse(first.ciphertext.copyOf(12).contentEquals(second.ciphertext.copyOf(12)))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun sessionPinsThePeerIdentity() {
        val alice = Party("alice")
        val bob = Party("bob")
        val (aliceSession, bobSession) = established(alice, bob)
        assertArrayEquals(bob.identity.identityPublic, aliceSession.remoteIdentityKey())
        assertArrayEquals(alice.identity.identityPublic, bobSession.remoteIdentityKey())
    }

    @Test
    fun hkdfMatchesRfc5869CaseOne() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = E2eeProtocol.hkdf(ikm, salt, info, 42)
        val hex = okm.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex)
    }

    // ------------------------------------- the signed prekey, once it was used

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { at -> needle.indices.all { haystack[at + it] == needle[it] } }

    @Test
    fun aSessionKeepsNothingOfTheSignedPrekeyItBeganWith() {
        // A phone replaces its signed prekey every week and deletes the old one a month later. Deleting
        // it means something only if no session goes on carrying its private half.
        val alice = Party("alice")
        val bob = Party("bob")
        val aliceSession = E2eeProtocol.initiate(alice.identity, bob.bundle(withOneTimeKey = false), alice.id, bob.id)
        val accepted = E2eeProtocol.openFirst(bob.identity, bob.prekey, null, say(aliceSession, "hello"), bob.id, alice.id, messageId)!!
        val stored = accepted.session.serialize()
        assertFalse("the stored session holds the private half of the signed prekey", contains(stored, bob.prekey.private))
        // The search finds what is there: the ratchet key that took its place.
        assertTrue(contains(stored, accepted.session.ourRatchetPrivate))
        assertFalse(accepted.session.ourRatchetPrivate.contentEquals(bob.prekey.private))

        // And the conversation does without it: both directions, and from storage.
        var bobSession = E2eeProtocol.deserialize(stored)!!
        assertEquals("more", hear(bobSession, say(aliceSession, "more")))
        assertEquals("reply", hear(aliceSession, say(bobSession, "reply")))
        bobSession = E2eeProtocol.deserialize(bobSession.serialize())!!
        assertEquals("again", hear(bobSession, say(aliceSession, "again")))
        assertFalse(contains(bobSession.serialize(), bob.prekey.private))
    }
}
