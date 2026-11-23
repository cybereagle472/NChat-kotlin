package com.nchat.app.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyAgreement
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * X3DH + Double Ratchet over P-256 / AES-256-GCM (Batch 3).
 *
 * Design decisions, locked with the server contract:
 *  - P-256 everywhere: platform-native from minSdk 24 to current Android and
 *    WebCrypto-native in the worker's tests; X25519 would need bundled crypto
 *    below API 33 (agent.md rule 11).
 *  - The envelope is opaque to the server:
 *    `{"v":1,"ik":…,"dh":…,"n":…,"pn":…,"otk":id?,"ct":…}` — `ik` is the
 *    sender's identity SPKI (the responder needs it to finish X3DH) and
 *    `otk` appears only on the first message of a session.
 *  - KDFs are domain-separated HMAC-SHA256 constructions; HKDF follows
 *    RFC 5869 exactly (pinned by the A.1 test vector).
 *  - One message key per nonce (the chain advances per message), so the fixed
 *    GCM nonce is safe by construction.
 *
 * Failure paths return null or throw — there is no "decrypt anyway".
 */
object E2eeProtocol {

    const val VERSION = 1

    private const val INFO_X3DH = "NChat X3DH v1"
    private const val INFO_DR_INIT = "NChat DR init v1"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val MAC = "HmacSHA256"
    private const val GCM_TAG_BITS = 128
    private const val GCM_NONCE_BYTES = 12
    private const val KEY_BYTES = 32
    private const val MAX_SKIPPED_KEYS = 512

    // ------------------------------------------------------------- material

    /** The account identity: one per user, published via /keys/register. */
    data class IdentityKeyPair(val identityPublic: ByteArray, val identityPrivate: ByteArray)

    /** A signed or one-time prekey pair; only the public half is uploaded. */
    data class PrekeyPair(val public: ByteArray, val private: ByteArray)

    fun generateIdentity(): IdentityKeyPair = generateEcKeypair().let { IdentityKeyPair(it.public, it.private) }

    fun generatePrekeyPair(): PrekeyPair = generateEcKeypair().let { PrekeyPair(it.public, it.private) }

