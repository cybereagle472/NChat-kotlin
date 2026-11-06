@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nchat.app.feature.chats

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.nchat.app.core.audio.VoiceWaveform
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.core.designsystem.BubbleShapeMine
import com.nchat.app.core.designsystem.BubbleShapeTheirs
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import com.nchat.app.data.model.Reaction
import kotlinx.coroutines.delay

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
            message.replyAuthor?.let { author ->
                ReplyQuote(
                    author = author,
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
                    duration = message.durationLabel ?: "0:00",
                    mine = mine,
                    mediaUrl = message.mediaUrl,
                    storedWaveform = message.thumbnailUrl
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
                maxLines = 1
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
        // The uploaded file itself; the gradient below is only the loading/fallback state.
        AsyncImage(
            model = mediaUrl,
            contentDescription = "Photo",
            contentScale = ContentScale.Crop,
            modifier = frame.background(Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF))))
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
    duration: String,
    mine: Boolean,
    mediaUrl: String? = null,
    /**
     * `thumbnail_url` when the sender recorded a waveform — a JSON array of normalised levels.
     *
     * Both clients write it (the reference's `downsampleWaveform`, this app's `VoiceWaveform`),
     * so a voice note keeps the shape of what was actually said wherever it is read.
     */
    storedWaveform: String? = null
) {
    /*
     * Keyed on the URL, not on nothing: a clip that was still being uploaded swaps its
     * `file://` path for the public URL, `remember(mediaUrl)` builds a fresh player for it, and
     * a `prepared = true` left over from the old one would have `start()` called on a player
     * that never had a data source set.
     */
    var playing by remember(mediaUrl) { mutableStateOf(false) }
    var progress by remember(mediaUrl) { mutableStateOf(0f) }
    var prepared by remember(mediaUrl) { mutableStateOf(false) }
    /** `prepareAsync()` is in flight — a second tap must not call `setDataSource` again. */
    var preparing by remember(mediaUrl) { mutableStateOf(false) }
    val totalMs = remember(duration) { parseVoiceDuration(duration) }
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

    // A player per bubble, created only when the message actually carries audio. Media is
    // served publicly by the worker (with byte ranges), so the URL can be set directly.
    val player = remember(mediaUrl) {
        mediaUrl?.takeIf { it.isNotBlank() }?.let {
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
            if (mediaUrl != null && VoicePlayback.current == mediaUrl) VoicePlayback.current = null
            runCatching { player?.release() }
        }
    }

    // Exclusive playback: another bubble started, so this one stops where it is.
    val playingUrl = VoicePlayback.current
    LaunchedEffect(playingUrl) {
        if (playing && playingUrl != mediaUrl) {
            runCatching { player?.pause() }
            playing = false
        }
    }

    // Real position while the player runs; the old timer is kept only for messages that
    // predate the upload and have no file to play.
    LaunchedEffect(playing, player) {
        if (!playing) return@LaunchedEffect
        if (player != null) {
            while (true) {
                delay(80)
                val total = runCatching { player.duration }.getOrDefault(0)
                val current = runCatching { player.currentPosition }.getOrDefault(0)
                progress = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f
                if (!playing) break
            }
        } else {
            if (totalMs <= 0) {
                playing = false
                return@LaunchedEffect
            }
            while (true) {
                delay(100)
                val next = progress + 100f / totalMs
                if (next >= 1f) {
                    progress = 0f
                    playing = false
                    break
                }
                progress = next
            }
        }
    }

    fun toggle() {
        val current = player
        if (current == null) {
            // No file behind this bubble: say so instead of pretending to play silence.
            NcToaster.show("This voice message has no audio attached")
            return
        }
        if (playing) {
            runCatching { current.pause() }
            playing = false
            if (VoicePlayback.current == mediaUrl) VoicePlayback.current = null
            return
        }
        // Tapped twice while the first `prepareAsync()` is still buffering: ignore, otherwise
        // `setDataSource` runs on a player that is already preparing and throws.
        if (preparing) return
        val started = runCatching {
            if (!prepared) {
                current.setDataSource(mediaUrl)
                current.setOnPreparedListener {
                    it.start()
                    prepared = true
                    playing = true
                    preparing = false
                    VoicePlayback.current = mediaUrl
                }
                current.setOnCompletionListener {
                    progress = 0f
                    playing = false
                    if (VoicePlayback.current == mediaUrl) VoicePlayback.current = null
                }
                current.setOnErrorListener { _, _, _ ->
                    playing = false
                    preparing = false
                    progress = 0f
                    if (VoicePlayback.current == mediaUrl) VoicePlayback.current = null
                    NcToaster.show("Couldn't play this voice message")
                    true
                }
                preparing = true
                current.prepareAsync()
            } else {
                if (current.currentPosition >= current.duration) current.seekTo(0)
                VoicePlayback.current = mediaUrl
                current.start()
                playing = true
            }
        }
        if (started.isFailure) {
            playing = false
            preparing = false
            NcToaster.show("Couldn't play this voice message")
        }
    }

    val accent = if (mine) Color.White else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .width(190.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { toggle() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (mine) Color(0x33FFFFFF) else MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause voice message" else "Play voice message",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                levels.forEachIndexed { index, level ->
                    val barHeight = (4f + 12f * level.coerceIn(0f, 1f)).dp
                    // The played/unplayed split follows the real position the player reports.
                    val played = (index + 1).toFloat() / levels.size <= progress + 0.001f
                    Box(
                        modifier = Modifier
                            .width(2.dp)
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
        // Total length at rest, elapsed while playing — the same thing the web bubble counts.
        Text(
            text = if (playing && totalMs > 0) {
                val elapsed = (progress * totalMs).toInt().coerceIn(0, totalMs)
                "%d:%02d".format(Locale.US, elapsed / 60_000, (elapsed / 1_000) % 60)
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
