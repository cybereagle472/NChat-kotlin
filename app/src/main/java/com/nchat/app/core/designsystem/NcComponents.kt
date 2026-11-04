package com.nchat.app.core.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
            .clip(shape)
            .background(fill)
            .border(1.dp, borderColor, shape)
    ) {
        content()
    }
}

/**
 * The list row from the reference `.chat-list-item`: translucent white fill, a
 * hairline highlight border, and the web `:active { scale(.98) }` press feel.
 *
 * Deliberately free of elevation and ripples: on device a shadow layer drawn
 * under a translucent fill composites as an opaque white plate, and the ripple
 * overlay smeared bright bands across the glass cards when scrolling.
 */
@Composable
fun NcGlassRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(NcSize.rowRadius),
    fill: Color = NcTheme.extra.rowFill,
    borderColor: Color = NcTheme.extra.rowBorder,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = NcSpace.md),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(NcSpace.md),
    content: @Composable RowScope.() -> Unit
) {
    val interaction = rememberPressSource()
    val pressModifier = if (onClick != null) Modifier.pressFeedback(interaction) else Modifier
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(pressModifier)
            .clip(shape)
            .background(fill)
            .border(1.dp, borderColor, shape)
            .then(clickModifier)
            .padding(contentPadding),
        verticalAlignment = verticalAlignment,
        horizontalArrangement = horizontalArrangement,
        content = content
    )
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.linearGradient(extra.bodyGradient))
    ) {
        // The three ambient blobs are painted once, in a single draw pass. They used to
        // be composables driven by an infinite transition, which re-rasterised three
        // large radial gradients every frame and made every screen feel sluggish.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val violet300 = NcPalette.Violet300
            val fuchsia500 = NcPalette.Fuchsia500
            val violet500 = NcPalette.Violet500

            fun blob(color: Color, radius: Float, center: Offset) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.5f), color.copy(alpha = 0f)),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }

            blob(violet300, 150.dp.toPx(), Offset(-24.dp.toPx(), 70.dp.toPx()))
            blob(
                fuchsia500,
                125.dp.toPx(),
                Offset(size.width + 34.dp.toPx(), size.height - 40.dp.toPx())
            )
            blob(violet500, 105.dp.toPx(), Offset(-16.dp.toPx(), size.height * 0.46f))
        }
        content()
    }
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
