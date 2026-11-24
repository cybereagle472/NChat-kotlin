package com.nchat.app.network

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * Seals [payload] for the group and hands it to [send]. Null — without
     * [send] being called — means the message may go out unsealed: nothing
     * was ever sealed in this group on this phone AND this phone, or some
     * member, has no keys. Every failure is thrown, and then the message
     * must not be sent in any other form. [group] is the server as far as
     * this group is concerned: who is in it, and where chains are handed
     * over.
     */
    suspend fun <T : Any> sendGroupSealed(
        groupId: String,
        clientId: String,
        payload: E2eePayload,
        group: GroupTransport,
        send: suspend (GroupSealed) -> T,
    ): T?

    /**
     * [willSeal] for a group: asked before a file for the group is uploaded.
     * True makes and hands over this phone's chain if that is still to do.
     */
    suspend fun willSealGroup(groupId: String, group: GroupTransport): Boolean

    /**
     * The content of a sealed group message written by [senderId], or null
     * when it cannot be read on this phone. Never throws, never touches the
     * network: a chain this phone lacks is fetched beforehand — see
     * [missingGroupKeys].
     */
    fun revealGroup(groupId: String, senderId: String, messageId: String, clientId: String?, mine: Boolean, envelope: String): E2eePayload?

    /**
     * The chains that [rows] were sealed with and this phone does not hold:
     * what to ask the server for before the rows are opened. Each chain is
     * named once. A chain the server said it has none of ([noGroupKey]) is
     * named again only for a message further on than any that was known
     * then: a chain is handed on to somebody who joins, as it then stands.
     */
    fun missingGroupKeys(groupId: String, rows: List<GroupRow>): List<GroupKeyRef>

    /**
     * Takes in the sealed envelope with which [senderId] handed this phone
     * their chain [chainId]. True when the chain is held afterwards. Never
     * throws.
     */
    fun acceptGroupKey(groupId: String, senderId: String, chainId: String, envelope: String): Boolean

    /** The server has no envelope that hands this phone that chain (it was not a member then, say). */
    fun noGroupKey(groupId: String, senderId: String, chainId: String)

    /**
     * For a chain whose envelope reached this phone and did not open
     * ([acceptGroupKey] answered false): tells the server so through [ask],
     * so that its sender hands the chain over once more. Once per chain
     * while the app runs — a second envelope that does not open either is
     * not chased. False: there was nothing to ask. Throws what [ask]
     * throws; the request then counts as not made.
     */
    suspend fun askGroupKeyAgain(groupId: String, senderId: String, chainId: String, ask: suspend () -> Unit): Boolean

    /** What this phone knows about the encryption of a group; null when nothing was ever sealed in it here. */
    fun groupSecurity(groupId: String): GroupSecurity?

    /**
     * What this phone kept of a message with [peerId] when it opened it
     * sealed — asked without the envelope, for a row the server now serves
     * in another form. The newest form when the message was edited; null
     * when it was never opened sealed here. Never throws.
     */
    fun kept(peerId: String, messageId: String, clientId: String?, mine: Boolean): E2eePayload?

    /** [kept] for a group message; only under the member who wrote it. */
    fun keptInGroup(groupId: String, senderId: String, messageId: String, clientId: String?, mine: Boolean): E2eePayload?

    /**
     * The message was deleted: what this phone kept of it — as first
     * opened, and every edit — is taken out of its archive. Never throws.
     */
    fun forget(peerId: String, messageId: String, clientId: String?)

    /** [forget] for a message [senderId] wrote to a group. */
    fun forgetInGroup(groupId: String, senderId: String, messageId: String, clientId: String?)
}

/** The server, as far as one group's chains are concerned. Every call may throw what the network throws. */
interface GroupTransport {
    /**
     * The other members and their keys; with [chainId], also who holds that
     * chain of this phone's and whether somebody holds it who should not.
     */
    suspend fun roster(chainId: String?): GroupRoster

