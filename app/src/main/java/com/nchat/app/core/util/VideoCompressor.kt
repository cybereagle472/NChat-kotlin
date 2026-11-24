package com.nchat.app.core.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Re-encodes a picked video the way [VideoPlan] decided: a smaller picture, H.264, at a bit
 * rate meant for watching on a phone.
 *
 * The work is done by AndroidX Media3's `Transformer`, which drives the phone's own hardware
 * decoder and encoder — nothing is bundled, and a minute of video takes seconds, not minutes.
 * It has to be created and started on a thread with a looper (the main thread here); the
 * encoding itself runs on Media3's own threads.
 *
 * Everything here may fail on some phone — an encoder that refuses the size, a file the decoder
 * cannot read. Failure is an answer (false), never an exception: the caller then sends the
 * original when that is small enough, exactly as before this existed.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object VideoCompressor {

    /** Longer than this and the phone is not going to finish; the original is tried instead. */
    private const val TIMEOUT_MS = 10 * 60 * 1000L

    /** What a video file says about itself; anything it does not say is zero, or -1 for the size. */
    class Source(val width: Int, val height: Int, val rotation: Int, val durationMs: Long, val sizeBytes: Long)

    fun describe(context: Context, uri: Uri): Source {
        var width = 0
        var height = 0
        var rotation = 0
        var duration = 0L
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                fun number(key: Int): Long = retriever.extractMetadata(key)?.trim()?.toLongOrNull() ?: 0L
                width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                rotation = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
                duration = number(MediaMetadataRetriever.METADATA_KEY_DURATION)
            } finally {
                runCatching { retriever.release() }
            }
        }
        return Source(width, height, rotation, duration, sizeOf(context, uri))
    }

    /** The size of the file behind [uri], or -1 when the provider does not say. */
    private fun sizeOf(context: Context, uri: Uri): Long {
        val declared = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (declared != null && declared > 0) return declared
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()?.takeIf { it > 0 } ?: -1L
    }

    /**
     * Re-encodes [uri] into [output] as [plan] says. True when [output] now holds the video;
     * false when this phone could not do it, and then [output] is not to be used.
     */
    suspend fun compress(context: Context, uri: Uri, plan: VideoPlan.Decision.Compress, output: File): Boolean {
        val main = Handler(Looper.getMainLooper())
        val app = context.applicationContext
        val finished = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                var running: Transformer? = null
                main.post {
                    try {
                        val transformer = Transformer.Builder(app)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setEncoderFactory(
                                DefaultEncoderFactory.Builder(app)
                                    .setRequestedVideoEncoderSettings(
                                        VideoEncoderSettings.Builder().setBitrate(plan.videoBitrate).build()
                                    )
                                    .build()
                            )
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (continuation.isActive) continuation.resume(true)
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException
                                ) {
                                    if (continuation.isActive) continuation.resume(false)
                                }
                            })
                            .build()
                        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                            .setEffects(
                                Effects(
                                    emptyList(),
                                    listOf<androidx.media3.common.Effect>(Presentation.createForHeight(plan.height))
                                )
                            )
                            .build()
                        running = transformer
                        transformer.start(item, output.absolutePath)
                    } catch (problem: Throwable) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                // Given up on (the timeout, or the send was dropped): stop the encoder too.
                continuation.invokeOnCancellation { main.post { runCatching { running?.cancel() } } }
            }
        }
        return finished == true && output.length() > 0
    }
}

/**
 * A picked video, ready to send: re-encoded when [VideoPlan] says so, as it is when it is small
 * already. Takes as long as the encoding takes — call it off the main thread's critical path.
 *
 * Throws [IllegalStateException] with a reason short enough to show when the video cannot be
 * sent at all.
 */
suspend fun prepareVideo(context: Context, uri: Uri): PickedFile = withContext(Dispatchers.IO) {
    val limit = PickedFile.MAX_BYTES.toLong()
    val source = VideoCompressor.describe(context, uri)
    val poster = MediaPreparer.videoPoster(context, uri)

    fun asItIs(): PickedFile = readPickedFile(context, uri)?.copy(thumbnail = poster)
        ?: throw IllegalStateException("this video is too large to send")

    var allowed = limit
    repeat(2) {
        when (val plan = VideoPlan.decide(source.width, source.height, source.rotation, source.durationMs, source.sizeBytes, allowed)) {
            VideoPlan.Decision.Keep -> return@withContext asItIs()
            VideoPlan.Decision.TooLong -> throw IllegalStateException("this video is too long to send")
            is VideoPlan.Decision.Compress -> {
                val output = File(context.cacheDir, "video-out-${System.nanoTime()}.mp4")
                try {
                    // This phone could not re-encode it: the original goes, when it is small enough.
                    if (!VideoCompressor.compress(context, uri, plan, output)) return@withContext asItIs()
                    if (output.length() in 1..limit) {
                        val name = (displayName(context, uri) ?: "video").substringBeforeLast('.')
                        return@withContext PickedFile("$name.mp4", "video/mp4", output.readBytes(), poster)
                    }
                } finally {
                    output.delete()
                }
                // The encoder wrote more than it was asked for: once more, planned for less.
                allowed = limit * 4 / 5
            }
        }
    }
    throw IllegalStateException("this video is too long to send")
}

/** The name the provider shows for [uri], or null. */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }
