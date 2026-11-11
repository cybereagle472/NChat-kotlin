package com.nchat.app.feature.update

import android.os.SystemClock
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.BuildConfig
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.update.AutoUpdater
import com.nchat.app.core.update.InstallResult
import com.nchat.app.core.update.UpdateForeground
import com.nchat.app.core.update.UpdateInfo
import com.nchat.app.core.update.updateCheckDue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The line under the notes: a step for the user to take, or something that went wrong. */
private data class GateNotice(val text: String, val isError: Boolean)

/**
 * Shown the first time NChat installs an update on a phone. It is an instruction, not a failure,
 * and it used to be printed in the red of an error: "downloaded, then an error".
 */
private const val INSTALL_PERMISSION_STEP =
    "One more step. Android asks you to allow NChat to install the update. " +
        "Turn on \"Allow from this source\" on the screen that opened, then come back here. " +
        "The update continues by itself. If no screen opened, look for " +
        "\"Install unknown apps\" in your phone's Settings."

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
 *
 * "This launch" can be days: NChat keeps its process alive to deliver messages. So the page also
 * looks for a release when the app comes back to the foreground, at most once every six hours
 * (see `updateCheckDue`), and a look that finds one makes the offer again.
 */
@Composable
fun StartupUpdateGate(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var notice by remember { mutableStateOf<GateNotice?>(null) }
    // Android's "Install unknown apps" screen was opened for this update.
    var awaitingPermission by remember { mutableStateOf(false) }

    // Once when the page is first composed, then on every return to the foreground that is due.
    // One collector, so two looks never overlap and a return never cancels a look in flight.
    LaunchedEffect(Unit) {
        var lastCheckMs: Long? = null
        var lastCheckSucceeded = false
        UpdateForeground.resumes.collect {
            if (installing || awaitingPermission) return@collect
            if (!updateCheckDue(SystemClock.elapsedRealtime(), lastCheckMs, lastCheckSucceeded)) return@collect
            val found = try {
                AutoUpdater.checkForUpdate()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Quiet on purpose: nobody asked for this look. It is repeated on a later return.
                lastCheckMs = SystemClock.elapsedRealtime()
                lastCheckSucceeded = false
                return@collect
            }
            lastCheckMs = SystemClock.elapsedRealtime()
            lastCheckSucceeded = true
            if (installing || awaitingPermission) return@collect
            val newer = found?.takeIf { candidate -> candidate.versionCode > BuildConfig.VERSION_CODE }
            if (newer != info) notice = null
            info = newer
            if (newer != null) dismissed = false
        }
    }

    val update = info
    if (update == null || dismissed) return

    fun dismiss() {
        dismissed = true
        awaitingPermission = false
        notice = null
    }

    fun startInstall() {
        if (installing) return
        installing = true
        progress = 0f
        notice = null
        awaitingPermission = false
        scope.launch {
            try {
                // A file that was already downloaded and checked is reused, not fetched again.
                val apk = AutoUpdater.downloadUpdate(context, update) { fraction ->
                    progress = fraction
                }
                when (val result = AutoUpdater.installUpdate(context, apk)) {
                    is InstallResult.Launched -> dismissed = true
                    is InstallResult.NeedsPermission -> {
                        awaitingPermission = true
                        notice = GateNotice(INSTALL_PERMISSION_STEP, isError = false)
                    }
                    is InstallResult.Failed -> notice = GateNotice(result.message, isError = true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice = GateNotice(error.message ?: "Could not download the update.", isError = true)
            } finally {
                installing = false
                progress = null
            }
        }
    }

    // Back from "Install unknown apps" with the switch on: carry on without another tap.
    LaunchedEffect(update) {
        UpdateForeground.resumes.collect {
            if (!awaitingPermission || installing) return@collect
            val allowed = withContext(Dispatchers.IO) { AutoUpdater.canInstallPackages(context) }
            if (allowed && awaitingPermission && !installing) startInstall()
        }
    }

    BackHandler(enabled = !installing) { dismiss() }

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

            notice?.let { current ->
                Spacer(modifier = Modifier.height(NcSpace.sm))
                SelectionContainer {
                    Text(
                        text = current.text,
                        color = if (current.isError) Color(0xFFDC2626) else NcTheme.extra.textPrimary,
                        fontSize = if (current.isError) 11.sp else 13.sp,
                        lineHeight = if (current.isError) TextUnit.Unspecified else 19.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // The browser route is for a failure; a step to take does not need a way round it.
                if (current.isError) {
                    TextButton(enabled = !installing, onClick = {
                        if (!AutoUpdater.openReleasePage(context)) {
                            notice = GateNotice("Could not open a browser on this device.", isError = true)
                        }
                    }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Open release page in browser")
                    }
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
                    onClick = { dismiss() },
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
                        .clickable(enabled = !installing) { startInstall() }
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
