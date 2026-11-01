package app.dukaan.android.core.receipt

import app.dukaan.android.core.db.SlipTemplate
import app.dukaan.android.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptLayoutTest {

    private fun rs(r: Long) = Money(r * 100)

    private val base = ReceiptModel(
        shopName = "Ali Mobiles", shopAddress = "Shop 12, Hall Road, Lahore", shopPhone = "0300-1234567",
        footer = null, number = "A-0042", dateTime = "14-10-2026 18:30", customer = "Ahmed Raza",
        lines = listOf(
            ReceiptLine("Samsung A15 128GB Black", "1", rs(42_000), rs(42_000), "356789012345678"),
            ReceiptLine("Screen guard (loose)", "1.5 m", rs(250), rs(375), null),
        ),
        subtotal = rs(42_375), discount = rs(375), taxRateBp = null, tax = Money.ZERO, total = rs(42_000),
        paid = rs(38_000), paidModeLabel = "Cash", voided = false, symbol = "Rs", lakhGrouping = true,
    )

    @Test
    fun `matches the DESIGN_md receipt`() {
        val rows = ReceiptLayout.rows(base)
        assertEquals(
            listOf(
                Row.Title("Ali Mobiles"),
                Row.Center("Shop 12, Hall Road, Lahore"),
                Row.Center("0300-1234567"),
                Row.Divider,
                Row.Pair("Invoice: A-0042", "14-10-2026 18:30"),
                Row.Text("Customer: Ahmed Raza"),
                Row.Divider,
                Row.Text("Samsung A15 128GB Black"),
                Row.Pair("1 x 42,000", "42,000", indent = true),
                Row.Text("IMEI 356789012345678", indent = true, mono = true),
                Row.Text("Screen guard (loose)"),
                Row.Pair("1.5 m x 250", "375", indent = true),
                Row.Divider,
                Row.Pair("Subtotal", "42,375"),
                Row.Pair("Discount", "-375"),
                Row.Pair("TOTAL", "Rs 42,000", emphasis = true),
                Row.Pair("Paid (Cash)", "38,000"),
                Row.Pair("Balance due", "4,000", emphasis = true),
                Row.Divider,
                Row.Center("Thank you!"),
            ),
            rows,
        )
    }

    @Test
    fun `plain cash sale has no subtotal, discount or balance rows`() {
        val rows = ReceiptLayout.rows(base.copy(discount = Money.ZERO, subtotal = rs(42_375), total = rs(42_375), paid = rs(42_375), customer = null))
        assertFalse(rows.any { it is Row.Pair && it.left in setOf("Subtotal", "Discount", "Balance due") })
        assertTrue(rows.contains(Row.Pair("TOTAL", "Rs 42,375", emphasis = true)))
        assertFalse(rows.any { it is Row.Text && it.text.startsWith("Customer") })
    }

    @Test
    fun `lakh grouping on big totals, tax row with rate`() {
        val rows = ReceiptLayout.rows(
            base.copy(discount = Money.ZERO, taxRateBp = 1_750, tax = rs(22_175), subtotal = rs(1_26_715), total = rs(1_48_890), paid = rs(1_48_890))
        )
        assertTrue(rows.contains(Row.Pair("Tax 17.5%", "22,175")))
        assertTrue(rows.contains(Row.Pair("TOTAL", "Rs 1,48,890", emphasis = true)))
    }

    @Test
    fun `voided receipt says so, custom footer, Urdu names untouched`() {
        val rows = ReceiptLayout.rows(base.copy(voided = true, footer = "شکریہ", lines = listOf(ReceiptLine("چارجر", "2", rs(600), rs(1_200), null))))
        assertTrue(rows.contains(Row.Center("*** VOID ***")))
        assertTrue(rows.contains(Row.Center("شکریہ")))
        assertTrue(rows.contains(Row.Text("چارجر")))
        val partPaid = ReceiptLayout.rows(base.copy(voided = true, paid = rs(0)))
        assertTrue(partPaid.none { it is Row.Pair && it.left == "Balance due" })
    }

    @Test
    fun `every slip template shows the same money, totals and IMEIs (D-80)`() {
        for (t in SlipTemplate.entries) {
            val rows = ReceiptLayout.rows(base, t)
            val text = rows.joinToString(" | ") { it.toString() }
            assertTrue("$t total", text.contains("Rs 42,000"))
            assertTrue("$t balance", rows.any { it is Row.Pair && it.left == "Balance due" && it.right == "4,000" })
            assertTrue("$t IMEI", rows.any { it is Row.Text && it.text == "IMEI 356789012345678" })
            assertTrue("$t customer", rows.any { it is Row.Text && it.text == "Customer: Ahmed Raza" })
            assertEquals("$t footer last", Row.Center("Thank you!", small = t == SlipTemplate.COMPACT), rows.last())
        }
    }

    @Test
    fun `compact puts each item on one line, bold uses bands for name and total`() {
        val compact = ReceiptLayout.rows(base, SlipTemplate.COMPACT)
        assertTrue(compact.contains(Row.Pair("1 x Samsung A15 128GB Black", "42,000", small = true)))
        assertTrue(compact.contains(Row.Center("Shop 12, Hall Road, Lahore · 0300-1234567", small = true)))
        val bold = ReceiptLayout.rows(base, SlipTemplate.BOLD)
        assertEquals(Row.Banner("Ali Mobiles"), bold.first())
        assertTrue(bold.contains(Row.Banner("TOTAL", "Rs 42,000")))
        assertTrue(ReceiptLayout.rows(base.copy(voided = true), SlipTemplate.BOLD).contains(Row.Banner("VOID")))
    }

    @Test
    fun `logo, tax number and terms appear only when the shop has them`() {
        assertFalse(ReceiptLayout.rows(base).any { it == Row.Logo })
        for (t in SlipTemplate.entries) {
            val rows = ReceiptLayout.rows(base.copy(taxNumber = "1234567-8", terms = "7 days warranty"), t, logo = true)
            assertEquals("$t logo first", Row.Logo, rows.first())
            assertTrue("$t tax no", rows.any { it is Row.Center && it.text == "Tax no: 1234567-8" })
            assertTrue("$t terms", rows.any { it is Row.Text && it.text == "7 days warranty" })
        }
    }

    @Test
    fun `tax rate formatting`() {
        assertEquals("18%", ReceiptLayout.formatRate(1_800))
        assertEquals("17.5%", ReceiptLayout.formatRate(1_750))
        assertEquals("0.25%", ReceiptLayout.formatRate(25))
    }
}
