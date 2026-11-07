package com.nchat.app.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Groups
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcGlassRow
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcUnreadBadge
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.Group
import com.nchat.app.ui.components.NcHeaderAction
import com.nchat.app.ui.components.NcTabHeader
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

@Composable
fun GroupsScreen(
    groups: List<Group>,
    friends: List<Friend>,
    listState: LazyListState,
    onOpenGroup: (Group) -> Unit,
    onCreateGroup: (name: String, description: String, memberIds: List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra
    var creating by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            NcTabHeader(
                title = "Groups",
                actions = {
                    NcHeaderAction(Icons.Rounded.Add, "Create group", onClick = { creating = true })
                }
            )

            // Where the chat list has a filter row, the groups list says how many there are: a
            // bare list under a gradient header gave no sense of scale.
            if (groups.isNotEmpty()) {
                Text(
                    text = if (groups.size == 1) "1 group" else "${groups.size} groups",
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.textMuted,
                    modifier = Modifier.padding(
                        start = NcSpace.lg,
                        end = NcSpace.lg,
                        top = NcSpace.md,
                        bottom = NcSpace.sm
                    )
                )
            }

            if (groups.isEmpty()) {
                EmptyGroups(onCreate = { creating = true })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(NcSpace.md),
                    verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
                ) {
                    items(items = groups, key = { it.id }) { group ->
                        NcGlassRow(onClick = { onOpenGroup(group) }) {
                            NcAvatar(
                                name = group.name,
                                emoji = group.avatarEmoji ?: "👥",
                                size = 56.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                /*
                                 * The same two-line grid as the chat rows, on purpose: name and
                                 * clock on the first line, everything about the group on the
                                 * second. The old row let the clock float wherever the name
                                 * ended, so the right edge of this tab did not line up with the
                                 * Chats tab it sits next to in the tab bar.
                                 */
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (group.unreadCount > 0) {
                                            extra.textPrimary
                                        } else {
                                            extra.textSecondary
                                        },
                                        fontWeight = if (group.unreadCount > 0) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Medium
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.size(NcSpace.sm))
                                    Text(
                                        text = TimeFormat.listTimestamp(group.lastActivityAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (group.unreadCount > 0) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            extra.textMuted
                                        },
                                        fontWeight = if (group.unreadCount > 0) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                        maxLines = 1
                                    )
                                }
                                Row(
                                    modifier = Modifier.padding(top = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Groups,
                                        contentDescription = null,
                                        tint = extra.textMuted,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "${group.memberCount} members",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = extra.textTertiary,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                    Text(
                                        text = " · ${group.lastActivity}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (group.unreadCount > 0) {
                                            extra.textSecondary
                                        } else {
                                            extra.textTertiary
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.size(NcSpace.sm))
                                    if (group.unreadCount > 0) {
                                        NcUnreadBadge(count = group.unreadCount)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (creating) {
            CreateGroupScreen(
                friends = friends,
                onBack = { creating = false },
                onCreate = { name, description, members ->
                    onCreateGroup(name, description, members)
                    creating = false
                }
            )
        }
    }
}

/**
 * No groups yet.
 *
 * The tab used to show a grey sentence at the bottom of an otherwise empty screen, which looked
 * like a loading failure. A group chat is a two-step idea for somebody who has never made one, so
 * the empty state says what it is and offers the one action that fixes it.
 */
@Composable
private fun EmptyGroups(onCreate: () -> Unit) {
    val extra = NcTheme.extra
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(NcSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(extra.brandGradient)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Groups,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
        Text(
            text = "No groups yet",
            color = extra.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = NcSpace.md)
        )
        Text(
            text = "A group is one conversation with several people at once — friends, family, a team.",
            color = extra.textTertiary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = NcSpace.xs, start = NcSpace.lg, end = NcSpace.lg)
        )
        Box(
            modifier = Modifier
                .padding(top = NcSpace.lg)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(extra.brandGradient))
                .clickable(onClick = onCreate)
                .padding(horizontal = NcSpace.lg, vertical = 10.dp)
        ) {
            Text(
                text = "Create a group",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
