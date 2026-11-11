package com.nchat.app.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class UpdateHttpException(
    val status: Int,
    /** A numeric Retry-After value, clamped by the downloader to a safe maximum. */
    val retryAfterMillis: Long? = null
) : IOException("HTTP $status")
/**
 * [refusedAfterChecks]: the file arrived whole and the package/signer checks then turned it away.
 * The message already says what was wrong; "try another network" would be the wrong advice.
 */
internal class InvalidUpdateDownload(
    message: String,
    val refusedAfterChecks: Boolean = false
) : IOException(message)

internal fun isTransientUpdateFailure(error: IOException): Boolean = when (error) {
    is InvalidUpdateDownload -> false
    is UpdateHttpException -> error.status == 408 || error.status == 429 || error.status in 500..599
    else -> true
}

/** Use Android's independent system download transport after a recoverable direct-transfer error. */
internal fun shouldUseAndroidDownloadFallback(error: IOException): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        when (cause) {
            is InvalidUpdateDownload -> return false
            is UpdateHttpException -> return isTransientUpdateFailure(cause)
        }
        cause = cause.cause
    }
    return true
}

private const val RETRY_ELSEWHERE = "Try another network or open the release page in your browser."

/**
 * What the update page says when every download source has failed.
 *
 * HTTP 404 is singled out. The manifest that announces a version reaches phones as soon as the
 * release is merged; the APK it points at is built and uploaded a few minutes later. In that
 * window the file is simply not there yet, and "try another network" would send the user away
 * to fix something that is not broken.
 */
internal fun downloadFailureMessage(error: IOException?): String = when {
    error is UpdateHttpException && error.status == 404 ->
        "This update is not on the download server (HTTP 404). A new version can take a few " +
            "minutes to appear there: please try again shortly. If it keeps happening, open the " +
            "release page in your browser."
    error is UpdateHttpException -> "The download server returned HTTP ${error.status}. $RETRY_ELSEWHERE"
    error is InvalidUpdateDownload && error.refusedAfterChecks ->
        error.message ?: "The update file is invalid."
    error is InvalidUpdateDownload -> "${error.message ?: "The update file is invalid."} $RETRY_ELSEWHERE"
    else -> "The update could not be downloaded. $RETRY_ELSEWHERE"
}

/** Bounded retries and optional independent mirrors; all update and redirect URLs must use HTTPS. */
internal class ApkDownloader(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val minBytes: Long = 100_000L,
    private val maxBytes: Long = 256L * 1024 * 1024
) {
    suspend fun download(
        urls: List<String>, temp: File, onProgress: (Float?) -> Unit,
        validate: (File) -> String? = { null }
    ) {
        var lastError: IOException? = null
        for (source in urls.distinct().take(MAX_SOURCES)) {
            for (attempt in 0 until MAX_ATTEMPTS) {
                currentCoroutineContext().ensureActive()
                try {
                    downloadOnce(source, temp, onProgress)
                    validate(temp)?.let { throw InvalidUpdateDownload(it, refusedAfterChecks = true) }
                    return
                } catch (error: CancellationException) {
                    temp.delete()
                    throw error
                } catch (error: IOException) {
                    temp.delete()
                    lastError = error
                    if (!isTransientUpdateFailure(error) || attempt == MAX_ATTEMPTS - 1) break
                    val backoff = (1_000L shl attempt).coerceAtMost(MAX_BACKOFF_MS)
                    val serverDelay = (error as? UpdateHttpException)?.retryAfterMillis
                        ?.coerceIn(0L, MAX_BACKOFF_MS)
                    pause(serverDelay ?: backoff)
                }
            }
        }
        throw IOException(downloadFailureMessage(lastError), lastError)
    }

    private suspend fun downloadOnce(source: String, temp: File, onProgress: (Float?) -> Unit) {
        var url = secureUrl(source)
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val connection = open(url).apply {
                connectTimeout = 30_000
                readTimeout = 90_000
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "NChat-Updater")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECTS) {
                    if (++redirects > MAX_REDIRECTS) throw InvalidUpdateDownload("Too many download redirects.")
                    val location = connection.getHeaderField("Location")
                        ?: throw InvalidUpdateDownload("The download redirect has no destination.")
                    url = secureUrl(URL(url, location).toExternalForm())
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    val retryAfterMillis = connection.getHeaderField("Retry-After")
                        ?.trim()
                        ?.toLongOrNull()
                        ?.times(1_000L)
                    throw UpdateHttpException(status, retryAfterMillis)
                }
                val total = connection.contentLengthLong.takeIf { it >= 0 }
                if (total != null && (total < minBytes || total > maxBytes)) {
                    throw InvalidUpdateDownload("The update file has an invalid size.")
                }
                onProgress(if (total != null) 0f else null)
                var copied = 0L
                var lastReported = -1
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > maxBytes) throw InvalidUpdateDownload("The update file is too large.")
                            output.write(buffer, 0, count)
                            val percent = total?.takeIf { it > 0 }?.let { (copied * 100 / it).toInt().coerceIn(0, 99) }
                            if (percent != null && percent != lastReported) {
                                lastReported = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
                if (copied < minBytes || (total != null && copied != total)) {
                    throw IOException("The update download was incomplete.")
                }
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun secureUrl(value: String): URL {
        val url = try { URL(value) } catch (_: Exception) {
            throw InvalidUpdateDownload("The update URL is invalid.")
        }
        if (!url.protocol.equals("https", ignoreCase = true) || url.host.isNullOrBlank() || url.userInfo != null) {
            throw InvalidUpdateDownload("The update URL must use HTTPS.")
        }
        return url
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_SOURCES = 4
        const val MAX_BACKOFF_MS = 20_000L
        const val MAX_REDIRECTS = 8
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
