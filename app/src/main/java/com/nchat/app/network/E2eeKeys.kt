package com.nchat.app.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The server's key directory, as far as this installation's own keys are concerned. Keys are base64 SPKI. */
interface KeyDirectory {
    /** What the account has registered: its identity key (null when none) and the one-time prekeys left. */
    class Status(val identityKey: String?, val oneTimePrekeys: Int)

    suspend fun status(): Status

    /** Makes these the account's keys, replacing any other installation's. */
    suspend fun register(identityKey: String, signedPrekey: String, signature: String)

    /** Adds one-time prekeys under [identityKey]. False when the account's registered identity is a different one. */
    suspend fun upload(identityKey: String, oneTimePrekeys: List<String>): Boolean
}

/**
 * This installation's own keys for one account, and their place in the
 * server's directory.
 *
 * **One phone per account.** The directory holds one identity key per
 * account, and only the installation holding its private half can read what
 * is sealed for the account. Signing in is what makes an installation that
 * one ([claim]); the installation it replaces finds out the next time it
 * looks ([sync] answers [Outcome.Replaced]) and stands down. Apart from a
 * sign-in, an installation registers only when the directory is empty — it
 * never takes an account back on its own, which is what stops two phones
 * from overwriting each other's keys on every launch.
 *
 * **Usable only when confirmed.** [identity] is empty until the server has
 * confirmed that these are the account's keys. A session started under an
 * unregistered identity could never be answered.
 *
 * **Private halves first.** Everything is stored (durably) before the server
 * hears about it: the server can never hand out a key this device has
 * already lost.
 *
 * The private keys never leave [vault]; only public halves reach
 * [directory].
 */
