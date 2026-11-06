@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nchat.app.feature.chats

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.nchat.app.core.audio.VoiceWaveform
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.core.designsystem.BubbleShapeMine
import com.nchat.app.core.designsystem.BubbleShapeTheirs
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.network.MediaFiles
import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import com.nchat.app.data.model.Reaction
import kotlinx.coroutines.delay
import kotlin.math.abs

/** 30dp gradient circle with the sender's initial, shown beside the first bubble of a run. */
@Composable
private fun GroupSenderAvatar(name: String) {
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(30.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(NcTheme.extra.brandGradient)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifBlank { "?" },
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Glass pill that separates messages by day. */
@Composable
fun DateSeparatorPill(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .shadow(1.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x38FFFFFF))
                .padding(horizontal = 14.dp, vertical = 5.dp)
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = NcTheme.extra.textTertiary
            )
        }
    }
}

@Composable
fun MessageBubble(
    message: Message,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    showAuthor: Boolean = false,
    showAvatar: Boolean = false,
    onMediaClick: () -> Unit = {},
    /** Tapping the warning on a message the server never accepted resends it. */
    onRetrySend: () -> Unit = {}
) {
    if (message.systemEvent) {
        DateSeparatorPill(label = message.text, modifier = modifier)
        return
    }
    val extra = NcTheme.extra
    val mine = message.isMine

    if (message.deleted) {
        DeletedBubble(
            message = message,
            mine = mine,
            showAuthor = showAuthor,
            showAvatar = showAvatar,
            modifier = modifier
        )
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        // Group chats prefix an incoming run with the sender's 30dp avatar; later bubbles
        // in the same run keep a spacer so they stay aligned underneath it.
        if (!mine && showAuthor) {
            if (showAvatar) {
                GroupSenderAvatar(message.authorName)
            } else {
                Spacer(modifier = Modifier.size(38.dp))
            }
        }
    Column(
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (showAuthor && !mine) {
            Text(
                text = message.authorName,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
            )
        }
        val bubbleShape = if (mine) BubbleShapeMine else BubbleShapeTheirs
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                // No shadow: every bubble used to own a render layer, which is the most
                // expensive thing you can do per row while scrolling a long history. The
                // incoming hairline gives the same separation for free.
                .clip(bubbleShape)
                .background(
                    if (mine) {
                        Brush.linearGradient(extra.bubbleMineGradient)
                    } else {
                        Brush.linearGradient(listOf(extra.bubbleIncoming, extra.bubbleIncoming))
                    }
                )
                .then(
                    if (mine) Modifier else Modifier.border(1.dp, Color(0x120F172A), bubbleShape)
                )
                .combinedClickable(onClick = onLongPress, onLongClick = onLongPress)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // A reply whose target is outside the loaded page still says *that* it is a reply
            // instead of silently looking like an ordinary message.
            if (message.replyAuthor != null || message.replyToId != null) {
                ReplyQuote(
                    author = message.replyAuthor ?: "Replied message",
                    text = message.replyText.orEmpty(),
                    mine = mine
                )
            }

            when (message.kind) {
                MessageKind.Photo -> PhotoContent(
                    mediaUrl = message.mediaUrl,
                    onClick = onMediaClick,
                    onLongPress = onLongPress
                )
                MessageKind.Voice -> VoiceContent(
                    messageId = message.id,
                    duration = message.durationLabel ?: "0:00",
                    mine = mine,
                    mediaUrl = message.mediaUrl,
                    storedWaveform = message.thumbnailUrl,
                    onLongPress = onLongPress
                )
                MessageKind.Document -> DocumentContent(
                    name = message.fileName ?: "document.pdf",
                    mine = mine
                )
                MessageKind.Location -> LocationContent(mine = mine)
                MessageKind.Video -> VideoContent(
                    mine = mine,
                    mediaUrl = message.mediaUrl,
                    thumbnailUrl = message.thumbnailUrl,
                    onClick = onMediaClick,
                    onLongPress = onLongPress
                )
                MessageKind.Text -> Unit
            }

            if (message.forwarded) {
                Text(
                    text = "Forwarded",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (mine) extra.bubbleMetaOnGradient else extra.bubbleMeta,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            if (message.kind != MessageKind.Voice &&
                (message.kind == MessageKind.Text || message.text.isNotBlank())
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (mine) Color.White else extra.bubbleIncomingText
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = TimeFormat.clock(message.timestamp),
                    fontSize = 10.sp,
                    color = if (mine) extra.bubbleMetaOnGradient else extra.bubbleMeta
                )
                if (message.isEdited) {
                    Text(
                        text = "edited",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (mine) extra.bubbleMetaOnGradient else extra.bubbleMeta
                    )
                }
                if (mine && message.failed) {
                    // The send gave up: say so on the bubble itself and make it the retry target
                    // (a message stuck on the clock forever with no explanation is the bug).
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "Not sent — tap to retry",
                        tint = Color(0xFFFCA5A5),
                        modifier = Modifier
                            .size(15.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onRetrySend)
                    )
                } else if (mine) {
                    Icon(
                        imageVector = if (message.state == DeliveryState.Read) {
                            Icons.Rounded.DoneAll
                        } else {
                            Icons.Rounded.Check
                        },
                        contentDescription = null,
                        tint = if (message.state == DeliveryState.Read) {
                            Color(0xFFE0F2FE)
                        } else {
                            extra.bubbleMetaOnGradient
                        },
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        if (message.reactions.isNotEmpty()) {
            ReactionsRow(
                reactions = message.reactions,
                mine = mine,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
    }
}

/** "Delete for everyone" tombstone: muted bubble, italic label, no actions. */
@Composable
private fun DeletedBubble(
    message: Message,
    mine: Boolean,
    showAuthor: Boolean,
    showAvatar: Boolean,
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        // Group chats prefix an incoming run with the sender's 30dp avatar; later bubbles
        // in the same run keep a spacer so they stay aligned underneath it.
        if (!mine && showAuthor) {
            if (showAvatar) {
                GroupSenderAvatar(message.authorName)
            } else {
                Spacer(modifier = Modifier.size(38.dp))
            }
        }
    Column(
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (showAuthor && !mine) {
            Text(
                text = message.authorName,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
            )
        }
        Row(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(if (mine) BubbleShapeMine else BubbleShapeTheirs)
                .background(Color(0x140F172A))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Block,
                contentDescription = null,
                tint = extra.textMuted,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = "This message was deleted",
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                color = extra.textMuted,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        Text(
            text = TimeFormat.clock(message.timestamp),
            fontSize = 10.sp,
            color = extra.textMuted,
            modifier = Modifier.padding(top = 2.dp, end = if (mine) 4.dp else 0.dp, start = if (mine) 0.dp else 4.dp)
        )
    }
    }
}

@Composable
private fun ReplyQuote(author: String, text: String, mine: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (mine) Color(0x33FFFFFF) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            )
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (mine) Color.White else MaterialTheme.colorScheme.primary)
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = author,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (mine) Color.White else MaterialTheme.colorScheme.primary
            )
            Text(
                text = text,
                fontSize = 12.sp,
                color = if (mine) Color(0xCCFFFFFF) else NcTheme.extra.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReactionsRow(reactions: List<Reaction>, mine: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        reactions.forEach { reaction ->
            val mineReaction = reaction.reactedByMe
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (mineReaction) Color(0xFFEDE9FE) else Color.White)
                    .then(
                        if (mineReaction) {
                            Modifier.border(1.dp, Color(0xFFC4B5FD), CircleShape)
                        } else {
                            Modifier.shadow(1.dp, CircleShape)
                        }
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = reaction.emoji, fontSize = 11.sp)
                    if (reaction.count > 1) {
                        Text(
                            text = reaction.count.toString(),
                            fontSize = 10.sp,
                            color = if (mineReaction) {
                                NcTheme.extra.typing
                            } else {
                                NcTheme.extra.textTertiary
                            },
                            fontWeight = if (mineReaction) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(start = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoContent(mediaUrl: String?, onClick: () -> Unit, onLongPress: () -> Unit) {
    val frame = Modifier
        .width(210.dp)
        .height(140.dp)
        .clip(RoundedCornerShape(12.dp))
        .combinedClickable(onClick = onClick, onLongClick = onLongPress)

    if (mediaUrl != null) {
        /*
         * The uploaded file itself; the gradient is the loading state, never a half-painted
         * picture. A photo on a private endpoint arrives over the authenticated image loader, so
         * on a slow link there is a real wait — it shows a spinner inside the frame (WhatsApp
         * does the same) instead of an empty gradient that looks like a broken image.
         */
        SubcomposeAsyncImage(
            model = mediaUrl,
            contentDescription = "Photo",
            contentScale = ContentScale.Crop,
            modifier = frame.background(Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)))),
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                }
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = "Photo could not be loaded",
                        tint = Color(0xCCFFFFFF),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        )
        return
    }

    Box(
        modifier = frame.background(Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Image,
            contentDescription = null,
            tint = Color(0xE6FFFFFF),
            modifier = Modifier.size(34.dp)
        )
    }
}

@Composable
private fun VideoContent(
    @Suppress("UNUSED_PARAMETER") mine: Boolean,
    mediaUrl: String?,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    /*
     * The poster frame, when the sender produced one. It is deliberately *not* `?: mediaUrl`:
     * an AsyncImage pointed at an .mp4 downloads the whole clip only to fail decoding it, which
     * is why video bubbles from a client that sends no thumbnail looked empty. No thumbnail
     * means the dark tile below, which is the honest state.
     */
    val poster = thumbnailUrl?.takeIf { it.isNotBlank() }
    Box(
        modifier = Modifier
            .width(210.dp)
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center
    ) {
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = "Video",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0x66000000)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play video",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Which voice note is playing right now.
 *
 * The reference pauses every other clip when one starts (`pauseOtherVoiceMessages`,
 * `ChatView.tsx:2209-2211`); two notes talking over each other is the sort of thing that makes
 * a messenger feel broken. Compose state at file scope, so every bubble sees the same value.
 */
private object VoicePlayback {
    var current by mutableStateOf<String?>(null)
}

/** Bars drawn in a voice bubble; the stored array is resampled to this. */
private const val VOICE_BARS = 24

@Composable
private fun VoiceContent(
    /** Identifies *this* bubble for exclusive playback — two notes can share a URL. */
    messageId: String,
    duration: String,
    mine: Boolean,
    mediaUrl: String? = null,
    /**
     * `thumbnail_url` when the sender recorded a waveform — a JSON array of normalised levels.
     *
     * Both clients write it (the reference's `downsampleWaveform`, this app's `VoiceWaveform`),
     * so a voice note keeps the shape of what was actually said wherever it is read.
     */
    storedWaveform: String? = null,
    /** Long press on the waveform opens the message menu, the way the rest of the bubble does. */
    onLongPress: () -> Unit = {}
) {
    /*
     * The clip, as a file on this device.
     *
     * A voice note that came from the server is a URL on a **private** endpoint: `GET
     * /media/file/…` only answers a signed-in caller, and `MediaPlayer` cannot attach a bearer
     * token — `setDataSource(url)` opens the URL itself, anonymously, on the calling thread.
     * Every remote clip therefore ended in "Couldn't play this voice message". The bytes are
     * fetched once through the authenticated client ([MediaFiles]) and played from the cache,
     * which also takes the network off the UI thread and turns every seek into a local read.
     *
     * A clip recorded here (still in the outbox) is already a `file://` URL and plays directly.
     */
    var audioFile by remember(mediaUrl) { mutableStateOf(MediaFiles.existingLocal(mediaUrl)) }
    var loading by remember(mediaUrl) { mutableStateOf(false) }
    var loadError by remember(mediaUrl) { mutableStateOf<String?>(null) }
    /** Bumped when a player dies, so the next tap gets a fresh one instead of a reset one. */
    var generation by remember(mediaUrl) { mutableStateOf(0) }
    /**
     * Where playback should begin once the audio exists.
     *
     * Survives the file arriving (the player state below is keyed on the file and resets), so a
     * tap or a scrub made while the clip was still downloading is not lost.
     */
    var pendingStartAt by remember(mediaUrl) { mutableStateOf<Int?>(null) }

    val filePath = audioFile?.absolutePath

    /*
     * Everything below is keyed on the *file*, not the URL: the moment the download lands, the
     * player state has to start over — a `prepared = true` inherited from a player that never
     * had a data source would call `start()` on an empty one.
     */
    var playing by remember(filePath) { mutableStateOf(false) }
    var prepared by remember(filePath) { mutableStateOf(false) }
    /** `prepareAsync()` is in flight — a second tap must not call `setDataSource` again. */
    var preparing by remember(filePath) { mutableStateOf(false) }
    /** Where the playhead is, in milliseconds. The bar and the timer both read this. */
    var positionMs by remember(filePath) { mutableStateOf(0) }
    /** The clip's real length, once the file has told us — the label can be a second off. */
    var realMs by remember(filePath) { mutableStateOf(0) }
    /** A seek made before the player was ready, applied the moment it is. */
    var pendingSeek by remember(filePath) { mutableStateOf<Int?>(null) }
    /**
     * A finger is on the waveform.
     *
     * While this is true the head follows the finger and the sampler below stays out of the way,
     * so the two can never fight over [positionMs] and make the bar jitter.
     */
    var scrubbing by remember(mediaUrl) { mutableStateOf(false) }
    /**
     * What the user wants, as opposed to what the player is doing.
     *
     * Set on the tap, honoured in `OnPreparedListener` and by the "file just arrived" effect
     * below. Without it, tapping play and then pause while the clip was still loading ended up
     * *playing*: the pause hit a player with nothing to pause, and the prepared callback started
     * it anyway.
     */
    var playWhenReady by remember(mediaUrl) { mutableStateOf(false) }

    val labelledMs = remember(duration) { parseVoiceDuration(duration) }
    val totalMs = if (realMs > 0) realMs else labelledMs
    val progress = if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f

    /*
     * Real bars when the message carries them, and a stable shape otherwise (voice notes sent
     * by an older build, or a clip that recorded nothing but silence). The placeholder is the
     * same repeating pattern the bubble always drew, so old conversations look unchanged.
     */
    val levels = remember(storedWaveform) {
        VoiceWaveform.parse(storedWaveform, VOICE_BARS).ifEmpty {
            List(VOICE_BARS) { index -> (4 + (index * 5) % 12) / 16f }
        }
    }

    /*
     * The clip is fetched as soon as the bubble is on screen.
     *
     * A voice note is tens of kilobytes; WhatsApp also has it before you press play. Fetching on
     * the tap instead meant every first play began with a download — the button sat behind a
     * spinner, which is exactly what "not instantly working" felt like. The work runs on
     * `Dispatchers.IO` inside [MediaFiles] (never on the frame), the result is cached, and a clip
     * that has been played once plays again offline.
     */
    LaunchedEffect(mediaUrl) {
        val url = mediaUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (audioFile != null) return@LaunchedEffect
        loading = true
        loadError = null
        val result = MediaFiles.local(url)
        loading = false
        result.fold(
            onSuccess = { file -> audioFile = file },
            onFailure = { error ->
                val reason = error.message?.takeIf { it.isNotBlank() } ?: "the download failed"
                loadError = reason
                playWhenReady = false
                playing = false
                NcToaster.show("Couldn't load this voice message — $reason")
            }
        )
    }

    // A player per bubble, bound to the local file. Media is served with byte ranges, so a seek
    // on a downloaded clip is immediate.
    val player = remember(filePath, generation) {
        filePath?.let {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                }
            }.getOrNull()
        }
    }
    DisposableEffect(player) {
        onDispose {
            if (VoicePlayback.current == messageId) VoicePlayback.current = null
            runCatching { player?.release() }
        }
    }

    // Exclusive playback: another bubble started, so this one stops where it is.
    val activeId = VoicePlayback.current
    LaunchedEffect(activeId) {
        if (playing && activeId != messageId) {
            runCatching { player?.pause() }
            playing = false
        }
    }

    // The playhead, sampled while the clip runs. 50 ms is smooth enough for a 24-bar wave and
    // cheap enough that a screenful of bubbles costs nothing.
    LaunchedEffect(playing, player) {
        val current = player ?: return@LaunchedEffect
        if (!playing) return@LaunchedEffect
        while (true) {
            delay(50)
            // The finger owns the playhead while it is down; a sample landing mid-drag pulled the
            // bar back towards the player's old position and looked like a stutter.
            if (scrubbing) continue
            runCatching { current.duration }.getOrNull()?.let { if (it > 0 && it != realMs) realMs = it }
            runCatching { current.currentPosition }.getOrNull()?.let { positionMs = it }
        }
    }

    /** Gives up on this player and says why; the next tap builds a new one. */
    fun failPlayback(message: String) {
        playing = false
        playWhenReady = false
        preparing = false
        prepared = false
        positionMs = 0
        if (VoicePlayback.current == messageId) VoicePlayback.current = null
        generation += 1
        NcToaster.show(message)
    }

    /**
     * Moves the playhead without changing whether the clip is playing.
     *
     * This is the *commit*: the waveform updates [positionMs] itself while the finger is down and
     * only lands here on release, so a fast drag costs one `seekTo` instead of a dozen queued
     * ones. Tapping the bars moves the head and nothing else — it never starts or stops playback.
     */
    fun seek(targetMs: Int) {
        val bounded = targetMs.coerceAtLeast(0).let { if (totalMs > 0) it.coerceAtMost(totalMs) else it }
        positionMs = bounded
        pendingStartAt = bounded
        val current = player ?: return
        if (prepared) runCatching { current.seekTo(bounded) } else pendingSeek = bounded
    }

    /** The millisecond a touch at [x] points at, clamped to the clip. */
    fun positionAt(x: Float, width: Float): Int {
        if (totalMs <= 0 || width <= 0f) return 0
        return ((x / width).coerceIn(0f, 1f) * totalMs).toInt()
    }

    /*
     * Starts from `from`, flipping the icon immediately.
     *
     * The state is optimistic and rolled back if the player fails: a tap on a slow link used to
     * look ignored until the first buffer arrived, and people tapped again. When the audio is
     * still being fetched the request is simply remembered — the effect below starts it the
     * moment the file lands.
     */
    fun start(from: Int = positionMs) {
        val current = player
        val path = filePath
        if (current == null || path == null) {
            if (loadError != null) {
                NcToaster.show("Couldn't load this voice message — $loadError")
                return
            }
            if (mediaUrl.isNullOrBlank()) {
                // No file behind this bubble: say so instead of pretending to play silence.
                NcToaster.show("This voice message has no audio attached")
                return
            }
            playWhenReady = true
            playing = true
            positionMs = from
            pendingStartAt = from
            return
        }
        VoicePlayback.current = messageId
        playWhenReady = true
        playing = true
        positionMs = from
        if (prepared) {
            val started = runCatching {
                val end = runCatching { current.duration }.getOrDefault(0)
                // Finished a moment ago? Start over instead of playing the last 200 ms.
                current.seekTo(if (end > 0 && from >= end - 250) 0 else from)
                current.start()
            }
            if (started.isFailure) failPlayback("Couldn't play this voice message")
            return
        }
        if (preparing) {
            pendingSeek = from
            return
        }
        pendingSeek = from
        preparing = true
        val started = runCatching {
            current.setDataSource(path)
            current.setOnPreparedListener { ready ->
                prepared = true
                preparing = false
                runCatching { ready.duration }.getOrNull()?.let { if (it > 0) realMs = it }
                pendingSeek?.let { target -> runCatching { ready.seekTo(target) } }
                pendingSeek = null
                if (playWhenReady) {
                    runCatching { ready.start() }
                    playing = true
                } else {
                    playing = false
                }
            }
            current.setOnCompletionListener {
                playing = false
                playWhenReady = false
                positionMs = 0
                if (VoicePlayback.current == messageId) VoicePlayback.current = null
            }
            current.setOnErrorListener { failed, what, extra ->
                runCatching { failed.reset() }
                val unsupported = what == MediaPlayer.MEDIA_ERROR_UNSUPPORTED ||
                    extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED
                failPlayback(
                    if (unsupported) {
                        "This phone can't decode that voice message"
                    } else {
                        "Couldn't play this voice message"
                    }
                )
                true
            }
            current.prepareAsync()
        }
        if (started.isFailure) {
            failPlayback("Couldn't play this voice message")
        }
    }

    fun pause() {
        playWhenReady = false
        runCatching { player?.pause() }
        playing = false
        if (VoicePlayback.current == messageId) VoicePlayback.current = null
    }

    fun toggle() {
        if (playing) pause() else start()
    }

    // The file arrived while the user was waiting on it: honour the tap that asked for it.
    LaunchedEffect(filePath) {
        if (filePath != null && playWhenReady && !prepared && !preparing) {
            val from = pendingStartAt ?: positionMs
            pendingStartAt = null
            start(from)
        }
    }

    val accent = if (mine) Color.White else MaterialTheme.colorScheme.primary
    /*
     * Width grows with the clip, like the reference bubble (`width = min(284, 176 + seconds*2.6)`):
     * a two-second "ok" is a stub, a two-minute note is a bar big enough to scrub. Capped a little
     * under the reference because the bubble's own padding sits inside a 300 dp container.
     */
    val widthSeconds = (labelledMs / 1000f).coerceAtLeast(1f)
    val voiceWidth = (176f + widthSeconds * 2.6f).coerceIn(176f, 264f).dp
    Row(
        modifier = Modifier
            .width(voiceWidth)
            .clip(RoundedCornerShape(12.dp))
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        /*
         * The play/pause button, and the *only* thing that starts or stops this clip.
         *
         * The row used to carry `clickable { toggle() }` while the waveform sat inside it, so a
         * drag across the bars to seek also landed as a click on the row and stopped the audio —
         * the report was "seeking pauses it". The tap target is this circle now, sized up to the
         * 40 dp a thumb expects, and the waveform never toggles anything.
         */
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (mine) Color(0x33FFFFFF) else MaterialTheme.colorScheme.primary)
                .clickable { toggle() },
            contentAlignment = Alignment.Center
        ) {
            when {
                loading -> CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
                playing -> Icon(
                    imageVector = Icons.Rounded.Pause,
                    contentDescription = "Pause voice message",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                else -> Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play voice message",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        /*
         * The waveform is a scrubber, and **only** a scrubber.
         *
         * Touch down and the head jumps to the finger; drag and it follows, the bars tinting behind
         * it. The down is consumed immediately, so neither the bubble's own click/long-press nor
         * anything else can read the same gesture: seeking must never start, stop or pause the
         * clip — that is the button's job, and the two used to overlap.
         *
         * The player is seeked once, on release (a fast drag would otherwise queue a dozen
         * `seekTo` calls and jitter). Holding still for half a second opens the message menu,
         * which is what a long press anywhere on a bubble does.
         */
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(24.dp)
                .pointerInput(messageId, filePath, totalMs) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        down.consume()
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val slop = viewConfiguration.touchSlop
                        var target = positionAt(down.position.x, width)
                        var dragging = false
                        val startedAt = System.currentTimeMillis()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // Claim the release too: the bubble's own tap/long-press detector
                                // cancels on a consumed change, so seeking can never open the menu.
                                change.consume()
                                break
                            }
                            if (!dragging) {
                                val dx = abs(change.position.x - down.position.x)
                                val dy = abs(change.position.y - down.position.y)
                                /*
                                 * A vertical drag belongs to the conversation list, not the bar:
                                 * let it go, unconsumed, so a chat scrolls when the finger starts
                                 * on a waveform. Only a clearly horizontal move (or a tap) is ours.
                                 */
                                if (dy > slop && dy > dx) return@awaitEachGesture
                                if (dx > slop) {
                                    dragging = true
                                    scrubbing = true
                                }
                            }
                            if (dragging) {
                                target = positionAt(change.position.x, width)
                                // The head follows the finger; the sampler stands down meanwhile.
                                positionMs = target
                                change.consume()
                            }
                        }
                        val held = System.currentTimeMillis() - startedAt
                        scrubbing = false
                        when {
                            // A long press that never moved is a menu, not a seek.
                            !dragging && held >= 450L -> onLongPress()
                            else -> seek(target)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The head: a thin bar where playback will resume, visible even when paused —
                    // the tint alone disappears at position 0 and reads as "no progress".
                    .drawBehind {
                        if (progress > 0f) {
                            val x = (size.width * progress).coerceIn(0f, size.width)
                            drawLine(
                                color = accent,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                levels.forEachIndexed { index, level ->
                    val barHeight = (4f + 12f * level.coerceIn(0f, 1f)).dp
                    // The played/unplayed split follows the real position the player reports.
                    val played = (index + 1).toFloat() / levels.size <= progress + 0.001f
                    Box(
                        modifier = Modifier
                            // Shares the bar's width, so a longer clip draws wider bars instead
                            // of the same 96 dp cluster in a wider box.
                            .weight(1f)
                            .height(barHeight)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                if (played) {
                                    accent
                                } else {
                                    accent.copy(alpha = 0.4f)
                                }
                            )
                    )
                }
            }
        }
        /*
         * Elapsed once the playhead has moved, total at rest — the same thing the web bubble
         * counts. Keying this on `playing` alone snapped back to the total the moment you
         * paused, losing the position you had just scrubbed to.
         */
        Text(
            text = if (totalMs > 0 && positionMs > 0) {
                "%d:%02d".format(Locale.US, (positionMs / 60_000), (positionMs / 1_000) % 60)
            } else {
                duration
            },
            fontSize = 10.sp,
            color = if (mine) Color(0xCCFFFFFF) else NcTheme.extra.textMuted
        )
    }
}


private fun parseVoiceDuration(label: String): Int {
    val parts = label.split(":")
    val seconds = if (parts.size == 2) {
        (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
    } else {
        parts.lastOrNull()?.toIntOrNull() ?: 1
    }
    return seconds.coerceAtLeast(1) * 1000
}

@Composable
private fun DocumentContent(name: String, mine: Boolean) {
    Row(
        modifier = Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (mine) Color(0x26FFFFFF) else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            tint = if (mine) Color.White else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (mine) Color.White else NcTheme.extra.bubbleIncomingText,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun LocationContent(mine: Boolean) {
    Row(
        modifier = Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (mine) Color(0x26FFFFFF) else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.LocationOn,
            contentDescription = null,
            tint = if (mine) Color.White else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = "Shared live location",
            fontSize = 12.sp,
            color = if (mine) Color.White else NcTheme.extra.bubbleIncomingText,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
