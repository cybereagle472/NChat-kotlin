package com.nchat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nchat.app.core.util.CrashLog
import com.nchat.app.data.ServiceLocator
import com.nchat.app.data.notifications.NcNotifications
import com.nchat.app.ui.CrashReportScreen
import com.nchat.app.ui.NChatApp

class MainActivity : ComponentActivity() {

    /**
     * Android 13+ asks the user before an app may notify at all.
     *
     * Asked once on launch, with no explanation dialog of our own: the system prompt is the
     * explanation, and a refusal is respected silently (`NcNotifications.canPost`).
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Composition root: the backend address comes from BuildConfig (see nchat.properties),
        // so there is nothing to configure here at runtime.
        ServiceLocator.init(applicationContext)
        requestNotificationPermission()
        // A notification tap that cold-started the app carries the chat to open.
        readChatTarget(intent)
        setContent {
            val context = LocalContext.current
            // If the previous launch died, show why before anything else. Reading and
            // deleting one small text file cannot fail in a way that matters.
            var report by remember { mutableStateOf(CrashLog.read(context)) }
            val pending = report
            if (pending != null) {
                CrashReportScreen(
                    report = pending,
                    onDismiss = {
                        CrashLog.clear(context)
                        report = null
                    }
                )
            } else {
                NChatApp()
            }
        }
    }

    /**
     * The app was already running when the notification was tapped (`launchMode=singleTop`).
     *
     * Without this the tap would only bring the app forward and leave the user on whatever screen
     * they were on, which is the classic "notification does nothing" bug.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readChatTarget(intent)
    }

    private fun readChatTarget(intent: Intent?) {
        val chatId = intent?.getStringExtra(NcNotifications.EXTRA_CHAT_ID) ?: return
        val title = intent.getStringExtra(NcNotifications.EXTRA_CHAT_TITLE).orEmpty()
        ServiceLocator.pendingOpen.value = chatId to title.ifBlank { "Chat" }
        // Consumed once: a later rotation must not re-open the same conversation.
        intent.removeExtra(NcNotifications.EXTRA_CHAT_ID)
        intent.removeExtra(NcNotifications.EXTRA_CHAT_TITLE)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    /**
     * Coming back to the foreground is the moment realtime has to be *guaranteed*, not
     * eventually re-established: the phone may have switched networks, slept through a
     * timeout, or lost the socket while the screen was off. Waking it here (instead of
     * waiting for a backoff timer) is the difference between a WhatsApp-like app and one
     * where "it appears after a few seconds".
     */
    override fun onResume() {
        super.onResume()
        if (ServiceLocator.tokens.signedIn.value) {
            ServiceLocator.ensureRealtime()
            ServiceLocator.reportPresence()
        }
    }
}
