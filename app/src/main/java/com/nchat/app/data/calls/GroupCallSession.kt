package com.nchat.app.data.calls

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A group voice call: one peer connection per participant (a mesh), one microphone.
 *
 * This is deliberately separate from [WebRtcEngine], which owns a single connection and a single
 * camera for a one-to-one call. Refactoring that engine into an N-peer one would put the working
 * 1:1 path at risk for no user-visible gain; a session object that reuses the same
 * [WebRtcFactory] costs a little duplication and touches nothing that already works.
 *
 * The shape of a call:
 *
 *  * **one** `AudioSource`/`AudioTrack` per session — libwebrtc happily sends the same track to
 *    several peer connections, and a second capture would fight for the microphone anyway;
 *  * **one** `PeerConnection` per participant, each with its own ICE/DTLS;
 *  * **one initiator per pair**, decided by the "who joined later offers" rule, plus
 *    *perfect-negotiation* politeness (the lexicographically larger id rolls back) for the case
 *    where both sides offer at the same moment — a glare that would otherwise leave a participant
 *    connected to nobody;
 *  * signalling through the caller: every local ICE candidate and SDP goes out as a JSON payload
 *    addressed to one participant (`POST /group-calls/:id/signal`), and everything inbound is
 *    matched by sender.
 *
 * Video is intentionally out of scope for this session: one camera cannot feed four encoders on
 * most phones without a lower layer doing the work, so group *video* becomes its own step instead
 * of shipping something that stutters.
 *
 * All WebRTC calls run on one executor, like the 1:1 engine: the bindings are not thread-safe and
 * expect a single driving thread.
 */
