package com.nchat.app.data.repository

import com.nchat.app.data.model.OutboxEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutgoingMediaTest {
    private val voice = OutboxEntry(
        clientId = "one", chatId = "peer", text = "Voice message", type = "voice"
    )

    @Test
    fun onlyAbsoluteHttpUrlsAreShareable() {
        assertEquals("https://example.com/media/a", " https://example.com/media/a ".shareableMediaUrl())
        assertEquals("http://example.com/a", "http://example.com/a".shareableMediaUrl())
        assertNull("file:///data/user/0/app/voice.m4a".shareableMediaUrl())
        assertNull("content://some.provider/clip".shareableMediaUrl())
        assertNull("https://".shareableMediaUrl())
        assertNull("/media/file/a".shareableMediaUrl())
        assertNull((null as String?).shareableMediaUrl())
    }

    @Test
    fun oldLocalVoiceUrlIsRepairedOnlyIfUploadFileIsKnown() {
        val old = voice.copy(mediaUrl = "file:///private/clip.m4a", localFile = "/private/clip.m4a")
        val prepared = old.prepareMediaForDelivery()
        assertNull(prepared?.mediaUrl)
        assertEquals("/private/clip.m4a", prepared?.localFile)
        assertNull(old.copy(localFile = null).prepareMediaForDelivery())
        assertNull(voice.copy(mediaUrl = "content://provider/clip").prepareMediaForDelivery())
        assertNull(voice.prepareMediaForDelivery())
    }

    @Test
    fun previouslyUploadedMediaAndVoiceWaveformsArePreserved() {
        val uploaded = voice.copy(mediaUrl = "https://example.com/clip.m4a", thumbnailUrl = "[1,2,3]")
        assertEquals(uploaded, uploaded.prepareMediaForDelivery())
        assertNull(uploaded.copy(thumbnailUrl = "file:///private/waveform").prepareMediaForDelivery()?.thumbnailUrl)
        val image = voice.copy(type = "image", mediaUrl = "https://example.com/photo", thumbnailUrl = "content://local/thumb")
        assertNull(image.prepareMediaForDelivery()?.thumbnailUrl)
    }

    @Test
    fun normalTextAndLocationDoNotRequireMediaUrls() {
        val text = voice.copy(type = "text", text = "hello")
        val location = voice.copy(type = "location", text = "33.1,73.1")
        assertEquals(text, text.prepareMediaForDelivery())
        assertEquals(location, location.prepareMediaForDelivery())
    }
}
