package com.nchat.app.feature.groups

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcBackdrop
import com.nchat.app.core.designsystem.NcGlassRow
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.Group

/**
 * Port of `GroupInfo.tsx`: gradient title bar, rounded group avatar with the camera
 * badge, editable name + description, the mute switch, the member list with an add
 * action and the leave-group affordance.
 */
@Composable
fun GroupInfoScreen(
    group: Group,
    friends: List<Friend>,
    selfName: String,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onAddMembers: (count: Int) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra
    var muted by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var nameDraft by remember(group.name) { mutableStateOf(group.name) }
    var pickingMembers by remember { mutableStateOf(false) }
    var confirmingLeave by remember { mutableStateOf(false) }
    var addedCount by remember { mutableStateOf(0) }

    val members = remember(friends, group.memberCount, addedCount) {
        friends.take((group.memberCount - 1 + addedCount).coerceAtLeast(0))
    }

    NcBackdrop(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(10.dp)
                    .background(Brush.linearGradient(extra.brandGradient))
                    .statusBarsPadding()
                    .padding(horizontal = NcSpace.sm, vertical = NcSpace.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "Group Info",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(modifier = Modifier.padding(top = NcSpace.xxl)) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(extra.brandGradient)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = group.avatarEmoji ?: "👥", fontSize = 40.sp)
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 8.dp, y = 8.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF8B5CF6))
                                .border(2.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CameraAlt,
                                contentDescription = "Change group photo",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NcSpace.xs),
                        modifier = Modifier.padding(top = NcSpace.md)
                    ) {
                        Text(
                            text = group.name,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = extra.textPrimary
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { renaming = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Rename group",
                                tint = Color(0xFF8B5CF6),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    if (group.description.isNotBlank()) {
                        Text(
                            text = group.description,
                            fontSize = 14.sp,
                            color = extra.textTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp, start = NcSpace.xl, end = NcSpace.xl)
                        )
                    }
                    Text(
                        text = "${group.memberCount + addedCount} members",
                        fontSize = 12.sp,
                        color = extra.textMuted,
                        modifier = Modifier.padding(top = 2.dp, bottom = NcSpace.lg)
                    )
                }

                // Mute notifications
                Row(
                    modifier = Modifier
                        .padding(horizontal = NcSpace.lg)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xB3FFFFFF))
                        .border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(16.dp))
                        .clickable { muted = !muted }
                        .padding(horizontal = NcSpace.lg, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Rounded.NotificationsOff else Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = if (muted) extra.textTertiary else Color(0xFF8B5CF6),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (muted) "Unmute notifications" else "Mute notifications",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = extra.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    NcSwitch(checked = !muted)
                }

                // Members
                Column(
                    modifier = Modifier
                        .padding(horizontal = NcSpace.lg, vertical = NcSpace.md)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xB3FFFFFF))
                        .border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NcSpace.lg, vertical = NcSpace.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${group.memberCount + addedCount} Members",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = extra.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { pickingMembers = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = Color(0xFF7C3AED),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Add",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF7C3AED)
                            )
                        }
                    }

                    MemberRow(name = "$selfName (you)", note = "Admin", isFirst = true)
                    members.forEach { friend ->
                        MemberRow(name = friend.name, note = "@${friend.handle}", isFirst = false)
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = NcSpace.lg, vertical = NcSpace.sm)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xB3FFFFFF))
                        .border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(16.dp))
                        .clickable { confirmingLeave = true }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Logout,
                            contentDescription = null,
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Leave group",
                            color = Color(0xFFDC2626),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Text(
                    text = "Group members can see each other's messages",
                    fontSize = 11.sp,
                    color = extra.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = NcSpace.xxl, top = NcSpace.sm)
                )
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = "Group name",
            initial = nameDraft,
            onDismiss = { renaming = false },
            onConfirm = { value ->
                nameDraft = value
                renaming = false
                onRename(value)
            }
        )
    }

    if (pickingMembers) {
        AddMembersDialog(
            candidates = friends.takeLast((friends.size - members.size).coerceAtLeast(0)),
            onDismiss = { pickingMembers = false },
            onConfirm = { count ->
                addedCount += count
                pickingMembers = false
                onAddMembers(count)
            }
        )
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text("Leave group?", fontWeight = FontWeight.SemiBold) },
            text = { Text("You will stop receiving messages from ${group.name}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLeave = false
                    onLeave()
                }) {
                    Text("Leave", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) {
                    Text("Cancel", color = extra.textTertiary)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun MemberRow(name: String, note: String, isFirst: Boolean) {
    val extra = NcTheme.extra
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NcSpace.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
    ) {
        NcAvatar(name = name, size = 40.dp, isOnline = isFirst, showPresence = isFirst)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = extra.textPrimary,
                maxLines = 1
            )
            Text(text = note, fontSize = 12.sp, color = extra.textTertiary)
        }
    }
}

/** Small toggle matching the reference `w-10 h-6` switch. */
@Composable
private fun NcSwitch(checked: Boolean) {
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 2.dp,
        animationSpec = tween(180),
        label = "switchKnob"
    )
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(CircleShape)
            .background(if (checked) Color(0xFF8B5CF6) else Color(0xFFCBD5E1))
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset, y = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            NcGlassRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        color = NcTheme.extra.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = draft.isNotBlank(), onClick = { onConfirm(draft.trim()) }) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = NcTheme.extra.textTertiary) }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun AddMembersDialog(
    candidates: List<Friend>,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val extra = NcTheme.extra
    var selected by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add members", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NcSpace.xs)) {
                if (candidates.isEmpty()) {
                    Text("Everyone you know is already in this group.", fontSize = 13.sp, color = extra.textTertiary)
                }
                candidates.forEach { friend ->
                    val isSelected = friend.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                selected = if (isSelected) selected - friend.id else selected + friend.id
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
                    ) {
                        NcAvatar(name = friend.name, size = 34.dp)
                        Text(
                            text = friend.name,
                            fontSize = 14.sp,
                            color = extra.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color(0xFF7C3AED),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(selected.size) }
            ) {
                Text("Add (${selected.size})", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = extra.textTertiary) }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp)
    )
}
