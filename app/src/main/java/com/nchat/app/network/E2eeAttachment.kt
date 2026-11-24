package com.nchat.app.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The files of an encrypted conversation — photos, videos, voice messages,
 * documents.
 *
 * A file is too large to travel inside a sealed message, so it travels
 * beside it: the phone seals the file under a key made for that one file,
 * uploads the result, and puts the address and the key into the message
 * ([Pointer]), which is sealed like any text. The server stores bytes it
 * cannot read and never sees the key.
 *
 * Format, version 1:
 *
 *     01 ‖ prefix (7 random bytes) ‖ chunk 0 ‖ chunk 1 ‖ …
 *     chunk i = AES-256-GCM(key,
 *                           nonce = prefix ‖ i (4 bytes, big-endian) ‖ last (01 for the final chunk, else 00),
 *                           associated data = 01,
 *                           the next 65,536 bytes of the padded content)
 *
 * Each chunk is checked on its own, so a file of any size is sealed and
 * opened with a few kilobytes of memory. The chunk number in the nonce is
 * what keeps chunks from being swapped, repeated or left out, and the
 * last-chunk flag is what keeps the file from being cut short at a chunk
 * boundary: the chunk that is now last was not sealed as the last one, and
 * does not open. A key seals one file, so a nonce is never used twice.
 *
 * The content is padded with zeros to a size bucket ([paddedSize]) before it
 * is sealed: sixteen sizes per doubling. The server learns the bucket, not
 * the size. The true size travels in the pointer.
 */
object E2eeAttachment {

    const val KEY_BYTES = 32

    /**
     * The largest file that is sealed. Padded and sealed it stays under the
     * 49 MiB the server accepts in one upload.
     */
    const val MAX_BYTES = 44L * 1024 * 1024

    private const val VERSION: Byte = 1
    private const val PREFIX_BYTES = 7
    private const val HEADER_BYTES = 1 + PREFIX_BYTES
    private const val CHUNK_BYTES = 64 * 1024
    private const val TAG_BYTES = 16
    private const val SMALLEST_BUCKET = 1024L
    private const val SMALLEST_STEP = 64L
    private const val CIPHER = "AES/GCM/NoPadding"

    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    /**
     * The size [length] bytes of content are padded to: itself rounded up to
     * a sixteenth of the power of two below it (never less than 1,024 bytes).
     * Integer arithmetic only — this is part of the format.
     */
    fun paddedSize(length: Long): Long {
        require(length >= 0) { "a size cannot be negative" }
        if (length <= SMALLEST_BUCKET) return SMALLEST_BUCKET
        val step = maxOf(SMALLEST_STEP, java.lang.Long.highestOneBit(length) shr 4)
        return (length + step - 1) / step * step
    }

    /** How many bytes [length] bytes of content are once sealed — what is uploaded. */
    fun sealedSize(length: Long): Long {
        val padded = paddedSize(length)
        val chunks = (padded + CHUNK_BYTES - 1) / CHUNK_BYTES
        return HEADER_BYTES + padded + chunks * TAG_BYTES
    }

    /**
     * Seals the first [length] bytes of [input] into [output]. Throws
     * [IOException] when [input] ends before that — a file that is not all
     * there is not sealed as a shorter one — and [IllegalArgumentException]
     * for a key of the wrong size or a file over [MAX_BYTES].
     */
    fun seal(key: ByteArray, input: InputStream, length: Long, output: OutputStream) {
        sealWithPrefix(key, ByteArray(PREFIX_BYTES).also { random.nextBytes(it) }, input, length, output)
    }

