package com.nchat.app.network

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest

/**
 * A sealed file as the app passes it around once the message that named it
 * has been opened.
 *
 * Every part of the app that shows a photo or plays a clip takes one string:
 * the address of the file. For a sealed file that string is the address the
 * upload was given, followed by a fragment that holds the key —
 *
 *     https://server/media/file/<uploader>/<id>#nchat-sealed:v1:<key>:<size>:<extension>
 *
 * The fragment is the part of an address that is never sent to a server, so
 * the string can be handed to whatever fetches files and the key stays on the
 * phone. Anything that sends an address *as data* — a message, a log line —
 * must send [strip] of it.
 */
object SealedMedia {
    private const val MARK = "#nchat-sealed:"
    private const val VERSION = "v1"
    private const val UNKNOWN = "bin"
    private const val MEDIA_PATH = "/media/file/"
    private val EXTENSION = Regex("[a-z0-9]{1,5}")

    /** A sealed file: where its bytes are, the key that opens them, the size of the content, and a file-name ending. */
    class Ref(val url: String, val key: ByteArray, val size: Long, val extension: String)

    /**
     * The address of a sealed file. [extension] only names the opened copy
     * (players and viewers go by it); anything that is not a few letters or
     * digits becomes `bin`.
     */
    fun address(url: String, key: ByteArray, size: Long, extension: String?): String {
        require(key.size == E2eeAttachment.KEY_BYTES) { "a file key is ${E2eeAttachment.KEY_BYTES} bytes" }
        return (strip(url) ?: url) + MARK + VERSION + ":" + urlSafe(encodeBase64(key)) + ":" + size + ":" + clean(extension)
    }

    /** What [address] made, taken apart again; null for any other address and for a fragment that is not whole. */
    fun parse(address: String?): Ref? {
        if (address == null) return null
        val at = address.indexOf(MARK)
        if (at <= 0) return null
        val parts = address.substring(at + MARK.length).split(':')
        if (parts.size != 4 || parts[0] != VERSION) return null
        val key = decodeBase64(standard(parts[1]))?.takeIf { it.size == E2eeAttachment.KEY_BYTES } ?: return null
        val size = parts[2].toLongOrNull()?.takeIf { it in 0..E2eeAttachment.MAX_BYTES } ?: return null
        return Ref(address.substring(0, at), key, size, clean(parts[3]))
    }

    fun isSealed(address: String?): Boolean = parse(address) != null

    /**
     * Whether [text] holds the key of a sealed file, whole or damaged — asked of everything
     * that is about to be sent to a server as readable data.
     */
    fun carriesKey(text: String?): Boolean = text != null && text.contains(MARK)

    /** [address] without the key — also when what follows the mark is damaged. Other addresses come back as they are. */
    fun strip(address: String?): String? {
        if (address == null) return null
        val at = address.indexOf(MARK)
        return if (at < 0) address else address.substring(0, at)
    }

    /** The file-name ending for a file of this type and name: the name's own when it has a plain one, else by type. */
    fun extensionFor(mime: String?, name: String?): String {
        val fromName = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { name.contains('.') && EXTENSION.matches(it) }
        val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val fromType = when {
            type == "image/jpeg" || type == "image/jpg" -> "jpg"
            type == "image/png" -> "png"
            type == "image/webp" -> "webp"
            type == "image/gif" -> "gif"
            type == "video/mp4" -> "mp4"
            type == "video/webm" -> "webm"
            type == "video/3gpp" -> "3gp"
            type == "audio/mp4" || type == "audio/m4a" || type == "audio/aac" -> "m4a"
            type == "audio/ogg" || type == "audio/opus" -> "ogg"
            type == "audio/mpeg" -> "mp3"
            type == "audio/webm" -> "webm"
            type == "application/pdf" -> "pdf"
            else -> null
        }
        // A photo named ".JPEG" is still a jpg to whatever opens it.
        return when (fromName) {
            null -> fromType ?: UNKNOWN
            "jpeg" -> "jpg"
            else -> fromName
        }
    }

