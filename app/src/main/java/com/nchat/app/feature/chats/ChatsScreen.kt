package com.nchat.app.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcGlassEmptyState
import com.nchat.app.core.designsystem.NcGlassRow
import com.nchat.app.core.designsystem.NcSize
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.core.designsystem.NcTypingDots
import com.nchat.app.core.designsystem.NcUnreadBadge
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.MessageKind
import com.nchat.app.ui.components.NcHeaderAction
import com.nchat.app.ui.components.NcTabHeader

@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    listState: LazyListState,
    onOpenChat: (Chat) -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Chat?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        NcTabHeader(
            title = "Chats",
            searchValue = state.query,
            onSearchChange = viewModel::onQueryChange,
            actions = {
                NcHeaderAction(Icons.Rounded.Add, "Create new chat", onClick = onNewChat)
                NcHeaderAction(Icons.Rounded.Settings, "Settings", onClick = onOpenSettings)
            }
        )

        state.error?.let { message ->
            SyncErrorBanner(message = message, onRetry = viewModel::retry)
        }

        if (state.isEmpty) {
            NcGlassEmptyState(
                emoji = if (state.query.isNotEmpty()) "🔍" else "💬",
                title = if (state.query.isNotEmpty()) "No chats found" else "No chats yet",
                message = if (state.query.isNotEmpty()) {
                    "Try a different search"
                } else {
                    "Start a conversation with your friends"
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NcSpace.md)
            ) {
                items(items = state.chats, key = { it.id }) { chat ->
                    ChatRow(
                        chat = chat,
                        onClick = {
                            viewModel.markRead(chat.id)
                            onOpenChat(chat)
                        },
                        onTogglePin = {
                            viewModel.togglePin(chat.id, !chat.pinned)
                            NcToaster.show(if (chat.pinned) "Chat unpinned" else "Chat pinned")
                        },
                        onToggleMute = {
                            viewModel.toggleMute(chat.id, !chat.muted)
                            NcToaster.show(
                                if (chat.muted) "Notifications on" else "Notifications muted"
                            )
                        },
                        onMarkRead = {
                            viewModel.markRead(chat.id)
                            NcToaster.show("Marked as read")
                        },
                        onDelete = { pendingDelete = chat }
                    )
                }
            }
        }
    }

    pendingDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete chat?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "This removes the conversation with ${doomed.title} from this device.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChat(doomed.id)
                        pendingDelete = null
                        NcToaster.show("Chat deleted")
                    }
                ) {
                    Text("Delete", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = NcTheme.extra.textTertiary)
                }
            }
        )
    }
}

@Composable
private fun ChatRow(
    chat: Chat,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit
) {
    val extra = NcTheme.extra
    val hasUnread = chat.unreadCount > 0
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
    NcGlassRow(
        onClick = onClick,
        onLongClick = { menuOpen = true },
        modifier = Modifier.padding(bottom = NcSpace.sm)
    ) {
        NcAvatar(
            name = chat.title,
            emoji = chat.avatarEmoji,
            imageUrl = chat.avatarUrl,
            size = NcSize.rowAvatar,
            isOnline = chat.isOnline,
            showPresence = !chat.isGroup
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasUnread) extra.textPrimary else extra.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.weight(1f))
                if (chat.pinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(13.dp)
                    )
                }
                if (chat.muted) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsOff,
                        contentDescription = "Muted",
                        tint = extra.textMuted,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(13.dp)
                    )
                }
                Text(
                    text = TimeFormat.listTimestamp(chat.lastMessageAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasUnread) MaterialTheme.colorScheme.primary else extra.textMuted,
                    fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.padding(start = NcSpace.sm)
                )
            }

            Row(
                modifier = Modifier.padding(top = NcSpace.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    chat.isRecording -> TypingLine(
                        label = "recording audio",
                        iconColor = extra.online,
                        showMic = true
                    )

                    chat.isTyping -> TypingLine(label = "typing", iconColor = extra.typing)

                    else -> PreviewLine(chat = chat, hasUnread = hasUnread)
                }

                if (hasUnread) {
                    Spacer(modifier = Modifier.weight(1f))
                    NcUnreadBadge(count = chat.unreadCount)
                }
            }
        }
    }

    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text(if (chat.pinned) "Unpin chat" else "Pin chat", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.PushPin,
                    contentDescription = null,
                    tint = extra.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                menuOpen = false
                onTogglePin()
            }
        )
        DropdownMenuItem(
            text = {
                Text(
                    if (chat.muted) "Unmute notifications" else "Mute notifications",
                    fontSize = 14.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.NotificationsOff,
                    contentDescription = null,
                    tint = extra.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                menuOpen = false
                onToggleMute()
            }
        )
        if (hasUnread) {
            DropdownMenuItem(
                text = { Text("Mark as read", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.DoneAll,
                        contentDescription = null,
                        tint = extra.textTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuOpen = false
                    onMarkRead()
                }
            )
        }
        DropdownMenuItem(
            text = { Text("Delete chat", fontSize = 14.sp, color = Color(0xFFDC2626)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = Color(0xFFDC2626),
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                menuOpen = false
                onDelete()
            }
        )
    }
    }
}

@Composable
private fun RowScope.TypingLine(
    label: String,
    iconColor: Color,
    showMic: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.weight(1f, fill = false)
    ) {
        if (showMic) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = iconColor,
            maxLines = 1
        )
        NcTypingDots(color = iconColor)
    }
}

@Composable
private fun RowScope.PreviewLine(chat: Chat, hasUnread: Boolean) {
    val extra = NcTheme.extra

    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (chat.lastMessageMine) {
            DeliveryTick(state = chat.lastMessageState)
        }
        KindIcon(kind = chat.lastMessageKind)
        Text(
            text = chat.lastMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasUnread) extra.textSecondary else extra.textTertiary,
            fontWeight = if (hasUnread) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
private fun DeliveryTick(state: DeliveryState?) {
    val isRead = state == DeliveryState.Read
    Icon(
        imageVector = if (isRead) Icons.Rounded.DoneAll else Icons.Rounded.Check,
        contentDescription = null,
        tint = if (isRead) NcTheme.extra.readTick else NcTheme.extra.textMuted,
        modifier = Modifier.size(14.dp)
    )
}

@Composable
private fun KindIcon(kind: MessageKind) {
    val icon = when (kind) {
        MessageKind.Photo -> Icons.Rounded.Image
        MessageKind.Video -> Icons.Rounded.Videocam
        MessageKind.Voice -> Icons.Rounded.Mic
        MessageKind.Document -> Icons.Rounded.Description
        MessageKind.Location -> Icons.Rounded.LocationOn
        MessageKind.Text -> null
    }
    if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NcTheme.extra.textMuted,
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * One line above the list when the last refresh failed.
 *
 * Without it a 401, a timeout and a genuinely empty account all look the same: an empty chat
 * list. Tapping Retry re-runs the same load that built the screen.
 */
@Composable
private fun SyncErrorBanner(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NcSpace.md, vertical = NcSpace.xs)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFFF1F2))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            color = Color(0xFFB91C1C),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) {
            Text(text = "Retry", color = Color(0xFFB91C1C), fontSize = 11.sp)
        }
    }
}
