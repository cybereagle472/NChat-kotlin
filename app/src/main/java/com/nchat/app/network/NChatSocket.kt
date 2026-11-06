package com.nchat.app.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class ConnectionState { Disconnected, Connecting, Connected }

/**
 * One realtime event, exactly as the backend emits it (`{"type": "...", ...}`).
 *
 * The server fans every event to each member's personal room (`user:<id>`), so a single
 * socket per signed-in client receives direct messages, group traffic, calls and statuses.
 */
class RealtimeEvent(val type: String, val raw: String) {

    /** Parsed payload; cheap enough to build lazily per event and unknown shapes are fine. */
    val json: JsonObject? by lazy {
        runCatching { NcJson.format.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    fun string(key: String): String? =
        (json?.get(key) as? JsonPrimitive)?.contentOrNull

    fun objectOrNull(key: String): JsonObject? =
        json?.get(key)?.let { element ->
            if (element is JsonObject) element else null
        }
}

/**
 * WebSocket client for `/realtime/connect?room=user:<id>&token=<jwt>`.
 *
 * The reference talks to one Durable Object per room; the Rust backend keeps the same
 * contract in-process. Reconnects use exponential backoff (1s → 30s) and reset on success.
 */
class NChatSocket(
    private val api: ApiClient,
    private val tokens: TokenStore,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private var loop: Job? = null
    @Volatile private var socket: WebSocket? = null

    /**
     * Wakes the reconnect loop out of its backoff wait.
     *
     * CONFLATED: several nudges in a row collapse into one, and `trySend` never blocks the
     * caller — [ensureConnected] is called from the UI thread when the app comes back to the
     * foreground, where a suspend call would be a bug.
     */
    private val nudge = Channel<Unit>(Channel.CONFLATED)

    fun connect() {
        if (!api.isConfigured) return
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    /**
     * "Be connected now."
     *
     * Called when the app returns to the foreground or the network changes. A healthy socket
     * is left alone; a sleeping one (or one parked in a long backoff after a failure) is
     * woken immediately instead of waiting out the delay — that wait is invisible to the user
     * but it is exactly the difference between "instant" and "it arrives a few seconds later".
     */
    fun ensureConnected() {
        if (!api.isConfigured) return
        if (_state.value == ConnectionState.Connected && socket != null) return
        if (loop?.isActive != true) {
            connect()
            return
        }
        nudge.trySend(Unit)
    }

    fun disconnect() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "client disconnect")
        socket = null
        _state.value = ConnectionState.Disconnected
    }

    /**
     * Device connectivity, mirrored in from `NetworkMonitor`.
     *
     * While the radio is off there is nothing to reconnect *to*, so the loop parks instead of
     * burning a failing handshake every backoff interval — and the moment the network is back
     * it is woken immediately rather than after the current wait. This is the difference
     * between WhatsApp's instant resume and "it arrives a few seconds later".
     */
    @Volatile private var networkAvailable = true

    fun setNetworkAvailable(available: Boolean) {
        val was = networkAvailable
        networkAvailable = available
        if (available && !was) reconnectNow()
    }

    /**
     * Tears the current socket down and dials again — used when connectivity was re-established.
     *
     * A socket that survived a wifi → cellular handover is often a zombie: the OS keeps the fd,
     * nothing arrives, and the client only notices on a keep-alive timeout tens of seconds
     * later. Re-dialling costs one handshake and makes the reconnect path deterministic; the
     * server's replay-free realtime means the resync that follows is what fills the gap anyway.
     */
    fun reconnectNow() {
        if (!api.isConfigured) return
        socket?.close(4000, "network changed")
        socket = null
        if (loop?.isActive != true) {
            connect()
            return
        }
        _state.value = ConnectionState.Connecting
        ensureConnected()
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            /*
             * No radio, no handshake.
             *
             * The loop used to keep dialling a dead network every 8 s, which costs nothing on
             * screen and a little battery everywhere else; parked here it waits for the nudge
             * that `setNetworkAvailable(true)` sends, so reconnecting after airplane mode is
             * immediate.
             */
            if (!networkAvailable) {
                _state.value = ConnectionState.Connecting
                withTimeoutOrNull(2_000) { nudge.receive() }
                continue
            }

            val userId = tokens.userId
            if (userId.isNullOrEmpty()) {
                // Signed out (or mid-sign-in). Wait instead of dying: this loop used to return
                // here, and nothing ever restarted it, so the app stayed offline after a single
                // refresh race at exactly the wrong moment.
                _state.value = ConnectionState.Disconnected
                delay(1_000)
                continue
            }

            var token = tokens.accessToken
            if (token.isNullOrEmpty()) {
                _state.value = ConnectionState.Connecting
                token = api.renewAccessToken()
                if (token.isNullOrEmpty()) {
                    delay(1_000)
                    continue
                }
            }

            _state.value = ConnectionState.Connecting
            val closed = CompletableDeferred<Unit>()
            var unauthorized = false
            val request = Request.Builder()
                .url("${api.socketRoot}/realtime/connect?room=user:$userId&token=$token")
                .build()

            socket = api.http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        attempt = 0
                        _state.value = ConnectionState.Connected
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        _events.tryEmit(RealtimeEvent(eventType(text), text))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        // The handshake is the one place the access token is checked: a 401 here
                        // means the 15-minute token had expired, and retrying with the same one
                        // would loop until the user restarted the app. Renew first.
                        if (response?.code == 401) unauthorized = true
                        closed.complete(Unit)
                    }
                }
            )

            closed.await()
            socket = null
            if (!currentCoroutineContext().isActive) break

            if (unauthorized) {
                // Renew once; if the refresh token is gone this signs the session out and the
                // `tokens.userId` check above parks the loop instead of hammering the server.
                api.renewAccessToken()
            }

            _state.value = ConnectionState.Connecting
            attempt += 1
            /*
             * Fast first, patient later.
             *
             * This used to start at 2 s and climb to 30 s, so a socket that dropped for any
             * reason — app backgrounded, tunnel, wifi handover — left the account receiving
             * nothing for tens of seconds, and every missed message had to be discovered by a
             * manual refresh. WhatsApp's first reconnect attempt is immediate; only a genuinely
             * unreachable server earns a long wait. Capped at 8 s so a dead network costs the
             * user one missed tick, not a stall.
             */
            val backoff = when (attempt) {
                1 -> 0L
                2 -> 250L
                3 -> 500L
                4 -> 1_000L
                5 -> 2_000L
                else -> 8_000L
            }
            // A foreground nudge (or a network change) skips the wait entirely.
            if (backoff > 0L) withTimeoutOrNull(backoff) { nudge.receive() }
        }
        _state.value = ConnectionState.Disconnected
    }

    /** `{"type":"message:new",...}` → `"message:new"`; empty when the frame has no type. */
    private fun eventType(raw: String): String {
        val parsed = runCatching { NcJson.format.parseToJsonElement(raw).jsonObject }.getOrNull()
        return (parsed?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
