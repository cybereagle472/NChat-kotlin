package com.nchat.app.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The archive is the only place the content of a sealed message exists after
 * its ratchet key is spent, so these tests are about never losing an entry
 * and never leaving one readable on disk.
 */
class E2eeArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = ByteArray(32) { (it * 5 + 3).toByte() }
    private val otherKey = ByteArray(32) { (it * 11 + 7).toByte() }

    private fun directory(): File = folder.root.resolve("archive")

    private fun archive(tailBytes: Long = 64L * 1024L, maxLoadedEntries: Int = 12_000) =
        E2eeArchive(directory(), key, tailBytes, maxLoadedEntries)

    private fun logs(): List<File> = directory().listFiles().orEmpty().toList()

    private fun ByteArray.holds(needle: String): Boolean {
        val wanted = needle.toByteArray(Charsets.UTF_8)
        if (wanted.isEmpty() || wanted.size > size) return false
        for (start in 0..size - wanted.size) {
            var index = 0
            while (index < wanted.size && this[start + index] == wanted[index]) index++
            if (index == wanted.size) return true
        }
        return false
    }

    // -------------------------------------------------------------- payload

    @Test
    fun payloadCodecKeepsTypeAndEveryCharacter() {
        val bodies = listOf("", "hello", "line one\nline two\ttab", "emoji 😀👨‍👩‍👧 and عربى and 日本語", "nul\u0000inside", "x".repeat(70_000))
        for (body in bodies) {
            val decoded = E2eePayload.decode(E2eePayload.text(body).encode())
            assertEquals(E2eePayload.TEXT, decoded.type)
            assertEquals(body, decoded.body)
        }
        // A type this build has never heard of survives intact — the reader decides what to show.
        val future = E2eePayload.decode(E2eePayload(200, "{\"k\":1}").encode())
        assertEquals(200, future.type)
        assertEquals("{\"k\":1}", future.body)
        // Decoding is total: authenticated bytes always become a payload.
        assertEquals(0, E2eePayload.decode(ByteArray(0)).type)
        assertEquals("", E2eePayload.decode(byteArrayOf(1)).body)
    }

    @Test
    fun payloadCarriesTheMessageItRepliesTo() {
        val reply = E2eePayload.decode(E2eePayload.text("so do I", replyTo = "m-41").encode())
        assertEquals("so do I", reply.body)
        assertEquals("m-41", reply.replyTo)
        assertNull(E2eePayload.decode(E2eePayload.text("not a reply").encode()).replyTo)
        assertNull("an empty id is no reply", E2eePayload.text("x", replyTo = "").replyTo)
        // A reply can be a reply with nothing else to say (a shared position, say).
        val bare = E2eePayload.decode(E2eePayload(E2eePayload.LOCATION, "", "m-7").encode())
        assertEquals("", bare.body)
        assertEquals("m-7", bare.replyTo)
    }

    @Test
    fun payloadSizeIsItsContentPlusAFewBytes() {
        // Sealed messages must fit where the same text fits unsealed, whatever characters it is made of.
        for (body in listOf("x".repeat(60_000), "\u0000\"\\".repeat(20_000), "😀".repeat(15_000))) {
            val overhead = E2eePayload.text(body, replyTo = "m-1").encode().size - body.toByteArray(Charsets.UTF_8).size
            assertTrue("overhead was $overhead bytes", overhead in 1..32)
        }
    }

    @Test
    fun payloadFromANewerBuildKeepsWhatThisBuildUnderstands() {
        fun field(tag: Int, value: ByteArray) =
            byteArrayOf(tag.toByte(), 0, 0, (value.size ushr 8).toByte(), value.size.toByte()) + value
        val known = E2eePayload.text("hello", replyTo = "m-9").encode()
        // A field this build has never heard of — in front, between, behind — is skipped.
        val extended = byteArrayOf(E2eePayload.TEXT.toByte()) +
            field(77, ByteArray(300) { 9 }) + known.copyOfRange(1, known.size) + field(78, ByteArray(0))
        val decoded = E2eePayload.decode(extended)
        assertEquals(E2eePayload.TEXT, decoded.type)
        assertEquals("hello", decoded.body)
        assertEquals("m-9", decoded.replyTo)
    }

    @Test
    fun payloadThatDoesNotParseIsUnreadableNotMisread() {
        val good = E2eePayload.text("hello", replyTo = "m-9").encode()
        // Cut anywhere inside a field: never a shortened body passed off as the message.
        for (length in 2 until good.size) {
            val cut = E2eePayload.decode(good.copyOf(length))
            if (cut.type != E2eePayload.UNREADABLE) {
                // Only a cut exactly between two fields is still well-formed.
                assertEquals("hello", cut.body)
                assertNull(cut.replyTo)
            }
        }
        // A length that points past the end, or is negative.
        for (length in listOf(byteArrayOf(0, 0, 0, 99), byteArrayOf(0x7f, -1, -1, -1), byteArrayOf(-1, -1, -1, -1))) {
            val hostile = E2eePayload.decode(byteArrayOf(E2eePayload.TEXT.toByte(), 1) + length + "abc".toByteArray())
            assertEquals(E2eePayload.UNREADABLE, hostile.type)
            assertEquals("", hostile.body)
        }
    }

    // -------------------------------------------------------------- archive

    @Test
    fun entriesSurviveANewInstanceAndTheLatestWins() {
        val first = archive()
        assertNull(first.recent("bob", "m:1"))
        assertNull(first.find("bob", "m:1"))
        first.put("bob", "m:1", E2eePayload.text("one"))
        first.put("bob", "m:2", E2eePayload(7, "two ✓", replyTo = "m:1"))
        first.put("carol", "m:1", E2eePayload.text("carol's"))
        first.flush()
        assertEquals("one", first.recent("bob", "m:1")?.body)

        val second = archive()
        assertEquals("one", second.recent("bob", "m:1")?.body)
        assertEquals("two ✓", second.find("bob", "m:2")?.body)
        assertEquals(7, second.find("bob", "m:2")?.type)
        assertEquals("what a message replies to is kept with it", "m:1", second.find("bob", "m:2")?.replyTo)
        assertNull(second.recent("bob", "m:1")?.replyTo)
        assertEquals("carol's", second.recent("carol", "m:1")?.body)
        assertNull(second.find("bob", "m:3"))
        assertNull(second.find("dave", "m:1"))

        second.put("bob", "m:1", E2eePayload.text("one, again"))
        assertEquals("one, again", second.recent("bob", "m:1")?.body)
        assertEquals("one, again", archive().find("bob", "m:1")?.body)
    }

    @Test
    fun nothingOnDiskIsReadable() {
        val archive = archive()
        archive.put("user-bob-1234", "m:message-id-777", E2eePayload.text("MEET AT NINE"))
        archive.flush()
        val file = logs().single()
        assertFalse(file.name.contains("user-bob-1234"))
        val bytes = file.readBytes()
        for (secret in listOf("MEET AT NINE", "message-id-777", "user-bob-1234")) {
            assertFalse("$secret must not appear in the archive file", bytes.holds(secret))
        }
        // …and the usual encodings of the text are not there either.
        assertFalse(bytes.holds(encodeBase64("MEET AT NINE".toByteArray())))
        assertNull(E2eeArchive(directory(), otherKey).find("user-bob-1234", "m:message-id-777"))
    }

    @Test
    fun entryCopiedIntoAnotherChatDoesNotOpenThere() {
        val archive = archive()
        archive.put("alice", "m:1", E2eePayload.text("for alice's chat"))
        val aliceLog = logs().single()
        archive.put("bob", "m:9", E2eePayload.text("bob's own"))
        val bobLog = logs().single { it != aliceLog }
        bobLog.appendBytes(aliceLog.readBytes())

        val reopened = archive()
        assertNull("an entry sealed for another chat must not open here", reopened.find("bob", "m:1"))
        assertEquals("bob's own", reopened.find("bob", "m:9")?.body)
        assertEquals("for alice's chat", reopened.find("alice", "m:1")?.body)
    }

    @Test
    fun tornOrDamagedLinesCostOnlyThemselves() {
        val archive = archive()
        archive.put("bob", "m:1", E2eePayload.text("one"))
        archive.put("bob", "m:2", E2eePayload.text("two"))
        val log = logs().single()
        val intact = log.readText()
        // A crash mid-append: half a line, no newline. Then junk, blank lines, and valid base64 that is not ours.
        log.writeText(intact + intact.trim().lines().last().take(40))
        archive().put("bob", "m:3", E2eePayload.text("three"))
        log.appendText("\n\n!!!! not base64 !!!!\n" + encodeBase64(ByteArray(80) { 9 }) + "\n")
        archive().put("bob", "m:4", E2eePayload.text("four"))

        val reopened = archive()
        for ((id, text) in listOf("m:1" to "one", "m:2" to "two", "m:3" to "three", "m:4" to "four")) {
            assertEquals(text, reopened.find("bob", id)?.body)
        }
    }

    @Test
    fun recentReadsOnlyTheTailAndFindReadsEverything() {
        val writer = archive()
        val body = "x".repeat(120)
        repeat(200) { writer.put("bob", "m:$it", E2eePayload.text("$body $it")) }
        writer.flush()
        assertTrue("the test needs a file much larger than the tail", logs().single().length() > 40_000)

        val reader = archive(tailBytes = 4_096)
        assertEquals("$body 199", reader.recent("bob", "m:199")?.body)
        assertEquals("$body 195", reader.recent("bob", "m:195")?.body)
        assertNull("an old entry is outside the tail", reader.recent("bob", "m:0"))
        // find() goes through the whole file once; after that everything is in memory.
        assertEquals("$body 0", reader.find("bob", "m:0")?.body)
        assertEquals("$body 57", reader.recent("bob", "m:57")?.body)
        assertNull(reader.find("bob", "m:200"))

        // An entry appended while only the tail is loaded is visible at once.
        val appender = archive(tailBytes = 4_096)
        assertNotNull(appender.recent("bob", "m:199"))
        appender.put("bob", "m:new", E2eePayload.text("new"))
        assertEquals("new", appender.recent("bob", "m:new")?.body)
        assertEquals("$body 3", appender.find("bob", "m:3")?.body)
        assertEquals("new", appender.find("bob", "m:new")?.body)
    }

    @Test
    fun memoryBoundEvictsOtherChatsWithoutLosingThem() {
        val archive = archive(maxLoadedEntries = 25)
        val chats = (0 until 8).map { "chat-$it" }
        for (chat in chats) repeat(10) { archive.put(chat, "m:$it", E2eePayload.text("$chat #$it")) }
        // Touch every chat (well past the bound), then read them all back twice over.
        for (round in 0 until 2) {
            for (chat in chats) {
                for (index in listOf(0, 4, 9)) assertEquals("$chat #$index", archive.find(chat, "m:$index")?.body)
                assertNull(archive.find(chat, "m:10"))
            }
        }
        // A chat larger than the bound on its own still works while it is the one in use.
        repeat(60) { archive.put("big", "m:$it", E2eePayload.text("big #$it")) }
        for (index in listOf(0, 30, 59)) assertEquals("big #$index", archive.find("big", "m:$index")?.body)
        assertEquals("chat-0 #9", archive.find("chat-0", "m:9")?.body)
    }

    @Test
    fun flushIsIdempotentAndClearForgetsEverything() {
        val archive = archive()
        archive.flush()
        archive.put("bob", "m:1", E2eePayload.text("one"))
        archive.flush()
        archive.flush()
        assertEquals("one", archive.find("bob", "m:1")?.body)

        archive.clear()
        assertFalse(directory().exists())
        assertNull(archive.recent("bob", "m:1"))
        assertNull(archive.find("bob", "m:1"))
        archive.flush()
        archive.put("bob", "m:2", E2eePayload.text("after clear"))
        archive.flush()
        assertEquals("after clear", archive().find("bob", "m:2")?.body)
        assertNull(archive().find("bob", "m:1"))
    }

    @Test
    fun aWriteThatCannotHappenThrowsInsteadOfPretending() {
        // The archive path is occupied by a regular file: no entry can be stored.
        val blocked = folder.root.resolve("blocked").also { it.writeText("not a directory") }
        val archive = E2eeArchive(blocked, key)
        try {
            archive.put("bob", "m:1", E2eePayload.text("lost?"))
            fail("a put that stored nothing must throw")
        } catch (expected: IOException) {
            // intended
        }
        assertNull(archive.find("bob", "m:1"))
        assertArrayEquals("not a directory".toByteArray(), blocked.readBytes())
    }
}
