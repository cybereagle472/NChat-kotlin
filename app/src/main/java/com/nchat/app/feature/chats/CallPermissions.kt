package com.nchat.app.feature.chats

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.nchat.app.core.designsystem.NcToaster

/**
 * "Ask for the microphone (and camera) first, then start or answer the call."
 *
 * Media cannot start without these, and asking *after* the offer is built is too late: the
 * caller's peer connection opens the mic while it creates the offer, and the callee's opens it
 * while it applies one. So both the call button and the Answer button go through this gate.
 *
 * Returns `(video, onGranted)`; [onGranted] runs immediately when the permissions are already
 * held, otherwise after the system dialog comes back granted.
 */
@Composable
fun rememberCallPermissionGate(): (Boolean, () -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pending.value
        pending.value = null
        // Bluetooth is best-effort (see the launch below), so it is not part of this verdict.
        val blocking = grants.filterKeys { it != Manifest.permission.BLUETOOTH_CONNECT }
        if (blocking.isNotEmpty() && blocking.values.all { it }) {
            action?.invoke()
        } else {
            NcToaster.show("Calls need the microphone — and the camera for video")
        }
    }

    return remember(context, launcher) {
        val gate: (Boolean, () -> Unit) -> Unit = { video, onGranted ->
            val missing = buildList {
                if (!granted(context, Manifest.permission.RECORD_AUDIO)) {
                    add(Manifest.permission.RECORD_AUDIO)
                }
                if (video && !granted(context, Manifest.permission.CAMERA)) {
                    add(Manifest.permission.CAMERA)
                }
            }
            if (missing.isEmpty()) {
                onGranted()
            } else {
                pending.value = onGranted
                // Bluetooth is asked for alongside, but never *required*: a headset is a
                // nicety, and denying it must not block the call.
                val ask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !granted(context, Manifest.permission.BLUETOOTH_CONNECT)
                ) {
                    missing + Manifest.permission.BLUETOOTH_CONNECT
                } else {
                    missing
                }
                launcher.launch(ask.toTypedArray())
            }
        }
        gate
    }
}

private fun granted(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
