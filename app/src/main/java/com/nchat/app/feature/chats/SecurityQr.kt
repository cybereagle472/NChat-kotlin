package com.nchat.app.feature.chats

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * The QR code for [text] as rows of modules — true for a dark one — without a margin; the
 * screen draws the white border. Null when there is nothing to draw or it cannot be drawn.
 *
 * Medium error correction: the code is read off another phone's screen, at an angle and with
 * reflections, and what it carries is short enough to afford it.
 */
internal fun qrModules(text: String): Array<BooleanArray>? {
    if (text.isEmpty()) return null
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0
        )
        // Asked for no size at all, the writer answers with one cell for each module.
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
    } catch (_: Exception) {
        null
    }
}

/** White modules a scanner needs around a QR code before it will take it for one. */
private const val QUIET_MODULES = 4

/** [text] as a QR code: black on white whatever the theme, with the border a scanner needs. */
@Composable
internal fun QrImage(text: String, side: Dp, description: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) } ?: return
    Canvas(
        modifier = modifier
            .size(side)
            .background(Color.White)
            .semantics { contentDescription = description }
    ) {
        val cell = this.size.minDimension / (modules.size + 2 * QUIET_MODULES)
        for (y in modules.indices) {
            val row = modules[y]
            for (x in row.indices) {
                if (!row[x]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset((x + QUIET_MODULES) * cell, (y + QUIET_MODULES) * cell),
                    // A hair more than a cell, so that no light shows between two dark ones.
                    size = Size(cell + 0.5f, cell + 0.5f)
                )
            }
        }
    }
}

/**
 * Opens the camera to read a QR code. [onScanned] gets what the code says, or null when the
 * user turned back without reading one. The answer to call it with a line of help for the
 * camera screen.
 *
 * The camera screen is the scanning library's own; it asks for the camera permission itself.
 */
@Composable
internal fun rememberQrScanner(onScanned: (String?) -> Unit): (prompt: String) -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result -> onScanned(result.contents) }
    return { prompt ->
        runCatching {
            launcher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(prompt)
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
            )
        }
    }
}
