package com.nchat.app.feature.history

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.PhoneMissed
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.CallRecord
import com.nchat.app.ui.components.NcTabHeader

@Composable
fun HistoryScreen(
    calls: List<CallRecord>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra

    Column(modifier = modifier.fillMaxSize()) {
        NcTabHeader(title = "History")

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NcSpace.md),
            verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
        ) {
            items(items = calls, key = { it.id }) { call ->
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
                        name = call.name,
                        emoji = if (call.isGroup) "👥" else null,
                        size = 52.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = call.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (call.missed) MaterialTheme.colorScheme.error else extra.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when {
                                    call.missed -> Icons.Rounded.PhoneMissed
                                    call.isOutgoing -> Icons.Rounded.CallMade
                                    else -> Icons.Rounded.CallReceived
                                },
                                contentDescription = null,
                                tint = if (call.missed) MaterialTheme.colorScheme.error else extra.textMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = buildString {
                                    append(
                                        when {
                                            call.missed -> "Missed"
                                            call.isOutgoing -> "Outgoing"
                                            else -> "Incoming"
                                        }
                                    )
                                    append(" · ")
                                    append(TimeFormat.shortAgo(call.timestamp))
                                    call.durationLabel?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.textTertiary,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 5.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .clickable { },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (call.isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                            contentDescription = if (call.isVideo) "Video call" else "Voice call",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            item(key = "history-footer") {
                Text(
                    text = "Call history is stored on this device",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Normal,
                    color = extra.textMuted,
                    modifier = Modifier.padding(start = NcSpace.xs, top = NcSpace.sm)
                )
            }
        }
    }
}
