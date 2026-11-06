package com.nchat.app.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One line of the storage screen: what it is, where it lives and how much it takes. */
data class StorageEntry(
    val key: String,
    val label: String,
    val detail: String,
    val bytes: Long,
    /** Clearing this loses something the user might want — the row says so. */
    val destructive: Boolean = false
)

/**
 * What NChat keeps on the device, and how to get rid of it.
 *
 * WhatsApp's "Storage and data" screen exists because media is the one thing that silently eats a
 * phone: a chat with a few hundred photos is hundreds of megabytes, and the only honest way to show
 * that is to measure the directories the app actually owns. Every entry below is a path this app
 * writes (see the store classes); nothing outside them is touched, and the sign-in token is never
 * part of anything that can be cleared here.
 */
object StorageReport {

    /** The directories, in the order the screen lists them. */
    fun scan(context: Context): List<StorageEntry> = with(context) {
        listOf(
            entry("images", "Photos and avatars", "cache/media-cache", File(cacheDir, "media-cache")),
            entry("media", "Voice notes and videos", "cache/media", File(cacheDir, "media")),
            entry("captures", "Camera captures", "cache/captures", File(cacheDir, "captures")),
            entry(
                key = "messages",
                label = "Message history",
                detail = "cache/messages — kept so chats open offline",
                file = File(filesDir, "cache/messages"),
                destructive = true
            ),
            entry("voice_out", "Recorded voice notes", "voice-out", File(filesDir, "voice-out")),
            entry("updates", "Downloaded updates", "updates", File(filesDir, "updates")),
            smallFiles(context)
        )
    }

    /** Deletes one category. Returns the bytes freed. */
    suspend fun clear(context: Context, key: String): Long = withContext(Dispatchers.IO) {
        val target = when (key) {
            "images" -> File(context.cacheDir, "media-cache")
            "media" -> File(context.cacheDir, "media")
            "captures" -> File(context.cacheDir, "captures")
            "messages" -> File(context.filesDir, "cache/messages")
            "voice_out" -> File(context.filesDir, "voice-out")
            "updates" -> File(context.filesDir, "updates")
            "lists" -> null
            else -> null
        }
        var freed = 0L
        if (key == "lists") {
            freed += clearLists(context)
        } else if (target != null) {
            freed += sizeOf(target)
            deleteRecursively(target)
        }
        freed
    }

    suspend fun scanAsync(context: Context): List<StorageEntry> = withContext(Dispatchers.IO) {
        scan(context)
    }

    // ------------------------------------------------------------------ internals

    private fun entry(
        key: String,
        label: String,
        detail: String,
        file: File,
        destructive: Boolean = false
    ): StorageEntry = StorageEntry(
        key = key,
        label = label,
        detail = detail,
        bytes = sizeOf(file),
        destructive = destructive
    )

    /**
     * The small stores: drafts, stars, pins, the archive list, the outbox and the two list caches.
     * They are kilobytes, but they are also the only files whose loss is invisible — hence one row
     * that says exactly what it clears.
     */
    private fun smallFiles(context: Context): StorageEntry {
        val files = listOf(
            File(context.filesDir, "drafts.json"),
            File(context.filesDir, "starred.json"),
            File(context.filesDir, "pinned.json"),
            File(context.filesDir, "archived.json"),
            File(context.filesDir, "outbox.json"),
            File(context.filesDir, "cache/chats.json"),
            File(context.filesDir, "cache/friends.json"),
            File(context.filesDir, "cache/groups.json")
        )
        val bytes = files.sumOf { if (it.exists()) it.length() else 0L }
        return StorageEntry(
            key = "lists",
            label = "Drafts, stars and cached lists",
            detail = "Small files — clearing drops unsent drafts too",
            bytes = bytes,
            destructive = true
        )
    }

    private fun clearLists(context: Context): Long {
        val files = listOf(
            File(context.filesDir, "drafts.json"),
            File(context.filesDir, "starred.json"),
            File(context.filesDir, "pinned.json"),
            File(context.filesDir, "archived.json"),
            File(context.filesDir, "cache/chats.json"),
            File(context.filesDir, "cache/friends.json"),
            File(context.filesDir, "cache/groups.json")
        )
        var freed = 0L
        files.forEach { file ->
            if (file.exists()) {
                freed += file.length()
                runCatching { file.delete() }
            }
        }
        return freed
    }

    private fun sizeOf(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        runCatching { file.delete() }
    }

    /** `1.4 MB`, `820 KB`, `0 B` — the way a storage screen reads. */
    fun format(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%d KB", bytes / 1024L)
        else -> "$bytes B"
    }
}
