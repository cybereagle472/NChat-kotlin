package com.nchat.app.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcGlassRow
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.pressFeedback
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.data.model.Chat

/**
 * Port of the `ForwardDialog` from `MessageActions.tsx`: a full-height picker with a
 * search field, multi-select chat rows and a gradient send action.
 */
@Composable
fun ForwardDialog(
    chats: List<Chat>,
    onDismiss: () -> Unit,
    onForward: (targets: List<String>) -> Unit
) {
    val extra = NcTheme.extra
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val filtered = remember(chats, query) {
        if (query.isBlank()) {
            chats
        } else {
            chats.filter { it.title.contains(query, ignoreCase = true) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = NcSpace.sm, vertical = NcSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = extra.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "Forward to",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = extra.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${selected.size} selected",
                    fontSize = 12.sp,
                    color = extra.textTertiary
                )
            }

            NcGlassRow(
                modifier = Modifier.padding(horizontal = NcSpace.md),
                contentPadding = PaddingValues(horizontal = NcSpace.md, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = extra.textMuted,
                    modifier = Modifier.size(18.dp)
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search chats",
                            fontSize = 14.sp,
                            color = extra.textMuted
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = extra.textPrimary),
                        cursorBrush = SolidColor(extra.typing),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(NcSpace.md),
                verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                items(items = filtered, key = { it.id }) { chat ->
                    val isSelected = chat.id in selected
                    NcGlassRow(
                        onClick = {
                            selected = if (isSelected) selected - chat.id else selected + chat.id
                        }
                    ) {
                        NcAvatar(
                            name = chat.title,
                            emoji = chat.avatarEmoji,
                            size = 44.dp,
                            isOnline = chat.isOnline,
                            showPresence = !chat.isGroup
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chat.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = extra.textPrimary,
                                maxLines = 1
                            )
                            Text(
                                text = if (chat.isGroup) "Group" else chat.lastMessage,
                                fontSize = 12.sp,
                                color = extra.textTertiary,
                                maxLines = 1
                            )
                        }
                        SelectionDot(selected = isSelected)
                    }
                }
            }

            val interaction = rememberPressSource()
            val enabled = selected.isNotEmpty()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(NcSpace.md)
                    .pressFeedback(interaction)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (enabled) {
                            Brush.linearGradient(extra.brandGradient)
                        } else {
                            Brush.linearGradient(listOf(Color(0xFFE2E8F0), Color(0xFFE2E8F0)))
                        }
                    )
                    .clickable(
                        enabled = enabled,
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onForward(selected.toList()) }
                    )
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (enabled) "Forward to ${selected.size}" else "Select chats",
                    color = if (enabled) Color.White else Color(0xFF94A3B8),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SelectionDot(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (selected) Brush.linearGradient(NcTheme.extra.brandGradient)
                else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .border(
                width = 1.5.dp,
                color = if (selected) Color.Transparent else Color(0xFFCBD5E1),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
