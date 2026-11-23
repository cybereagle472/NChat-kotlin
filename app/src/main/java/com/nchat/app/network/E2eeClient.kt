package com.nchat.app.network

import java.security.MessageDigest

/**
 * Seal/open orchestration for direct messages.
 *
 * **Capability.** A peer is E2EE-capable when their key bundle can be
 * fetched; old clients have none and keep receiving plaintext. The bundle is
 * fetched only when no usable session exists (each fetch spends one of the
 * peer's one-time prekeys), so steady-state sends are local ratchet work.
 *
 * **No downgrade.** Once any session with a peer has existed, this client
 * never answers "send it in the clear" for that peer again — a server that
 * suddenly claims the peer has no keys gets an error, not plaintext. A bundle
 * that does not verify throws for the same reason.
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
 * **What this does not do yet.** The first identity key a peer presents is
 * accepted, and so is a later change of it: there is no key verification
 * between users. That defeats a server that only stores or leaks data, not
 * one that actively substitutes keys.
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
) {

    /**
     * A sealed message ready to send. [recipientIdentity] is the identity key
     * (base64 SPKI) the envelope was sealed for; it goes to the server with
     * the message so a send to keys the recipient no longer has is refused.
     */
    class Sealed(val envelope: String, val recipientIdentity: String)

    private val lock = Any()

    // Unit of work — everything below is guarded by [lock].
    private var depth = 0
    private val records = HashMap<String, E2eeStore.PeerRecord?>()
    private val changedPeers = LinkedHashSet<String>()
    private val spentOneTimeKeys = LinkedHashSet<String>()

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
    fun <T> batch(block: () -> T): T = synchronized(lock) {
        depth++
        try {
            block()
        } finally {
            depth--
            if (depth == 0) commit()
        }
    }

    /**
     * Seals [payload] for [peerId], or returns null when the peer has never
     * had key material (the caller then sends plaintext). [clientId] is the
     * message's `client_message_id`: our own sealed messages are found again
     * under it, since only the recipient can open the envelope itself.
     *
     * Throws when sealing is required but not possible; the caller must not
     * send the message in any other form.
     */
    suspend fun seal(peerId: String, clientId: String, payload: E2eePayload): Sealed? {
        require(peerId.isNotBlank() && clientId.isNotBlank()) { "peer and client message id are required" }
        batch { sealOnCurrent(peerId, clientId, payload) }?.let { return it }

        // Checked before the fetch: a bundle we cannot use must not cost the peer a one-time prekey.
        val keys = identity() ?: error("this device has no identity key")
        val bundle = fetchBundle(peerId)
        return batch {
            // Another send, or a handshake from the peer, may have got here first.
            sealOnCurrent(peerId, clientId, payload) ?: run {
                val existing = recordFor(peerId)
                when {
                    bundle != null -> {
                        val session = E2eeProtocol.initiate(keys, bundle, localId, peerId)
                        val record = existing ?: E2eeStore.PeerRecord()
                        record.sessions.add(0, E2eeStore.StoredSession(session, retired = false))
                        records[peerId] = record
                        sealOn(peerId, record, clientId, payload)
                    }
                    existing != null -> error("the peer's key material is unavailable; refusing to send unencrypted")
                    else -> null
                }
            }
        }
    }

    private fun sealOnCurrent(peerId: String, clientId: String, payload: E2eePayload): Sealed? {
        val record = recordFor(peerId) ?: return null
        if (record.sendable() == null) return null
        return sealOn(peerId, record, clientId, payload)
    }

    private fun sealOn(peerId: String, record: E2eeStore.PeerRecord, clientId: String, payload: E2eePayload): Sealed {
        val session = record.sendable()?.session ?: error("no sending session")
        try {
            val envelope = E2eeProtocol.seal(session, payload.encode()).toJson()
            archive.put(peerId, archiveKey(SENT, clientId, envelope), payload)
            archive.flush()
            store.savePeer(peerId, record, durable = true)
            changedPeers.remove(peerId)
            return Sealed(envelope, encodeBase64(session.remoteIdentityKey()))
        } catch (failure: Throwable) {
            // The in-memory session advanced but was not stored: drop it so the stored state stays the truth.
            records.remove(peerId)
            changedPeers.remove(peerId)
            throw failure
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
     * replay.
     */
    fun reveal(peerId: String, messageId: String, clientId: String?, mine: Boolean, envelope: String): E2eePayload? {
        val reference = if (mine) clientId else messageId
        if (peerId.isBlank() || reference.isNullOrBlank()) return null
        return try {
            batch {
                val key = archiveKey(if (mine) SENT else RECEIVED, reference, envelope)
                archive.recent(peerId, key)
                    ?: if (unreadable[peerId]?.contains(key) == true) null
                    else (openOrFind(peerId, key, mine, envelope) ?: giveUp(peerId, key))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openOrFind(peerId: String, key: String, mine: Boolean, envelope: String): E2eePayload? {
        // Our own envelope is sealed for the peer: only the archive has its content.
        if (mine) return archive.find(peerId, key)
        // A new message is the common case, and the ratchet settles it without reading old history.
        return open(peerId, key, envelope) ?: archive.find(peerId, key)
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

    private fun open(peerId: String, key: String, envelopeJson: String): E2eePayload? {
        val envelope = E2eeProtocol.Envelope.fromJson(envelopeJson) ?: return null
        val record = recordFor(peerId) ?: E2eeStore.PeerRecord()

        for (index in record.sessions.indices) {
            val stored = record.sessions[index]
            val plaintext = E2eeProtocol.open(stored.session, envelope) ?: continue
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
        val opened = E2eeProtocol.openFirst(keys, prekey, oneTimePrivate, envelope, localId, peerId) ?: return null

        val peerIdentity = opened.session.remoteIdentityKey()
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
                    // Not synced: if this write is lost, the keys are derived again and the archive answers first.
                    records[peerId]?.let { store.savePeer(peerId, it, durable = false) }
                }
                store.removeOneTimeKeys(spentOneTimeKeys)
            }
        } catch (_: Exception) {
            // Nothing newer was stored than what the archive holds; the same messages open again next time.
        } finally {
            records.clear()
            changedPeers.clear()
            spentOneTimeKeys.clear()
        }
    }

    /**
     * The server refused a sealed send because the peer registered different
     * keys (reinstall, new phone). Existing sessions keep opening late
     * messages but are never sealed with again; the next [seal] starts a
     * fresh session against the peer's current bundle.
     */
    fun retireSessions(peerId: String) {
        batch {
            val record = recordFor(peerId) ?: return@batch
            record.sessions.forEach { it.retired = true }
            store.savePeer(peerId, record, durable = true)
            unreadable.remove(peerId)
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
        const val ENVELOPE_DIGEST_BYTES = 16
        const val MAX_REMEMBERED_FAILURES = 1024
    }
}