    /** [seal] with the nonce prefix given, so the format can be pinned by a test. Never call it with a fixed prefix. */
    internal fun sealWithPrefix(key: ByteArray, prefix: ByteArray, input: InputStream, length: Long, output: OutputStream) {
        require(key.size == KEY_BYTES) { "a file key is $KEY_BYTES bytes" }
        require(prefix.size == PREFIX_BYTES) { "a nonce prefix is $PREFIX_BYTES bytes" }
        require(length in 0..MAX_BYTES) { "a file of $length bytes is not sealed" }
        val secret = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance(CIPHER)
        val padded = paddedSize(length)
        output.write(VERSION.toInt())
        output.write(prefix)

        val chunk = ByteArray(CHUNK_BYTES)
        var contentLeft = length
        var paddedLeft = padded
        var index = 0
        while (paddedLeft > 0) {
            val size = minOf(CHUNK_BYTES.toLong(), paddedLeft).toInt()
            val fromContent = minOf(size.toLong(), contentLeft).toInt()
            var filled = 0
            while (filled < fromContent) {
                val read = input.read(chunk, filled, fromContent - filled)
                if (read < 0) throw IOException("the file ended before it was all read")
                filled += read
            }
            // What is not content is padding: zeros.
            java.util.Arrays.fill(chunk, fromContent, size, 0)
            contentLeft -= fromContent
            paddedLeft -= size
            cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, index, last = paddedLeft == 0L)))
            cipher.updateAAD(byteArrayOf(VERSION))
            output.write(cipher.doFinal(chunk, 0, size))
            index++
        }
        output.flush()
    }

    /**
     * Opens a sealed file into [output] and answers whether all of it was
     * what the sender sealed. [length] is the size of the content, from the
     * pointer. Never throws.
     *
     * Chunks are written as they are checked, so on false [output] may
     * already hold the beginning of the file: write to a place that is
     * thrown away on false, never to the place the file is used from.
     */
    fun open(key: ByteArray, input: InputStream, length: Long, output: OutputStream): Boolean = try {
        openChecked(key, input, length, output)
    } catch (_: Exception) {
        false
    }

    private fun openChecked(key: ByteArray, input: InputStream, length: Long, output: OutputStream): Boolean {
        if (key.size != KEY_BYTES || length < 0 || length > MAX_BYTES) return false
        val header = ByteArray(HEADER_BYTES)
        if (readFully(input, header, 0, HEADER_BYTES) != HEADER_BYTES || header[0] != VERSION) return false
        val prefix = header.copyOfRange(1, HEADER_BYTES)
        val secret = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance(CIPHER)

        // One byte more than a chunk is read each time: whether anything follows is what
        // tells the last chunk from the others, and the last one was sealed as the last.
        val sealedChunk = CHUNK_BYTES + TAG_BYTES
        var buffer = ByteArray(sealedChunk + 1)
        var next = ByteArray(sealedChunk + 1)
        var held = readFully(input, buffer, 0, sealedChunk + 1)
        var contentLeft = length
        var index = 0
        while (true) {
            if (held < TAG_BYTES + 1) return false // no chunk, or one too short to hold anything
            val last = held <= sealedChunk
            val size = if (last) held else sealedChunk
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, index, last)))
            cipher.updateAAD(byteArrayOf(VERSION))
            val plain = cipher.doFinal(buffer, 0, size)
            val wanted = minOf(plain.size.toLong(), contentLeft).toInt()
            output.write(plain, 0, wanted)
            contentLeft -= wanted
            if (last) break
            // The byte read ahead is the first of the next chunk.
            next[0] = buffer[sealedChunk]
            held = 1 + readFully(input, next, 1, sealedChunk)
            val swap = buffer
            buffer = next
            next = swap
            index++
            if (index < 0) return false
        }
        output.flush()
        // Everything the pointer promised must have been there.
        return contentLeft == 0L
    }

    /** [seal] for content that is in memory. */
    fun seal(key: ByteArray, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(sealedSize(content.size.toLong()).toInt())
        seal(key, ByteArrayInputStream(content), content.size.toLong(), output)
        return output.toByteArray()
    }

    /** [open] for a sealed file that is in memory; null when it is not what the sender sealed. */
    fun open(key: ByteArray, sealed: ByteArray, length: Long): ByteArray? {
        if (length < 0 || length > MAX_BYTES) return null
        val output = ByteArrayOutputStream(minOf(length, sealed.size.toLong()).toInt())
        return if (open(key, ByteArrayInputStream(sealed), length, output)) output.toByteArray() else null
    }

    private fun nonce(prefix: ByteArray, index: Int, last: Boolean): ByteArray {
        val nonce = ByteArray(PREFIX_BYTES + 5)
        System.arraycopy(prefix, 0, nonce, 0, PREFIX_BYTES)
        nonce[PREFIX_BYTES] = (index ushr 24).toByte()
        nonce[PREFIX_BYTES + 1] = (index ushr 16).toByte()
        nonce[PREFIX_BYTES + 2] = (index ushr 8).toByte()
        nonce[PREFIX_BYTES + 3] = index.toByte()
        nonce[PREFIX_BYTES + 4] = if (last) 1 else 0
        return nonce
    }

    /** Reads until [length] bytes are there or the input ends; returns how many were read. */
    private fun readFully(input: InputStream, target: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = input.read(target, offset + total, length - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    /**
     * Where a sealed file is and how to open it — the part of a sealed
     * message that stands for its attachment.
     *
     * [url] is the address the upload was given; [key] opens it; [size] is
     * the size of the content inside the padding. The rest describes the
     * file to the person, and is as sealed as the message it is in: [mime]
     * and [name] as the sender's phone knew them, [duration] of a voice
     * message or video, the [waveform] of a voice message, and a small
     * [thumbnail] (JPEG) of a photo or video, shown before the file itself
     * has been fetched.
     */
    class Pointer(
        val url: String,
        val key: ByteArray,
        val size: Long,
        val mime: String? = null,
        val name: String? = null,
        val duration: String? = null,
        val waveform: String? = null,
        val thumbnail: ByteArray? = null,
    ) {
        fun encode(): String = buildJsonObject {
            put("u", url)
            put("k", encodeBase64(key))
            put("z", size)
            mime?.let { put("m", it) }
            name?.let { put("f", it) }
            duration?.let { put("d", it) }
            waveform?.let { put("w", it) }
            thumbnail?.let { put("t", encodeBase64(it)) }
        }.toString()

        companion object {
            /** Null for anything that is not a pointer this build can use. Fields it does not know are ignored. */
            fun decode(text: String): Pointer? = try {
                val fields = Json.parseToJsonElement(text) as? JsonObject
                val url = fields?.string("u")?.takeIf { it.isNotBlank() }
                val key = fields?.string("k")?.let { decodeBase64(it) }?.takeIf { it.size == KEY_BYTES }
                val size = (fields?.get("z") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
                if (fields == null || url == null || key == null || size == null || size < 0 || size > MAX_BYTES) {
                    null
                } else {
                    Pointer(
                        url = url,
                        key = key,
                        size = size,
                        mime = fields.string("m"),
                        name = fields.string("f"),
                        duration = fields.string("d"),
                        waveform = fields.string("w"),
                        thumbnail = fields.string("t")?.let { decodeBase64(it) },
                    )
                }
            } catch (_: Exception) {
                null
            }

            private fun JsonObject.string(name: String): String? =
                (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }
}
