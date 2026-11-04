package com.nchat.app.feature.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nchat.app.BuildConfig
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.data.ServiceLocator

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = ServiceLocator.profile
    val extra = NcTheme.extra

    Column(modifier = modifier.fillMaxSize()) {
        // Gradient header with back affordance.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(extra.brandGradient))
                .shadow(8.dp)
                .statusBarsPadding()
                .padding(horizontal = NcSpace.md, vertical = NcSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
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
                text = "Settings",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NcSpace.md)
        ) {
            item(key = "profile") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(extra.rowFill)
                        .border(1.dp, extra.rowBorder, RoundedCornerShape(20.dp))
                        .padding(NcSpace.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
                ) {
                    NcAvatar(name = profile.fullName, size = 64.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.fullName,
                            style = MaterialTheme.typography.titleLarge,
                            color = extra.textSecondary
                        )
                        Text(
                            text = profile.about,
                            style = MaterialTheme.typography.bodyMedium,
                            color = extra.textTertiary,
                            maxLines = 2
                        )
                        Text(
                            text = profile.phoneNumber,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            item(key = "account") {
                SettingsGroup {
                    SettingsRow(Icons.Rounded.Person, "Profile", "Name, about and phone number", onClick = {})
                    SettingsRow(Icons.Rounded.Lock, "Privacy", "Last seen, read receipts, blocked", onClick = {})
                    SettingsRow(Icons.Rounded.Notifications, "Notifications", "Messages, groups, calls", onClick = {})
                    SettingsRow(Icons.Rounded.Folder, "Storage and data", "Media auto-download", onClick = {})
                }
            }

            item(key = "support") {
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Rounded.Refresh,
                        title = "Check for updates",
                        subtitle = if (state.checking) {
                            "Checking…"
                        } else {
                            "Version ${BuildConfig.VERSION_NAME}"
                        },
                        onClick = { viewModel.checkForUpdates(context) },
                        trailing = {
                            if (state.checking) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                    )
                    SettingsRow(Icons.Rounded.HelpOutline, "Help centre", "FAQs and troubleshooting", onClick = {})
                    SettingsRow(
                        Icons.Rounded.Info,
                        "About NChat",
                        "Build ${BuildConfig.VERSION_CODE} · Kotlin client",
                        onClick = {}
                    )
                }
            }

            item(key = "footer") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(NcSpace.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "NChat ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Smooth, private, and fast messaging",
                        style = MaterialTheme.typography.labelSmall,
                        color = extra.textMuted,
                        modifier = Modifier.padding(top = 2.dp)
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = extra.textTertiary
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

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NcSpace.md)
            .shadow(2.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(NcTheme.extra.rowFill)
            .border(1.dp, NcTheme.extra.rowBorder, RoundedCornerShape(18.dp))
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    val extra = NcTheme.extra
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = NcSpace.md, vertical = NcSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
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
                color = extra.textSecondary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = extra.textMuted
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = extra.textMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
