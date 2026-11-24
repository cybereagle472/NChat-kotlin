package com.nchat.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a video between the picker and the upload.
 *
 * A phone records a minute of video as a hundred megabytes or more; a chat
 * attachment may be 24. Before this, a clip longer than a few seconds was
 * refused as too large. The plan decides, from what the file says about
 * itself, whether to send it as it is, to re-encode it — and how small — or
 * to say that no setting will make it fit.
 */
class VideoPlanTest {
    private val limit = 24L * 1024 * 1024

    private fun megabytes(value: Double): Long = (value * 1024 * 1024).toLong()

    /** A file of [megabitsPerSecond] on average, [seconds] long. */
    private fun decide(width: Int, height: Int, rotation: Int, seconds: Int, megabitsPerSecond: Double): VideoPlan.Decision =
        VideoPlan.decide(
            width = width,
            height = height,
            rotation = rotation,
            durationMs = seconds * 1000L,
            sizeBytes = (megabitsPerSecond * 1_000_000 / 8 * seconds).toLong(),
            limitBytes = limit,
        )

    private fun compressed(decision: VideoPlan.Decision): VideoPlan.Decision.Compress {
        assertTrue("expected a re-encode, got $decision", decision is VideoPlan.Decision.Compress)
        return decision as VideoPlan.Decision.Compress
    }

    @Test
    fun aPhoneClipIsBroughtDownToSevenTwenty() {
        // Half a minute straight from the camera: 1080p at 20 Mbit/s, 75 MB.
        val landscape = compressed(decide(1920, 1080, rotation = 0, seconds = 30, megabitsPerSecond = 20.0))
        assertEquals(720, landscape.height)
        assertEquals(1_800_000, landscape.videoBitrate)
        // 4K: the same picture in the end.
        val ultra = compressed(decide(3840, 2160, rotation = 0, seconds = 60, megabitsPerSecond = 45.0))
        assertEquals(720, ultra.height)
        assertEquals(1_800_000, ultra.videoBitrate)
    }

    @Test
    fun aClipHeldUprightKeepsItsShape() {
        // The camera writes a portrait clip as a landscape picture plus "turn it by 90".
        val portrait = compressed(decide(1920, 1080, rotation = 90, seconds = 30, megabitsPerSecond = 20.0))
        assertEquals("720 wide, so 1280 tall", 1280, portrait.height)
        assertEquals(1280, compressed(decide(1920, 1080, rotation = 270, seconds = 30, megabitsPerSecond = 20.0)).height)
        assertEquals(720, compressed(decide(1920, 1080, rotation = 180, seconds = 30, megabitsPerSecond = 20.0)).height)
        // A file that stores the picture upright already.
        assertEquals(1280, compressed(decide(1080, 1920, rotation = 0, seconds = 30, megabitsPerSecond = 20.0)).height)
    }

    @Test
    fun aLongerClipGetsASmallerPictureInsteadOfBeingRefused() {
        // What fits in 24 MB decides: about 100 s at the first setting, then smaller ones.
        assertEquals(720, compressed(decide(1920, 1080, 0, seconds = 95, megabitsPerSecond = 20.0)).height)
        val threeMinutes = compressed(decide(1920, 1080, 0, seconds = 180, megabitsPerSecond = 20.0))
        assertEquals(480, threeMinutes.height)
        assertEquals(700_000, threeMinutes.videoBitrate)
        val twoMinutes = compressed(decide(1920, 1080, 0, seconds = 120, megabitsPerSecond = 20.0))
        assertEquals(480, twoMinutes.height)
        assertEquals(1_100_000, twoMinutes.videoBitrate)
        val fiveMinutes = compressed(decide(1920, 1080, 0, seconds = 300, megabitsPerSecond = 20.0))
        assertEquals(360, fiveMinutes.height)
        assertEquals(450_000, fiveMinutes.videoBitrate)
        // Whatever is chosen fits, sound included, with room to spare for the container.
        for (seconds in listOf(30, 95, 120, 180, 300)) {
            val plan = compressed(decide(1920, 1080, 0, seconds, megabitsPerSecond = 20.0))
            val bytes = (plan.videoBitrate + VideoPlan.AUDIO_BITRATE).toLong() * seconds / 8
            assertTrue("$seconds s would be $bytes bytes", bytes < limit)
        }
    }