    /**
     * Whether [address] is a file on the server at [base] (the address the
     * app was built for), under its media path. A sealed message can name
     * any address; only this one is ever fetched.
     */
    fun isOn(base: String, address: String): Boolean = try {
        val root = URI(base.trim().trimEnd('/'))
        val target = URI(strip(address) ?: "")
        val path = target.rawPath.orEmpty()
        val prefix = root.rawPath.orEmpty().trimEnd('/') + MEDIA_PATH
        base.isNotBlank() &&
            root.scheme != null && root.host != null &&
            target.scheme.equals(root.scheme, ignoreCase = true) &&
            target.host.equals(root.host, ignoreCase = true) &&
            port(target) == port(root) &&
            target.rawUserInfo == null &&
            target.rawQuery == null &&
            path.startsWith(prefix) && path.length > prefix.length &&
            // No segment that walks back out of the media path, however it is spelled.
            path.substring(prefix.length).split('/').none { it.isEmpty() || it == "." || it == ".." || it.contains('%') }
    } catch (_: Exception) {
        false
    }

    /**
     * A small picture as text (`data:image/jpeg;base64,…`): how the thumbnail of a file that
     * is waiting to be sent is kept next to it until it travels inside the sealed message.
     */
    fun inlineImage(jpeg: ByteArray): String = INLINE_JPEG + encodeBase64(jpeg)

    /** The picture [inlineImage] wrote; null for anything else, an address included. */
    fun inlineImageBytes(text: String?): ByteArray? {
        if (text == null || !text.startsWith(INLINE_JPEG)) return null
        return decodeBase64(text.substring(INLINE_JPEG.length))?.takeIf { it.isNotEmpty() }
    }

    private const val INLINE_JPEG = "data:image/jpeg;base64,"

