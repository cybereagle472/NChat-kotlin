package com.nchat.app.data.repository

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.nchat.app.core.designsystem.NcToaster
import androidx.compose.runtime.Immutable
import com.nchat.app.data.calls.GroupCallSession
import com.nchat.app.data.calls.WebRtcEngine
import com.nchat.app.data.calls.WebRtcFactory
import com.nchat.app.data.model.ActiveCall
import com.nchat.app.data.model.CallRecord
import com.nchat.app.data.model.CallState
import com.nchat.app.network.CallDto
import com.nchat.app.network.ApiException
import com.nchat.app.network.GroupCallParticipantDto
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.NcJson
import com.nchat.app.network.RealtimeEvent
import com.nchat.app.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Calls, both directions, against the Cloudflare worker — signalling **and** media.
 *
 * The protocol is REST + realtime (`worker/src/routes/calls.ts`), and the media half is
 * [WebRtcEngine]:
 *
 * - caller: `POST /calls { receiver_id, call_type, offer_sdp }` → `{ call }`, and the callee's
 *   personal room receives `call:incoming` with the call plus `caller_profile` (`calls.ts:66`);
 * - callee answers: `POST /calls/:id/answer { answer_sdp }` → the caller receives
 *   `call:answered { callId, answer_sdp }`;
 * - both: ICE candidates go out through `POST /calls/:id/signal { candidate }` and arrive as
 *   `call:signal { callId, from, candidate }`;
 * - either side ends it: `POST /calls/:id/end { status, duration }` → `call:ended`;
 * - `GET /calls/history` feeds the History tab, `GET /calls/turn-credentials` supplies the
 *   Cloudflare TURN servers used when a direct path is impossible.
 *
 * The state machine is deliberately explicit — [CallState.Ringing] → [CallState.Connecting] →
 * [CallState.Live] → [CallState.Ended] — because the reference's hardest-won lesson is that
 * "answered" and "connected" are different things (`CallModal.tsx` keeps
 * `everConnectedRef`, a 20 s connecting timeout and a 6 s disconnect grace for exactly this).
 * A call that never connects is reported as failed instead of sitting on "Connecting…".
 */
/** One participant of a group call, as the overlay draws them. */
@Immutable
data class GroupCallPeer(
    val id: String,
    val name: String,
    /** That participant's audio is flowing. */
    val connected: Boolean = false
)

enum class GroupCallState { Connecting, Live, Ended }

/** The group call this app is in. Null when there is none. */
@Immutable
data class GroupCallUi(
    val id: String,
    val groupId: String,
    val groupName: String,
    val peers: List<GroupCallPeer> = emptyList(),
    val state: GroupCallState = GroupCallState.Connecting,
    val micMuted: Boolean = false,
    val speakerOn: Boolean = true,
    val startedAt: Long = 0L,
    val note: String? = null
) {
    val connectedCount: Int get() = peers.count { it.connected }
}

interface CallRepository {
    val history: StateFlow<List<CallRecord>>

    /** The group call in progress, or null. Drives [com.nchat.app.feature.chats.GroupCallOverlay]. */
    val groupCall: StateFlow<GroupCallUi?>

    /**
     * Starts a group call (`POST /group-calls`) and joins it.
     *
     * A server without the route answers 404/405; that is reported as "group calls aren't enabled
     * on this server" instead of ringing into nothing.
     */
    fun startGroupCall(groupId: String, groupName: String, video: Boolean)

    /** Leaves the group call: tells the server, closes every peer, and clears the screen. */
    fun leaveGroupCall()

    fun toggleGroupMic()

    /** The call this app is ringing/connected in, or null. Drives the call screen. */
    val active: StateFlow<ActiveCall?>

    /** Our camera, for the call screen's preview renderer. Null when there is no video. */
    val localVideoTrack: StateFlow<VideoTrack?>

    /** The far end's camera, for the full-screen renderer. */
    val remoteVideoTrack: StateFlow<VideoTrack?>

    /** The EGL context the renderers must be initialised with; null when media is down. */
    val eglContext: EglBase.Context?

    fun refresh()

    /** Start an outgoing call and show the ringing screen (`POST /calls`). */
    fun dial(personId: String, name: String, avatarUrl: String?, video: Boolean)

