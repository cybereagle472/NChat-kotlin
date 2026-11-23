package com.nchat.app.network

import android.content.Context
import android.util.Base64
import com.nchat.app.core.util.CrashLog
import kotlinx.coroutines.CancellationException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * End-to-end encryption key foundation (Phase A).
 *
 * Generates the account's P-256 identity keypair and signed prekey, persists
 * the private halves in [SecurePrefs] (AES-256-GCM under an Android Keystore
 * key — the same storage class as the session tokens), and registers only the
 * PUBLIC halves with the backend. The server verifies the signed-prekey
 * signature on every upload, so a bug in the conversion below fails loudly as
 * an HTTP error (recorded via [CrashLog]) instead of ever publishing material
 * that cannot decrypt.
 *
 * Wire contract locked by the server's tests: SPKI DER = exactly 91 bytes;
 * signature = ECDSA P1363 (64 raw bytes). Android's Signature emits DER, so
 * the r||s conversion happens here.
 *
 * [sync] is idempotent: the same material is re-registered on every call, so
 * sign-in and app launch both converge on a consistent server state.
 */
class E2eeKeys(
    context: Context,
    private val api: NChatApi,
    private val tokens: TokenStore,
) {
    private val prefs = SecurePrefs(context.applicationContext, PREFS)

    /** Generates once, registers the public halves; never throws — callers are fire-and-forget. */
    suspend fun sync() {
        if (tokens.accessToken.isNullOrEmpty()) return
        try {
            val material = load() ?: generateAndPersist()
            api.registerKeys(
                identityPublicKey = material.identityPublic,
                signedPrekeyPublic = material.prekeyPublic,
                signedPrekeySignature = material.prekeySignature,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Sign-in and app launch must never fail over key sync; the next attempt retries.
            CrashLog.note("e2ee key sync", error)
        }
    }

    private data class Material(
        val identityPublic: String,
        val prekeyPublic: String,
        val prekeySignature: String,
    )

    /** Complete stored material only — any missing half means a fresh, consistent set. */
    private fun load(): Material? {
        val identityPublic = prefs.getString(KEY_IDENTITY_PUBLIC).orEmpty()
        val prekeyPublic = prefs.getString(KEY_PREKEY_PUBLIC).orEmpty()
        val prekeySignature = prefs.getString(KEY_PREKEY_SIGNATURE).orEmpty()
        if (identityPublic.isEmpty() || prekeyPublic.isEmpty() || prekeySignature.isEmpty()) return null
        return Material(identityPublic, prekeyPublic, prekeySignature)
    }

    private fun generateAndPersist(): Material {
        val identity = generateEcKeypair()
        val prekey = generateEcKeypair()
        val signature = E2eeProtocol.derToP1363(sign(prekey.public.encoded, identity.private))
        val material = Material(
            identityPublic = encodeBase64(identity.public.encoded),
            prekeyPublic = encodeBase64(prekey.public.encoded),
            prekeySignature = encodeBase64(signature),
        )
        // Persist before registering: never publish material this device cannot use again.
        val persisted = prefs.putStringsSync(
            KEY_IDENTITY_PRIVATE to encodeBase64(identity.private.encoded),
            KEY_IDENTITY_PUBLIC to material.identityPublic,
            KEY_PREKEY_PRIVATE to encodeBase64(prekey.private.encoded),
            KEY_PREKEY_PUBLIC to material.prekeyPublic,
            KEY_PREKEY_SIGNATURE to material.prekeySignature,
        )
        if (!persisted) error("could not persist E2EE key material")
        return material
    }

    private fun generateEcKeypair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    private fun sign(content: ByteArray, signer: PrivateKey): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(signer)
            update(content)
            sign()
        }

    private fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val PREFS = "e2ee_keys"
        const val KEY_IDENTITY_PRIVATE = "identity_private"
        const val KEY_IDENTITY_PUBLIC = "identity_public"
        const val KEY_PREKEY_PRIVATE = "signed_prekey_private"
        const val KEY_PREKEY_PUBLIC = "signed_prekey_public"
        const val KEY_PREKEY_SIGNATURE = "signed_prekey_signature"
    }
}
