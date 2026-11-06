package com.nchat.app.data.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * "Don't put NChat to sleep."
 *
 * Doze is what turns a working background connection into "messages only when I open the app".
 * There is no silent way for an app to exempt itself, and there should not be — so this asks, in
 * the system's own words, and remembers nothing: the OS state is the only truth.
 */
internal object BatteryExemption {

    /** True when Android is already leaving this app alone. */
    fun granted(context: Context): Boolean {
        val power = ContextCompat.getSystemService(context, PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun request(context: Context) {
        if (granted(context)) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        // Both go through a chooser-less start: the first is the real dialog, the second the
        // list on ROMs that have removed it. `NEW_TASK` is required from a non-activity context.
        val started = runCatching {
            context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!started) {
            runCatching { context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
