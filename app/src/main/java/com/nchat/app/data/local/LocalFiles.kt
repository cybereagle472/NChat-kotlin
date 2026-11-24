package com.nchat.app.data.local

import com.nchat.app.core.util.CrashLog
import com.nchat.app.network.LocalSeal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** What a file of one of the on-phone stores holds, as far as the signed-in account can tell. */
internal sealed class Stored {
    /** There is no such file. */
    object Absent : Stored()

    /** Its content. [plain]: it was written by a build before sealing, and is to be sealed at the next chance. */
    class Readable(val text: String, val plain: Boolean) : Stored()

    /** Sealed, and there is nothing to open it with right now. It may open later: it is left alone. */
    object Locked : Stored()

    /** Sealed with another key — another account's, or this one's before a sign-out. It will never open here. */
    object Foreign : Stored()

    /** Sealed with this account's key and still does not open: damaged, or put in another file's place. */
    object Damaged : Stored()
}

/**
 * Reading and writing for everything the app keeps on the phone beside its
 * keys and its encrypted history: cached conversations, the lists with their
 * previews, messages waiting to be sent, drafts, stars, pins, the archive.
 *
 * All of it is sealed for the account that is signed in (see [LocalSeal]),
 * and every store follows the same rules:
 *  - what is written is sealed; with nothing to seal with, nothing is
 *    written — never an unsealed copy "for now";
 *  - a file from a build before sealing is still read, and is sealed from
 *    then on;
 *  - a sealed file that cannot be opened right now stays where it is.
 *
 * What a store does with a file that will never open, or is damaged, is the
 * store's own decision; [read] only tells the cases apart.
 */
internal class LocalFiles(private val seal: () -> LocalSeal?) {

    /** Throws when the file exists and cannot be read at all. */
    fun read(file: File, place: String): Stored {
        if (!file.isFile) return Stored.Absent
        val bytes = file.readBytes()
        if (!LocalSeal.isSealed(bytes)) return Stored.Readable(String(bytes, Charsets.UTF_8), plain = true)
        val seal = seal() ?: return Stored.Locked
        val opened = seal.open(place, bytes)
        return when {
            opened != null -> Stored.Readable(String(opened, Charsets.UTF_8), plain = false)
            seal.sealedHere(bytes) -> Stored.Damaged
            else -> Stored.Foreign
        }
    }

    /**
     * Puts [text] in [file], sealed for [place]; the file is replaced whole
     * or not at all. False when there is nothing to seal with, or when the
     * write failed — what was there before is then still there.
     */
    fun write(
        file: File,
        place: String,
        text: String,
        temporaryPrefix: String = TEMPORARY_PREFIX,
        durable: Boolean = false,
        replace: (File, File) -> Boolean = RENAME,
    ): Boolean {
        val seal = seal() ?: return false
        return put(file, seal.seal(place, text.toByteArray(Charsets.UTF_8)), temporaryPrefix, durable, replace)
    }

    /**
     * Seals a file from a build before sealing where it lies, whatever is in
     * it. True when it was sealed by this call; false when it already was,
     * when there is no such file, or when there is nothing to seal with.
     */
    fun sealPlain(
        file: File,
        place: String,
        temporaryPrefix: String = TEMPORARY_PREFIX,
        replace: (File, File) -> Boolean = RENAME,
    ): Boolean {
        return try {
            if (!file.isFile || looksSealed(file)) return false
            val seal = seal() ?: return false
            put(file, seal.seal(place, file.readBytes()), temporaryPrefix, durable = false, replace = replace)
        } catch (error: Exception) {
            CrashLog.note("a file from before sealing could not be sealed", error)
            false
        }
    }

    /** The first bytes are enough to tell, and a sweep over many files reads no more than them. */
    private fun looksSealed(file: File): Boolean {
        val head = ByteArray(LocalSeal.MARK_BYTES)
        var filled = 0
        FileInputStream(file).use { stream ->
            while (filled < head.size) {
                val read = stream.read(head, filled, head.size - filled)
                if (read < 0) break
                filled += read
            }
        }
        return LocalSeal.isSealed(head.copyOf(filled))
    }

