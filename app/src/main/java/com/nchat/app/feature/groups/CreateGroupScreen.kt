package com.nchat.app.feature.groups

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
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcGlassRow
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.pressFeedback
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.data.model.Friend

/**
 * Port of `CreateGroup.tsx`: gradient title bar, the camera avatar slot, name and
 * description fields, a member picker and the gradient "Create Group" action.
 */
@Composable
fun CreateGroupScreen(
    friends: List<Friend>,
    onBack: () -> Unit,
    onCreate: (name: String, description: String, memberCount: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val canCreate = name.isNotBlank() && selected.isNotEmpty()

    Column(modifier = modifier.fillMaxSize().background(Color.White)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp)
                .background(Brush.linearGradient(extra.brandGradient))
                .statusBarsPadding()
                .padding(horizontal = NcSpace.sm, vertical = NcSpace.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cancel",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "New Group",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = NcSpace.sm)
            )
            Text(
                text = "${selected.size} selected",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NcSpace.md),
            verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
        ) {
            item(key = "identity") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF3E8FF))
                            .border(2.dp, Color(0xFFE9D5FF), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = "Add group photo",
                            tint = Color(0xFF9333EA),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            item(key = "name") {
                FieldCard(label = "Group name") {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 15.sp,
                            color = extra.textPrimary
                        ),
                        cursorBrush = SolidColor(extra.typing),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (name.isEmpty()) {
                                Text(
                                    text = "Enter group name",
                                    fontSize = 15.sp,
                                    color = extra.textMuted
                                )
                            }
                            inner()
                        }
                    )
                }
            }

            item(key = "description") {
                FieldCard(label = "Description (optional)") {
                    BasicTextField(
                        value = description,
                        onValueChange = { description = it },
                        maxLines = 3,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 15.sp,
                            color = extra.textPrimary
                        ),
                        cursorBrush = SolidColor(extra.typing),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (description.isEmpty()) {
                                Text(
                                    text = "What is this group about?",
                                    fontSize = 15.sp,
                                    color = extra.textMuted
                                )
                            }
                            inner()
                        }
                    )
                }
            }

            item(key = "members-header") {
                Text(
                    text = "ADD MEMBERS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = extra.textMuted,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(start = NcSpace.xs, top = NcSpace.sm)
                )
            }

            items(items = friends, key = { it.id }) { friend ->
                val isSelected = friend.id in selected
                NcGlassRow(
                    onClick = {
                        selected = if (isSelected) selected - friend.id else selected + friend.id
                    }
                ) {
                    NcAvatar(
                        name = friend.name,
                        size = 44.dp,
                        isOnline = friend.isOnline,
                        showPresence = true
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = friend.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = extra.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "@${friend.handle}",
                            fontSize = 12.sp,
                            color = extra.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    SelectionCircle(selected = isSelected)
                }
            }

            item(key = "create") {
                CreateGroupButton(enabled = canCreate) {
                    onCreate(name.trim(), description.trim(), selected.size)
                }
            }
        }
    }
}

@Composable
private fun FieldCard(label: String, content: @Composable () -> Unit) {
    val extra = NcTheme.extra
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF8FAFC))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
            .padding(horizontal = NcSpace.lg, vertical = NcSpace.md)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = extra.textMuted,
            letterSpacing = 0.5.sp
        )
        Box(modifier = Modifier.padding(top = 6.dp)) { content() }
    }
}

@Composable
private fun SelectionCircle(selected: Boolean) {
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

@Composable
private fun CreateGroupButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = rememberPressSource()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NcSpace.md)
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) {
                    Brush.linearGradient(NcTheme.extra.brandGradient)
                } else {
                    Brush.linearGradient(listOf(Color(0xFFE2E8F0), Color(0xFFE2E8F0)))
                }
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Create Group",
            color = if (enabled) Color.White else Color(0xFF94A3B8),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
