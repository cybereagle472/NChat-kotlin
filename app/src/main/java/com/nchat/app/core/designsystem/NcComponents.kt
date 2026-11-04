package com.nchat.app.core.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** First letter of a contact name, matching the reference avatar treatment. */
internal fun initialOf(name: String): String =
    name.trim().take(1).uppercase().ifBlank { "?" }

/** The translucent "glass-card" surface used by nav bars, sheets and empty states. */
@Composable
fun NcGlassSurface(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(20.dp),
    fill: Color = NcTheme.extra.glassFill,
    borderColor: Color = NcTheme.extra.glassBorder,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .shadow(8.dp, shape, ambientColor = NcTheme.extra.glassShadow, spotColor = NcTheme.extra.glassShadow)
            .clip(shape)
            .background(fill)
            .border(1.dp, borderColor, shape)
    ) {
        content()
    }
}

/**
 * Circular avatar with the brand gradient and an optional presence dot, exactly like
 * the chat list rows and chat header in the reference app.
 */
@Composable
fun NcAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = NcSize.rowAvatar,
    emoji: String? = null,
    isOnline: Boolean = false,
    showPresence: Boolean = false,
    onGradient: Boolean = false
) {
    val extra = NcTheme.extra
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    if (onGradient) {
                        Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x33FFFFFF)))
                    } else {
                        Brush.linearGradient(extra.brandGradient)
                    }
                )
                .then(if (onGradient) Modifier.border(2.dp, Color(0x4DFFFFFF), CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (emoji != null) {
                Text(text = emoji, fontSize = (size.value * 0.4f).sp)
            } else {
                Text(
                    text = initialOf(name),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.36f).sp,
                    maxLines = 1
                )
            }
        }
        if (showPresence && isOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.25f)
                    .clip(CircleShape)
                    .background(extra.online)
                    .border(2.5.dp, extra.onlineRing, CircleShape)
            )
        }
    }
}

/** Gradient unread counter (min 22dp tall, 11dp radius). */
@Composable
fun NcUnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.linearGradient(NcTheme.extra.unreadBadgeGradient))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Three bouncing dots used by the "typing" indicator. */
@Composable
fun NcTypingDots(
    modifier: Modifier = Modifier,
    color: Color = NcTheme.extra.typing,
    dotSize: Dp = 5.dp
) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1400, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer { translationY = -lift * dotSize.toPx() * 0.8f }
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/**
 * Full-screen violet/purple/fuchsia backdrop with the slow drifting blobs from the
 * reference `animate-blob` layers.
 */
@Composable
fun NcBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val extra = NcTheme.extra
    val transition = rememberInfiniteTransition(label = "blobs")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.linearGradient(extra.bodyGradient))
    ) {
        Blob(
            color = NcPalette.Violet300,
            size = 260.dp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .graphicsLayer {
                    translationX = -60.dp.toPx() + drift * 36.dp.toPx()
                    translationY = 40.dp.toPx() - drift * 48.dp.toPx()
                }
        )
        Blob(
            color = NcPalette.Fuchsia500,
            size = 220.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .graphicsLayer {
                    translationX = 70.dp.toPx() - drift * 40.dp.toPx()
                    translationY = -30.dp.toPx() + drift * 30.dp.toPx()
                }
        )
        Blob(
            color = NcPalette.Violet500,
            size = 180.dp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX = -40.dp.toPx()
                    translationY = drift * 60.dp.toPx() - 40.dp.toPx()
                }
        )
        content()
    }
}

@Composable
private fun Blob(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f))
                )
            )
    )
}

/** Glass empty state with the gradient emoji tile from the reference chat list. */
@Composable
fun NcGlassEmptyState(
    emoji: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    NcGlassSurface(
        modifier = modifier
            .padding(NcSpace.md)
            .fillMaxSize(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(NcSpace.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .shadow(4.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(NcTheme.extra.brandGradient)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, fontSize = 30.sp)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NcTheme.extra.textSecondary,
                modifier = Modifier.padding(top = NcSpace.lg)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = NcTheme.extra.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = NcSpace.xs)
            )
        }
    }
}
