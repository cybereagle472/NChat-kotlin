package com.nchat.app.data.repository

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class ProfileSetupAvatarTest {
    @Test fun defaultAvatarDoesNotUpload() = runBlocking {
        var uploaded = false
        val url = profileSetupAvatarUrl<String>(null) { uploaded = true; it }
        assertEquals("", url)
        assertFalse(uploaded)
    }

    @Test fun selectedPhotoUsesUploadedUrl() = runBlocking {
        val url = profileSetupAvatarUrl("photo") { "https://example.com/$it.png" }
        assertEquals("https://example.com/photo.png", url)
    }

    @Test fun rejectsAnUnshareablePhotoUrl() = runBlocking {
        try {
            profileSetupAvatarUrl("photo") { "content://local/photo" }
            fail("A local URI must not be saved as a profile photo")
        } catch (_: IOException) {
            // Expected: keep setup open and let the user retry or use the default.
        }
    }
}
