package com.nchat.app.feature.groups

import androidx.compose.foundation.layout.Arrangement
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
import com.nchat.app.data.model.Group
import com.nchat.app.ui.components.NcHeaderAction
import com.nchat.app.ui.components.NcTabHeader

@Composable
fun GroupsScreen(
    groups: List<Group>,
    listState: LazyListState,
    onCreateGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra

    Column(modifier = modifier.fillMaxSize()) {
        NcTabHeader(
            title = "Groups",
            actions = {
                NcHeaderAction(Icons.Rounded.Add, "Create group", onClick = onCreateGroup)
            }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NcSpace.md),
            verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
        ) {
            items(items = groups, key = { it.id }) { group ->
                NcGlassRow(onClick = {}) {
                    NcAvatar(
                        name = group.name,
                        emoji = group.avatarEmoji ?: "👥",
                        size = 56.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (group.unreadCount > 0) extra.textPrimary else extra.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = TimeFormat.listTimestamp(group.lastActivityAt),
                                style = MaterialTheme.typography.labelMedium,
                                color = extra.textMuted
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Groups,
                                contentDescription = null,
                                tint = extra.textMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "${group.memberCount} members · ${group.lastActivity}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = extra.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 5.dp)
                            )
                            if (group.unreadCount > 0) {
                                NcUnreadBadge(count = group.unreadCount)
                            }
                        }
                    }
                }
            }

            item(key = "groups-footer") {
                Text(
                    text = "Groups keep your conversations private and organised",
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.textMuted,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(start = NcSpace.xs, top = NcSpace.sm)
                )
            }
        }
    }
}
