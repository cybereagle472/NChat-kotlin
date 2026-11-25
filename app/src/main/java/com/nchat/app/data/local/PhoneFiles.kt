package com.nchat.app.data.local

import java.io.File

/**
 * What "Log out and erase" removes from the phone beside the encryption
 * keys, the encrypted history and the sealed stores — those are erased by
 * their owners, which also know when a half-done erasure has to be finished.
 *
 * This is the rest: what the app keeps in the open, because it never was
 * sealed or is on its way to being sent.
 *  - everything in the cache directory: photos, pictures of people, clips and
 *    videos that were downloaded; camera captures; a recording or a video
 *    that was being prepared;
 *  - `voice-out`: voice messages recorded here and not sent yet;
 *  - `blocked`: who the account has blocked, by name;
 *  - `cache`: conversations and lists. They are sealed, and noise once the
 *    key is gone; they go all the same.
 *
 * Not touched: a downloaded update, how the app is set up (sounds, the
 * wallpaper), and anything the user saved outside the app — a photo in the
 * gallery, a backup file.
 */
internal object PhoneFiles {

    /** In the app's files directory. */
    private val ACCOUNT_DIRECTORIES = listOf("voice-out", "blocked", "cache")

    /**
     * Removes all of it, as far as the phone lets it. True when nothing of
     * it is left; a directory that is not there has nothing left in it.
     */
    fun erase(filesDir: File, cacheDir: File): Boolean {
        var clean = true
        // The cache directory itself stays: the app goes on writing into it.
        cacheDir.listFiles()?.forEach { if (!remove(it)) clean = false }
        for (name in ACCOUNT_DIRECTORIES) if (!remove(File(filesDir, name))) clean = false
        return clean
    }

    private fun remove(target: File): Boolean = !target.exists() || target.deleteRecursively() || !target.exists()
}
