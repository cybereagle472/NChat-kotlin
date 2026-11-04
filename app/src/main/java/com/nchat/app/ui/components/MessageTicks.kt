package com.nchat.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.data.model.MessageStatus

/**
 * Delivery state indicator: clock while sending, single tick when sent, double tick when
 * delivered and blue double tick when read.
 */
@Composable
fun MessageTicks(
    status: MessageStatus?,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    relaxedTint: Color = NcTheme.extra.mutedText
) {
    val tint = when (status) {
        MessageStatus.Read -> MaterialTheme.colorScheme.tertiary
        MessageStatus.Sending -> relaxedTint.copy(alpha = 0.55f)
        else -> relaxedTint
    }
    val icon = when (status) {
        MessageStatus.Sending -> Icons.Rounded.Schedule
        MessageStatus.Sent -> Icons.Rounded.Check
        MessageStatus.Delivered, MessageStatus.Read -> Icons.Rounded.DoneAll
        null -> Icons.Rounded.Check
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size)
    )
}
