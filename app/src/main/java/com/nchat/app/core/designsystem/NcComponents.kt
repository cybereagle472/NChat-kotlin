package com.nchat.app.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Deterministic gradient pairs so the same contact always gets the same avatar colour. */
private val AvatarGradients: List<List<Color>> = listOf(
    listOf(Color(0xFF0B7D62), Color(0xFF20C997)),
    listOf(Color(0xFF3E93D6), Color(0xFF63C3F0)),
    listOf(Color(0xFF7B5BD6), Color(0xFFB39DF5)),
    listOf(Color(0xFFD96C3A), Color(0xFFF2A65A)),
    listOf(Color(0xFFC84B67), Color(0xFFF0809B)),
    listOf(Color(0xFF3A7D5C), Color(0xFF7CC49B)),
    listOf(Color(0xFF4B5EA8), Color(0xFF8E9BE8)),
    listOf(Color(0xFFA0703A), Color(0xFFD9B078))
)

internal fun initialsOf(name: String): String {
    val parts = name.trim().split(' ').filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    return if (parts.size == 1) {
        parts.first().take(2).uppercase()
    } else {
        (parts[0].first().toString() + parts[1].first().toString()).uppercase()
    }
}

private fun gradientFor(name: String): List<Color> {
    val index = (name.hashCode().rem(AvatarGradients.size) + AvatarGradients.size) % AvatarGradients.size
    return AvatarGradients[index]
}

/**
 * Circular avatar with initials, an optional emoji face, an active story ring and a
 * presence dot.
 */
@Composable
fun NcAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = NcSize.avatar,
    emoji: String? = null,
    showPresence: Boolean = false,
    isOnline: Boolean = false,
    hasStoryRing: Boolean = false,
    shape: Shape = CircleShape
) {
    val gradient = remember(name) { gradientFor(name) }
    val ringColors = NcTheme.extra.storyRing
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .then(
                    if (hasStoryRing) {
                        Modifier.border(
                            width = 2.dp,
                            brush = Brush.sweepGradient(ringColors + ringColors.first()),
                            shape = shape
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(if (hasStoryRing) 2.5.dp else 0.dp)
                .clip(shape)
                .background(Brush.linearGradient(gradient)),
            contentAlignment = Alignment.Center
        ) {
            if (emoji != null) {
                Text(text = emoji, fontSize = (size.value * 0.42f).sp)
            } else {
                Text(
                    text = initialsOf(name),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.36f).sp,
                    maxLines = 1
                )
            }
        }

        if (showPresence) {
            val dotSize = size * 0.3f
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(if (isOnline) NcTheme.extra.online else NcTheme.extra.offline)
                    .border(2.dp, surfaceColor, CircleShape)
            )
        }
    }
}

@Composable
fun NcUnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
    muted: Boolean = false
) {
    if (count <= 0) return
    val extra = NcTheme.extra
    val background = if (muted) extra.mutedBadge else extra.unreadBadge
    val contentColor = if (muted) Color.White else extra.unreadBadgeText
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun NcSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .padding(start = NcSpace.lg, end = NcSpace.md, top = NcSpace.md, bottom = NcSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

@Composable
fun NcEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.padding(NcSpace.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = NcSpace.lg)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = NcTheme.extra.mutedText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = NcSpace.xs, start = NcSpace.sm, end = NcSpace.sm)
        )
        if (actionLabel != null && onAction != null) {
            NcTextPillButton(
                label = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = NcSpace.lg)
            )
        }
    }
}

@Composable
fun NcTextPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = NcSpace.xl, vertical = NcSpace.sm)
        )
    }
}

@Composable
fun NcFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0
) {
    val primary = MaterialTheme.colorScheme.primary
    val containerColor by animateColorAsState(
        targetValue = if (selected) primary else MaterialTheme.colorScheme.surface,
        animationSpec = tween(220),
        label = "chipContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else NcTheme.extra.mutedText,
        animationSpec = tween(220),
        label = "chipContent"
    )
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = containerColor,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NcSpace.md, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .padding(start = NcSpace.xs + 1.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f) else primary)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** Three bouncing dots used for the "typing…" state. */
@Composable
fun NcTypingDots(
    modifier: Modifier = Modifier,
    color: Color = NcTheme.extra.typing,
    dotSize: Dp = 5.dp
) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
    ) {
        repeat(3) { index ->
            val offset by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, delayMillis = index * 110),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        translationY = -offset * dotSize.toPx() * 0.9f
                        alpha = 0.45f + offset * 0.55f
                    }
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