    @Test
    fun whatNoSettingMakesFitIsSaidToBeTooLong() {
        assertEquals(VideoPlan.Decision.TooLong, decide(1920, 1080, 0, seconds = 420, megabitsPerSecond = 20.0))
        assertEquals(VideoPlan.Decision.TooLong, decide(640, 360, 0, seconds = 3600, megabitsPerSecond = 0.5))
    }

    @Test
    fun aClipThatIsSmallAlreadyGoesAsItIs() {
        // What this would produce anyway, or less: re-encoding it would only cost time and quality.
        assertEquals(VideoPlan.Decision.Keep, decide(1280, 720, 0, seconds = 60, megabitsPerSecond = 1.5))
        assertEquals(VideoPlan.Decision.Keep, decide(854, 480, 0, seconds = 120, megabitsPerSecond = 1.0))
        assertEquals(VideoPlan.Decision.Keep, decide(720, 1280, 0, seconds = 20, megabitsPerSecond = 2.2))
        // A large picture at a low bit rate is small too: making it 720 would save next to nothing.
        assertEquals(VideoPlan.Decision.Keep, decide(1920, 1080, 0, seconds = 30, megabitsPerSecond = 2.0))
        // Small in megabytes but large in pixels and bit rate: a few seconds of 4K still shrink tenfold.
        val short = compressed(decide(3840, 2160, 0, seconds = 4, megabitsPerSecond = 40.0))
        assertEquals(720, short.height)
        // Compact in bit rate but over the limit because it is long: planned like any other.
        val long = compressed(decide(1280, 720, 0, seconds = 150, megabitsPerSecond = 2.0))
        assertEquals(480, long.height)
    }

    @Test
    fun aSmallPictureIsNeverBlownUp() {
        val small = compressed(decide(640, 360, 0, seconds = 30, megabitsPerSecond = 8.0))
        assertEquals(360, small.height)
        val portrait = compressed(decide(360, 640, 0, seconds = 30, megabitsPerSecond = 8.0))
        assertEquals(640, portrait.height)
    }

    @Test
    fun theHeightIsAlwaysEven() {
        // Encoders refuse odd sizes. 853 x 480 held upright would be 853 tall.
        val odd = compressed(decide(853, 480, rotation = 90, seconds = 30, megabitsPerSecond = 8.0))
        assertEquals(854, odd.height)
        for (width in 1001..1011) {
            val plan = compressed(decide(width, 1777, 0, seconds = 30, megabitsPerSecond = 20.0))
            assertEquals("for $width", 0, plan.height % 2)
            assertTrue(plan.height >= 2)
        }
    }

    @Test
    fun aFileThatSaysNothingAboutItselfGoesAsItIsOrNotAtAll() {
        fun unknown(width: Int, height: Int, durationMs: Long, size: Long) =
            VideoPlan.decide(width, height, rotation = 0, durationMs = durationMs, sizeBytes = size, limitBytes = limit)
        assertEquals(VideoPlan.Decision.Keep, unknown(0, 0, 0L, megabytes(3.0)))
        assertEquals(VideoPlan.Decision.Keep, unknown(1920, 1080, 0L, megabytes(3.0)))
        assertEquals(VideoPlan.Decision.Keep, unknown(-1, -1, 30_000L, megabytes(24.0)))
        assertEquals("too large to send as it is, and nothing to plan from", VideoPlan.Decision.TooLong, unknown(0, 0, 0L, megabytes(25.0)))
        assertEquals(VideoPlan.Decision.TooLong, unknown(1920, 1080, 0L, megabytes(200.0)))
        // A size that is not known at all is not assumed to be small.
        assertEquals(VideoPlan.Decision.TooLong, unknown(0, 0, 0L, -1L))
        // With the picture and the length known, the size need not be: the plan does not depend on it.
        val planned = VideoPlan.decide(1920, 1080, 0, durationMs = 30_000L, sizeBytes = -1L, limitBytes = limit)
        assertEquals(720, compressed(planned).height)
    }
}
