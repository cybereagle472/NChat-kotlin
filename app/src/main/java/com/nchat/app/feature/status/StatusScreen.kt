package com.nchat.app.feature.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcGlassRow
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.ui.components.NcHeaderAction
import com.nchat.app.ui.components.NcTabHeader

@Composable
fun StatusScreen(
    statuses: List<StatusUpdate>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra
    val mine = statuses.firstOrNull { it.isMine }
    val others = remember(statuses) { statuses.filterNot { it.isMine } }

    var viewingIndex by remember { mutableStateOf<Int?>(null) }
    var locallyViewed by remember { mutableStateOf(emptySet<String>()) }
    fun openStatus(status: StatusUpdate) {
        viewingIndex = statuses.indexOfFirst { it.id == status.id }.takeIf { it >= 0 }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            NcTabHeader(
                title = "Status",
                actions = {
                    NcHeaderAction(Icons.Rounded.CameraAlt, "Camera", onClick = {})
                    NcHeaderAction(Icons.Rounded.Edit, "Text status", onClick = {})
                }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NcSpace.md),
                verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                item(key = "my-status") {
                    NcGlassRow(onClick = { mine?.let(::openStatus) }) {
                        Box {
                            NcAvatar(name = mine?.authorName ?: "My status", size = 56.dp)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .border(2.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "My status",
                                style = MaterialTheme.typography.titleMedium,
                                color = extra.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Tap to add status update",
                                style = MaterialTheme.typography.bodyMedium,
                                color = extra.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                item(key = "recent-header") {
                    Text(
                        text = "Recent updates",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = extra.textTertiary,
                        modifier = Modifier.padding(start = NcSpace.xs, top = NcSpace.sm)
                    )
                }

                items(items = others, key = { it.id }) { status ->
                    NcGlassRow(onClick = { openStatus(status) }) {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(
                                    if (status.viewed || status.id in locallyViewed) {
                                        Brush.linearGradient(listOf(extra.divider, extra.divider))
                                    } else {
                                        Brush.linearGradient(extra.brandGradient)
                                    }
                                )
                                .padding(3.dp)
                        ) {
                            NcAvatar(
                                name = status.authorName,
                                emoji = status.avatarEmoji,
                                size = 56.dp
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = status.authorName,
                                style = MaterialTheme.typography.titleMedium,
                                color = extra.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = status.caption,
                                style = MaterialTheme.typography.bodyMedium,
                                color = extra.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = TimeFormat.shortAgo(status.timestamp),
                            style = MaterialTheme.typography.labelMedium,
                            color = extra.textMuted,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                item(key = "status-footer") {
                    Spacer(modifier = Modifier.size(NcSpace.md))
                    Text(
                        text = "Status updates disappear after 24 hours",
                        style = MaterialTheme.typography.labelSmall,
                        color = extra.textMuted
                    )
                }
            }
        }

        viewingIndex?.let { start ->
            StatusViewer(
                statuses = statuses,
                startIndex = start,
                onViewed = { id -> locallyViewed = locallyViewed + id },
                onDismiss = { viewingIndex = null }
            )
        }
    }
}
