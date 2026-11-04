package com.nchat.app.core.util

import android.content.Context
import android.os.Build
import com.nchat.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash recorder.
 *
 * Android's own message — "NChat keeps stopping" — carries no line of code, no cause and no
 * version, and a phone without a cable cannot run `adb logcat`. So before the process dies
 * the trace is written to the app's own `files/` directory, and the next launch shows it
 * with a Copy button.
 *
 * Deliberately tiny and dependency-free: this code runs while the process is already
 * failing, so it must not touch the network, the Keystore, Compose or the token store.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    @Volatile
    private var appContext: Context? = null

    /** Called first thing in [com.nchat.app.NChatApplication.onCreate]. */
    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Record, then let the platform do what it would have done anyway.
            runCatching { android.util.Log.e("NChatCrash", "uncaught on ${thread.name}", error) }
            runCatching { record(appContext, "uncaught on thread ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Record a failure that was caught on purpose (the app keeps running).
     *
     * Used for things that must never take the app down — a background coroutine, the image
     * loader — where a silent catch would hide a real bug.
     */
    fun note(where: String, error: Throwable) {
        runCatching { android.util.Log.e("NChatCrash", where, error) }
        runCatching { record(appContext, where, error) }
    }

    /** The text of the last recorded failure, or `null` when there is nothing to show. */
    fun read(context: Context): String? = runCatching {
        val file = file(context)
        if (!file.exists()) return@runCatching null
        file.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun record(context: Context?, where: String, error: Throwable) {
        val ctx = context ?: return
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val header = buildString {
            appendLine("NChat crash report")
            appendLine("when:    ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("app:     ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            appendLine(
                "android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}"
            )
            appendLine("where:   $where")
            appendLine()
        }
        val body = header + trace
        val target = file(ctx)
        target.parentFile?.mkdirs()
        target.writeText(body)

        // Also drop a copy where a file manager can reach it (Android/data/<pkg>/files).
        // If the app cannot start at all, the report is still copyable without adb.
        runCatching {
            val external = ctx.getExternalFilesDir(null) ?: return@runCatching
            File(external, FILE_NAME).writeText(body)
        }
    }
}
