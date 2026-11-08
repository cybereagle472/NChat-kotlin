package com.nchat.app.network

import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOriginTest {
    private fun get(url: String) = Request.Builder().url(url).build()

    @Test
    fun `credentials only go to media on the configured origin and base path`() {
        val base = "https://chat.example.com/api"
        assertTrue(isBackendMedia(base, get("https://chat.example.com/api/media/file/1")))
        assertFalse(isBackendMedia(base, get("https://chat.example.com.attacker.test/api/media/file/1")))
        assertFalse(isBackendMedia(base, get("https://chat.example.com:8443/api/media/file/1")))
        assertFalse(isBackendMedia(base, get("http://chat.example.com/api/media/file/1")))
        assertFalse(isBackendMedia(base, get("https://chat.example.com/api-fake/media/file/1")))
        assertFalse(isBackendMedia(base, get("https://chat.example.com/media/file/1")))
        assertFalse(isBackendMedia("", get("https://chat.example.com/media/file/1")))
    }
}
