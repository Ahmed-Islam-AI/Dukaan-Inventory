package app.dukaan.android.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `parses what shopkeepers type`() {
        assertEquals(Money(125_000), Money.parse("1250"))
        assertEquals(Money(125_000), Money.parse(" 1,250 "))
        assertEquals(Money(125_050), Money.parse("1250.5"))
        assertEquals(Money(125_005), Money.parse("1250.05"))
        assertEquals(Money(0), Money.parse("0"))
    }

    @Test
    fun `parses digits typed on Urdu and Arabic keyboards`() {
        assertEquals(Money(125_000), Money.parse("۱۲۵۰"))     // Urdu (Extended Arabic-Indic)
        assertEquals(Money(10_000), Money.parse("١٠٠"))       // Arabic-Indic
        assertEquals(Money(125_050), Money.parse("۱۲۵۰٫۵"))   // Arabic decimal separator
        assertEquals(Money(125_050), Money.parse("۱,۲۵۰.۵۰")) // mixed separators
    }

    @Test
    fun `rejects input instead of guessing`() {
        listOf("", " ", "-5", "12.345", "abc", "1.2.3", "Rs 100", "۱۲a").forEach {
            assertNull("should reject '$it'", Money.parse(it))
        }
    }

    @Test
    fun `formats whole and fractional amounts with lakh grouping`() {
        assertEquals("Rs 1,250", Money(125_000).format())
        assertEquals("Rs 1,250.50", Money(125_050).format())
        assertEquals("Rs 0.05", Money(5).format())
        assertEquals("-Rs 200", Money(-20_000).format())
        assertEquals("Rs 1,26,400", Money(12_640_000).format())
        assertEquals("Rs 1,23,45,678", Money(1_234_567_800).format())
        assertEquals("Rs 999", Money(99_900).format())
    }

    @Test
    fun `western grouping and no symbol`() {
        assertEquals("$ 1,000,000", Money(100_000_000).format("$", lakhGrouping = false))
        assertEquals("126,400", Money(12_640_000).format(symbol = null, lakhGrouping = false))
        assertEquals("42,000", Money(4_200_000).format(symbol = null))
        assertEquals("-775", Money(-77_500).format(symbol = null))
    }

    @Test
    fun `arithmetic is exact`() {
        assertEquals(Money(30), Money(10) + Money(20))
        assertEquals(Money(-10), Money(10) - Money(20))
        assertEquals(Money(2_500), Money(250) * 10)
    }

    @Test(expected = ArithmeticException::class)
    fun `overflow throws instead of wrapping`() {
        Money(Long.MAX_VALUE) + Money(1)
    }
}
