@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.nchat.app.feature.chats

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.MarkChatRead
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcEmptyState
import com.nchat.app.core.designsystem.NcFilterChip
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcUnreadBadge
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.MessageStatus
import com.nchat.app.ui.components.NcGradientHeader
import com.nchat.app.ui.components.NcHeaderAction
import com.nchat.app.ui.components.NcSearchField

@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    listState: LazyListState,
    onOpenChat: (Chat) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var actionChat by remember { mutableStateOf<Chat?>(null) }
    var showNewChat by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val searchFocus = remember { FocusRequester() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            NcGradientHeader(
                title = "NChat",
                subtitle = if (state.unreadCount > 0) {
                    "${state.unreadCount} unread messages"
                } else {
                    "Tap a conversation to continue"
                },
                actions = {
                    NcHeaderAction(
                        icon = Icons.Rounded.Search,
                        contentDescription = "Search chats",
                        onClick = { searchFocus.requestFocus() }
                    )
                    NcHeaderAction(
                        icon = Icons.Rounded.MoreVert,
                        contentDescription = "Settings",
                        onClick = onOpenSettings
                    )
                }
            )

            NcSearchField(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                focusRequester = searchFocus
            )

            FilterRow(
                selected = state.filter,
                unreadCount = state.unreadCount,
                onSelect = viewModel::onFilterChange
            )

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> Unit

                    state.isEmpty -> NcEmptyState(
                        icon = Icons.Rounded.Search,
                        title = "No conversations",
                        message = if (state.query.isNotBlank()) {
                            "Nothing matches \"${state.query}\". Try a different name or keyword."
                        } else {
                            "There is nothing in this filter yet."
                        },
                        actionLabel = if (state.query.isNotBlank() || state.filter != ChatFilter.All) {
                            "Clear filters"
                        } else {
                            null
                        },
                        onAction = {
                            viewModel.onQueryChange("")
                            viewModel.onFilterChange(ChatFilter.All)
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    else -> ChatList(
                        pinned = state.pinned,
                        others = state.others,
                        listState = listState,
                        onOpenChat = { chat ->
                            viewModel.markRead(chat.id)
                            onOpenChat(chat)
                        },
                        onLongPress = { actionChat = it }
                    )
                }
            }
        }

        NewChatFab(
            onClick = { showNewChat = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = NcSpace.xl, bottom = NcSpace.xl)
        )
    }

    if (showNewChat) {
        NewChatSheet(
            contacts = state.contacts,
            onDismiss = { showNewChat = false },
            onSelect = { chat ->
                showNewChat = false
                viewModel.markRead(chat.id)
                onOpenChat(chat)
            }
        )
    }

    actionChat?.let { chat ->
        ChatActionSheet(
            chat = chat,
            sheetState = sheetState,
            onDismiss = { actionChat = null },
            onTogglePin = {
                viewModel.togglePin(chat.id)
                actionChat = null
            },
            onToggleMute = {
                viewModel.toggleMute(chat.id)
                actionChat = null
            },
            onMarkRead = {
                viewModel.markRead(chat.id)
                actionChat = null
            }
        )
    }
}

@Composable
private fun FilterRow(
    selected: ChatFilter,
    unreadCount: Int,
    onSelect: (ChatFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NcSpace.lg, vertical = NcSpace.xs),
        horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
    ) {
        ChatFilter.entries.forEach { filter ->
            NcFilterChip(
                label = filter.label,
                selected = filter == selected,
                badgeCount = if (filter == ChatFilter.Unread) unreadCount else 0,
                onClick = { onSelect(filter) }
            )
        }
    }
}

@Composable
private fun ChatList(
    pinned: List<Chat>,
    others: List<Chat>,
    listState: LazyListState,
    onOpenChat: (Chat) -> Unit,
    onLongPress: (Chat) -> Unit
) {
    val itemsSource = remember(pinned, others) { pinned + others }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (pinned.isNotEmpty()) {
            item(key = "pinned-header") {
                ListSectionLabel("Pinned")
            }
            items(items = pinned, key = { it.id }) { chat ->
                ChatRow(
                    chat = chat,
                    onClick = { onOpenChat(chat) },
                    onLongPress = { onLongPress(chat) },
                    modifier = Modifier.animateItemPlacement(
                        animationSpec = tween(durationMillis = 260)
                    )
                )
            }
        }
        if (pinned.isNotEmpty() && others.isNotEmpty()) {
            item(key = "others-header") {
                ListSectionLabel("All chats")
            }
        }
        items(items = others, key = { it.id }) { chat ->
            ChatRow(
                chat = chat,
                onClick = { onOpenChat(chat) },
                onLongPress = { onLongPress(chat) },
                modifier = Modifier.animateItemPlacement(
                    animationSpec = tween(durationMillis = 260)
                )
            )
        }
        item(key = "footer") {
            Text(
                text = "${itemsSource.size} conversations",
                style = MaterialTheme.typography.labelSmall,
                color = NcTheme.extra.mutedText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NcSpace.lg, vertical = NcSpace.md)
            )
        }
    }
}

