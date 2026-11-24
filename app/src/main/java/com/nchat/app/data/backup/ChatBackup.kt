package com.nchat.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.nchat.app.network.E2eeBackup
import com.nchat.app.network.EndToEnd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * A backup of the encrypted history as a document the user chooses: where it
 * goes and where it comes from is up to the system's file picker, so the app
 * asks for no storage permission and never learns where the file is kept.
 * What is in the file, and what protects it, is [E2eeBackup].
 */
class ChatBackup(
    private val context: Context,
    private val endToEnd: () -> EndToEnd,
    /** Messages that could not be shown may be readable now. */
    private val onRestored: () -> Unit,
) {
    /**
     * Writes the backup to [target]. Null when there is nothing to write it
     * from (see [EndToEnd.backUp]). A file is left behind only when it is a
     * backup of something: whenever this does not end well, and when there
     * was not one message to put in it, what was written is taken away again
     * — half a file, or an empty one, must not lie about looking like a
     * backup.
     */
    suspend fun save(target: Uri, passphrase: CharArray): E2eeBackup.Written? = withContext(Dispatchers.IO) {
        try {
            val written = open(target).buffered().use { endToEnd().backUp(it, passphrase) }
            if (written == null || written.messages == 0) remove(target)
            written
        } catch (failure: Throwable) {
            remove(target)
            throw failure
        }
    }

    /** Emptied as it is opened, where the place it is kept in can do that; it was made a moment ago and is empty anyway. */
    private fun open(target: Uri): OutputStream {
        val resolver = context.contentResolver
        return runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
            ?: resolver.openOutputStream(target, "w")
            ?: throw IOException("the file could not be opened")
    }

    /**
     * Restores from [source]; see [EndToEnd.restore]. Throws
     * [E2eeBackup.Failure] with what is wrong with the file.
     */
    suspend fun restore(source: Uri, passphrase: CharArray): Int? = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source) ?: throw IOException("the file could not be opened")
        try {
            input.buffered().use { endToEnd().restore(it, passphrase) }.also { if (it != null && it > 0) onRestored() }
        } catch (failure: E2eeBackup.Failure) {
            if (failure.restored > 0) onRestored()
            throw failure
        }
    }

    /** Takes away a file that was made to hold a backup and holds none. */
    suspend fun discard(target: Uri) = withContext(Dispatchers.IO) { remove(target) }

    /** Emptied first — that works wherever writing did — then deleted, where the place it is kept in allows that. */
    private fun remove(target: Uri) {
        runCatching { open(target).close() }
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
    }
}
