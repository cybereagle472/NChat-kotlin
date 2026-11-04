package com.nchat.app.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.data.model.Friend
import com.nchat.app.ui.components.NcTabHeader

@Composable
fun DiscoverScreen(
    friends: List<Friend>,
    listState: LazyListState,
    onMessage: (Friend) -> Unit,
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra
    val requests = friends.filter { it.requestIncoming }
    val suggestions = friends.filterNot { it.requestIncoming }

    Column(modifier = modifier.fillMaxSize()) {
        NcTabHeader(title = "Discover")

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NcSpace.md),
            verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
        ) {
            if (requests.isNotEmpty()) {
                item(key = "requests-label") {
                    SectionLabel("Friend requests")
                }
                items(items = requests, key = { "req-${it.id}" }) { friend ->
                    FriendRow(friend = friend, onMessage = onMessage)
                }
            }

            item(key = "suggestions-label") {
                SectionLabel("People you may know")
            }

            items(items = suggestions, key = { "sug-${it.id}" }) { friend ->
                FriendRow(friend = friend, onMessage = onMessage)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = NcTheme.extra.textTertiary,
        modifier = Modifier.padding(start = NcSpace.xs, top = NcSpace.sm, bottom = NcSpace.xxs)
    )
}

@Composable
private fun FriendRow(friend: Friend, onMessage: (Friend) -> Unit) {
    val extra = NcTheme.extra

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(extra.rowFill)
            .border(1.dp, extra.rowBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = NcSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
    ) {
        NcAvatar(
            name = friend.name,
            size = 52.dp,
            isOnline = friend.isOnline,
            showPresence = true
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friend.name,
                style = MaterialTheme.typography.titleMedium,
                color = extra.textSecondary,
                maxLines = 1
            )
            Text(
                text = "${friend.handle} · ${friend.mutualFriends} mutual friends",
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textTertiary,
                maxLines = 1
            )
        }

        if (friend.requestIncoming) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(extra.brandGradient.first(), extra.brandGradient[1])
                        )
                    )
                    .clickable { onMessage(friend) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Accept",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .clickable { onMessage(friend) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.PersonAdd,
                    contentDescription = "Add friend",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
