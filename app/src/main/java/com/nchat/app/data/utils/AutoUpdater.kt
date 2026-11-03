package com.nchat.app.data.utils

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

object AutoUpdater {
    private const val VERSION_URL =
        "https://raw.githubusercontent.com/cybereagle472/NChat-kotlin/main/version.json"

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "NChat-UI-Lab-Updater")
        }
        try {
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
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NChat-UI-Lab-Updater")
        }
        try {
            val targetDir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val apk = File(targetDir, "nchat-ui-lab-${info.versionCode}.apk")
            connection.inputStream.use { input ->
                FileOutputStream(apk).use { output -> input.copyTo(output) }
            }
            if (apk.length() < 100_000L) {
                apk.delete()
                error("Downloaded update is incomplete. Please try again.")
            }
            apk
        } finally {
            connection.disconnect()
        }
    }

    fun installUpdate(context: Context, apk: File): Boolean {
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

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }
}
