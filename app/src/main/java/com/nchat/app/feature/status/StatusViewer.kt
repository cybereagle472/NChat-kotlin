package com.nchat.app.feature.status

import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.network.MediaFiles
import kotlinx.coroutines.delay

/** How long a text or photo status stays before it advances, as WhatsApp does. */
private const val STATUS_DURATION_MS = 5_000

/**
 * Full-screen story viewer.
 *
 * Text, photo and **video** are all played here, and each one advances on its own clock: a text
 * or photo status for five seconds, a video for exactly its own length. Before this, anything
 * that was not text was handed to an image loader, so a video status showed a blank screen and
 * the progress bar counted to five while the clip had not even started.
 *
 * Sound is on by default and can be muted with the speaker button; muting is remembered for the
 * whole session, because a status is watched in a row and nobody wants to mute five clips.
 */
@Composable
fun StatusViewer(
    statuses: List<StatusUpdate>,
    startIndex: Int,
    onViewed: (String) -> Unit,
    onClose: () -> Unit,
    onDelete: (StatusUpdate) -> Unit = {},
    /** Replying to somebody's status opens your chat with them — the message is a normal one. */
    onReply: (StatusUpdate) -> Unit = {}
) {
    if (statuses.isEmpty()) return

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, statuses.lastIndex)) }
    val progress = remember { Animatable(0f) }
    var paused by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var muted by remember { mutableStateOf(false) }
    val dismissThreshold = with(LocalDensity.current) { 96.dp.toPx() }
    val current = statuses.getOrNull(index) ?: return
    /** Video segments take their progress from the player, not from the timer. */
    var videoProgress by remember(current.id) { mutableFloatStateOf(0f) }

    val url = current.contentUrl
    val isVideo = current.contentType.equals("video", ignoreCase = true) && !url.isNullOrBlank()
    val isPhoto = (current.contentType.equals("image", ignoreCase = true) ||
        current.contentType.equals("photo", ignoreCase = true)) && !url.isNullOrBlank()

    fun advance() {
        if (index < statuses.lastIndex) index += 1 else onClose()
    }

    fun rewind() {
        if (index > 0) index -= 1 else onClose()
    }

    // A new item starts unread-to-read and from the beginning.
    LaunchedEffect(index) {
        onViewed(current.id)
        progress.snapTo(0f)
    }

    // The timer only drives text and photos; a video is measured by the player below.
    LaunchedEffect(index, paused, isVideo) {
        if (paused || isVideo) return@LaunchedEffect
        val remaining = ((1f - progress.value) * STATUS_DURATION_MS).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        if (progress.value >= 1f) advance()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragOffset }
            .background(Color.Black)
            .pointerInput(index) {
                detectTapGestures(
                    onPress = {
                        paused = true
                        tryAwaitRelease()
                        paused = false
                    },
                    onTap = { offset ->
                        val width = size.width.toFloat()
                        when {
                            offset.x <= width * 0.3f -> rewind()
                            offset.x >= width * 0.7f -> advance()
                            else -> Unit
                        }
                    }
                )
            }
            .pointerInput(index) {
                detectVerticalDragGestures(
                    onDragStart = { paused = true },
                    onDragEnd = {
                        if (dragOffset > dismissThreshold) onClose() else {
                            dragOffset = 0f
                            paused = false
                        }
                    },
                    onDragCancel = {
                        dragOffset = 0f
                        paused = false
                    }
                ) { change, dragAmount ->
                    if (dragAmount > 0f || dragOffset > 0f) {
                        dragOffset = (dragOffset + dragAmount).coerceAtLeast(0f)
                        change.consume()
                    }
                }
            }
    ) {
        // ------------------------------------------------------------ the media
        when {
            isVideo -> StatusVideo(
                url = url.orEmpty(),
                paused = paused,
                muted = muted,
                statusId = current.id,
                // The clip's own length is the segment's clock.
                onProgress = { fraction -> videoProgress = fraction },
                onFinished = { advance() },
                onFailed = {
                    NcToaster.show("Couldn't play this video")
                    advance()
                }
            )

            isPhoto -> AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(statusBackdrop(current.backgroundColor), Color(0xFF0B1120))
                        )
                    )
            )
        }

        val caption = current.caption.takeIf {
            it.isNotBlank() && !it.equals("Photo", ignoreCase = true) &&
                !it.equals("Video", ignoreCase = true)
        }

        // Text statuses (and the caption of a photo or video) sit over the media.
        if (caption != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isPhoto || isVideo) Color.Black.copy(alpha = 0.28f) else Color.Transparent
                    )
            )
            Text(
                text = caption.orEmpty(),
                color = Color.White,
                fontSize = if (isPhoto || isVideo) 16.sp else 26.sp,
                lineHeight = if (isPhoto || isVideo) 22.sp else 34.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = statusFont(current.fontStyle),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(if (isPhoto || isVideo) Alignment.BottomCenter else Alignment.Center)
                    .padding(
                        horizontal = NcSpace.xxxl,
                        vertical = if (isPhoto || isVideo) 84.dp else 0.dp
                    )
            )
        }

        // Reply: the same thing WhatsApp's bottom text field does, minus the field. A status is
        // not a message thread, so the honest reply is a direct message to that person.
        if (!current.isMine) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = NcSpace.md, bottom = 32.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x33FFFFFF))
                    .clickable {
                        paused = true
                        onReply(current)
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Reply,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Reply",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // A swipe-down dims the story the further it is dragged towards dismissal.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Color.Black.copy(alpha = (dragOffset / (dismissThreshold * 3f)).coerceIn(0f, 0.5f))
                )
        )

        // --------------------------------------------------------- progress bars
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = NcSpace.sm, vertical = NcSpace.xs),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            statuses.forEachIndexed { segmentIndex, _ ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    val fill = when {
                        segmentIndex < index -> 1f
                        segmentIndex == index -> if (isVideo) videoProgress else progress.value
                        else -> 0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fill)
                            .fillMaxHeight()
                            .background(Color.White)
                    )
                }
            }
        }

        // ------------------------------------------------------------- header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = NcSpace.lg, start = NcSpace.md, end = NcSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Close status",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            NcAvatar(
                name = current.authorName,
                emoji = current.avatarEmoji,
                imageUrl = current.avatarUrl,
                size = 40.dp,
                onGradient = true
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (current.isMine) "My status" else current.authorName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = buildString {
                        append(relativeTime(current.timestamp))
                        remainingLabel(current.expiresAt)?.let { append(" · ").append(it) }
                    },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp
                )
            }

            if (isVideo) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.25f))
                        .clickable { muted = !muted },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                        contentDescription = if (muted) "Unmute" else "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            if (current.isMine) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.25f))
                        .clickable {
                            onDelete(current)
                            onClose()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete this update",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

/**
 * A video status, played from a local copy.
 *
 * `GET /media/file/…` only answers a signed-in caller and `VideoView` cannot attach a bearer
 * token, so the clip is fetched through [MediaFiles] first and played from disk — the same
 * arrangement voice notes already use. The segment's progress comes from the player's own
 * position, which is the only way a 40-second clip and a 4-second clip can both look right.
 */
@Composable
private fun StatusVideo(
    url: String,
    paused: Boolean,
    muted: Boolean,
    statusId: String,
    onProgress: (Float) -> Unit,
    onFinished: () -> Unit,
    onFailed: () -> Unit
) {
    var file by remember(statusId) { mutableStateOf<java.io.File?>(null) }
    var player by remember(statusId) { mutableStateOf<VideoView?>(null) }
    /** `VideoView` exposes no volume API, so the prepared `MediaPlayer` is kept for muting. */
    var media by remember(statusId) { mutableStateOf<android.media.MediaPlayer?>(null) }

    LaunchedEffect(statusId) {
        val result = MediaFiles.local(url)
        result.onSuccess { file = it }.onFailure { onFailed() }
    }

    // Leaving this status must stop its sound: a `VideoView` keeps decoding after the composable
    // that created it is gone unless it is told otherwise, and the next story would play over it.
    DisposableEffect(player) {
        onDispose { runCatching { player?.stopPlayback() } }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ready = file
        if (ready != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        setVideoPath(ready.absolutePath)
                        setOnPreparedListener { prepared ->
                            prepared.isLooping = false
                            prepared.setVolume(
                                if (muted) 0f else 1f,
                                if (muted) 0f else 1f
                            )
                            media = prepared
                            player = this
                            if (!paused) start()
                        }
                        setOnCompletionListener { onFinished() }
                        setOnErrorListener { _, _, _ ->
                            onFailed()
                            true
                        }
                    }
                }
            )
        } else {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        }

        // Pause/play and mute, applied to the surface that is already up.
        LaunchedEffect(paused) {
            val view = player ?: return@LaunchedEffect
            runCatching {
                if (paused) view.pause() else if (!view.isPlaying) view.start()
            }
        }

        LaunchedEffect(muted) {
            runCatching { media?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) }
        }

        // Progress, polled — `VideoView` has no progress callback of its own.
        LaunchedEffect(ready, paused) {
            if (ready == null) return@LaunchedEffect
            while (true) {
                val view = player
                if (view != null && !paused) {
                    val duration = runCatching { view.duration }.getOrDefault(0)
                    if (duration > 0) {
                        onProgress(view.currentPosition.toFloat() / duration.toFloat())
                    }
                }
                delay(80)
            }
        }
    }
}

/** The composer's hex colour, as a backdrop; the fallback is the app's own violet. */
private fun statusBackdrop(hex: String?): Color =
    runCatching {
        if (hex.isNullOrBlank()) Color(0xFF7C3AED)
        else Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(Color(0xFF7C3AED))

/** `sans`, `serif` or `mono`, matching the composer's three font chips. */
private fun statusFont(style: String?): FontFamily = when (style) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

private fun relativeTime(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 1_440L -> "${minutes / 60L} h ago"
        else -> "${minutes / 1_440L} d ago"
    }
}
