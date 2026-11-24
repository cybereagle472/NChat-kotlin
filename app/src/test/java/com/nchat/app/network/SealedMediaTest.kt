package com.nchat.app.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * How the app carries a sealed file around once the message that named it
 * has been opened: as an address with the key in its fragment — the part of
 * an address no server is sent — and as an opened copy kept on the phone.
 */
class SealedMediaTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = ByteArray(32) { (it * 3 + 1).toByte() }
    private val url = "https://chat.example/api/media/file/u1/qz-attachment"

    // -------------------------------------------------------------- address

    @Test
    fun anAddressCarriesTheKeyWhereNoServerIsSentIt() {
        val address = SealedMedia.address(url, key, 123_456L, "jpg")
        assertTrue(address.startsWith("$url#"))
        assertTrue(SealedMedia.isSealed(address))

        val parsed = SealedMedia.parse(address)!!
        assertEquals(url, parsed.url)
        assertArrayEquals(key, parsed.key)
        assertEquals(123_456L, parsed.size)
        assertEquals("jpg", parsed.extension)

        // What a request, a log line or another server may be given: the address without the key.
        assertEquals(url, SealedMedia.strip(address))
        val sent = java.net.URI(address)
        assertEquals("/api/media/file/u1/qz-attachment", sent.rawPath)
        assertNull(sent.rawQuery)
        assertFalse("the key is in the fragment and nowhere else", address.substringBefore('#').contains(encodeBase64(key).take(12)))
    }

    @Test
    fun whatIsNotASealedAddressIsLeftAsItIs() {
        for (plain in listOf(url, "$url?download=1", "$url#section", "file:///data/user/0/app/files/voice.m4a", "content://media/1", "")) {
            assertNull(plain, SealedMedia.parse(plain))
            assertFalse(SealedMedia.isSealed(plain))
            assertEquals(plain, SealedMedia.strip(plain))
        }
        assertNull(SealedMedia.parse(null))
        assertFalse(SealedMedia.isSealed(null))
        assertNull(SealedMedia.strip(null))
    }

    @Test
    fun aDamagedFragmentIsNotAKeyButIsStillNeverSentOn() {
        val good = SealedMedia.address(url, key, 10L, "mp4")
        val fragment = good.substringAfter('#')
        val parts = fragment.split(':')
        val damaged = listOf(
            "$url#${fragment.replaceFirst("v1", "v9")}",
            "$url#${parts.take(3).joinToString(":")}",
            "$url#${parts.toMutableList().also { it[2] = it[2].dropLast(4) }.joinToString(":")}",
            "$url#${parts.toMutableList().also { it[2] = "!!!" }.joinToString(":")}",
            "$url#${parts.toMutableList().also { it[3] = "-1" }.joinToString(":")}",
            "$url#${parts.toMutableList().also { it[3] = "ten" }.joinToString(":")}",
            "$url#${parts.toMutableList().also { it[3] = (E2eeAttachment.MAX_BYTES + 1).toString() }.joinToString(":")}",
        )
        for (address in damaged) {
            assertNull(address, SealedMedia.parse(address))
            assertFalse(SealedMedia.isSealed(address))
            assertEquals("the fragment goes whether or not it is whole", url, SealedMedia.strip(address))
        }
        assertEquals(10L, SealedMedia.parse(good)!!.size)
    }

    @Test
    fun theExtensionIsAFewLettersAndDigitsOrNothingTelling() {
        assertEquals("jpg", SealedMedia.parse(SealedMedia.address(url, key, 1L, "JPG"))!!.extension)
        assertEquals("m4a", SealedMedia.parse(SealedMedia.address(url, key, 1L, ".m4a"))!!.extension)
        for (odd in listOf(null, "", "../../etc", "a b", "toolongext", "é", "tar.gz")) {
            assertEquals("for $odd", "bin", SealedMedia.parse(SealedMedia.address(url, key, 1L, odd))!!.extension)
        }
        assertEquals("jpg", SealedMedia.extensionFor("image/jpeg", "holiday.JPEG"))
        assertEquals("the name decides when it has an ending", "pdf", SealedMedia.extensionFor("application/octet-stream", "scan.pdf"))
        assertEquals("m4a", SealedMedia.extensionFor("audio/mp4", null))
        assertEquals("mp4", SealedMedia.extensionFor("video/mp4", "clip"))
        assertEquals("bin", SealedMedia.extensionFor(null, null))
        assertEquals("bin", SealedMedia.extensionFor("application/x-unknown", "noending"))
    }

    @Test
    fun aSealedFileIsOnlyEverFetchedFromTheServerTheAppTalksTo() {
        val base = "https://chat.example.com/api"
        assertTrue(SealedMedia.isOn(base, "https://chat.example.com/api/media/file/u1/f1"))
        assertTrue(SealedMedia.isOn("$base/", "https://chat.example.com/api/media/file/u1/f1"))
        assertTrue("with or without its key", SealedMedia.isOn(base, SealedMedia.address("https://chat.example.com/api/media/file/u1/f1", key, 1L, "jpg")))
        for (elsewhere in listOf(
            "https://chat.example.com.attacker.test/api/media/file/1",
            "https://attacker.test/api/media/file/1",
            "https://chat.example.com:8443/api/media/file/1",
            "http://chat.example.com/api/media/file/1",
            "https://chat.example.com/api-fake/media/file/1",
            "https://chat.example.com/media/file/1",
            "https://chat.example.com/api/media/file/",
            "https://chat.example.com/api/media/file/../../keys/bundle/x",
            "https://chat.example.com/api/media/upload",
            "https://user@chat.example.com/api/media/file/1",
            "file:///data/x",
            "not a url",
            "",
        )) {
            assertFalse(elsewhere, SealedMedia.isOn(base, elsewhere))
        }
        assertFalse("an app without a server has no media", SealedMedia.isOn("", "https://chat.example.com/api/media/file/1"))
    }

    @Test
    fun aThumbnailWaitingToBeSentIsAPictureWrittenOutAsText() {
        val jpeg = byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70)
        val text = SealedMedia.inlineImage(jpeg)
        assertTrue(text.startsWith("data:image/jpeg;base64,"))
        assertArrayEquals(jpeg, SealedMedia.inlineImageBytes(text))
        for (other in listOf(null, "", url, "file:///x/thumb.jpg", "data:text/plain;base64,AAAA", "data:image/jpeg;base64,", "data:image/jpeg;base64,!!!", "3,9,27")) {
            assertNull("$other", SealedMedia.inlineImageBytes(other))
        }
    }

    @Test
    fun whateverHoldsAKeyIsRecognisedBeforeItIsSentAsReadableData() {
        val address = SealedMedia.address(url, key, 10L, "jpg")
        assertTrue(SealedMedia.carriesKey(address))
        assertTrue("also inside a longer text", SealedMedia.carriesKey("look at $address please"))
        assertTrue("also when it is no longer whole", SealedMedia.carriesKey(address.dropLast(20)))
        for (plain in listOf(null, "", url, "$url#section", "hello", SealedMedia.strip(address))) {
            assertFalse("$plain", SealedMedia.carriesKey(plain))
        }
    }

    // ---------------------------------------------------------------- store

    /** A server that holds sealed files and counts how often it is asked. */
    private class Shelf {
        val files = HashMap<String, ByteArray>()
        val asked = AtomicInteger()
        var failure: IOException? = null

        fun fetch(url: String, into: OutputStream) {
            asked.incrementAndGet()
            failure?.let { throw it }
            into.write(files[url] ?: throw IOException("404"))
        }
    }

    private fun directory(): File = folder.root.resolve("media")

    private fun contentOf(size: Int, salt: Int = 0): ByteArray = ByteArray(size) { ((it + salt) * 13).toByte() }

    private fun everyFile(): List<File> = directory().walkTopDown().filter { it.isFile }.toList()

    @Test
    fun aFileIsFetchedOnceOpenedAndKept() {
        val shelf = Shelf()
        val plain = contentOf(90_000)
        shelf.files[url] = E2eeAttachment.seal(key, plain)
        val store = SealedMediaStore({ directory() })
        val address = SealedMedia.address(url, key, plain.size.toLong(), "jpg")

        assertNull(store.cached(address))
        val file = store.local(address, shelf::fetch)
        assertArrayEquals(plain, file.readBytes())
        assertTrue(file.name.endsWith(".jpg"))
        assertTrue(file.absolutePath.startsWith(directory().absolutePath))
        assertFalse("the name says nothing of where the file came from", file.name.contains("attachment") || file.name.contains("qz"))

        assertEquals(file, store.local(address, shelf::fetch))
        assertEquals(file, store.cached(address))
        assertEquals("asked for once", 1, shelf.asked.get())
        // The same after a restart.
        assertEquals(file, SealedMediaStore({ directory() }).cached(address))
        assertEquals("nothing but the file is left lying about", listOf(file), everyFile())
    }

    @Test
    fun whatDoesNotOpenIsNotKept() {
        val shelf = Shelf()
        val plain = contentOf(70_000)
        val sealed = E2eeAttachment.seal(key, plain)
        val store = SealedMediaStore({ directory() })
        val address = SealedMedia.address(url, key, plain.size.toLong(), "mp4")

        for (bad in listOf(
            sealed.copyOf().also { it[sealed.size / 2] = (it[sealed.size / 2].toInt() xor 1).toByte() },
            sealed.copyOf(sealed.size - 1),
            E2eeAttachment.seal(ByteArray(32) { 5 }, plain),
            "<html>not found</html>".toByteArray(),
            ByteArray(0),
        )) {
            shelf.files[url] = bad
            try {
                store.local(address, shelf::fetch)
                fail("kept a file that did not open")
            } catch (_: IOException) {
            }
            assertNull(store.cached(address))
            assertEquals(emptyList<File>(), everyFile())
        }
        // The pointer promised more than was sealed.
        shelf.files[url] = sealed
        try {
            store.local(SealedMedia.address(url, key, E2eeAttachment.paddedSize(70_000L) + 1, "mp4"), shelf::fetch)
            fail("kept a file shorter than promised")
        } catch (_: IOException) {
        }
        assertEquals(emptyList<File>(), everyFile())
        // And once the server has the right bytes, it works.
        assertArrayEquals(plain, store.local(address, shelf::fetch).readBytes())
    }

    @Test
    fun aFetchThatFailsLeavesNothingBehindAndIsTriedAgain() {
        val shelf = Shelf()
        val plain = contentOf(5_000)
        shelf.files[url] = E2eeAttachment.seal(key, plain)
        shelf.failure = IOException("no connection")
        val store = SealedMediaStore({ directory() })
        val address = SealedMedia.address(url, key, plain.size.toLong(), "m4a")
        try {
            store.local(address, shelf::fetch)
            fail("a fetch that failed returned a file")
        } catch (failure: IOException) {
            assertEquals("no connection", failure.message)
        }
        assertEquals(emptyList<File>(), everyFile())
        shelf.failure = null
        assertArrayEquals(plain, store.local(address, shelf::fetch).readBytes())
        assertEquals(2, shelf.asked.get())
    }

    @Test
    fun whatIsNotASealedAddressIsNotFetchedAtAll() {
        val shelf = Shelf()
        val store = SealedMediaStore({ directory() })
        for (address in listOf(url, "$url#section", "", "file:///x")) {
            try {
                store.local(address, shelf::fetch)
                fail("fetched $address")
            } catch (_: IOException) {
            }
            assertNull(store.cached(address))
        }
        assertEquals(0, shelf.asked.get())
    }

    @Test
    fun whatThisPhoneSentIsAlreadyThere() {
        val shelf = Shelf()
        val plain = contentOf(40_000)
        val store = SealedMediaStore({ directory() })
        val address = SealedMedia.address(url, key, plain.size.toLong(), "jpg")
        val seeded = store.seed(address, plain)!!
        assertArrayEquals(plain, seeded.readBytes())
        assertEquals(seeded, store.local(address, shelf::fetch))
        assertEquals("the phone does not download what it uploaded", 0, shelf.asked.get())
        // Content of another size than the address names is not that file.
        assertNull(store.seed(SealedMedia.address("$url-2", key, 10L, "jpg"), plain))
        assertNull(store.seed(url, plain))
    }

    @Test
    fun theSameFileUnderAnotherKeyIsAnotherFile() {
        val shelf = Shelf()
        val plain = contentOf(3_000)
        shelf.files[url] = E2eeAttachment.seal(key, plain)
        val store = SealedMediaStore({ directory() })
        val right = SealedMedia.address(url, key, plain.size.toLong(), "jpg")
        val wrong = SealedMedia.address(url, ByteArray(32) { 9 }, plain.size.toLong(), "jpg")
        val file = store.local(right, shelf::fetch)
        assertNull("a copy opened with one key does not answer for another", store.cached(wrong))
        try {
            store.local(wrong, shelf::fetch)
            fail("opened with the wrong key")
        } catch (_: IOException) {
        }
        assertEquals(file, store.cached(right))
    }

    @Test
    fun theOldestFilesGoWhenThereIsNoRoomLeft() {
        val shelf = Shelf()
        val store = SealedMediaStore({ directory() }, maxBytes = 25_000L)
        val addresses = (1..4).map { index ->
            val plain = contentOf(10_000, salt = index)
            shelf.files["$url-$index"] = E2eeAttachment.seal(key, plain)
            SealedMedia.address("$url-$index", key, plain.size.toLong(), "jpg")
        }
        val first = store.local(addresses[0], shelf::fetch).also { it.setLastModified(1_000L) }
        val second = store.local(addresses[1], shelf::fetch).also { it.setLastModified(2_000L) }
        val third = store.local(addresses[2], shelf::fetch)
        assertFalse("the one used longest ago made room", first.exists())
        assertTrue(second.exists() && third.exists())
        // Using a file is what keeps it.
        second.setLastModified(1_000L)
        third.setLastModified(2_000L)
        assertEquals(second, store.local(addresses[1], shelf::fetch))
        val fourth = store.local(addresses[3], shelf::fetch)
        assertTrue("it was used a moment ago", second.exists())
        assertFalse(third.exists())
        assertTrue(fourth.exists())
        // One file larger than all the room there is still gets shown.
        val big = contentOf(60_000)
        shelf.files["$url-big"] = E2eeAttachment.seal(key, big)
        assertArrayEquals(big, store.local(SealedMedia.address("$url-big", key, big.size.toLong(), "mp4"), shelf::fetch).readBytes())
    }

    @Test
    fun withoutAPlaceToKeepFilesNothingIsFetched() {
        val shelf = Shelf()
        val plain = contentOf(1_000)
        shelf.files[url] = E2eeAttachment.seal(key, plain)
        val store = SealedMediaStore({ null })
        val address = SealedMedia.address(url, key, plain.size.toLong(), "jpg")
        assertNull(store.cached(address))
        assertNull(store.seed(address, plain))
        assertNull(store.thumbnail(address, byteArrayOf(1, 2, 3)))
        try {
            store.local(address, shelf::fetch)
            fail("fetched with nowhere to put it")
        } catch (_: IOException) {
        }
        assertEquals(0, shelf.asked.get())
    }

    @Test
    fun aSmallPictureOfTheFileIsWrittenOnceAndFoundAgain() {
        val store = SealedMediaStore({ directory() })
        val address = SealedMedia.address(url, key, 9L, "jpg")
        val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)
        val first = store.thumbnail(address, jpeg)!!
        assertArrayEquals(jpeg, first.readBytes())
        assertTrue(first.name.endsWith(".jpg"))
        val modified = first.lastModified()
        assertEquals(first, store.thumbnail(address, jpeg))
        assertEquals("not written again", modified, first.lastModified())
        assertNotEquals(first, store.thumbnail(SealedMedia.address("$url-2", key, 9L, "jpg"), jpeg))
        assertNull("nothing to show", store.thumbnail(address, ByteArray(0)))
        assertNull(store.thumbnail(url, jpeg))
        // It is not the file, and does not stand in for it.
        assertNull(store.cached(address))
    }

    @Test
    fun twoAsksForOneFileFetchItOnce() {
        val plain = contentOf(20_000)
        val sealed = E2eeAttachment.seal(key, plain)
        val store = SealedMediaStore({ directory() })
        val address = SealedMedia.address(url, key, plain.size.toLong(), "jpg")
        val asked = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow: (String, OutputStream) -> Unit = { _, into ->
            asked.incrementAndGet()
            started.countDown()
            release.await()
            into.write(sealed)
        }
        val results = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
        val threads = (1..2).map { thread { results += store.local(address, slow).readBytes() } }
        started.await()
        Thread.sleep(100)
        release.countDown()
        threads.forEach { it.join() }
        assertEquals(1, asked.get())
        assertEquals(2, results.size)
        results.forEach { assertArrayEquals(plain, it) }
    }
}
