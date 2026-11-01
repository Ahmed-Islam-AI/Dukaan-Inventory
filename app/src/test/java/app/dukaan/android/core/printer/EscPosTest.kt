package app.dukaan.android.core.printer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosTest {

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

    @Test
    fun `small image is one band with correct header and bits`() {
        // 10 x 2: row 0 = left-most dot only, row 1 = dots 0 and 9.
        val bytes = EscPos.raster(10, 2) { x, y -> x == 0 || (y == 1 && x == 9) }
        assertArrayEquals(byteArrayOf(0x1B, 0x40), bytes.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(0x1D, 0x76, 0x30, 0x00), bytes.copyOfRange(2, 6))
        assertEquals(2, bytes.u(6)); assertEquals(0, bytes.u(7)) // 2 bytes per row (10 dots padded to 16)
        assertEquals(2, bytes.u(8)); assertEquals(0, bytes.u(9)) // 2 rows
        assertEquals(0x80, bytes.u(10)); assertEquals(0x00, bytes.u(11)) // row 0
        assertEquals(0x80, bytes.u(12)); assertEquals(0x40, bytes.u(13)) // row 1: dot 9 = 2nd bit of byte 2
        // Trailer: feed 4 lines, partial cut.
        assertArrayEquals(byteArrayOf(0x1B, 0x64, 4, 0x1D, 0x56, 66, 0), bytes.copyOfRange(14, bytes.size))
    }

    @Test
    fun `tall image is split into 24-row bands for small printer buffers`() {
        val width = 384 // 58 mm
        val height = 50
        val bytes = EscPos.raster(width, height) { _, _ -> false }
        val perBandHeader = 8
        val rowBytes = width / 8
        val expected = 2 + (3 * perBandHeader) + height * rowBytes + 7 // init + 3 bands (24, 24, 2) + trailer
        assertEquals(expected, bytes.size)
        // Third band header says 2 rows.
        val thirdHeader = 2 + 2 * (perBandHeader + 24 * rowBytes)
        assertEquals(2, bytes.u(thirdHeader + 6))
    }

    @Test
    fun `80 mm width header encodes 72 bytes per row`() {
        val bytes = EscPos.raster(576, 1) { _, _ -> true }
        assertEquals(72, bytes.u(6))
        assertEquals(0xFF, bytes.u(10))
    }

    @Test
    fun `dark detection`() {
        assertTrue(EscPos.isDark(0xFF000000.toInt()))
        assertFalse(EscPos.isDark(0xFFFFFFFF.toInt()))
        assertFalse(EscPos.isDark(0x00000000)) // transparent
        assertTrue(EscPos.isDark(0xFF404040.toInt()))
        assertFalse(EscPos.isDark(0xFFD0D0D0.toInt()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty image is a bug`() {
        EscPos.raster(0, 10) { _, _ -> true }
    }
}
