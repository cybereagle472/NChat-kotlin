package com.nchat.app.network

import com.nchat.app.network.E2eeProtocol.Envelope
import com.nchat.app.network.E2eeProtocol.PeerBundle
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Red-phase contract for the Double Ratchet core (Batch 3): X3DH in both
 * directions, ordered and out-of-order delivery, ratchet rotation across a
 * reply, tamper detection, envelope JSON, HKDF (RFC 5869), and session
 * serialization. Everything runs on the plain JVM — no Android APIs.
 */
class E2eeProtocolTest {

    /**
     * Android's platform ECDH class (javax.crypto.KeyAgreement) is a stub on
     * the JVM, so the tests swap in the JDK's java.security.KeyAgreement —
     * identical P-256 agreement, real implementation.
     */
    private object JvmEcdh : E2eeProtocol.EcdhEngine {
        override fun agree(privateKey: java.security.PrivateKey, publicKey: java.security.PublicKey): ByteArray {
            // java.security.KeyAgreement exists on the JDK at runtime but not
            // in android.jar, which is what unit tests compile against.
            val type = Class.forName("java.security.KeyAgreement")
            val agreement = type.getMethod("getInstance", String::class.java).invoke(null, "ECDH")
            type.getMethod("init", java.security.Key::class.java).invoke(agreement, privateKey)
            type.getMethod("doPhase", java.security.Key::class.java, Boolean::class.javaPrimitiveType)
                .invoke(agreement, publicKey, true)
            return type.getMethod("generateSecret").invoke(agreement) as ByteArray
        }
    }

    @Before
    fun useJvmEcdh() {
        E2eeProtocol.ecdhEngine = JvmEcdh
    }

    @After
    fun restorePlatformEcdh() {
        E2eeProtocol.ecdhEngine = E2eeProtocol.PlatformEcdh
    }

    private fun bytes(value: String) = value.toByteArray(StandardCharsets.UTF_8)

    @Test
    fun x3dhEstablishesAndRoundTripsAcrossARatchetRotation() {
        val alice = E2eeProtocol.generateIdentity()
        val bob = E2eeProtocol.generateIdentity()
        val bobPrekey = E2eeProtocol.generatePrekeyPair()
        val prekeySignature = E2eeProtocol.signPrekey(bobPrekey.public, bob.identityPrivate)
        val otk = E2eeProtocol.generateOneTimePrekey()

        val bundle = PeerBundle(
            identityPublic = bob.identityPublic,
            signedPrekeyPublic = bobPrekey.public,
            signedPrekeySignature = prekeySignature,
            oneTimePrekeyId = 7,
            oneTimePrekeyPublic = otk.public,
        )

        // Initiator: verifies the signed prekey against the identity, runs X3DH.
        val aliceSession = E2eeProtocol.initiate(alice, bundle)
        val first = E2eeProtocol.seal(aliceSession, bytes("hello"))
        assertEquals(1, E2eeProtocol.VERSION)
        assertEquals(7, first.otkId)

        // Responder: looks the one-time private half up by id, derives the same SK.
        val accepted = E2eeProtocol.openFirst(bob, bobPrekey, otkId = 7, oneTimePrekeyPrivate = otk.private, first)
        assertNotNull(accepted)
        assertArrayEquals(bytes("hello"), accepted!!.plaintext)
        val bobSession = accepted.session

        // Reply (Bob generates his ratchet key — first DH ratchet step).
        val reply = E2eeProtocol.seal(bobSession, bytes("hi alice"))
        val openedReply = E2eeProtocol.open(aliceSession, reply)
        assertNotNull(openedReply)
        assertArrayEquals(bytes("hi alice"), openedReply!!)

        // Alice sends again — her ratchet rotates on the new remote key.
        val third = E2eeProtocol.seal(aliceSession, bytes("again"))
        val openedThird = E2eeProtocol.open(bobSession, third)
        assertNotNull(openedThird)
        assertArrayEquals(bytes("again"), openedThird!!)

        // Only the FIRST envelope carries the one-time prekey id.
        assertNull(reply.otkId)
        assertNull(third.otkId)
    }

