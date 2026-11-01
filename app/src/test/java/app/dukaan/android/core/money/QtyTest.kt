package app.dukaan.android.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QtyTest {

    @Test
    fun `parses whole and decimal quantities`() {
        assertEquals(Qty(3_000), Qty.parse("3", allowDecimal = false))
        assertEquals(Qty(2_500), Qty.parse("2.5", allowDecimal = true))
        assertEquals(Qty(125), Qty.parse("0.125", allowDecimal = true))
        assertEquals(Qty(2_500), Qty.parse("۲٫۵", allowDecimal = true))
    }

    @Test
    fun `rejects decimals for whole-unit items, zero, negatives and 4+ decimals`() {
        assertNull(Qty.parse("2.5", allowDecimal = false))
        assertNull(Qty.parse("0", allowDecimal = true))
        assertNull(Qty.parse("0.000", allowDecimal = true))
        assertNull(Qty.parse("-1", allowDecimal = true))
        assertNull(Qty.parse("1.2345", allowDecimal = true))
        assertNull(Qty.parse("", allowDecimal = true))
    }

    @Test
    fun `formats without trailing zeros`() {
        assertEquals("3", Qty(3_000).format())
        assertEquals("2.5", Qty(2_500).format())
        assertEquals("0.125", Qty(125).format())
        assertEquals("-2", Qty(-2_000).format())
    }

    @Test
    fun `decimal arithmetic is exact`() {
        assertEquals(Qty(300), Qty(100) + Qty(200))
    }

    @Test
    fun `price times quantity rounds half-up to the paisa`() {
        // Rs 250/kg × 1.5 kg = Rs 375
        assertEquals(Money(37_500), Money(25_000).times(Qty(1_500)))
        // Rs 0.99 × 0.5 = 49.5 paisa → 50
        assertEquals(Money(50), Money(99).times(Qty(500)))
        // Rs 0.99 × 0.499 = 49.401 paisa → 49
        assertEquals(Money(49), Money(99).times(Qty(499)))
        assertEquals(Money(4_200_000), Money(4_200_000).times(Qty.ONE))
    }
}