class E2eeKeys(
    private val vault: SecretVault,
    private val store: E2eeStore,
    private val directory: KeyDirectory,
    /** Another installation has taken the account over; called once per discovery, from [sync]. */
    private val onReplaced: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { Ready, Replaced }

    private class Material(
        val identity: E2eeProtocol.IdentityKeyPair,
        val prekey: E2eeProtocol.PrekeyPair,
        val signature: ByteArray,
        val registered: Boolean,
    ) {
        val identityKey: String get() = encodeBase64(identity.identityPublic)
    }

    private val syncing = Mutex()

    /** Guards [closed] and every write, so nothing is written once [close] has returned. */
    private val gate = Any()
    private var closed = false

    @Volatile private var loaded = false
    @Volatile private var material: Material? = null
    @Volatile private var claiming = false
    @Volatile private var refusedAt = 0L
    @Volatile private var leaving: String? = null

    /** The identity key pair, once the server has confirmed it as the account's. Never touches the network. */
    fun identity(): E2eeProtocol.IdentityKeyPair? = current()?.takeIf { it.registered }?.identity

    fun signedPrekey(): E2eeProtocol.PrekeyPair? = current()?.prekey

    /**
     * The user has just signed in on this installation: the next [sync] makes
     * it the account's, whatever is registered now. Remembered across
     * restarts until that registration has happened.
     */
    fun claim() {
        claiming = true
        // Best effort: without the note the claim still holds until the process ends.
        runCatching { write(CLAIM, "1") }
    }

    /**
     * Brings the directory and this installation into agreement: registers
     * when that is this installation's place, confirms otherwise, and tops
     * up the one-time prekeys. Throws what [directory] throws; nothing is
     * left half-done that the next call does not finish.
     */
    suspend fun sync(): Outcome = syncing.withLock {
        ensureOpen()
        val keys = current() ?: create()
        val status = directory.status()
        val mine = keys.identityKey
        val confirmed = status.identityKey == mine
        val claimed = claiming || vault.contains(CLAIM)
        if (!confirmed) {
            // Somebody signed in elsewhere after this installation registered.
            if (status.identityKey != null && !claimed) return@withLock standDown(keys)
            ensureOpen()
            directory.register(mine, encodeBase64(keys.prekey.public), encodeBase64(keys.signature))
        }
        persist(keys, registered = true)
        if (claimed) {
            write(CLAIM, null)
            claiming = false
        }

        // A registration under a new identity emptied the server's pool.
        val available = if (confirmed) status.oneTimePrekeys else 0
        if (available < REFILL_BELOW) {
            val fresh = List(POOL_TARGET - available) { E2eeProtocol.generateOneTimePrekey() }
            synchronized(gate) {
                check(!closed) { E2eeText.SIGNED_OUT }
                store.addOneTimeKeys(fresh.associate { E2eeProtocol.keyReference(it.public) to it.private })
            }
            // Refused: another installation registered while this one was at work.
            if (!directory.upload(mine, fresh.map { encodeBase64(it.public) })) return@withLock standDown(keys)
        }
        Outcome.Ready
    }

    private fun standDown(keys: Material): Outcome {
        persist(keys, registered = false)
        ensureOpen()
        onReplaced()
        return Outcome.Replaced
    }

    /**
     * [sync] for the moment a message is waiting to be sealed and [identity]
     * is still empty. Trouble reaching the server is thrown — the message is
     * retried, not sent unsealed. A server that answers but refuses leaves
     * this installation without keys, exactly as before it had any, and is
     * not asked again for [RETRY_REFUSED_AFTER_MS]. A replaced installation
     * sends nothing.
     */
    suspend fun ensureRegistered() {
        if (refusedAt != 0L && clock() - refusedAt < RETRY_REFUSED_AFTER_MS) return
        val outcome = try {
            sync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreachable: IOException) {
            throw unreachable
        } catch (_: Exception) {
            refusedAt = clock()
            return
        }
        refusedAt = 0L
        check(outcome == Outcome.Ready) { E2eeText.REPLACED }
    }

    /**
     * Ends this object's life: after this returns nothing is written, on the
     * phone or in the directory. Sign-out calls it before erasing the vault,
     * so a [sync] still in flight cannot put keys back in either place.
     */
    fun close() {
        synchronized(gate) {
            if (closed) return
            // Read while the vault still exists: erasing needs it after the erasure.
            leaving = runCatching { current()?.identityKey }.getOrNull()
            closed = true
        }
    }

    /**
     * After [close]: the identity key this installation may have registered —
     * what erasing the account asks the server to withdraw. It answers once no request
     * of this object is in flight any more, so that withdrawal is the last
     * word and a registration cannot land after it.
     */
    suspend fun published(): String? = syncing.withLock { leaving }

    private fun ensureOpen() {
        synchronized(gate) { check(!closed) { E2eeText.SIGNED_OUT } }
    }

    /** Stored keys, read once. A read that fails is tried again — it must never look like "no keys yet". */
    private fun current(): Material? {
        if (!loaded) {
            synchronized(gate) {
                if (!loaded) {
                    material = vault.read(KEYS)?.let(::parse)
                    loaded = true
                }
            }
        }
        return material
    }

    private fun create(): Material {
        val identity = E2eeProtocol.generateIdentity()
        val prekey = E2eeProtocol.generatePrekeyPair()
        val signature = E2eeProtocol.signPrekey(prekey.public, identity.identityPrivate)
        return persist(Material(identity, prekey, signature, registered = false), registered = false)
    }

    private fun persist(keys: Material, registered: Boolean): Material {
        if (keys.registered == registered && material === keys) return keys
        val next = Material(keys.identity, keys.prekey, keys.signature, registered)
        val fields = listOf(
            keys.identity.identityPrivate, keys.identity.identityPublic,
            keys.prekey.private, keys.prekey.public, keys.signature,
        )
        write(KEYS, (if (registered) REGISTERED else UNREGISTERED) + fields.joinToString("") { SEPARATOR + encodeBase64(it) })
        material = next
        return next
    }

    private fun parse(stored: String): Material? {
        val fields = stored.split(SEPARATOR)
        if (fields.size != 6 || (fields[0] != REGISTERED && fields[0] != UNREGISTERED)) return null
        val bytes = fields.drop(1).map { decodeBase64(it) ?: return null }
        if (!E2eeProtocol.isValidPublicKey(bytes[1]) || !E2eeProtocol.isValidPublicKey(bytes[3])) return null
        return Material(
            identity = E2eeProtocol.IdentityKeyPair(identityPublic = bytes[1], identityPrivate = bytes[0]),
            prekey = E2eeProtocol.PrekeyPair(public = bytes[3], private = bytes[2]),
            signature = bytes[4],
            registered = fields[0] == REGISTERED,
        )
    }

    private fun write(key: String, value: String?) {
        synchronized(gate) {
            check(!closed) { E2eeText.SIGNED_OUT }
            vault.write(key, value, durable = true)
        }
    }

    private companion object {
        const val KEYS = "e2ee.keys"
        const val CLAIM = "e2ee.claim"
        const val REGISTERED = "1"
        const val UNREGISTERED = "0"
        const val SEPARATOR = "."

        /** The server keeps at most 100 per account; half of that leaves room and lasts many new chats. */
        const val POOL_TARGET = 50
        const val REFILL_BELOW = 20
        const val RETRY_REFUSED_AFTER_MS = 60_000L
    }
}
