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

    private fun text(value: ByteArray?) = value?.let { String(it, Charsets.UTF_8) }

    private fun say(session: Session, value: String): Envelope = E2eeProtocol.seal(session, bytes(value))

    private fun hear(session: Session, envelope: Envelope): String? = text(E2eeProtocol.open(session, envelope))

    /** Alice initiates towards Bob and Bob accepts her first message. */
    private fun established(alice: Party = Party("alice"), bob: Party = Party("bob")): Pair<Session, Session> {
        val aliceSession = E2eeProtocol.initiate(alice.identity, bob.bundle(), alice.id, bob.id)
        val first = say(aliceSession, "hello")
        val accepted = E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first, bob.id, alice.id)
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

        val bobSession = E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first, bob.id, alice.id)!!.session
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

        val accepted = E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, flight[2], bob.id, alice.id)
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
            assertNull(E2eeProtocol.open(alice, forged))
            assertArrayEquals("a rejected envelope must not change session state", before, alice.serialize())
        }
        assertEquals("genuine", hear(alice, genuine))
    }

    @Test
    fun forgedCountersCostBoundedWork() {
        val (alice, bob) = established()
        // Alice needs a receiving chain for the "previous chain length" bound to apply.
        assertEquals("first", hear(alice, say(bob, "first")))
        val genuine = say(bob, "genuine")
        val before = alice.serialize()
        val started = System.nanoTime()
        assertNull(E2eeProtocol.open(alice, genuine.copy(n = Int.MAX_VALUE)))
        assertNull(E2eeProtocol.open(alice, genuine.copy(n = 5_000_000)))
        assertNull(E2eeProtocol.open(alice, genuine.copy(dh = E2eeProtocol.generatePrekeyPair().public, n = 0, pn = Int.MAX_VALUE)))
        assertNull(E2eeProtocol.open(alice, genuine.copy(dh = E2eeProtocol.generatePrekeyPair().public, n = 5_000_000, pn = 0)))
        val millis = (System.nanoTime() - started) / 1_000_000
        // Unbounded, the 5,000,000 case alone is seconds of HMAC and Int.MAX_VALUE is most of an hour.
        assertTrue("forged counters cost $millis ms of key derivation", millis < 3_000)
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
    fun attackerControlledKeysNeverThrow() {
        val alice = Party("alice")
        val bob = Party("bob")
        val (aliceSession, bobSession) = established(alice, bob)
        val genuine = say(bobSession, "genuine")
        val garbage = ByteArray(E2eeProtocol.PUBLIC_KEY_BYTES) { 5 }
        val before = aliceSession.serialize()

        for (hostile in listOf(garbage, ByteArray(3), offCurve)) {
            assertNull(E2eeProtocol.open(aliceSession, genuine.copy(dh = hostile)))
        }
        assertArrayEquals(before, aliceSession.serialize())

        val first = say(E2eeProtocol.initiate(alice.identity, bob.bundle(), alice.id, bob.id), "hi")
        for (hostile in listOf(garbage, offCurve)) {
            assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first.copy(ik = hostile), bob.id, alice.id))
            assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first.copy(ek = hostile), bob.id, alice.id))
            assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first.copy(dh = hostile), bob.id, alice.id))
        }
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, garbage, first, bob.id, alice.id))
        // The genuine envelope still opens after all of that.
        assertNotNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first, bob.id, alice.id))
        // A handshake-less envelope can never establish a session.
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, null, genuine, bob.id, alice.id))
    }

    @Test
    fun handshakeIsBoundToBothAccountIds() {
        // Unknown-key-share: Mallory republishes Alice's bundle as her own. A
        // message Bob seals "to Mallory" must not open for Alice as if Bob had
        // written to her — the account ids are part of the session secret.
        val alice = Party("alice")
        val bob = Party("bob")
        val toMallory = say(E2eeProtocol.initiate(bob.identity, alice.bundle(), bob.id, "mallory"), "for mallory")
        assertNull(E2eeProtocol.openFirst(alice.identity, alice.prekey, alice.otk.private, toMallory, alice.id, bob.id))
        // Relayed under Mallory's account it still does not open.
        assertNull(E2eeProtocol.openFirst(alice.identity, alice.prekey, alice.otk.private, toMallory, alice.id, "mallory"))

        val toAlice = say(E2eeProtocol.initiate(bob.identity, alice.bundle(), bob.id, alice.id), "for alice")
        assertNull(E2eeProtocol.openFirst(alice.identity, alice.prekey, alice.otk.private, toAlice, alice.id, "mallory"))
        assertNotNull(E2eeProtocol.openFirst(alice.identity, alice.prekey, alice.otk.private, toAlice, alice.id, bob.id))
    }

    @Test
    fun oneTimePrekeyMustMatchTheHandshake() {
        val alice = Party("alice")
        val bob = Party("bob")
        val withOtk = say(E2eeProtocol.initiate(alice.identity, bob.bundle(), alice.id, bob.id), "hi")
        val other = E2eeProtocol.generateOneTimePrekey()
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, other.private, withOtk, bob.id, alice.id))
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, null, withOtk, bob.id, alice.id))

        // A bundle without one-time prekeys still works (three agreements).
        val without = say(E2eeProtocol.initiate(alice.identity, bob.bundle(withOneTimeKey = false), alice.id, bob.id), "no otk")
        assertNull(without.otk)
        assertTrue(without.carriesHandshake)
        assertNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, without, bob.id, alice.id))
        val accepted = E2eeProtocol.openFirst(bob.identity, bob.prekey, null, without, bob.id, alice.id)
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
        assertNull(E2eeProtocol.openFirst(bob.identity, rotated, bob.otk.private, first, bob.id, alice.id))
        assertNull(
            E2eeProtocol.openFirst(
                bob.identity, bob.prekey, bob.otk.private,
                first.copy(spk = E2eeProtocol.keyReference(rotated.public)), bob.id, alice.id,
            ),
        )
        assertNotNull(E2eeProtocol.openFirst(bob.identity, bob.prekey, bob.otk.private, first, bob.id, alice.id))
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
            assertArrayEquals(payload, E2eeProtocol.open(alice, E2eeProtocol.seal(bob, payload)))
        }
        // Trailing zero bytes are content, not padding.
        val zeros = ByteArray(40)
        assertArrayEquals(zeros, E2eeProtocol.open(alice, E2eeProtocol.seal(bob, zeros)))
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
}