    fun generateOneTimePrekey(): PrekeyPair = generateEcKeypair().let { PrekeyPair(it.public, it.private) }

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
        val oneTimePrekeyId: Int?,
        val oneTimePrekeyPublic: ByteArray?,
    )

    /** One sealed message — byte-identical on the wire and in storage. */
    data class Envelope(
        val v: Int,
        val ik: ByteArray,
        val dh: ByteArray,
        val n: Int,
        val pn: Int,
        val otkId: Int?,
        val ciphertext: ByteArray,
    ) {
        fun toJson(): String = buildJsonObject {
            put("v", v)
            put("ik", encodeBase64(ik))
            put("dh", encodeBase64(dh))
            put("n", n)
            put("pn", pn)
            if (otkId != null) put("otk", otkId)
            put("ct", encodeBase64(ciphertext))
        }.toString()

        companion object {
            /** Strict parse: any malformed or future-version payload fails closed. */
            fun fromJson(json: String): Envelope? {
                return try {
                    val obj = Json.parseToJsonElement(json).jsonObject
                    val v = obj.int("v") ?: return null
                    if (v != VERSION) return null
                    val ik = obj.base64("ik") ?: return null
                    val dh = obj.base64("dh") ?: return null
                    val n = obj.int("n") ?: return null
                    val pn = obj.int("pn") ?: return null
                    if (n < 0 || pn < 0) return null
                    val ciphertext = obj.base64("ct") ?: return null
                    Envelope(v, ik, dh, n, pn, obj.int("otk"), ciphertext)
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    data class Opened(val session: Session, val plaintext: ByteArray)

    // -------------------------------------------------------------- sessions

    internal class Chain(var ck: ByteArray, var n: Int)

    class Session internal constructor(
        internal val identityPublic: ByteArray,
        internal var rootKey: ByteArray,
        internal var sendChain: Chain?,
        /** Remote ratchet key the current send chain was derived against. */
        internal var sendChainRemote: ByteArray?,
        internal var sendPrevCount: Int,
        internal var recvChain: Chain?,
        internal var ourRatchetPrivate: ByteArray?,
        internal var ourRatchetPublic: ByteArray?,
        internal var theirRatchet: ByteArray?,
        internal var pendingOtkId: Int?,
    ) {
        internal val skipped = LinkedHashMap<String, ByteArray>()

        fun serialize(): ByteArray {
            val payload = ByteArrayOutputStream().use { out ->
                DataOutputStream(out).apply {
                    writeInt(identityPublic.size)
                    write(identityPublic)
                    write(rootKey)
                    writeNullableChain(sendChain)
                    writeNullableBytes(sendChainRemote)
                    writeInt(sendPrevCount)
                    writeNullableChain(recvChain)
                    writeNullableBytes(ourRatchetPrivate)
                    writeNullableBytes(ourRatchetPublic)
                    writeNullableBytes(theirRatchet)
                    if (pendingOtkId == null) {
                        writeByte(0)
                    } else {
                        writeByte(1)
                        writeInt(pendingOtkId!!)
                    }
                    writeInt(skipped.size)
                    for ((key, messageKey) in skipped) {
                        val separator = key.lastIndexOf('#')
                        val dhKey = key.substring(0, separator)
                        val n = key.substring(separator + 1).toInt()
                        writeInt(dhKey.length)
                        write(dhKey.toByteArray(Charsets.US_ASCII))
                        writeInt(n)
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

        private fun DataOutputStream.writeNullableBytes(value: ByteArray?) {
            if (value == null) {
                writeByte(0)
                return
            }
            writeByte(1)
            writeInt(value.size)
            write(value)
        }
    }

    /**
     * Initiator side of X3DH. Verifies the signed prekey against the peer's
     * identity first — an invalid signature throws (never a silent downgrade).
     */
    fun initiate(identity: IdentityKeyPair, bundle: PeerBundle): Session {
        if (!verifyPrekeySignature(bundle.signedPrekeyPublic, bundle.signedPrekeySignature, bundle.identityPublic)) {
            error("signed prekey signature does not verify against peer identity")
        }
        val ephemeral = generateEcKeypair()
        val dh1 = dh(identity.identityPrivate, bundle.signedPrekeyPublic)
        val dh2 = dh(ephemeral.private, bundle.identityPublic)
        val dh3 = dh(ephemeral.private, bundle.signedPrekeyPublic)
        val dh4 = bundle.oneTimePrekeyPublic?.let { dh(ephemeral.private, it) }
        val secret = x3dhSecret(listOfNotNull(dh1, dh2, dh3, dh4))
        val (rootKey, initialChain) = initialKeyMaterial(secret)
        wipe(secret, dh1, dh2, dh3, dh4)
        return Session(
            identityPublic = identity.identityPublic,
            rootKey = rootKey,
            sendChain = Chain(initialChain, 0),
            sendChainRemote = null,
            sendPrevCount = 0,
            recvChain = null,
            ourRatchetPrivate = ephemeral.private,
            ourRatchetPublic = ephemeral.public,
            theirRatchet = null,
            pendingOtkId = bundle.oneTimePrekeyId,
        )
    }

    /**
     * Responder side: the first inbound envelope plus the private halves it
     * was sealed against. [otkId] must match the envelope (null on both sides
     * when the initiator's bundle had no one-time prekeys).
     */
    fun openFirst(
        identity: IdentityKeyPair,
        signedPrekey: PrekeyPair,
        otkId: Int?,
        oneTimePrekeyPrivate: ByteArray?,
        envelope: Envelope,
    ): Opened? {
        if (envelope.otkId != otkId) return null
        // Mirror of initiate(): envelope.ik = sender identity, envelope.dh = sender ephemeral.
        val dh1 = dh(signedPrekey.private, envelope.ik)
        val dh2 = dh(identity.identityPrivate, envelope.dh)
        val dh3 = dh(signedPrekey.private, envelope.dh)
        val dh4 = oneTimePrekeyPrivate?.let { dh(it, envelope.dh) }
        val secret = x3dhSecret(listOfNotNull(dh1, dh2, dh3, dh4))
        val (rootKey, initialChain) = initialKeyMaterial(secret)
        wipe(secret, dh1, dh2, dh3, dh4)
        val session = Session(
            identityPublic = identity.identityPublic,
            rootKey = rootKey,
            sendChain = null,
            sendChainRemote = null,
            sendPrevCount = 0,
            recvChain = Chain(initialChain, 0),
            ourRatchetPrivate = null,
            ourRatchetPublic = null,
            theirRatchet = envelope.dh,
            pendingOtkId = null,
        )
        val plaintext = receiveWithChain(session, session.recvChain!!, envelope) ?: return null
        return Opened(session, plaintext)
    }

    /** Seal one plaintext into an envelope, rotating the ratchet when due. */
    fun seal(session: Session, plaintext: ByteArray): Envelope {
        if (session.sendChain == null || session.sendChainRemote != session.theirRatchet) {
            // First reply (no send chain yet) or the remote ratchet moved.
            val remote = session.theirRatchet ?: error("no remote ratchet key to derive against")
            val ratchet = generateEcKeypair()
            val shared = dh(ratchet.private, remote)
            val (rootKey, chainKey) = kdfRootKey(session.rootKey, shared)
            wipe(shared)
            session.rootKey = rootKey
            session.sendPrevCount = session.sendChain?.n ?: 0
            session.sendChain = Chain(chainKey, 0)
            session.ourRatchetPrivate = ratchet.private
            session.ourRatchetPublic = ratchet.public
            session.sendChainRemote = remote
        }
        val chain = session.sendChain!!
        val headerN = chain.n
        val headerPn = session.sendPrevCount
        val (messageKey, nextChainKey) = chainKdf(chain.ck)
        chain.ck = nextChainKey
        chain.n += 1
        val header = headerBytes(session.ourRatchetPublic!!, headerN, headerPn)
        val ciphertext = gcmEncrypt(messageKey, header, plaintext)
        wipe(messageKey)
        val otkId = session.pendingOtkId
        session.pendingOtkId = null
        return Envelope(
            v = VERSION,
            ik = session.identityPublic,
            dh = session.ourRatchetPublic!!,
            n = headerN,
            pn = headerPn,
            otkId = otkId,
            ciphertext = ciphertext,
        )
    }

    /** Decrypt an inbound envelope against an established session; null on any failure. */
    fun open(session: Session, envelope: Envelope): ByteArray? {
        if (session.theirRatchet != null && envelope.dh contentEquals session.theirRatchet) {
            val chain = session.recvChain ?: return null
            return receiveWithChain(session, chain, envelope)
        }
        // New remote ratchet key (including the very first reply): derive the
        // matching receive chain. Requires OUR ratchet key — without it there
        // is nothing to DH against, so the message cannot belong here.
        val ourPrivate = session.ourRatchetPrivate ?: return null
        val shared = dh(ourPrivate, envelope.dh)
        val (rootKey, chainKey) = kdfRootKey(session.rootKey, shared)
        wipe(shared)
        session.rootKey = rootKey
        val chain = Chain(chainKey, 0)
        session.recvChain = chain
        session.theirRatchet = envelope.dh
        return receiveWithChain(session, chain, envelope)
    }

    /**
     * Chain receive with commit-on-success semantics: skipped keys are only
     * stashed (and the chain only advanced) after GCM authentication passes,
     * so a tampered header cannot corrupt session state.
     */
    private fun receiveWithChain(session: Session, chain: Chain, envelope: Envelope): ByteArray? {
        val remoteKey = envelope.dh
        val skipPrefix = encodeBase64(remoteKey) + "#"
        return when {
            envelope.n < chain.n -> {
                val messageKey = session.skipped[skipPrefix + envelope.n] ?: return null
                val plaintext = gcmDecrypt(messageKey, headerBytes(remoteKey, envelope.n, envelope.pn), envelope.ciphertext)
                    ?: return null
                session.skipped.remove(skipPrefix + envelope.n)
                wipe(messageKey)
                plaintext
            }
            envelope.n == chain.n -> {
                val messageKey = hmac(chain.ck, byteArrayOf(0x01))
                val plaintext = gcmDecrypt(messageKey, headerBytes(remoteKey, envelope.n, envelope.pn), envelope.ciphertext)
                    ?: run { wipe(messageKey); return null }
                chain.ck = hmac(chain.ck, byteArrayOf(0x02))
                chain.n += 1
                wipe(messageKey)
                plaintext
            }
            else -> {
                // Gap: derive every key up front, commit only on success.
                var ck = chain.ck
                var current = chain.n
                val derived = mutableListOf<ByteArray>()
                while (current <= envelope.n) {
                    val (messageKey, next) = chainKdf(ck)
                    derived += messageKey
                    ck = next
                    current += 1
                }
                val messageKey = derived.last()
                val plaintext = gcmDecrypt(messageKey, headerBytes(remoteKey, envelope.n, envelope.pn), envelope.ciphertext)
                    ?: run { derived.forEach { wipe(it) }; return null }
                var stashIndex = chain.n
                for (index in 0 until derived.size - 1) {
                    if (session.skipped.size >= MAX_SKIPPED_KEYS) {
                        val oldest = session.skipped.keys.firstOrNull()
                        if (oldest != null) {
                            session.skipped.remove(oldest)?.let { wipe(it) }
                        }
                    }
                    session.skipped[skipPrefix + stashIndex] = derived[index]
                    stashIndex += 1
                }
                wipe(messageKey)
                chain.ck = ck
                chain.n = envelope.n + 1
                plaintext
            }
        }
    }

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

    private fun x3dhSecret(dhOutputs: List<ByteArray>): ByteArray =
        hkdf(
            ikm = dhOutputs.reduce { acc, bytes -> acc + bytes },
            salt = ByteArray(KEY_BYTES),
            info = INFO_X3DH.toByteArray(Charsets.US_ASCII),
            length = KEY_BYTES,
        )

    private fun initialKeyMaterial(secret: ByteArray): Pair<ByteArray, ByteArray> {
        val material = hkdf(
            ikm = secret,
            salt = ByteArray(KEY_BYTES),
            info = INFO_DR_INIT.toByteArray(Charsets.US_ASCII),
            length = KEY_BYTES * 2,
        )
        return material.copyOfRange(0, KEY_BYTES) to material.copyOfRange(KEY_BYTES, KEY_BYTES * 2)
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

    private fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(loadPrivate(privateKey))
            doFinal(loadPublic(publicKey))
        }

    private fun gcmEncrypt(key: ByteArray, header: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, ByteArray(GCM_NONCE_BYTES)))
        cipher.updateAAD(header)
        return cipher.doFinal(plaintext)
    }

    private fun gcmDecrypt(key: ByteArray, header: ByteArray, ciphertext: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, ByteArray(GCM_NONCE_BYTES)))
        cipher.updateAAD(header)
        cipher.doFinal(ciphertext)
    } catch (_: Throwable) {
        null
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
        else -> error("ECDSA integer does not fit32 bytes (${value.size})")
    }

    private fun loadPublic(encoded: ByteArray) =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    private fun loadPrivate(encoded: ByteArray) =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    private fun wipe(vararg arrays: ByteArray?) {
        for (array in arrays) {
            if (array != null) Arrays.fill(array, 0)
        }
    }

    // ----------------------------------------------------- serialization

    private val MAGIC = byteArrayOf('N'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte(), '2'.code.toByte())

    /** Restore a session; any corruption, truncation, or version drift fails closed. */
    fun deserialize(bytes: ByteArray): Session? {
        if (bytes.size < MAGIC.size + 32 + 1) return null
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val digest = bytes.copyOfRange(MAGIC.size, MAGIC.size + 32)
        val payload = bytes.copyOfRange(MAGIC.size + 32, bytes.size)
        if (!MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(digest)) return null
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val identityLength = input.readInt()
                if (identityLength !in 1..512) return null
                val identityPublic = input.readFully(identityLength)
                val rootKey = input.readFully(KEY_BYTES)
                val sendChain = input.readNullableChain()
                val sendChainRemote = input.readNullableBytes()
                val sendPrevCount = input.readInt()
                val recvChain = input.readNullableChain()
                val ourRatchetPrivate = input.readNullableBytes()
                val ourRatchetPublic = input.readNullableBytes()
                val theirRatchet = input.readNullableBytes()
                val pendingOtkId = when (input.readUnsignedByte()) {
                    0 -> null
                    else -> input.readInt()
                }
                val skippedCount = input.readInt()
                if (skippedCount < 0 || skippedCount > MAX_SKIPPED_KEYS) return null
                val skipped = LinkedHashMap<String, ByteArray>()
                repeat(skippedCount) {
                    val dhLength = input.readInt()
                    if (dhLength <= 0 || dhLength > 512) return null
                    val dhKey = input.readFully(dhLength)
                    val n = input.readInt()
                    if (n < 0) return null
                    val messageKey = input.readFully(KEY_BYTES)
                    skipped[encodeBase64(dhKey) + "#" + n] = messageKey
                }
                if (input.available() != 0) return null
                Session(
                    identityPublic = identityPublic,
                    rootKey = rootKey,
                    sendChain = sendChain,
                    sendChainRemote = sendChainRemote,
                    sendPrevCount = sendPrevCount,
                    recvChain = recvChain,
                    ourRatchetPrivate = ourRatchetPrivate,
                    ourRatchetPublic = ourRatchetPublic,
                    theirRatchet = theirRatchet,
                    pendingOtkId = pendingOtkId,
                ).apply { this.skipped.putAll(skipped) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun DataInputStream.readFully(length: Int): ByteArray {
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes
    }

    private fun DataInputStream.readNullableChain(): Chain? = when (readUnsignedByte()) {
        0 -> null
        else -> Chain(readFully(KEY_BYTES), readInt())
    }

    private fun DataInputStream.readNullableBytes(): ByteArray? = when (readUnsignedByte()) {
        0 -> null
        else -> {
            val length = readInt()
            if (length <= 0 || length > 4096) error("bad length $length")
            readFully(length)
        }
    }

    // ----------------------------------------------------------- encodings

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()

    private fun JsonObject.base64(key: String): ByteArray? =
        this[key]?.jsonPrimitive?.content?.let { decodeBase64(it) }
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