    /** Stores the envelopes that hand the chain [chainId] to members. */
    suspend fun handOver(chainId: String, keys: List<GroupKey>)

    /** Whether [error] is the server saying that the members, or their keys, are not what this phone assumed. */
    fun rosterChanged(error: Throwable): Boolean
}

/**
 * [stale]: somebody holds the chain that was asked about who has left the
 * group or has registered other keys since — it must not be used again.
 */
class GroupRoster(val members: List<RosterMember>, val stale: Boolean)

/**
 * [identityKey] is null for a member whose app has no keys; [hasKey]: they
 * hold the chain that was asked about. [again]: they were handed it and
 * said the envelope did not open on their phone — they are handed it once
 * more, on a session started anew.
 */
class RosterMember(val userId: String, val identityKey: String?, val hasKey: Boolean, val again: Boolean = false)

/** The envelope that hands a chain to one member, and the identity key it was sealed for. */
class GroupKey(val recipientId: String, val recipientIdentity: String, val envelope: String)

/** A sealed group message ready to send, and the id of the chain it was sealed with. */
class GroupSealed(val envelope: String, val chainId: String)

/** A stored group message as far as its chain is concerned: who wrote it, and its envelope. */
class GroupRow(val senderId: String, val envelope: String)

/** A chain this phone was not handed yet. */
class GroupKeyRef(val senderId: String, val chainId: String) {
    override fun equals(other: Any?): Boolean = other is GroupKeyRef && other.senderId == senderId && other.chainId == chainId
    override fun hashCode(): Int = 31 * senderId.hashCode() + chainId.hashCode()
    override fun toString(): String = "GroupKeyRef($senderId, $chainId)"
}

