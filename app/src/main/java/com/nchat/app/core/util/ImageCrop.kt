package com.nchat.app.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Bitmap helpers for the avatar cropper. The preview and the export share the same
 * transform maths, so what you see in the circular frame is exactly what gets saved.
 */
object ImageCrop {

    /** Scale that makes the image fully cover a square of [framePx] (no empty gaps). */
    fun coverScale(widthPx: Int, heightPx: Int, framePx: Float): Float =
        if (widthPx <= 0 || heightPx <= 0) 1f else framePx / min(widthPx, heightPx).toFloat()

    /** Largest pan that still keeps the frame covered, per axis. */
    fun maxPan(
        widthPx: Int,
        heightPx: Int,
        framePx: Float,
        zoom: Float,
        rotationDegrees: Float
    ): Offset {
        val scale = coverScale(widthPx, heightPx, framePx) * zoom
        val swapped = (Math.round(rotationDegrees / 90f) % 2) != 0
        val dispW = (if (swapped) heightPx else widthPx) * scale
        val dispH = (if (swapped) widthPx else heightPx) * scale
        return Offset(
            max(0f, (dispW - framePx) / 2f),
            max(0f, (dispH - framePx) / 2f)
        )
    }

    /**
     * Renders the visible circle into a [output] × [output] square bitmap, applying the
     * same pan / zoom / rotation the preview showed.
     */
    fun cropToSquare(
        source: ImageBitmap,
        zoom: Float,
        rotationDegrees: Float,
        pan: Offset,
        framePx: Float,
        output: Int = 640
    ): ImageBitmap = runCatching {
        val bitmap = source.asAndroidBitmap()
        val k = output / framePx
        val result = Bitmap.createBitmap(output, output, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val scale = coverScale(bitmap.width, bitmap.height, framePx) * zoom * k
        canvas.translate(output / 2f + pan.x * k, output / 2f + pan.y * k)
        canvas.rotate(rotationDegrees)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bitmap, -bitmap.width / 2f, -bitmap.height / 2f, paint)
        result.asImageBitmap()
    }.getOrElse { source }

    /** Decodes a picked image, downsampled so a 48MP photo cannot OOM the app. */
    fun loadFromUri(context: Context, uri: Uri, maxDimension: Int = 2048): ImageBitmap? =
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension) {
                sample *= 2
            }

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?.asImageBitmap()
        }.getOrNull()
}