    private fun put(file: File, bytes: ByteArray, temporaryPrefix: String, durable: Boolean, replace: (File, File) -> Boolean): Boolean {
        var temporary: File? = null
        return try {
            val target = file.absoluteFile
            val parent = target.parentFile ?: throw IOException("the file has no directory")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("the directory could not be created")
            val candidate = File.createTempFile(temporaryPrefix, TEMPORARY_SUFFIX, parent)
            temporary = candidate
            FileOutputStream(candidate).use { stream ->
                stream.write(bytes)
                stream.flush()
                if (durable) stream.fd.sync()
            }
            // On the app's own storage this is a rename within one directory: the old file or the
            // new one, never half of each. The committed file is never deleted to make it work.
            if (!replace(candidate, target)) throw IOException("the file could not be replaced")
            true
        } catch (error: Exception) {
            // Says which kind of file, never which conversation.
            CrashLog.note("on-phone store write failed ($temporaryPrefix)", error)
            false
        } finally {
            runCatching { temporary?.delete() }
        }
    }

    companion object {
        const val TEMPORARY_PREFIX = "local-"
        const val TEMPORARY_SUFFIX = ".tmp"
        val RENAME: (File, File) -> Boolean = { source, target -> source.renameTo(target) }
    }
}

/**
 * A small sealed file whose whole content is also held in memory: drafts,
 * stars, pins, the archive.
 *
 * The content is read when the store is made. If the file is sealed and
 * there is nothing to open it with yet, the store starts empty and *knows
 * that it has not read the file*: until it has, what changes is kept in
 * memory only, so that what is on disk is not overwritten with less. Every
 * access tries the file again.
 *
 * A file that will never open here — sealed for another account, or
 * damaged — counts as empty and is replaced by the next change.
 */
internal class SealedState<T>(
    private val file: File,
    private val place: String,
    seal: () -> LocalSeal?,
    private val empty: T,
    private val isEmpty: (T) -> Boolean,
    private val decode: (String) -> T,
    private val encode: (T) -> String,
) {
    private val files = LocalFiles(seal)
    private val lock = Any()

    /** Guarded by [lock]. False while the file is there and has not been opened yet. */
    private var loaded = false

    private val state = MutableStateFlow(empty)
    val value: StateFlow<T> = state.asStateFlow()

    init {
        synchronized(lock) { load() }
    }

    fun current(): T = synchronized(lock) {
        load()
        state.value
    }

    /** Applies [change] and stores the result; a change that changes nothing writes nothing. */
    fun update(change: (T) -> T) {
        synchronized(lock) {
            load()
            val before = state.value
            val after = change(before)
            if (after == before) return
            state.value = after
            if (!loaded) return
            if (isEmpty(after)) {
                runCatching { file.delete() }
            } else {
                files.write(file, place, encode(after))
            }
        }
    }

    /** The account is gone: nothing is held and nothing is left on disk. */
    fun clear() {
        synchronized(lock) {
            state.value = empty
            loaded = true
            runCatching { file.delete() }
        }
    }

    private fun load() {
        if (loaded) return
        val stored = try {
            files.read(file, place)
        } catch (_: Exception) {
            return
        }
        when (stored) {
            Stored.Locked -> return
            Stored.Absent, Stored.Foreign, Stored.Damaged -> Unit
            is Stored.Readable -> {
                val decoded = runCatching { decode(stored.text) }.getOrNull()
                if (decoded != null) {
                    state.value = decoded
                    // From a build before sealing: read once, sealed from then on.
                    if (stored.plain) files.write(file, place, stored.text)
                } else if (stored.plain) {
                    // Unsealed and of no use to anybody: it is not left lying there.
                    runCatching { file.delete() }
                }
            }
        }
        loaded = true
    }
}
