package com.nchat.app.core.audio

import org.json.JSONArray
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The waveform a voice note is drawn with — captured here, rendered by both clients.
 *
 * The reference stores the recorded amplitude in the message's `thumbnail_url` (a JSON array of
 * normalised floats, `ChatView.tsx:37-60` + `chatService.sendMessage`'s `waveform` parameter) and
 * draws the bubble from it. This client used to draw voice notes with a fixed pattern
 * (`4 + (index * 5) % 12`) no matter what was said, and sent nothing at all, so a voice note
 * recorded on Android arrived on the web as a flat placeholder and one recorded on the web
 * arrived here as the same repeating decoration.
 *
 * Samples are the raw `MediaRecorder.getMaxAmplitude()` readings (`0..32767`, one per tick).
 * They are bucketed down to [BARS], normalised to the clip's own loudest moment — a quiet
 * speaker still gets a readable waveform instead of a flat line — lifted with a light gamma so
 * the quieter-but-real bars stay visible, and floored so silence is a thin bar, not nothing.
 */
object VoiceWaveform {

    /** Bar count the reference stores (its `VOICE_WAVEFORM_BARS`). */
    const val BARS = 27

    /**
     * Raw amplitudes → `0..1` bar heights, or an empty list for a clip with no signal.
     *
     * An empty result is deliberate: sending `[]` would tell the other client "this clip has a
     * waveform" and then draw nothing, so the caller should send no waveform at all and let the
     * bubble fall back.
     */
    fun down(samples: List<Int>, bars: Int = BARS): List<Float> {
        val usable = samples.filter { it > 0 }
        if (usable.isEmpty() || bars <= 0) return emptyList()
        val loudest = usable.maxOrNull()?.toFloat() ?: return emptyList()
        if (loudest <= 0f) return emptyList()
        val bucket = samples.size.toFloat() / bars
        return (0 until bars).map { index ->
            val start = (index * bucket).toInt().coerceIn(0, samples.lastIndex)
            val end = ((index + 1) * bucket).toInt().coerceIn(start + 1, samples.size)
            val average = samples.subList(start, end).average().toFloat()
            val level = (average / loudest).coerceIn(0f, 1f)
            // Gamma + floor: the same treatment the web bubble applies to the same numbers.
            (0.12f + 0.88f * level.pow(0.7f)).coerceIn(0f, 1f)
        }
    }

    /** The `thumbnail_url` payload for a finished recording, or null when there is no signal. */
    fun json(samples: List<Int>, bars: Int = BARS): String? {
        val levels = down(samples, bars)
        if (levels.isEmpty()) return null
        val array = JSONArray()
        levels.forEach { level -> array.put(((level * 100f).roundToInt() / 100f).toDouble()) }
        return array.toString()
    }

    /**
     * `thumbnail_url` → bar heights, resampled to [bars] so the bubble always fits.
     *
     * Anything that is not a JSON array of numbers — an image's preview URL, a video's poster,
     * null, garbage — parses to an empty list, which is exactly what the bubble's fallback
     * wants. Never throws.
     */
    fun parse(stored: String?, bars: Int = 24): List<Float> {
        val text = stored?.trim().orEmpty()
        if (!text.startsWith("[")) return emptyList()
        val values = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val value = array.optDouble(index, Double.NaN)
                if (value.isNaN()) null else value.toFloat()
            }
        }.getOrDefault(emptyList())
        if (values.isEmpty() || values.size < 2) return emptyList()
        val target = bars.coerceAtLeast(2)
        if (values.size == target) return values.map { it.coerceIn(0f, 1f) }
        return (0 until target).map { index ->
            val position = (index.toFloat() / target) * values.size
            values[position.toInt().coerceIn(0, values.lastIndex)].coerceIn(0f, 1f)
        }
    }
}
