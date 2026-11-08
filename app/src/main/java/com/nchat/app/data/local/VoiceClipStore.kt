package com.nchat.app.data.local

import android.content.Context
import java.io.File

/**
 * Recorded voice clips waiting to be uploaded.
 *
 * A voice note used to exist only in the memory of one `sendVoice` call: the bytes went straight
 * to `POST /media/upload`, and if that failed — no signal, a tunnel, airplane mode — the
 * recording was gone and the bubble sat there red. WhatsApp does not work that way: you talk,
 * the clip is on the phone, and it leaves when the phone has a network.
 *
 * So the bytes land here first (`filesDir/voice-out/<client_message_id>.m4a`), the outbox entry
 * points at the file, and delivery uploads it. The file is deleted once the server has the
 * message. Unreferenced files are retained until a safe, coordinated cleanup is available.
 *
 * Nothing here is user-facing: the bubble plays a pending clip straight off this file.
 */
class VoiceClipStore internal constructor(private val dir: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "voice-out"))

    /**
     * Writes [bytes] for [clientId] and returns the absolute path, or null when the disk refuses.
     *
     * A null return means the recording could not be saved. The caller reports a failure rather
     * than sending an empty voice message.
     */
    fun save(clientId: String, bytes: ByteArray, extension: String): String? = runCatching {
        if (bytes.isEmpty()) return null
        dir.mkdirs()
        val safeId = safeId(clientId)
        val safeExt = extension.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "m4a" }
        val file = File(dir, "$safeId.$safeExt")
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()

    /** The clip at [path], or null when it is gone or cannot be read. */
    fun read(path: String?): ByteArray? = runCatching {
        val file = path?.let { File(it) } ?: return null
        if (!file.isFile || !inside(file)) return null
        file.takeIf { it.length() > 0 }?.readBytes()
    }.getOrNull()

    /** Resolve only this message's own clip, not another pending recording or an arbitrary URI. */
    internal fun ownedPathFromUri(clientId: String, uri: String?): String? = runCatching {
        if (uri == null || !uri.startsWith("file://")) return null
        val file = File(uri.removePrefix("file://")).canonicalFile
        if (file.parentFile != dir.canonicalFile || file.name.substringBeforeLast('.', "") != safeId(clientId)) {
            return null
        }
        file.takeIf { it.isFile }?.absolutePath
    }.getOrNull()

    fun delete(path: String?) {
        runCatching {
            val file = path?.let { File(it) } ?: return
            if (inside(file)) file.delete()
        }
    }

    private fun safeId(clientId: String): String =
        clientId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "voice" }

    /** Only files this store owns can ever be deleted — never a caller's arbitrary path. */
    private fun inside(file: File): Boolean = runCatching {
        val root = dir.canonicalPath + File.separator
        file.canonicalPath.startsWith(root)
    }.getOrDefault(false)
}
