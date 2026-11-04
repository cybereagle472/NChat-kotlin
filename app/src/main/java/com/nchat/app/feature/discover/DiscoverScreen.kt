package com.nchat.app.feature.discover

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.pressFeedback
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.FriendRelation
import kotlinx.coroutines.delay

private enum class DiscoverTab(val label: String, val icon: ImageVector) {
    Friends("Friends", Icons.Rounded.Groups),
    Requests("Requests", Icons.Rounded.PersonAdd),
    Find("Find", Icons.Rounded.Search)
}

private val Violet = Color(0xFF7C3AED)
private val Slate800 = Color(0xFF1E293B)
private val Slate700 = Color(0xFF334155)
private val Slate500 = Color(0xFF64748B)
private val Slate400 = Color(0xFF94A3B8)
private val Green600 = Color(0xFF16A34A)
private val Amber700 = Color(0xFFB45309)
private val Red500 = Color(0xFFEF4444)

/**
 * `Discover.tsx` — the combined Friends / Requests / Find surface, including the search
 * fields, the add / cancel / unfriend actions and the unfriend confirmation dialog.
 */
@Composable
fun DiscoverScreen(
    friends: List<Friend>,
    listState: LazyListState,
    onAddFriend: (String) -> Unit,
    onCancelRequest: (String) -> Unit,
    onAcceptRequest: (String) -> Unit,
    onDeclineRequest: (String) -> Unit,
    onUnfriend: (String) -> Unit,
    onMessage: (Friend) -> Unit,
    onCall: (Friend, Boolean) -> Unit,
    onViewProfile: (Friend) -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableStateOf(DiscoverTab.Friends) }
    var query by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<Friend?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }

    val friendRows = friends.filter { it.relation == FriendRelation.Friend }
    val requests = friends.filter { it.relation == FriendRelation.Incoming }
    val suggestions = friends.filter {
        it.relation == FriendRelation.Outgoing || it.relation == FriendRelation.None
    }

    Column(modifier = modifier.fillMaxSize()) {
        DiscoverHeader(
            tab = tab,
            friendCount = friendRows.size,
            requestCount = requests.size,
            onSelect = { tab = it }
        )

        val visibleFriends = friendRows.filter { it.matches(query) }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NcSpace.md),
            verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
        ) {
            when (tab) {
                DiscoverTab.Friends -> {
                    item(key = "friends-search") {
                        SearchField(
                            value = query,
                            placeholder = "Search friends...",
                            onValueChange = { query = it }
                        )
                    }

                    if (visibleFriends.isEmpty()) {
                        item(key = "friends-empty") {
                            EmptyCard(
                                emoji = "👥",
                                title = if (query.isBlank()) "No friends yet" else "No friends found",
                                message = if (query.isBlank()) {
                                    "Add friends to start chatting"
                                } else {
                                    "Try a different search"
                                }
                            )
                        }
                    } else {
                        items(
                            items = visibleFriends,
                            key = { "friend-${it.id}" }
                        ) { friend ->
                            FriendCard(
                                    friend = friend,
                                    onOpenProfile = { onViewProfile(friend) },
                                    onMessage = { onMessage(friend) },
                                onCall = { video -> onCall(friend, video) }
                            )
                        }
                    }
                }

                DiscoverTab.Requests -> {
                    if (requests.isEmpty()) {
                        item(key = "requests-empty") {
                            EmptyCard(
                                emoji = "📬",
                                title = "No pending requests",
                                message = "Friend requests will appear here"
                            )
                        }
                    } else {
                        items(
                            items = requests,
                            key = { "request-${it.id}" }
                        ) { friend ->
                            RequestCard(
                                    friend = friend,
                                    busy = busyId == friend.id,
                                    onOpenProfile = { onViewProfile(friend) },
                                    onAccept = {
                                        busyId = friend.id
                                        onAcceptRequest(friend.id)
                                    },
                                onDecline = {
                                    busyId = friend.id
                                    onDeclineRequest(friend.id)
                                }
                            )
                        }
                    }
                }

                DiscoverTab.Find -> {
                    item(key = "find-search") {
                        SearchField(
                            value = query,
                            placeholder = "Search by username or name...",
                            onValueChange = { query = it }
                        )
                    }

                    val visibleUsers = suggestions.filter { it.matches(query) }

                    if (visibleUsers.isEmpty()) {
                        item(key = "find-empty") {
                            EmptyCard(
                                emoji = "🔍",
                                title = if (query.isBlank()) "No users yet" else "No users found",
                                message = if (query.isBlank()) {
                                    "Check back later for new people"
                                } else {
                                    "Try a different search"
                                }
                            )
                        }
                    } else {
                        item(key = "find-label") {
                            Row(modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xCCEDE9FE))
                                        .border(1.dp, Color(0x996DD3FA).copy(alpha = 0.35f), CircleShape)
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "All Users",
                                        color = Color(0xFF6D28D9),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        items(
                            items = visibleUsers,
                            key = { "user-${it.id}" }
                        ) { friend ->
                            UserCard(
                                    friend = friend,
                                    busy = busyId == friend.id,
                                    onOpenProfile = { onViewProfile(friend) },
                                    onAdd = {
                                        busyId = friend.id
                                        onAddFriend(friend.id)
                                    },
                                    onCancel = {
                                        busyId = friend.id
                                        onCancelRequest(friend.id)
                                    },
                                onUnfriendClick = { confirming = friend }
                            )
                        }
                    }
                }
            }
        }
    }

    // The processing flag only exists to mirror the reference button's "..." state.
    LaunchedEffect(busyId) {
        if (busyId != null) {
            delay(650)
            busyId = null
        }
    }

    confirming?.let { friend ->
        UnfriendDialog(
            name = friend.name,
            onDismiss = { confirming = null },
            onConfirm = {
                onUnfriend(friend.id)
                confirming = null
            }
        )
    }
}

