package app.dukaan.android.core.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCodesTest {

    // 490154203237518 is the classic valid example IMEI (Luhn-correct).
    private val imei = "490154203237518"

    @Test
    fun `Luhn check`() {
        assertTrue(ScanCodes.isValidImei(imei))
        assertFalse(ScanCodes.isValidImei("490154203237519"))
        assertFalse(ScanCodes.isValidImei("49015420323751"))
        assertFalse(ScanCodes.isValidImei("49015420323751X"))
    }

    @Test
    fun `IMEI labels and spaces are stripped`() {
        assertEquals(imei, ScanCodes.clean(imei))
        assertEquals(imei, ScanCodes.clean(" $imei\n"))
        assertEquals(imei, ScanCodes.clean("IMEI:$imei"))
        assertEquals(imei, ScanCodes.clean("IMEI1 $imei"))
        assertEquals(imei, ScanCodes.clean("49 0154 2032 3751 8"))
    }

    @Test
    fun `product barcodes and other text pass through`() {
        assertEquals("8964000123456", ScanCodes.clean("8964000123456")) // EAN-13, 13 digits
        assertEquals("SKU-CHG-25W", ScanCodes.clean(" SKU-CHG-25W "))
        // 15 digits that fail Luhn are not "fixed" into an IMEI.
        assertEquals("490154203237519", ScanCodes.clean("490154203237519"))
    }
}
