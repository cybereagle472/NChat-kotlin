package com.nchat.app.data.repository

import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.data.model.StatusViewer
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

    /** Authors whose updates this account muted (`GET /status/muted`, bare user ids). */
    val mutedAuthors: StateFlow<Set<String>>

    fun refresh()
    fun create(text: String)

    /** Posts a photo status: upload the picture, then `POST /status` with `content_url`. */
    fun createPhoto(bytes: ByteArray, mimeType: String, caption: String, fileName: String = "status.jpg")

    fun markViewed(id: String)
    fun delete(id: String)

    /** Who watched my status — `GET /status/:id/views`. Empty for anyone else's. */
    suspend fun viewers(statusId: String): List<StatusViewer>

    fun mute(authorId: String)
    fun unmute(authorId: String)
}

class RemoteStatusRepository(
    private val api: NChatApi,
    private val tokens: TokenStore,
    private val socket: NChatSocket,
    private val scope: CoroutineScope
) : StatusRepository {

    private val _statuses = MutableStateFlow<List<StatusUpdate>>(emptyList())
    override val statuses: StateFlow<List<StatusUpdate>> = _statuses.asStateFlow()

    private val _mutedAuthors = MutableStateFlow<Set<String>>(emptySet())
    override val mutedAuthors: StateFlow<Set<String>> = _mutedAuthors.asStateFlow()

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
            runCatching { api.mutedStatuses().muted }.getOrNull()?.let { _mutedAuthors.value = it.toSet() }
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

    override fun createPhoto(bytes: ByteArray, mimeType: String, caption: String, fileName: String) {
        scope.launch {
            val media = runCatching { api.uploadMedia(fileName, mimeType, bytes) }.getOrNull()
                ?: return@launch
            runCatching {
                api.createStatus(
                    textContent = caption,
                    contentType = "image",
                    contentUrl = media.url
                )
            }
            refresh()
        }
    }

    override suspend fun viewers(statusId: String): List<StatusViewer> =
        runCatching { api.statusViews(statusId).views }.getOrDefault(emptyList()).map { view ->
            StatusViewer(
                id = view.viewerId,
                name = view.fullName?.takeIf { it.isNotBlank() }
                    ?: view.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
                    ?: "NChat user",
                avatarUrl = view.avatarUrl,
                viewedAt = isoMillis(view.viewedAt)
            )
        }

    override fun mute(authorId: String) {
        if (authorId.isBlank()) return
        _mutedAuthors.update { it + authorId }
        _statuses.update { list -> list.filterNot { it.authorId == authorId && !it.isMine } }
        scope.launch {
            runCatching { api.muteStatus(authorId) }
            refresh()
        }
    }

    override fun unmute(authorId: String) {
        if (authorId.isBlank()) return
        _mutedAuthors.update { it - authorId }
        scope.launch {
            runCatching { api.unmuteStatus(authorId) }
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
        authorId = userId,
        contentType = contentType,
        contentUrl = contentUrl,
        viewCount = viewCount,
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
