@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nchat.app.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.BubbleShapeMine
import com.nchat.app.core.designsystem.BubbleShapeTheirs
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import com.nchat.app.data.model.Reaction
import kotlinx.coroutines.delay

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
    showAuthor: Boolean = false
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
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (showAuthor && !mine) {
            Text(
                text = message.authorName,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
            )
        }
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .shadow(if (mine) 0.dp else 1.dp, if (mine) BubbleShapeMine else BubbleShapeTheirs)
                .clip(if (mine) BubbleShapeMine else BubbleShapeTheirs)
                .background(
                    if (mine) {
                        Brush.linearGradient(extra.bubbleMineGradient)
                    } else {
                        Brush.linearGradient(listOf(extra.bubbleIncoming, extra.bubbleIncoming))
                    }
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
                MessageKind.Photo -> PhotoContent()
                MessageKind.Voice -> VoiceContent(
                    duration = message.durationLabel ?: "00:00",
                    mine = mine
                )
                MessageKind.Document -> DocumentContent(
                    name = message.fileName ?: "document.pdf",
                    mine = mine
                )
                MessageKind.Location -> LocationContent(mine = mine)
                MessageKind.Video -> VideoContent(mine = mine)
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
                if (mine) {
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

/** "Delete for everyone" tombstone: muted bubble, italic label, no actions. */
@Composable
private fun DeletedBubble(
    message: Message,
    mine: Boolean,
    showAuthor: Boolean,
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (showAuthor && !mine) {
            Text(
                text = message.authorName,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
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
private fun PhotoContent() {
    Box(
        modifier = Modifier
            .width(210.dp)
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)))),
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
private fun VideoContent(mine: Boolean) {
    Box(
        modifier = Modifier
            .width(210.dp)
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B)),
        contentAlignment = Alignment.Center
    ) {
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

@Composable
private fun VoiceContent(duration: String, mine: Boolean) {
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    val totalMs = remember(duration) { parseVoiceDuration(duration) }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
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

    val accent = if (mine) Color.White else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .width(190.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { playing = !playing }
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
                repeat(22) { index ->
                    val barHeight = (4 + (index * 5) % 12).dp
                    val played = index / 22f <= progress
                    Box(
                        modifier = Modifier
                            .width(2.5.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(2.dp))
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
        Text(
            text = duration,
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
