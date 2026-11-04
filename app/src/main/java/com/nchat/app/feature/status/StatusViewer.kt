package com.nchat.app.feature.status

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.data.model.StatusUpdate

private const val STATUS_DURATION_MS = 4_500

/** Story backgrounds, cycled deterministically so a status keeps its colour. */
private val StatusBackdrops = listOf(
    listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)),
    listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
    listOf(Color(0xFFEC4899), Color(0xFFF43F5E)),
    listOf(Color(0xFF0EA5E9), Color(0xFF6366F1)),
    listOf(Color(0xFFF59E0B), Color(0xFFEC4899))
)

/**
 * Full-screen story viewer, ported from the `Status.tsx` overlay: segmented progress
 * bars, the author header, tap-right/tap-left navigation and the 24sp caption on a
 * brand backdrop.
 */
@Composable
fun StatusViewer(
    statuses: List<StatusUpdate>,
    startIndex: Int,
    onViewed: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (statuses.isEmpty()) return
    var index by remember {
        mutableIntStateOf(startIndex.coerceIn(0, statuses.lastIndex))
    }
    val progress = remember { Animatable(0f) }
    val current = statuses.getOrNull(index) ?: return

    LaunchedEffect(index, statuses.size) {
        val status = statuses.getOrNull(index) ?: return@LaunchedEffect
        onViewed(status.id)
        progress.snapTo(0f)
        progress.animateTo(1f, tween(STATUS_DURATION_MS, easing = LinearEasing))
        if (index < statuses.lastIndex) index += 1 else onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(StatusBackdrops[index % StatusBackdrops.size]))
    ) {
        // Tap zones: left half goes back, right half advances (reference uses thirds).
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.4f)
                    .clickable {
                        if (index > 0) index -= 1 else onDismiss()
                    }
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.6f)
                    .clickable {
                        if (index < statuses.lastIndex) index += 1 else onDismiss()
                    }
            )
        }

        Text(
            text = current.caption,
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = NcSpace.xxxl)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = NcSpace.sm, vertical = NcSpace.xs),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            statuses.forEachIndexed { segmentIndex, _ ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    val fill = when {
                        segmentIndex < index -> 1f
                        segmentIndex == index -> progress.value
                        else -> 0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fill)
                            .fillMaxHeight()
                            .background(Color.White)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = NcSpace.md, start = NcSpace.md, end = NcSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.2f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Close status",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            NcAvatar(
                name = current.authorName,
                emoji = current.avatarEmoji,
                size = 40.dp,
                onGradient = true
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (current.isMine) "My Status" else current.authorName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = relativeTime(current.timestamp),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

private fun relativeTime(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 1_440L -> "${minutes / 60L} h ago"
        else -> "${minutes / 1_440L} d ago"
    }
}
