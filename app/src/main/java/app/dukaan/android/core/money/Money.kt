package app.dukaan.android.core.money

/**
 * An amount in minor units (paisa for PKR). Never Double: 0.1 + 0.2 != 0.3 in floating point,
 * and a billing app that is off by a paisa loses the shopkeeper's trust.
 *
 * Database columns store the raw Long (`...Paisa`); this class is for arithmetic and display.
 */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {

    operator fun plus(other: Money) = Money(Math.addExact(minor, other.minor))
    operator fun minus(other: Money) = Money(Math.subtractExact(minor, other.minor))
    operator fun times(qty: Int) = Money(Math.multiplyExact(minor, qty.toLong()))

    /** Unit price × quantity in milli-units, rounded half-up to the paisa (D-39). */
    fun times(qty: Qty): Money {
        val product = Math.multiplyExact(minor, qty.milli)
        val rounded = Math.floorDiv(product + 500, 1000)
        return Money(rounded)
    }
    operator fun unaryMinus() = Money(Math.negateExact(minor))
    override fun compareTo(other: Money) = minor.compareTo(other.minor)

    val isNegative get() = minor < 0

    /**
     * "Rs 1,26,400", "1,250.50", "-775". Whole amounts drop the decimals because most shop prices
     * are whole rupees. [symbol] null = no symbol (cart lines and lists, D-43). Lakh grouping for
     * South Asian currencies (D-42). Digits are always ASCII so they stay LTR inside Urdu (D-44).
     */
    fun format(symbol: String? = "Rs", lakhGrouping: Boolean = true): String {
        val abs = kotlin.math.abs(minor) // Math.absExact needs API 33; Long.MIN_VALUE is not a real price
        val digits = (abs / 100).toString()
        val whole = if (lakhGrouping) groupLakh(digits) else groupThousands(digits)
        val fraction = abs % 100
        val number = if (fraction == 0L) whole else "$whole.${fraction.toString().padStart(2, '0')}"
        val sign = if (minor < 0) "-" else ""
        return if (symbol == null) "$sign$number" else "$sign$symbol $number"
    }

    companion object {
        val ZERO = Money(0)

        /** Currencies written with lakh/crore grouping (12,34,567). */
        val LAKH_CURRENCIES = setOf("PKR", "INR", "BDT", "NPR")

        private fun groupThousands(digits: String) =
            digits.reversed().chunked(3).joinToString(",").reversed()

        /** Last 3 digits, then groups of 2: 1234567 → 12,34,567. */
        private fun groupLakh(digits: String): String {
            if (digits.length <= 3) return digits
            val head = digits.dropLast(3)
            val tail = digits.takeLast(3)
            return head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
        }

        /**
         * Parses what a shopkeeper types: "1250", "1,250", "1250.5", "1250.50", and the same in
         * Urdu/Arabic digits ("۱۲۵۰"), which Urdu keyboards produce.
         * Returns null for anything else (negatives, 3+ decimals, letters, blank) so the field
         * can show an error instead of silently guessing.
         */
        fun parse(input: String): Money? {
            val text = normalizeNumberInput(input)
            if (!Regex("""\d{1,13}(\.\d{1,2})?""").matches(text)) return null
            val parts = text.split('.')
            val fraction = parts.getOrNull(1)?.padEnd(2, '0') ?: "00"
            return Money(parts[0].toLong() * 100 + fraction.toLong())
        }
    }
}
