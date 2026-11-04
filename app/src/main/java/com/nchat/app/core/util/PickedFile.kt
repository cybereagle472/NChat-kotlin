package com.nchat.app.core.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * A file the user picked, already read into memory and ready for `POST /media/upload`.
 *
 * Chat attachments are photos, clips and documents — bounded by [MAX_BYTES] so a huge video
 * cannot exhaust the heap. Anything larger is refused by the caller with a toast rather than
 * half-uploaded.
 */
data class PickedFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    /** Small preview uploaded separately and sent as `thumbnail_url`. */
    val thumbnail: ByteArray? = null
) {
    val size: Long get() = bytes.size.toLong()

    companion object {
        /** 24 MB — comfortably above a phone photo or a short clip, well under a heap crash. */
        const val MAX_BYTES = 24 * 1024 * 1024
    }
}

/** Reads the picked [uri] into a [PickedFile], or null when it is too big or unreadable. */
fun readPickedFile(context: Context, uri: Uri): PickedFile? = runCatching {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"

    var name: String? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0)
            }
    }

    val bytes = resolver.openInputStream(uri)?.use { stream -> stream.readBytes() } ?: return null
    if (bytes.size > PickedFile.MAX_BYTES) return null

    PickedFile(
        name = name?.takeIf { it.isNotBlank() } ?: "attachment",
        mimeType = mime,
        bytes = bytes
    )
}.getOrNull()
