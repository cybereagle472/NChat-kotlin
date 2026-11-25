package com.nchat.app.network

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * What the user is told when a sealed message cannot be sent or shown. The
 * reasons for a failed send are worded to follow "Message not sent — ".
 */
internal object E2eeText {
    const val SIGNED_OUT = "this account has been signed out"
    const val NOT_READY = "encryption isn't ready on this phone yet; try again in a moment"
    const val PEER_HAS_NO_KEYS = "this contact can't receive encrypted messages right now (they may have erased NChat from their phone)"
    const val PEER_KEYS_UNVERIFIED = "this contact's encryption keys could not be checked"
    const val REPLACED = "this account is now in use on another phone"
    const val CODE_CHANGED = "this contact's security code changed. Check the notice at the top of the chat, then send it again"
    const val MEMBER_CODE_CHANGED =
        "the security code of somebody in this group changed. Open your chat with them, check the notice, then send it again"
    const val UNAVAILABLE =
        "encrypted chats can't be opened on this phone right now. Restart NChat; if that doesn't help, " +
            "choose \"Log out and erase\" in Settings and sign in again"

    /** In place of a sealed message this phone has no key for (sent to, or from, another installation). */
    const val CANNOT_SHOW = "🔒 This message can't be shown on this phone"

    /** In place of sealed content of a kind this build does not know. */
    const val NEEDS_UPDATE = "🔒 Update NChat to see this message"

    /** In place of a sealed file whose address is missing or is not on this app's server. */
    const val FILE_UNAVAILABLE = "🔒 This attachment can't be opened"
}

/** How the user leaves an account on this phone. */
enum class LogOut {
    /**
     * What this phone keeps of the account stays, and nothing of it is shown
     * or used until the same account signs in here again.
     */
    Keep,

