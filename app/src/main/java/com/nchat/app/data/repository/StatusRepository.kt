package com.nchat.app.data.repository

import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.StatusDto
import com.nchat.app.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Statuses (the 24-hour stories): my own, plus the feed of friends who haven't been muted.
 *
 * The server keeps them apart (`/status/mine`, `/status/feed`) because only mine carry a
 * viewer count; the UI shows one list, so they are merged with `isMine` set the way the
 * Status tab expects.
 */
interface StatusRepository {
    val statuses: StateFlow<List<StatusUpdate>>
    fun refresh()
    fun create(text: String)
    fun markViewed(id: String)
    fun delete(id: String)
}

class RemoteStatusRepository(
    private val api: NChatApi,
    private val tokens: TokenStore,
    private val socket: NChatSocket,
    private val scope: CoroutineScope
) : StatusRepository {

    private val _statuses = MutableStateFlow<List<StatusUpdate>>(emptyList())
    override val statuses: StateFlow<List<StatusUpdate>> = _statuses.asStateFlow()

    init {
        scope.launch {
            socket.events.collect { event ->
                if (event.type == "status:new" || event.type == "status:view") refresh()
            }
        }
        refresh()
    }

    override fun refresh() {
        scope.launch {
            val mine = runCatching { api.myStatuses().statuses }.getOrDefault(emptyList())
            val feed = runCatching { api.statusFeed().statuses }.getOrDefault(emptyList())
            val myName = tokens.fullName ?: "You"
            _statuses.value = mine.map { it.toModel(myName, isMine = true) } +
                feed.map { it.toModel(it.fullName ?: "NChat user", isMine = false) }
        }
    }

    override fun create(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch {
            runCatching { api.createStatus(body) }
            refresh()
        }
    }

    override fun markViewed(id: String) {
        _statuses.update { list -> list.map { if (it.id == id) it.copy(viewed = true) else it } }
        if (id.startsWith("status-")) return
        scope.launch { runCatching { api.viewStatus(id) } }
    }

    override fun delete(id: String) {
        _statuses.update { list -> list.filterNot { it.id == id } }
        scope.launch {
            runCatching { api.deleteStatus(id) }
            refresh()
        }
    }

    private fun StatusDto.toModel(authorName: String, isMine: Boolean) = StatusUpdate(
        id = id,
        authorName = authorName,
        avatarUrl = avatarUrl,
        caption = textContent?.takeIf { it.isNotBlank() }
            ?: caption?.takeIf { it.isNotBlank() }
            ?: when (contentType) {
                "image", "photo" -> "Photo"
                "video" -> "Video"
                else -> ""
            },
        timestamp = isoMillis(createdAt),
        viewed = isMine || viewedByMe,
        isMine = isMine
    )

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
