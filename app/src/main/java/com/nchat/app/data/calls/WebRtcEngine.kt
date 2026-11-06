package com.nchat.app.data.calls

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * One live call's media, on top of `org.webrtc`.
 *
 * This is the half of calling that 1.0.40 left out: signalling and call records were real,
 * but nothing carried the user's voice. The offer/answer and ICE plumbing follows the
 * reference client (`src/components/CallModal.tsx`, `src/services/callService.ts`) exactly,
 * because the worker only relays opaque JSON (`worker/src/routes/calls.ts:124`):
 *
 * - the caller builds a peer connection, creates an **offer**, and posts it with the call
 *   itself (`POST /calls { offer_sdp }`, `calls.ts:66`) — the callee receives it inside
 *   `call:incoming`, so there is no separate "store the offer" round trip;
 * - the callee applies that offer, creates an **answer**, and posts it
 *   (`POST /calls/:id/answer`, `calls.ts:137`) — the caller gets `call:answered { answer_sdp }`;
 * - both sides trickle **ICE candidates** through `POST /calls/:id/signal { candidate }`,
 *   which the worker forwards as `call:signal { callId, from, candidate }`.
 *
 * Two details from the reference are easy to get wrong and fatal in practice: candidates can
 * start flowing before this side has a peer connection (the callee is still ringing) or before
 * its remote description is set, so they are **queued** and flushed rather than dropped; and a
 * peer that never reaches ICE-connected is a real failure, reported as [Listener.onFailed]
 * instead of leaving the screen on "Connecting…" forever.
 *
 * Everything WebRTC runs on one executor: libwebrtc's bindings expect the peer connection to
 * be driven from a single thread. Results are posted back to the main thread before they reach
 * the repository.
 */