    /** Answer the incoming call on screen (offer → `POST /calls/:id/answer`). */
    fun answer()

    /** Decline the incoming call; the caller sees "Declined". */
    fun decline()

    /** Hang up / cancel a ringing call. */
    fun hangUp()

    /** Clear a finished call from the screen. */
    fun dismiss()

    fun setMicMuted(muted: Boolean)

    fun setSpeaker(on: Boolean)

    fun flipCamera()
}

class RemoteCallRepository(
    private val api: NChatApi,
    private val tokens: TokenStore,
    private val socket: NChatSocket,
    private val scope: CoroutineScope,
    private val context: Context
) : CallRepository {

    private val _history = MutableStateFlow<List<CallRecord>>(emptyList())
    override val history: StateFlow<List<CallRecord>> = _history.asStateFlow()

    private val _active = MutableStateFlow<ActiveCall?>(null)
    override val active: StateFlow<ActiveCall?> = _active.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    override val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    override val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    override val eglContext: EglBase.Context? get() = engine?.eglContext

    private val myId: String get() = tokens.userId.orEmpty()

    // --------------------------------------------------------------- call state

    /*
     * Cross-thread state.
     *
     * The app-wide coroutine scope runs on `Dispatchers.Default` and socket events arrive on
     * their own thread, so every field the call machine shares is volatile, and the candidate
     * queue is lock-guarded: an ICE candidate produced while `POST /calls` was still in flight
     * must not be lost to a data race.
     */
    @Volatile
    private var engine: WebRtcEngine? = null

    @Volatile
    private var callId: String? = null

    /** The caller's offer, held between `call:incoming` and the Answer tap. */
    @Volatile
    private var pendingOffer: JsonObject? = null

    /** Candidates gathered before the server gave us a call id. */
    private val pendingCandidates = mutableListOf<JsonObject>()

    // ---------------------------------------------------------------- group calls

    private val _groupCall = MutableStateFlow<GroupCallUi?>(null)
    override val groupCall: StateFlow<GroupCallUi?> = _groupCall.asStateFlow()

    private var groupSession: GroupCallSession? = null
    private var groupMicMuted = false
    private var groupCallId: String? = null
    /** Signals that arrived before the session existed (the join response races the socket). */
    private val earlyGroupSignals = mutableListOf<Pair<String, JsonObject>>()

    private var dialJob: Job? = null
    private var answerJob: Job? = null
    private var ringJob: Job? = null
    private var connectingJob: Job? = null
    private var disconnectJob: Job? = null
    private var dismissJob: Job? = null
    @Volatile
    private var everConnected = false
    /** Guards against a second teardown of the same call. */
    @Volatile
    private var terminated = false

    private var turnCache: JsonElement? = null
    private var turnFetchedAt = 0L

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /** Takes the audio route for the duration of a call and hands it back afterwards. */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        runCatching {
            audioManager?.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        runCatching { audioManager?.abandonAudioFocus(focusListener) }
    }

    init {
        scope.launch {
            socket.events.collect { event -> handleCallEvent(event.type, event) }
        }
        refresh()
    }

    /** True while a call is on screen *or* being prepared (before the server returns an id). */
    private fun busy(): Boolean =
        _active.value != null || dialJob?.isActive == true || answerJob?.isActive == true

    // -------------------------------------------------------------- dial / answer

    override fun dial(personId: String, name: String, avatarUrl: String?, video: Boolean) {
        if (personId.isBlank()) return
        if (busy()) {
            NcToaster.show("You're already on a call")
            return
        }
        dialJob = scope.launch {
            val ice = iceServers()
            val callEngine = withContext(Dispatchers.Default) {
                WebRtcEngine.create(context, video, listener)
            }
            if (callEngine == null) {
                NcToaster.show("This device can't make calls")
                return@launch
            }
            engine = callEngine
            val offer = callEngine.createOffer(ice)
            if (offer == null) {
                closeMedia()
                NcToaster.show("Couldn't start the call — check microphone/camera access")
                return@launch
            }
            val id = runCatching {
                api.startCall(personId, if (video) "video" else "audio", offer).call?.id
            }.getOrNull()
            if (id == null) {
                closeMedia()
                NcToaster.show("Couldn't start the call — check your connection")
                return@launch
            }
            callId = id
            terminated = false
            drainPendingCandidates()
            _active.value = ActiveCall(
                id = id,
                peerId = personId,
                peerName = name,
                peerAvatarUrl = avatarUrl,
                isVideo = video,
                incoming = false,
                state = CallState.Ringing,
                speakerOn = video
            )
            applyAudioRoute(video)
            requestAudioFocus()
            // No voicemail: nobody picked up within the ring window, so record it as missed.
            ringJob = scope.launch {
                delay(RING_TIMEOUT_MS)
                if (_active.value?.state == CallState.Ringing) {
                    _active.value = _active.value?.copy(state = CallState.Ended, note = "No answer")
                    finish(callId, "missed", 0L)
                }
            }
        }
    }

    override fun answer() {
        val call = _active.value ?: return
        if (call.state != CallState.Ringing || !call.incoming) return
        if (answerJob?.isActive == true) return
        answerJob = scope.launch {
            val offer = pendingOffer
            if (offer == null) {
                NcToaster.show("This call's setup didn't arrive — ask them to call again")
                decline()
                return@launch
            }
            val callEngine = withContext(Dispatchers.Default) {
                WebRtcEngine.create(context, call.isVideo, listener)
            }
            if (callEngine == null) {
                NcToaster.show("This device can't make calls")
                decline()
                return@launch
            }
            engine = callEngine
            _active.value = call.copy(state = CallState.Connecting, startedAt = 0L)
            val answer = callEngine.acceptOffer(offer, iceServers())
            if (answer == null) {
                // We never managed to join: that is a dropped call, not a declined one
                // (`CallModal.tsx` answerCall's catch does exactly this).
                closeMedia()
                _active.value = call.copy(state = CallState.Ended, note = "Couldn't connect")
                runCatching { api.endCall(call.id, "ended", 0L) }
                scheduleDismiss()
                return@launch
            }
            val ok = runCatching { api.answerCall(call.id, answer) }.isSuccess
            if (!ok) {
                // Most likely the caller already hung up while we were accepting.
                closeMedia()
                _active.value = null
                refresh()
                return@launch
            }
            callId = call.id
            terminated = false
            drainPendingCandidates()
            requestAudioFocus()
            armConnectingTimeout()
        }
    }

    override fun decline() {
        val call = _active.value ?: return
        stopTimeouts()
        _active.value = call.copy(state = CallState.Ended, note = "Declined")
        closeMedia()
        scope.launch {
            runCatching { api.endCall(call.id, "declined", 0L) }
            refresh()
        }
        scheduleDismiss()
    }

    override fun hangUp() {
        val call = _active.value ?: return
        val seconds = if (call.state == CallState.Live && call.startedAt > 0L) {
            ((System.currentTimeMillis() - call.startedAt) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        stopTimeouts()
        _active.value = if (call.state == CallState.Ringing) {
            call.copy(state = CallState.Ended, note = "Cancelled")
        } else {
            call.copy(state = CallState.Ended, note = null)
        }
        closeMedia()
        scope.launch {
            // A call nobody picked up is "missed" on both phones, not "ended".
            val status = when {
                call.state == CallState.Ringing && call.incoming -> "declined"
                call.state == CallState.Ringing -> "missed"
                else -> "ended"
            }
            runCatching { api.endCall(call.id, status, seconds) }
            refresh()
        }
        scheduleDismiss()
    }

    override fun dismiss() {
        dismissJob?.cancel()
        _active.value = null
        if (_active.value == null) {
            terminated = true
            closeMedia()
        }
    }

    override fun setMicMuted(muted: Boolean) {
        engine?.setMicMuted(muted)
        _active.update { it?.copy(micMuted = muted) }
    }

    override fun setSpeaker(on: Boolean) {
        _active.update { it?.copy(speakerOn = on) }
        applyAudioRoute(on)
    }

    override fun flipCamera() {
        engine?.flipCamera()
        _active.update { it?.copy(frontCamera = engine?.isFrontCamera ?: it.frontCamera) }
    }

    // -------------------------------------------------------------- group calls

    override fun startGroupCall(groupId: String, groupName: String, video: Boolean) {
        if (groupId.isBlank()) return
        if (_active.value != null || _groupCall.value != null) {
            NcToaster.show("You're already on a call")
            return
        }
        scope.launch {
            val envelope = runCatching { api.startGroupCall(groupId, if (video) "video" else "audio") }
                .getOrElse { error ->
                    // No group-call support on this server is a *different* message from "no
                    // network": one is worth retrying, the other never will be.
                    val unsupported = (error as? ApiException)?.let { it.status == 404 || it.status == 405 } == true
                    NcToaster.show(
                        if (unsupported) "This server doesn't support group calls yet"
                        else "Couldn't start the group call — check your connection"
                    )
                    return@launch
                }
            val call = envelope.call ?: return@launch
            joinGroupSession(
                callId = call.id,
                groupId = groupId,
                groupName = call.groupName ?: groupName,
                participants = envelope.participants,
                video = video
            )
        }
    }

    private suspend fun joinGroupSession(
        callId: String,
        groupId: String,
        groupName: String,
        participants: List<GroupCallParticipantDto>,
        video: Boolean
    ) {
        if (!WebRtcFactory.ensure(context)) {
            NcToaster.show("This device can't make calls")
            return
        }
        val others = participants.filter { it.userId.isNotBlank() && it.userId != myId }
        val session = withContext(Dispatchers.Default) {
            GroupCallSession(
                appContext = context.applicationContext,
                callId = callId,
                myId = myId,
                iceServers = iceServers(),
                listener = groupListener
            )
        }
        groupSession = session
        groupCallId = callId
        groupMicMuted = false
        _groupCall.value = GroupCallUi(
            id = callId,
            groupId = groupId,
            groupName = groupName,
            peers = others.map { participant ->
                GroupCallPeer(
                    id = participant.userId,
                    name = participant.profile?.fullName
                        ?: participant.profile?.username
                        ?: "Participant"
                )
            },
            speakerOn = true
        )
        applyAudioRoute(true)
        requestAudioFocus()
        // We just joined, so we are the new side in this mesh: offer to everybody already here.
        others.forEach { participant -> session.addPeer(participant.userId, offer = true) }
        // Anything that raced the join response is replayed now that a session exists.
        val early = synchronized(earlyGroupSignals) {
            val copy = earlyGroupSignals.toList()
            earlyGroupSignals.clear()
            copy
        }
        early.forEach { (from, payload) -> session.handleSignal(from, payload) }
    }

    override fun leaveGroupCall() {
        val current = _groupCall.value ?: return
        val session = groupSession
        val id = groupCallId
        groupSession = null
        groupCallId = null
        _groupCall.value = null
        applyAudioRoute(null)
        abandonAudioFocus()
        scope.launch {
            session?.close()
            if (id != null) runCatching { api.leaveGroupCall(id) }
        }
    }

    override fun toggleGroupMic() {
        groupMicMuted = !groupMicMuted
        groupSession?.setMicMuted(groupMicMuted)
        _groupCall.update { it?.copy(micMuted = groupMicMuted) }
    }

    private val groupListener = object : GroupCallSession.Listener {
        override fun onSignal(peerId: String, payload: JsonObject) {
            val id = groupCallId ?: return
            scope.launch { runCatching { api.signalGroupCall(id, peerId, payload) } }
        }

        override fun onPeerConnected(peerId: String) {
            val current = _groupCall.value ?: return
            val peer = current.peers.firstOrNull { it.id == peerId } ?: return
            val peers = current.peers.map { if (it.id == peerId) peer.copy(connected = true) else it }
            val firstConnection = current.state == GroupCallState.Connecting
            _groupCall.value = current.copy(
                peers = peers,
                state = GroupCallState.Live,
                startedAt = if (firstConnection) System.currentTimeMillis() else current.startedAt
            )
        }

        override fun onPeerFailed(peerId: String) {
            val current = _groupCall.value ?: return
            _groupCall.value = current.copy(
                peers = current.peers.map {
                    if (it.id == peerId) it.copy(connected = false) else it
                },
                note = "A participant lost connection"
            )
        }

        override fun onError(message: String) {
            NcToaster.show(message)
        }
    }

    /**
     * A socket event that belongs to the group call.
     *
     * The worker's group-call event names are not part of the collected contract, so they are
     * matched by *shape* rather than by an exact string: any `…signal…` event that carries our
     * call id and a sender is treated as signalling, and `…join…` / `…leave…` events add or drop a
     * participant. Nothing here ever throws — an unrecognised event is ignored.
     */
    private fun handleGroupEvent(event: RealtimeEvent) {
        val callId = groupCallId ?: return
        val eventCall = event.string("callId")
            ?: event.string("call_id")
            ?: event.string("groupCallId")
            ?: event.string("group_call_id")
        if (eventCall != null && eventCall != callId) return
        val from = event.string("from")
            ?: event.string("from_id")
            ?: event.string("senderId")
            ?: event.string("sender_id")
        val type = event.type.lowercase()
        val payload = event.objectOrNull("payload") ?: event.objectOrNull("data") ?: event.json

        when {
            type.contains("signal") -> if (from != null && payload != null) {
                val session = groupSession
                if (session == null) {
                    synchronized(earlyGroupSignals) { earlyGroupSignals.add(from to payload) }
                } else {
                    session.handleSignal(from, payload)
                }
            }

            type.contains("join") && type.contains("group") -> if (from != null && from != myId) {
                addGroupPeer(from, event.string("name") ?: "Participant")
            }

            type.contains("leave") && type.contains("group") -> if (from != null) {
                val current = _groupCall.value ?: return
                _groupCall.value = current.copy(peers = current.peers.filterNot { it.id == from })
                groupSession?.removePeer(from)
            }

            type.contains("end") && type.contains("group") -> {
                _groupCall.update { it?.copy(state = GroupCallState.Ended, note = "Call ended") }
                val session = groupSession
                groupSession = null
                groupCallId = null
                scope.launch { session?.close() }
                applyAudioRoute(null)
            }
        }
    }

    private fun addGroupPeer(peerId: String, name: String) {
        val current = _groupCall.value ?: return
        if (current.peers.any { it.id == peerId }) return
        _groupCall.value = current.copy(peers = current.peers + GroupCallPeer(id = peerId, name = name))
        // The newcomer will offer to us; a peer connection is created when its first signal lands.
    }

    // -------------------------------------------------------------- media engine

    private val listener = object : WebRtcEngine.Listener {
        override fun onIceCandidate(candidate: JsonObject) {
            val id = callId
            if (id == null) {
                synchronized(pendingCandidates) { pendingCandidates.add(candidate) }
                return
            }
            scope.launch {
                runCatching {
                    api.signalCall(id, buildJsonObject { put("candidate", candidate) })
                }
            }
        }

        override fun onConnected() {
            if (terminated || everConnected) return
            val current = _active.value ?: return
            if (current.state == CallState.Ended) return
            everConnected = true
            disconnectJob?.cancel()
            connectingJob?.cancel()
            ringJob?.cancel()
            _active.update { call ->
                call?.copy(state = CallState.Live, startedAt = System.currentTimeMillis())
            }
            val video = _active.value?.isVideo == true
            applyAudioRoute(_active.value?.speakerOn ?: video)
        }

        override fun onDisconnected() {
            if (!everConnected || terminated) return
            disconnectJob?.cancel()
            // A blip (wifi handover, a missed keepalive) usually recovers within a second or
            // two — the reference waits 6 s before believing it (`CallModal.tsx:33`).
            disconnectJob = scope.launch {
                delay(DISCONNECT_GRACE_MS)
                if (_active.value?.state == CallState.Live) {
                    _active.update { it?.copy(state = CallState.Ended, note = "Connection lost") }
                    finish(callId, "ended", 0L, media = false)
                }
            }
        }

        override fun onFailed() {
            if (terminated) return
            val id = callId
            val note = if (everConnected) "Connection lost" else "Couldn't connect"
            _active.update { it?.copy(state = CallState.Ended, note = note) }
            // `finish` reads the id it was handed, so teardown clearing `callId` cannot leave
            // the other phone ringing for a call this side gave up on.
            finish(id, "ended", 0L)
        }

        override fun onLocalVideo(track: VideoTrack?) {
            _localVideoTrack.value = track
            _active.update { it?.copy(localVideo = track != null) }
        }

        override fun onRemoteVideo(track: VideoTrack?) {
            _remoteVideoTrack.value = track
            _active.update { it?.copy(remoteVideo = track != null) }
        }

        override fun onError(message: String) {
            Log.w(TAG, "media error: $message")
            _active.update { it?.copy(mediaError = message) }
        }
    }

    private fun armConnectingTimeout() {
        connectingJob?.cancel()
        connectingJob = scope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            if (!everConnected && _active.value?.state == CallState.Connecting) {
                _active.update { it?.copy(state = CallState.Ended, note = "Couldn't connect") }
                finish(callId, "ended", 0L, media = false)
            }
        }
    }

    private fun stopTimeouts() {
        ringJob?.cancel()
        connectingJob?.cancel()
        disconnectJob?.cancel()
        ringJob = null
        connectingJob = null
        disconnectJob = null
    }

    /** Ends a call server-side, tears the media down, and lets the screen show the outcome. */
    private fun finish(id: String?, status: String, seconds: Long, media: Boolean = true) {
        if (terminated) return
        terminated = true
        stopTimeouts()
        if (media) closeMedia()
        scope.launch {
            id?.let { runCatching { api.endCall(it, status, seconds) } }
            refresh()
        }
        scheduleDismiss()
    }

    private fun closeMedia() {
        val dying = engine
        engine = null
        // Compose removes the video sinks when these become null; give it a frame or two before
        // the tracks and capturer are disposed underneath it.
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        if (dying != null) scope.launch { delay(SINK_DETACH_MS); dying.close() }
        pendingOffer = null
        callId = null
        synchronized(pendingCandidates) { pendingCandidates.clear() }
        everConnected = false
        applyAudioRoute(null)
        abandonAudioFocus()
    }

    private fun scheduleDismiss() {
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(1_600)
            if (_active.value?.state == CallState.Ended) _active.value = null
        }
    }

    private fun drainPendingCandidates() {
        val id = callId ?: return
        val queued = synchronized(pendingCandidates) {
            if (pendingCandidates.isEmpty()) return
            pendingCandidates.toList().also { pendingCandidates.clear() }
        }
        scope.launch {
            queued.forEach { candidate ->
                runCatching {
                    api.signalCall(id, buildJsonObject { put("candidate", candidate) })
                }
            }
        }
    }

    /** Speaker for video calls and the speakerphone toggle; earpiece otherwise. */
    private fun applyAudioRoute(speaker: Boolean?) {
        val manager = audioManager ?: return
        runCatching {
            if (speaker == null) {
                manager.isSpeakerphoneOn = false
                manager.mode = AudioManager.MODE_NORMAL
            } else {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                manager.isSpeakerphoneOn = speaker
            }
        }
    }

    /**
     * `GET /calls/turn-credentials`, cached for 100 minutes like the reference
     * (`callService.ts:45`) — the worker mints short-lived Cloudflare credentials, and asking
     * again on every call would only add latency to dialling.
     */
    private suspend fun iceServers(): List<org.webrtc.PeerConnection.IceServer> {
        val now = System.currentTimeMillis()
        val fresh = turnCache != null && now - turnFetchedAt < TURN_TTL_MS
        if (!fresh) {
            turnCache = runCatching { api.turnCredentials().iceServers }.getOrNull()
            turnFetchedAt = now
        }
        return WebRtcEngine.iceServers(turnCache)
    }

    // ------------------------------------------------------------------ events

    private fun handleCallEvent(type: String, event: RealtimeEvent) {
        when (type) {
            "call:incoming" -> {
                val row = event.objectOrNull("call") ?: return
                val call = runCatching { NcJson.format.decodeFromJsonElement<CallDto>(row) }
                    .getOrNull() ?: return
                if (call.callerId == myId) return
                if (busy()) {
                    // Busy: tell the caller so their screen doesn't ring forever.
                    scope.launch { runCatching { api.endCall(call.id, "declined", 0L) } }
                    return
                }
                resetCallState()
                pendingOffer = call.offerSdp.asSdpObject()
                val profile = call.callerProfile
                _active.value = ActiveCall(
                    id = call.id,
                    peerId = call.callerId,
                    peerName = profile?.fullName ?: profile?.username ?: "NChat user",
                    peerAvatarUrl = profile?.avatarUrl,
                    isVideo = call.callType.equals("video", ignoreCase = true),
                    incoming = true,
                    state = CallState.Ringing,
                    speakerOn = call.callType.equals("video", ignoreCase = true)
                )
            }

            "call:answered" -> {
                val id = event.string("callId") ?: event.string("call_id") ?: return
                if (id != _active.value?.id) return
                ringJob?.cancel()
                if (_active.value?.state == CallState.Ringing) {
                    _active.update { it?.copy(state = CallState.Connecting) }
                }
                val answer = event.objectOrNull("answer_sdp") ?: event.objectOrNull("answerSdp")
                if (answer != null) engine?.applyAnswer(answer)
                armConnectingTimeout()
            }

            "call:signal" -> {
                val id = event.string("callId") ?: event.string("call_id") ?: return
                if (id != _active.value?.id) return
                val candidate = event.objectOrNull("candidate") ?: return
                engine?.addRemoteCandidate(candidate)
            }

            "call:ended" -> {
                val id = event.string("callId") ?: event.string("call_id")
                val current = _active.value
                val status = event.string("status").orEmpty()
                if (current != null && (id == null || current.id == id)) {
                    terminated = true
                    stopTimeouts()
                    closeMedia()
                    _active.value = current.copy(
                        state = CallState.Ended,
                        note = when {
                            status.equals("declined", true) -> "Declined"
                            status.equals("missed", true) -> "Missed"
                            else -> null
                        }
                    )
                    scheduleDismiss()
                }
                refresh()
            }

            /*
             * Group calls. Their event names are not part of the collected contract, so every
             * remaining event is offered to `handleGroupEvent`, which matches on shape (a call id
             * plus `signal` / `join` / `leave` / `end` in the name) and ignores everything else.
             */
            else -> handleGroupEvent(event)
        }
    }

    private fun resetCallState() {
        stopTimeouts()
        val dying = engine
        engine = null
        if (dying != null) scope.launch { delay(SINK_DETACH_MS); dying.close() }
        callId = null
        pendingOffer = null
        synchronized(pendingCandidates) { pendingCandidates.clear() }
        everConnected = false
        terminated = false
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
    }

    // ------------------------------------------------------------------- REST

    override fun refresh() {
        scope.launch {
            val rows = runCatching { api.callHistory().history }.getOrDefault(emptyList())
            _history.value = rows.map { it.toRecord() }
        }
    }

    private fun CallDto.toRecord(): CallRecord {
        val outgoing = callerId == myId
        val other = otherProfile
        return CallRecord(
            id = id,
            name = other?.fullName ?: other?.username ?: "NChat user",
            personId = other?.id ?: if (outgoing) receiverId else callerId,
            isVideo = callType.equals("video", ignoreCase = true),
            isOutgoing = outgoing,
            missed = status.equals("missed", true) || status.equals("declined", true),
            timestamp = isoMillis(createdAt),
            durationLabel = duration?.takeIf { it > 0 }?.let { seconds ->
                String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
            }
        )
    }

    private fun isoMillis(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return synchronized(ISO) { runCatching { ISO.parse(value)?.time ?: 0L }.getOrDefault(0L) }
    }

    /**
     * SDP arrives either as the object the caller posted (`call:incoming`) or as the JSON
     * string the database stored (`GET /calls/history`), so both are accepted.
     */
    private fun JsonElement?.asSdpObject(): JsonObject? = when (this) {
        is JsonObject -> this
        is JsonPrimitive -> runCatching {
            NcJson.format.parseToJsonElement(content) as? JsonObject
        }.getOrNull()

        else -> null
    }

    private companion object {
        const val TAG = "NChatCalls"

        /** The reference's own numbers (`CallModal.tsx:18`, `:26`, `:33`). */
        const val RING_TIMEOUT_MS = 45_000L
        const val CONNECTING_TIMEOUT_MS = 20_000L
        const val DISCONNECT_GRACE_MS = 6_000L
        const val TURN_TTL_MS = 100 * 60_000L

        /** How long the UI gets to detach video sinks before the media is torn down. */
        const val SINK_DETACH_MS = 400L

        val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