    private fun port(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    private fun clean(extension: String?): String =
        extension?.trim()?.removePrefix(".")?.lowercase()?.takeIf { EXTENSION.matches(it) } ?: UNKNOWN

    private fun urlSafe(base64: String): String = base64.trimEnd('=').replace('+', '-').replace('/', '_')

    private fun standard(urlSafe: String): String {
        val text = urlSafe.replace('-', '+').replace('_', '/')
        return text + "=".repeat((4 - text.length % 4) % 4)
    }
}

/**
 * The opened copies of sealed files on this phone.
 *
 * A player or an image view needs a file it can read, so a sealed file is
 * fetched, opened and kept here in the open, inside the storage that belongs
 * to the signed-in account ([directory]; see `EndToEnd.mediaDirectory`) —
 * erasing the account removes it with the keys. Nothing is kept that did not open
 * completely: a file is written under a temporary name and takes its real
 * one only once every chunk has been checked.
 *
 * The room is bounded ([maxBytes]); the files used longest ago go first, and
 * can be fetched again.
 */
class SealedMediaStore(
    /** Where to keep files; null while nobody is signed in. */
    private val directory: () -> File?,
    private val maxBytes: Long = DEFAULT_BYTES,
    private val maxThumbnailBytes: Long = DEFAULT_THUMBNAIL_BYTES,
) {
    /** One fetch per file at a time: a bubble and the viewer asking together share the download. */
    private val gates = Array(GATES) { Any() }

    /** The opened copy of [address] if it is here; using it is what keeps it. */
    fun cached(address: String?): File? {
        val ref = SealedMedia.parse(address) ?: return null
        val file = fileFor(ref) ?: return null
        if (!file.isFile || file.length() != ref.size) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /**
     * The opened copy of [address], fetched with [fetch] when it is not here
     * yet. [fetch] gets the address without the key and writes what the
     * server answers. Throws [IOException] when the file cannot be fetched
     * or is not what the sender sealed; nothing is kept then.
     */
    @Throws(IOException::class)
    fun local(address: String?, fetch: (url: String, into: OutputStream) -> Unit): File {
        val ref = SealedMedia.parse(address) ?: throw IOException("not a sealed file")
        val target = fileFor(ref) ?: throw IOException("there is nowhere to keep files right now")
        synchronized(gates[(target.name.hashCode() and Int.MAX_VALUE) % GATES]) {
            cached(address)?.let { return it }
            val folder = target.parentFile ?: throw IOException("there is nowhere to keep files right now")
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("could not create the folder for files")
            val sealed = File(folder, target.name + SEALED_SUFFIX)
            val opening = File(folder, target.name + OPENING_SUFFIX)
            try {
                FileOutputStream(sealed).use { fetch(ref.url, it) }
                val opened = sealed.inputStream().buffered().use { input ->
                    FileOutputStream(opening).use { output -> E2eeAttachment.open(ref.key, input, ref.size, output) }
                }
                if (!opened || opening.length() != ref.size) throw IOException("the file is not what was sent")
                if (!opening.renameTo(target)) throw IOException("the file could not be kept")
            } finally {
                sealed.delete()
                opening.delete()
            }
            prune(folder, keep = target, limit = maxBytes)
            return target
        }
    }

    /**
     * Puts the content this phone has just sealed and uploaded where
     * [local] will look for it, so its own bubble shows it without a
     * download. Null when [address] is not a sealed address, [content] is
     * not of the size it names, or there is nowhere to keep it.
     */
    fun seed(address: String?, content: ByteArray): File? {
        val ref = SealedMedia.parse(address) ?: return null
        if (content.size.toLong() != ref.size) return null
        val target = fileFor(ref) ?: return null
        return try {
            val folder = target.parentFile ?: return null
            if (!folder.isDirectory && !folder.mkdirs()) return null
            val writing = File(folder, target.name + OPENING_SUFFIX)
            try {
                writing.writeBytes(content)
                if (!writing.renameTo(target)) return null
            } finally {
                writing.delete()
            }
            prune(folder, keep = target, limit = maxBytes)
            target
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The small picture that came inside the sealed message of [address],
     * written to a file so that an image view can show it. Null when there
     * is nothing to show or nowhere to keep it.
     */
    fun thumbnail(address: String?, jpeg: ByteArray): File? {
        val ref = SealedMedia.parse(address) ?: return null
        if (jpeg.isEmpty()) return null
        val root = directory() ?: return null
        return try {
            val folder = File(root, THUMBNAILS)
            val target = File(folder, nameOf(ref) + ".jpg")
            if (target.isFile && target.length() == jpeg.size.toLong()) return target
            if (!folder.isDirectory && !folder.mkdirs()) return null
            val writing = File(folder, target.name + OPENING_SUFFIX)
            try {
                writing.writeBytes(jpeg)
                if (!writing.renameTo(target)) return null
            } finally {
                writing.delete()
            }
            prune(folder, keep = target, limit = maxThumbnailBytes)
            target
        } catch (_: Exception) {
            null
        }
    }

    /** The message of [address] was deleted: its opened file and its small picture go with it. */
    fun forget(address: String?) {
        val ref = SealedMedia.parse(address) ?: return
        try {
            fileFor(ref)?.delete()
            directory()?.let { File(File(it, THUMBNAILS), nameOf(ref) + ".jpg").delete() }
        } catch (_: Exception) {
            // Still bounded by the room the store has; it goes when its turn comes.
        }
    }

    private fun fileFor(ref: SealedMedia.Ref): File? = directory()?.let { File(it, nameOf(ref) + "." + ref.extension) }

    /** A name that says nothing of the address, and differs when the key does. */
    private fun nameOf(ref: SealedMedia.Ref): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ref.url.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(ref.key)
        return digest.digest().copyOf(NAME_BYTES).joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
    }

    /** Keeps [folder] under [limit] bytes by dropping the files used longest ago; never [keep]. */
    private fun prune(folder: File, keep: File, limit: Long) {
        try {
            val files = folder.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= limit) return
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= limit) return
                if (file == keep) continue
                val length = file.length()
                if (file.delete()) total -= length
            }
        } catch (_: Exception) {
            // A cache that could not be trimmed now is trimmed at the next file.
        }
    }

    private companion object {
        const val DEFAULT_BYTES = 256L * 1024 * 1024
        const val DEFAULT_THUMBNAIL_BYTES = 24L * 1024 * 1024
        const val THUMBNAILS = "thumbs"
        const val SEALED_SUFFIX = ".sealed"
        const val OPENING_SUFFIX = ".part"
        const val NAME_BYTES = 16
        const val GATES = 16
    }
}
