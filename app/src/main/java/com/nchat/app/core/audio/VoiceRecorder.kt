package com.nchat.app.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records a voice message to a real file.
 *
 * The reference records with `MediaRecorder`, uploads the blob through `POST /media/upload`
 * and sends a message whose `media_url` points at it — the audio itself is what the other
 * side plays. The app used to have only the animation: `sendVoice()` posted
 * `type = "audio"` with no file at all, so a voice bubble arrived with nothing behind it.
 *
 * AAC in an MP4 container (`.m4a`) is what both Android and the worker handle without
 * transcoding; the worker stores it as `media_type: "audio"` and serves it with byte ranges.
 */
class VoiceRecorder(private val context: Context) {

    /** A finished recording, ready to upload. */
    data class Clip(val bytes: ByteArray, val mimeType: String, val fileName: String)

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt: Long = 0L

    val isRecording: Boolean get() = recorder != null

    /** Milliseconds since [start]; 0 when idle. */
    fun elapsedMs(): Long = if (recorder == null) 0L else System.currentTimeMillis() - startedAt

    /**
     * Starts capturing. Returns false when the microphone is unavailable (permission denied,
     * already in use, no encoder) so the caller can put the UI back instead of pretending.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        release()
        val output = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(44_100)
            created.setAudioEncodingBitRate(64_000)
            created.setOutputFile(output.absolutePath)
            created.prepare()
            created.start()
            recorder = created
            file = output
            startedAt = System.currentTimeMillis()
            true
        } catch (error: Throwable) {
            // A device that refuses the encoder must not leave a half-open recorder behind.
            runCatching { created.release() }
            output.delete()
            recorder = null
            file = null
            false
        }
    }

    /**
     * Stops and returns the clip, or null when nothing usable was captured (too short, the
     * recorder never started, or `stop()` threw — which MediaRecorder does when it recorded
     * no frames at all).
     */
    fun stop(): Clip? {
        val current = recorder ?: return null
        recorder = null
        val output = file
        file = null
        // `MediaRecorder.stop()` throws a RuntimeException when it captured too few frames, and
        // on several devices it also throws for a recording that is perfectly playable. The file
        // is the source of truth: stop/release best-effort, then read whatever was written.
        runCatching { current.stop() }
        runCatching { current.release() }
        val bytes = output?.takeIf { it.length() > 0 }?.readBytes()
        output?.delete()
        if (bytes == null || bytes.isEmpty()) return null
        return Clip(bytes = bytes, mimeType = "audio/mp4", fileName = "voice.m4a")
    }

    /** Uses up and deletes the in-progress recording without sending it. */
    fun cancel() {
        val current = recorder
        recorder = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
        }
        file?.delete()
        file = null
    }

    private fun release() {
        recorder?.let { runCatching { it.release() } }
        recorder = null
        file?.delete()
        file = null
    }
}
