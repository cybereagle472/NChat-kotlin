package com.nchat.app.network

import java.io.File
import java.security.SecureRandom

/**
 * What the user is told when a sealed message cannot be sent or shown. The
 * reasons for a failed send are worded to follow "Message not sent — ".
 */
internal object E2eeText {
    const val SIGNED_OUT = "this account has been signed out"
    const val NOT_READY = "encryption isn't ready on this phone yet; try again in a moment"
    const val PEER_HAS_NO_KEYS = "this contact can't receive encrypted messages right now (they may have signed out)"
    const val PEER_KEYS_UNVERIFIED = "this contact's encryption keys could not be checked"
    const val REPLACED = "this account is now in use on another phone"
    const val CODE_CHANGED = "this contact's security code changed. Check the notice at the top of the chat, then send it again"
    const val UNAVAILABLE =
        "encrypted chats can't be opened on this phone right now. Restart NChat; if that doesn't help, sign out and sign in again"

    /** In place of a sealed message this phone has no key for (sent to, or from, another installation). */
    const val CANNOT_SHOW = "🔒 This message can't be shown on this phone"

    /** In place of sealed content of a kind this build does not know. */
    const val NEEDS_UPDATE = "🔒 Update NChat to see this message"
}

/**
 * Where the key that seals this phone's end-to-end encryption state lives.
 * In the app that is storage protected by the Android Keystore.
 */
interface StorageKeyring {
    /** The account the state on this phone belongs to, and the key that opens it. */
    class Entry(val owner: String, val key: ByteArray)

    /** Null when nothing is stored. Throws when something is stored but cannot be read right now. */
    fun load(): Entry?

    /** Replaces whatever was stored; on stable storage before this returns. Throws on failure. */
    fun store(entry: Entry)

    /** Forgets the key: nothing that was sealed with it can be opened again. Throws on failure. */
    fun clear()
}

/**
 * A key bundle as the server serves it (base64) in the form the protocol
 * takes. Throws when a part of it is not what it claims to be: a bundle that
 * cannot be used is an error — read as "this user has no keys", it would send
 * the message in the clear.
 */
fun peerBundle(identityKey: String, signedPrekey: String, signature: String, oneTimePrekey: String?): E2eeProtocol.PeerBundle {
    fun bytes(value: String): ByteArray = decodeBase64(value)?.takeIf { it.isNotEmpty() } ?: error(E2eeText.PEER_KEYS_UNVERIFIED)
    return E2eeProtocol.PeerBundle(
        identityPublic = bytes(identityKey),
        signedPrekeyPublic = bytes(signedPrekey),
        signedPrekeySignature = bytes(signature),
        oneTimePrekeyPublic = oneTimePrekey?.let(::bytes),
    )
}

/**
 * End-to-end encryption on this phone: the keys and the opened history of
 * the account that is signed in here.
 *
 * **One account at a time.** What is kept belongs to exactly one account.
 * Another account signing in removes it first, so nobody's keys stay behind
 * on a phone somebody else now uses.
 *
 * **What ends it.** Signing out erases everything, at once and without the
 * network. A session that merely expired erases nothing: the same account
 * signing in again finds its keys and its history where they were.
 *
 * **Never absent.** [messaging] always answers. With nobody signed in, or
 * with storage that cannot be opened, the answer is a stand-in that refuses
 * to send and shows nothing — never a quiet fall back to plaintext, and
 * never new keys written over ones that might still be readable later.
 */
