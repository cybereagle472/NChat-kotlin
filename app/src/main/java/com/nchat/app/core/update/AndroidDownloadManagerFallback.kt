package com.nchat.app.core.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL

/**
 * Second transport for devices/networks where an app-owned HttpURLConnection gets a gateway
 * timeout but Android's system downloader (also used by browsers) can reach the release asset.
 * Files are staged in app-specific external storage and copied to the updater's private temp file
 * only after DownloadManager reports a complete transfer.
 */
internal class AndroidDownloadManagerFallback(private val context: Context) {

    suspend fun download(
        urls: List<String>,
        destination: File,
        onProgress: (Float?) -> Unit
    ) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: throw IOException("Android's download service is unavailable.")
        val externalUpdates = context.getExternalFilesDir(UPDATE_DIRECTORY)
            ?: throw IOException("Android could not access temporary update storage.")
        if (!externalUpdates.exists() && !externalUpdates.mkdirs()) {
            throw IOException("Android could not create temporary update storage.")
        }

        var lastError: IOException? = null
        for (source in urls.distinct()) {
            val url = validateHttpsUrl(source)
            val staged = File(externalUpdates, "${destination.name}.system-download")
            if (staged.exists() && !staged.delete()) {
                throw IOException("Could not clear an incomplete system download.")
            }
            val request = DownloadManager.Request(Uri.parse(url.toExternalForm())).apply {
                setTitle("NChat update")
                setDescription("Downloading a verified NChat update")
                setMimeType(APK_MIME)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setDestinationInExternalFilesDir(context, UPDATE_DIRECTORY, staged.name)
                addRequestHeader("Accept", "*/*")
                addRequestHeader("User-Agent", USER_AGENT)
            }
            val id = try {
                manager.enqueue(request)
            } catch (error: Exception) {
                staged.delete()
                throw IOException("Could not start Android's download service.", error)
            }

            try {
                awaitDownload(manager, id, staged, destination, onProgress)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                lastError = error
                destination.delete()
            } finally {
                manager.remove(id)
                staged.delete()
            }
        }
        throw lastError ?: IOException("Android's download service could not retrieve the update.")
    }

    private suspend fun awaitDownload(
        manager: DownloadManager,
        id: Long,
        staged: File,
        destination: File,
        onProgress: (Float?) -> Unit
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() - startedAt >= MAX_WAIT_MS) {
                throw IOException("Android's download service timed out while fetching the update.")
            }
            val query = DownloadManager.Query().setFilterById(id)
            val row = manager.query(query) ?: throw IOException("Android's download service lost the request.")
            val (status, reason, downloaded, total) = row.use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("Android's download service lost the request.")
                DownloadState(
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                )
            }

            if (total > MAX_APK_BYTES || downloaded > MAX_APK_BYTES) {
                throw InvalidUpdateDownload("The update file is too large.")
            }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (!staged.isFile || staged.length() <= 0L) {
                        throw IOException("Android reported a completed download, but the APK file is missing.")
                    }
                    onProgress(0.99f)
                    FileInputStream(staged).use { input ->
                        FileOutputStream(destination).use { output -> input.copyTo(output) }
                    }
                    return
                }
                DownloadManager.STATUS_FAILED -> throw IOException(
                    "Android's download service failed (reason $reason). Check your connection and retry."
                )
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> {
                    val fraction = if (total > 0L) {
                        (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 0.99f)
                    } else null
                    onProgress(fraction)
                }
                else -> throw IOException("Android's download service returned an unknown status.")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun validateHttpsUrl(value: String): URL {
        val url = try { URL(value) } catch (error: Exception) {
            throw IOException("The update URL is invalid.", error)
        }
        if (!url.protocol.equals("https", ignoreCase = true) || url.host.isNullOrBlank() || url.userInfo != null) {
            throw IOException("The update URL must use HTTPS.")
        }
        return url
    }

    private data class DownloadState(
        val status: Int,
        val reason: Int,
        val downloaded: Long,
        val total: Long
    )

    private companion object {
        const val UPDATE_DIRECTORY = "updates"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) NChat-Updater"
        const val POLL_INTERVAL_MS = 500L
        const val MAX_WAIT_MS = 15 * 60 * 1_000L
        const val MAX_APK_BYTES = 256L * 1024 * 1024
    }
}