/** Gradient header with the sparkles title and the three sub-tabs, count badges included. */
@Composable
private fun DiscoverHeader(
    tab: DiscoverTab,
    friendCount: Int,
    requestCount: Int,
    onSelect: (DiscoverTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(NcTheme.extra.brandGradient))
            .shadow(10.dp)
            .statusBarsPadding()
            .padding(horizontal = NcSpace.lg, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Discover",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = NcSpace.md),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DiscoverTab.values().forEach { entry ->
                val active = entry == tab
                val background by animateColorAsState(
                    targetValue = if (active) Color.White else Color(0x33FFFFFF),
                    animationSpec = tween(300),
                    label = "discoverTabBg"
                )
                val foreground by animateColorAsState(
                    targetValue = if (active) Violet else Color.White,
                    animationSpec = tween(300),
                    label = "discoverTabFg"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .graphicsLayer {
                            val scale = if (active) 1.05f else 1f
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(background)
                        .then(
                            if (active) {
                                Modifier
                            } else {
                                Modifier.border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                            }
                        )
                        .clickable { onSelect(entry) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = entry.icon,
                            contentDescription = null,
                            tint = foreground,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = entry.label,
                            color = foreground,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                        val badge = when (entry) {
                            DiscoverTab.Friends -> friendCount
                            DiscoverTab.Requests -> requestCount
                            DiscoverTab.Find -> 0
                        }
                        if (badge > 0) {
                            TabBadge(
                                count = badge,
                                active = active,
                                urgent = entry == DiscoverTab.Requests && !active
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabBadge(count: Int, active: Boolean, urgent: Boolean) {
    val background = when {
        active && urgent -> Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)))
        active -> SolidColor(Color(0xDDEDE9FE))
        urgent -> Brush.linearGradient(listOf(Color(0xFFEC4899), Color(0xFFF43F5E)))
        else -> SolidColor(Color(0x33FFFFFF))
    }
    val foreground = if (active && !urgent) Violet else Color.White
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 7.dp, vertical = 1.dp)
    ) {
        Text(
            text = count.toString(),
            color = foreground,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Glass search field with the leading magnifier, exactly like both reference search bars. */
@Composable
private fun SearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xD9FFFFFF))
            .border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = Color(0xFFA78BFA),
                modifier = Modifier.size(16.dp)
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = Slate800),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                cursorBrush = SolidColor(Violet),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            )
            if (value.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Clear search",
                    tint = Slate400,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onValueChange("") }
                )
            }
        }
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = Slate400,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 26.dp)
            )
        }
    }
}

@Composable
private fun FriendCard(
    friend: Friend,
    onOpenProfile: () -> Unit,
    onMessage: () -> Unit,
    onCall: (Boolean) -> Unit
) {
    GlassCard(onClick = onOpenProfile) {
        NcAvatar(
            name = friend.name,
            size = 56.dp,
            isOnline = friend.isOnline,
            showPresence = true
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friend.name,
                color = Slate800,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = friend.handle.ifBlank { "@user" },
                color = Slate500,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            PresenceLabel(friend = friend)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            RowAction(Icons.Rounded.ChatBubble, "Message", onMessage)
            RowAction(Icons.Rounded.Phone, "Voice call") { onCall(false) }
            RowAction(Icons.Rounded.Videocam, "Video call") { onCall(true) }
        }
    }
}