class EndToEnd(
    private val root: File,
    private val keyring: StorageKeyring,
    private val directory: KeyDirectory,
    private val fetchBundle: suspend (peerId: String) -> E2eeProtocol.PeerBundle?,
    /** Another phone has taken the signed-in account over; see [E2eeKeys]. */
    private val onReplaced: () -> Unit,
    /** Trouble worth recording; never contains message content or key material. */
    private val report: (what: String, error: Throwable) -> Unit = { _, _ -> },
    /** What [SealedMessaging.security] answers for this peer has changed; see [E2eeClient]. */
    private val onSecurityChanged: (peerId: String) -> Unit = {},
) {
    private class Account(val userId: String, val keys: E2eeKeys, val client: E2eeClient) {
        fun close() {
            client.close()
            keys.close()
        }
    }

    private class Refusing(private val reason: String) : SealedMessaging {
        override fun <T> batch(block: () -> T): T = block()

        override fun reveal(peerId: String, messageId: String, clientId: String?, mine: Boolean, envelope: String): E2eePayload? = null

        override suspend fun <T : Any> sendSealed(
            peerId: String,
            clientId: String,
            payload: E2eePayload,
            keysChanged: (Throwable) -> Boolean,
            editOf: String?,
            send: suspend (E2eeClient.Sealed) -> T,
        ): T? = throw IllegalStateException(reason)

        override fun security(peerId: String): PeerSecurity? = null

        override fun securityCode(peerId: String, key: String): String? = null

        override fun acceptChange(peerId: String, key: String): Boolean = false

        override fun setVerified(peerId: String, key: String, verified: Boolean): Boolean = false
    }

    private val lock = Any()
    private val signedOut: SealedMessaging = Refusing(E2eeText.SIGNED_OUT)
    private val unavailable: SealedMessaging = Refusing(E2eeText.UNAVAILABLE)

    // Guarded by [lock].
    private var current: String? = null
    private var open: Account? = null

    /** The account whose state could not be opened; not tried again until something changes. */
    private var failed: String? = null

    /** An account was already signed in when the app started. */
    fun resume(userId: String?) {
        synchronized(lock) {
            current = userId?.takeIf { it.isNotBlank() }
            failed = null
        }
    }

    /**
     * The user has just signed in on this phone, which makes it the phone the
     * account lives on: the next [sync] registers its keys in place of any
     * other phone's.
     */
    fun signedIn(userId: String) {
        synchronized(lock) {
            current = userId.takeIf { it.isNotBlank() }
            failed = null
            account()?.keys?.claim()
        }
    }

    /** Sealing and opening for the signed-in account. */
    fun messaging(): SealedMessaging = synchronized(lock) {
        if (current == null) signedOut else account()?.client ?: unavailable
    }

    /**
     * Brings the server's key directory and this phone into agreement; see
     * [E2eeKeys.sync]. Null when there is nothing to do it for. Throws what
     * the directory throws.
     */
    suspend fun sync(): E2eeKeys.Outcome? {
        val keys = synchronized(lock) { account()?.keys } ?: return null
        return keys.sync()
    }

    /**
     * The user signs out: this phone forgets the account's keys and
     * everything they opened, before this returns and without the network.
     *
     * The answer is for the server, which should stop handing those keys
     * out: it names the identity key to withdraw (null when there is none),
     * once nothing of this account can reach the server any more.
     */
    fun signOut(): suspend () -> String? {
        val leaving = synchronized(lock) {
            // Opened if need be: the identity to withdraw is in there.
            val account = account()
            current = null
            failed = null
            open = null
            account?.close()
            erase()
            account?.keys
        }
        return { leaving?.published() }
    }

    private fun account(): Account? {
        val userId = current ?: return null
        open?.let { if (it.userId == userId) return it }
        if (failed == userId) return null
        open?.close()
        open = null
        return try {
            open(userId).also { open = it }
        } catch (failure: Exception) {
            failed = userId
            report("end-to-end encryption state could not be opened", failure)
            null
        }
    }

    private fun open(userId: String): Account {
        val home = File(root, hashedName(userId))
        val stored = keyring.load()
        val key = if (stored != null && stored.owner == userId && stored.key.size == SealedBox.KEY_BYTES) {
            stored.key
        } else {
            // Nothing here belongs to this account: what is left is another account's, or debris.
            erase()
            ByteArray(SealedBox.KEY_BYTES).also {
                SecureRandom().nextBytes(it)
                keyring.store(StorageKeyring.Entry(userId, it))
            }
        }
        root.listFiles()?.forEach { if (it != home) it.deleteRecursively() }

        val vault = FileSecretVault(File(home, "vault"), key)
        val store = E2eeStore(vault)
        val keys = E2eeKeys(vault, store, directory, onReplaced)
        val client = E2eeClient(
            localId = userId,
            store = store,
            archive = E2eeArchive(File(home, "archive"), key),
            identity = keys::identity,
            signedPrekey = keys::signedPrekey,
            fetchBundle = fetchBundle,
            prepare = keys::ensureRegistered,
            onSecurityChanged = onSecurityChanged,
        )
        return Account(userId, keys, client)
    }

    /** The key first: once it is gone, whatever survives on disk is noise. */
    private fun erase() {
        try {
            keyring.clear()
        } catch (failure: Exception) {
            report("the storage key could not be removed", failure)
        }
        if (!root.deleteRecursively() && root.exists()) {
            report("end-to-end encryption files could not be removed", IllegalStateException("delete failed"))
        }
    }
}
