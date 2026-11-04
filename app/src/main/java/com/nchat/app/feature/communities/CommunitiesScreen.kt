package com.nchat.app.feature.communities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcUnreadBadge
import com.nchat.app.data.model.Community
import com.nchat.app.ui.components.NcGradientHeader
import com.nchat.app.ui.components.NcHeaderAction

@Composable
fun CommunitiesScreen(
    communities: List<Community>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        NcGradientHeader(
            title = "Communities",
            subtitle = "${communities.size} communities joined",
            actions = {
                NcHeaderAction(Icons.Rounded.Search, "Search communities", onClick = {})
                NcHeaderAction(Icons.Rounded.MoreVert, "More options", onClick = {})
            }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = NcSpace.md,
                vertical = NcSpace.md
            ),
            verticalArrangement = Arrangement.spacedBy(NcSpace.md)
        ) {
            items(items = communities, key = { it.id }) { community ->
                CommunityCard(community = community)
            }
            item(key = "communities-footer") {
                Text(
                    text = "Communities keep related groups in one place",
                    style = MaterialTheme.typography.labelSmall,
                    color = NcTheme.extra.mutedText,
                    modifier = Modifier.padding(horizontal = NcSpace.xs, vertical = NcSpace.sm)
                )
            }
        }
    }
}

@Composable
private fun CommunityCard(community: Community) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(NcSpace.md + 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NcAvatar(
                    name = community.name,
                    emoji = community.avatarEmoji ?: community.name.take(1),
                    size = 50.dp
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = NcSpace.md)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = community.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = "Official community",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = NcSpace.xs)
                                .size(14.dp)
                        )
                    }
                    Text(
                        text = community.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = NcTheme.extra.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (community.unreadCount > 0) {
                    NcUnreadBadge(count = community.unreadCount)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Open community",
                        tint = NcTheme.extra.mutedText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(NcSpace.sm))

            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaChip(icon = true, text = community.memberLabel)
                Spacer(modifier = Modifier.width(NcSpace.sm))
                MetaChip(icon = false, text = "${community.groupCount} groups")
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NcSpace.sm)
                    .clip(RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = NcSpace.sm, vertical = NcSpace.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Campaign,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = community.announcement,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = NcSpace.sm)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaChip(icon: Boolean, text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NcSpace.sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon) {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = null,
                    tint = NcTheme.extra.mutedText,
                    modifier = Modifier
                        .size(13.dp)
                        .padding(end = 0.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = NcTheme.extra.mutedText
            )
        }
    }
}
