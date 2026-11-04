package com.nchat.app.data.repository

import com.nchat.app.data.mock.MockDataProvider
import com.nchat.app.data.model.CallRecord
import com.nchat.app.network.CallDto
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The History tab: one row per finished call, newest first. */
interface CallRepository {
    val history: StateFlow<List<CallRecord>>
    fun refresh()
}

class InMemoryCallRepository(
    seed: List<CallRecord> = MockDataProvider.calls()
) : CallRepository {

    private val _history = MutableStateFlow(seed)
    override val history: StateFlow<List<CallRecord>> = _history.asStateFlow()

    override fun refresh() = Unit
}

class RemoteCallRepository(
    private val api: NChatApi,
    private val tokens: TokenStore,
    private val socket: NChatSocket,
    private val scope: CoroutineScope
) : CallRepository {

    private val _history = MutableStateFlow<List<CallRecord>>(emptyList())
    override val history: StateFlow<List<CallRecord>> = _history.asStateFlow()

    private val myId: String get() = tokens.userId.orEmpty()

    init {
        scope.launch {
            socket.events.collect { event ->
                when (event.type) {
                    "call:ended", "call:answered", "call:incoming" -> refresh()
                }
            }
        }
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
