package com.nchat.app.data.repository

import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.data.model.ActiveCall
import com.nchat.app.data.model.CallRecord
import com.nchat.app.data.model.CallState
import com.nchat.app.network.CallDto
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.NcJson
import com.nchat.app.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Calls, both directions, against the Cloudflare worker.
 *
 * The protocol is entirely REST + realtime (`worker/src/routes/calls.ts`):
 *
 * - caller: `POST /calls` → `{ call }`, and the callee's personal room receives
 *   `call:incoming` with the full call plus `caller_profile` (`calls.ts:66`, `:117`);
 * - callee answers: `POST /calls/:id/answer` and the caller receives `call:answered`;
 * - either side ends it: `POST /calls/:id/end` and the other receives `call:ended`;
 * - `GET /calls/history` feeds the History tab.
 *
 * Before 1.0.40 none of that was called: the call screen ran a 1.6-second timer and then
 * pretended to be connected, the callee's phone never rang, and no call record was ever
 * written. [active] is the state machine the call screen renders, so what is on screen is
 * exactly what the server has.
 *
 * **Media**: exchanging audio/video needs WebRTC (`signalCall` / `turn-credentials` are the
 * transport for it). This build wires the full signalling and call lifecycle; the media
 * layer is the next piece, and the call screen says so instead of faking a conversation.
 */
interface CallRepository {
    val history: StateFlow<List<CallRecord>>

    /** The call this app is ringing/connected in, or null. Drives the call screen. */
    val active: StateFlow<ActiveCall?>

    fun refresh()

    /** `POST /calls` — opens a call record and rings the other side. */
    suspend fun placeCall(personId: String, video: Boolean): String? = null

    /** `POST /calls/{id}/end` — writes the outcome the History tab reads back. */
    suspend fun finishCall(callId: String, seconds: Long, status: String = "ended") = Unit

    /** Start an outgoing call and show the ringing screen. */
    fun dial(personId: String, name: String, avatarUrl: String?, video: Boolean)

    /** Answer the incoming call on screen (`POST /calls/:id/answer`). */
    fun answer()

    /** Decline the incoming call; the caller sees "Declined". */
    fun decline()

    /** Hang up / cancel a ringing call. */
    fun hangUp()

    /** Clear a finished call from the screen (the overlay's own "close"). */
    fun dismiss()
}

class RemoteCallRepository(
    private val api: NChatApi,
    private val tokens: TokenStore,
    private val socket: NChatSocket,
    private val scope: CoroutineScope
) : CallRepository {

    private val _history = MutableStateFlow<List<CallRecord>>(emptyList())
    override val history: StateFlow<List<CallRecord>> = _history.asStateFlow()

    private val _active = MutableStateFlow<ActiveCall?>(null)
    override val active: StateFlow<ActiveCall?> = _active.asStateFlow()

    private val myId: String get() = tokens.userId.orEmpty()

    init {
        scope.launch {
            socket.events.collect { event -> handleCallEvent(event.type, event) }
        }
        refresh()
    }

    // ------------------------------------------------------------------ screen

    override fun dial(personId: String, name: String, avatarUrl: String?, video: Boolean) {
        if (personId.isBlank()) return
        if (_active.value != null) {
            NcToaster.show("You're already on a call")
            return
        }
        scope.launch {
            val id = placeCall(personId, video)
            if (id == null) {
                NcToaster.show("Couldn't start the call — check your connection")
                return@launch
            }
            _active.value = ActiveCall(
                id = id,
                peerId = personId,
                peerName = name,
                peerAvatarUrl = avatarUrl,
                isVideo = video,
                incoming = false,
                state = CallState.Ringing
            )
        }
    }

    override fun answer() {
        val call = _active.value ?: return
        if (call.state != CallState.Ringing) return
        scope.launch {
            val answered = runCatching { api.answerCall(call.id, null) }.isSuccess
            if (!answered) {
                NcToaster.show("Couldn't answer — the call may have ended")
                _active.value = call.copy(state = CallState.Ended, note = "Missed")
                scheduleDismiss()
                return@launch
            }
            _active.value = call.copy(
                state = CallState.Connected,
                startedAt = System.currentTimeMillis()
            )
        }
    }

    override fun decline() {
        val call = _active.value ?: return
        _active.value = call.copy(state = CallState.Ended, note = "Declined")
        scope.launch {
            runCatching { api.endCall(call.id, "declined", 0L) }
            refresh()
        }
        scheduleDismiss()
    }

    override fun hangUp() {
        val call = _active.value ?: return
        val seconds = if (call.state == CallState.Connected && call.startedAt > 0L) {
            ((System.currentTimeMillis() - call.startedAt) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        _active.value = if (call.state == CallState.Ringing) {
            call.copy(state = CallState.Ended, note = "Cancelled")
        } else {
            call.copy(state = CallState.Ended, note = null)
        }
        scope.launch {
            // `status` is what the History row shows on both phones; a call nobody picked up
            // is "missed", not "ended" (see the reference's `endCall` call sites).
            val status = if (call.state == CallState.Ringing) {
                if (call.incoming) "declined" else "missed"
            } else {
                "ended"
            }
            runCatching { api.endCall(call.id, status, seconds) }
            refresh()
        }
        scheduleDismiss()
    }

    override fun dismiss() {
        _active.value = null
    }

    /** Closes the overlay a moment after it shows how the call finished. */
    private fun scheduleDismiss() {
        scope.launch {
            delay(1_600)
            // Only clear the call we finished — a new one may have started meanwhile.
            val current = _active.value
            if (current != null && current.state == CallState.Ended) _active.value = null
        }
    }

    // ------------------------------------------------------------------ events

    private fun handleCallEvent(type: String, event: com.nchat.app.network.RealtimeEvent) {
        when (type) {
            "call:incoming" -> {
                val row = event.objectOrNull("call") ?: return
                val call = runCatching { NcJson.format.decodeFromJsonElement<CallDto>(row) }.getOrNull()
                    ?: return
                if (call.callerId == myId) return
                // Already busy: tell the caller so their screen doesn't ring forever.
                if (_active.value != null) {
                    scope.launch { runCatching { api.endCall(call.id, "declined", 0L) } }
                    return
                }
                val profile = call.callerProfile
                _active.value = ActiveCall(
                    id = call.id,
                    peerId = call.callerId,
                    peerName = profile?.fullName ?: profile?.username ?: "NChat user",
                    peerAvatarUrl = profile?.avatarUrl,
                    isVideo = call.callType.equals("video", ignoreCase = true),
                    incoming = true,
                    state = CallState.Ringing
                )
            }

            "call:answered" -> {
                val callId = event.string("callId") ?: event.string("call_id") ?: return
                val current = _active.value ?: return
                if (current.id != callId || current.state != CallState.Ringing) return
                _active.value = current.copy(
                    state = CallState.Connected,
                    startedAt = System.currentTimeMillis()
                )
            }

            "call:ended" -> {
                val callId = event.string("callId") ?: event.string("call_id")
                val current = _active.value
                val status = event.string("status").orEmpty()
                if (current != null && (callId == null || current.id == callId)) {
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

            "call:signal" -> Unit // WebRTC transport — see the class comment.
        }
    }

    // ------------------------------------------------------------------- REST

    override suspend fun placeCall(personId: String, video: Boolean): String? =
        runCatching {
            api.startCall(personId, if (video) "video" else "audio").call?.id
        }.getOrNull()

    override suspend fun finishCall(callId: String, seconds: Long, status: String) {
        runCatching { api.endCall(callId, status, seconds) }
        refresh()
    }

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

    private companion object {
        val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
