package com.nchat.app.data.repository

import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.data.model.StatusViewer
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NcJson
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.StatusDto
import com.nchat.app.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
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
/**
 * Where a status post is up to, so the composer can show a real bar and a real error.
 *
 * Posting was fire-and-forget: `createPhoto` uploaded, ignored the failure and refreshed, so a
 * status that never went out looked exactly like one that did.
 */
sealed interface StatusPost {
    data object Idle : StatusPost
    /** Uploading, with the fraction the HTTP layer reports (null while preparing). */
    data class Busy(val progress: Float?) : StatusPost
    data class Failed(val message: String) : StatusPost
}

interface StatusRepository {
    val statuses: StateFlow<List<StatusUpdate>>

    /** Progress of the post being composed. */
    val post: StateFlow<StatusPost>

    /** Authors whose updates this account muted (`GET /status/muted`, bare user ids). */
    val mutedAuthors: StateFlow<Set<String>>

    fun refresh()

    /** Posts a text status, with the background colour and font the author picked. */
    fun createText(text: String, backgroundColor: String? = null, fontStyle: String? = null)

    /**
     * Posts a photo or a **video** status: upload the file, then `POST /status` with
     * `content_url` and the matching `content_type`.
     */
    fun createMedia(
        bytes: ByteArray,
        mimeType: String,
        contentType: String,
        caption: String,
        fileName: String,
        durationMs: Long = 0L
    )

    /** Dismisses a failed post so the composer can be used again. */
    fun clearPostError()

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

    /** This account, so an event about my own status is not treated as somebody else's. */
    private val myId: String get() = tokens.userId.orEmpty()

    private val _mutedAuthors = MutableStateFlow<Set<String>>(emptySet())
    override val mutedAuthors: StateFlow<Set<String>> = _mutedAuthors.asStateFlow()

    init {
        scope.launch {
            socket.events.collect { event ->
                when (event.type) {
                    /*
                     * The event carries the whole status row
                     * (`worker/src/routes/status.ts:62-63` sends `{ status: { ...row,
                     * full_name, username, avatar_url } }`), so a new post can be shown the
                     * moment it is published. Reloading mine + the feed + the mute list took
                     * three round trips, which is why a friend's status appeared late.
                     */
                    "status:new" -> {
                        val row = event.objectOrNull("status")
                        val dto = row?.let {
                            runCatching { NcJson.format.decodeFromJsonElement<StatusDto>(it) }.getOrNull()
                        }
                        if (dto == null) refresh() else applyIncoming(dto)
                    }
                    // Somebody looked at *my* status: only my own rows changed.
                    "status:view" -> refreshMine()
                }
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

    private val _post = MutableStateFlow<StatusPost>(StatusPost.Idle)
    override val post: StateFlow<StatusPost> = _post.asStateFlow()

    override fun clearPostError() {
        if (_post.value is StatusPost.Failed) _post.value = StatusPost.Idle
    }

    override fun createText(text: String, backgroundColor: String?, fontStyle: String?) {
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch {
            _post.value = StatusPost.Busy(null)
            runCatching {
                api.createStatus(
                    textContent = body,
                    contentType = "text",
                    backgroundColor = backgroundColor,
                    fontStyle = fontStyle
                )
            }.onFailure { error ->
                _post.value = StatusPost.Failed(postError(error))
                return@launch
            }
            _post.value = StatusPost.Idle
            refresh()
        }
    }

    override fun createMedia(
        bytes: ByteArray,
        mimeType: String,
        contentType: String,
        caption: String,
        fileName: String,
        durationMs: Long
    ) {
        scope.launch {
            _post.value = StatusPost.Busy(null)
            val media = runCatching {
                api.uploadMedia(fileName, mimeType, bytes) { fraction ->
                    _post.value = StatusPost.Busy(fraction)
                }
            }.getOrElse { error ->
                _post.value = StatusPost.Failed(postError(error))
                return@launch
            }
            runCatching {
                api.createStatus(
                    textContent = caption,
                    contentType = contentType,
                    caption = caption.takeIf { it.isNotBlank() },
                    contentUrl = media.url,
                    durationMs = durationMs
                )
            }.onFailure { error ->
                _post.value = StatusPost.Failed(postError(error))
                return@launch
            }
            _post.value = StatusPost.Idle
            refresh()
        }
    }

    /** The composer shows this verbatim, so it has to say what the user can do about it. */
    private fun postError(error: Throwable): String {
        val text = error.message.orEmpty()
        return if (text.contains("timeout", ignoreCase = true) ||
            text.contains("Failed to connect", ignoreCase = true) ||
            text.contains("Unable to resolve host", ignoreCase = true)
        ) {
            "Couldn't reach NChat. Check your connection and try again."
        } else {
            "Couldn't post your status. Please try again."
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

    /** A friend published a status: put it at the top of the list right away. */
    private fun applyIncoming(dto: StatusDto) {
        if (dto.id.isEmpty() || dto.userId == myId) return
        if (dto.userId in _mutedAuthors.value) return
        val name = dto.fullName ?: dto.username ?: "NChat user"
        _statuses.update { list ->
            (list.filterNot { it.id == dto.id } + dto.toModel(name, isMine = false))
                .sortedByDescending { it.timestamp }
        }
    }

    /** Refreshes only this account's own statuses (view counts). */
    private fun refreshMine() {
        scope.launch {
            val mine = runCatching { api.myStatuses().statuses }.getOrDefault(emptyList())
            val myName = tokens.fullName ?: "You"
            _statuses.update { list ->
                (list.filterNot { it.isMine } + mine.map { it.toModel(myName, isMine = true) })
                    .sortedByDescending { it.timestamp }
            }
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
        isMine = isMine,
        expiresAt = isoMillis(expiresAt),
        backgroundColor = backgroundColor,
        fontStyle = fontStyle,
        durationMs = duration ?: 0L
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
