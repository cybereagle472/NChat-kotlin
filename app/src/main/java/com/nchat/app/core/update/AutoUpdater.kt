package com.nchat.app.core.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nchat.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

/** Outcome of handing a downloaded APK to the system installer. */
sealed interface InstallResult {
    /** The installer UI was opened; Android takes over from here. */
    data object Launched : InstallResult

    /** "Install unknown apps" is still off for NChat; settings were opened. */
    data object NeedsPermission : InstallResult

    /** Everything else, with a message safe to show in the UI. */
    data class Failed(val message: String) : InstallResult
}

/**
 * In-app updater.
 *
 * Android only installs an update over an existing app when its signing key
 * is compatible with the installed version. All disk, network and PackageManager work happens on
 * [Dispatchers.IO]; only the final `startActivity` touches the main thread, and it is
 * always wrapped, because a bounced update must never crash or freeze the app.
 */
object AutoUpdater {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/cybereagle472/NChat-kotlin/main/version.json"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val MIN_APK_BYTES = 100_000L

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Pragma", "no-cache")
            setRequestProperty("User-Agent", "NChat-Updater")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("Update server returned HTTP ${connection.responseCode}.")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val remoteCode = json.getInt("versionCode")
            if (remoteCode <= BuildConfig.VERSION_CODE) return@withContext null
            UpdateInfo(
                versionCode = remoteCode,
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                notes = json.optString("releaseNotes", "Performance and design improvements")
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the APK and reports real byte progress through [onProgress]: a value in
     * `0f..1f`, or `null` when the server does not send a Content-Length (the UI then
     * shows an indeterminate bar for that stretch).
     */
    suspend fun downloadUpdate(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float?) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NChat-Updater")
            setRequestProperty("Accept", APK_MIME)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("APK download failed (HTTP ${connection.responseCode}).")
            }
            val targetDir = File(context.filesDir, "updates").apply {
                if (!exists() && !mkdirs()) error("Could not create the update folder.")
            }
            // Remove APKs from earlier attempts so storage never creeps up.
            targetDir.listFiles()?.forEach { stale ->
                if (stale.name != "nchat-${info.versionCode}.apk") stale.delete()
            }

            val apk = File(targetDir, "nchat-${info.versionCode}.apk")
            val temp = File(targetDir, "nchat-${info.versionCode}.part")
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }

            onProgress(if (totalBytes != null) 0f else null)
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        // One callback per whole percent keeps the UI smooth without
                        // flooding the main thread with state updates.
                        val percent = totalBytes
                            ?.let { ((copied * 100) / it).toInt().coerceIn(0, 100) }
                        if (percent != null && percent != lastReported) {
                            lastReported = percent
                            onProgress(percent / 100f)
                        }
                    }
                    output.flush()
                }
            }

            if (temp.length() < MIN_APK_BYTES || !hasZipHeader(temp)) {
                temp.delete()
                error("The downloaded file is not a valid APK. Please retry.")
            }
            if (!isNchatPackage(context, temp)) {
                temp.delete()
                error("The downloaded file is not a valid NChat build.")
            }
            if (apk.exists()) apk.delete()
            check(temp.renameTo(apk)) { "Could not prepare the downloaded update." }
            onProgress(1f)
            apk
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Shares the APK with the system installer.
     *
     * Never throws: every failure comes back as [InstallResult.Failed] so the caller can
     * show a dialog instead of letting the app die.
     */
    suspend fun installUpdate(context: Context, apk: File): InstallResult {
        if (!apk.exists() || apk.length() < MIN_APK_BYTES) {
            return InstallResult.Failed("The update file is missing. Please download it again.")
        }

        val canInstall = withContext(Dispatchers.IO) {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls()
        }
        if (!canInstall) {
            openUnknownSourcesSettings(context)
            return InstallResult.NeedsPermission
        }

        val uri: Uri = try {
            withContext(Dispatchers.IO) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            }
        } catch (error: IllegalArgumentException) {
            return InstallResult.Failed(
                "Could not share the update file (${error.message ?: "provider error"})."
            )
        }

        val candidates = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                clipData = ClipData.newRawUri("NChat update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, APK_MIME)
                clipData = ClipData.newRawUri("NChat update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        val launched = withContext(Dispatchers.Main) {
            candidates.any { intent ->
                try {
                    context.startActivity(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                } catch (_: SecurityException) {
                    false
                } catch (_: Exception) {
                    false
                }
            }
        }

        return if (launched) {
            InstallResult.Launched
        } else {
            InstallResult.Failed("No package installer is available on this device.")
        }
    }

    private fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Some OEMs rename this screen; the user can still enable it in Settings.
        }
    }

    private fun hasZipHeader(file: File): Boolean = try {
        file.inputStream().use { input ->
            input.read() == 0x50 && input.read() == 0x4B
        }
    } catch (_: Exception) {
        false
    }

    private fun isNchatPackage(context: Context, file: File): Boolean = try {
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        archive?.packageName == context.packageName
    } catch (_: Exception) {
        false
    }
}
