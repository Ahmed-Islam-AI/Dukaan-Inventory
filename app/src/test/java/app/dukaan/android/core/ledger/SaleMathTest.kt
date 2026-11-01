package app.dukaan.android.core.ledger

import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import org.junit.Assert.assertEquals
import org.junit.Test

class SaleMathTest {

    private fun rs(r: Long) = Money(r * 100)

    @Test
    fun `phone plus chargers plus decimal item`() {
        val t = SaleMath.totals(
            listOf(rs(42_000) to Qty.units(1), rs(1_200) to Qty.units(2), rs(250) to Qty(1_500)),
            discount = rs(775), taxRateBp = null,
        )
        assertEquals(listOf(rs(42_000), rs(2_400), rs(375)), t.lineTotals)
        assertEquals(rs(44_775), t.subtotal)
        assertEquals(Money.ZERO, t.tax)
        assertEquals(rs(44_000), t.total)
    }

    @Test
    fun `tax applies after discount and rounds half-up`() {
        // (1000 − 100) × 18% = 162.00
        val t = SaleMath.totals(listOf(rs(1_000) to Qty.ONE), rs(100), 1_800)
        assertEquals(rs(162), t.tax)
        assertEquals(rs(1_062), t.total)
        // 0.05 × 10% = 0.005 → 0.01 (half-up)
        assertEquals(Money(1), SaleMath.totals(listOf(Money(5) to Qty.ONE), Money.ZERO, 1_000).tax)
    }

    @Test
    fun `discount larger than subtotal is capped, total never negative`() {
        val t = SaleMath.totals(listOf(rs(500) to Qty.ONE), rs(900), null)
        assertEquals(rs(500), t.discount)
        assertEquals(Money.ZERO, t.total)
    }

    @Test
    fun `empty cart totals zero`() {
        assertEquals(Money.ZERO, SaleMath.totals(emptyList(), Money.ZERO, 1_800).total)
    }

    @Test
    fun `price times decimal quantity rounds per line`() {
        // Rs 0.99 × 0.5 = 49.5 paisa → 50; three such lines = 150, not round(148.5)
        val t = SaleMath.totals(List(3) { Money(99) to Qty(500) }, Money.ZERO, null)
        assertEquals(Money(150), t.subtotal)
    }
}
