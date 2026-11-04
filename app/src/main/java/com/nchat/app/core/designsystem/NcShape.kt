package com.nchat.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

internal val NcShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Spacing scale. Use these instead of magic numbers so layouts stay on rhythm. */
object NcSpace {
    val none = 0.dp
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

/** Shared component sizes. */
object NcSize {
    val rowHeight = 72.dp
    val compactRowHeight = 60.dp
    val avatar = 52.dp
    val avatarSmall = 36.dp
    val avatarLarge = 76.dp
    val topBarHeight = 60.dp
    val bottomBarHeight = 64.dp
    val iconButton = 40.dp
    val touchTarget = 48.dp
    val composerMaxHeight = 132.dp
}
