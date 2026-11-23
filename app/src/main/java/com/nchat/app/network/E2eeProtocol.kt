package com.nchat.app.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * X3DH + Double Ratchet over P-256 / AES-256-GCM — wire version 1.
 *
 * The state machine is the Signal Double Ratchet algorithm, step for step
 * (initialisation, `RatchetEncrypt`, `RatchetDecrypt`, `SkipMessageKeys`,
 * `DHRatchet`); only the primitives differ:
 *  - P-256 everywhere: platform-native from minSdk 24 to current Android.
 *    X25519 would need bundled crypto below API 33 (agent.md rule 11).
 *  - KDFs are domain-separated HMAC-SHA256; HKDF follows RFC 5869 exactly
 *    (pinned by the A.1 test vector).
 *  - AES-256-GCM with a random 96-bit nonce per message. Each message key is
 *    used once by construction, and the random nonce keeps that true even if
 *    a device ever restores an older copy of its ratchet state.
 *
 * Envelope (opaque to the server):
 * `{"v":1,"ik":…,"ek":…,"spk":…,"otk":…,"dh":…,"n":…,"pn":…,"ct":…}` — `ik`,
 * `ek`, `spk` and `otk` are the X3DH handshake and ride on every message
 * until the first reply arrives, so ANY message of the first flight can
 * establish the session (a lost or reordered first message cannot strand it).
 * `spk`/`otk` name the recipient's prekeys by fingerprint ([keyReference]),
 * never by a server-assigned id: the private half can be stored before the
 * public half is uploaded, and a prekey rotation needs no wire change.
 *
 * Guarantees the callers rely on:
 *  - [open] and [openFirst] never throw and never mutate a session unless the
 *    envelope authenticates — a forged, replayed or malformed envelope costs
 *    a bounded amount of work and changes nothing.
 *  - The handshake binds both identity keys AND both account ids into the
 *    session secret, so a key bundle replayed under another account
 *    (unknown-key-share) can never produce a working session.
 *  - Every public key that did not originate on this device is checked to be
 *    a canonical P-256 point ON the curve before any provider touches it
 *    ([isValidPublicKey]) — invalid-curve inputs are refused by construction.
 *  - Plaintext is padded to [PAD_BLOCK] bytes before sealing; the server
 *    learns a length bucket, never the length.
 */
object E2eeProtocol {

    const val VERSION = 1

    /** Canonical P-256 SubjectPublicKeyInfo — the only public-key encoding v1 accepts. */
    const val PUBLIC_KEY_BYTES = 91

    private const val INFO_X3DH = "NChat X3DH v1"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val MAC = "HmacSHA256"
    private const val GCM_TAG_BITS = 128
    private const val GCM_NONCE_BYTES = 12
    private const val KEY_BYTES = 32
    private const val PAD_BLOCK = 128
    private const val KEY_REFERENCE_BYTES = 9

    /** Largest forward jump one envelope may force — bounds the work a forged counter can cost. */
    private const val MAX_SKIP = 2000

    /** Message keys kept for late arrivals; the oldest fall out first. */
    private const val MAX_SKIPPED_KEYS = 2000

    private val random = SecureRandom()