@Composable
private fun ListSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.9.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = NcSpace.lg,
            end = NcSpace.lg,
            top = NcSpace.md,
            bottom = NcSpace.xxs
        )
    )
}

@Composable
private fun ChatRow(
    chat: Chat,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra
    val hasUnread = chat.unreadCount > 0
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (chat.isPinned) extra.pinnedTint else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(horizontal = NcSpace.md + 2.dp, vertical = NcSpace.sm + 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NcAvatar(
                name = chat.title,
                emoji = chat.avatarEmoji,
                isOnline = chat.isOnline,
                showPresence = !chat.isGroup,
                hasStoryRing = chat.hasStory && !chat.storySeen
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = NcSpace.md)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (chat.isVerified) {
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = "Verified",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = NcSpace.xs)
                                .size(14.dp)
                        )
                    }
                    if (chat.isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = "Pinned",
                            tint = extra.mutedText,
                            modifier = Modifier
                                .padding(start = NcSpace.xs)
                                .size(13.dp)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = TimeFormat.listTimestamp(chat.lastMessageAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasUnread) MaterialTheme.colorScheme.primary else extra.mutedText,
                        fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chat.isTyping) {
                        Text(
                            text = "typing…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        if (chat.draft != null) {
                            Text(
                                text = "Draft: ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                text = chat.draft,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontStyle = FontStyle.Italic,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        } else {
                            if (chat.lastMessageFromMe) {
                                OutgoingTicks(status = chat.lastMessageStatus)
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                            Text(
                                text = chat.lastMessagePreview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = extra.mutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (chat.isMuted) {
                        Icon(
                            imageVector = Icons.Rounded.VolumeOff,
                            contentDescription = "Muted",
                            tint = extra.mutedText,
                            modifier = Modifier
                                .padding(start = NcSpace.xs)
                                .size(14.dp)
                        )
                    }

                    if (hasUnread) {
                        Spacer(modifier = Modifier.width(NcSpace.xs + 2.dp))
                        NcUnreadBadge(count = chat.unreadCount, muted = chat.isMuted)
                    } else if (chat.isPinned) {
                        Spacer(modifier = Modifier.width(NcSpace.xs + 2.dp))
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = null,
                            tint = extra.mutedText.copy(alpha = 0.5f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 78.dp),
            thickness = 0.8.dp,
            color = extra.divider
        )
    }
}

@Composable
private fun OutgoingTicks(status: MessageStatus?) {
    val tint = when (status) {
        MessageStatus.Read -> MaterialTheme.colorScheme.tertiary
        MessageStatus.Sending -> NcTheme.extra.mutedText.copy(alpha = 0.6f)
        else -> NcTheme.extra.mutedText
    }
    val icon = when (status) {
        MessageStatus.Sending -> Icons.Rounded.Schedule
        MessageStatus.Sent -> Icons.Rounded.Check
        else -> Icons.Rounded.DoneAll
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(15.dp)
    )
}

@Composable
private fun NewChatFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradient = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary
    )
    Box(
        modifier = modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(gradient))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = "New chat",
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun NewChatSheet(
    contacts: List<Chat>,
    onDismiss: () -> Unit,
    onSelect: (Chat) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(modifier = Modifier.padding(bottom = NcSpace.xxl)) {
            Text(
                text = "New chat",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = NcSpace.xl, vertical = NcSpace.sm)
            )
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(items = contacts, key = { it.id }) { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(contact) }
                            .padding(horizontal = NcSpace.xl, vertical = NcSpace.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NcAvatar(
                            name = contact.title,
                            emoji = contact.avatarEmoji,
                            size = 42.dp,
                            showPresence = true,
                            isOnline = contact.isOnline
                        )
                        Column(modifier = Modifier.padding(start = NcSpace.md)) {
                            Text(
                                text = contact.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (contact.isOnline) "online" else "last seen recently",
                                style = MaterialTheme.typography.labelSmall,
                                color = NcTheme.extra.mutedText
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatActionSheet(
    chat: Chat,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onMarkRead: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(modifier = Modifier.padding(bottom = NcSpace.xxl)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NcSpace.xl, vertical = NcSpace.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NcAvatar(name = chat.title, emoji = chat.avatarEmoji, size = 44.dp)
                Column(modifier = Modifier.padding(start = NcSpace.md)) {
                    Text(
                        text = chat.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (chat.isGroup) "${chat.memberCount ?: 0} members" else "Direct chat",
                        style = MaterialTheme.typography.labelMedium,
                        color = NcTheme.extra.mutedText
                    )
                }
            }
            SheetAction(Icons.Rounded.PushPin, if (chat.isPinned) "Unpin chat" else "Pin chat", onTogglePin)
            SheetAction(
                Icons.Rounded.VolumeOff,
                if (chat.isMuted) "Unmute notifications" else "Mute notifications",
                onToggleMute
            )
            SheetAction(Icons.Rounded.MarkChatRead, "Mark as read", onMarkRead)
            SheetAction(Icons.Rounded.Archive, "Archive chat", onDismiss)
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = NcSpace.xl, vertical = NcSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(21.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = NcSpace.lg)
        )
    }
}
