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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block as BlockIcon
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nchat.app.BuildConfig
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcBackdrop
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.pressFeedback
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.data.ServiceLocator
import com.nchat.app.data.model.UserProfile

/**
 * 1:1 port of the reference `Settings.tsx` main section: a brand-gradient header
 * that carries the profile card, then bright `rgba(255,255,255,.8)` glass
 * sections with pastel icon tiles. Everything sits on [NcBackdrop] so the page
 * keeps the light violet body gradient instead of the window background.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = ServiceLocator.profile

    NcBackdrop(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsHeader(profile = profile, onBack = onBack)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = NcSpace.md, vertical = NcSpace.md)
            ) {
                item(key = "account") {
                    SettingsSection {
                        SettingsMenuItem(MenuTone.Purple, Icons.Rounded.Person, "Account", "Profile, username, email")
                        SettingsMenuItem(MenuTone.Green, Icons.Rounded.Lock, "Privacy", "Last seen, profile photo")
                        SettingsMenuItem(MenuTone.Orange, Icons.Rounded.Notifications, "Notifications", "Messages, calls, sounds")
                        SettingsMenuItem(MenuTone.Red, BlockIcon, "Blocked Users", "Manage blocked contacts", last = true)
                    }
                }

                item(key = "chats") {
                    SettingsSection {
                        SettingsMenuItem(MenuTone.Blue, Icons.Rounded.ChatBubbleOutline, "Chats", "Theme, wallpapers, chat history")
                        SettingsMenuItem(MenuTone.Teal, Icons.Rounded.Palette, "Appearance", "Theme, dark mode")
                        SettingsMenuItem(MenuTone.Indigo, Icons.Rounded.Language, "Language", "English", last = true)
                    }
                }

                item(key = "support") {
                    SettingsSection {
                        SettingsMenuItem(MenuTone.Cyan, Icons.Rounded.HelpOutline, "Help", "FAQ, contact us, privacy policy")
                        SettingsMenuItem(
                            tone = MenuTone.Gray,
                            icon = Icons.Rounded.Info,
                            title = "About",
                            subtitle = "Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}"
                        )
                        SettingsMenuItem(
                            tone = MenuTone.Indigo,
                            icon = Icons.Rounded.Refresh,
                            title = "Check for updates",
                            subtitle = if (state.checking) "Checking…" else "Version ${BuildConfig.VERSION_NAME}",
                            onClick = { viewModel.checkForUpdates(context) },
                            trailing = {
                                if (state.checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = NcTheme.extra.textPrimary
                                    )
                                }
                            },
                            last = true
                        )
                    }
                }

                item(key = "footer") {
                    Text(
                        text = "NChat v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                        fontSize = 12.sp,
                        color = NcTheme.extra.textMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = NcSpace.lg, bottom = NcSpace.xxl),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    val info = state.info
    if (state.dialogVisible && info != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("NChat ${info.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NcSpace.sm)) {
                    Text("A new build is ready to install.")
                    Text(
                        text = info.notes,
                        fontSize = 14.sp,
                        color = NcTheme.extra.textTertiary
                    )
                    if (state.installing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.installing,
                    onClick = { viewModel.downloadAndInstall(context) }
                ) {
                    Text(if (state.installing) "Please wait" else "Download and install")
                }
            },
            dismissButton = {
                TextButton(enabled = !state.installing, onClick = viewModel::dismissDialog) {
                    Text("Later")
                }
            }
        )
    }

    state.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::clearNotice,
            title = { Text("NChat") },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = viewModel::clearNotice) { Text("OK") } }
        )
    }
}

/** Gradient header with the round back button and the profile card. */
@Composable
private fun SettingsHeader(profile: UserProfile, onBack: () -> Unit) {
    val extra = NcTheme.extra
    val backInteraction = rememberPressSource()
    val profileInteraction = rememberPressSource()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp)
            .background(
                Brush.linearGradient(
                    listOf(
                        extra.brandGradient[0].copy(alpha = 0.96f),
                        extra.brandGradient[1].copy(alpha = 0.96f),
                        extra.brandGradient[2].copy(alpha = 0.96f)
                    )
                )
            )
            .statusBarsPadding()
            .padding(NcSpace.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pressFeedback(backInteraction)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
                    .clickable(
                        interactionSource = backInteraction,
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
            Text(
                text = "Settings",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.width(36.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .pressFeedback(profileInteraction)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = profileInteraction,
                    indication = null,
                    onClick = {}
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            ) {
                NcAvatar(name = profile.fullName, size = 56.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.fullName,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = profile.about.ifBlank { "Add a status" },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Bright glass group: `rgba(255,255,255,.8)` fill, white hairline border. */
@Composable
private fun SettingsSection(content: @Composable () -> Unit) {
    val extra = NcTheme.extra
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NcSpace.md)
            .clip(RoundedCornerShape(16.dp))
            .background(extra.sectionFill)
            .border(1.dp, extra.sectionBorder, RoundedCornerShape(16.dp))
    ) {
        content()
    }
}

@Composable
private fun SettingsMenuItem(
    tone: MenuTone,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
    last: Boolean = false
) {
    val extra = NcTheme.extra
    val interaction = rememberPressSource()

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressFeedback(interaction, pressedScale = 0.99f)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tone.background),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tone.foreground,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = extra.textPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = extra.textTertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = extra.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (!last) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 70.dp),
                thickness = 1.dp,
                color = MenuDivider
            )
        }
    }
}

private val MenuDivider = Color(0xFFF3F4F6)

/** Pastel icon tiles from the reference `.settings-menu-icon.*` rules. */
private enum class MenuTone(val background: Color, val foreground: Color) {
    Purple(Color(0xFFF3E8FF), Color(0xFF9333EA)),
    Green(Color(0xFFDCFCE7), Color(0xFF16A34A)),
    Orange(Color(0xFFFFEDD5), Color(0xFFEA580C)),
    Red(Color(0xFFFEE2E2), Color(0xFFDC2626)),
    Blue(Color(0xFFDBEAFE), Color(0xFF2563EB)),
    Teal(Color(0xFFCCFBF1), Color(0xFF0D9488)),
    Indigo(Color(0xFFE0E7FF), Color(0xFF4F46E5)),
    Cyan(Color(0xFFCFFAFE), Color(0xFF0891B2)),
    Gray(Color(0xFFF3F4F6), Color(0xFF6B7280))
}