    /** DER framing of an uncompressed P-256 SubjectPublicKeyInfo, up to and including the `04` point tag. */
    internal val SPKI_PREFIX: ByteArray = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
    )

    /** P-256 field prime and curve constant `b` (`a` is −3); pinned against the platform's own curve by a test. */
    internal val CURVE_P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    internal val CURVE_B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val THREE = BigInteger.valueOf(3)

    // ------------------------------------------------------------- material

    /** The account identity: one per user, published via /keys/register. */
    data class IdentityKeyPair(val identityPublic: ByteArray, val identityPrivate: ByteArray)

    /** A signed or one-time prekey pair; only the public half is uploaded. */
    data class PrekeyPair(val public: ByteArray, val private: ByteArray)

    fun generateIdentity(): IdentityKeyPair = generateEcKeypair().let { IdentityKeyPair(it.public, it.private) }

    fun generatePrekeyPair(): PrekeyPair = generateEcKeypair()

    fun generateOneTimePrekey(): PrekeyPair = generateEcKeypair()

    /** ECDSA over the prekey's SPKI, P1363 (64 bytes) per the wire contract. */
    fun signPrekey(prekeyPublic: ByteArray, identityPrivate: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(loadPrivate(identityPrivate))
            update(prekeyPublic)
            sign()
        }
        return derToP1363(signature)
    }

    /** Public material of a peer, exactly as GET /keys/bundle/:userId serves it. */
    data class PeerBundle(
        val identityPublic: ByteArray,
        val signedPrekeyPublic: ByteArray,
        val signedPrekeySignature: ByteArray,
        val oneTimePrekeyPublic: ByteArray?,
    )

    /**
     * How an envelope names one of the recipient's prekeys: the first 72 bits
     * of SHA-256 over its public key, base64 (12 characters). Both sides can
     * compute it from the public key alone.
     */
    fun keyReference(publicKey: ByteArray): String =
        encodeBase64(MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(KEY_REFERENCE_BYTES))

    /**
     * True for a canonical uncompressed P-256 SubjectPublicKeyInfo whose point
     * satisfies the curve equation. P-256 has cofactor 1, so on-curve is full
     * public-key validation. Works on public data only.
     */
    fun isValidPublicKey(spki: ByteArray): Boolean {
        if (spki.size != PUBLIC_KEY_BYTES) return false
        for (index in SPKI_PREFIX.indices) if (spki[index] != SPKI_PREFIX[index]) return false
        val x = BigInteger(1, spki.copyOfRange(SPKI_PREFIX.size, SPKI_PREFIX.size + 32))
        val y = BigInteger(1, spki.copyOfRange(SPKI_PREFIX.size + 32, PUBLIC_KEY_BYTES))
        if (x >= CURVE_P || y >= CURVE_P) return false
        val left = y.multiply(y).mod(CURVE_P)
        val right = x.multiply(x).multiply(x).subtract(x.multiply(THREE)).add(CURVE_B).mod(CURVE_P)
        return left == right
    }

    /** One sealed message — byte-identical on the wire and in storage. */
    data class Envelope(
        val v: Int,
        /** Sender identity (SPKI) — handshake only. */
        val ik: ByteArray?,
        /** X3DH ephemeral key (SPKI) — handshake only. */
        val ek: ByteArray?,
        /** [keyReference] of the recipient's signed prekey the handshake used — handshake only. */
        val spk: String?,
        /** [keyReference] of the recipient's one-time prekey, when the bundle had one — handshake only. */
        val otk: String?,
        val dh: ByteArray,
        val n: Int,
        val pn: Int,
        val ciphertext: ByteArray,
    ) {
        /** True while the sender is still waiting for the first reply. */
        val carriesHandshake: Boolean get() = ik != null && ek != null && spk != null

        fun toJson(): String = buildJsonObject {
            put("v", v)
            if (ik != null && ek != null && spk != null) {
                put("ik", encodeBase64(ik))
                put("ek", encodeBase64(ek))
                put("spk", spk)
                if (otk != null) put("otk", otk)
            }
            put("dh", encodeBase64(dh))
            put("n", n)
            put("pn", pn)
            put("ct", encodeBase64(ciphertext))
        }.toString()

        companion object {
            /** Strict parse: any malformed, inconsistent or future-version payload fails closed. */
            fun fromJson(json: String): Envelope? {
                return try {
                    parse(Json.parseToJsonElement(json).jsonObject)
                } catch (_: Throwable) {
                    null
                }
            }

            private fun parse(obj: JsonObject): Envelope? {
                if (obj.int("v") != VERSION) return null
                val dh = obj.publicKey("dh") ?: return null
                val n = obj.int("n") ?: return null
                val pn = obj.int("pn") ?: return null
                if (n < 0 || pn < 0) return null
                val ciphertext = obj.base64("ct") ?: return null
                if (ciphertext.size < GCM_NONCE_BYTES + GCM_TAG_BITS / 8) return null
                val handshake = obj.containsKey("ik")
                if (handshake != obj.containsKey("ek") || handshake != obj.containsKey("spk")) return null
                if (!handshake) {
                    if (obj.containsKey("otk")) return null
                    return Envelope(VERSION, null, null, null, null, dh, n, pn, ciphertext)
                }
                val ik = obj.publicKey("ik") ?: return null
                val ek = obj.publicKey("ek") ?: return null
                val spk = obj.reference("spk") ?: return null
                val otk = if (obj.containsKey("otk")) obj.reference("otk") ?: return null else null
                return Envelope(VERSION, ik, ek, spk, otk, dh, n, pn, ciphertext)
            }
        }
    }

    data class Opened(val session: Session, val plaintext: ByteArray)

    // -------------------------------------------------------------- sessions

    internal class Chain(var ck: ByteArray, var n: Int)

    class Session internal constructor(
        /** Our identity (SPKI) — stamped on handshake envelopes. */
        internal var localIdentity: ByteArray,
        /** The peer identity (SPKI) this session is bound to. */
        internal var remoteIdentity: ByteArray,
        internal var rootKey: ByteArray,
        internal var sendChain: Chain?,
        internal var sendPrevCount: Int,
        internal var recvChain: Chain?,
        internal var ourRatchetPrivate: ByteArray,
        internal var ourRatchetPublic: ByteArray,
        /** Remote ratchet key the receiving chain belongs to (the peer's signed prekey at first). */
        internal var theirRatchet: ByteArray?,
        /** X3DH ephemeral key, attached to every envelope until the first reply opens. */
        internal var pendingEphemeral: ByteArray?,
        internal var pendingSignedPrekey: String?,
        internal var pendingOneTimeKey: String?,
    ) {
        internal val skipped = LinkedHashMap<String, ByteArray>()

        /** The peer identity key (SPKI) this session is pinned to — input to safety numbers. */
        fun remoteIdentityKey(): ByteArray = remoteIdentity.copyOf()

        /** Deep copy: a trial decryption works on this and is adopted only when it authenticates. */
        internal fun copy(): Session {
            val twin = Session(
                localIdentity = localIdentity.copyOf(),
                remoteIdentity = remoteIdentity.copyOf(),
                rootKey = rootKey.copyOf(),
                sendChain = sendChain?.let { Chain(it.ck.copyOf(), it.n) },
                sendPrevCount = sendPrevCount,
                recvChain = recvChain?.let { Chain(it.ck.copyOf(), it.n) },
                ourRatchetPrivate = ourRatchetPrivate.copyOf(),
                ourRatchetPublic = ourRatchetPublic.copyOf(),
                theirRatchet = theirRatchet?.copyOf(),
                pendingEphemeral = pendingEphemeral?.copyOf(),
                pendingSignedPrekey = pendingSignedPrekey,
                pendingOneTimeKey = pendingOneTimeKey,
            )
            for ((key, messageKey) in skipped) twin.skipped[key] = messageKey.copyOf()
            return twin
        }

        internal fun adopt(other: Session) {
            localIdentity = other.localIdentity
            remoteIdentity = other.remoteIdentity
            rootKey = other.rootKey
            sendChain = other.sendChain
            sendPrevCount = other.sendPrevCount
            recvChain = other.recvChain
            ourRatchetPrivate = other.ourRatchetPrivate
            ourRatchetPublic = other.ourRatchetPublic
            theirRatchet = other.theirRatchet
            pendingEphemeral = other.pendingEphemeral
            pendingSignedPrekey = other.pendingSignedPrekey
            pendingOneTimeKey = other.pendingOneTimeKey
            skipped.clear()
            skipped.putAll(other.skipped)
        }

        fun serialize(): ByteArray {
            val payload = ByteArrayOutputStream().use { out ->
                DataOutputStream(out).apply {
                    writeLengthPrefixed(localIdentity)
                    writeLengthPrefixed(remoteIdentity)
                    write(rootKey)
                    writeNullableChain(sendChain)
                    writeInt(sendPrevCount)
                    writeNullableChain(recvChain)
                    writeLengthPrefixed(ourRatchetPrivate)
                    writeLengthPrefixed(ourRatchetPublic)
                    writeNullableBytes(theirRatchet)
                    writeNullableBytes(pendingEphemeral)
                    writeNullableBytes(pendingSignedPrekey?.toByteArray(Charsets.US_ASCII))
                    writeNullableBytes(pendingOneTimeKey?.toByteArray(Charsets.US_ASCII))
                    writeInt(skipped.size)
                    for ((key, messageKey) in skipped) {
                        val separator = key.lastIndexOf('#')
                        val ratchetKey = decodeBase64(key.substring(0, separator)) ?: error("corrupt skipped-key id")
                        writeLengthPrefixed(ratchetKey)
                        writeInt(key.substring(separator + 1).toInt())
                        write(messageKey)
                    }
                    flush()
                }
                out.toByteArray()
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            return MAGIC + digest + payload
        }

        private fun DataOutputStream.writeNullableChain(chain: Chain?) {
            if (chain == null) {
                writeByte(0)
                return
            }
            writeByte(1)
            write(chain.ck)
            writeInt(chain.n)
        }

        private fun DataOutputStream.writeLengthPrefixed(value: ByteArray) {
            writeInt(value.size)
            write(value)
        }

        private fun DataOutputStream.writeNullableBytes(value: ByteArray?) {
            if (value == null) {
                writeByte(0)
                return
            }
            writeByte(1)
            writeLengthPrefixed(value)
        }
    }

    // ------------------------------------------------------------- handshake

    /**
     * Initiator side of X3DH. Verifies the signed prekey against the peer's
     * identity first — an invalid signature throws (never a silent downgrade).
     * [localId]/[remoteId] are the two account ids; they are mixed into the
     * session secret together with both identity keys.
     */
    fun initiate(identity: IdentityKeyPair, bundle: PeerBundle, localId: String, remoteId: String): Session {
        val oneTimePublic = bundle.oneTimePrekeyPublic
        if (!isValidPublicKey(bundle.identityPublic) || !isValidPublicKey(bundle.signedPrekeyPublic) ||
            (oneTimePublic != null && !isValidPublicKey(oneTimePublic))
        ) {
            error("peer key material is not a valid P-256 public key")
        }
        if (!verifyPrekeySignature(bundle.signedPrekeyPublic, bundle.signedPrekeySignature, bundle.identityPublic)) {
            error("signed prekey signature does not verify against peer identity")
        }
        val ephemeral = generateEcKeypair()
        val dh1 = dh(identity.identityPrivate, bundle.signedPrekeyPublic)
        val dh2 = dh(ephemeral.private, bundle.identityPublic)
        val dh3 = dh(ephemeral.private, bundle.signedPrekeyPublic)
        val dh4 = oneTimePublic?.let { dh(ephemeral.private, it) }
        val secret = x3dhSecret(
            listOfNotNull(dh1, dh2, dh3, dh4),
            handshakeContext(identity.identityPublic, bundle.identityPublic, localId, remoteId),
        )
        wipe(dh1, dh2, dh3, dh4, ephemeral.private)
        // Double Ratchet initialisation (initiator): a fresh ratchet key against
        // the peer's signed prekey. The ephemeral private key is gone already,
        // so a later device compromise cannot rebuild this first chain.
        val ratchet = generateEcKeypair()
        val shared = dh(ratchet.private, bundle.signedPrekeyPublic)
        val (rootKey, chainKey) = kdfRootKey(secret, shared)
        wipe(shared, secret)
        return Session(
            localIdentity = identity.identityPublic,
            remoteIdentity = bundle.identityPublic,
            rootKey = rootKey,
            sendChain = Chain(chainKey, 0),
            sendPrevCount = 0,
            recvChain = null,
            ourRatchetPrivate = ratchet.private,
            ourRatchetPublic = ratchet.public,
            theirRatchet = bundle.signedPrekeyPublic,
            pendingEphemeral = ephemeral.public,
            pendingSignedPrekey = keyReference(bundle.signedPrekeyPublic),
            pendingOneTimeKey = oneTimePublic?.let { keyReference(it) },
        )
    }

    /**
     * Responder side: any handshake-carrying envelope plus the private halves
     * it was sealed against. The envelope must name [signedPrekey], and
     * [oneTimePrekeyPrivate] must be present exactly when the envelope names
     * a one-time prekey. Null on any failure.
     */
    fun openFirst(
        identity: IdentityKeyPair,
        signedPrekey: PrekeyPair,
        oneTimePrekeyPrivate: ByteArray?,
        envelope: Envelope,
        localId: String,
        remoteId: String,
    ): Opened? {
        return try {
            val senderIdentity = envelope.ik ?: return null
            val ephemeral = envelope.ek ?: return null
            if (envelope.spk != keyReference(signedPrekey.public)) return null
            if ((envelope.otk == null) != (oneTimePrekeyPrivate == null)) return null
            // Mirror of initiate(): same four agreements, seen from the other side.
            val dh1 = dh(signedPrekey.private, senderIdentity)
            val dh2 = dh(identity.identityPrivate, ephemeral)
            val dh3 = dh(signedPrekey.private, ephemeral)
            val dh4 = oneTimePrekeyPrivate?.let { dh(it, ephemeral) }
            val secret = x3dhSecret(
                listOfNotNull(dh1, dh2, dh3, dh4),
                handshakeContext(senderIdentity, identity.identityPublic, remoteId, localId),
            )
            wipe(dh1, dh2, dh3, dh4)
            // Double Ratchet initialisation (responder): the signed prekey is our
            // first ratchet key; receiving turns the ratchet and replaces it.
            val session = Session(
                localIdentity = identity.identityPublic,
                remoteIdentity = senderIdentity,
                rootKey = secret,
                sendChain = null,
                sendPrevCount = 0,
                recvChain = null,
                ourRatchetPrivate = signedPrekey.private.copyOf(),
                ourRatchetPublic = signedPrekey.public.copyOf(),
                theirRatchet = null,
                pendingEphemeral = null,
                pendingSignedPrekey = null,
                pendingOneTimeKey = null,
            )
            val plaintext = receive(session, envelope) ?: return null
            Opened(session, plaintext)
        } catch (_: Throwable) {
            null
        }
    }

    // --------------------------------------------------------------- ratchet

    /** Seal one plaintext. The chain advances only once the ciphertext exists. */
    fun seal(session: Session, plaintext: ByteArray): Envelope {
        val chain = session.sendChain ?: error("session has no sending chain")
        val (messageKey, nextChainKey) = chainKdf(chain.ck)
        val counter = chain.n
        val previous = session.sendPrevCount
        val ratchetPublic = session.ourRatchetPublic
        val ciphertext = gcmEncrypt(messageKey, headerBytes(ratchetPublic, counter, previous), pad(plaintext))
        wipe(messageKey)
        chain.ck = nextChainKey
        chain.n = counter + 1
        val ephemeral = session.pendingEphemeral
        return Envelope(
            v = VERSION,
            ik = if (ephemeral != null) session.localIdentity else null,
            ek = ephemeral,
            spk = if (ephemeral != null) session.pendingSignedPrekey else null,
            otk = if (ephemeral != null) session.pendingOneTimeKey else null,
            dh = ratchetPublic,
            n = counter,
            pn = previous,
            ciphertext = ciphertext,
        )
    }

    /**
     * Decrypt an inbound envelope against an established session. Works on a
     * copy and adopts it only when the envelope authenticates; returns null
     * (session untouched) for anything else. Never throws.
     */
    fun open(session: Session, envelope: Envelope): ByteArray? {
        return try {
            val trial = session.copy()
            val plaintext = receive(trial, envelope) ?: return null
            session.adopt(trial)
            plaintext
        } catch (_: Throwable) {
            null
        }
    }

    /** `RatchetDecrypt`. Mutates [state]; callers pass a disposable copy. */
    private fun receive(state: Session, envelope: Envelope): ByteArray? {
        val header = headerBytes(envelope.dh, envelope.n, envelope.pn)

        // 1. A message that was skipped over earlier (any chain).
        val skipId = skipKey(envelope.dh, envelope.n)
        val stashed = state.skipped[skipId]
        if (stashed != null) {
            val padded = gcmDecrypt(stashed, header, envelope.ciphertext) ?: return null
            state.skipped.remove(skipId)
            wipe(stashed)
            return unpad(padded)?.also { confirmHandshake(state) }
        }

        // 2. A new remote ratchet key: close the current receiving chain
        //    (keeping the keys of messages still in flight), then turn.
        val turned = !(envelope.dh contentEquals state.theirRatchet)
        if (turned) {
            val closing = state.recvChain
            val closingKey = state.theirRatchet
            if (closing != null && closingKey != null && !skipTo(state, closing, closingKey, envelope.pn)) return null
            val shared = dh(state.ourRatchetPrivate, envelope.dh)
            val (rootKey, chainKey) = kdfRootKey(state.rootKey, shared)
            wipe(shared)
            state.rootKey = rootKey
            state.recvChain = Chain(chainKey, 0)
            state.theirRatchet = envelope.dh
        }

        // 3. The receiving chain, skipping ahead when messages are missing.
        val chain = state.recvChain ?: return null
        if (envelope.n < chain.n) return null // already consumed — a replay
        if (!skipTo(state, chain, envelope.dh, envelope.n)) return null
        val (messageKey, nextChainKey) = chainKdf(chain.ck)
        val padded = gcmDecrypt(messageKey, header, envelope.ciphertext)
        wipe(messageKey)
        if (padded == null) return null
        chain.ck = nextChainKey
        chain.n += 1

        // 4. Authenticated: our half of the DH ratchet (new sending chain).
        if (turned) {
            val ratchet = generateEcKeypair()
            val shared = dh(ratchet.private, envelope.dh)
            val (rootKey, chainKey) = kdfRootKey(state.rootKey, shared)
            wipe(shared)
            state.sendPrevCount = state.sendChain?.n ?: 0
            state.sendChain = Chain(chainKey, 0)
            state.rootKey = rootKey
            state.ourRatchetPrivate = ratchet.private
            state.ourRatchetPublic = ratchet.public
        }
        confirmHandshake(state)
        return unpad(padded)
    }

    /** The peer has our session: stop attaching the handshake. */
    private fun confirmHandshake(state: Session) {
        state.pendingEphemeral = null
        state.pendingSignedPrekey = null
        state.pendingOneTimeKey = null
    }

    /** `SkipMessageKeys`: stash the keys of [chain] up to (excluding) [until]. False = jump too large. */
    private fun skipTo(state: Session, chain: Chain, ratchetKey: ByteArray, until: Int): Boolean {
        if (until <= chain.n) return true
        if (until - chain.n > MAX_SKIP) return false
        while (chain.n < until) {
            val (messageKey, nextChainKey) = chainKdf(chain.ck)
            if (state.skipped.size >= MAX_SKIPPED_KEYS) {
                val oldest = state.skipped.keys.first()
                state.skipped.remove(oldest)?.let { wipe(it) }
            }
            state.skipped[skipKey(ratchetKey, chain.n)] = messageKey
            chain.ck = nextChainKey
            chain.n += 1
        }
        return true
    }

    private fun skipKey(ratchetKey: ByteArray, n: Int): String = encodeBase64(ratchetKey) + "#" + n

    // ----------------------------------------------------------------- crypto

    /** RFC 5869 HKDF-SHA256 (extract + expand), pinned by the A.1 vector. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0) { "length must be positive" }
        val hashLength = 32
        require(length <= 255 * hashLength) { "length too large" }
        val effectiveSalt = if (salt.isEmpty()) ByteArray(hashLength) else salt
        val prk = hmac(effectiveSalt, ikm)
        val output = ByteArrayOutputStream(length)
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < length) {
            val block = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            output.write(block)
            wipe(previous)
            previous = block
            counter += 1
        }
        wipe(prk, previous)
        return output.toByteArray().copyOf(length)
    }

    private fun x3dhSecret(dhOutputs: List<ByteArray>, context: ByteArray): ByteArray {
        val material = dhOutputs.reduce { acc, bytes -> acc + bytes }
        val secret = hkdf(
            ikm = material,
            salt = ByteArray(KEY_BYTES),
            info = INFO_X3DH.toByteArray(Charsets.US_ASCII) + context,
            length = KEY_BYTES,
        )
        wipe(material)
        return secret
    }

    /** Initiator first, responder second; every field length-prefixed so no two layouts collide. */
    private fun handshakeContext(
        initiatorIdentity: ByteArray,
        responderIdentity: ByteArray,
        initiatorId: String,
        responderId: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        for (field in listOf(
            initiatorIdentity,
            responderIdentity,
            initiatorId.toByteArray(Charsets.UTF_8),
            responderId.toByteArray(Charsets.UTF_8),
        )) {
            require(field.size <= 0xFFFF) { "handshake field too long" }
            out.write(field.size ushr 8)
            out.write(field.size)
            out.write(field)
        }
        return out.toByteArray()
    }

    /** NChat ratchet KDF v1: two domain-separated HMACs keyed by the root key. */
    private fun kdfRootKey(rootKey: ByteArray, sharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
        val nextRoot = hmac(rootKey, byteArrayOf(0x01) + sharedSecret)
        val chainKey = hmac(rootKey, byteArrayOf(0x02) + sharedSecret)
        return nextRoot to chainKey
    }

    /** Signal-style chain KDF: message key and next chain key from one step. */
    private fun chainKdf(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val messageKey = hmac(chainKey, byteArrayOf(0x01))
        val next = hmac(chainKey, byteArrayOf(0x02))
        return messageKey to next
    }

    /**
     * ECDH over P-256. JCA's KeyAgreement lives in `javax.crypto` (there is
     * no `java.security.KeyAgreement` on either the JDK or Android), and the
     * class is real on both the device and the plain-JVM unit tests — the
     * two-step init/doPhase/generateSecret flow is the portable API. The
     * public key is validated in [loadPublic] before the provider sees it.
     */
    private fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(loadPrivate(privateKey))
            doPhase(loadPublic(publicKey), true)
            generateSecret()
        }

    /** `nonce || ciphertext || tag` with a fresh random nonce. */
    private fun gcmEncrypt(key: ByteArray, header: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(GCM_NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(header)
        return nonce + cipher.doFinal(plaintext)
    }

    private fun gcmDecrypt(key: ByteArray, header: ByteArray, sealed: ByteArray): ByteArray? {
        return try {
            if (sealed.size < GCM_NONCE_BYTES + GCM_TAG_BITS / 8) return null
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, sealed, 0, GCM_NONCE_BYTES),
            )
            cipher.updateAAD(header)
            cipher.doFinal(sealed, GCM_NONCE_BYTES, sealed.size - GCM_NONCE_BYTES)
        } catch (_: Throwable) {
            null
        }
    }

    /** ISO/IEC 7816-4 padding to a multiple of [PAD_BLOCK]: `0x80` then zeros. */
    private fun pad(plaintext: ByteArray): ByteArray {
        val padded = ByteArray((plaintext.size / PAD_BLOCK + 1) * PAD_BLOCK)
        System.arraycopy(plaintext, 0, padded, 0, plaintext.size)
        padded[plaintext.size] = 0x80.toByte()
        return padded
    }

    private fun unpad(padded: ByteArray): ByteArray? {
        if (padded.isEmpty() || padded.size % PAD_BLOCK != 0) return null
        var end = padded.size - 1
        while (end >= 0 && padded[end].toInt() == 0) end--
        if (end < 0 || padded[end] != 0x80.toByte()) return null
        return padded.copyOf(end)
    }

    private fun headerBytes(ratchetPublic: ByteArray, n: Int, pn: Int): ByteArray =
        byteArrayOf(VERSION.toByte()) + ratchetPublic +
            byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) +
            byteArrayOf((pn ushr 24).toByte(), (pn ushr 16).toByte(), (pn ushr 8).toByte(), pn.toByte())

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(MAC).run {
            init(SecretKeySpec(key, MAC))
            doFinal(data)
        }

    private fun verifyPrekeySignature(prekeyPublic: ByteArray, signature: ByteArray, identityPublic: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(loadPublic(identityPublic))
            update(prekeyPublic)
            verify(p1363ToDer(signature))
        }
    } catch (_: Throwable) {
        false
    }

    private fun generateEcKeypair(): PrekeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        val pair = generateKeyPair()
        PrekeyPair(pair.public.encoded, pair.private.encoded)
    }

    /**
     * ECDSA signatures travel as DER (`30 len 02 rlen r 02 slen s`) but the
     * wire contract wants fixed-width P1363: r and s each left-padded to 32.
     */
    internal fun derToP1363(der: ByteArray): ByteArray {
        if (der.size < 8 || der[0] != 0x30.toByte() || (der[1].toInt() and 0xff) != der.size - 2) {
            error("unexpected ECDSA DER structure (${der.size} bytes)")
        }
        var index = 2
        if (der[index] != 0x02.toByte()) error("DER: expected r integer")
        val rLength = der[index + 1].toInt() and 0xff
        index += 2
        val r = der.copyOfRange(index, index + rLength)
        index += rLength
        if (der[index] != 0x02.toByte()) error("DER: expected s integer")
        val sLength = der[index + 1].toInt() and 0xff
        index += 2
        val s = der.copyOfRange(index, index + sLength)
        if (index + sLength != der.size) error("DER: trailing bytes")
        return fixedWidth(r) + fixedWidth(s)
    }

    /** Reverse of [derToP1363]: P1363 r||s back to DER for Signature.verify. */
    internal fun p1363ToDer(p1363: ByteArray): ByteArray {
        if (p1363.size != 64) error("P1363 signature must be 64 bytes (${p1363.size})")
        val r = derInteger(p1363.copyOfRange(0, 32))
        val s = derInteger(p1363.copyOfRange(32, 64))
        val body = r + s
        if (body.size >= 128) error("DER body too long (${body.size})")
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun derInteger(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start].toInt() == 0 && value[start + 1].toInt() and 0x80 == 0) start++
        var bytes = value.copyOfRange(start, value.size)
        if (bytes[0].toInt() and 0x80 != 0) bytes = byteArrayOf(0) + bytes
        return byteArrayOf(0x02, bytes.size.toByte()) + bytes
    }

    private fun fixedWidth(value: ByteArray): ByteArray = when {
        value.size == 32 -> value
        value.size == 33 && value[0].toInt() == 0 -> value.copyOfRange(1, 33)
        value.size < 32 -> ByteArray(32 - value.size) + value
        else -> error("ECDSA integer does not fit in 32 bytes (${value.size})")
    }

    /** The only place peer-supplied key bytes reach a crypto provider. */
    private fun loadPublic(encoded: ByteArray): java.security.PublicKey {
        if (!isValidPublicKey(encoded)) error("not a valid P-256 public key")
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    }

    private fun loadPrivate(encoded: ByteArray) =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    private fun wipe(vararg arrays: ByteArray?) {
        for (array in arrays) {
            if (array != null) Arrays.fill(array, 0)
        }
    }

    // ----------------------------------------------------- serialization

    private val MAGIC = byteArrayOf('N'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())

    /** Restore a session; any corruption, truncation, or layout drift fails closed. */
    fun deserialize(bytes: ByteArray): Session? {
        if (bytes.size < MAGIC.size + 32 + 1) return null
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val digest = bytes.copyOfRange(MAGIC.size, MAGIC.size + 32)
        val payload = bytes.copyOfRange(MAGIC.size + 32, bytes.size)
        if (!MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(digest)) return null
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val localIdentity = input.readLengthPrefixed()
                val remoteIdentity = input.readLengthPrefixed()
                val rootKey = input.readExactly(KEY_BYTES)
                val sendChain = input.readNullableChain()
                val sendPrevCount = input.readInt()
                val recvChain = input.readNullableChain()
                val ourRatchetPrivate = input.readLengthPrefixed()
                val ourRatchetPublic = input.readLengthPrefixed()
                val theirRatchet = input.readNullableBytes()
                val pendingEphemeral = input.readNullableBytes()
                val pendingSignedPrekey = input.readNullableBytes()?.let { String(it, Charsets.US_ASCII) }
                val pendingOneTimeKey = input.readNullableBytes()?.let { String(it, Charsets.US_ASCII) }
                val skippedCount = input.readInt()
                if (sendPrevCount < 0 || skippedCount < 0 || skippedCount > MAX_SKIPPED_KEYS) return null
                val session = Session(
                    localIdentity = localIdentity,
                    remoteIdentity = remoteIdentity,
                    rootKey = rootKey,
                    sendChain = sendChain,
                    sendPrevCount = sendPrevCount,
                    recvChain = recvChain,
                    ourRatchetPrivate = ourRatchetPrivate,
                    ourRatchetPublic = ourRatchetPublic,
                    theirRatchet = theirRatchet,
                    pendingEphemeral = pendingEphemeral,
                    pendingSignedPrekey = pendingSignedPrekey,
                    pendingOneTimeKey = pendingOneTimeKey,
                )
                repeat(skippedCount) {
                    val ratchetKey = input.readLengthPrefixed()
                    val n = input.readInt()
                    if (n < 0) return null
                    session.skipped[skipKey(ratchetKey, n)] = input.readExactly(KEY_BYTES)
                }
                if (input.available() != 0) return null
                session
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun DataInputStream.readExactly(length: Int): ByteArray {
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes
    }

    private fun DataInputStream.readLengthPrefixed(): ByteArray {
        val length = readInt()
        if (length <= 0 || length > 4096) error("bad length $length")
        return readExactly(length)
    }

    private fun DataInputStream.readNullableChain(): Chain? {
        if (readUnsignedByte() == 0) return null
        val chainKey = readExactly(KEY_BYTES)
        val n = readInt()
        if (n < 0) error("bad chain counter $n")
        return Chain(chainKey, n)
    }

    private fun DataInputStream.readNullableBytes(): ByteArray? =
        if (readUnsignedByte() == 0) null else readLengthPrefixed()

    // ----------------------------------------------------------- encodings

    /** A JSON number (never a quoted one) that fits an Int. */
    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toIntOrNull()
    }

    private fun JsonObject.base64(key: String): ByteArray? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return decodeBase64(primitive.content)
    }

    private fun JsonObject.publicKey(key: String): ByteArray? =
        base64(key)?.takeIf { it.size == PUBLIC_KEY_BYTES }

    /** A [keyReference]: canonical base64 of exactly [KEY_REFERENCE_BYTES] bytes. */
    private fun JsonObject.reference(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val decoded = decodeBase64(primitive.content) ?: return null
        if (decoded.size != KEY_REFERENCE_BYTES || encodeBase64(decoded) != primitive.content) return null
        return primitive.content
    }
}

