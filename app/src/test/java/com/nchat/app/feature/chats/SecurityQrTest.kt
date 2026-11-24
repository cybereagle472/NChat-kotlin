package com.nchat.app.feature.chats

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.nchat.app.network.SecurityCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture of a security code has to be a QR code that another phone's
 * camera reads back as the same sixty digits. There is no camera here, so the
 * picture is drawn the way the screen draws it and handed to the reader the
 * scanner uses.
 */
class SecurityQrTest {
    private val code = "427967518579389282864513861332751967621959880930629597202811"

    /** The modules as pixels: [scale] pixels a module, with a white margin of [quiet] modules. */
    private fun read(modules: Array<BooleanArray>, scale: Int = 6, quiet: Int = 4): String {
        val side = (modules.size + 2 * quiet) * scale
        val pixels = IntArray(side * side) { 0xFFFFFF }
        for (y in modules.indices) for (x in modules[y].indices) {
            if (!modules[y][x]) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0x000000
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
    }

    @Test
    fun thePictureReadsBackAsTheCode() {
        val text = SecurityCode.qrText(code)
        val modules = qrModules(text)
        assertNotNull(modules)
        assertEquals(text, read(modules!!))
        assertEquals("…and so as the same sixty digits", code, SecurityCode.fromQr(read(modules)))
        assertEquals(SecurityCode.Scanned.Same, SecurityCode.scanned(read(modules), code))
    }

    @Test
    fun thePictureIsSquareAndSmallEnoughToScanOffAPhone() {
        val modules = qrModules(SecurityCode.qrText(code))!!
        assertTrue(modules.all { it.size == modules.size })
        // 70 characters of the dense kind with medium error correction: version 4, 33 modules.
        assertTrue("${modules.size} modules a side", modules.size in 21..37)
        // No margin of its own: the screen draws the white border.
        assertTrue(modules.first().any { it } && modules.last().any { it })
        assertTrue(modules.any { it.first() } && modules.any { it.last() })
    }

    @Test
    fun anotherCodeGivesAnotherPicture() {
        val other = code.replaceRange(0, 1, "5")
        val first = qrModules(SecurityCode.qrText(code))!!
        val second = qrModules(SecurityCode.qrText(other))!!
        assertTrue(first.indices.any { !first[it].contentEquals(second[it]) })
        assertEquals(SecurityCode.Scanned.Different, SecurityCode.scanned(read(second), code))
    }

    @Test
    fun nothingToDrawGivesNoPicture() {
        assertNull(qrModules(""))
    }
}
