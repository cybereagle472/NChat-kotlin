package com.nchat.app.feature.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nchat.app.BuildConfig
import com.nchat.app.core.designsystem.NcBackdrop
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.core.designsystem.pressFeedback
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.data.ServiceLocator
import com.nchat.app.data.model.UserProfile
import com.nchat.app.feature.profile.ProfileAvatar
import com.nchat.app.feature.profile.ProfileScreen
import kotlin.math.roundToInt

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
    val profile by ServiceLocator.profileRepository.profile.collectAsStateWithLifecycle()
    val avatar by ServiceLocator.profileRepository.avatar.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(SettingsPane.Main) }
    // Read once: the repositories are built from this at first use, so a change applies on
    // the next launch (the dialog says so).
    BackHandler(enabled = section != SettingsPane.Main) { section = SettingsPane.Main }

    val preferences = ServiceLocator.preferencesRepository
    val privacy by preferences.privacy.collectAsStateWithLifecycle()
    val notifications by preferences.notifications.collectAsStateWithLifecycle()
    val blockedPeople by ServiceLocator.friendRepository.blockedPeople.collectAsStateWithLifecycle()

    when (section) {
        SettingsPane.Privacy -> {
            PrivacyPane(
                settings = privacy,
                onBack = { section = SettingsPane.Main },
                onLastSeenChange = { preferences.setLastSeen(privacy.lastSeen.next()) },
                onProfilePhotoChange = { preferences.setProfilePhoto(privacy.profilePhoto.next()) },
                onReadReceiptsChange = preferences::setReadReceipts,
                onOnlineStatusChange = preferences::setOnlineStatus
            )
            return
        }

        SettingsPane.Storage -> {
            StoragePane(onBack = { section = SettingsPane.Main })
            return
        }
        SettingsPane.Notifications -> {
            NotificationsPane(
                settings = notifications,
                onBack = { section = SettingsPane.Main },
                onMessagesChange = preferences::setMessageNotifications,
                onCallsChange = preferences::setCallNotifications,
                onPreviewChange = preferences::setShowPreview,
                onSoundChange = preferences::setSound,
                onVibrateChange = preferences::setVibrate
            )
            return
        }

        SettingsPane.Blocked -> {
            BlockedUsersPane(
                blockedPeople = blockedPeople,
                onBack = { section = SettingsPane.Main },
                onUnblock = { person ->
                    ServiceLocator.friendRepository.unblock(person.id)
                    NcToaster.show("${person.name} unblocked — your chat is back")
                }
            )
            return
        }

        SettingsPane.Sync -> {
            SyncCheckPane(onBack = { section = SettingsPane.Main })
            return
        }

        else -> Unit
    }

    if (section == SettingsPane.Profile) {
        ProfileScreen(
            profile = profile,
            avatar = avatar,
            onBack = { section = SettingsPane.Main },
            onUpdateName = ServiceLocator.profileRepository::updateName,
            onUpdateAbout = ServiceLocator.profileRepository::updateAbout,
            onAvatarChanged = { ServiceLocator.profileRepository.setAvatar(it) },
            modifier = modifier
        )
        return
    }

    NcBackdrop(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsHeader(
                profile = profile,
                avatar = avatar,
                onBack = onBack,
                onOpenProfile = { section = SettingsPane.Profile }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = NcSpace.md, vertical = NcSpace.md)
            ) {
                item(key = "account") {
                    SettingsSection {
                        SettingsMenuItem(
                            tone = MenuTone.Purple,
                            icon = Icons.Rounded.Person,
                            title = "Account",
                            subtitle = "Profile, username, email",
                            onClick = { section = SettingsPane.Profile }
                        )
                        SettingsMenuItem(
                            tone = MenuTone.Green,
                            icon = Icons.Rounded.Lock,
                            title = "Privacy",
                            subtitle = "Last seen, profile photo",
                            onClick = { section = SettingsPane.Privacy }
                        )
                        SettingsMenuItem(
                            tone = MenuTone.Orange,
                            icon = Icons.Rounded.Notifications,
                            title = "Notifications",
                            subtitle = "Messages, calls, sounds",
                            onClick = { section = SettingsPane.Notifications }
                        )
                        SettingsMenuItem(
                            tone = MenuTone.Red,
                            icon = Icons.Rounded.Block,
                            title = "Blocked Users",
                            subtitle = if (blockedPeople.isEmpty()) {
                                "Manage blocked contacts"
                            } else {
                                "${blockedPeople.size} blocked"
                            },
                            onClick = { section = SettingsPane.Blocked },
                            last = true
                        )
                    }
                }

                item(key = "chats") {
                    SettingsSection {
                        SettingsMenuItem(
                            tone = MenuTone.Orange,
                            icon = Icons.Rounded.Storage,
                            title = "Storage and data",
                            subtitle = "Media, message history, downloads",
                            onClick = { section = SettingsPane.Storage }
                        )
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
                            tone = MenuTone.Gray,
                            icon = Icons.Rounded.Sync,
                            title = "Sync check",
                            subtitle = "What the server answers, call by call",
                            onClick = { section = SettingsPane.Sync }
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

                item(key = "logout") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = NcSpace.md)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xCCFFFFFF))
                            .border(1.dp, Color(0x99FECACA), RoundedCornerShape(16.dp))
                            .clickable { ServiceLocator.authRepository.signOut() }
                            .padding(vertical = 15.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Logout,
                            contentDescription = null,
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(19.dp)
                        )
                        Text(
                            text = "Log Out",
                            color = Color(0xFFDC2626),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                item(key = "footer") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = NcSpace.lg, bottom = NcSpace.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "NChat v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                            fontSize = 12.sp,
                            color = NcTheme.extra.textMuted,
                            textAlign = TextAlign.Center
                        )
                        // Read-only, straight from BuildConfig: this is how you check that the
                        // build actually received the address it was compiled with.
                        Text(
                            text = "Server: ${builtInServer()}",
                            fontSize = 11.sp,
                            color = NcTheme.extra.textMuted,
                            textAlign = TextAlign.Center
                        )
                    }
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
                val extra = NcTheme.extra
                val progress = state.progress
                Column(verticalArrangement = Arrangement.spacedBy(NcSpace.md)) {
                    Text(
                        text = "A new build is ready to install.",
                        fontSize = 14.sp,
                        color = extra.textSecondary
                    )
                    Text(
                        text = info.notes,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = extra.textTertiary,
                        maxLines = 14,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.installing) {
                        Column(verticalArrangement = Arrangement.spacedBy(NcSpace.xs)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (progress != null && progress >= 1f) {
                                        "Installing update…"
                                    } else {
                                        "Downloading update…"
                                    },
                                    fontSize = 12.sp,
                                    color = extra.textSecondary
                                )
                                Text(
                                    text = if (progress != null) {
                                        "${(progress * 100).roundToInt()}%"
                                    } else {
                                        "Please wait"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = extra.textPrimary
                                )
                            }
                            val barModifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                            if (progress != null) {
                                LinearProgressIndicator(
                                    progress = { progress.coerceIn(0f, 1f) },
                                    modifier = barModifier,
                                    color = NcTheme.extra.brandGradient[1],
                                    trackColor = NcTheme.extra.brandGradient[1].copy(alpha = 0.18f)
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = barModifier,
                                    color = NcTheme.extra.brandGradient[1],
                                    trackColor = NcTheme.extra.brandGradient[1].copy(alpha = 0.18f)
                                )
                            }
                        }
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
private fun SettingsHeader(
    profile: UserProfile,
    avatar: ImageBitmap?,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit
) {
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
                    onClick = onOpenProfile
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
                ProfileAvatar(
                    name = profile.fullName,
                    image = avatar,
                    size = 56.dp,
                    imageUrl = profile.avatarUrl
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.fullName,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = profile.about.ifBlank { "Add a status" },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                    color = extra.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = extra.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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

/** Which pane of Settings is showing. */
private enum class SettingsPane { Main, Profile, Privacy, Notifications, Blocked, Storage, Sync }


/**
 * The host this APK was compiled against, with the scheme stripped so it reads like a name.
 *
 * It is a label, not a setting — there is no way to change it from the UI (the address is
 * baked in at build time). It exists so "did the build get my address?" has a visual answer.
 */
private fun builtInServer(): String =
    ServiceLocator.serverUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .ifEmpty { "not set in this build" }
