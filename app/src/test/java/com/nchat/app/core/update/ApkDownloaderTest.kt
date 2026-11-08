package com.nchat.app.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ApkDownloaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val payload = byteArrayOf(0x50, 0x4b, 1, 2, 3)

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val bytes: ByteArray = byteArrayOf(),
        private val length: Long = bytes.size.toLong(),
        private val location: String? = null,
        private val retryAfter: String? = null,
        private val stream: (() -> InputStream)? = null
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = length
        override fun getHeaderField(name: String?): String? = when (name) {
            "Location" -> location
            "Retry-After" -> retryAfter
            else -> null
        }
        override fun getInputStream(): InputStream = stream?.invoke() ?: ByteArrayInputStream(bytes)
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }

    private fun downloader(open: (URL) -> HttpURLConnection) =
        ApkDownloader(open = open, pause = {}, minBytes = 4, maxBytes = 100)

    @Test
    fun retriesGatewayTimeoutThenDownloads() = runBlocking {
        val codes = ArrayDeque(listOf(504, 504, 200))
        val connections = mutableListOf<FakeConnection>()
        val downloader = downloader { url ->
            FakeConnection(url, codes.removeFirst(), payload).also(connections::add)
        }
        val part = folder.root.resolve("download.part.apk")
        downloader.download(listOf("https://primary.example/app.apk"), part, {})
        assertArrayEquals(payload, part.readBytes())
        assertEquals(3, connections.size)
        assertTrue(connections.all { it.disconnected })
    }

    @Test
    fun honorsServerRetryAfterForGatewayTimeouts() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val downloader = ApkDownloader(
            open = { url ->
                attempts++
                FakeConnection(url, if (attempts == 1) 504 else 200, payload, retryAfter = "3")
            },
            pause = { delays += it },
            minBytes = 4,
            maxBytes = 100
        )
        val part = folder.root.resolve("download.part.apk")
        downloader.download(listOf("https://primary.example/app.apk"), part, {})
        assertEquals(listOf(3_000L), delays)
        assertArrayEquals(payload, part.readBytes())
    }

    @Test
    fun permanentFailureTriesIndependentMirrorWithoutRetryingPrimary() = runBlocking {
        val hosts = mutableListOf<String>()
        val downloader = downloader { url ->
            hosts += url.host
            FakeConnection(url, if (url.host == "primary.example") 404 else 200, payload)
        }
        val part = folder.root.resolve("download.part.apk")
        downloader.download(listOf("https://primary.example/app", "https://mirror.example/app"), part, {})
        assertEquals(listOf("primary.example", "mirror.example"), hosts)
        assertArrayEquals(payload, part.readBytes())
    }

    @Test
    fun truncatedDownloadIsRetriedAndIncompleteFilesAreRemoved() = runBlocking {
        var attempts = 0
        val downloader = downloader { url ->
            attempts++
            FakeConnection(url, 200, payload, length = 10)
        }
        val part = folder.root.resolve("download.part.apk")
        val error = try {
            downloader.download(listOf("https://primary.example/app"), part, {})
            throw AssertionError("Expected download failure")
        } catch (e: java.io.IOException) { e }
        assertTrue(error.message!!.contains("another network"))
        assertEquals(5, attempts)
        assertFalse(part.exists())
    }

    @Test
    fun rejectsInsecureRedirectWithoutOpeningIt() = runBlocking {
        val hosts = mutableListOf<String>()
        val downloader = downloader { url ->
            hosts += url.toString()
            FakeConnection(url, 302, location = "http://mirror.example/app")
        }
        val part = folder.root.resolve("download.part.apk")
        try {
            downloader.download(listOf("https://primary.example/app"), part, {})
            throw AssertionError("Expected insecure redirect rejection")
        } catch (_: java.io.IOException) { }
        assertEquals(listOf("https://primary.example/app"), hosts)
        assertFalse(part.exists())
    }

    @Test
    fun failedValidationCanFallBackToMirror() = runBlocking {
        var requests = 0
        val downloader = downloader { url ->
            requests++
            FakeConnection(url, 200, if (url.host == "primary.example") byteArrayOf(9, 9, 9, 9) else payload)
        }
        val part = folder.root.resolve("download.part.apk")
        downloader.download(listOf("https://primary.example/app", "https://mirror.example/app"), part, {},
            validate = { if (it.readBytes().contentEquals(payload)) null else "Wrong APK" })
        assertEquals(2, requests)
        assertArrayEquals(payload, part.readBytes())
    }

    @Test
    fun cancellationRemovesPartialFile() = runBlocking {
        val downloader = downloader { url -> FakeConnection(url, 200, payload, stream = {
            object : InputStream() {
                override fun read(): Int = throw CancellationException("cancelled")
            }
        }) }
        val part = folder.root.resolve("download.part.apk")
        try {
            downloader.download(listOf("https://primary.example/app"), part, {})
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(part.exists())
    }
}