    /**
     * Everything this phone keeps of the account is erased, and the server
     * is asked to stop handing its keys out: for a phone that goes to
     * somebody else.
     */
    Erase,
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
 * **What ends it.** Only erasing does — [logOut] with [LogOut.Erase], at
 * once and without the network — and another account signing in. Logging
 * out with [LogOut.Keep], and a session that expired, erase nothing: until
 * somebody signs in nothing is opened and nothing is sealed, and the same
 * account signing in again finds its keys and its history where they were.
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
    /** How hard a backup's key is to make from its passphrase; see [E2eeBackup.ROUNDS]. */
    private val backupRounds: Int = E2eeBackup.ROUNDS,
) {
    private class Account(
        val userId: String,
        val keys: E2eeKeys,
        val client: E2eeClient,
        val localSeal: LocalSeal,
        /** Nothing of this account was on the phone when it was opened: its storage key was made then. */
        val startedAnew: Boolean,
    ) {
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

        override suspend fun willSeal(peerId: String): Boolean = throw IllegalStateException(reason)

        override suspend fun <T : Any> sendGroupSealed(
            groupId: String,
            clientId: String,
            payload: E2eePayload,
            group: GroupTransport,
            send: suspend (GroupSealed) -> T,
        ): T? = throw IllegalStateException(reason)

        override suspend fun willSealGroup(groupId: String, group: GroupTransport): Boolean = throw IllegalStateException(reason)

        override fun revealGroup(groupId: String, senderId: String, messageId: String, clientId: String?, mine: Boolean, envelope: String): E2eePayload? = null

        override fun missingGroupKeys(groupId: String, rows: List<GroupRow>): List<GroupKeyRef> = emptyList()

        override fun acceptGroupKey(groupId: String, senderId: String, chainId: String, envelope: String): Boolean = false

        override fun noGroupKey(groupId: String, senderId: String, chainId: String) = Unit

        override suspend fun askGroupKeyAgain(groupId: String, senderId: String, chainId: String, ask: suspend () -> Unit): Boolean = false

        override fun groupSecurity(groupId: String): GroupSecurity? = null

        override fun kept(peerId: String, messageId: String, clientId: String?, mine: Boolean): E2eePayload? = null

        override fun keptInGroup(groupId: String, senderId: String, messageId: String, clientId: String?, mine: Boolean): E2eePayload? = null

        override fun forget(peerId: String, messageId: String, clientId: String?) = Unit

        override fun forgetInGroup(groupId: String, senderId: String, messageId: String, clientId: String?) = Unit

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
     *
     * True when the account starts anew here: the phone held nothing of it —
     * a new phone, one it was erased from, one another account used since —
     * so it has new keys, and what was sealed for earlier ones cannot be
     * opened on it without a backup. False when its keys and its history
     * were here and are in use again, and also when what is here cannot be
     * opened right now: that is not a new start, and nothing was replaced.
     */
    fun signedIn(userId: String): Boolean = synchronized(lock) {
        current = userId.takeIf { it.isNotBlank() }
        failed = null
        val before = open
        val account = account() ?: return@synchronized false
        account.keys.claim()
        account !== before && account.startedAnew
    }

    /** Sealing and opening for the signed-in account. */
    fun messaging(): SealedMessaging = synchronized(lock) {
        if (current == null) signedOut else account()?.client ?: unavailable
    }

    /**
     * Where the opened copies of this account's sealed files are kept (see
     * [SealedMediaStore]); null when nobody is signed in or the account's
     * storage cannot be opened. It lies inside what belongs to the account,
     * so it is erased with the keys — by [LogOut.Erase], and when another
     * account signs in.
     */
    fun mediaDirectory(): File? = synchronized(lock) {
        val userId = current ?: return@synchronized null
        if (account() == null) null else File(File(root, hashedName(userId)), MEDIA)
    }

    /**
     * What seals the rest of what this account keeps on this phone — cached
     * conversations, the lists with their previews, messages waiting to be
     * sent, drafts (see [LocalSeal]). It is made from the account's storage
     * key, so erasing the account, and another account signing in, leave
     * everything sealed with it unopenable; while the account is only logged
     * out there is no seal, and the same one again once it is back.
     *
     * Null when nobody is signed in or the account's storage cannot be
     * opened. Then there is nothing to seal with, and whoever asked writes
     * nothing rather than write it unsealed.
     */
    fun localSeal(): LocalSeal? = synchronized(lock) {
        if (current == null) null else account()?.localSeal
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
     * Writes to [out] a backup of everything this phone keeps of the
     * signed-in account's sealed messages, sealed under [passphrase]; see
     * [E2eeBackup]. Null — with nothing written — when nobody is signed in
     * or the account's storage cannot be opened.
     *
     * Takes about a second before the first byte: that is the price of every
     * guess at the passphrase. Throws [IllegalArgumentException], before
     * anything is written, for a passphrase [E2eeBackup.passphraseProblem]
     * turns down; [IOException] when the account logged out before all of it
     * was read — what was written is then not a backup, and does not
     * restore; and whatever [out] throws.
     */
    fun backUp(out: OutputStream, passphrase: CharArray): E2eeBackup.Written? {
        val (userId, client) = signedInClient() ?: return null
        val writer = E2eeBackup.Writer(out, userId, passphrase, backupRounds)
        val kept = client.keptChats() ?: throw IOException(E2eeText.SIGNED_OUT)
        var chats = 0
        for (chat in kept.ids) {
            val entries = client.keptOf(chat) ?: throw IOException(E2eeText.SIGNED_OUT)
            if (entries.isEmpty()) continue
            writer.chat(chat)
            chats++
            for ((key, payload) in entries) writer.entry(key, payload)
        }
        // Everything was read before anything could be erased: it is a whole backup, whatever happened since.
        writer.finish()
        return E2eeBackup.Written(writer.entries, chats, kept.unreadable)
    }

    /**
     * Reads a backup of the signed-in account from [input] and puts back
     * what this phone does not keep; returns how many messages that was.
     * Null — with nothing read — when nobody is signed in or the account's
     * storage cannot be opened.
     *
     * What the phone keeps already is never replaced. Throws
     * [E2eeBackup.Failure] with what is wrong with the file; it says how
     * many messages were put back before that — everything read before the
     * damage was sealed under the passphrase, and is kept. Throws whatever
     * [input] throws. Null as well when the account logged out meanwhile:
     * nothing is written back for an account that has left.
     */
    fun restore(input: InputStream, passphrase: CharArray): Int? {
        val (userId, client) = signedInClient() ?: return null
        var restored = 0
        var left = false
        var chat: String? = null
        val waiting = ArrayList<Pair<String, E2eePayload>>()
        // A chat at a time: its entries go into the archive together, in the order of the backup.
        fun settle() {
            val name = chat
            if (name != null && waiting.isNotEmpty()) {
                val added = client.restoreKept(name, waiting)
                if (added == null) left = true else restored += added
            }
            waiting.clear()
        }
        try {
            E2eeBackup.read(input, userId, passphrase) { name, key, payload ->
                if (name != chat) {
                    settle()
                    chat = name
                }
                waiting += key to payload
            }
        } catch (failure: E2eeBackup.Failure) {
            settle()
            throw E2eeBackup.Failure(failure.problem, restored)
        }
        settle()
        return if (left) null else restored
    }

    private fun signedInClient(): Pair<String, E2eeClient>? = synchronized(lock) {
        val userId = current ?: return@synchronized null
        account()?.let { userId to it.client }
    }

    /**
     * The user logs out. Before this returns, and without the network,
     * nothing of the account is opened or sealed any more — not through
     * this object, and not through what was handed out before.
     *
     * [LogOut.Keep] leaves everything where it is: the storage key, the
     * keys and the opened history. The same account signing in again finds
     * them; another account signing in removes them first. Nothing is
     * withdrawn from the server, so what is sent meanwhile is sealed for
     * this phone and waits for it.
     *
     * [LogOut.Erase] removes all of it for good.
     *
     * The answer is for the server: the identity key it should stop handing
     * out, once nothing of this account can reach the server any more. It is
     * null when there is none, and always for [LogOut.Keep] — the keys this
     * phone registered stay the account's.
     */
    fun logOut(how: LogOut): suspend () -> String? {
        val withdrawn = synchronized(lock) {
            // To erase, opened if need be: the identity to withdraw is in there.
            val account = if (how == LogOut.Erase) account() else open
            current = null
            failed = null
            open = null
            account?.close()
            if (how == LogOut.Erase) {
                erase()
                account?.keys
            } else {
                null
            }
        }
        return { withdrawn?.published() }
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
        val kept = stored != null && stored.owner == userId && stored.key.size == SealedBox.KEY_BYTES
        val key = if (stored != null && kept) {
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
        return Account(userId, keys, client, LocalSeal(key), startedAnew = !kept)
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

    private companion object {
        const val MEDIA = "media"
    }
}
