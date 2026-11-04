package com.nchat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcBackdrop
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcToaster

/**
 * First-launch gate for builds with no baked-in backend.
 *
 * The reference web app never hardcodes its API address either: `VITE_API_URL` is supplied
 * when the bundle is built, and the Android build mirrors that with `NCHAT_API_BASE`
 * (see `nchat.properties`). When neither the build nor Settings ▸ Server names a backend,
 * this screen asks for one instead of letting every request fail silently.
 */
@Composable
fun ServerSetupScreen(onConnect: (String) -> Unit) {
    val extra = NcTheme.extra
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        val cleaned = draft.trim().trimEnd('/')
        when {
            cleaned.isEmpty() -> error = "Enter the address of your NChat server"
            !cleaned.startsWith("https://") && !cleaned.startsWith("http://") ->
                error = "The address must start with https:// (or http:// for a local server)"
            else -> {
                error = ""
                keyboard?.hide()
                NcToaster.show("Server saved — connecting")
                onConnect(cleaned)
            }
        }
    }

    NcBackdrop(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
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
                    imageVector = Icons.Rounded.Cloud,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
            }

            Text(
                text = "Connect NChat",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = extra.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = NcSpace.lg)
            )
            Text(
                text = "This build was compiled without a backend address, so it asks you once. " +
                    "Enter the URL of your NChat server — the Worker, or a Rust server you run " +
                    "yourself. It is stored on this device and can be changed later in " +
                    "Settings ▸ Server.",
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = NcSpace.sm)
                    .widthIn(max = 360.dp)
            )

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    if (error.isNotEmpty()) error = ""
                },
                modifier = Modifier
                    .padding(top = NcSpace.xl)
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
                singleLine = true,
                label = { Text("Server address") },
                placeholder = { Text("https://your-server.example") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        tint = extra.textMuted
                    )
                },
                isError = error.isNotEmpty(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = extra.textMuted.copy(alpha = 0.35f),
                    cursorColor = Color(0xFF8B5CF6)
                )
            )

            if (error.isNotEmpty()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFDC2626),
                    modifier = Modifier
                        .padding(top = NcSpace.xs)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                )
            }

            Box(
                modifier = Modifier
                    .padding(top = NcSpace.lg)
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(extra.brandGradient))
                    .clickable { submit() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Connect",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }

            Text(
                text = "Tip: the address is the same one the web client uses as VITE_API_URL.",
                style = MaterialTheme.typography.labelSmall,
                color = extra.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = NcSpace.lg)
            )
        }
    }
}
