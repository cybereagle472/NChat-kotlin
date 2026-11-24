package com.nchat.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.nchat.app.network.E2eeBackup
import com.nchat.app.network.EndToEnd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

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
     * from (see [EndToEnd.backUp]). Whenever it does not end well, what was
     * written so far is taken away again: half a file must not be left lying
     * about looking like a backup.
     */
    suspend fun save(target: Uri, passphrase: CharArray): E2eeBackup.Written? = withContext(Dispatchers.IO) {
        try {
            val out = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("the file could not be opened")
            val written = out.buffered().use { endToEnd().backUp(it, passphrase) }
            if (written == null) remove(target)
            written
        } catch (failure: Throwable) {
            remove(target)
            throw failure
        }
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
        runCatching { context.contentResolver.openOutputStream(target, "wt")?.close() }
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
    }
}