    @Test
    fun outOfOrderMessagesStillDecryptViaSkippedKeys() {
        val alice = E2eeProtocol.generateIdentity()
        val bob = E2eeProtocol.generateIdentity()
        val bobPrekey = E2eeProtocol.generatePrekeyPair()
        val otk = E2eeProtocol.generateOneTimePrekey()
        val bundle = PeerBundle(
            identityPublic = bob.identityPublic,
            signedPrekeyPublic = bobPrekey.public,
            signedPrekeySignature = E2eeProtocol.signPrekey(bobPrekey.public, bob.identityPrivate),
            oneTimePrekeyId = 1,
            oneTimePrekeyPublic = otk.public,
        )

        val aliceSession = E2eeProtocol.initiate(alice, bundle)
        val first = E2eeProtocol.seal(aliceSession, bytes("one"))
        val second = E2eeProtocol.seal(aliceSession, bytes("two"))
        val third = E2eeProtocol.seal(aliceSession, bytes("three"))

        val accepted = E2eeProtocol.openFirst(bob, bobPrekey, otkId = 1, oneTimePrekeyPrivate = otk.private, first)!!
        val bobSession = accepted.session

        // Arrive as three, one, two — all must decrypt.
        val gotThird = E2eeProtocol.open(bobSession, third)
        val gotFirst = E2eeProtocol.open(bobSession, first)
        val gotSecond = E2eeProtocol.open(bobSession, second)
        assertArrayEquals(bytes("three"), gotThird)
        assertArrayEquals(bytes("one"), gotFirst)
        assertArrayEquals(bytes("two"), gotSecond)
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val alice = E2eeProtocol.generateIdentity()
        val bob = E2eeProtocol.generateIdentity()
        val bobPrekey = E2eeProtocol.generatePrekeyPair()
        val otk = E2eeProtocol.generateOneTimePrekey()
        val bundle = PeerBundle(
            identityPublic = bob.identityPublic,
            signedPrekeyPublic = bobPrekey.public,
            signedPrekeySignature = E2eeProtocol.signPrekey(bobPrekey.public, bob.identityPrivate),
            oneTimePrekeyId = 3,
            oneTimePrekeyPublic = otk.public,
        )
        val aliceSession = E2eeProtocol.initiate(alice, bundle)
        val sealed = E2eeProtocol.seal(aliceSession, bytes("secret"))
        val bobSession = E2eeProtocol.openFirst(bob, bobPrekey, 3, otk.private, sealed)!!.session

        // Flip one ciphertext byte: GCM authentication must fail closed.
        val tampered = sealed.copy(ciphertext = sealed.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertNull(E2eeProtocol.open(bobSession, tampered))

        // A tampered header (n bumped) must fail the same way.
        val bumped = sealed.copy(n = sealed.n + 99)
        assertNull(E2eeProtocol.open(bobSession, bumped))
    }

    @Test
    fun envelopeJsonRoundTripsByteForByte() {
        val envelope = Envelope(
            v = E2eeProtocol.VERSION,
            ik = ByteArray(91) { (it + 40).toByte() },
            dh = ByteArray(91) { it.toByte() },
            n = 4,
            pn = 2,
            otkId = 9,
            ciphertext = ByteArray(48) { (it * 3).toByte() },
        )
        val json = envelope.toJson()
        assertTrue(json.contains("\"v\":1"))
        val parsed = Envelope.fromJson(json)
        assertNotNull(parsed)
        assertEquals(envelope.v, parsed!!.v)
        assertArrayEquals(envelope.ik, parsed.ik)
        assertArrayEquals(envelope.dh, parsed.dh)
        assertEquals(envelope.n, parsed.n)
        assertEquals(envelope.pn, parsed.pn)
        assertEquals(envelope.otkId, parsed.otkId)
        assertArrayEquals(envelope.ciphertext, parsed.ciphertext)

        // Malformed or future-version payloads fail closed.
        assertNull(Envelope.fromJson("{not json"))
        assertNull(Envelope.fromJson("""{"v":99,"dh":"","n":0,"pn":0,"ct":""}"""))
    }

    @Test
    fun hkdfMatchesRfc5869CaseOne() {
        // RFC 5869 A.1 — SHA-256, basic test case: pins extract+expand exactly.
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { 0x00 }
        val info = ByteArray(10) { 0xf0.toByte() }
        val okm = E2eeProtocol.hkdf(ikm = ikm, salt = salt, info = info, length = 42)
        val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        assertEquals(expected, okm.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun sessionsSurviveSerialization() {
        val alice = E2eeProtocol.generateIdentity()
        val bob = E2eeProtocol.generateIdentity()
        val bobPrekey = E2eeProtocol.generatePrekeyPair()
        val otk = E2eeProtocol.generateOneTimePrekey()
        val bundle = PeerBundle(
            identityPublic = bob.identityPublic,
            signedPrekeyPublic = bobPrekey.public,
            signedPrekeySignature = E2eeProtocol.signPrekey(bobPrekey.public, bob.identityPrivate),
            oneTimePrekeyId = 11,
            oneTimePrekeyPublic = otk.public,
        )
        val session = E2eeProtocol.initiate(alice, bundle)
        val restored = E2eeProtocol.deserialize(session.serialize())
        assertNotNull(restored)
        val sealed = E2eeProtocol.seal(restored!!, bytes("after restart"))
        val accepted = E2eeProtocol.openFirst(bob, bobPrekey, 11, otk.private, sealed)
        assertNotNull(accepted)
        assertArrayEquals(bytes("after restart"), accepted!!.plaintext)

        // Corrupt bytes fail closed instead of producing a half-alive session.
        val corrupt = session.serialize().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertNull(E2eeProtocol.deserialize(corrupt))
    }

    @Test
    fun wrongOneTimePrekeyIdCannotOpen() {
        val alice = E2eeProtocol.generateIdentity()
        val bob = E2eeProtocol.generateIdentity()
        val bobPrekey = E2eeProtocol.generatePrekeyPair()
        val otk = E2eeProtocol.generateOneTimePrekey()
        val otherOtk = E2eeProtocol.generateOneTimePrekey()
        val bundle = PeerBundle(
            identityPublic = bob.identityPublic,
            signedPrekeyPublic = bobPrekey.public,
            signedPrekeySignature = E2eeProtocol.signPrekey(bobPrekey.public, bob.identityPrivate),
            oneTimePrekeyId = 5,
            oneTimePrekeyPublic = otk.public,
        )
        val session = E2eeProtocol.initiate(alice, bundle)
        val sealed = E2eeProtocol.seal(session, bytes("only for the right key"))
        // The WRONG private half: X3DH derives a different SK → GCM must fail.
        val opened = E2eeProtocol.openFirst(bob, bobPrekey, 5, otherOtk.private, sealed)
        assertNull(opened)
        assertFalse(opened != null)
    }
}
