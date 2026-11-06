package com.nchat.app.feature.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MarkChatRead
import androidx.compose.material.icons.rounded.MarkChatUnread
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
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
import com.nchat.app.data.ServiceLocator
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
    /*
     * While the phone has no network the failed request is not news, and the red banner that
     * reports it sits on top of a perfectly usable list of saved chats. The home screen's
     * "Waiting for network" pill already says the one thing worth saying, so the banner is
     * reserved for answers the server actually gave (a 500, a 401, a bad response).
     */
    val deviceOnline by ServiceLocator.network.collectAsStateWithLifecycle()
    // Drafts: a chat with unsent text says so on its row, exactly like WhatsApp's red "Draft".
    val drafts by ServiceLocator.drafts.drafts.collectAsStateWithLifecycle()
    // Archive is device-side (`ArchivedStore`): the worker has no column for it.
    val archivedIds by ServiceLocator.archived.ids.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(ChatFilter.All) }
    var archiveOpen by remember { mutableStateOf(false) }

    val archivedChats = remember(state.chats, archivedIds) {
        state.chats.filter { it.id in archivedIds }
    }
    val visibleChats = remember(state.chats, archivedIds, filter) {
        state.chats
            .filter { it.id !in archivedIds }
            .filter { chat -> filter == ChatFilter.All || chat.unreadCount > 0 }
    }

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

        state.error?.takeIf { deviceOnline }?.let { message ->
            SyncErrorBanner(message = message, onRetry = viewModel::retry)
        }

        ChatFilterRow(filter = filter, onFilter = { filter = it })

        if (visibleChats.isEmpty()) {
            NcGlassEmptyState(
                emoji = when {
                    state.query.isNotEmpty() -> "🔍"
                    filter == ChatFilter.Unread -> "✅"
                    else -> "💬"
                },
                title = when {
                    state.query.isNotEmpty() -> "No chats found"
                    filter == ChatFilter.Unread -> "No unread chats"
                    else -> "No chats yet"
                },
                message = when {
                    state.query.isNotEmpty() -> "Try a different search"
                    filter == ChatFilter.Unread -> "You are all caught up"
                    else -> "Start a conversation with your friends"
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NcSpace.md)
            ) {
                if (archivedChats.isNotEmpty()) {
                    item(key = "archived") {
                        ArchivedRow(count = archivedChats.size) { archiveOpen = true }
                    }
                }
                items(items = visibleChats, key = { it.id }) { chat ->
                    SwipeableChatRow(
                        chat = chat,
                        onArchive = {
                            ServiceLocator.archived.archive(chat.id)
                            NcToaster.show("Archived")
                        },
                        onToggleRead = {
                            if (chat.unreadCount > 0) {
                                viewModel.markRead(chat.id)
                                ServiceLocator.notifications.cancel(chat.id)
                                NcToaster.show("Marked as read")
                            } else {
                                // There is no "unread" flag on the server, so this is the honest
                                // version WhatsApp also uses when it cannot mark unread: say so.
                                NcToaster.show("Only unread chats can be marked read")
                            }
                        }
                    ) {
                    ChatRow(
                        chat = chat,
                        draft = drafts[chat.id],
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
                            // Reading the chat clears its notification, like WhatsApp.
                            ServiceLocator.notifications.cancel(chat.id)
                            NcToaster.show("Marked as read")
                        },
                        onDelete = { pendingDelete = chat }
                    )
                    }
                }
            }
        }
    }

    if (archiveOpen) {
        ArchivedChatsDialog(
            chats = archivedChats,
            onDismiss = { archiveOpen = false },
            onUnarchive = { chat ->
                ServiceLocator.archived.unarchive(chat.id)
                NcToaster.show("Unarchived")
            },
            onOpen = { chat ->
                archiveOpen = false
                viewModel.markRead(chat.id)
                onOpenChat(chat)
            }
        )
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

/** All / Unread — the two filters the Chats tab can honestly offer (groups live in their own tab). */
private enum class ChatFilter(val label: String) {
    All("All"),
    Unread("Unread")
}

@Composable
private fun ChatFilterRow(filter: ChatFilter, onFilter: (ChatFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NcSpace.md, end = NcSpace.md, bottom = NcSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
    ) {
        ChatFilter.values().forEach { entry ->
            val selected = entry == filter
            Text(
                text = entry.label,
                color = if (selected) Color.White else NcTheme.extra.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (selected) {
                            Brush.linearGradient(NcTheme.extra.brandGradient)
                        } else {
                            Brush.linearGradient(listOf(Color.White, Color.White))
                        }
                    )
                    .clickable { onFilter(entry) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}

/** The "Archived" entry that sits above the list while the archive has anything in it. */
@Composable
private fun ArchivedRow(count: Int, onOpen: () -> Unit) {
    val extra = NcTheme.extra
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NcSpace.sm)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.7f))
            .clickable(onClick = onOpen)
            .padding(horizontal = NcSpace.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
    ) {
        Icon(
            imageVector = Icons.Rounded.Archive,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Archived",
            color = extra.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (count == 1) "1" else "$count",
            color = extra.textTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * A chat row that answers to a swipe.
 *
 * Right reveals **Archive**, left reveals **Mark read** — one axis, claimed only after a
 * horizontal drag, so tapping and scrolling are untouched. The action fires on release past 72 dp
 * and the row springs back; the list itself decides what happens next (the archived row simply
 * disappears, which reads exactly like WhatsApp's row flying away).
 */
@Composable
private fun SwipeableChatRow(
    chat: Chat,
    onArchive: () -> Unit,
    onToggleRead: () -> Unit,
    content: @Composable () -> Unit
) {
    val animation = remember(chat.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    val ceiling = with(density) { 120.dp.toPx() }
    val offset = animation.value

    Box(modifier = Modifier.fillMaxWidth()) {
        // Hints live behind the row: archive on the left, read state on the right.
        if (offset > 4f) {
            SwipeHint(
                icon = Icons.Rounded.Archive,
                label = "Archive",
                color = Color(0xFF6D28D9),
                progress = (offset / threshold).coerceIn(0f, 1f),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = NcSpace.md),
                mirrored = false
            )
        } else if (offset < -4f) {
            SwipeHint(
                icon = if (chat.unreadCount > 0) Icons.Rounded.MarkChatRead else Icons.Rounded.MarkChatUnread,
                label = if (chat.unreadCount > 0) "Read" else "Unread",
                color = Color(0xFF059669),
                progress = (-offset / threshold).coerceIn(0f, 1f),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = NcSpace.md),
                mirrored = true
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { animation.snapTo((animation.value + delta).coerceIn(-ceiling, ceiling)) }
                    },
                    onDragStopped = {
                        if (animation.value >= threshold) onArchive()
                        else if (animation.value <= -threshold) onToggleRead()
                        scope.launch { animation.animateTo(0f, tween(180)) }
                    }
                )
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    progress: Float,
    modifier: Modifier = Modifier,
    mirrored: Boolean
) {
    Row(
        modifier = modifier.graphicsLayer { alpha = 0.35f + 0.65f * progress },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (mirrored) {
            Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(19.dp))
        } else {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(19.dp))
            Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The archive, as a sheet: open a chat or put it back into the main list. */
@Composable
private fun ArchivedChatsDialog(
    chats: List<Chat>,
    onDismiss: () -> Unit,
    onUnarchive: (Chat) -> Unit,
    onOpen: (Chat) -> Unit
) {
    val extra = NcTheme.extra
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(vertical = NcSpace.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Archived",
                color = extra.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tap a chat to open it, or Unarchive to move it back.",
                color = extra.textTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = NcSpace.lg, vertical = 6.dp)
            )
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(items = chats, key = { it.id }) { chat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(chat) }
                            .padding(horizontal = NcSpace.lg, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
                    ) {
                        Text(
                            text = chat.title,
                            color = extra.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Unarchive",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onUnarchive(chat) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(
    chat: Chat,
    draft: String? = null,
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

                    else -> PreviewLine(chat = chat, hasUnread = hasUnread, draft = draft)
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
private fun RowScope.PreviewLine(chat: Chat, hasUnread: Boolean, draft: String? = null) {
    val extra = NcTheme.extra

    if (!draft.isNullOrBlank()) {
        // The half-written message wins the preview slot until it is sent: it is the only thing
        // on this row that is still waiting for the user.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Draft:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEF4444),
                maxLines = 1
            )
            Text(
                text = draft,
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        return
    }

    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (chat.lastMessageMine) {
            if (chat.lastMessageFailed) DeliveryFailed() else DeliveryTick(state = chat.lastMessageState)
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

/** The newest message never reached the server: the row says so, like the bubble does. */
@Composable
private fun DeliveryFailed() {
    Icon(
        imageVector = Icons.Rounded.ErrorOutline,
        contentDescription = "Not sent",
        tint = Color(0xFFF87171),
        modifier = Modifier.size(14.dp)
    )
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
