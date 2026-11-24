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
    fun payloadCarriesWhereItsFileIsAndTheKeyToIt() {
        val pointer = """{"u":"https://chat.example/media/file/a/b","k":"AAAA","z":12}"""
        val photo = E2eePayload.decode(E2eePayload(E2eePayload.PHOTO, "the caption", "m-3", attachment = pointer).encode())
        assertEquals(E2eePayload.PHOTO, photo.type)
        assertEquals("the caption", photo.body)
        assertEquals("m-3", photo.replyTo)
        assertEquals(pointer, photo.attachment)
        // No caption, no reply: the file alone.
        val voice = E2eePayload.decode(E2eePayload(E2eePayload.VOICE, "", attachment = pointer).encode())
        assertEquals(E2eePayload.VOICE, voice.type)
        assertEquals("", voice.body)
        assertNull(voice.replyTo)
        assertEquals(pointer, voice.attachment)
        // A text has none, and an empty one is none.
        assertNull(E2eePayload.decode(E2eePayload.text("words").encode()).attachment)
        assertNull(E2eePayload(E2eePayload.PHOTO, "", attachment = "").attachment)
        // The four kinds are told apart by the type alone, and each has a number of its own.
        assertEquals(
            4,
            setOf(E2eePayload.PHOTO, E2eePayload.VIDEO, E2eePayload.VOICE, E2eePayload.DOCUMENT, E2eePayload.TEXT, E2eePayload.LOCATION)
                .minus(setOf(E2eePayload.TEXT, E2eePayload.LOCATION)).size,
        )
        assertTrue(E2eePayload(E2eePayload.DOCUMENT, "", attachment = pointer).isAttachment)
        assertTrue(E2eePayload(E2eePayload.VIDEO, "").isAttachment)
        assertFalse(E2eePayload.text("x").isAttachment)
        assertFalse(E2eePayload(E2eePayload.LOCATION, "1,2").isAttachment)
        assertFalse("a kind from a later build is not assumed to be a file", E2eePayload(200, "").isAttachment)
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
    fun anEntryKeepsThePointerToItsFile() {
        val pointer = """{"u":"https://chat.example/media/file/a/b","k":"QUJDREVGR0hJSktM","z":12,"t":"/9j/"}"""
        val first = archive()
        first.put("bob", "m:1", E2eePayload(E2eePayload.VIDEO, "look", "m:0", attachment = pointer))
        first.put("bob", "m:2", E2eePayload.text("no file"))
        first.flush()
        assertEquals(pointer, first.recent("bob", "m:1")!!.attachment)

        val again = archive()
        val video = again.find("bob", "m:1")!!
        assertEquals(E2eePayload.VIDEO, video.type)
        assertEquals("look", video.body)
        assertEquals("m:0", video.replyTo)
        assertEquals(pointer, video.attachment)
        assertNull(again.find("bob", "m:2")!!.attachment)
        // The key to the file is not readable on disk any more than the text is.
        for (log in logs()) {
            assertFalse(log.readBytes().holds("chat.example"))
            assertFalse(log.readBytes().holds("QUJDREVGR0hJSktM"))
        }
    }

    @Test
    fun aDeletedMessageIsTakenOutOfTheFileNotMerelyHidden() {
        val first = archive()
        first.put("bob", "m:1#aaaa", E2eePayload.text("keep me"))
        first.put("bob", "m:2#bbbb", E2eePayload.text("delete me ".repeat(200)))
        first.put("bob", "m:2#cccc", E2eePayload.text("delete me, edited ".repeat(200)))
        first.put("bob", "c:9#dddd", E2eePayload.text("my own, delete me too ".repeat(200)))
        first.put("bob", "m:20#eeee", E2eePayload.text("not the same message as m:2"))
        first.put("carol", "m:2#ffff", E2eePayload.text("carol's m:2 is another message"))
        first.flush()
        val file = logs().single { it.length() > 4_000 }
        val before = file.length()

        assertEquals("the message, its edit, and the copy kept of one's own", 3, first.remove("bob", listOf("m:2#", "c:9#")))
        assertTrue("the bytes are gone from the file: ${file.length()} of $before", file.length() < before / 10)
        assertNull(first.find("bob", "m:2#bbbb"))
        assertNull(first.recent("bob", "m:2#cccc"))
        assertNull(first.find("bob", "c:9#dddd"))
        assertEquals("keep me", first.find("bob", "m:1#aaaa")?.body)
        assertEquals("a longer id that begins the same is another message", "not the same message as m:2", first.find("bob", "m:20#eeee")?.body)
        assertEquals("carol's m:2 is another message", first.find("carol", "m:2#ffff")?.body)

        // The same for a process that starts later, and what is written afterwards is kept.
        val again = archive()
        assertNull(again.find("bob", "m:2#bbbb"))
        assertEquals("keep me", again.find("bob", "m:1#aaaa")?.body)
        again.put("bob", "m:3#gggg", E2eePayload.text("after"))
        again.flush()
        assertEquals("after", archive().find("bob", "m:3#gggg")?.body)
        // Nothing to remove is not an error, and rewrites nothing.
        val untouched = file.lastModified()
        assertEquals(0, again.remove("bob", listOf("m:999#")))
        assertEquals(0, again.remove("nobody", listOf("m:1#")))
        assertEquals(0, again.remove("bob", emptyList()))
        assertEquals(untouched, file.lastModified())
        for (log in logs()) assertFalse(log.name.endsWith(".tmp"))
    }

    @Test
    fun theNewestVersionOfAMessageIsFoundByTheMessageAlone() {
        val first = archive()
        assertNull(first.latest("bob", "m:1#"))
        first.put("bob", "m:1#aaaa", E2eePayload.text("as first sent"))
        first.put("bob", "m:2#bbbb", E2eePayload.text("another message"))
        first.put("bob", "m:1#cccc", E2eePayload.text("as edited"))
        first.flush()
        // Without the envelope: what the phone kept of message 1, in its newest form.
        assertEquals("as edited", first.latest("bob", "m:1#")?.body)
        assertEquals("another message", first.latest("bob", "m:2#")?.body)
        assertNull(first.latest("bob", "m:3#"))
        assertNull("a longer id that begins the same is another message", first.latest("bob", "m:#"))
        assertNull(first.latest("carol", "m:1#"))

        val again = archive()
        assertEquals("as edited", again.latest("bob", "m:1#")?.body)
        // Also from far back in a long history, which is read only when it is asked for.
        val long = archive(tailBytes = 512)
        for (index in 0 until 60) long.put("dave", "m:$index#x$index", E2eePayload.text("message $index"))
        long.flush()
        val reader = archive(tailBytes = 512)
        assertEquals("message 59", reader.latest("dave", "m:59#")?.body)
        assertEquals("message 0", reader.latest("dave", "m:0#")?.body)
        // Removed is removed for this lookup too.
        assertEquals(1, reader.remove("dave", listOf("m:0#")))
        assertNull(reader.latest("dave", "m:0#"))
        assertNull(archive(tailBytes = 512).latest("dave", "m:0#"))
    }

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

    // ------------------------------------------------------------ for a backup

    @Test
    fun theArchiveKnowsWhichChatsItKeeps() {
        // The files are named by a hash, so that the directory names nobody. A backup needs the
        // chats themselves: each file carries, sealed, the chat it is of.
        val archive = archive()
        assertEquals(emptyList<String>(), archive.chats().ids)
        archive.put("user-bob-1234", "m:1#a", E2eePayload.text("one"))
        archive.put("g:family-5678", "m:3:bob:2#b", E2eePayload.text("two"))
        archive.put("user-bob-1234", "m:3#c", E2eePayload.text("three"))
        assertEquals(listOf("g:family-5678", "user-bob-1234"), archive.chats().ids.sorted())
        assertEquals("…and still does in a new run of the app", listOf("g:family-5678", "user-bob-1234"), archive().chats().ids.sorted())
        assertEquals(0, archive().chats().unreadable)
        archive().put("user-bob-1234", "m:4#d", E2eePayload.text("four"))
        assertEquals("the name is in a file once, however much is written to it", 2 + 4, logs().sumOf { file -> file.readLines().count { it.isNotBlank() } })

        for (file in logs()) {
            for (name in listOf("user-bob-1234", "family-5678")) {
                assertFalse("${file.name} names $name", file.readBytes().holds(name) || file.name.contains(name))
            }
        }
        // Under another key nothing says whose the files are — and that is said, not passed over.
        val foreign = E2eeArchive(directory(), otherKey).chats()
        assertEquals(emptyList<String>(), foreign.ids)
        assertEquals(2, foreign.unreadable)

        // Deleting from a chat writes its file again: it still says which chat it is.
        archive().remove("user-bob-1234", listOf("m:1#"))
        assertEquals(listOf("g:family-5678", "user-bob-1234"), archive().chats().ids.sorted())
        assertEquals("three", archive().find("user-bob-1234", "m:3#c")?.body)

        archive().clear()
        assertEquals(emptyList<String>(), archive().chats().ids)
    }

    @Test
    fun aFileThatLostItsNameGetsItBackWithTheNextMessage() {
        val archive = archive()
        archive.put("user-bob-1234", "m:1#a", E2eePayload.text("one"))
        archive.put("user-carol-9", "m:1#b", E2eePayload.text("carol's"))
        val bob = logs().single { it.name == hashedName("user-bob-1234") + ".log" }
        val carol = logs().single { it != bob }

        // The line that names the chat is torn off: what is left cannot be put in a backup…
        val entries = bob.readLines().filter { it.isNotBlank() }
        bob.writeText(entries.drop(1).joinToString("\n", postfix = "\n"))
        val broken = archive().chats()
        assertEquals(listOf("user-carol-9"), broken.ids)
        assertEquals("…and the archive says so instead of leaving it out quietly", 1, broken.unreadable)
        assertEquals("the messages themselves are still read", "one", archive().find("user-bob-1234", "m:1#a")?.body)

        // …until something is written to the chat again.
        archive().put("user-bob-1234", "m:2#c", E2eePayload.text("two"))
        val mended = archive().chats()
        assertEquals(listOf("user-bob-1234", "user-carol-9"), mended.ids.sorted())
        assertEquals(0, mended.unreadable)
        assertEquals(listOf("m:1#a", "m:2#c"), archive().entries("user-bob-1234").map { it.first })

        // The name of one chat's file does not name another's.
        carol.appendText(bob.readLines().first { it.isNotBlank() && it !in entries } + "\n")
        assertEquals(listOf("user-bob-1234", "user-carol-9"), archive().chats().ids.sorted())
        val copied = folder.root.resolve("copy").also { it.mkdirs() }
        bob.copyTo(copied.resolve(hashedName("somebody-else") + ".log"))
        val elsewhere = E2eeArchive(copied, key).chats()
        assertEquals(emptyList<String>(), elsewhere.ids)
        assertEquals(1, elsewhere.unreadable)

        // A file with nothing in it is not a chat that could not be read.
        directory().resolve(hashedName("nobody") + ".log").writeText("\n")
        assertEquals(0, archive().chats().unreadable)
    }

    @Test
    fun everyEntryOfAChatIsHandedOutInTheOrderItWasWritten() {
        val archive = archive(tailBytes = 256)
        assertEquals(emptyList<Pair<String, E2eePayload>>(), archive.entries("bob"))
        archive.put("bob", "m:c-1#a", E2eePayload.text("see you at seven"))
        archive.put("bob", "c:c-2#b", E2eePayload.text("fine", replyTo = "s-1"))
        for (index in 0 until 40) archive.put("bob", "m:c-1$index#x", E2eePayload.text("filler $index, to push the first ones out of the tail"))
        archive.put("bob", "e:s-1#c", E2eePayload(E2eePayload.PHOTO, "the harbour", attachment = "v1:pointer"))
        archive.put("carol", "m:c-9#z", E2eePayload.text("another chat"))
        archive.flush()

        val entries = archive().entries("bob")
        assertEquals(43, entries.size)
        assertEquals(listOf("m:c-1#a", "c:c-2#b"), entries.take(2).map { it.first })
        assertEquals("e:s-1#c", entries.last().first)
        assertEquals("see you at seven", entries[0].second.body)
        assertEquals("s-1", entries[1].second.replyTo)
        assertEquals(E2eePayload.PHOTO, entries.last().second.type)
        assertEquals("v1:pointer", entries.last().second.attachment)
        assertEquals(listOf("m:c-9#z"), archive().entries("carol").map { it.first })

        // What was deleted is not handed out again.
        archive().remove("bob", listOf("c:c-2#"))
        assertFalse(archive().entries("bob").any { it.first == "c:c-2#b" })
        assertEquals(42, archive().entries("bob").size)
    }

    @Test
    fun restoringAddsWhatIsMissingAndNothingTwice() {
        val source = archive()
        source.put("bob", "m:c-1#a", E2eePayload.text("one"))
        source.put("bob", "c:c-2#b", E2eePayload.text("two", replyTo = "s-1"))
        source.put("bob", "e:s-1#c", E2eePayload.text("one, edited"))
        val kept = source.entries("bob")

        // Another installation: another directory, another key.
        val other = folder.root.resolve("elsewhere")
        fun target() = E2eeArchive(other, otherKey)
        assertEquals(3, target().restore("bob", kept))
        assertEquals("one", target().find("bob", "m:c-1#a")?.body)
        assertEquals("s-1", target().find("bob", "c:c-2#b")?.replyTo)
        assertEquals(listOf("bob"), target().chats().ids)
        for (file in other.listFiles().orEmpty()) assertFalse(file.readBytes().holds("edited"))

        assertEquals("a second time adds nothing", 0, target().restore("bob", kept))
        assertEquals(3, target().entries("bob").size)

        // What this phone kept itself is not replaced by what a backup says about the same message.
        target().put("bob", "m:c-7#q", E2eePayload.text("kept here"))
        assertEquals(0, target().restore("bob", listOf("m:c-7#q" to E2eePayload.text("from the backup"))))
        assertEquals("kept here", target().find("bob", "m:c-7#q")?.body)
        assertEquals(1, target().restore("bob", kept + listOf("m:c-8#r" to E2eePayload.text("only in the backup"))))
        assertEquals("only in the backup", target().find("bob", "m:c-8#r")?.body)
        assertEquals(0, target().restore("bob", emptyList()))
        assertEquals("a restored file still says which chat it is", listOf("bob"), target().chats().ids)
    }

    @Test
    fun whatThePhoneKeptSinceIsNewerThanAnythingABackupHolds() {
        // Two forms of one message — as first sent, then edited — are two entries under one
        // name, and the one written last is the newest. A backup is older than whatever the
        // phone has kept by itself since, so what it brings back goes in before that.
        val source = archive()
        source.put("bob", "m:s-1#first", E2eePayload.text("as first sent"))
        source.put("bob", "m:s-1#second", E2eePayload.text("edited once"))
        source.put("bob", "m:s-2#only", E2eePayload.text("another message"))
        val backup = source.entries("bob")

        val other = folder.root.resolve("elsewhere")
        fun target() = E2eeArchive(other, otherKey)
        target().put("bob", "m:s-1#third", E2eePayload.text("edited again, after the backup"))
        target().put("bob", "m:s-9#new", E2eePayload.text("arrived after the backup"))
        assertEquals(3, target().restore("bob", backup))

        assertEquals("edited again, after the backup", target().latest("bob", "m:s-1#")?.body)
        assertEquals("as first sent", target().find("bob", "m:s-1#first")?.body)
        assertEquals("edited once", target().find("bob", "m:s-1#second")?.body)
        assertEquals(
            listOf("m:s-1#first", "m:s-1#second", "m:s-2#only", "m:s-1#third", "m:s-9#new"),
            target().entries("bob").map { it.first },
        )
        // Within the backup the order is the backup's: its last form of a message is its newest.
        val fresh = folder.root.resolve("fresh")
        assertEquals(3, E2eeArchive(fresh, otherKey).restore("bob", backup))
        assertEquals("edited once", E2eeArchive(fresh, otherKey).latest("bob", "m:s-1#")?.body)
        // An instance that was already reading the chat sees what was restored.
        val running = E2eeArchive(folder.root.resolve("running"), otherKey)
        running.put("bob", "m:s-9#new", E2eePayload.text("arrived after the backup"))
        assertNull(running.find("bob", "m:s-2#only"))
        assertEquals(3, running.restore("bob", backup))
        assertEquals("another message", running.recent("bob", "m:s-2#only")?.body)
        assertEquals("arrived after the backup", running.find("bob", "m:s-9#new")?.body)
    }
}
