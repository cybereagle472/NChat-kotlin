package com.nchat.app.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Turns a picked photo into something worth sending.
 *
 * Two things happen here, and both matter:
 *
 * 1. **Privacy** — a phone photo carries EXIF (GPS coordinates, device model, sometimes a
 *    thumbnail of the original). Re-encoding through a Bitmap drops all of it; only the
 *    pixels survive.
 * 2. **Speed** — a modern camera JPEG is 4–8 MB. Downscaling to [MAX_DIMENSION] and
 *    compressing at 85% lands around 150–300 KB, which is what makes a chat list scroll
 *    smoothly and an upload finish before the user changes screens.
 *
 * A thumbnail is produced alongside so the bubble and the media viewer can paint instantly
 * while the full file streams in.
 */
object MediaPreparer {

    /** Longest side of the uploaded image. */
    private const val MAX_DIMENSION = 1600

    /** Longest side of the preview stored in `thumbnail_url`. */
    private const val THUMB_DIMENSION = 320

    private const val QUALITY = 85
    private const val THUMB_QUALITY = 70

    data class Prepared(
        val bytes: ByteArray,
        val thumbnail: ByteArray?,
        val fileName: String,
        val mimeType: String
    )

    /** True when the pick is an image we can re-encode (everything else is sent untouched). */
    fun canCompress(mimeType: String): Boolean = mimeType.startsWith("image/")

    /**
     * Decodes [uri], applies its EXIF rotation, downscales it and returns JPEG bytes plus a
     * thumbnail. Returns null when the image cannot be decoded (the caller then falls back to
     * uploading the original file).
     */
    fun prepare(context: Context, uri: Uri, fileName: String): Prepared? {
        val resolver = context.contentResolver

        // Pass 1: bounds only, so a 50 MP photo is never fully decoded.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.getOrNull() ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Pass 2: decode at the smallest power-of-two sample that is still >= the target.
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null

        val rotated = rotate(decoded, exifRotation(context, uri))
        val main = scaleDown(rotated, MAX_DIMENSION)
        val thumb = scaleDown(main, THUMB_DIMENSION)

        val jpeg = encode(main, QUALITY) ?: return null
        val thumbnail = encode(thumb, THUMB_QUALITY)

        val stem = fileName.substringBeforeLast('.', fileName).ifBlank { "photo" }
        return Prepared(
            bytes = jpeg,
            thumbnail = thumbnail,
            fileName = "$stem.jpg",
            mimeType = "image/jpeg"
        )
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }

    private fun exifRotation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray? = runCatching {
        ByteArrayOutputStream().use { sink ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, sink)
            sink.toByteArray()
        }
    }.getOrNull()
}
