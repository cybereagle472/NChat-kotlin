package com.nchat.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcBackdrop
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcToggle
import com.nchat.app.core.designsystem.pressFeedback
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.data.repository.NotificationSettings
import com.nchat.app.data.repository.PrivacySettings

private val PaneTitle = Color(0xFF1E293B)
private val PaneBody = Color(0xFF64748B)
private val PaneValue = Color(0xFF7C3AED)

/** Shared chrome for the settings sub-pages: gradient header with a back pill + list body. */
@Composable
private fun SettingsPaneScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    NcBackdrop(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(NcTheme.extra.brandGradient))
                    .statusBarsPadding()
                    .padding(horizontal = NcSpace.md, vertical = NcSpace.md)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val interaction = rememberPressSource()
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .pressFeedback(interaction)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = onBack
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = NcSpace.md)
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            color = Color(0xCCFFFFFF),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NcSpace.md),
                verticalArrangement = Arrangement.spacedBy(NcSpace.sm),
                content = content
            )
        }
    }
}

/** White glass card that groups related rows. */
@Composable
private fun PaneSection(title: String? = null, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                color = PaneBody,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(start = NcSpace.xs, top = NcSpace.xs, bottom = 6.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xCCFFFFFF))
                .border(1.dp, Color(0xCCFFFFFF), RoundedCornerShape(16.dp))
        ) {
            content()
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    last: Boolean = false
) {
    val interaction = rememberPressSource()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier
                            .pressFeedback(interaction, pressedScale = 0.99f)
                            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = PaneTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = PaneBody,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    color = PaneValue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Color(0xFFCBD5E1),
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(18.dp)
                )
            }
        }
        if (!last) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 14.dp),
                thickness = 1.dp,
                color = Color(0xFFF1F5F9)
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    last: Boolean = false
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = PaneTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = PaneBody,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(modifier = Modifier.size(NcSpace.sm))
            NcToggle(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (!last) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 14.dp),
                thickness = 1.dp,
                color = Color(0xFFF1F5F9)
            )
        }
    }
}

/** Settings ▸ Privacy: who can see what, plus the two behaviour switches. */
@Composable
fun PrivacyPane(
    settings: PrivacySettings,
    onBack: () -> Unit,
    onLastSeenChange: () -> Unit,
    onProfilePhotoChange: () -> Unit,
    onReadReceiptsChange: (Boolean) -> Unit,
    onOnlineStatusChange: (Boolean) -> Unit
) {
    SettingsPaneScaffold(
        title = "Privacy",
        subtitle = "Who can see my info",
        onBack = onBack
    ) {
        item(key = "visibility") {
            PaneSection(title = "Who can see my info") {
                SettingRow(
                    title = "Last seen",
                    subtitle = "When you were last online",
                    value = settings.lastSeen.label,
                    onClick = onLastSeenChange
                )
                SettingRow(
                    title = "Profile photo",
                    subtitle = "Your avatar in search results",
                    value = settings.profilePhoto.label,
                    onClick = onProfilePhotoChange,
                    last = true
                )
            }
        }

        item(key = "other") {
            PaneSection(title = "Other") {
                SwitchRow(
                    title = "Read receipts",
                    subtitle = "Show blue ticks when you read a message",
                    checked = settings.readReceipts,
                    onCheckedChange = onReadReceiptsChange
                )
                SwitchRow(
                    title = "Online status",
                    subtitle = "Let friends see when you are active",
                    checked = settings.onlineStatus,
                    onCheckedChange = onOnlineStatusChange,
                    last = true
                )
            }
        }

        item(key = "privacy-footer") {
            Text(
                text = "Changing these settings affects what other people see about you. " +
                    "Existing chats keep their own history.",
                color = PaneBody,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = NcSpace.xs, vertical = NcSpace.xs)
            )
        }
    }
}

/** Settings ▸ Notifications: the five switches from the reference notification pane. */
@Composable
fun NotificationsPane(
    settings: NotificationSettings,
    onBack: () -> Unit,
    onMessagesChange: (Boolean) -> Unit,
    onCallsChange: (Boolean) -> Unit,
    onPreviewChange: (Boolean) -> Unit,
    onSoundChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit
) {
    SettingsPaneScaffold(
        title = "Notifications",
        subtitle = "Messages, calls, sounds",
        onBack = onBack
    ) {
        item(key = "messages") {
            PaneSection(title = "Message notifications") {
                SwitchRow(
                    title = "Messages",
                    subtitle = "Alert me for new messages",
                    checked = settings.messages,
                    onCheckedChange = onMessagesChange
                )
                SwitchRow(
                    title = "Show preview",
                    subtitle = "Include the message text in the alert",
                    checked = settings.preview,
                    onCheckedChange = onPreviewChange,
                    last = true
                )
            }
        }

        item(key = "calls") {
            PaneSection(title = "Call notifications") {
                SwitchRow(
                    title = "Calls",
                    subtitle = "Ring for incoming voice and video calls",
                    checked = settings.calls,
                    onCheckedChange = onCallsChange,
                    last = true
                )
            }
        }

        item(key = "sound") {
            PaneSection(title = "Sound") {
                SwitchRow(
                    title = "Sound",
                    subtitle = "Play a tone for new messages",
                    checked = settings.sound,
                    onCheckedChange = onSoundChange
                )
                SwitchRow(
                    title = "Vibrate",
                    subtitle = "Buzz when a message arrives",
                    checked = settings.vibrate,
                    onCheckedChange = onVibrateChange,
                    last = true
                )
            }
        }
    }
}

/** Settings ▸ Blocked users: everyone the user has blocked, with a one-tap unblock. */
@Composable
fun BlockedUsersPane(
    blockedNames: List<String>,
    onBack: () -> Unit,
    onUnblock: (String) -> Unit
) {
    SettingsPaneScaffold(
        title = "Blocked Users",
        subtitle = "Manage blocked contacts",
        onBack = onBack
    ) {
        if (blockedNames.isEmpty()) {
            item(key = "blocked-empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x99FFFFFF))
                        .border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(16.dp))
                        .padding(vertical = 40.dp, horizontal = NcSpace.lg),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF3E8FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Block,
                            contentDescription = null,
                            tint = Color(0xFF9333EA),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "No blocked users",
                        color = PaneTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = NcSpace.md)
                    )
                    Text(
                        text = "People you block can no longer message or call you",
                        color = PaneBody,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            item(key = "blocked-list") {
                PaneSection {
                    blockedNames.forEachIndexed { index, name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NcAvatar(name = name, size = 40.dp)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = NcSpace.md)
                            ) {
                                Text(
                                    text = name,
                                    color = PaneTitle,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Blocked",
                                    color = PaneBody,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            Text(
                                text = "Unblock",
                                color = PaneColorAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onUnblock(name) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        if (index != blockedNames.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp),
                                thickness = 1.dp,
                                color = Color(0xFFF1F5F9)
                            )
                        }
                    }
                }
            }

            item(key = "blocked-footer") {
                Spacer(modifier = Modifier.height(NcSpace.xs))
                Text(
                    text = "Blocked contacts cannot call you or see your status updates.",
                    color = PaneBody,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = NcSpace.xs)
                )
            }
        }
    }
}

private val PaneColorAccent = Color(0xFF7C3AED)
