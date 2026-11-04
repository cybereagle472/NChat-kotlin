package com.nchat.app.feature.history

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneMissed
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.pressFeedback
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.CallRecord

private enum class HistoryFilter(val label: String) {
    All("All"),
    Voice("Voice"),
    Video("Video"),
    Missed("Missed")
}

private val Violet = Color(0xFF7C3AED)
private val Slate800 = Color(0xFF1E293B)
private val Slate600 = Color(0xFF475569)
private val Slate500 = Color(0xFF64748B)
private val Blue600 = Color(0xFF2563EB)
private val Green600 = Color(0xFF16A34A)
private val Red500 = Color(0xFFEF4444)
private val Red600 = Color(0xFFDC2626)

/**
 * `History.tsx` — the call log: gradient header with the All / Voice / Video / Missed
 * filter pills, then one glass row per call with direction icon, type pill, duration,
 * timestamp and the voice / video / message shortcuts.
 */
@Composable
fun HistoryScreen(
    calls: List<CallRecord>,
    listState: LazyListState,
    onCall: (CallRecord, Boolean) -> Unit,
    onMessage: (CallRecord) -> Unit,
    onViewProfile: (CallRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    var filter by remember { mutableStateOf(HistoryFilter.All) }

    val visible = remember(calls, filter) {
        calls.filter { call ->
            when (filter) {
                HistoryFilter.All -> true
                HistoryFilter.Missed -> call.missed
                HistoryFilter.Voice -> !call.isVideo
                HistoryFilter.Video -> call.isVideo
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        HistoryHeader(filter = filter, onSelect = { filter = it })

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NcSpace.md),
            verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
        ) {
            if (visible.isEmpty()) {
                item(key = "history-empty") {
                    EmptyHistory()
                }
            } else {
                items(
                    items = visible,
                    key = { it.id }
                ) { call ->
                    HistoryRow(
                            call = call,
                            onOpenProfile = { onViewProfile(call) },
                            onVoice = { onCall(call, false) },
                            onVideo = { onCall(call, true) },
                        onMessage = { onMessage(call) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryHeader(filter: HistoryFilter, onSelect: (HistoryFilter) -> Unit) {
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
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Call History",
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
                .padding(top = NcSpace.md)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HistoryFilter.values().forEach { entry ->
                val active = entry == filter
                val background by animateColorAsState(
                    targetValue = if (active) Color.White else Color(0x33FFFFFF),
                    animationSpec = tween(300),
                    label = "historyFilterBg"
                )
                val foreground by animateColorAsState(
                    targetValue = if (active) Violet else Color.White,
                    animationSpec = tween(300),
                    label = "historyFilterFg"
                )
                Box(
                    modifier = Modifier
                        .height(36.dp)
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
                        .clickable { onSelect(entry) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = entry.label,
                        color = foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    call: CallRecord,
    onOpenProfile: () -> Unit,
    onVoice: () -> Unit,
    onVideo: () -> Unit,
    onMessage: () -> Unit
) {
    val interaction = rememberPressSource()
    val duration = call.durationLabel?.takeIf { it.isNotBlank() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xB3FFFFFF))
            .border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onOpenProfile)
            .padding(horizontal = NcSpace.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (call.missed) {
                        Brush.linearGradient(listOf(Color(0xFFF87171), Color(0xFFFB7185)))
                    } else {
                        Brush.linearGradient(NcTheme.extra.brandGradient)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            AvatarInitial(call)
        }

        // Fixed three-line hierarchy: name, direction + type, then time + duration.
        // Every line is single-line so a narrow card can never stack characters.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = call.name,
                color = if (call.missed) Red600 else Slate800,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = directionIcon(call),
                    contentDescription = null,
                    tint = directionColor(call),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = directionLabel(call),
                    color = Slate600,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    // Only the direction label yields space; the pill keeps its size.
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (call.isVideo) {
                    TypePill(
                        label = "Video",
                        icon = Icons.Rounded.Videocam,
                        foreground = Violet,
                        background = Color(0xFFEDE9FE)
                    )
                } else {
                    TypePill(
                        label = "Voice",
                        icon = Icons.Rounded.Call,
                        foreground = Blue600,
                        background = Color(0xFFDBEAFE)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = TimeFormat.historyTimestamp(call.timestamp),
                    color = Slate500,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false
                )
                if (duration != null) {
                    Text(
                        text = "·",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Text(
                        text = duration,
                        color = Violet,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HistoryAction(Icons.Rounded.Call, "Voice call", onVoice)
            HistoryAction(Icons.Rounded.Videocam, "Video call", onVideo)
            HistoryAction(Icons.Rounded.ChatBubble, "Message", onMessage)
        }
    }
}

@Composable
private fun AvatarInitial(call: CallRecord) {
    Text(
        text = if (call.isGroup) "👥" else call.name.trim().take(1).uppercase().ifBlank { "?" },
        color = Color.White,
        fontSize = if (call.isGroup) 17.sp else 15.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

@Composable
private fun TypePill(
    label: String,
    icon: ImageVector,
    foreground: Color,
    background: Color
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = label,
            color = foreground,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun HistoryAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = rememberPressSource()
    Box(
        modifier = Modifier
            .size(30.dp)
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(9.dp))
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

@Composable
private fun EmptyHistory() {
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
            Text(text = "📞", fontSize = 30.sp)
        }
        Text(
            text = "No call history",
            color = Slate600,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = "Your calls will appear here",
            color = Slate500,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun directionIcon(call: CallRecord): ImageVector = when {
    call.missed -> Icons.Rounded.PhoneMissed
    call.isOutgoing -> Icons.Rounded.CallMade
    else -> Icons.Rounded.CallReceived
}

private fun directionColor(call: CallRecord): Color = when {
    call.missed -> Red500
    call.isOutgoing -> Violet
    else -> Green600
}

private fun directionLabel(call: CallRecord): String = when {
    call.missed -> "Missed"
    call.isOutgoing -> "Outgoing"
    else -> "Incoming"
}
