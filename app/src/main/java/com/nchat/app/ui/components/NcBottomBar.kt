package com.nchat.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcSize
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcUnreadBadge
import com.nchat.app.core.navigation.HomeTab

/**
 * Custom bottom navigation. Selected items lift slightly, tint to the brand colour and
 * reveal their label, matching modern messenger behaviour.
 */
@Composable
fun NcBottomBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    chatUnread: Int,
    modifier: Modifier = Modifier
) {
    val surface = MaterialTheme.colorScheme.surface
    val divider = NcTheme.extra.divider

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(divider)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(surface)
                .navigationBarsPadding()
                .height(NcSize.bottomBarHeight),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeTab.entries.forEach { tab ->
                BottomBarItem(
                    tab = tab,
                    selected = tab == selected,
                    badge = if (tab == HomeTab.Chats) chatUnread else 0,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    tab: HomeTab,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else NcTheme.extra.mutedText,
        animationSpec = tween(220),
        label = "tabTint"
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(220),
        label = "pillAlpha"
    )
    val lift by animateFloatAsState(
        targetValue = if (selected) -2f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "tabLift"
    )
    val labelSize by animateFloatAsState(
        targetValue = if (selected) 11.5f else 10.5f,
        animationSpec = tween(220),
        label = "tabLabel"
    )

    Box(
        modifier = modifier
            .height(NcSize.bottomBarHeight)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer { translationY = lift }
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f * pillAlpha))
                )
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = tint,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(22.dp)
                )
                if (badge > 0) {
                    NcUnreadBadge(
                        count = badge,
                        modifier = Modifier
                            .offset(x = 6.dp, y = (-6).dp)
                    )
                }
            }
            Text(
                text = tab.label,
                color = tint,
                fontSize = labelSize.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
