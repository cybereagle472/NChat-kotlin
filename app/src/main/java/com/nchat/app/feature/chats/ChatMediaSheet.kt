package com.nchat.app.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind

/**
 * WhatsApp's "Media, links and docs" for one conversation.
 *
 * Everything here is derived from the messages the chat already holds, so the sheet opens
 * instantly and works offline. The tabs answer three different questions: *what pictures and clips
 * did we share*, *which files*, and *what was linked* — a link is anything the text of a message
 * contains that starts with `http`.
 *
 * Tapping a photo hands the message back to the caller, which opens the full-screen pager on the
 * chat's media — so the viewer you land in is the one you already know, complete with swiping and
 * zoom.
 */
@Composable
fun ChatMediaSheet(
    messages: List<Message>,
    onDismiss: () -> Unit,
    onOpenMedia: (Message) -> Unit
) {
    val extra = NcTheme.extra
    var tab by remember { mutableStateOf(MediaTab.Media) }

    val media = remember(messages) {
        messages.filter { !it.deleted && (it.kind == MessageKind.Photo || it.kind == MessageKind.Video) }
            .asReversed()
    }
    val documents = remember(messages) {
        messages.filter { !it.deleted && it.kind == MessageKind.Document }.asReversed()
    }
    val links = remember(messages) {
        messages.filterNot { it.deleted }
            .flatMap { message -> linkOf(message).map { message to it } }
            .asReversed()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp, max = 620.dp)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(Color.White)
                    .clickable(enabled = false, onClick = {})
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NcSpace.lg, vertical = NcSpace.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Media, links and docs",
                        color = extra.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Close",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NcSpace.md, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
                ) {
                    MediaTab.values().forEach { entry ->
                        val selected = entry == tab
                        val count = when (entry) {
                            MediaTab.Media -> media.size
                            MediaTab.Docs -> documents.size
                            MediaTab.Links -> links.size
                        }
                        Text(
                            text = "${entry.label} $count",
                            color = if (selected) Color.White else extra.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (selected) {
                                        Brush.linearGradient(NcTheme.extra.brandGradient)
                                    } else {
                                        Brush.linearGradient(listOf(Color(0xFFF1F5F9), Color(0xFFF1F5F9)))
                                    }
                                )
                                .clickable { tab = entry }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                when {
                    tab == MediaTab.Media && media.isEmpty() -> EmptyMedia("No photos or videos yet")
                    tab == MediaTab.Docs && documents.isEmpty() -> EmptyMedia("No documents shared yet")
                    tab == MediaTab.Links && links.isEmpty() -> EmptyMedia("No links in this chat")

                    tab == MediaTab.Media -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(items = media, key = { it.id }) { message ->
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFE2E8F0))
                                    .clickable { onOpenMedia(message) },
                                contentAlignment = Alignment.Center
                            ) {
                                val source = message.thumbnailUrl ?: message.mediaUrl
                                if (source != null) {
                                    AsyncImage(
                                        model = source,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Image,
                                        contentDescription = null,
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                if (message.kind == MessageKind.Video) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(RoundedCornerShape(13.dp))
                                            .background(Color.Black.copy(alpha = 0.45f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    tab == MediaTab.Docs -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = documents, key = { it.id }) { message ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = NcSpace.lg, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(Color(0xFFDBEAFE)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Description,
                                        contentDescription = null,
                                        tint = Color(0xFF2563EB),
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = message.fileName ?: "Document",
                                        color = extra.textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${message.authorName} · ${TimeFormat.clock(message.timestamp)}",
                                        color = extra.textTertiary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = links, key = { "${it.first.id}-${it.second}" }) { row ->
                            val message = row.first
                            val url = row.second
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = NcSpace.lg, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(Color(0xFFD1FAE5)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Link,
                                        contentDescription = null,
                                        tint = Color(0xFF059669),
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = url,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${message.authorName} · ${TimeFormat.clock(message.timestamp)}",
                                        color = extra.textTertiary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMedia(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(NcSpace.xxl),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = NcTheme.extra.textTertiary, fontSize = 13.sp)
    }
}

private enum class MediaTab(val label: String) {
    Media("Media"),
    Docs("Docs"),
    Links("Links")
}

/** The first `http(s)` address in a message's text, if it has one. */
private fun linkOf(message: Message): List<String> {
    if (message.kind != MessageKind.Text || message.text.isBlank()) return emptyList()
    return LINK_REGEX.findAll(message.text)
        .map { it.value.trimEnd('.', ',', ')', ']', '"', '\'') }
        .distinct()
        .toList()
}

private val LINK_REGEX = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
