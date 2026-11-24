package com.nchat.app.core.util

/**
 * What is done to a picked video before it is uploaded.
 *
 * A phone camera writes a minute of video as a hundred megabytes or more, and
 * a chat attachment may be 24. Sent as recorded, anything longer than a few
 * seconds was refused. So a video is re-encoded first, the way every
 * messenger does it: a smaller picture at a bit rate chosen for watching on a
 * phone. The settings are a short ladder, and the first rung that fits the
 * limit with its sound is taken:
 *
 *     720 lines   1.8 Mbit/s    up to about 1 min 40 s
 *     480 lines   1.1 Mbit/s    up to about 2 min 30 s
 *     480 lines   0.7 Mbit/s    up to about 3 min 45 s
 *     360 lines   0.45 Mbit/s   up to about 5 min 30 s
 *
 * "Lines" is the shorter side of the picture, so a clip held upright keeps
 * its shape. A picture smaller than the rung is not enlarged.
 *
 * This file decides and nothing else; `VideoCompressor` does the work.
 */
object VideoPlan {

    /** The sound track of a re-encoded video, bits per second; part of what has to fit. */
    const val AUDIO_BITRATE = 128_000

    /**
     * The average bit rate at or under which a file that fits is sent as it is. The size of
     * its picture does not matter then: it is already as small as re-encoding would make it.
     */
    private const val COMPACT_BITRATE = 2_400_000L

    /** Left for the container and for an encoder that overshoots what it was asked for. */
    private const val HEADROOM = 0.94

    private class Rung(val side: Int, val bitrate: Int)

    private val LADDER = listOf(Rung(720, 1_800_000), Rung(480, 1_100_000), Rung(480, 700_000), Rung(360, 450_000))

    sealed class Decision {
        /** Send the file as it is. */
        object Keep : Decision() {
            override fun toString() = "Keep"
        }

        /** Re-encode to [height] pixels (of the picture as it is watched, upright) at [videoBitrate] bits per second. */
        class Compress(val height: Int, val videoBitrate: Int) : Decision() {
            override fun toString() = "Compress(height=$height, videoBitrate=$videoBitrate)"
        }

        /** No rung makes it fit — or nothing is known about a file that is too large as it is. */
        object TooLong : Decision() {
            override fun toString() = "TooLong"
        }
    }

    /**
     * [width] and [height] are the stored picture and [rotation] the degrees
     * it is turned by when shown (a phone held upright stores a landscape
     * picture and says 90). Values that are not known are zero or negative.
     */
    fun decide(width: Int, height: Int, rotation: Int, durationMs: Long, sizeBytes: Long, limitBytes: Long): Decision {
        val fitsAsItIs = sizeBytes in 1..limitBytes
        if (width <= 0 || height <= 0 || durationMs <= 0) {
            // Nothing to plan from: it goes as it is, or not at all.
            return if (fitsAsItIs) Decision.Keep else Decision.TooLong
        }
        val turned = rotation % 180 != 0
        val shownWidth = if (turned) height else width
        val shownHeight = if (turned) width else height
        val side = minOf(shownWidth, shownHeight)
        val seconds = durationMs / 1000.0

        // What this would produce anyway, or less: re-encoding would only cost time and quality.
        if (fitsAsItIs && sizeBytes * 8 / seconds <= COMPACT_BITRATE) return Decision.Keep

        val budget = limitBytes * 8 * HEADROOM / seconds - AUDIO_BITRATE
        val rung = LADDER.firstOrNull { it.bitrate <= budget } ?: return Decision.TooLong
        val targetSide = minOf(rung.side, side)
        return Decision.Compress(even(shownHeight.toLong() * targetSide / side.toDouble()), rung.bitrate)
    }

    /** The nearest even number, at least 2: encoders refuse odd sizes. */
    private fun even(value: Double): Int = maxOf(2, (Math.round(value / 2.0) * 2).toInt())
}
