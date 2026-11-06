package com.nchat.app.feature.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.window.Dialog
import com.nchat.app.BuildConfig
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.update.AutoUpdater
import com.nchat.app.core.update.InstallResult
import com.nchat.app.core.update.UpdateInfo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * "There is a new build" — shown on its own the first time the app is opened after a release.
 *
 * It used to exist only behind Settings ▸ Check for updates, which meant a user could sit on an
 * old build for weeks without knowing. The release notes are the whole point of the screen, so
 * the text scrolls and is never truncated: previously it was capped at fourteen lines and cut
 * mid-sentence, which is worse than not showing it at all.
 *
 * Both options are always available — install now, or later — and "later" is remembered for this
 * launch only, so the next time the app starts the offer is made again.
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
        val found = runCatching { AutoUpdater.checkForUpdate() }.getOrNull() ?: return@LaunchedEffect
        if (found.versionCode > BuildConfig.VERSION_CODE) info = found
    }

    val update = info
    if (update == null || dismissed) {
        return
    }

    Dialog(onDismissRequest = { if (!installing) dismissed = true }) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF131A24))
                .padding(NcSpace.lg)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(NcTheme.extra.brandGradient)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NChat ${update.versionName} is ready",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "You are on ${BuildConfig.VERSION_NAME}",
                        color = Color(0xB3FFFFFF),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(NcSpace.md))

            // The notes, in full. `heightIn` caps the sheet so a long changelog never pushes the
            // buttons off screen; the scroll keeps every word reachable.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = update.notes,
                    color = Color(0xD9FFFFFF),
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }

            if (installing) {
                Spacer(modifier = Modifier.height(NcSpace.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if ((progress ?: 0f) >= 1f) "Installing…" else "Downloading…",
                        color = Color(0xB3FFFFFF),
                        fontSize = 12.sp
                    )
                    Text(
                        text = progress?.let { "${(it * 100).roundToInt()}%" } ?: "…",
                        color = Color.White,
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
                        trackColor = Color(0x33FFFFFF)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = bar,
                        color = NcTheme.extra.brandGradient[1],
                        trackColor = Color(0x33FFFFFF)
                    )
                }
            }

            notice?.let { message ->
                Spacer(modifier = Modifier.height(NcSpace.sm))
                Text(
                    text = message,
                    color = Color(0xFFFCA5A5),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(NcSpace.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    enabled = !installing,
                    onClick = { dismissed = true }
                ) {
                    Text("Later", color = Color(0xCCFFFFFF))
                }
                TextButton(
                    enabled = !installing,
                    onClick = {
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
                                notice = error.message ?: "Could not download the update."
                            } finally {
                                installing = false
                                progress = null
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (installing) "Working…" else "Update now",
                        color = NcTheme.extra.brandGradient[1],
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
