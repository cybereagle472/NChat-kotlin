package com.nchat.app.feature.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.BuildConfig
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.update.AutoUpdater
import com.nchat.app.core.update.InstallResult
import com.nchat.app.core.update.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * "There is a new build" — a page that opens by itself on the first start after a release.
 *
 * It used to exist only behind Settings ▸ Check for updates, which meant a user could sit on an
 * old build for weeks without ever knowing. The release notes are the point of the screen, so the
 * text scrolls and is never truncated: it was once capped at fourteen lines and cut mid-sentence,
 * which is worse than not showing the notes at all.
 *
 * Both choices are always present — install now, or later — and "later" is remembered for this
 * launch only, so the next start makes the offer again. The system back gesture means "later";
 * while a download is running it is ignored, because there is nothing to go back to and the APK
 * is already being written.
 */
@Composable
fun StartupUpdateGate(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val found = try {
            AutoUpdater.checkForUpdate()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        } ?: return@LaunchedEffect
        if (found.versionCode > BuildConfig.VERSION_CODE) info = found
    }

    val update = info
    if (update == null || dismissed) return

    BackHandler(enabled = !installing) { dismissed = true }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(NcTheme.extra.bodyGradient))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = NcSpace.lg)
        ) {
            Spacer(modifier = Modifier.height(NcSpace.lg))

            // ---------------------------------------------------------------- hero
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(NcTheme.extra.brandGradient)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(modifier = Modifier.height(NcSpace.md))

            Text(
                text = "Update available",
                color = NcTheme.extra.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "NChat ${update.versionName} is available to download.",
                color = NcTheme.extra.textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(NcSpace.md))

            // ------------------------------------------------- versions, side by side
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                VersionChip(label = "Installed", value = BuildConfig.VERSION_NAME, active = false)
                Text(
                    text = "→",
                    color = NcTheme.extra.textTertiary,
                    fontSize = 14.sp
                )
                VersionChip(label = "New", value = update.versionName, active = true)
            }

            Spacer(modifier = Modifier.height(NcSpace.lg))

            // ---------------------------------------------------------------- notes
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .weight(1f)
                    .padding(NcSpace.md)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "What's new",
                    color = NcTheme.extra.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(NcSpace.sm))
                Text(
                    text = update.notes,
                    color = NcTheme.extra.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 21.sp
                )
                Spacer(modifier = Modifier.height(NcSpace.sm))
            }

            // ------------------------------------------------------------- progress
            if (installing) {
                Spacer(modifier = Modifier.height(NcSpace.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if ((progress ?: 0f) >= 1f) "Installing…" else "Downloading…",
                        color = NcTheme.extra.textSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = progress?.let { "${(it * 100).roundToInt()}%" } ?: "…",
                        color = NcTheme.extra.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(NcSpace.xs))
                val bar = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                val value = progress
                if (value != null) {
                    LinearProgressIndicator(
                        progress = { value.coerceIn(0f, 1f) },
                        modifier = bar,
                        color = NcTheme.extra.brandGradient[1],
                        trackColor = NcTheme.extra.brandGradient[1].copy(alpha = 0.15f)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = bar,
                        color = NcTheme.extra.brandGradient[1],
                        trackColor = NcTheme.extra.brandGradient[1].copy(alpha = 0.15f)
                    )
                }
            }

            notice?.let { message ->
                Spacer(modifier = Modifier.height(NcSpace.sm))
                Text(
                    text = message,
                    color = Color(0xFFDC2626),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(enabled = !installing, onClick = {
                    if (!AutoUpdater.openReleasePage(context)) notice = "Could not open a browser on this device."
                }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Open release page in browser")
                }
            }

            // ------------------------------------------------------------- actions
            Spacer(modifier = Modifier.height(NcSpace.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                TextButton(
                    enabled = !installing,
                    onClick = { dismissed = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Not now",
                        color = NcTheme.extra.textSecondary,
                        fontSize = 14.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (installing) {
                                Brush.linearGradient(
                                    listOf(Color(0xFFCBD5E1), Color(0xFFCBD5E1))
                                )
                            } else {
                                Brush.linearGradient(NcTheme.extra.brandGradient)
                            }
                        )
                        .clickable(enabled = !installing) {
                            installing = true
                            progress = 0f
                            notice = null
                            scope.launch {
                                try {
                                    val apk = AutoUpdater.downloadUpdate(context, update) { fraction ->
                                        progress = fraction
                                    }
                                    when (val result = AutoUpdater.installUpdate(context, apk)) {
                                        is InstallResult.Launched -> dismissed = true
                                        is InstallResult.NeedsPermission -> notice =
                                            "Allow NChat to install apps, then try again."
                                        is InstallResult.Failed -> notice = result.message
                                    }
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    notice = error.message ?: "Could not download the update."
                                } finally {
                                    installing = false
                                    progress = null
                                }
                            }
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!installing) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = if (installing) "Installing…" else "Update now",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(NcSpace.md))
        }
    }
}

/** "Installed 1.0.62" / "New 1.0.63", so the step is readable without reading the sentence. */
@Composable
private fun VersionChip(label: String, value: String, active: Boolean) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Color(0x147C3AED) else Color(0x0F0F172A))
            .padding(horizontal = NcSpace.sm, vertical = 6.dp)
    ) {
        Text(
            text = label.uppercase(),
            color = NcTheme.extra.textTertiary,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            color = if (active) NcTheme.extra.brandGradient[1] else NcTheme.extra.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
