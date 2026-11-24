package com.nchat.app.network

import java.security.MessageDigest

/**
 * End-to-end encryption as the rest of the app uses it. There is always one
 * of these: where sealing is not possible it fails, it is never absent — an
 * absent encryption layer would read as "send it in the clear".
 */
interface SealedMessaging {
    /** Runs [block] as one unit of work; wrap whole pages of history in it. */
    fun <T> batch(block: () -> T): T

    /** The content of a sealed message, or null when it cannot be read on this phone. Never throws. */
    fun reveal(peerId: String, messageId: String, clientId: String?, mine: Boolean, envelope: String): E2eePayload?

    /**
     * Seals [payload] and hands it to [send]. Null — without [send] being
     * called — means the message may go out unsealed; every failure is
     * thrown, and then the message must not be sent in any other form.
     */
    suspend fun <T : Any> sendSealed(
        peerId: String,
        clientId: String,
        payload: E2eePayload,
        keysChanged: (Throwable) -> Boolean,
        editOf: String? = null,
        send: suspend (E2eeClient.Sealed) -> T,
    ): T?

    /**
     * Whether what is sent to [peerId] next will be sealed — asked before a
     * file is uploaded, which has to be sealed or not and cannot be decided
     * afterwards. True starts the session if there is none yet. False means
     * the conversation is not encrypted and a file may go as it always did.
     * Every failure is thrown, as in [sendSealed], and then nothing may be
     * uploaded readable.
     */
    suspend fun willSeal(peerId: String): Boolean

    /**
     * What this phone knows about the encryption of the conversation with
     * [peerId]; null when nothing was ever encrypted with them. Never throws.
     */
    fun security(peerId: String): PeerSecurity?

    /**
     * The number both people compare (see [SecurityCode]), for the key that
     * [security] reported as [key]. Null when that is no longer the peer's
     * key, or this phone has no confirmed keys of its own. Never throws.
     */
    fun securityCode(peerId: String, key: String): String?

    /**
     * The user has seen that the peer's keys changed. False — and nothing
     * changes — when the peer's key is no longer [key], the key the notice
     * on the screen was about. Never throws.
     */
    fun acceptChange(peerId: String, key: String): Boolean

    /**
     * The user compared the code shown for [key] with the other person's
     * ([verified]), or takes that back. Verifying also settles a pending
     * change. False when the peer's key is no longer [key]. Never throws.
     */
    fun setVerified(peerId: String, key: String, verified: Boolean): Boolean
}

/**
 * What a phone knows about the encryption of one conversation.
 *
 * [key] is the other person's identity key (base64) as this phone last saw
 * it — null when it holds none at the moment, because its record of them
 * could not be read. [verified]: the user compared the security code for
 * that key. [changed]: the key took another's place and the user has not
 * acknowledged it yet. [held]: it took the place of a verified key, and
 * nothing is sent until the user has looked. [since]: when encryption with
 * this person began, by this phone's clock.
 */
data class PeerSecurity(
    val key: String?,
    val verified: Boolean,
    val changed: Boolean,
    val held: Boolean,
    val since: Long,
)

