package com.nchat.app.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * In a conversation, where messages this phone has no key for would stand: how many there
 * are, why they cannot be shown, and the way to a backup. One of these takes the place of what
 * used to be a bubble with the same sentence for every such message (see [TimelineItem.Locked]).
 *
 * It is a notice and not a message: it has no sender, cannot be selected, replied to or
 * forwarded, and [onRestore] is the one thing it does.
 */
@Composable
internal fun LockedNotice(count: Int, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xF2FFFFFF))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "\uD83D\uDD12 " + RestoreText.lockedTitle(count),
                color = Color(0xFF1E293B),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = RestoreText.lockedBody(count),
                color = Color(0xFF64748B),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = RestoreText.RESTORE,
                color = Color(0xFF7C3AED),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onRestore)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
