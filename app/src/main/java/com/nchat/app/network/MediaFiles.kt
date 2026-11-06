package com.nchat.app.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Media files that have to be *played*, fetched once and handed to a player as a local file.
 *
 * A photo can be loaded with an HTTP header (Coil does it, see [MediaImages]); `MediaPlayer`
 * cannot — `setDataSource(url)` opens the URL itself, with no `Authorization` line. Media reads
 * on this backend are private (`GET /media/file/…` answers only a signed-in caller, see
 * `server/src/routes/media.rs`), so pointing the player straight at the URL failed on **every**
 * voice note that came from the server with "Couldn't play this voice message".
 *
 * Two more things go wrong with a URL handed to a player, both fixed by downloading first:
 *
 * - `setDataSource(url)` performs its network I/O on the calling thread, which is the UI thread
 *   when it runs from a tap;
 * - the same clip scrolled away and back re-connects, and every seek on a network source is a
 *   range request.
 *
 * So the bytes come through the authenticated client (the same token + one-shot renew policy as
 * images), land in `cacheDir/voice/`, are sanity-checked to be audio, and are played from disk.
 * The cache is bounded and self-cleaning, and a cached clip plays offline.
 */
object MediaFiles {

    /** A clip longer than this is not a voice note; refuse instead of filling the cache. */
    private const val MAX_BYTES = 32L * 1024 * 1024

    /** Everything downloaded here is disposable: cap it and evict oldest-first. */
    private const val CACHE_BYTES = 64L * 1024 * 1024

    private var appContext: Context? = null
    private var client: OkHttpClient? = null

    /**
     * Wires the downloader once, at start-up.
     *
     * The token and the refresh are lambdas for the same reason [MediaImages] takes them that
     * way: a problem in the token store must never stop the app from starting.
     */
    fun install(
        context: Context,
        rootUrl: String,
        accessToken: () -> String?,
        renewSession: suspend () -> String?
    ) {
        appContext = context.applicationContext
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(
                MediaAuthInterceptor(
                    root = rootUrl.trim().trimEnd('/'),
                    accessToken = accessToken,
                    renewSession = renewSession
                )
            )
            .build()
    }

    /** The file behind a `file://` URL already on this device (a clip recorded here). */
    fun existingLocal(url: String?): File? {
        val raw = url?.takeIf { it.isNotBlank() } ?: return null
        if (!raw.startsWith("file://", ignoreCase = true)) return null
        return File(raw.removePrefix("file://")).takeIf { it.isFile && it.length() > 0 }
    }

    /** The cached download of [url], or null when it has never been fetched. */
    fun cached(url: String): File? {
        if (url.isBlank()) return null
        val dir = cacheDir() ?: return null
        return dir.listFiles()
            ?.firstOrNull { it.name.startsWith(key(url)) && it.isFile && it.length() > 0 }
    }

