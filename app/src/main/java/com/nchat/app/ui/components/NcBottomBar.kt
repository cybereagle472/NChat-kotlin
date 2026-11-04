package com.nchat.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcGlassSurface
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.core.navigation.HomeTab

/**
 * Floating glass tab bar, a direct port of the reference markup:
 *
 * ```
 * <nav class="glass-card mx-3 mb-3 rounded-3xl px-1.5 py-2">
 *   <button class="px-2 py-2 rounded-xl ... bg-gradient-... scale-105 shadow-lg">
 * ```
 *
 * The active chip is a brand-gradient pill at scale 1.05 with white content; inactive
 * items are slate-500 and press down to 0.95. No elevation layers: a shadow drawn
 * under a rounded chip is exactly what produced the banded, uneven colouring on
 * Android, so depth comes from the glass panel and the gradient itself.
 */
@Composable
fun NcBottomBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NcGlassSurface(
        modifier = modifier
            .padding(horizontal = NcSpace.md, vertical = NcSpace.md)
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = NcSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeTab.entries.forEach { tab ->
                NavItem(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: HomeTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = rememberPressSource()
    // Tab selection has to read as instant: a short colour tween and a snappy scale,
    // no bouncy spring overshoot (that overshoot is what felt like a delayed tap).
    val tint by animateColorAsState(
        targetValue = if (selected) Color.White else NcTheme.extra.textTertiary,
        animationSpec = tween(120),
        label = "navTint"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.05f else 1f,
        animationSpec = tween(140),
        label = "navScale"
    )
    // The reference also presses the chip down to 0.95 while it is held.
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(90),
        label = "navPress"
    )

    Box(
        modifier = modifier
            // One layer scales the fill, the border and the content together, and both
            // values are read inside the block so the animation never recomposes.
            .graphicsLayer {
                val combined = scale * pressScale
                scaleX = combined
                scaleY = combined
            }
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    Brush.linearGradient(NcTheme.extra.brandGradient)
                } else {
                    Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                }
            )
            .then(
                if (selected) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = NcSpace.sm, vertical = NcSpace.sm),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = tab.label,
                color = tint,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
