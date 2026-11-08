package com.nchat.app.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.local.StarredMessage
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind

/**
 * In-chat search — the magnifier on WhatsApp's chat header.
 *
 * Results are computed from the messages the conversation already holds, so typing is instant
 * and works offline; each hit shows its author, its time and the matching line, with the query
 * highlighted. Tapping one closes the overlay and the caller scrolls the timeline to that bubble
 * (which is briefly ringed so the eye finds it among identical bubbles).
 */
@Composable
fun ChatSearchOverlay(
    messages: List<Message>,
    onDismiss: () -> Unit,
    onPick: (Message) -> Unit
) {
    val extra = NcTheme.extra
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val results = remember(query, messages) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            emptyList()
        } else {
            messages.asReversed().filter { message -> message.matches(needle) }.take(200)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF1F5F9))
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = NcSpace.sm, vertical = NcSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Close search",
                        tint = extra.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF1F5F9))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = extra.textTertiary,
                        modifier = Modifier.size(17.dp)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search this chat",
                                color = extra.textTertiary,
                                fontSize = 14.sp
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = extra.textPrimary,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear",
                            tint = extra.textTertiary,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable { query = "" }
                        )
                    }
                }
            }

            when {
                query.isBlank() -> SearchHint("Type to search messages, files and names")
                results.isEmpty() -> SearchHint("No messages match \"${query.trim()}\"")
                else -> {
                    Text(
                        text = if (results.size == 1) "1 message" else "${results.size} messages",
                        color = extra.textTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = NcSpace.lg, vertical = 6.dp)
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = results, key = { it.id }) { message ->
                            SearchResultRow(
                                message = message,
                                query = query.trim(),
                                onClick = {
                                    keyboard?.hide()
                                    onPick(message)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(NcSpace.xxl),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = NcTheme.extra.textTertiary,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun SearchResultRow(message: Message, query: String, onClick: () -> Unit) {
    val extra = NcTheme.extra
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(Color.White)
            .padding(horizontal = NcSpace.lg, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(NcSpace.md),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFFEDE9FE)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "💬", fontSize = 15.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.authorName,
                    color = extra.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = "  ·  ${TimeFormat.clock(message.timestamp)}",
                    color = extra.textTertiary,
                    fontSize = 11.sp
                )
            }
            Text(
                text = message.highlighted(query),
                color = extra.textSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** The line a result shows: the text, or what kind of attachment it is. */
private fun Message.matches(needle: String): Boolean {
    if (deleted) return false
    val haystack = buildString {
        append(text)
        append(' ')
        append(authorName)
        append(' ')
        append(fileName.orEmpty())
        append(' ')
        append(kindLabel())
    }
    return haystack.contains(needle, ignoreCase = true)
}

private fun Message.kindLabel(): String = when (kind) {
    MessageKind.Text -> ""
    MessageKind.Photo -> "photo"
    MessageKind.Video -> "video"
    MessageKind.Voice -> "voice message audio"
    MessageKind.Document -> "document file"
    MessageKind.Location -> "location map"
}

private fun Message.searchBody(): String = when {
    text.isNotBlank() -> text
    kind == MessageKind.Voice -> "Voice message · ${durationLabel ?: ""}"
    kind == MessageKind.Photo -> "Photo"
    kind == MessageKind.Video -> "Video"
    kind == MessageKind.Document -> fileName ?: "Document"
    kind == MessageKind.Location -> "Location"
    else -> ""
}

/** The body with every occurrence of [query] in brand purple, like the reference's search rows. */
private fun Message.highlighted(query: String): AnnotatedString {
    val body = searchBody()
    if (query.isBlank()) return AnnotatedString(body)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor <= body.length) {
            val hit = body.indexOf(query, cursor, ignoreCase = true)
            if (hit < 0) {
                append(body.substring(cursor))
                break
            }
            append(body.substring(cursor, hit))
            withStyle(SpanStyle(color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold)) {
                append(body.substring(hit, hit + query.length))
            }
            cursor = hit + query.length
        }
    }
}

/**
 * "Starred messages" for one conversation.
 *
 * The body is the copy taken when the message was starred, so a starred message survives the
 * sender deleting it and survives being scrolled out of the loaded page — WhatsApp keeps the text
 * of a starred message it has since lost for exactly the same reason.
 */
@Composable
fun StarredMessagesSheet(
    items: List<StarredMessage>,
    onDismiss: () -> Unit,
    onPick: (StarredMessage) -> Unit,
    onUnstar: (StarredMessage) -> Unit
) {
    val extra = NcTheme.extra
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
                    .heightIn(max = 520.dp)
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
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Starred messages",
                        color = extra.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                    Text(
                        text = if (items.size == 1) "1" else "${items.size}",
                        color = extra.textTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (items.isEmpty()) {
                    Text(
                        text = "Tap and hold a message, then choose Star to keep it here.",
                        color = extra.textTertiary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = NcSpace.lg, vertical = NcSpace.xl)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(items = items, key = { it.chatId + it.messageId }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(item) }
                                    .padding(horizontal = NcSpace.lg, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.authorName,
                                        color = extra.textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.preview,
                                        color = extra.textSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.StarOutline,
                                    contentDescription = "Unstar",
                                    tint = extra.textTertiary,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .clickable { onUnstar(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Small ribbon under the header: what the ⋮ button offers inside a conversation. */
@Composable
fun ChatOptionsSheet(
    onDismiss: () -> Unit,
    onStarred: () -> Unit,
    onMedia: () -> Unit = {}
) {
    val extra = NcTheme.extra
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.TopEnd
        ) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 54.dp, end = NcSpace.md)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .clickable(enabled = false, onClick = {})
                    .padding(vertical = 4.dp)
            ) {
                OptionRow("Media, links and docs", Icons.Rounded.PhotoLibrary) { onMedia() }
                OptionRow("Starred messages", Icons.Rounded.Star) { onStarred() }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NcTheme.extra.textTertiary,
            modifier = Modifier.size(16.dp)
        )
        Text(text = label, color = NcTheme.extra.textPrimary, fontSize = 14.sp)
    }
}
