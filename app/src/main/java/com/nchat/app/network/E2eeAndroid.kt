package com.nchat.app.network

import com.nchat.app.data.repository.isTransientSendFailure
import java.io.IOException

/**
 * The storage key of [EndToEnd], kept in [SecurePrefs]: sealed under a key
 * that never leaves the Android Keystore, like the session tokens.
 */
class PrefsKeyring(private val prefs: SecurePrefs) : StorageKeyring {

    override fun load(): StorageKeyring.Entry? {
        if (!prefs.contains(OWNER) && !prefs.contains(KEY)) return null
        // Stored, so "no answer" means the Keystore did not decrypt it — not that there is nothing.
        val owner = prefs.getString(OWNER)
        val key = prefs.getString(KEY)?.let { decodeBase64(it) }
        if (owner.isNullOrEmpty() || key == null) throw IOException("the storage key could not be read")
        return StorageKeyring.Entry(owner, key)
    }

    override fun store(entry: StorageKeyring.Entry) {
        if (!prefs.putStringsSync(OWNER to entry.owner, KEY to encodeBase64(entry.key))) {
            throw IOException("the storage key could not be stored")
        }
    }

    override fun clear() {
        if (!prefs.putStringsSync(OWNER to null, KEY to null)) throw IOException("the storage key could not be removed")
    }

    private companion object {
        const val OWNER = "owner"
        const val KEY = "storage_key"
    }
}

/** The server's key directory over [NChatApi]. */
class ApiKeyDirectory(private val api: NChatApi) : KeyDirectory {

    override suspend fun status(): KeyDirectory.Status = reaching {
        val status = api.keyStatus()
        KeyDirectory.Status(status.identityPublicKey?.takeIf { it.isNotEmpty() }, status.remaining)
    }

    override suspend fun register(identityKey: String, signedPrekey: String, signature: String) = reaching {
        api.registerKeys(identityKey, signedPrekey, signature)
    }

    override suspend fun replaceSignedPrekey(identityKey: String, signedPrekey: String, signature: String): KeyDirectory.Replacement =
        reaching { api.replaceSignedPrekey(identityKey, signedPrekey, signature) }

    override suspend fun upload(identityKey: String, oneTimePrekeys: List<String>): Boolean = reaching {
        api.uploadPrekeys(identityKey, oneTimePrekeys)
    }

    /**
     * A server that is down or busy counts as not reached, exactly like no
     * network: whoever is waiting for keys waits and tries again. Only an
     * answer that will not change by asking again is passed on as a refusal —
     * which includes a server that has no key directory yet.
     */
    private suspend fun <T> reaching(call: suspend () -> T): T = try {
        call()
    } catch (error: ApiException) {
        if (error.status != NChatApi.KEYS_NOT_SET_UP && isTransientSendFailure(error)) {
            throw IOException("the key server is unavailable (${error.status})", error)
        }
        throw error
    }
}

/** A peer's key bundle from the server, or null when that account has no keys. */
suspend fun NChatApi.fetchPeerBundle(userId: String): E2eeProtocol.PeerBundle? = keyBundle(userId)?.let {
    peerBundle(it.identityPublicKey, it.signedPrekeyPublic, it.signedPrekeySignature, it.oneTimePrekey?.publicKey)
}
