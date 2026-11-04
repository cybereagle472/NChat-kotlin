package com.nchat.app.core.update

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

/**
 * In-app updater. The APK is always signed with the same committed keystore, so a new
 * build installs straight over the previous one without uninstalling.
 */
object AutoUpdater {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/cybereagle472/NChat-kotlin/main/version.json"
    private const val APK_MIME = "application/vnd.android.package-archive"

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
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Update server returned HTTP ${connection.responseCode}."
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

    suspend fun downloadUpdate(context: Context, info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NChat-Updater")
            setRequestProperty("Accept", APK_MIME)
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "APK download failed (HTTP ${connection.responseCode})."
            }
            val targetDir = File(context.filesDir, "updates").apply {
                if (!exists() && !mkdirs()) error("Could not create update folder.")
            }
            val apk = File(targetDir, "nchat-${info.versionCode}.apk")
            val temp = File(targetDir, "nchat-${info.versionCode}.part")
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            val validZip = temp.inputStream().use { input ->
                input.read() == 0x50 && input.read() == 0x4B
            }
            if (!validZip || temp.length() < 100_000L) {
                temp.delete()
                error("Downloaded APK is invalid or incomplete. Please retry.")
            }
            if (apk.exists()) apk.delete()
            check(temp.renameTo(apk)) { "Could not prepare the downloaded update." }
            apk
        } finally {
            connection.disconnect()
        }
    }

    fun installUpdate(context: Context, apk: File): Boolean {
        check(apk.exists() && apk.length() > 100_000L) {
            "Update APK is missing. Please download it again."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return false
        }
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME)
            clipData = ClipData.newUri(context.contentResolver, "NChat update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        check(installIntent.resolveActivity(context.packageManager) != null) {
            "Android package installer is not available on this phone."
        }
        context.startActivity(installIntent)
        return true
    }
}