@Composable
private fun PresenceLabel(friend: Friend) {
    val text: String
    val color: Color
    when {
        friend.isOnline -> {
            text = "Online"
            color = Green600
        }
        friend.lastSeenAt != null -> {
            text = "Last seen ${TimeFormat.dateLabel(friend.lastSeenAt)}"
            color = Slate400
        }
        else -> {
            text = "Offline"
            color = Slate400
        }
    }
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = if (friend.isOnline) FontWeight.Medium else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun RequestCard(
    friend: Friend,
    busy: Boolean,
    onOpenProfile: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xB3FFFFFF))
            .border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenProfile),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NcAvatar(
                name = friend.name,
                size = 48.dp,
                isOnline = friend.isOnline,
                showPresence = true
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = friend.name,
                    color = Slate800,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = friend.handle.ifBlank { "@user" },
                    color = Violet,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = friend.requestedAt?.let { TimeFormat.agoLabel(it) } ?: "Just now",
                    color = Slate500,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(NcTheme.extra.brandGradient))
                    .clickable(enabled = !busy, onClick = onAccept),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = if (busy) "..." else "Accept",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCCE2E8F0))
                    .border(1.dp, Color(0x99CBD5E1), RoundedCornerShape(12.dp))
                    .clickable(enabled = !busy, onClick = onDecline),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = Slate700,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Decline",
                        color = Slate700,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun UserCard(
    friend: Friend,
    busy: Boolean,
    onOpenProfile: () -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    onUnfriendClick: () -> Unit
) {
    GlassCard(onClick = onOpenProfile) {
        NcAvatar(
            name = friend.name,
            size = 48.dp,
            isOnline = friend.isOnline,
            showPresence = true
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = friend.name,
                    color = Slate800,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (friend.relation == FriendRelation.Friend) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFDCFCE7))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "Friend",
                            color = Color(0xFF15803D),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Text(
                text = friend.handle.ifBlank { "@user" },
                color = Slate500,
                fontSize = 11.sp,
                maxLines = 1
            )
        }

        when (friend.relation) {
            FriendRelation.Friend -> ActionPill(
                label = if (busy) "..." else "Friends",
                icon = Icons.Rounded.HowToReg,
                background = SolidColor(Color(0xFFDCFCE7)),
                foreground = Color(0xFF15803D),
                onClick = onUnfriendClick
            )

            FriendRelation.Outgoing -> ActionPill(
                label = if (busy) "..." else "Cancel",
                icon = Icons.Rounded.Close,
                background = SolidColor(Color(0xFFFEF3C7)),
                foreground = Amber700,
                onClick = onCancel
            )

            else -> ActionPill(
                label = "Add",
                icon = Icons.Rounded.PersonAdd,
                background = Brush.linearGradient(NcTheme.extra.brandGradient),
                foreground = Color.White,
                onClick = onAdd
            )
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    icon: ImageVector,
    background: Brush,
    foreground: Color,
    onClick: () -> Unit
) {
    val interaction = rememberPressSource()
    Row(
        modifier = Modifier
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(14.dp)
        )
        Text(text = label, color = foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RowAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val interaction = rememberPressSource()
    Box(
        modifier = Modifier
            .size(32.dp)
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Violet,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** `.glass-card` row: translucent white, hairline border, scale-only press feedback. */
@Composable
private fun GlassCard(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = rememberPressSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xB3FFFFFF))
            .border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun EmptyCard(emoji: String, title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(256.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x99FFFFFF))
            .border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(16.dp))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(NcTheme.extra.brandGradient)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = 30.sp)
        }
        Text(
            text = title,
            color = Slate700,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = message,
            color = Slate500,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Unfriend confirmation from the reference `Discover.tsx` modal. */
@Composable
private fun UnfriendDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(200), label = "dialogAlpha")
        val scale by animateFloatAsState(
            targetValue = if (shown) 1f else 0.95f,
            animationSpec = tween(200),
            label = "dialogScale"
        )
        val interaction = remember { MutableInteractionSource() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f * alpha))
                .clickable(interactionSource = interaction, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFAFFFFFF))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFEE2E2)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PersonRemove,
                        contentDescription = null,
                        tint = Red500,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    text = "Unfriend $name?",
                    color = Slate800,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = "Are you sure you want to remove $name from your friends? " +
                        "You can always add them back later.",
                    color = Slate500,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF1F5F9))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Cancel",
                            color = Slate700,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Red500)
                            .clickable(onClick = onConfirm),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Unfriend",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/** `matchesQuery` from the reference: case-insensitive name / handle contains. */
private fun Friend.matches(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    val needle = trimmed.removePrefix("@").lowercase()
    return name.lowercase().contains(needle) ||
        handle.removePrefix("@").lowercase().contains(needle)
}