/** [since]: when this phone first sealed or opened a sealed message in the group, by its own clock. */
data class GroupSecurity(val since: Long)

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
    /** After how many messages a group chain is replaced; smaller only in tests. */
    private val maxChainMessages: Int = E2eeGroup.MAX_CHAIN_MESSAGES,
) : SealedMessaging {

    /**
     * A group message was not sent because [memberId] — a contact the user
     * had verified — now has other keys, and nothing is sealed for those
     * until the user has looked (see [PeerSecurity.held]).
     */
    class MemberHeld(val memberId: String) : IllegalStateException(E2eeText.MEMBER_CODE_CHANGED)

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

    // Groups, within the unit of work.
    private val groups = HashMap<String, E2eeStore.GroupRecord?>()
    private val changedGroups = LinkedHashSet<String>()
    private val heldChains = HashMap<Pair<String, String>, MutableList<E2eeGroup.Chain>>()
    private val changedChains = LinkedHashSet<Pair<String, String>>()

    /** Group records and chains that must be on stable storage when the unit of work ends. */
    private val durableGroups = LinkedHashSet<String>()
    private val durableChains = LinkedHashSet<Pair<String, String>>()

    /**
     * What is known, while the app runs, about a chain this phone does not
     * hold. [seen]: the furthest message of it that was met. [absentUpTo]:
     * the server had no envelope for this phone when messages up to that
     * number were known. [failed]: an envelope came and did not open (its
     * digest). [asked]: the server was told so. [hopeless]: the envelope
     * that came after that did not open either.
     */
    private class Lacking {
        var seen = -1
        var absentUpTo = -1
        var failed: String? = null
        var asked = false
        var hopeless = false
    }

    /** By [chainName]; the oldest fall out first. */
    private val lackingChains = LinkedHashMap<String, Lacking>()

    /** One send per group at a time: making a chain and handing it over is not something to do twice at once. */
    private val groupGates = HashMap<String, Mutex>()

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
    private suspend fun startSession(peerId: String, expected: ByteArray? = null): Boolean {
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
            // Asked for under particular keys (a group's roster named them): a bundle for
            // other keys is not what was asked for, and nothing is built on it.
            if (expected != null && bundle != null && !bundle.identityPublic.contentEquals(expected)) {
                throw IOException("the server's answers about this member's keys do not agree")
            }
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

    private fun open(peerId: String, key: String, envelopeJson: String, bindings: List<ByteArray>): E2eePayload? =
        openPairwise(peerId, envelopeJson, bindings) { payload ->
            try {
                archive.put(peerId, key, payload)
                true
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Opens a one-to-one envelope from [peerId] — a message, or the envelope
     * that hands over a group chain — as one of [bindings]. [keep] puts what
     * it contained where it belongs and answers whether that worked; only
     * then does the advanced ratchet state become something to store.
     */
    private fun openPairwise(
        peerId: String,
        envelopeJson: String,
        bindings: List<ByteArray>,
        keep: (E2eePayload) -> Boolean,
    ): E2eePayload? {
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
            adopt(peerId, record, keep(payload))
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
        if (adopt(peerId, record, keep(payload)) && oneTimeReference != null) spentOneTimeKeys += oneTimeReference
        return payload
    }

    /**
     * What was opened is kept first; only then does the advanced ratchet
     * state become something to store. [kept] false: keeping it failed, and
     * the in-memory state for this peer is discarded, so the key this
     * message consumed is never recorded as spent. The caller still shows
     * the content — the message simply opens again next time.
     */
    private fun adopt(peerId: String, record: E2eeStore.PeerRecord, kept: Boolean): Boolean {
        if (!kept) {
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
            if (changedPeers.isNotEmpty() || spentOneTimeKeys.isNotEmpty() || changedGroups.isNotEmpty() || changedChains.isNotEmpty()) {
                archive.flush()
                // Chains before sessions: a chain arrives inside a one-to-one message, and the
                // session that opened it must not be stored as having done so before the chain is.
                for (name in changedChains) {
                    heldChains[name]?.let { store.saveChains(name.first, name.second, it, durable = name in durableChains) }
                }
                for (groupId in changedGroups) {
                    groups[groupId]?.let { store.saveGroup(groupId, it, durable = groupId in durableGroups) }
                }
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
            groups.clear()
            changedGroups.clear()
            heldChains.clear()
            changedChains.clear()
            durableGroups.clear()
            durableChains.clear()
        }
    }

    // ----------------------------------------------------------------- groups

    /**
     * **Groups.** A message to a group is sealed once, with this phone's
     * chain for that group ([E2eeGroup]), and the chain is handed to every
     * other member who has keys inside a sealed one-to-one envelope — the
     * same sessions, the same rules about whose keys they are.
     *
     * The server keeps messages and those envelopes in step: it refuses a
     * sealed message whose chain a member lacks, or that somebody still
     * holds who has left the group or has other keys now. On that refusal
     * the roster is read, and then either the chain is handed on (somebody
     * joined) or a new one is made and handed to everybody who is left
     * (somebody is to stop reading). In the ordinary case — nothing changed
     * — a send reads no roster and hands nothing over.
     *
     * **No downgrade.** A group counts as encrypted on this phone from the
     * first message it seals or opens there. Before that, a message goes
     * out unsealed when this phone or any member has no keys: sealing would
     * shut that member out of their own group. After that, nothing goes out
     * unsealed; a member without keys is not handed the chain and cannot
     * read until they have keys.
     */
    override suspend fun <T : Any> sendGroupSealed(
        groupId: String,
        clientId: String,
        payload: E2eePayload,
        group: GroupTransport,
        send: suspend (GroupSealed) -> T,
    ): T? {
        require(groupId.isNotBlank() && clientId.isNotBlank()) { "group and message ids are required" }
        val gate = synchronized(lock) { groupGates.getOrPut(groupId) { Mutex() } }
        return gate.withLock { sendToGroup(groupId, clientId, payload, group, send) }
    }

    override suspend fun willSealGroup(groupId: String, group: GroupTransport): Boolean {
        require(groupId.isNotBlank()) { "a group id is required" }
        val gate = synchronized(lock) { groupGates.getOrPut(groupId) { Mutex() } }
        return gate.withLock { readyChain(groupId, group, lookAgain = false) }
    }

    private suspend fun <T : Any> sendToGroup(
        groupId: String,
        clientId: String,
        payload: E2eePayload,
        group: GroupTransport,
        send: suspend (GroupSealed) -> T,
    ): T? {
        val target = ORIGINAL + clientId
        var lookAgain = false
        while (true) {
            if (!readyChain(groupId, group, lookAgain)) return null
            // After a refusal the message is sealed anew: whoever was just handed the chain
            // was handed it as it stands now, which is after the envelope that was refused.
            val sealed = batch { sealForGroup(groupId, clientId, payload, target, anew = lookAgain) }
            try {
                val result = send(sealed)
                groupDelivered(groupId, target)
                return result
            } catch (failure: Throwable) {
                // Once: a roster that has changed again by the second attempt is the caller's to retry.
                if (lookAgain || !group.rosterChanged(failure)) throw failure
                lookAgain = true
            }
        }
    }

    /**
     * Makes sure this phone has a chain for the group that everybody who is
     * to read holds, and nobody else. False: the message may go out
     * unsealed. [lookAgain]: the server refused a message, so the roster is
     * read whatever this phone believes.
     */
    private suspend fun readyChain(groupId: String, group: GroupTransport, lookAgain: Boolean): Boolean {
        var encryptedHere = false
        val own = batch {
            check(!closed) { E2eeText.SIGNED_OUT }
            val record = groupFor(groupId)
            encryptedHere = record != null
            record?.own
        }
        val worn = own != null && (own.next >= maxChainMessages || clock() - own.created > MAX_CHAIN_AGE_MS)
        if (own != null && !worn && !lookAgain) return true

        // A chain is made or handed on, which takes this phone's own keys.
        val keys = identity() ?: run {
            prepare()
            identity()
        }
        if (keys == null) {
            if (!encryptedHere) return false
            error(E2eeText.NOT_READY)
        }
        val roster = group.roster(own?.idText)
        // Nothing was ever sealed in this group here, and somebody's app has no keys: sealing
        // would shut them out of their own group. It stays as it was until everyone has keys.
        if (!encryptedHere && roster.members.any { it.identityKey == null }) return false

        val readers = roster.members.filter { it.identityKey != null }
        val replace = own == null || worn || roster.stale
        val chain = if (replace) E2eeGroup.newChain(clock()) else own!!
        val lacking = if (replace) readers else readers.filter { !it.hasKey }
        if (lacking.isNotEmpty()) {
            val handed = E2eeGroup.distribution(chain)
            // Every envelope first, then the upload: a member whose keys the user has not
            // looked at stops all of it before anybody was handed anything.
            val envelopes = lacking.map { member -> sealChainFor(groupId, chain.idText, handed, member) }
            for (part in envelopes.chunked(KEYS_PER_REQUEST)) group.handOver(chain.idText, part)
        }
        if (replace) {
            batch {
                check(!closed) { E2eeText.SIGNED_OUT }
                val record = groupFor(groupId) ?: E2eeStore.GroupRecord()
                record.own = chain
                if (record.since == 0L) record.since = clock()
                // Sealed with a chain that is not used any more.
                record.unsent.clear()
                groups[groupId] = record
                store.saveGroup(groupId, record, durable = true)
                changedGroups.remove(groupId)
            }
        }
        return true
    }

    /**
     * The envelope that hands [member] the chain: sealed on the one-to-one
     * session with them, under the keys the roster names and no others.
     */
    private suspend fun sealChainFor(groupId: String, chainId: String, handed: ByteArray, member: RosterMember): GroupKey {
        val peerId = member.userId
        val expected = member.identityKey?.let { decodeBase64(it) } ?: throw IOException("the roster names no usable key for this member")
        try {
            val usable = batch {
                // Throws for a member whose changed keys the user has not looked at.
                val sendable = sendableFor(peerId)
                when {
                    // What was sealed for them on the session there is did not open on their
                    // phone. Nothing more is sealed on it: they are handed the chain on a new one.
                    member.again -> {
                        if (sendable) retireSessions(peerId)
                        false
                    }
                    !sendable -> false
                    recordFor(peerId)?.sendable()?.session?.remoteIdentityKey()?.contentEquals(expected) == true -> true
                    else -> {
                        // The roster names other keys than the session was made with: a new phone.
                        retireSessions(peerId)
                        false
                    }
                }
            }
            if (!usable && !startSession(peerId, expected)) throw IOException("this member's keys could not be fetched")
        } catch (refused: IllegalStateException) {
            // The same rule as in a chat with them alone, said about the group.
            if (refused.message == E2eeText.CODE_CHANGED) throw MemberHeld(peerId)
            // The roster says they have keys and the key server says they have none: one of
            // the two answers is out of date. Asking again may settle it; nothing is sent meanwhile.
            if (refused.message == E2eeText.PEER_HAS_NO_KEYS) throw IOException("this member's keys could not be fetched")
            throw refused
        }
        return batch {
            check(!closed) { E2eeText.SIGNED_OUT }
            val record = recordFor(peerId)
            val session = record?.sendable()?.session
            if (record == null || session == null || !session.remoteIdentityKey().contentEquals(expected)) {
                throw IOException("the server's answers about this member's keys do not agree")
            }
            try {
                val content = E2eePayload(E2eePayload.GROUP_KEY, encodeBase64(handed)).encode()
                val envelope = E2eeProtocol.seal(session, content, chainTarget(groupId, chainId)).toJson()
                store.savePeer(peerId, record, durable = true)
                changedPeers.remove(peerId)
                GroupKey(peerId, encodeBase64(session.remoteIdentityKey()), envelope)
            } catch (failure: Throwable) {
                // The in-memory session advanced but was not stored: drop it so the stored state stays the truth.
                records.remove(peerId)
                changedPeers.remove(peerId)
                throw failure
            }
        }
    }

    private fun sealForGroup(groupId: String, clientId: String, payload: E2eePayload, target: String, anew: Boolean): GroupSealed {
        check(!closed) { E2eeText.SIGNED_OUT }
        val record = groupFor(groupId) ?: error("no chain for this group")
        val chain = record.own ?: error("no chain for this group")
        val chat = GROUP + groupId
        val plaintext = payload.encode()
        val content = encodeBase64(MessageDigest.getInstance("SHA-256").digest(plaintext))

        // The same content for the same message, not yet known to have arrived: the same envelope.
        val kept = record.unsent[target]
        if (!anew && kept != null && kept.content == content && kept.chain == chain.idText) {
            val key = groupArchiveKey(SENT, clientId, localId, kept.envelope)
            if ((archive.recent(chat, key) ?: archive.find(chat, key)) == null) {
                archive.put(chat, key, payload)
                archive.flush()
            }
            return GroupSealed(kept.envelope, kept.chain)
        }
        try {
            val envelope = E2eeGroup.seal(chain, groupId, localId, plaintext, target.toByteArray(Charsets.UTF_8))
            archive.put(chat, groupArchiveKey(SENT, clientId, localId, envelope), payload)
            archive.flush()
            record.unsent.remove(target)
            record.unsent[target] = E2eeStore.GroupUnsent(content, envelope, chain.idText)
            if (record.since == 0L) record.since = clock()
            store.saveGroup(groupId, record, durable = true)
            changedGroups.remove(groupId)
            return GroupSealed(envelope, chain.idText)
        } catch (failure: Throwable) {
            // The chain moved on in memory but was not stored: drop it so the stored state stays the truth.
            groups.remove(groupId)
            changedGroups.remove(groupId)
            throw failure
        }
    }

    /** The server has this envelope; it need not be kept for a retry any longer. */
    private fun groupDelivered(groupId: String, target: String) {
        batch {
            if (closed) return@batch
            val record = groupFor(groupId) ?: return@batch
            if (record.unsent.remove(target) != null) changedGroups += groupId
        }
    }

    override fun revealGroup(groupId: String, senderId: String, messageId: String, clientId: String?, mine: Boolean, envelope: String): E2eePayload? {
        val reference = if (mine) clientId else messageId
        if (groupId.isBlank() || senderId.isBlank() || messageId.isBlank() || reference.isNullOrBlank()) return null
        return try {
            batch {
                if (closed) return@batch null
                val chat = GROUP + groupId
                // A message this phone wrote is kept under this account, whatever the row says.
                val author = if (mine) localId else senderId
                if (!mine && senderId == localId) return@batch null
                val key = groupArchiveKey(if (mine) SENT else RECEIVED, reference, author, envelope)
                archive.recent(chat, key)
                    ?: if (unreadable[chat]?.contains(key) == true) null
                    else {
                        // Our own envelope is sealed with our chain, which only moves forward: only the archive has its content.
                        val opened = if (mine) archive.find(chat, key) else openGroup(groupId, senderId, chat, key, messageId, clientId, envelope) ?: archive.find(chat, key)
                        opened ?: giveUp(chat, key)
                    }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openGroup(
        groupId: String,
        senderId: String,
        chat: String,
        key: String,
        messageId: String,
        clientId: String?,
        envelope: String,
    ): E2eePayload? {
        val chainId = E2eeGroup.chainId(envelope) ?: return null
        val name = groupId to senderId
        val chain = chainsFor(groupId, senderId).firstOrNull { it.idText == chainId } ?: return null
        val bindings = listOfNotNull(clientId?.takeIf { it.isNotBlank() }?.let { ORIGINAL + it }, EDIT + messageId)
            .map { it.toByteArray(Charsets.UTF_8) }
        val plaintext = bindings.firstNotNullOfOrNull { E2eeGroup.open(chain, groupId, senderId, envelope, it) } ?: return null
        val payload = E2eePayload.decode(plaintext)
        // Archive first; only then does the chain having moved become something to store.
        try {
            archive.put(chat, key, payload)
        } catch (_: Exception) {
            heldChains.remove(name)
            changedChains.remove(name)
            return payload
        }
        changedChains += name
        unreadable.remove(chat)
        encryptedHere(groupId)
        return payload
    }

    override fun missingGroupKeys(groupId: String, rows: List<GroupRow>): List<GroupKeyRef> = try {
        batch {
            if (closed || groupId.isBlank()) return@batch emptyList()
            val wanted = LinkedHashMap<String, GroupKeyRef>()
            for (row in rows) {
                if (row.senderId.isBlank() || row.senderId == localId) continue
                val place = E2eeGroup.place(row.envelope) ?: continue
                val name = chainName(groupId, row.senderId, place.chainId)
                val known = lackingChains[name]
                // Asked for before, and nothing has come since that could change the answer.
                val settled = known != null && (known.hopeless || place.number <= known.absentUpTo)
                if (known != null && place.number > known.seen) known.seen = place.number
                if (name in wanted || settled) continue
                if (chainsFor(groupId, row.senderId).any { it.idText == place.chainId }) continue
                if (known == null) lackingOf(name).seen = place.number
                wanted[name] = GroupKeyRef(row.senderId, place.chainId)
            }
            wanted.values.toList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    override fun acceptGroupKey(groupId: String, senderId: String, chainId: String, envelope: String): Boolean = try {
        batch {
            if (closed || groupId.isBlank() || senderId.isBlank() || chainId.isBlank()) return@batch false
            val name = groupId to senderId
            val held = chainsFor(groupId, senderId)
            // Held already: the envelope has done its work, and the chain stays where it is.
            if (held.any { it.idText == chainId }) return@batch true
            var taken = false
            val opened = openPairwise(senderId, envelope, listOf(chainTarget(groupId, chainId))) { content ->
                val chain = content.takeIf { it.type == E2eePayload.GROUP_KEY }
                    ?.let { decodeBase64(it.body) }
                    ?.let { E2eeGroup.accept(it) }
                    ?.takeIf { it.idText == chainId }
                if (chain != null) {
                    held.add(0, chain)
                    heldChains[name] = held
                    changedChains += name
                    // On stable storage before the session that opened the envelope is: see commit().
                    durableChains += name
                    unreadable.remove(GROUP + groupId)
                    lackingChains.remove(chainName(groupId, senderId, chainId))
                    encryptedHere(groupId)
                    taken = true
                }
                // Authentic either way; its key is spent whether or not it carried what was asked for.
                true
            }
            // It did not open here, or was not the chain it was said to be: no use to this
            // phone, however often it is fetched. (Something that merely went wrong on the
            // way — storage, say — throws, and is not counted as that.)
            if (!taken) didNotOpen(chainName(groupId, senderId, chainId), envelope, authentic = opened != null)
            taken
        }
    } catch (_: Exception) {
        false
    }

    /** An envelope that was to hand over the chain [name] is no use to this phone. */
    private fun didNotOpen(name: String, envelope: String, authentic: Boolean) {
        val state = lackingOf(name)
        val digest = encodeBase64(MessageDigest.getInstance("SHA-256").digest(envelope.toByteArray(Charsets.UTF_8)))
        // The same envelope fetched once more says nothing new.
        if (state.failed == digest) return
        // A second one, after the sender was asked for it: not chased further while the app
        // runs. So is one that opened and carried something else — asking again would spend
        // the sender a session to no end.
        if (state.asked || authentic) state.hopeless = true
        state.failed = digest
    }

    override suspend fun askGroupKeyAgain(groupId: String, senderId: String, chainId: String, ask: suspend () -> Unit): Boolean {
        val name = chainName(groupId, senderId, chainId)
        val due = synchronized(lock) {
            val state = lackingChains[name]
            // Marked before the request leaves, so that two readers of the same page ask once.
            if (closed || state == null || state.failed == null || state.asked || state.hopeless) false
            else {
                state.asked = true
                true
            }
        }
        if (!due) return false
        try {
            ask()
        } catch (failure: Throwable) {
            synchronized(lock) { lackingChains[name]?.asked = false }
            throw failure
        }
        return true
    }

    private fun lackingOf(name: String): Lacking {
        lackingChains[name]?.let { return it }
        val state = Lacking()
        lackingChains[name] = state
        val eldest = lackingChains.iterator()
        while (lackingChains.size > MAX_REMEMBERED_FAILURES && eldest.hasNext()) {
            eldest.next()
            eldest.remove()
        }
        return state
    }

    override fun noGroupKey(groupId: String, senderId: String, chainId: String) {
        synchronized(lock) {
            if (closed) return
            // Only a chain that was looked for can be found absent.
            val state = lackingChains[chainName(groupId, senderId, chainId)] ?: return
            // Its sender has been asked to hand it over again: "nothing yet" is what the
            // server says until they have, and not a reason to stop looking.
            if (state.asked && !state.hopeless) return
            state.absentUpTo = state.seen
        }
    }

    override fun groupSecurity(groupId: String): GroupSecurity? = try {
        batch {
            if (closed) null else groupFor(groupId)?.let { GroupSecurity(it.since) }
        }
    } catch (_: Exception) {
        null
    }

    /** A sealed message was written or read in this group on this phone: from here on it counts as encrypted. */
    private fun encryptedHere(groupId: String) {
        val record = groupFor(groupId) ?: E2eeStore.GroupRecord().also { groups[groupId] = it }
        if (record.since != 0L) return
        record.since = clock()
        changedGroups += groupId
        durableGroups += groupId
    }

    private fun groupFor(groupId: String): E2eeStore.GroupRecord? {
        if (!groups.containsKey(groupId)) groups[groupId] = store.loadGroup(groupId)
        return groups[groupId]
    }

    private fun chainsFor(groupId: String, senderId: String): MutableList<E2eeGroup.Chain> =
        heldChains.getOrPut(groupId to senderId) { store.loadChains(groupId, senderId) }

    /**
     * Where an opened group message is kept. Unlike a one-to-one chat, a
     * group's archive holds messages of many people, so who wrote the message
     * is part of the name: what was opened as one member's is never found
     * again as another's.
     */
    private fun groupArchiveKey(direction: String, reference: String, senderId: String, envelope: String): String =
        archiveKey(direction, authored(senderId, reference), envelope)

    /** A message of a group as its archive names it: who wrote it, then its id. The length keeps the two apart. */
    private fun authored(senderId: String, reference: String): String = senderId.length.toString() + ":" + senderId + ":" + reference

    // ------------------------------------------------- deleted, and replaced

    override fun kept(peerId: String, messageId: String, clientId: String?, mine: Boolean): E2eePayload? {
        val reference = if (mine) clientId else messageId
        if (peerId.isBlank() || reference.isNullOrBlank()) return null
        return keptAs(peerId, (if (mine) SENT else RECEIVED) + reference + "#")
    }

    override fun keptInGroup(groupId: String, senderId: String, messageId: String, clientId: String?, mine: Boolean): E2eePayload? {
        val reference = if (mine) clientId else messageId
        if (groupId.isBlank() || senderId.isBlank() || reference.isNullOrBlank()) return null
        if (!mine && senderId == localId) return null
        return keptAs(GROUP + groupId, (if (mine) SENT else RECEIVED) + authored(if (mine) localId else senderId, reference) + "#")
    }

    private fun keptAs(chat: String, reference: String): E2eePayload? = try {
        batch { if (closed) null else archive.latest(chat, reference) }
    } catch (_: Exception) {
        null
    }

    override fun forget(peerId: String, messageId: String, clientId: String?) {
        if (peerId.isBlank()) return
        forgetAs(
            peerId,
            listOfNotNull(
                messageId.takeIf { it.isNotBlank() }?.let { RECEIVED + it + "#" },
                clientId?.takeIf { it.isNotBlank() }?.let { SENT + it + "#" },
            ),
        )
    }

    override fun forgetInGroup(groupId: String, senderId: String, messageId: String, clientId: String?) {
        if (groupId.isBlank() || senderId.isBlank()) return
        forgetAs(
            GROUP + groupId,
            listOfNotNull(
                messageId.takeIf { it.isNotBlank() }?.let { RECEIVED + authored(senderId, it) + "#" },
                // This phone's own copy is kept under this account, by the id it gave the message.
                clientId?.takeIf { it.isNotBlank() && senderId == localId }?.let { SENT + authored(localId, it) + "#" },
            ),
        )
    }

    private fun forgetAs(chat: String, references: List<String>) {
        try {
            batch {
                if (closed) return@batch
                if (archive.remove(chat, references) > 0) unreadable.remove(chat)
            }
        } catch (_: Exception) {
            // What could not be removed now is still there to be removed the next time it is asked.
        }
    }

    /** What the envelope that hands over a chain is sealed for: that chain of that group, and nothing else. */
    private fun chainTarget(groupId: String, chainId: String): ByteArray = (CHAIN + groupId + ":" + chainId).toByteArray(Charsets.UTF_8)

    private fun chainName(groupId: String, senderId: String, chainId: String): String = groupId.length.toString() + ":" + groupId + ":" + senderId + ":" + chainId

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
            groups.clear()
            changedGroups.clear()
            heldChains.clear()
            changedChains.clear()
            durableGroups.clear()
            durableChains.clear()
            lackingChains.clear()
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

        /** What the envelope that hands over a group chain is sealed for. */
        const val CHAIN = "k:"

        /** The archive of a group's messages, apart from every one-to-one chat. */
        const val GROUP = "g:"

        /** A chain is not kept for ever: after this long, the next message starts a new one. */
        const val MAX_CHAIN_AGE_MS = 30L * 24 * 60 * 60 * 1000

        /** How many members one request hands a chain to. */
        const val KEYS_PER_REQUEST = 100
    }
}
