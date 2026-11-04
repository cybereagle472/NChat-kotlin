@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nchat.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nchat.app.BuildConfig
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSectionHeader
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcTextPillButton
import com.nchat.app.data.ServiceLocator
import com.nchat.app.data.model.UserProfile

@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = ServiceLocator.profile

    Column(modifier = modifier.fillMaxSize()) {
        SettingsHeader(onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = NcSpace.xxxl)
        ) {
            item(key = "profile") {
                ProfileCard(profile = profile)
            }

            item(key = "account-header") {
                NcSectionHeader(title = "Account")
            }
            item(key = "account-rows") {
                SettingsGroup {
                    SettingsRow(Icons.Rounded.Person, "Profile", "Name, about and phone number") {}
                    SettingsRow(Icons.Rounded.Lock, "Privacy", "Last seen, read receipts, blocked") {}
                    SettingsRow(Icons.Rounded.Notifications, "Notifications", "Messages, groups, calls") {}
                    SettingsRow(Icons.Rounded.Folder, "Storage and data", "Media auto-download") {}
                }
            }

            item(key = "appearance-header") {
                NcSectionHeader(title = "Appearance")
            }
            item(key = "appearance-rows") {
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Rounded.DarkMode,
                        title = "Dark theme",
                        subtitle = if (darkTheme) "Enabled" else "Disabled",
                        onClick = onToggleTheme,
                        trailing = {
                            Switch(checked = darkTheme, onCheckedChange = { onToggleTheme() })
                        }
                    )
                }
            }

            item(key = "support-header") {
                NcSectionHeader(title = "Support")
            }
            item(key = "support-rows") {
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Rounded.Refresh,
                        title = "Check for updates",
                        subtitle = if (state.checking) "Checking…" else "Version ${BuildConfig.VERSION_NAME}",
                        onClick = { viewModel.checkForUpdates(context) },
                        trailing = {
                            if (state.checking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    )
                    SettingsRow(Icons.Rounded.HelpOutline, "Help centre", "FAQs and troubleshooting") {}
                    SettingsRow(Icons.Rounded.Info, "About NChat", "Build ${BuildConfig.VERSION_CODE}") {}
                }
            }

            item(key = "footer") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(NcSpace.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "NChat ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Kotlin + Compose client · Rust backend in progress",
                        style = MaterialTheme.typography.labelSmall,
                        color = NcTheme.extra.mutedText,
                        modifier = Modifier.padding(top = NcSpace.xs)
                    )
                }
            }
        }
    }

    val info = state.info
    if (state.dialogVisible && info != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("NChat ${info.versionName} available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NcSpace.sm)) {
                    Text("A new build is ready to install.")
                    Text(
                        text = info.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NcTheme.extra.mutedText
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
            confirmButton = {
                TextButton(onClick = viewModel::clearNotice) { Text("OK") }
            }
        )
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(NcTheme.extra.headerGradient))
            .statusBarsPadding()
            .padding(start = NcSpace.xs, end = NcSpace.lg, top = NcSpace.sm, bottom = NcSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
        Text(
            text = "Settings",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ProfileCard(profile: UserProfile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(NcSpace.lg)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { }
            .padding(NcSpace.md + 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NcAvatar(name = profile.fullName, size = 64.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = NcSpace.md)
        ) {
            Text(
                text = profile.fullName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = profile.about,
                style = MaterialTheme.typography.bodySmall,
                color = NcTheme.extra.mutedText,
                maxLines = 2
            )
            Text(
                text = profile.phoneNumber,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        NcTextPillButton(label = "Edit", onClick = {})
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NcSpace.lg)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = NcSpace.md + 2.dp, vertical = NcSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = NcSpace.md)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = NcTheme.extra.mutedText
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Spacer(modifier = Modifier.size(NcSpace.xs))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = NcTheme.extra.mutedText,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