/**
 * Seal/open orchestration for direct messages.
 *
 * **Capability.** A message is sealed when both ends have registered keys:
 * the peer's bundle can be fetched and this device has its own. Old clients
 * have no keys and keep exchanging plaintext. The bundle is fetched only when
 * no usable session exists (each fetch spends one of the peer's one-time
 * prekeys), so steady-state sends are local ratchet work.
 *
 * **No downgrade.** Once any session with a peer has existed, this client
 * never answers "send it in the clear" for that peer again — a server that
 * suddenly claims the peer has no keys gets an error, not plaintext. A bundle
 * that does not verify throws for the same reason.
 *
 * **One envelope, one place.** A new message is sealed for its
 * `client_message_id`; an edit is sealed for the server id of the message it
 * replaces. An envelope opens nowhere else, so the server — which stores the
 * rows and swaps a row's envelope when a message is edited — cannot move
 * content between messages or turn an edit into a message of its own.
 *
 * **Sessions.** Several sessions per peer are kept, so messages sealed under
 * an older one still open when both sides started a session at the same
 * moment or the peer reinstalled. The session that last opened a message
 * becomes the sending session, which is what makes both sides converge.
 *
 * **Durability.** Opened content is archived before the ratchet state that
 * consumed its key is stored, and a one-time prekey is forgotten only after
 * the session it created is stored — whatever is interrupted, no message is
 * left with its key spent and nothing to show for it. A sealed message's
 * content and the advanced state are on stable storage before the envelope
 * is handed to the caller.
 *
 * **Sealed once.** An envelope is kept until the server has it, and a retry
 * sends that same envelope. Sealing the content again on every attempt would
 * use up a message number each time, and a peer refuses a message that is
 * too many numbers ahead of the last one it saw.
 *
 * **Whose keys.** The first identity key a peer presents is taken on trust
 * — two phones that have never met can do nothing else — and kept. From
 * then on another key is never taken silently: the user is told
 * ([PeerSecurity.changed]), and where they had verified the old key,
 * nothing more is sealed for that peer until they have looked
 * ([PeerSecurity.held]); what arrives is still shown. A server that hands
 * out keys of its own from the very first message is caught only by the
 * two people comparing their [securityCode] — which is what it is for.
 *
 * Every call is safe from any thread. The receive path ([reveal]) never
 * touches the network and never throws.
 */
