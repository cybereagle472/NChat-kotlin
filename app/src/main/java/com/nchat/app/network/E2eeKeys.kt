package com.nchat.app.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The server's key directory, as far as this installation's own keys are concerned. Keys are base64 SPKI. */
interface KeyDirectory {
    /** What the account has registered: its identity key (null when none) and the one-time prekeys left. */
    class Status(val identityKey: String?, val oneTimePrekeys: Int)

    /** What the server made of a new signed prekey; see [replaceSignedPrekey]. */
    enum class Replacement {
        /** It is the signed prekey the server hands out from now on. */
        Done,

        /** Refused: the account's registered identity key is another one, or it has none. Nothing changed. */
        Refused,

        /** The server has no way to do this — it is older than this app. Nothing changed. */
        Unavailable;

        companion object {
            /**
             * What the server's refusal of the request means, by HTTP status.
             * Null for every status that is neither of the two: that one
             * stays an error, to be reported and tried again, and is never
             * taken for an answer. 404 is what a server answers that does
             * not have the route yet.
             */
            fun ofRefusal(status: Int): Replacement? = when (status) {
                409 -> Refused
                404 -> Unavailable
                else -> null
            }
        }
    }

    suspend fun status(): Status

    /** Makes these the account's keys, replacing any other installation's. */
    suspend fun register(identityKey: String, signedPrekey: String, signature: String)

    /**
     * Has the server hand out [signedPrekey] in place of the one it has for
     * [identityKey] — and does that only while [identityKey] is the
     * account's registered identity key. It never makes these the account's
     * keys: that is [register], and the difference is the point. Between an
     * installation's asking whether it still is the account's and its
     * sending this, the user may have signed in somewhere else.
     */
    suspend fun replaceSignedPrekey(identityKey: String, signedPrekey: String, signature: String): Replacement

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
 * **The signed prekey is replaced every week.** It is the one key every
 * first message to this account is sealed with, whether a one-time prekey
 * was at hand or not, so it must not live as long as the account does. Once
 * it is [REPLACE_AFTER_MS] old by this phone's clock, [sync] makes another,
 * signed by the same identity key, and has the server hand that out instead
 * ([KeyDirectory.replaceSignedPrekey] — never a registration: replacing a
 * prekey must not be able to move an account). The identity key does not
 * change, so contacts notice nothing and no conversation is interrupted.
 *
 * The replaced one is kept, because a first message sealed for it may still
 * be on its way: for [KEEP_REPLACED_MS] at the least. A replacement removes
 * from the phone the keys that were replaced that long ago — all but the one
 * replaced last of them, so that a date written by a clock that was wrong
 * cannot cost the key that was in use until then. With a replacement every
 * week that is six weeks; [MAX_REPLACED_KEPT] at most are kept. A first
 * message that arrives after its key was removed does not open. A session
 * that has begun does not need the signed prekey any more.
 *
 * At every moment of a replacement the phone opens what is sealed for the
 * key the server hands out and for the one before, whatever is interrupted:
 * the new key is stored and in use before the server hears of it, and stays
 * marked "to be handed out" until the phone has heard the server's answer
 * and written it down. Until then every [sync] sends that same key again.
 *
 * **Two entries.** [KEYS] is the record every build of the app reads: the
 * identity key and one signed prekey. It keeps its form, and names the
 * signed prekey the server hands out, so an older build installed over this
 * one finds keys it can use. [SIGNED_PREKEYS] is what this build goes by:
 * the signed prekey in use and when it was made, the one about to take its
 * place, and the replaced ones. Where it is missing — keys made by a build
 * that never replaced them — or damaged, or written for another identity
 * key, the record is all there is; it does not say how old its signed prekey
 * is, and that one is replaced at the next [sync].
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

    /** A signed prekey, with the signature of the identity key that goes to the server with it. */
    private class Signed(val pair: E2eeProtocol.PrekeyPair, val signature: ByteArray) {
        val reference: String = E2eeProtocol.keyReference(pair.public)
    }

    /** A signed prekey the server has not handed out since [since], kept for what was sealed with it before. */
    private class Replaced(val pair: E2eeProtocol.PrekeyPair, val since: Long) {
        val reference: String = E2eeProtocol.keyReference(pair.public)
    }

    /** What [KEYS] holds. */
    private class Record(val identity: E2eeProtocol.IdentityKeyPair, val registered: Boolean, val prekey: Signed)

    /** What [SIGNED_PREKEYS] holds. */
    private class SignedPrekeys(val prekey: Signed, val since: Long, val next: Signed?, val replaced: List<Replaced>)