// ---------------------------------------------------------- base64 codec
//
// android.util.Base64 is unavailable on the plain JVM (unit tests) and
// java.util.Base64 needs API 26 (minSdk is 24), so the wire codec lives here:
// standard alphabet, padded, no line breaks — byte-compatible with both.

private val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private val B64_LOOKUP = IntArray(128) { -1 }.apply {
    B64_ALPHABET.forEachIndexed { index, char -> this[char.code] = index }
}

internal fun encodeBase64(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var index = 0
    while (index + 2 < bytes.size) {
        val block = ((bytes[index].toInt() and 0xff) shl 16) or
            ((bytes[index + 1].toInt() and 0xff) shl 8) or
            (bytes[index + 2].toInt() and 0xff)
        out.append(B64_ALPHABET[(block shr 18) and 0x3f])
        out.append(B64_ALPHABET[(block shr 12) and 0x3f])
        out.append(B64_ALPHABET[(block shr 6) and 0x3f])
        out.append(B64_ALPHABET[block and 0x3f])
        index += 3
    }
    when (bytes.size - index) {
        1 -> {
            val block = (bytes[index].toInt() and 0xff) shl 16
            out.append(B64_ALPHABET[(block shr 18) and 0x3f])
            out.append(B64_ALPHABET[(block shr 12) and 0x3f])
            out.append('=')
            out.append('=')
        }
        2 -> {
            val block = ((bytes[index].toInt() and 0xff) shl 16) or ((bytes[index + 1].toInt() and 0xff) shl 8)
            out.append(B64_ALPHABET[(block shr 18) and 0x3f])
            out.append(B64_ALPHABET[(block shr 12) and 0x3f])
            out.append(B64_ALPHABET[(block shr 6) and 0x3f])
            out.append('=')
        }
    }
    return out.toString()
}

