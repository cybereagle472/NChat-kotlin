package com.nchat.app.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * A photo, a video, a voice message or a file is sealed on the phone under a
 * key of its own; the server stores bytes it cannot read, and the key travels
 * inside the sealed message. These tests are about three things: what is
 * sealed comes out again exactly, nothing that was changed on the way comes
 * out at all, and the bytes the server holds say nothing but a size bucket.
 */
class E2eeAttachmentTest {
    private val key = ByteArray(32) { it.toByte() }
    private val prefix = ByteArray(7) { (0xa0 + it).toByte() }

    private fun content(size: Int): ByteArray = ByteArray(size) { ((it * 7 + 3) and 0xff).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    // ------------------------------------------------------------ the format

    @Test
    fun theFormatIsTheOneWrittenDown() {
        // Computed outside Kotlin, twice (Node's crypto and Python's cryptography), from the
        // description in E2eeAttachment: version byte, 7-byte nonce prefix, AES-256-GCM chunks
        // of 64 KiB under nonce = prefix ‖ chunk number ‖ last-chunk flag, version byte as
        // associated data, content padded with zeros to its size bucket.
        val expected = mapOf(
            "hello".toByteArray() to (1048 to "8786553e9a481b1e99ad4432137a9edfb15bde45e4e18b52157d95fba0fefa34"),
            content(70_000) to (73_768 to "a6a66de576f988f14cb504e0134a2ab7e1cabeb597d70d4a09670ff5a8a9f26a"),
            ByteArray(0) to (1048 to "32e56a00ae7f54ebb9b81fb0084ee2ed19f6b2e792e2da71538022c0a23c3d76"),
            content(65_536) to (65_560 to "cf1b05df93e9b3763cb4677042f3f4aaaeca4d192e9f8a138f70f6e908d0e72f"),
        )
        for ((plain, pinned) in expected) {
            val sealed = ByteArrayOutputStream()
            E2eeAttachment.sealWithPrefix(key, prefix, ByteArrayInputStream(plain), plain.size.toLong(), sealed)
            assertEquals(pinned.first, sealed.size())
            assertEquals(pinned.second, sha256(sealed.toByteArray()))
            assertEquals(pinned.first.toLong(), E2eeAttachment.sealedSize(plain.size.toLong()))
        }
    }

    @Test
    fun sizesAreRoundedUpInCoarseSteps() {
        val pinned = mapOf(
            0L to 1024L, 1L to 1024L, 1024L to 1024L, 1025L to 1088L, 1088L to 1088L, 1089L to 1152L,
            2048L to 2048L, 2049L to 2176L, 65_536L to 65_536L, 65_537L to 69_632L, 70_000L to 73_728L,
            1_048_576L to 1_048_576L, 1_048_577L to 1_114_112L, 46_137_344L to 46_137_344L,
        )
        for ((size, padded) in pinned) assertEquals("padded size of $size", padded, E2eeAttachment.paddedSize(size))

        // Never smaller than the content, never more than a sixteenth larger once past the smallest bucket.
        var previous = 0L
        for (size in listOf(1_500L, 4_097L, 100_001L, 999_999L, 5_000_000L, 44L * 1024 * 1024)) {
            val padded = E2eeAttachment.paddedSize(size)
            assertTrue(padded >= size)
            assertTrue("at most 6.25 % over for $size", (padded - size) * 16 <= size)
            assertTrue(padded >= previous)
            previous = padded
        }
        // Sixteen sizes between one megabyte and two: that is all the server can tell apart there.
        val buckets = (1_048_577L..2_097_152L step 1_021L).map { E2eeAttachment.paddedSize(it) }.toSet()
        assertEquals(16, buckets.size)
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun whatIsSealedOpensAgainAtEverySizeAroundTheChunkEdges() {
        for (size in listOf(0, 1, 15, 1023, 1024, 1025, 65_535, 65_536, 65_537, 69_632, 131_072, 131_073, 200_000)) {
            val plain = content(size)
            val sealed = E2eeAttachment.seal(key, plain)
            assertEquals(E2eeAttachment.sealedSize(size.toLong()), sealed.size.toLong())
            assertArrayEquals("size $size", plain, E2eeAttachment.open(key, sealed, size.toLong()))
        }
    }

    @Test
    fun streamsOfAnyShapeGiveTheSameResult() {
        // An input that hands out a few bytes at a time, as a network or a slow disk does.
        class Trickle(private val bytes: ByteArray) : InputStream() {
            private var at = 0
            override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xff else -1
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (at >= bytes.size) return -1
                val count = minOf(length, 1 + at % 7, bytes.size - at)
                System.arraycopy(bytes, at, target, offset, count)
                at += count
                return count
            }
        }
        val plain = content(300_001)
        val sealed = ByteArrayOutputStream()
        E2eeAttachment.seal(key, Trickle(plain), plain.size.toLong(), sealed)
        val opened = ByteArrayOutputStream()
        assertTrue(E2eeAttachment.open(key, Trickle(sealed.toByteArray()), plain.size.toLong(), opened))
        assertArrayEquals(plain, opened.toByteArray())
    }

    @Test
    fun anInputShorterThanAnnouncedIsAnErrorNotAShorterFile() {
        try {
            E2eeAttachment.seal(key, ByteArrayInputStream(content(100)), 101L, ByteArrayOutputStream())
            fail("sealed a file that was not all there")
        } catch (_: IOException) {
        }
        // A longer one is sealed up to the announced length: the caller said how much is the file.
        val sealed = ByteArrayOutputStream()
        E2eeAttachment.seal(key, ByteArrayInputStream(content(100)), 60L, sealed)
        assertArrayEquals(content(60), E2eeAttachment.open(key, sealed.toByteArray(), 60L))
    }

    // ------------------------------------------------- what the server holds

    @Test
    fun theServerLearnsABucketAndNothingElse() {
        val quiet = ByteArray(70_000)
        val loud = ByteArray(70_000) { 0x41 }
        val first = E2eeAttachment.seal(key, loud)
        val second = E2eeAttachment.seal(key, loud)
        assertFalse("the same file sealed twice is not the same bytes", first.contentEquals(second))
        assertEquals(first.size, E2eeAttachment.seal(key, quiet).size)
        assertEquals("anything in the same bucket has the same size", first.size, E2eeAttachment.seal(key, ByteArray(73_000)).size)
        assertNotEquals(first.size, E2eeAttachment.seal(key, ByteArray(74_000)).size)
        // No run of the content shows through.
        var longestRun = 0
        var run = 0
        for (byte in first) {
            run = if (byte == 0x41.toByte()) run + 1 else 0
            longestRun = maxOf(longestRun, run)
        }
        assertTrue(longestRun < 8)
    }

    @Test
    fun keysAreRandomAndOfTheRightSize() {
        val a = E2eeAttachment.newKey()
        val b = E2eeAttachment.newKey()
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
        for (wrong in listOf(ByteArray(0), ByteArray(16), ByteArray(31), ByteArray(33))) {
            try {
                E2eeAttachment.seal(wrong, content(10))
                fail("sealed under a key of ${wrong.size} bytes")
            } catch (_: IllegalArgumentException) {
            }
            assertNull(E2eeAttachment.open(wrong, E2eeAttachment.seal(key, content(10)), 10L))
        }
    }

    // --------------------------------------------------------------- tamper

    @Test
    fun aWrongKeyOpensNothing() {
        val sealed = E2eeAttachment.seal(key, content(5_000))
        assertNull(E2eeAttachment.open(ByteArray(32) { 9 }, sealed, 5_000L))
    }

    @Test
    fun anyChangedByteIsRefused() {
        val plain = content(140_000)
        val sealed = E2eeAttachment.seal(key, plain)
        // Version, nonce prefix, first chunk, the tag between chunks, a middle chunk, the last tag.
        for (position in listOf(0, 1, 7, 8, 9, 65_543, 65_544 + 8, 65_560 + 8, 100_000, sealed.size - 17, sealed.size - 1)) {
            val damaged = sealed.copyOf()
            damaged[position] = (damaged[position].toInt() xor 0x01).toByte()
            assertNull("byte $position", E2eeAttachment.open(key, damaged, plain.size.toLong()))
        }
        assertArrayEquals(plain, E2eeAttachment.open(key, sealed, plain.size.toLong()))
    }

    @Test
    fun aFileCutShortOrGrownIsRefused() {
        val plain = content(140_000) // three chunks once padded
        val sealed = E2eeAttachment.seal(key, plain)
        val chunk = 65_536 + 16
        assertEquals(8 + 2 * chunk + (E2eeAttachment.paddedSize(140_000L).toInt() - 2 * 65_536) + 16, sealed.size)

        assertNull("one byte short", E2eeAttachment.open(key, sealed.copyOf(sealed.size - 1), plain.size.toLong()))
        assertNull("the last chunk gone", E2eeAttachment.open(key, sealed.copyOf(8 + 2 * chunk), plain.size.toLong()))
        // Even when what is left would cover the length that is asked for.
        assertNull("the last chunk gone, less asked for", E2eeAttachment.open(key, sealed.copyOf(8 + 2 * chunk), 100_000L))
        assertNull("only the first chunk", E2eeAttachment.open(key, sealed.copyOf(8 + chunk), 60_000L))
        assertNull("a byte more", E2eeAttachment.open(key, sealed + byteArrayOf(0), plain.size.toLong()))
        assertNull("the header alone", E2eeAttachment.open(key, sealed.copyOf(8), 0L))
        assertNull("nothing", E2eeAttachment.open(key, ByteArray(0), 0L))
        assertNull("no header", E2eeAttachment.open(key, sealed.copyOfRange(8, sealed.size), plain.size.toLong()))
    }

    @Test
    fun chunksCannotBeMovedOrRepeated() {
        val plain = content(200_000) // four chunks
        val sealed = E2eeAttachment.seal(key, plain)
        val chunk = 65_536 + 16
        fun part(index: Int) = sealed.copyOfRange(8 + index * chunk, minOf(sealed.size, 8 + (index + 1) * chunk))
        val header = sealed.copyOf(8)

        assertNull("two chunks swapped", E2eeAttachment.open(key, header + part(1) + part(0) + part(2) + part(3), plain.size.toLong()))
        assertNull("a chunk twice", E2eeAttachment.open(key, header + part(0) + part(0) + part(1) + part(2) + part(3), plain.size.toLong()))
        assertNull("a chunk left out", E2eeAttachment.open(key, header + part(0) + part(2) + part(3), plain.size.toLong()))
        // A chunk of another file sealed under the same key does not fit in either.
        val other = E2eeAttachment.seal(key, content(200_000))
        val foreign = other.copyOfRange(8 + chunk, 8 + 2 * chunk)
        assertNull("a chunk of another file", E2eeAttachment.open(key, header + part(0) + foreign + part(2) + part(3), plain.size.toLong()))
        assertArrayEquals(plain, E2eeAttachment.open(key, header + part(0) + part(1) + part(2) + part(3), plain.size.toLong()))
    }

    @Test
    fun theLengthThatWasSealedIsTheLengthThatComesOut() {
        val plain = content(3_000)
        val sealed = E2eeAttachment.seal(key, plain)
        assertNull("more than was sealed, padding included", E2eeAttachment.open(key, sealed, E2eeAttachment.paddedSize(3_000L) + 1))
        assertNull(E2eeAttachment.open(key, sealed, -1L))
        // The padding is zeros and authenticated; asking for less than the content is the caller's business.
        assertArrayEquals(plain.copyOf(10), E2eeAttachment.open(key, sealed, 10L))
        val failed = ByteArrayOutputStream()
        assertFalse(E2eeAttachment.open(key, ByteArrayInputStream(sealed.copyOf(sealed.size - 1)), 3_000L, failed))
    }

    @Test
    fun aFileOverTheLimitIsNotSealed() {
        assertTrue(E2eeAttachment.MAX_BYTES >= 40L * 1024 * 1024)
        assertTrue("fits the server's 49 MiB upload", E2eeAttachment.sealedSize(E2eeAttachment.MAX_BYTES) < 49L * 1024 * 1024)
        try {
            E2eeAttachment.seal(key, ByteArrayInputStream(ByteArray(0)), E2eeAttachment.MAX_BYTES + 1, ByteArrayOutputStream())
            fail("sealed a file over the limit")
        } catch (_: IllegalArgumentException) {
        }
    }

    // -------------------------------------------------------------- pointer

    @Test
    fun thePointerKeepsEveryField() {
        val pointer = E2eeAttachment.Pointer(
            url = "https://chat.example/media/file/u1/abc",
            key = key,
            size = 123_456L,
            mime = "image/jpeg",
            name = "holiday – 日本.jpg",
            duration = "0:42",
            waveform = "3,9,27,81",
            thumbnail = byteArrayOf(-1, -40, -1, 0, 7),
        )
        val decoded = E2eeAttachment.Pointer.decode(pointer.encode())!!
        assertEquals(pointer.url, decoded.url)
        assertArrayEquals(key, decoded.key)
        assertEquals(123_456L, decoded.size)
        assertEquals("image/jpeg", decoded.mime)
        assertEquals("holiday – 日本.jpg", decoded.name)
        assertEquals("0:42", decoded.duration)
        assertEquals("3,9,27,81", decoded.waveform)
        assertArrayEquals(byteArrayOf(-1, -40, -1, 0, 7), decoded.thumbnail)

        val bare = E2eeAttachment.Pointer.decode(E2eeAttachment.Pointer("https://chat.example/f", key, 0L).encode())!!
        assertNull(bare.mime)
        assertNull(bare.name)
        assertNull(bare.duration)
        assertNull(bare.waveform)
        assertNull(bare.thumbnail)
    }

    @Test
    fun whatIsNotAPointerIsRefused() {
        val good = E2eeAttachment.Pointer("https://chat.example/f", key, 10L).encode()
        assertTrue(E2eeAttachment.Pointer.decode(good) != null)
        val k = encodeBase64(key)
        val refused = listOf(
            "",
            "not json",
            "[]",
            "{}",
            """{"u":"https://chat.example/f","z":10}""",
            """{"k":"$k","z":10}""",
            """{"u":"","k":"$k","z":10}""",
            """{"u":"https://chat.example/f","k":"${encodeBase64(ByteArray(16))}","z":10}""",
            """{"u":"https://chat.example/f","k":"!!!","z":10}""",
            """{"u":"https://chat.example/f","k":"$k","z":-1}""",
            """{"u":"https://chat.example/f","k":"$k","z":"ten"}""",
            // A size is a number. Written as text it is something else's idea of this format.
            """{"u":"https://chat.example/f","k":"$k","z":"10"}""",
            """{"u":"https://chat.example/f","k":"$k","z":10.5}""",
            """{"u":"https://chat.example/f","k":"$k"}""",
            """{"u":"https://chat.example/f","k":"$k","z":${E2eeAttachment.MAX_BYTES + 1}}""",
        )
        for (text in refused) assertNull(text, E2eeAttachment.Pointer.decode(text))
        // A field from a later version is not a reason to refuse the file.
        val later = E2eeAttachment.Pointer.decode("""{"u":"https://chat.example/f","k":"$k","z":10,"future":{"x":1}}""")!!
        assertEquals(10L, later.size)
    }
}
