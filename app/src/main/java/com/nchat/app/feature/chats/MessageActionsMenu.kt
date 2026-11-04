package com.nchat.app.feature.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Forward
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.rememberPressSource

/** Quick reactions from the reference `MessageActions.tsx`. */
private val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** The full 32-emoji palette from `GroupMessageActions.tsx`, shown 8 per row. */
private val AllReactions = listOf(
    "👍", "👎", "❤️", "🧡", "💛", "💚", "💙", "💜",
    "😀", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎",
    "😮", "😯", "😲", "😱", "😢", "😭", "😤", "😡",
    "🙏", "👏", "🎉", "🔥", "💯", "✨", "💪", "🤝"
)

/**
 * The long-press message menu, ported from `MessageActions.tsx`: a black/30 scrim over
 * a blurred page, then a white 16dp-radius card with a quick-reaction strip on top and
 * the action list below it. It is anchored to the pressed bubble and clamped so it can
 * never leave the screen — the reference does the same with `max-w-[min(15.5rem,…)]`.
 */
@Composable
fun MessageActionMenu(
    anchor: Rect?,
    showCopy: Boolean,
    showReply: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    reactedEmojis: Set<String> = emptySet(),
    onShowAllReactions: () -> Unit = {}
) {
    val extra = NcTheme.extra
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val cardWidth = 248.dp
            val estimatedHeight = 178.dp

            val anchorCenterX = anchor?.let { with(density) { it.center.x.toDp() } } ?: (maxWidth / 2)
            val anchorTop = anchor?.let { with(density) { it.top.toDp() } }
            val anchorBottom = anchor?.let { with(density) { it.bottom.toDp() } }

            val placeAbove = anchorBottom != null && anchorBottom > estimatedHeight + 24.dp
            val rawY = if (placeAbove) {
                (anchorTop ?: 0.dp) - estimatedHeight - 6.dp
            } else {
                (anchorBottom ?: (maxHeight / 2)) + 6.dp
            }
            val y = rawY.coerceIn(NcSpace.sm, (maxHeight - estimatedHeight - NcSpace.sm).coerceAtLeast(NcSpace.sm))
            val x = (anchorCenterX - cardWidth / 2)
                .coerceIn(NcSpace.sm, (maxWidth - cardWidth - NcSpace.sm).coerceAtLeast(NcSpace.sm))

            // Scrim: bg-black/30 + backdrop-blur-sm in the reference.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            AnimatedVisibility(
                visible = appeared,
                enter = fadeIn(tween(120)) + scaleIn(tween(220), initialScale = 0.9f),
                modifier = Modifier
                    .offset(x, y)
                    .width(cardWidth)
            ) {
                Column(
                    modifier = Modifier
                        .shadow(16.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0x14000000), RoundedCornerShape(16.dp))
                ) {
                    ReactionStrip(
                        onReact = onReact,
                        reactedEmojis = reactedEmojis,
                        onShowAllReactions = onShowAllReactions
                    )
                    HorizontalDivider(thickness = 1.dp, color = Color(0xFFF1F5F9))
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        if (showReply) {
                            MenuAction(Icons.Rounded.Reply, "Reply", extra.textSecondary, onClick = onReply)
                        }
                        MenuAction(Icons.Rounded.Forward, "Forward", extra.textSecondary, onClick = onForward)
                        if (showCopy) {
                            MenuAction(Icons.Rounded.ContentCopy, "Copy", extra.textSecondary, onClick = onCopy)
                        }
                        MenuAction(
                            icon = Icons.Rounded.Delete,
                            label = "Delete",
                            tint = Color(0xFFDC2626),
                            destructive = true,
                            onClick = onDelete
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionStrip(
    onReact: (String) -> Unit,
    reactedEmojis: Set<String>,
    onShowAllReactions: () -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QuickReactions.forEach { emoji ->
            val interaction = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (emoji in reactedEmojis) Color(0xFFEDE9FE) else Color.Transparent
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onReact(emoji) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, fontSize = 18.sp)
            }
        }

        // The reference caps the quick strip and puts the rest behind a smiley button.
        val interaction = rememberPressSource()
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onShowAllReactions
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Mood,
                contentDescription = "More reactions",
                tint = NcTheme.extra.textTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * The "All Reactions" bottom sheet from `GroupMessageActions.tsx`: scrim, rounded white
 * sheet sliding up, a Reactions header with a close button and the 8-column emoji grid.
 */
@Composable
fun ReactionPickerSheet(
    reactedEmojis: Set<String>,
    onReact: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val offset by animateFloatAsState(
        targetValue = if (appeared) 0f else 40f,
        animationSpec = tween(240),
        label = "reactionsOffset"
    )
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(180),
        label = "reactionsAlpha"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f * alpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = offset.dp.toPx()
                        this.alpha = alpha
                    }
                    .shadow(18.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Color.White)
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = NcSpace.lg, end = NcSpace.sm, top = NcSpace.md, bottom = NcSpace.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Reactions",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NcTheme.extra.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close reactions",
                            tint = NcTheme.extra.textTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = Color(0xFFF1F5F9))

                Column(modifier = Modifier.padding(NcSpace.md)) {
                    AllReactions.chunked(8).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            row.forEach { emoji ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (emoji in reactedEmojis) {
                                                Color(0xFFEDE9FE)
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .clickable { onReact(emoji) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = emoji, fontSize = 22.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = rememberPressSource()
    val extra = NcTheme.extra
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (destructive) tint else extra.textTertiary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}
