package com.nchat.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Radii mirrored from the reference stylesheet (rounded-xl / 2xl / 3xl, 16dp cards). */
internal val NcShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

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

object NcSize {
    val headerAction = 40.dp
    val rowAvatar = 56.dp
    val rowAvatarSmall = 44.dp
    val rowRadius = 16.dp
    val navRadius = 24.dp
    val navHeight = 62.dp
    val composerButton = 36.dp
    val touchTarget = 48.dp
}

/** Corner treatment for chat bubbles: 16dp with a 6dp tail on the sender side. */
val BubbleShapeMine = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 16.dp,
    bottomEnd = 6.dp,
    bottomStart = 16.dp
)

val BubbleShapeTheirs = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 16.dp,
    bottomEnd = 16.dp,
    bottomStart = 6.dp
)
