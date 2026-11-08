package com.nchat.app.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceClipStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun savedClipRemainsAvailableUntilExplicitlyDeleted() {
        val store = VoiceClipStore(folder.root.resolve("voice-out"))
        val bytes = byteArrayOf(1, 2, 3)
        val path = store.save("client-id", bytes, "m4a")
        assertNotNull(path)
        assertArrayEquals(bytes, store.read(path))
        assertArrayEquals(bytes, VoiceClipStore(folder.root.resolve("voice-out")).read(path))
        store.delete(path)
        assertNull(store.read(path))
    }

    @Test
    fun deleteDoesNotRemoveFilesOutsideVoiceDirectory() {
        val store = VoiceClipStore(folder.root.resolve("voice-out"))
        val other = folder.newFile("outside.m4a")
        other.writeBytes(byteArrayOf(7))
        store.delete(other.absolutePath)
        assertArrayEquals(byteArrayOf(7), other.readBytes())
    }
}
