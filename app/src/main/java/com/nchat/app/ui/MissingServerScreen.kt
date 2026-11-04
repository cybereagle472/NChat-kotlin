package com.nchat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nchat.app.BuildConfig
import com.nchat.app.core.designsystem.NcBackdrop
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme

/**
 * Shown only when the APK was compiled without a backend address.
 *
 * The app deliberately has **no server switcher**: the address is a property of the build
 * (`NCHAT_API_BASE` → `BuildConfig`), exactly like the reference web app's `VITE_API_URL`.
 * A build that ships without one is a packaging mistake, so this screen explains what to
 * rebuild rather than offering a text field nobody should need.
 */
@Composable
fun MissingServerScreen() {
    val extra = NcTheme.extra

    NcBackdrop(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = NcSpace.lg, vertical = NcSpace.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(extra.brandGradient)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
            }

            Text(
                text = "This build has no server",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = extra.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = NcSpace.lg)
            )
            Text(
                text = "NChat connects to one backend, chosen when the APK is compiled " +
                    "(the same way the web app takes VITE_API_URL). This build was made " +
                    "without that value, so it has nowhere to sign in.",
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = NcSpace.sm)
                    .widthIn(max = 380.dp)
            )
            Text(
                text = "Install the published release, or rebuild with NCHAT_API_BASE set.",
                style = MaterialTheme.typography.labelMedium,
                color = extra.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = NcSpace.lg)
                    .fillMaxWidth()
                    .widthIn(max = 380.dp)
            )
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                style = MaterialTheme.typography.labelSmall,
                color = extra.textMuted,
                modifier = Modifier.padding(top = NcSpace.xl)
            )
        }
    }
}