class WebRtcEngine private constructor(
    private val appContext: Context,
    private val isVideo: Boolean,
    private val listener: Listener
) {

    /** What the call screen has to know while media is running. */
    interface Listener {
        /** A local ICE candidate to relay (`POST /calls/:id/signal`). */
        fun onIceCandidate(candidate: JsonObject)

        /** ICE/DTLS is up: audio (and video) is actually flowing. */
        fun onConnected()

        /** ICE dropped; it may still recover, so the caller applies a grace window. */
        fun onDisconnected()

        /** ICE gave up. The call is over even though nobody pressed anything. */
        fun onFailed()

        fun onLocalVideo(track: VideoTrack?)

        /** The remote video track appeared (video calls only). */
        fun onRemoteVideo(track: VideoTrack?)

        /** A media-layer problem worth telling the user about. */
        fun onError(message: String)
    }

    /**
     * The context the call screen's renderers initialise with.
     *
     * Deliberately the *factory's* long-lived EGL base, not this call's: a renderer creates its
     * own EGL surface sharing this context, and it is released by Compose a frame or two after
     * the call ends — by which point a per-call context would already be gone, which is a
     * native crash rather than an exception.
     */
    val eglContext: EglBase.Context? get() = WebRtcFactory.renderContext

    var localVideoTrack: VideoTrack? = null
        private set

    val isFrontCamera: Boolean get() = frontCamera

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nchat-webrtc").apply { isDaemon = true }
    }

    private val eglBase: EglBase = EglBase.create()
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var capturer: CameraVideoCapturer? = null
    private var frontCamera = true
    private var remoteDescriptionSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var closed = false

    // ------------------------------------------------------------------ setup

    private fun buildPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val factory = WebRtcFactory.factory ?: run {
            listener.onError("Media engine unavailable")
            return
        }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(config, observer)
        if (pc == null) {
            listener.onError("Could not open the media channel")
            return
        }
        peerConnection = pc

        // Audio first, always: a call that connects with no voice is worse than no call.
        runCatching {
            val source = factory.createAudioSource(MediaConstraints())
            val track = factory.createAudioTrack("nchat-audio", source)
            audioSource = source
            audioTrack = track
            pc.addTrack(track, listOf(STREAM_ID))
        }.onFailure {
            Log.w(TAG, "audio setup failed", it)
            listener.onError("Could not open the microphone")
        }

        if (isVideo) startVideo(factory, pc)
    }

    private fun startVideo(factory: PeerConnectionFactory, pc: PeerConnection) {
        runCatching {
            val helper = SurfaceTextureHelper.create("nchat-capture", eglBase.eglBaseContext)
            surfaceHelper = helper
            val enumerator = Camera2Enumerator(appContext)
            val names = enumerator.deviceNames
            if (names.isEmpty()) {
                listener.onError("No camera on this device")
                return
            }
            val picked = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.first()
            frontCamera = enumerator.isFrontFacing(picked)
            val camera = enumerator.createCapturer(picked, null)
            if (camera == null) {
                listener.onError("Could not start the camera")
                return
            }
            val source = factory.createVideoSource(camera.isScreencast)
            camera.initialize(helper, appContext, source.capturerObserver)
            camera.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
            val track = factory.createVideoTrack("nchat-video", source)
            capturer = camera
            videoSource = source
            localVideoTrack = track
            pc.addTrack(track, listOf(STREAM_ID))
            post { listener.onLocalVideo(track) }
        }.onFailure {
            Log.w(TAG, "video setup failed", it)
            listener.onError("Could not start the camera")
        }
    }

    /** Swaps front/back camera mid-call, keeping the same video source and track. */
    fun flipCamera() {
        val helper = surfaceHelper ?: return
        val source = videoSource ?: return
        worker.execute {
            runCatching {
                val enumerator = Camera2Enumerator(appContext)
                val target = enumerator.deviceNames.firstOrNull {
                    enumerator.isFrontFacing(it) != frontCamera
                } ?: return@runCatching
                capturer?.let { old ->
                    runCatching { old.stopCapture() }
                    runCatching { old.dispose() }
                }
                val camera = enumerator.createCapturer(target, null) ?: return@runCatching
                camera.initialize(helper, appContext, source.capturerObserver)
                camera.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
                capturer = camera
                frontCamera = enumerator.isFrontFacing(target)
                // The track already attached to the peer connection keeps its sink; only the
                // capturer feeding the source changes, which is exactly what a flip is.
            }.onFailure { Log.w(TAG, "camera flip failed", it) }
        }
    }

    // -------------------------------------------------------------- signalling

    /** Caller side: create the offer that travels inside `POST /calls`. */
    suspend fun createOffer(iceServers: List<PeerConnection.IceServer>): JsonObject? =
        withWorker {
            buildPeerConnection(iceServers)
            val pc = peerConnection ?: return@withWorker null
            val offer = pc.createOfferSuspending()
            if (offer == null || !pc.setLocalDescriptionSuspending(offer)) {
                listener.onError("Could not start the call")
                return@withWorker null
            }
            sdpJson("offer", offer.description)
        }

    /** Callee side: apply the caller's offer and produce the answer for `/answer`. */
    suspend fun acceptOffer(
        offer: JsonObject,
        iceServers: List<PeerConnection.IceServer>
    ): JsonObject? = withWorker {
        buildPeerConnection(iceServers)
        val pc = peerConnection ?: return@withWorker null
        val remote = offer.toSessionDescription()
        if (remote == null || !pc.setRemoteDescriptionSuspending(remote)) {
            listener.onError("Could not join the call")
            return@withWorker null
        }
        remoteDescriptionSet = true
        flushPendingCandidates(pc)
        val answer = pc.createAnswerSuspending()
        if (answer == null || !pc.setLocalDescriptionSuspending(answer)) {
            listener.onError("Could not join the call")
            return@withWorker null
        }
        sdpJson("answer", answer.description)
    }

    /** Caller side: apply the answer that arrived in `call:answered`. */
    fun applyAnswer(answer: JsonObject) {
        worker.execute {
            runCatching {
                val pc = peerConnection ?: return@runCatching
                val remote = answer.toSessionDescription() ?: return@runCatching
                if (pc.setRemoteDescriptionSuspending(remote)) {
                    remoteDescriptionSet = true
                    flushPendingCandidates(pc)
                } else {
                    Log.w(TAG, "answer could not be applied")
                }
            }.onFailure { Log.w(TAG, "applyAnswer failed", it) }
        }
    }

    /** Adds a relayed candidate, or queues it until this side can accept it. */
    fun addRemoteCandidate(candidate: JsonObject) {
        val parsed = runCatching {
            IceCandidate(
                candidate["sdpMid"]?.jsonPrimitive?.content ?: "",
                candidate["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                candidate["candidate"]?.jsonPrimitive?.content ?: return
            )
        }.getOrNull() ?: return
        worker.execute {
            val pc = peerConnection
            if (pc == null || !remoteDescriptionSet) {
                pendingCandidates.add(parsed)
            } else {
                runCatching { pc.addIceCandidate(parsed) }
            }
        }
    }

    private fun flushPendingCandidates(pc: PeerConnection) {
        if (pendingCandidates.isEmpty()) return
        val queued = pendingCandidates.toList()
        pendingCandidates.clear()
        queued.forEach { runCatching { pc.addIceCandidate(it) } }
    }

    // ----------------------------------------------------------------- controls

    fun setMicMuted(muted: Boolean) {
        worker.execute { runCatching { audioTrack?.setEnabled(!muted) } }
    }

    fun setVideoEnabled(enabled: Boolean) {
        worker.execute { runCatching { localVideoTrack?.setEnabled(enabled) } }
    }

    // -------------------------------------------------------------------- teardown

    fun close() {
        if (closed) return
        closed = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { worker.execute {
            runCatching { capturer?.stopCapture() }
            runCatching { capturer?.dispose() }
            capturer = null
            runCatching { localVideoTrack?.dispose() }
            localVideoTrack = null
            runCatching { videoSource?.dispose() }
            videoSource = null
            runCatching { surfaceHelper?.dispose() }
            surfaceHelper = null
            runCatching { audioTrack?.dispose() }
            audioTrack = null
            runCatching { audioSource?.dispose() }
            audioSource = null
            runCatching { peerConnection?.close() }
            runCatching { peerConnection?.dispose() }
            peerConnection = null
            runCatching { eglBase.release() }
            worker.shutdown()
        } }
    }

    // ----------------------------------------------------------------- observer

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            reportIce(newState)
        }

        override fun onStandardizedIceConnectionChange(
            newState: PeerConnection.IceConnectionState?
        ) {
            reportIce(newState)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> post { listener.onConnected() }
                PeerConnection.PeerConnectionState.DISCONNECTED -> post { listener.onDisconnected() }
                PeerConnection.PeerConnectionState.FAILED -> post { listener.onFailed() }
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

        override fun onIceCandidate(candidate: IceCandidate?) {
            val c = candidate ?: return
            val json = buildJsonObject {
                put("candidate", JsonPrimitive(c.sdp))
                put("sdpMid", JsonPrimitive(c.sdpMid))
                put("sdpMLineIndex", JsonPrimitive(c.sdpMLineIndex))
            }
            post { listener.onIceCandidate(json) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) = Unit

        override fun onAddStream(stream: MediaStream?) {
            (stream?.videoTracks?.firstOrNull() as? VideoTrack)?.let { track ->
                post { listener.onRemoteVideo(track) }
            }
        }

        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(dataChannel: DataChannel?) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            publish(receiver?.track())
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            publish(transceiver?.receiver?.track())
        }
    }

    private fun reportIce(state: PeerConnection.IceConnectionState?) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> post { listener.onConnected() }

            PeerConnection.IceConnectionState.DISCONNECTED -> post { listener.onDisconnected() }
            PeerConnection.IceConnectionState.FAILED -> post { listener.onFailed() }
            else -> Unit
        }
    }

    private fun publish(track: MediaStreamTrack?) {
        val video = track as? VideoTrack ?: return
        post { listener.onRemoteVideo(video) }
    }

    // ------------------------------------------------------------------ helpers

    private fun post(block: () -> Unit) {
        if (closed) return
        mainHandler.post { if (!closed) runCatching(block) }
    }

    /**
     * Runs [block] on the WebRTC thread and suspends the caller until it returns.
     *
     * The result is nullable by construction: a failure anywhere in the media stack becomes
     * "no offer / no answer", which the repository turns into a clear message, instead of an
     * exception escaping into the app-wide coroutine scope.
     */
    private suspend fun <T : Any> withWorker(block: () -> T?): T? =
        suspendCancellableCoroutine { continuation ->
            runCatching {
                worker.execute {
                    val result = runCatching(block)
                        .onFailure { Log.w(TAG, "media task failed", it) }
                        .getOrNull()
                    if (continuation.isActive) continuation.resume(result)
                }
            }.onFailure { Log.w(TAG, "media thread is gone", it) }
        }

    private fun sdpJson(type: String, sdp: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("sdp", JsonPrimitive(sdp))
    }

    private fun JsonObject.toSessionDescription(): SessionDescription? {
        val type = this["type"]?.jsonPrimitive?.content ?: "offer"
        val sdp = this["sdp"]?.jsonPrimitive?.content ?: return null
        return SessionDescription(
            if (type == "answer") SessionDescription.Type.ANSWER else SessionDescription.Type.OFFER,
            sdp
        )
    }

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

    /** Blocks the worker thread until SDP creation finishes — callers are already off-main. */
    private fun waitForSdp(start: (SdpObserver) -> Unit): SessionDescription? {
        var result: SessionDescription? = null
        val latch = CountDownLatch(1)
        start(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                result = sdp
                latch.countDown()
            }

            override fun onSetSuccess() = latch.countDown()

            override fun onCreateFailure(error: String?) {
                Log.w(TAG, "createSdp failed: $error")
                latch.countDown()
            }

            override fun onSetFailure(error: String?) {
                Log.w(TAG, "setDescription failed: $error")
                latch.countDown()
            }
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

            override fun onSetFailure(error: String?) {
                Log.w(TAG, "setDescription failed: $error")
                latch.countDown()
            }
        })
        latch.await(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return ok
    }

    companion object {
        private const val TAG = "NChatWebRtc"
        private const val STREAM_ID = "nchat-stream"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
        private const val SDP_TIMEOUT_MS = 8_000L

        /**
         * Builds the ICE list the reference uses: public STUN for the common case, then the
         * worker's short-lived Cloudflare TURN credentials for calls that cannot go direct
         * (`GET /calls/turn-credentials`, `calls.ts:38`).
         */
        fun iceServers(turn: JsonElement?): List<PeerConnection.IceServer> {
            val servers = mutableListOf<PeerConnection.IceServer>()
            STUN_URLS.forEach { url ->
                runCatching {
                    servers.add(PeerConnection.IceServer.builder(url).createIceServer())
                }
            }
            // The worker answers `{ iceServers: [...] }`; be tolerant of either shape.
            val list = when (val raw = turn) {
                is JsonArray -> raw
                is JsonObject -> raw["iceServers"] as? JsonArray
                else -> null
            }
            list?.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val urls = when (val raw = obj["urls"]) {
                    is JsonArray -> raw.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    null -> emptyList()
                    else -> listOfNotNull(runCatching { raw.jsonPrimitive.content }.getOrNull())
                }
                val username = runCatching { obj["username"]?.jsonPrimitive?.content }.getOrNull()
                val credential = runCatching { obj["credential"]?.jsonPrimitive?.content }.getOrNull()
                urls.forEach { url ->
                    runCatching {
                        val builder = PeerConnection.IceServer.builder(url)
                        if (username != null) builder.setUsername(username)
                        // The worker (and the JS client) call this `credential`; libwebrtc's
                        // Java builder names the same field `password`.
                        if (credential != null) builder.setPassword(credential)
                        servers.add(builder.createIceServer())
                    }
                }
            }
            return servers
        }

        private val STUN_URLS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302",
            "stun:stun3.l.google.com:19302",
            "stun:stun4.l.google.com:19302"
        )

        /** Null when this device cannot do WebRTC at all; the call then runs signal-only. */
        fun create(context: Context, video: Boolean, listener: Listener): WebRtcEngine? {
            if (!WebRtcFactory.ensure(context)) return null
            return WebRtcEngine(context.applicationContext, video, listener)
        }
    }
}

/**
 * Process-wide WebRTC plumbing.
 *
 * `PeerConnectionFactory` is expensive and must be initialised once per process, so it is
 * shared by every call and never torn down — unlike a per-call engine, which is created when a
 * call starts and released when it ends.
 */
internal object WebRtcFactory {

    private const val TAG = "NChatWebRtc"

    @Volatile
    var factory: PeerConnectionFactory? = null
        private set

    /** Kept alive for the process: the encoder/decoder factories hold its EGL context. */
    @Volatile
    private var eglBase: EglBase? = null

    /** Shared by every call-screen renderer; see [WebRtcEngine.eglContext]. */
    val renderContext: EglBase.Context? get() = eglBase?.eglBaseContext

    fun ensure(context: Context): Boolean {
        factory?.let { return true }
        return synchronized(this) {
            factory?.let { return true }
            runCatching {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                val egl = EglBase.create()
                eglBase = egl
                factory = PeerConnectionFactory.builder()
                    .setOptions(PeerConnectionFactory.Options())
                    .setVideoEncoderFactory(
                        DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
                    )
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory()
            }.onFailure { Log.e(TAG, "WebRTC init failed", it) }
            factory != null
        }
    }
}
