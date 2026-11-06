package com.nchat.app.data.push

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nchat.app.MainActivity
import com.nchat.app.R
import com.nchat.app.data.ServiceLocator
import com.nchat.app.data.notifications.NcNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter

/**
 * Keeps NChat reachable while it is not on screen — the app's own push, with no Firebase.
 *
 * ## Why this exists
 *
 * On Android the only channel that can wake a closed app on Google's behalf is FCM. NChat does
 * not use it (no Firebase, no Play Services dependency for messaging), so the alternative is the
 * one every non-Google client uses: hold the connection yourself. This service holds nothing new —
 * it keeps [ServiceLocator]'s existing realtime socket alive in a foreground service, so the
 * already-written notification path (`RemoteChatRepository.onIncoming` →
 * [NcNotifications.show]) fires while the UI is gone. There is no second transport, no push
 * server, and no second copy of the message-handling logic to drift out of sync.
 *
 * ## What it costs
 *
 * A foreground service means Android shows a quiet, permanent line in the shade ("NChat ·
 * connected"). That is the price of not using Google, and it is the same price every FCM-less
 * messenger pays. The line is on a `MIN`-importance channel: no sound, no icon in the status bar,
 * collapsed at the bottom of the shade.
 *
 * ## Watchdog
 *
 * A socket that died while the phone was asleep (doze, wifi→cellular, a killed Durable Object)
 * would otherwise stay dead until the user opened the app. Every [WATCHDOG_MS] the service asks
 * the socket to be connected *now*, and re-sends the presence heartbeat that keeps this account
 * showing as online to everyone else. Both calls are no-ops when everything is healthy.
 */
class BackgroundSyncService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdog: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Idempotent: MainActivity already ran this, but a service started by START_STICKY (after
        // the process was killed) can be the first thing to run.
        runCatching { ServiceLocator.init(applicationContext) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (!ServiceLocator.tokens.signedIn.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceLocator.connectRealtime()
        // `onStartCommand` runs again on every START_STICKY restart; one watchdog is enough and
        // several would each ping the socket every minute.
        if (watchdog != null) return START_STICKY
        watchdog = scope.launch {
            while (true) {
                delay(WATCHDOG_MS)
                if (!ServiceLocator.tokens.signedIn.value) {
                    stopSelf()
                    return@launch
                }
                runCatching {
                    ServiceLocator.ensureRealtime()
                    ServiceLocator.reportPresence()
                }
            }
        }
        // Signed out while running: nothing left to keep alive.
        scope.launch {
            ServiceLocator.tokens.signedIn.filter { signed -> !signed }.collect { stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NcNotifications.backgroundChannel(this))
            .setSmallIcon(R.drawable.ic_stat_message)
            .setColor(0xFF7C3AED.toInt())
            .setContentTitle("NChat")
            .setContentText("Connected — messages arrive as they come")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    companion object {
        private const val TAG = "NChatBackground"
        private const val NOTIF_ID = 0x4E434842
        private const val WATCHDOG_MS = 60_000L

        /** Starts the service; safe to call when it is already running. */
        fun start(context: Context) {
            val intent = Intent(context, BackgroundSyncService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                // A ROM that refuses to start a foreground service must not take the app down;
                // the UI's own socket still works while the app is open.
                Log.w(TAG, "could not start background sync", error)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BackgroundSyncService::class.java)) }
        }

        /**
         * Asks Android to stop optimising NChat's battery usage.
         *
         * Doze freezes background work after a while on a still phone; a messaging app that is
         * frozen cannot reconnect, and "messages arrive when I open the app" is the result. The
         * system dialog is the only honest way to ask — `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
         * shows a single confirmation and the user can refuse. Falls back to the battery-usage
         * list on ROMs that do not implement the direct dialog.
         */
        fun requestBatteryExemption(context: Context) { BatteryExemption.request(context) }
    }
}
