package com.nchat.app.feature.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcGlassEmptyState
import com.nchat.app.core.designsystem.NcGlassRow
import com.nchat.app.core.designsystem.NcSize
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
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
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: Chat, onClick: () -> Unit) {
    val extra = NcTheme.extra
    val hasUnread = chat.unreadCount > 0

    NcGlassRow(
        onClick = onClick,
        modifier = Modifier.padding(bottom = NcSpace.sm)
    ) {
        NcAvatar(
            name = chat.title,
            emoji = chat.avatarEmoji,
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
