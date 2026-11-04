package com.nchat.app.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneMissed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcEmptyState
import com.nchat.app.core.designsystem.NcFilterChip
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.CallEntry
import com.nchat.app.data.model.CallOutcome
import com.nchat.app.data.model.CallType
import com.nchat.app.ui.components.NcGradientHeader
import com.nchat.app.ui.components.NcHeaderAction

private enum class CallFilter(val label: String) { All("All"), Missed("Missed") }

@Composable
fun CallsScreen(
    calls: List<CallEntry>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    var filter by remember { mutableStateOf(CallFilter.All) }
    val visible = remember(calls, filter) {
        calls.filter { filter == CallFilter.All || it.outcome == CallOutcome.Missed }
            .sortedByDescending { it.timestamp }
    }

    Column(modifier = modifier.fillMaxSize()) {
        NcGradientHeader(
            title = "Calls",
            subtitle = "${calls.size} recent calls",
            actions = {
                NcHeaderAction(Icons.Rounded.Search, "Search calls", onClick = {})
                NcHeaderAction(Icons.Rounded.MoreVert, "More options", onClick = {})
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NcSpace.lg, vertical = NcSpace.md),
            horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
        ) {
            CallFilter.entries.forEach { option ->
                NcFilterChip(
                    label = option.label,
                    selected = option == filter,
                    onClick = { filter = option }
                )
            }
        }

        if (visible.isEmpty()) {
            NcEmptyState(
                icon = Icons.Rounded.Call,
                title = "No calls yet",
                message = "Your voice and video call history will appear here.",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(items = visible, key = { it.id }) { call ->
                    CallRow(call = call)
                }
                item(key = "calls-footer") {
                    Text(
                        text = "Call history is stored on this device",
                        style = MaterialTheme.typography.labelSmall,
                        color = NcTheme.extra.mutedText,
                        modifier = Modifier.padding(NcSpace.lg)
                    )
                }
            }
        }
    }
}

@Composable
private fun CallRow(call: CallEntry) {
    val isMissed = call.outcome != CallOutcome.Answered
    val nameColor = if (isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { }
                .padding(horizontal = NcSpace.md + 2.dp, vertical = NcSpace.sm + 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NcAvatar(
                name = call.contactName,
                emoji = if (call.isGroup) "👥" else null,
                size = 48.dp
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = NcSpace.md)
            ) {
                Text(
                    text = call.contactName,
                    style = MaterialTheme.typography.titleMedium,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            isMissed -> Icons.Rounded.PhoneMissed
                            call.isOutgoing -> Icons.Rounded.CallMade
                            else -> Icons.Rounded.CallReceived
                        },
                        contentDescription = null,
                        tint = if (isMissed) MaterialTheme.colorScheme.error else NcTheme.extra.mutedText,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = buildString {
                            append(
                                when {
                                    call.outcome == CallOutcome.Missed -> "Missed"
                                    call.outcome == CallOutcome.Declined -> "Declined"
                                    call.isOutgoing -> "Outgoing"
                                    else -> "Incoming"
                                }
                            )
                            append(" · ")
                            append(TimeFormat.shortAgo(call.timestamp))
                            call.durationLabel?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NcTheme.extra.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .clickable { },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (call.type == CallType.Video) {
                        Icons.Rounded.Videocam
                    } else {
                        Icons.Rounded.Call
                    },
                    contentDescription = if (call.type == CallType.Video) "Video call" else "Voice call",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 76.dp),
            thickness = 0.8.dp,
            color = NcTheme.extra.divider
        )
    }
}
