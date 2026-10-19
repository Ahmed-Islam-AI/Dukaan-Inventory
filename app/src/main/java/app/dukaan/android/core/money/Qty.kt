package app.dukaan.android.core.money

/**
 * A quantity in thousandths of the item's unit (D-39): 2.5 kg = 2500, 1 phone = 1000.
 * Never Float: 0.1 kg + 0.2 kg must be exactly 0.3 kg. DB columns store the raw Long (`...Milli`).
 */
@JvmInline
value class Qty(val milli: Long) : Comparable<Qty> {

    operator fun plus(other: Qty) = Qty(Math.addExact(milli, other.milli))
    operator fun minus(other: Qty) = Qty(Math.subtractExact(milli, other.milli))
    operator fun unaryMinus() = Qty(Math.negateExact(milli))
    override fun compareTo(other: Qty) = milli.compareTo(other.milli)

    val isWhole get() = milli % 1000 == 0L

    /** "3", "2.5", "0.125", "-2". Trailing zeros dropped. ASCII digits (stay LTR in Urdu). */
    fun format(): String {
        val abs = kotlin.math.abs(milli)
        val whole = abs / 1000
        val fraction = (abs % 1000).toString().padStart(3, '0').trimEnd('0')
        val number = if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
        return if (milli < 0) "-$number" else number
    }

    companion object {
        val ZERO = Qty(0)
        val ONE = Qty(1000)

        fun units(whole: Long) = Qty(Math.multiplyExact(whole, 1000L))

        /**
         * Parses typed quantities: "3", "2.5", "0.125", Urdu/Arabic digits. Up to 3 decimals,
         * and decimals only when [allowDecimal] (item setting). Zero and negatives are rejected:
         * a sale/purchase line always moves a positive amount; adjustments pick direction separately.
         */
        fun parse(input: String, allowDecimal: Boolean): Qty? {
            val text = normalizeNumberInput(input)
            val pattern = if (allowDecimal) """\d{1,9}(\.\d{1,3})?""" else """\d{1,9}"""
            if (!Regex(pattern).matches(text)) return null
            val parts = text.split('.')
            val fraction = parts.getOrNull(1)?.padEnd(3, '0') ?: "000"
            val qty = Qty(parts[0].toLong() * 1000 + fraction.toLong())
            return qty.takeIf { it.milli > 0 }
        }
    }
}
