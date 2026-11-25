package com.nchat.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * "Log out and erase" is for a phone that goes to somebody else. The keys,
 * the encrypted history and the sealed stores are erased by their owners;
 * this is the rest — what the app keeps in the open because it never was
 * sealed: downloaded photos and clips, camera captures, voice messages that
 * were recorded and not sent yet, the names of blocked people.
 */
class PhoneFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file(root: File, path: String): File = File(root, path).also {
        it.parentFile.mkdirs()
        it.writeText("somebody's")
    }

    @Test
    fun erasingRemovesWhatWasKeptInTheOpenAndLeavesTheAppWorking() {
        val files = folder.newFolder("files")
        val cache = folder.newFolder("cache")
        val gone = listOf(
            file(cache, "media-cache/journal"),
            file(cache, "media-cache/3f/photo.0"),
            file(cache, "media/clip.m4a"),
            file(cache, "captures/capture_1.jpg"),
            file(cache, "voice-1700000000000.m4a"),
            file(cache, "video-out-12345.mp4"),
            file(cache, "something a later version adds/file"),
            file(files, "voice-out/c-1.m4a"),
            file(files, "blocked/alice.json"),
            file(files, "cache/chats.json"),
            file(files, "cache/friends.json"),
            file(files, "cache/messages/alice/bob.json"),
        )
        val kept = listOf(
            // Not the account's: the update that was downloaded, and how the app is set up.
            file(files, "updates/nchat.apk"),
            file(files, "datastore/settings.preferences_pb"),
            // The encryption state has an owner of its own, which erases it first and reports on it.
            file(files, "e2ee/0123456789abcdef/vault/entry.bin"),
        )

        assertTrue(PhoneFiles.erase(files, cache))
        gone.forEach { assertFalse(it.path, it.exists()) }
        kept.forEach { assertTrue(it.path, it.isFile) }
        // The directories themselves stay: the app goes on writing into them for whoever signs in next.
        assertTrue(cache.isDirectory)
        assertEquals(emptyList<String>(), cache.list().orEmpty().toList())
        assertTrue(files.isDirectory)

        // Nothing there: nothing to do, and that is not a failure.
        assertTrue(PhoneFiles.erase(files, cache))
        assertTrue(PhoneFiles.erase(File(files, "no such directory"), File(cache, "no such directory")))
    }
}