class GroupCallSession(
    private val appContext: Context,
    val callId: String,
    private val myId: String,
    private val iceServers: List<PeerConnection.IceServer>,
    private val listener: Listener
) {

    interface Listener {
        /** A payload that has to reach [peerId] (`POST /group-calls/:id/signal`). */
        fun onSignal(peerId: String, payload: JsonObject)

        /** That participant's audio is flowing. */
        fun onPeerConnected(peerId: String)

        /** ICE failed for that participant; the rest of the call carries on. */
        fun onPeerFailed(peerId: String)

        fun onError(message: String)
    }

    private class Peer(val id: String) {
        var connection: PeerConnection? = null
        val pendingCandidates = mutableListOf<IceCandidate>()
        var remoteDescriptionSet = false
        var makingOffer = false
        var closed = false
    }

    private val mainThread = android.os.Handler(android.os.Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nchat-group-webrtc").apply { isDaemon = true }
    }

    private val peers = mutableMapOf<String, Peer>()
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var closed = false
    private var muted = false

    /**
     * The single microphone, shared by every peer connection.
     *
     * Built once per call: a second `AudioSource` would fight the first for the microphone, and
     * libwebrtc sends one track to as many connections as you add it to.
     */
    private fun ensureAudio(): AudioTrack? {
        audioTrack?.let { track ->
            track.setEnabled(!muted)
            return track
        }
        val factory = WebRtcFactory.factory ?: return null
        val source = runCatching { factory.createAudioSource(MediaConstraints()) }.getOrNull() ?: return null
        val track = runCatching { factory.createAudioTrack("nchat-group-audio", source) }.getOrNull()
            ?: return null
        audioSource = source
        audioTrack = track
        track.setEnabled(!muted)
        return track
    }

    /**
     * Connects to [peerId].
     *
     * [offer] true when we are the initiator for this pair — the caller decides that from the
     * participant list (the side that joined later offers), so both sides never race by design.
     */
    fun addPeer(peerId: String, offer: Boolean) {
        if (closed || peerId.isBlank() || peerId == myId) return
        worker.execute {
            val peer = peers.getOrPut(peerId) { Peer(peerId) }
            if (peer.connection != null) return@execute
            val connection = buildConnection(peer) ?: return@execute
            peer.connection = connection
            val track = ensureAudio()
            if (track == null) {
                listener.onError("Could not open the microphone")
                return@execute
            }
            runCatching { connection.addTrack(track, listOf(STREAM_ID)) }
            if (offer) sendOffer(peer)
        }
    }

    /** Routes one inbound signal. Unknown or malformed payloads are ignored, never fatal. */
    fun handleSignal(from: String, payload: JsonObject) {
        if (closed || from.isBlank() || from == myId) return
        worker.execute {
            val peer = peers.getOrPut(from) { Peer(from) }
            val connection = peer.connection ?: run {
                val built = buildConnection(peer) ?: return@execute
                peer.connection = built
                ensureAudio()?.let { track -> runCatching { built.addTrack(track, listOf(STREAM_ID)) } }
                built
            }
            when {
                payload.offerSdp() != null -> applyOffer(peer, connection, payload.offerSdp()!!)
                payload.answerSdp() != null -> applyAnswer(peer, connection, payload.answerSdp()!!)
                payload.candidate() != null -> addCandidate(peer, connection, payload.candidate()!!)
                else -> Log.w(TAG, "unrecognised group-call signal from $from")
            }
        }
    }

    fun removePeer(peerId: String) {
        worker.execute {
            val peer = peers.remove(peerId) ?: return@execute
            peer.closed = true
            runCatching { peer.connection?.close() }
            runCatching { peer.connection?.dispose() }
            peer.connection = null
        }
    }

    fun setMicMuted(mt: Boolean) {
        muted = mt
        worker.execute { runCatching { audioTrack?.setEnabled(!mt) } }
    }

    fun close() {
        if (closed) return
        closed = true
        worker.execute {
            peers.values.forEach { peer ->
                peer.closed = true
                runCatching { peer.connection?.close() }
                runCatching { peer.connection?.dispose() }
                peer.connection = null
            }
            peers.clear()
            runCatching { audioTrack?.dispose() }
            runCatching { audioSource?.dispose() }
            audioTrack = null
            audioSource = null
            worker.shutdown()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun buildConnection(peer: Peer): PeerConnection? {
        val factory = WebRtcFactory.factory ?: run {
            listener.onError("Media engine unavailable")
            return null
        }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val connection = factory.createPeerConnection(config, observerFor(peer)) ?: run {
            listener.onError("Could not open the media channel")
            return null
        }
        return connection
    }

    private fun observerFor(peer: Peer) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            val value = candidate ?: return
            val payload = buildJsonObject {
                put(
                    "candidate",
                    buildJsonObject {
                        put("candidate", JsonPrimitive(value.sdp))
                        put("sdpMid", JsonPrimitive(value.sdpMid))
                        put("sdpMLineIndex", JsonPrimitive(value.sdpMLineIndex))
                    }
                )
            }
            post { listener.onSignal(peer.id, payload) }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> post { listener.onPeerConnected(peer.id) }
                PeerConnection.PeerConnectionState.FAILED -> post { listener.onPeerFailed(peer.id) }
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
    }

    private fun sendOffer(peer: Peer) {
        val connection = peer.connection ?: return
        val offer = connection.createOfferSuspending() ?: return
        if (peer.makingOffer) return
        peer.makingOffer = true
        connection.setLocalDescriptionSuspending(offer)
        val payload = buildJsonObject {
            put(
                "sdp",
                buildJsonObject {
                    put("type", JsonPrimitive("offer"))
                    put("sdp", JsonPrimitive(offer.description))
                }
            )
        }
        post { listener.onSignal(peer.id, payload) }
    }

    private fun applyOffer(peer: Peer, connection: PeerConnection, offerJson: JsonObject) {
        val offer = offerJson.toSessionDescription(SessionDescription.Type.OFFER) ?: return
        /*
         * Glare. Both sides may have offered in the same moment (a participant list that changed
         * while a join was in flight). The polite side — the lexicographically larger id — rolls
         * its own offer back and takes the remote one; the impolite side keeps its offer and will
         * receive this peer's answer instead of an offer.
         */
        if (connection.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            val polite = myId > peer.id
            if (!polite) return
            connection.setLocalDescriptionSuspending(SessionDescription(SessionDescription.Type.ROLLBACK, ""))
        }
        peer.makingOffer = false
        if (!connection.setRemoteDescriptionSuspending(offer)) return
        peer.remoteDescriptionSet = true
        flushCandidates(peer, connection)
        val answer = connection.createAnswerSuspending() ?: return
        connection.setLocalDescriptionSuspending(answer)
        val payload = buildJsonObject {
            put(
                "sdp",
                buildJsonObject {
                    put("type", JsonPrimitive("answer"))
                    put("sdp", JsonPrimitive(answer.description))
                }
            )
        }
        post { listener.onSignal(peer.id, payload) }
    }

    private fun applyAnswer(peer: Peer, connection: PeerConnection, answerJson: JsonObject) {
        val answer = answerJson.toSessionDescription(SessionDescription.Type.ANSWER) ?: return
        if (connection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        peer.makingOffer = false
        if (!connection.setRemoteDescriptionSuspending(answer)) return
        peer.remoteDescriptionSet = true
        flushCandidates(peer, connection)
    }

    private fun addCandidate(peer: Peer, connection: PeerConnection, candidateJson: JsonObject) {
        val candidate = candidateJson.toIceCandidate() ?: return
        if (!peer.remoteDescriptionSet) {
            synchronized(peer.pendingCandidates) { peer.pendingCandidates.add(candidate) }
            return
        }
        runCatching { connection.addIceCandidate(candidate) }
    }

    private fun flushCandidates(peer: Peer, connection: PeerConnection) {
        val queued = synchronized(peer.pendingCandidates) {
            val copy = peer.pendingCandidates.toList()
            peer.pendingCandidates.clear()
            copy
        }
        queued.forEach { candidate -> runCatching { connection.addIceCandidate(candidate) } }
    }

    private fun post(block: () -> Unit) = mainThread.post(block)

    // ------------------------------------------------------------------ sdp helpers

    private fun PeerConnection.createOfferSuspending(): SessionDescription? = waitForSdp { observer ->
        createOffer(observer, MediaConstraints())
    }

    private fun PeerConnection.createAnswerSuspending(): SessionDescription? = waitForSdp { observer ->
        createAnswer(observer, MediaConstraints())
    }

    private fun PeerConnection.setLocalDescriptionSuspending(sdp: SessionDescription): Boolean =
        waitForSet { observer -> setLocalDescription(observer, sdp) }

    private fun PeerConnection.setRemoteDescriptionSuspending(sdp: SessionDescription): Boolean =
        waitForSet { observer -> setRemoteDescription(observer, sdp) }

    private fun waitForSdp(start: (SdpObserver) -> Unit): SessionDescription? {
        var result: SessionDescription? = null
        val latch = CountDownLatch(1)
        start(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                result = sdp
                latch.countDown()
            }

            override fun onSetSuccess() = latch.countDown()
            override fun onCreateFailure(error: String?) = latch.countDown()
            override fun onSetFailure(error: String?) = latch.countDown()
        })
        latch.await(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result
    }

    private fun waitForSet(start: (SdpObserver) -> Unit): Boolean {
        var ok = false
        val latch = CountDownLatch(1)
        start(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) = Unit
            override fun onSetSuccess() {
                ok = true
                latch.countDown()
            }

            override fun onCreateFailure(error: String?) = latch.countDown()
            override fun onSetFailure(error: String?) = latch.countDown()
        })
        latch.await(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return ok
    }

    companion object {
        private const val TAG = "NChatGroupCall"
        private const val STREAM_ID = "nchat-group"
        private const val SDP_TIMEOUT_MS = 4_000L

        /**
         * The SDP in an inbound payload.
         *
         * Three shapes are accepted because the group-call signal endpoint is generic and the
         * worker's exact envelope is not part of the documented contract: `{"sdp":{…}}` (what
         * this client sends), `{"offer_sdp":…}` / `{"answer_sdp":…}` (the 1:1 routes' naming) and
         * a flat `{"type":"offer","sdp":"…"}`.
         */
        private fun JsonObject.offerSdp(): JsonObject? {
            this["offer_sdp"]?.let { return sdpHolder(it, SessionDescription.Type.OFFER) }
            val nested = this["sdp"] as? JsonObject ?: return null
            val type = nested["type"]?.jsonPrimitive?.content ?: "offer"
            return if (type == "offer") nested else null
        }

        private fun JsonObject.answerSdp(): JsonObject? {
            this["answer_sdp"]?.let { return sdpHolder(it, SessionDescription.Type.ANSWER) }
            val nested = this["sdp"] as? JsonObject ?: return null
            val type = nested["type"]?.jsonPrimitive?.content ?: ""
            return if (type == "answer") nested else null
        }

        private fun sdpHolder(element: kotlinx.serialization.json.JsonElement, type: SessionDescription.Type): JsonObject? {
            val sdp = when (element) {
                is JsonObject -> element["sdp"]?.jsonPrimitive?.content
                else -> element.jsonPrimitive.content
            } ?: return null
            return buildJsonObject {
                put("type", JsonPrimitive(if (type == SessionDescription.Type.ANSWER) "answer" else "offer"))
                put("sdp", JsonPrimitive(sdp))
            }
        }

        private fun JsonObject.toSessionDescription(type: SessionDescription.Type): SessionDescription? {
            val sdp = this["sdp"]?.jsonPrimitive?.content ?: return null
            return SessionDescription(type, sdp)
        }

        private fun JsonObject.candidate(): JsonObject? = this["candidate"] as? JsonObject

        private fun JsonObject.toIceCandidate(): IceCandidate? {
            val sdp = this["candidate"]?.jsonPrimitive?.content ?: return null
            val mid = this["sdpMid"]?.jsonPrimitive?.contentOrNullSafe()
            val index = this["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            return IceCandidate(mid, index, sdp)
        }

        private fun JsonPrimitive.contentOrNullSafe(): String? = content.takeIf { it.isNotBlank() && it != "null" }
    }
}