    private class Material(
        val identity: E2eeProtocol.IdentityKeyPair,
        val registered: Boolean,
        /** The signed prekey the server hands out — or did, until it took [next] and this phone has yet to hear of it. */
        val prekey: Signed,
        /** When [prekey] was made, by this phone's clock; 0 when nobody wrote that down. */
        val since: Long,
        /** The one that is to take its place: stored, in use for opening, and perhaps handed out already. */
        val next: Signed?,
        /** Newest first. */
        val replaced: List<Replaced>,
    ) {
        val identityKey: String get() = encodeBase64(identity.identityPublic)

        fun asRegistered(registered: Boolean) = Material(identity, registered, prekey, since, next, replaced)

        fun signedPrekey(reference: String): E2eeProtocol.PrekeyPair? {
            if (prekey.reference == reference) return prekey.pair
            if (next != null && next.reference == reference) return next.pair
            return replaced.firstOrNull { it.reference == reference }?.pair
        }
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

    /**
     * The signed prekey a first message names by [reference]
     * ([E2eeProtocol.keyReference]), as far as this phone holds it: the one
     * the server hands out, the one about to take its place, and the
     * replaced ones that are kept. Never touches the network.
     */
    fun signedPrekey(reference: String): E2eeProtocol.PrekeyPair? = current()?.signedPrekey(reference)

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
     * when that is this installation's place, confirms otherwise, tops up
     * the one-time prekeys, and replaces the signed prekey when it is due.
     * Throws what [directory] throws; nothing is left half-done that the
     * next call does not finish.
     */
    suspend fun sync(): Outcome = syncing.withLock {
        ensureOpen()
        val held = current() ?: create()
        val status = directory.status()
        val mine = held.identityKey
        val confirmed = status.identityKey == mine
        val claimed = claiming || vault.contains(CLAIM)
        if (!confirmed) {
            // Somebody signed in elsewhere after this installation registered.
            if (status.identityKey != null && !claimed) return@withLock standDown(held)
            ensureOpen()
            directory.register(mine, encodeBase64(held.prekey.pair.public), encodeBase64(held.prekey.signature))
        }
        val keys = persist(held, registered = true)
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
        // Last: trouble with it holds up nothing of the above.
        if (due(keys)) replaceSignedPrekey(keys) else Outcome.Ready
    }

    /**
     * Whether the signed prekey is to be replaced now: a replacement was
     * begun and not finished, or the key is [REPLACE_AFTER_MS] old. A key
     * whose age is not known is not taken for a young one — neither one made
     * at a time nobody wrote down, nor one dated later than now, by a clock
     * that has been put right since.
     */
    private fun due(keys: Material): Boolean {
        if (keys.next != null) return true
        val now = clock()
        return keys.since <= 0L || keys.since > now || now - keys.since >= REPLACE_AFTER_MS
    }

    private suspend fun replaceSignedPrekey(held: Material): Outcome {
        // The private half first, and in use: from the moment the server has the new key, senders are
        // handed it. A key that is waiting already is the one to send — a new one for every attempt
        // would leave the phone holding keys the server may or may not have handed out.
        val next = held.next ?: newSignedPrekey(held.identity)
        val keys = if (held.next != null) held else store(Material(held.identity, held.registered, held.prekey, held.since, next, held.replaced))
        ensureOpen()
        when (directory.replaceSignedPrekey(keys.identityKey, encodeBase64(next.pair.public), encodeBase64(next.signature))) {
            KeyDirectory.Replacement.Done -> {
                val now = clock()
                val replaced = (listOf(Replaced(keys.prekey.pair, now)) + stillKept(keys.replaced, now)).take(MAX_REPLACED_KEPT)
                val settled = Material(keys.identity, keys.registered, next, now, null, replaced)
                // The record older builds read, then the list this build goes by. Stopped between the
                // two, the list still says "to be handed out", and the next look sends the key again.
                write(KEYS, record(settled))
                store(settled)
                return Outcome.Ready
            }
            // An older server. The key waits; a registration in its place is the one thing not to do.
            KeyDirectory.Replacement.Unavailable -> return Outcome.Ready
            KeyDirectory.Replacement.Refused -> {
                // The registered identity key was not this one at that moment. Another phone's — then
                // this one stands down, now and not at its next routine look. Nobody's — then the
                // directory has lost the keys, nobody took the account, and the next look puts them
                // back. This one's after all — then the next look tries again.
                val registered = directory.status().identityKey
                if (registered != null && registered != keys.identityKey) return standDown(keys)
                throw IOException("the key directory changed while the signed prekey was being replaced")
            }
        }
    }

    /**
     * Which of the [replaced] keys (newest first) stay on the phone at
     * [now]: every one replaced less than [KEEP_REPLACED_MS] ago, and of the
     * others the one replaced last — the first of them in the list, whatever
     * the dates say. A date is only as good as the phone's clock was when it
     * was written: after an hour with the clock years behind, the key that
     * was in use until that hour is dated years back. (Signal's app keeps
     * "the youngest of those" as well.)
     */
    private fun stillKept(replaced: List<Replaced>, now: Long): List<Replaced> {
        val kept = ArrayList<Replaced>()
        var oneOfTheOthers = false
        for (old in replaced) {
            val lately = now - old.since < KEEP_REPLACED_MS
            if (lately || !oneOfTheOthers) kept += old
            if (!lately) oneOfTheOthers = true
        }
        return kept
    }

    private fun newSignedPrekey(identity: E2eeProtocol.IdentityKeyPair): Signed {
        val pair = E2eeProtocol.generatePrekeyPair()
        return Signed(pair, E2eeProtocol.signPrekey(pair.public, identity.identityPrivate))
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
                    material = load()
                    loaded = true
                }
            }
        }
        return material
    }

    private fun load(): Material? {
        val record = vault.read(KEYS)?.let(::parseRecord) ?: return null
        val list = vault.read(SIGNED_PREKEYS)?.let { parseSignedPrekeys(it, record.identity) }
            // Keys of a build that never replaced its signed prekey, or a list that is damaged or was
            // written for another identity key: the record is all there is.
            ?: return Material(record.identity, record.registered, record.prekey, since = 0L, next = null, replaced = emptyList())
        val known = record.prekey.reference
        val listed = known == list.prekey.reference || known == list.next?.reference || list.replaced.any { it.reference == known }
        if (listed) return Material(record.identity, record.registered, list.prekey, list.since, list.next, list.replaced)
        // The record names a signed prekey the list does not know, which no order of this build's writes
        // leaves behind. Any of them may be the one the server hands out, so nothing either entry holds
        // is given up, and nothing depends on a write to come: the record's key stays in the record and
        // the list's stay in the list until the list has been written with all of them. The next look
        // does that — the record's key counts as the one in use, of an age nobody knows, and nothing
        // as waiting, so that look makes a new key, and it stores the list before the server hears of
        // it and before the record is given another key.
        val now = clock()
        return Material(
            record.identity, record.registered, record.prekey, since = 0L, next = null,
            replaced = listOfNotNull(list.next?.let { Replaced(it.pair, now) }, Replaced(list.prekey.pair, now)) + list.replaced,
        )
    }

    private fun create(): Material {
        val identity = E2eeProtocol.generateIdentity()
        val keys = Material(identity, registered = false, newSignedPrekey(identity), since = clock(), next = null, replaced = emptyList())
        // The record first: it is what makes these this phone's keys. Without the list they are still
        // whole — the signed prekey then merely counts as due.
        return store(persist(keys, registered = false))
    }

    /** Writes [KEYS] with the registered flag as given. The signed prekey in it is the one in use. */
    private fun persist(keys: Material, registered: Boolean): Material {
        if (keys.registered == registered && material === keys) return keys
        val next = keys.asRegistered(registered)
        write(KEYS, record(next))
        material = next
        return next
    }

    /** Writes [SIGNED_PREKEYS], and makes [keys] what this object answers with. */
    private fun store(keys: Material): Material {
        write(SIGNED_PREKEYS, signedPrekeys(keys))
        material = keys
        return keys
    }

    /** [KEYS], in the form every build of the app has written it: a flag and five fields. */
    private fun record(keys: Material): String {
        val fields = listOf(
            keys.identity.identityPrivate, keys.identity.identityPublic,
            keys.prekey.pair.private, keys.prekey.pair.public, keys.prekey.signature,
        )
        return (if (keys.registered) REGISTERED else UNREGISTERED) + fields.joinToString("") { SEPARATOR + encodeBase64(it) }
    }

    private fun parseRecord(stored: String): Record? {
        val fields = stored.split(SEPARATOR)
        if (fields.size != 6 || (fields[0] != REGISTERED && fields[0] != UNREGISTERED)) return null
        val bytes = fields.drop(1).map { decodeBase64(it) ?: return null }
        if (!E2eeProtocol.isValidPublicKey(bytes[1]) || !E2eeProtocol.isValidPublicKey(bytes[3])) return null
        return Record(
            identity = E2eeProtocol.IdentityKeyPair(identityPublic = bytes[1], identityPrivate = bytes[0]),
            registered = fields[0] == REGISTERED,
            prekey = Signed(E2eeProtocol.PrekeyPair(public = bytes[3], private = bytes[2]), bytes[4]),
        )
    }

    private fun signedPrekeys(keys: Material): String = buildJsonObject {
        put(FORMAT, LIST_FORMAT)
        // Whose they are. Under another identity key they open nothing, and the server refuses their signatures.
        put(IDENTITY, keys.identityKey)
        put(PREKEY, entry(keys.prekey.pair) { put(SIGNATURE, encodeBase64(keys.prekey.signature)) })
        put(SINCE, keys.since)
        keys.next?.let { next -> put(NEXT, entry(next.pair) { put(SIGNATURE, encodeBase64(next.signature)) }) }
        put(REPLACED, buildJsonArray { for (old in keys.replaced) add(entry(old.pair) { put(SINCE, old.since) }) })
    }.toString()

    private fun entry(pair: E2eeProtocol.PrekeyPair, more: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put(PRIVATE, encodeBase64(pair.private))
        put(PUBLIC, encodeBase64(pair.public))
        more()
    }

    /** Null for anything that is not a whole list of this format, written for [identity]. */
    private fun parseSignedPrekeys(stored: String, identity: E2eeProtocol.IdentityKeyPair): SignedPrekeys? {
        try {
            val list = Json.parseToJsonElement(stored) as? JsonObject ?: return null
            if (list.number(FORMAT) != LIST_FORMAT.toLong()) return null
            if (list.text(IDENTITY) != encodeBase64(identity.identityPublic)) return null
            val prekey = signed(list[PREKEY]) ?: return null
            val since = list.number(SINCE) ?: return null
            val next = if (list.containsKey(NEXT)) signed(list[NEXT]) ?: return null else null
            val replaced = (list[REPLACED] as? JsonArray ?: return null).map { entry ->
                Replaced(prekeyPair(entry) ?: return null, (entry as JsonObject).number(SINCE) ?: return null)
            }
            return SignedPrekeys(prekey, since, next, replaced)
        } catch (_: Exception) {
            return null
        }
    }

    private fun signed(entry: JsonElement?): Signed? {
        val signature = (entry as? JsonObject)?.text(SIGNATURE)?.let { decodeBase64(it) } ?: return null
        return Signed(prekeyPair(entry) ?: return null, signature)
    }

    private fun prekeyPair(entry: JsonElement?): E2eeProtocol.PrekeyPair? {
        val fields = entry as? JsonObject ?: return null
        val public = fields.text(PUBLIC)?.let { decodeBase64(it) } ?: return null
        val private = fields.text(PRIVATE)?.let { decodeBase64(it) } ?: return null
        if (!E2eeProtocol.isValidPublicKey(public)) return null
        return E2eeProtocol.PrekeyPair(public = public, private = private)
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private fun write(key: String, value: String?) {
        synchronized(gate) {
            check(!closed) { E2eeText.SIGNED_OUT }
            vault.write(key, value, durable = true)
        }
    }

    private companion object {
        const val KEYS = "e2ee.keys"
        const val SIGNED_PREKEYS = "e2ee.spk"
        const val CLAIM = "e2ee.claim"
        const val REGISTERED = "1"
        const val UNREGISTERED = "0"
        const val SEPARATOR = "."

        const val LIST_FORMAT = 1
        const val FORMAT = "v"
        const val IDENTITY = "identity"
        const val PREKEY = "prekey"
        const val NEXT = "next"
        const val REPLACED = "replaced"
        const val SINCE = "since"
        const val PRIVATE = "private"
        const val PUBLIC = "public"
        const val SIGNATURE = "signature"

        /**
         * A signed prekey is replaced once it is a week old. X3DH asks for
         * "weekly or monthly"; a week is the short end of that, and one
         * request a week costs a phone nothing.
         */
        const val REPLACE_AFTER_MS = 7L * 24L * 60L * 60L * 1000L

        /** How long a replaced one is kept at the least: a first message sealed for it may be that long on its way. */
        const val KEEP_REPLACED_MS = 30L * 24L * 60L * 60L * 1000L

        /** …and how many at most. A week apart there are six; more than this is a clock that jumps. */
        const val MAX_REPLACED_KEPT = 12

        /** The server keeps at most 100 per account; half of that leaves room and lasts many new chats. */
        const val POOL_TARGET = 50
        const val REFILL_BELOW = 20
        const val RETRY_REFUSED_AFTER_MS = 60_000L
    }
}