internal fun decodeBase64(text: String): ByteArray? {
    if (text.length % 4 != 0) return null
    if (text.isEmpty()) return ByteArray(0)
    val padding = when {
        text.endsWith("==") -> 2
        text.endsWith("=") -> 1
        else -> 0
    }
    val out = ByteArray(text.length / 4 * 3 - padding)
    var outIndex = 0
    var index = 0
    while (index < text.length) {
        val c0 = text.charCodeAt(index++)
        val c1 = text.charCodeAt(index++)
        val c2 = text.charCodeAt(index++)
        val c3 = text.charCodeAt(index++)
        val v0 = c0.base64Value() ?: return null
        val v1 = c1.base64Value() ?: return null
        val v2 = if (c2 == '='.code) null else c2.base64Value() ?: return null
        val v3 = if (c3 == '='.code) null else c3.base64Value() ?: return null
        if (v2 == null && v3 != null) return null
        if (v2 == null && padding != 2) return null
        if (v3 == null && padding != 1 && padding != 2) return null
        val block = (v0 shl 18) or (v1 shl 12) or ((v2 ?: 0) shl 6) or (v3 ?: 0)
        out[outIndex++] = ((block shr 16) and 0xff).toByte()
        if (v2 != null) out[outIndex++] = ((block shr 8) and 0xff).toByte()
        if (v3 != null) out[outIndex++] = (block and 0xff).toByte()
    }
    return if (outIndex == out.size) out else null
}

private fun Int.base64Value(): Int? = if (this in 0..127) B64_LOOKUP[this].takeIf { it >= 0 } else null

private fun String.charCodeAt(index: Int): Int = this[index].code
