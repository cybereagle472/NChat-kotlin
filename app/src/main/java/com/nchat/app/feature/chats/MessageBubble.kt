@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nchat.app.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.AttachmentKind
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.Reaction
import com.nchat.app.ui.components.MessageTicks

/** Centered pill that separates messages by day. */
@Composable
fun DaySeparator(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = NcTheme.extra.mutedText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun MessageBubble(
    message: Message,
    showAuthor: Boolean,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.systemEvent) {
        DaySeparator(label = message.text, modifier = modifier)
        return
    }

    val extra = NcTheme.extra
    val bubbleColor = if (message.isMine) extra.outgoingBubble else extra.incomingBubble
    val textColor = if (message.isMine) extra.outgoingText else extra.incomingText
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomEnd = if (message.isMine) 5.dp else 18.dp,
        bottomStart = if (message.isMine) 18.dp else 5.dp
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = shape,
            color = bubbleColor,
            tonalElevation = if (message.isMine) 0.dp else 1.dp,
            modifier = Modifier
                .widthIn(max = 296.dp)
                .combinedClickable(onClick = onLongPress, onLongClick = onLongPress)
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                if (showAuthor && !message.isMine) {
                    Text(
                        text = message.authorName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                message.replyAuthor?.let { author ->
                    ReplyQuote(author = author, text = message.replyText.orEmpty())
                    Spacer(modifier = Modifier.height(4.dp))
                }

                AttachmentContent(message = message, textColor = textColor)

                if (message.attachment == AttachmentKind.Photo) {
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (message.text.isNotBlank() && message.attachment != AttachmentKind.Photo) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = TimeFormat.clock(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = extra.bubbleTime
                    )
                    if (message.isMine) {
                        Spacer(modifier = Modifier.width(3.dp))
                        MessageTicks(status = message.status, size = 14.dp)
                    }
                }
            }
        }

        if (message.reactions.isNotEmpty()) {
            ReactionRow(
                reactions = message.reactions,
                isMine = message.isMine,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun ReplyQuote(author: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f))
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = author,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = NcTheme.extra.mutedText,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ReactionRow(
    reactions: List<Reaction>,
    isMine: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        reactions.forEach { reaction ->
            Surface(
                shape = RoundedCornerShape(50),
                color = if (reaction.reactedByMe) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = reaction.emoji, fontSize = 11.sp)
                    if (reaction.count > 1) {
                        Text(
                            text = reaction.count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (reaction.reactedByMe) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                NcTheme.extra.mutedText
                            },
                            modifier = Modifier.padding(start = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentContent(message: Message, textColor: Color) {
    when (message.attachment) {
        AttachmentKind.Photo -> PhotoAttachment(label = message.attachmentLabel)
        AttachmentKind.Voice -> VoiceAttachment(
            duration = message.durationLabel ?: "00:00",
            tint = MaterialTheme.colorScheme.primary
        )
        AttachmentKind.Document -> DocumentAttachment(
            label = message.attachmentLabel ?: "document.pdf",
            textColor = textColor
        )
        AttachmentKind.Location -> LocationAttachment(textColor = textColor)
        AttachmentKind.Contact -> ContactAttachment(textColor = textColor)
        AttachmentKind.Video, AttachmentKind.Sticker, AttachmentKind.None -> Unit
    }
}

@Composable
private fun PhotoAttachment(label: String?) {
    Box(
        modifier = Modifier
            .width(212.dp)
            .height(148.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF35786A), Color(0xFF7FBFAA))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Image,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(34.dp)
        )
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun VoiceAttachment(duration: String, tint: Color) {
    Row(
        modifier = Modifier
            .width(212.dp)
            .clip(RoundedCornerShape(10.dp))
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(tint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play voice message",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            repeat(26) { index ->
                val barHeight = (4 + (index * 5) % 13).dp
                Box(
                    modifier = Modifier
                        .width(2.5.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint.copy(alpha = if (index < 12) 0.95f else 0.4f))
                )
            }
        }
        Text(
            text = duration,
            style = MaterialTheme.typography.labelSmall,
            color = NcTheme.extra.bubbleTime
        )
    }
}

@Composable
private fun DocumentAttachment(label: String, textColor: Color) {
    Row(
        modifier = Modifier
            .width(212.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp)
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 1
            )
            Text(
                text = "PDF document",
                style = MaterialTheme.typography.labelSmall,
                color = NcTheme.extra.mutedText
            )
        }
    }
}

@Composable
private fun LocationAttachment(textColor: Color) {
    Row(
        modifier = Modifier
            .width(212.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = "Shared live location",
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun ContactAttachment(textColor: Color) {
    Row(
        modifier = Modifier
            .width(212.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = "Contact card",
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