class E2eeClient(
    /** The account this client belongs to; bound into every handshake. */
    private val localId: String,
    private val store: E2eeStore,
    private val archive: E2eeArchive,
    private val identity: () -> E2eeProtocol.IdentityKeyPair?,
    private val signedPrekey: () -> E2eeProtocol.PrekeyPair?,
    private val fetchBundle: suspend (peerId: String) -> E2eeProtocol.PeerBundle?,
    /**
     * Called when a session has to be started and [identity] is still empty:
     * the moment to wait for key registration that is under way (right after
     * sign-in). It reports nothing — [identity] is simply asked again.
     */
    private val prepare: suspend () -> Unit = {},
    /** This phone's clock; dates the beginning of encryption with a peer. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * What [security] answers for this peer has changed. Called after the
     * unit of work that changed it, on the thread that did the work, with no
     * lock held: the listener may ask this client anything.
     */
    private val onSecurityChanged: (peerId: String) -> Unit = {},
) : SealedMessaging {

    /**
     * A sealed message ready to send. [recipientIdentity] is the identity key
     * (base64 SPKI) the envelope was sealed for; it goes to the server with
     * the message so a send to keys the recipient no longer has is refused.
     */
    class Sealed(val envelope: String, val recipientIdentity: String)

    private val lock = Any()

    // Everything below is guarded by [lock].
    private var closed = false

    // Unit of work.
    private var depth = 0
    private val records = HashMap<String, E2eeStore.PeerRecord?>()
    private val changedPeers = LinkedHashSet<String>()
    private val spentOneTimeKeys = LinkedHashSet<String>()

    /** Peers whose record must be on stable storage when the unit of work ends, not merely written. */
    private val durablePeers = LinkedHashSet<String>()

    /** Peers to announce through [onSecurityChanged] once the unit of work is over. */
    private val securityChanges = LinkedHashSet<String>()

    /**
     * Per peer: messages that would not open. They are not tried again until
     * something changes for that peer (a message opens, a session appears) or
     * the app restarts — history from a previous installation would otherwise
     * cost futile key agreement on every refresh.
     */
    private val unreadable = HashMap<String, LinkedHashSet<String>>()

    /**
     * Runs [block] as one unit of work: every message it reveals shares one
     * archive sync and one write per peer. Wrap whole pages of history in it —
     * opening a hundred new messages then costs one sync instead of a hundred.
     */
    override fun <T> batch(block: () -> T): T {
        var announce: List<String> = emptyList()
        try {
            return synchronized(lock) {
                depth++
                try {
                    block()
                } finally {
                    depth--
                    if (depth == 0) {
                        commit()
                        if (securityChanges.isNotEmpty()) {
                            announce = securityChanges.toList()
                            securityChanges.clear()
                        }
                    }
                }
            }
        } finally {
            // After the work and outside the lock; a listener that fails is the listener's problem.
            for (peerId in announce) {
                try {
                    onSecurityChanged(peerId)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Seals [payload] for [peerId], or returns null when the message may go
     * out unsealed: nothing was ever encrypted with this peer AND one of the
     * two ends has no registered keys. Null is never returned for a peer a
     * session has existed with.
     *
     * [clientId] is the message's `client_message_id`: our own sealed
     * messages are found again under it, since only the recipient can open
     * the envelope itself. [editOf] is the server id of the message this
     * content replaces, or null for a new message.
     *
     * Throws when sealing is required but not possible; the caller must not
     * send the message in any other form.
     */
    suspend fun seal(peerId: String, clientId: String, payload: E2eePayload, editOf: String? = null): Sealed? {
        require(peerId.isNotBlank() && clientId.isNotBlank() && editOf?.isBlank() != true) {
            "peer and message ids are required"
        }
        val target = targetOf(clientId, editOf)
        batch { sealOnCurrent(peerId, clientId, payload, target) }?.let { return it }
        // No session to seal on: one is started, or the message may go out unsealed.
        if (!startSession(peerId)) return null
        return batch { sealOnCurrent(peerId, clientId, payload, target) } ?: error(E2eeText.PEER_HAS_NO_KEYS)
    }

    override suspend fun willSeal(peerId: String): Boolean {
        require(peerId.isNotBlank()) { "a peer id is required" }
        if (batch { sendableFor(peerId) }) return true
        return startSession(peerId)
    }

    /** Whether there is a session to seal on for [peerId]. Throws for a closed client and for a held contact. */
    private fun sendableFor(peerId: String): Boolean {
        check(!closed) { E2eeText.SIGNED_OUT }
        val record = recordFor(peerId) ?: return false
        // A contact the user had verified now presents other keys: nothing is sealed for them,
        // and no bundle is fetched, until the user has looked.
        if (record.trust?.held == true) error(E2eeText.CODE_CHANGED)
        return record.sendable() != null
    }

    /**
     * Starts a session with [peerId] from their key bundle and stores it.
     * True when there is a session to seal on afterwards. False when the
     * conversation may stay unsealed: nothing was ever encrypted with this
     * peer and one of the two ends has no registered keys. Throws when a
     * session is required and cannot be had.
     */
    private suspend fun startSession(peerId: String): Boolean {
        // A session takes this device's own keys.
        // Settled before the fetch: a bundle we cannot use must not cost the peer a one-time prekey.
        val keys = identity() ?: run {
            prepare()
            identity()
        }
        if (keys == null) {
            if (batch { recordFor(peerId) } == null) return false
            error(E2eeText.NOT_READY)
        }
        val bundle = fetchBundle(peerId)
        return batch {
            // Another send, or a handshake from the peer, may have got here first.
            if (sendableFor(peerId)) return@batch true
            val existing = recordFor(peerId)
            when {
                bundle != null -> {
                    val session = try {
                        E2eeProtocol.initiate(keys, bundle, localId, peerId)
                    } catch (unverified: IllegalStateException) {
                        throw IllegalStateException(E2eeText.PEER_KEYS_UNVERIFIED, unverified)
                    }
                    val record = existing ?: E2eeStore.PeerRecord()
                    noteIdentity(peerId, record, bundle.identityPublic, first = existing == null)
                    record.sessions.add(0, E2eeStore.StoredSession(session, retired = false))
                    records[peerId] = record
                    // Stored at once, held or not: the notice must outlive a restart, a file
                    // may take minutes to upload before the first message is sealed, and the
                    // next attempt must not cost the peer another one-time prekey.
                    store.savePeer(peerId, record, durable = true)
                    changedPeers.remove(peerId)
                    if (record.trust?.held == true) error(E2eeText.CODE_CHANGED)
                    true
                }
                existing != null -> error(E2eeText.PEER_HAS_NO_KEYS)
                else -> false
            }
        }
    }

    /**
     * [seal] and [send] as one step, for the caller that posts to the server.
     * Returns null without calling [send] when the message may go out
     * unsealed. When [send] fails with what [keysChanged] recognises as "the
     * recipient has registered different keys" (a reinstall, a new phone),
     * the sessions with that peer are retired and the message is sealed once
     * more for the keys the peer has now; any other failure, and a second
     * refusal, are the caller's. Until [send] has returned, calling this
     * again for the same message and content sends the same envelope.
     */
    override suspend fun <T : Any> sendSealed(
        peerId: String,
        clientId: String,
        payload: E2eePayload,
        keysChanged: (Throwable) -> Boolean,
        editOf: String?,
        send: suspend (Sealed) -> T,
    ): T? {
        val sealed = seal(peerId, clientId, payload, editOf) ?: return null
        val result = try {
            send(sealed)
        } catch (failure: Throwable) {
            if (!keysChanged(failure)) throw failure
            retireSessions(peerId)
            // Never null here: a peer with sessions, retired or not, is sealed for or not sent to.
            send(seal(peerId, clientId, payload, editOf) ?: error(E2eeText.PEER_HAS_NO_KEYS))
        }
        delivered(peerId, targetOf(clientId, editOf))
        return result
    }

    /** What an envelope is sealed for: a new message, or the edit of a stored one. */
    private fun targetOf(clientId: String, editOf: String?): String = if (editOf == null) ORIGINAL + clientId else EDIT + editOf

    private fun sealOnCurrent(peerId: String, clientId: String, payload: E2eePayload, target: String): Sealed? {
        if (!sendableFor(peerId)) return null
        return sealOn(peerId, recordFor(peerId) ?: return null, clientId, payload, target)
    }

    private fun sealOn(
        peerId: String,
        record: E2eeStore.PeerRecord,
        clientId: String,
        payload: E2eePayload,
        target: String,
    ): Sealed {
        val session = record.sendable()?.session ?: error("no sending session")
        val recipient = encodeBase64(session.remoteIdentityKey())
        val plaintext = payload.encode()
        val content = encodeBase64(MessageDigest.getInstance("SHA-256").digest(plaintext))

        // The same content for the same message, not yet known to have arrived: the same envelope.
        val kept = record.unsent[target]
        if (kept != null && kept.content == content && kept.recipientIdentity == recipient) {
            val key = archiveKey(SENT, clientId, kept.envelope)
            if ((archive.recent(peerId, key) ?: archive.find(peerId, key)) == null) {
                // Our own copy went missing; without it this phone could not show what it sent.
                archive.put(peerId, key, payload)
                archive.flush()
            }
            return Sealed(kept.envelope, recipient)
        }
        try {
            val envelope = E2eeProtocol.seal(session, plaintext, target.toByteArray(Charsets.UTF_8)).toJson()
            archive.put(peerId, archiveKey(SENT, clientId, envelope), payload)
            archive.flush()
            record.unsent.remove(target)
            record.unsent[target] = E2eeStore.Unsent(content, envelope, recipient)
            store.savePeer(peerId, record, durable = true)
            changedPeers.remove(peerId)
            return Sealed(envelope, recipient)
        } catch (failure: Throwable) {
            // The in-memory session advanced but was not stored: drop it so the stored state stays the truth.
            records.remove(peerId)
            changedPeers.remove(peerId)
            throw failure
        }
    }

    /** The server has this envelope; it need not be kept for a retry any longer. */
    private fun delivered(peerId: String, target: String) {
        batch {
            if (closed) return@batch
            val record = recordFor(peerId) ?: return@batch
            // Not synced: losing this write only keeps an envelope nobody will ask for.
            if (record.unsent.remove(target) != null) changedPeers += peerId
        }
    }

    /**
     * The content of a sealed message, or null when it cannot be read on this
     * device. A message goes through the ratchet exactly once; from then on
     * it is answered from the archive.
     *
     * The archive entry is bound to the message (`messageId`, or our own
     * `clientId`) AND to the exact envelope: an old envelope presented as a
     * new message finds nothing there and is refused by the ratchet as a
     * replay. [messageId] and [clientId] are the row's ids as the server
     * reports them; an envelope sealed for another row does not open.
     */
    override fun reveal(peerId: String, messageId: String, clientId: String?, mine: Boolean, envelope: String): E2eePayload? {
        val reference = if (mine) clientId else messageId
        if (peerId.isBlank() || messageId.isBlank() || reference.isNullOrBlank()) return null
        return try {
            batch {
                if (closed) return@batch null
                val key = archiveKey(if (mine) SENT else RECEIVED, reference, envelope)
                archive.recent(peerId, key)
                    ?: if (unreadable[peerId]?.contains(key) == true) null
                    else (openOrFind(peerId, key, messageId, clientId, mine, envelope) ?: giveUp(peerId, key))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openOrFind(
        peerId: String,
        key: String,
        messageId: String,
        clientId: String?,
        mine: Boolean,
        envelope: String,
    ): E2eePayload? {
        // Our own envelope is sealed for the peer: only the archive has its content.
        if (mine) return archive.find(peerId, key)
        // What this row may hold: the message as first sent, or an edit of it.
        val bindings = listOfNotNull(clientId?.takeIf { it.isNotBlank() }?.let { ORIGINAL + it }, EDIT + messageId)
            .map { it.toByteArray(Charsets.UTF_8) }
        // A new message is the common case, and the ratchet settles it without reading old history.
        return open(peerId, key, envelope, bindings) ?: archive.find(peerId, key)
    }

    private fun giveUp(peerId: String, key: String): E2eePayload? {
        val failures = unreadable.getOrPut(peerId) { LinkedHashSet() }
        failures += key
        val eldest = failures.iterator()
        while (failures.size > MAX_REMEMBERED_FAILURES && eldest.hasNext()) {
            eldest.next()
            eldest.remove()
        }
        return null
    }

    private fun open(peerId: String, key: String, envelopeJson: String, bindings: List<ByteArray>): E2eePayload? {
        val envelope = E2eeProtocol.Envelope.fromJson(envelopeJson) ?: return null
        val known = recordFor(peerId)
        val record = known ?: E2eeStore.PeerRecord()

        for (index in record.sessions.indices) {
            val stored = record.sessions[index]
            val plaintext = bindings.firstNotNullOfOrNull { E2eeProtocol.open(stored.session, envelope, it) } ?: continue
            if (index > 0 && !stored.retired) {
                // The peer is using this session: make it ours for sending too.
                record.sessions.removeAt(index)
                record.sessions.add(0, stored)
            }
            val payload = E2eePayload.decode(plaintext)
            accept(peerId, record, key, payload)
            return payload
        }

        // No session opens it. A handshake means the peer started a new one —
        // first contact, a reinstall, or both sides at the same moment.
        val handshake = envelope.ek ?: return null
        val handshakeReference = E2eeProtocol.keyReference(handshake)
        if (handshakeReference in record.seenHandshakes) return null
        val keys = identity() ?: return null
        val prekey = signedPrekey() ?: return null
        // Cheapest test first: a handshake built for a prekey we no longer hold
        // (anything sent to a previous installation) ends here, before the
        // one-time prekey pool is read from storage.
        if (envelope.spk != E2eeProtocol.keyReference(prekey.public)) return null
        val oneTimeReference = envelope.otk
        val oneTimePrivate = if (oneTimeReference != null) store.oneTimeKey(oneTimeReference) ?: return null else null
        // A handshake that does not authenticate changes nothing — junk cannot spend a one-time prekey.
        val opened = bindings.firstNotNullOfOrNull {
            E2eeProtocol.openFirst(keys, prekey, oneTimePrivate, envelope, localId, peerId, it)
        } ?: return null

        val peerIdentity = opened.session.remoteIdentityKey()
        noteIdentity(peerId, record, peerIdentity, first = known == null)
        for (stored in record.sessions) {
            // A different identity key means a new installation; the old one is not listening any more.
            if (!stored.session.remoteIdentityKey().contentEquals(peerIdentity)) stored.retired = true
        }
        record.sessions.add(0, E2eeStore.StoredSession(opened.session, retired = false))
        record.seenHandshakes += handshakeReference
        val payload = E2eePayload.decode(opened.plaintext)
        if (accept(peerId, record, key, payload) && oneTimeReference != null) spentOneTimeKeys += oneTimeReference
        return payload
    }

    /**
     * Archive first; only then does the advanced ratchet state become
     * something to store. Returns false when the archive write failed: the
     * in-memory state for this peer is then discarded, so the key this
     * message consumed is never recorded as spent. The caller still shows the
     * content — the message simply opens again next time.
     */
    private fun accept(peerId: String, record: E2eeStore.PeerRecord, key: String, payload: E2eePayload): Boolean {
        try {
            archive.put(peerId, key, payload)
        } catch (_: Exception) {
            records.remove(peerId)
            changedPeers.remove(peerId)
            return false
        }
        records[peerId] = record
        changedPeers += peerId
        unreadable.remove(peerId)
        return true
    }

    /**
     * Ends a unit of work. Order is the whole point: archive to stable
     * storage, then ratchet state, then spent one-time prekeys. Stopping
     * after any step leaves a state from which every message still opens.
     */
    private fun commit() {
        try {
            if (changedPeers.isNotEmpty() || spentOneTimeKeys.isNotEmpty()) {
                archive.flush()
                for (peerId in changedPeers) {
                    // Not synced as a rule: if this write is lost, the keys are derived again and the
                    // archive answers first. What is known about a peer's key is not found out twice.
                    records[peerId]?.let { store.savePeer(peerId, it, durable = peerId in durablePeers) }
                }
                store.removeOneTimeKeys(spentOneTimeKeys)
            }
        } catch (_: Exception) {
            // Nothing newer was stored than what the archive holds; the same messages open again next time.
        } finally {
            records.clear()
            changedPeers.clear()
            spentOneTimeKeys.clear()
            durablePeers.clear()
        }
    }

    // --------------------------------------------------------- whose keys

    /**
     * A session built with [identity] as the peer's key is about to join
     * [record]; [first] when no record of this peer existed. The first key
     * is taken on trust. A different one later is taken too — the session
     * exists — but never silently: see [PeerSecurity].
     */
    private fun noteIdentity(peerId: String, record: E2eeStore.PeerRecord, identity: ByteArray, first: Boolean) {
        val trust = record.trust ?: when {
            first -> E2eeStore.Trust(identity.copyOf(), since = clock())
            // A record that says nothing about the peer's key: its newest session knows one…
            record.sessions.isNotEmpty() -> E2eeStore.Trust(record.sessions.first().session.remoteIdentityKey())
            // …and with no session either, nothing is known. The user is told so rather than spared.
            else -> E2eeStore.Trust(identity.copyOf(), changed = true)
        }.also {
            record.trust = it
            securityChanges += peerId
            durablePeers += peerId
        }
        if (trust.identity.contentEquals(identity)) return
        trust.identity = identity.copyOf()
        trust.changed = true
        if (trust.verified) {
            // The tick was for the other key. It does not pass on, and what it protected is not sent on.
            trust.verified = false
            trust.held = true
        }
        securityChanges += peerId
        durablePeers += peerId
    }

    /** What [record] knows of the peer's key; for a record that says nothing, what its newest session knows. */
    private fun trustOf(record: E2eeStore.PeerRecord): E2eeStore.Trust? =
        record.trust ?: record.sessions.firstOrNull()?.let { E2eeStore.Trust(it.session.remoteIdentityKey()) }

    override fun security(peerId: String): PeerSecurity? = try {
        batch {
            if (closed) return@batch null
            val record = recordFor(peerId) ?: return@batch null
            val trust = trustOf(record)
            if (trust != null) {
                PeerSecurity(encodeBase64(trust.identity), trust.verified, trust.changed, trust.held, trust.since)
            } else {
                // Encrypted before, and nothing of it can be read: whose keys those were is not known any more.
                PeerSecurity(key = null, verified = false, changed = true, held = false, since = 0L)
            }
        }
    } catch (_: Exception) {
        null
    }

    override fun securityCode(peerId: String, key: String): String? = try {
        val keys = batch {
            if (closed) return@batch null
            val trust = recordFor(peerId)?.let(::trustOf) ?: return@batch null
            if (encodeBase64(trust.identity) != key) return@batch null
            val own = identity()?.identityPublic ?: return@batch null
            own to trust.identity.copyOf()
        }
        // Outside the lock: ten thousand hashes must not hold up a message.
        keys?.let { SecurityCode.of(localId, it.first, peerId, it.second) }
    } catch (_: Exception) {
        null
    }

    override fun acceptChange(peerId: String, key: String): Boolean = settle(peerId, key) { trust ->
        val pending = trust.changed || trust.held
        trust.changed = false
        trust.held = false
        pending
    }

    override fun setVerified(peerId: String, key: String, verified: Boolean): Boolean = settle(peerId, key) { trust ->
        if (verified) {
            // Having compared the code is the most the user can say about a change.
            val news = !trust.verified || trust.changed || trust.held
            trust.verified = true
            trust.changed = false
            trust.held = false
            news
        } else {
            val news = trust.verified
            trust.verified = false
            news
        }
    }

    /**
     * Applies what the user said about the key they were shown ([key]);
     * [change] answers whether that was news. False when [key] is no longer
     * the peer's key, or what was said could not be stored — then it counts
     * as not said.
     */
    private fun settle(peerId: String, key: String, change: (E2eeStore.Trust) -> Boolean): Boolean = try {
        batch {
            if (closed) return@batch false
            val record = recordFor(peerId) ?: return@batch false
            val trust = trustOf(record) ?: return@batch false
            if (encodeBase64(trust.identity) != key) return@batch false
            if (change(trust)) {
                record.trust = trust
                try {
                    store.savePeer(peerId, record, durable = true)
                } catch (failure: Exception) {
                    records.remove(peerId)
                    throw failure
                }
                securityChanges += peerId
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * The server refused a sealed send because the peer registered different
     * keys (reinstall, new phone). Existing sessions keep opening late
     * messages but are never sealed with again; the next [seal] starts a
     * fresh session against the peer's current bundle. Envelopes waiting for
     * a retry were sealed for the old keys and are dropped.
     */
    fun retireSessions(peerId: String) {
        batch {
            if (closed) return@batch
            val record = recordFor(peerId) ?: return@batch
            record.sessions.forEach { it.retired = true }
            record.unsent.clear()
            store.savePeer(peerId, record, durable = true)
            unreadable.remove(peerId)
        }
    }

    /**
     * Ends this client's life: from here on it reads nothing and writes
     * nothing. Sign-out calls it before erasing the account's keys and
     * history, so work that was still in flight cannot write them back.
     */
    fun close() {
        synchronized(lock) {
            closed = true
            records.clear()
            changedPeers.clear()
            spentOneTimeKeys.clear()
            durablePeers.clear()
            securityChanges.clear()
            unreadable.clear()
        }
    }

    private fun recordFor(peerId: String): E2eeStore.PeerRecord? {
        if (!records.containsKey(peerId)) records[peerId] = store.loadPeer(peerId)
        return records[peerId]
    }

    private fun archiveKey(direction: String, reference: String, envelope: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(envelope.toByteArray(Charsets.UTF_8))
        return direction + reference + "#" + encodeBase64(digest.copyOf(ENVELOPE_DIGEST_BYTES))
    }

    private companion object {
        const val SENT = "c:"
        const val RECEIVED = "m:"

        /** What an envelope is sealed for: a new message (by client id) or the edit of a stored one (by server id). */
        const val ORIGINAL = "m:"
        const val EDIT = "e:"
        const val ENVELOPE_DIGEST_BYTES = 16
        const val MAX_REMEMBERED_FAILURES = 1024
    }
}