    /**
     * A local file for [url] — the one on disk, the one in the cache, or a fresh download.
     *
     * Runs entirely on [Dispatchers.IO]: nothing here may touch the main thread, because the
     * caller is a tap on a play button.
     */
    suspend fun local(url: String): Result<File> = withContext(Dispatchers.IO) {
        existingLocal(url)?.let { return@withContext Result.success(it) }
        cached(url)?.let { return@withContext Result.success(it) }
        // A `file://` clip that is gone (already sent, or swept) cannot be downloaded back:
        // say so instead of handing a file URL to the HTTP client and throwing.
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(
                MediaFileException("this recording is no longer on this device")
            )
        }
        download(url)
    }

    private fun download(url: String): Result<File> {
        val http = client
            ?: return Result.failure(MediaFileException("the downloader is not configured"))
        val dir = cacheDir()
            ?: return Result.failure(MediaFileException("this device has no cache directory"))
        val started = System.currentTimeMillis()
        val request = runCatching { Request.Builder().url(url).get().build() }.getOrElse {
            ApiLog.record("GET", pathOf(url), null, 0, "that link cannot be opened")
            return Result.failure(MediaFileException("that link cannot be opened"))
        }
        val response = runCatching { http.newCall(request).execute() }.getOrElse { error ->
            ApiLog.record("GET", pathOf(url), null, System.currentTimeMillis() - started, error.short())
            return Result.failure(MediaFileException(error.short()))
        }
        response.use { answer ->
            val millis = System.currentTimeMillis() - started
            if (!answer.isSuccessful) {
                ApiLog.record("GET", pathOf(url), answer.code, millis, "media read refused")
                return Result.failure(
                    MediaFileException(
                        when (answer.code) {
                            401, 403 -> "the file is not shared with you"
                            404 -> "the file is no longer on the server"
                            else -> "the server answered ${answer.code}"
                        }
                    )
                )
            }
            val body = answer.body
                ?: return Result.failure(MediaFileException("the server sent no file"))
            val declared = body.contentLength()
            if (declared > MAX_BYTES) {
                ApiLog.record("GET", pathOf(url), answer.code, millis, "media too large")
                return Result.failure(MediaFileException("the file is too large to play"))
            }
            val name = key(url) + extensionOf(url, answer.header("Content-Type"))
            val target = File(dir, name)
            val temp = File(dir, "$name.part")
            val written = runCatching {
                temp.outputStream().use { sink -> copyCapped(body.byteStream(), sink) }
            }
            val bytes = written.getOrNull()
            if (written.isFailure || bytes == null || bytes == 0L) {
                temp.delete()
                ApiLog.record("GET", pathOf(url), answer.code, millis, "media transfer failed")
                return Result.failure(MediaFileException("the download stopped before it finished"))
            }
            if (!looksLikeAudio(temp)) {
                temp.delete()
                ApiLog.record("GET", pathOf(url), answer.code, millis, "not audio")
                return Result.failure(MediaFileException("the server did not return audio"))
            }
            temp.renameTo(target)
            ApiLog.record("GET", pathOf(url), answer.code, millis, null)
            prune(keep = target)
            return Result.success(target)
        }
    }

    /** Streams [source] to [sink], refusing to write past [MAX_BYTES]; returns the byte count. */
    private fun copyCapped(source: InputStream, sink: OutputStream): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_BYTES) throw IOException("the file is too large to play")
            sink.write(buffer, 0, read)
        }
        sink.flush()
        return total
    }

    /**
     * Whether [file] starts with a container header a player can open.
     *
     * The point is to catch the case that used to reach "Couldn't play this voice message" with
     * no explanation: a `200` carrying an error page or a JSON body, saved and handed to the
     * player. `audio/mp4`, `audio/webm`, `audio/ogg`, `audio/mpeg`, `audio/wav` and `audio/amr`
     * are all recognised.
     */
    private fun looksLikeAudio(file: File): Boolean = runCatching {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        if (read < 4) return false
        fun at(index: Int, vararg bytes: Int) =
            read > index + bytes.size - 1 &&
                bytes.indices.all { (head[index + it].toInt() and 0xFF) == bytes[it] }
        when {
            // 'ftyp' at offset 4 — MP4/M4A, what this app records.
            at(4, 0x66, 0x74, 0x79, 0x70) -> true
            // EBML — WebM/Matroska, what a browser's MediaRecorder produces.
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> true
            at(0, 0x4F, 0x67, 0x67, 0x53) -> true // "OggS"
            at(0, 0x52, 0x49, 0x46, 0x46) -> true // "RIFF"
            at(0, 0x49, 0x44, 0x33) -> true // "ID3"
            at(0, 0x23, 0x21, 0x41, 0x4D, 0x52) -> true // "#!AMR"
            at(0, 0xFF, 0xF1) || at(0, 0xFF, 0xF9) -> true // ADTS AAC
            at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) || at(0, 0xFF, 0xF2) || at(0, 0xFF, 0xE3) -> true
            else -> false
        }
    }.getOrDefault(false)

    /** Keeps the cache under [CACHE_BYTES] by dropping the files read longest ago. */
    private fun prune(keep: File) {
        runCatching {
            val dir = keep.parentFile ?: return
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= CACHE_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= CACHE_BYTES) return
                if (file == keep) return@forEach
                total -= file.length()
                file.delete()
            }
        }
    }

    private fun cacheDir(): File? {
        val context = appContext ?: return null
        return File(context.cacheDir, "voice").apply { mkdirs() }
    }

    /** A stable file name per URL: the URL itself, hashed, so nothing user-visible leaks. */
    private fun key(url: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
    }.getOrElse { url.hashCode().toUInt().toString(16) }

    /**
     * The extension a player likes to see.
     *
     * The container is what matters, and it is sniffed from the bytes; this only keeps the file
     * name honest for the system decoder and for anyone reading a bug report.
     */
    private fun extensionOf(url: String, contentType: String?): String {
        val fromType = when {
            contentType == null -> null
            contentType.contains("mp4", true) || contentType.contains("m4a", true) -> "m4a"
            contentType.contains("webm", true) -> "webm"
            contentType.contains("ogg", true) || contentType.contains("opus", true) -> "ogg"
            contentType.contains("mpeg", true) -> "mp3"
            contentType.contains("wav", true) -> "wav"
            contentType.contains("amr", true) -> "amr"
            contentType.contains("aac", true) -> "aac"
            else -> null
        }
        val fromUrl = url.substringBefore('?').substringAfterLast('/')
            .substringAfterLast('.', "")
            .takeIf { it.length in 2..5 && it.all { char -> char.isLetterOrDigit() } }
        return ".${fromType ?: fromUrl ?: "audio"}"
    }

    /** The log line — the path only, the same shape `ApiLog` prints for REST calls. */
    private fun pathOf(url: String): String =
        runCatching { android.net.Uri.parse(url).encodedPath ?: url }.getOrDefault(url)

    private fun Throwable.short(): String = when (this) {
        is java.net.UnknownHostException -> "no connection"
        is java.net.SocketTimeoutException -> "the server did not answer"
        is IOException -> message ?: "network error"
        else -> message ?: javaClass.simpleName
    }
}

/** A media fetch that failed for a reason worth showing: [message] is already user-sized. */
class MediaFileException(message: String) : IOException(message)
