package app.dukaan.android.core.printer

import java.io.ByteArrayOutputStream

/**
 * ESC/POS raster encoding (D-56). Nearly every 58/80 mm Bluetooth receipt printer understands
 * `GS v 0`, so we send the receipt as pixels: the same image as the PDF (D-21), Urdu included,
 * no printer-specific text commands, no third-party library.
 *
 *   ESC @                         initialise
 *   GS v 0 m xL xH yL yH d...     one band of up to [BAND_ROWS] rows, 1 bit per dot, MSB = left
 *   ...                           next band (small bands: cheap printers have tiny buffers)
 *   ESC d n                       feed n lines so the paper clears the tear bar
 *   GS V 66 0                     partial cut (ignored by printers without a cutter)
 */
object EscPos {
    const val BAND_ROWS = 24
    private const val FEED_LINES = 4

    /**
     * Encodes a [width] × [height] image. [isBlack] is asked once per dot. Width is padded up to a
     * whole byte with white dots.
     */
    fun raster(width: Int, height: Int, isBlack: (x: Int, y: Int) -> Boolean): ByteArray {
        require(width > 0 && height > 0) { "empty image ${width}x$height" }
        val bytesPerRow = (width + 7) / 8
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @
        var top = 0
        while (top < height) {
            val rows = minOf(BAND_ROWS, height - top)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))           // GS v 0, normal density
            out.write(bytesPerRow and 0xFF); out.write(bytesPerRow shr 8 and 0xFF)
            out.write(rows and 0xFF); out.write(rows shr 8 and 0xFF)
            for (y in top until top + rows) {
                for (byteIndex in 0 until bytesPerRow) {
                    var b = 0
                    for (bit in 0 until 8) {
                        val x = byteIndex * 8 + bit
                        if (x < width && isBlack(x, y)) b = b or (0x80 ushr bit)
                    }
                    out.write(b)
                }
            }
            top += rows
        }
        out.write(byteArrayOf(0x1B, 0x64, FEED_LINES.toByte())) // ESC d n
        out.write(byteArrayOf(0x1D, 0x56, 66, 0))               // GS V 66 0 (partial cut)
        return out.toByteArray()
    }

    /** Luminance threshold: dark enough pixels print as a dot. Anti-aliased text stays crisp. */
    fun isDark(argb: Int): Boolean {
        val a = argb ushr 24 and 0xFF
        if (a < 128) return false // transparent = paper
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000 < 160
    }
}
