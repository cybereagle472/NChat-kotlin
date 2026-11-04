package com.nchat.app.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import kotlin.math.min

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
    private var socket: WebSocket? = null

    fun connect() {
        if (!api.isConfigured) return
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    fun disconnect() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "client disconnect")
        socket = null
        _state.value = ConnectionState.Disconnected
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val userId = tokens.userId
            val token = tokens.accessToken
            if (userId.isNullOrEmpty() || token.isNullOrEmpty()) {
                _state.value = ConnectionState.Disconnected
                return
            }

            _state.value = ConnectionState.Connecting
            val closed = CompletableDeferred<Unit>()
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
                        closed.complete(Unit)
                    }
                }
            )

            closed.await()
            socket = null
            if (!currentCoroutineContext().isActive) break

            _state.value = ConnectionState.Connecting
            attempt += 1
            val backoff = min(30_000L, 1_000L shl min(attempt, 5))
            delay(backoff)
        }
        _state.value = ConnectionState.Disconnected
    }

    /** `{"type":"message:new",...}` → `"message:new"`; empty when the frame has no type. */
    private fun eventType(raw: String): String {
        val parsed = runCatching { NcJson.format.parseToJsonElement(raw).jsonObject }.getOrNull()
        return (parsed?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
