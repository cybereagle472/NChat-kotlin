package com.nchat.app.feature.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcEmptyState
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcUnreadBadge
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.Channel
import com.nchat.app.data.model.Story
import com.nchat.app.ui.components.NcGradientHeader
import com.nchat.app.ui.components.NcHeaderAction

@Composable
fun UpdatesScreen(
    stories: List<Story>,
    channels: List<Channel>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        NcGradientHeader(
            title = "Updates",
            subtitle = "Status and channels",
            actions = {
                NcHeaderAction(Icons.Rounded.CameraAlt, "Add status", onClick = {})
                NcHeaderAction(Icons.Rounded.MoreVert, "More options", onClick = {})
            }
        )

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "status-header") {
                SectionTitle(title = "Status", trailing = "Tap to view")
            }
            item(key = "status-row") {
                LazyRow(
                    contentPadding = PaddingValues(
                        horizontal = NcSpace.lg,
                        vertical = NcSpace.sm
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
                ) {
                    items(items = stories, key = { it.id }) { story ->
                        StoryItem(story = story)
                    }
                }
            }

            item(key = "channels-header") {
                SectionTitle(
                    title = "Channels",
                    trailing = "${channels.size} channels",
                    modifier = Modifier.padding(top = NcSpace.md)
                )
            }

            if (channels.isEmpty()) {
                item(key = "channels-empty") {
                    NcEmptyState(
                        icon = Icons.Rounded.Campaign,
                        title = "No channels yet",
                        message = "Follow channels to get updates from the people and brands you care about.",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                items(items = channels, key = { it.id }) { channel ->
                    ChannelRow(channel = channel)
                }
            }

            item(key = "updates-footer") {
                Text(
                    text = "Status updates disappear after 24 hours",
                    style = MaterialTheme.typography.labelSmall,
                    color = NcTheme.extra.mutedText,
                    modifier = Modifier.padding(NcSpace.lg)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    trailing: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = NcSpace.lg, end = NcSpace.lg, top = NcSpace.sm, bottom = NcSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun StoryItem(story: Story) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .clickable { },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            NcAvatar(
                name = story.authorName,
                emoji = story.avatarEmoji,
                size = 58.dp,
                hasStoryRing = !story.isMine && !story.viewed
            )
            if (story.isMine) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(21.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Add status",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
        Text(
            text = if (story.isMine) "My status" else story.authorName.substringBefore(' '),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = NcSpace.xs)
        )
        Text(
            text = if (story.isMine) "Tap to add" else TimeFormat.shortAgo(story.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = NcTheme.extra.mutedText,
            maxLines = 1
        )
    }
}

@Composable
private fun ChannelRow(channel: Channel) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { }
                .padding(horizontal = NcSpace.md + 2.dp, vertical = NcSpace.sm + 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NcAvatar(name = channel.name, emoji = "📣", size = 50.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = NcSpace.md)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (channel.verified) {
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = "Verified",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = NcSpace.xs)
                                .size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = TimeFormat.listTimestamp(channel.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (channel.unreadCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            NcTheme.extra.mutedText
                        }
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channel.lastUpdate,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NcTheme.extra.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (channel.isMuted) {
                        Icon(
                            imageVector = Icons.Rounded.VolumeOff,
                            contentDescription = "Muted",
                            tint = NcTheme.extra.mutedText,
                            modifier = Modifier
                                .padding(start = NcSpace.xs)
                                .size(14.dp)
                        )
                    }
                    if (channel.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(NcSpace.xs + 2.dp))
                        NcUnreadBadge(count = channel.unreadCount, muted = channel.isMuted)
                    }
                }
                Text(
                    text = channel.followerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = NcTheme.extra.mutedText,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 78.dp),
            thickness = 0.8.dp,
            color = NcTheme.extra.divider
        )
    }
}
