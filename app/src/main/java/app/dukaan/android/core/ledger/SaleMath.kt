package app.dukaan.android.core.ledger

import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty

data class Totals(
    val lineTotals: List<Money>,
    val subtotal: Money,
    val discount: Money,
    val tax: Money,
    val total: Money,
)

/**
 * Sale totals, one rule for the cart, the ledger and the receipt (D-37):
 *   line total = round(price × qty) half-up
 *   subtotal   = Σ line totals
 *   discount   = invoice-level, applied before tax, never more than the subtotal
 *   tax        = round((subtotal − discount) × rateBp / 10 000) half-up; 0 when tax is off
 *   total      = subtotal − discount + tax
 */
object SaleMath {
    fun totals(lines: List<Pair<Money, Qty>>, discount: Money, taxRateBp: Int?): Totals {
        require(!discount.isNegative) { "discount must not be negative" }
        val lineTotals = lines.map { (price, qty) -> price.times(qty) }
        val subtotal = lineTotals.fold(Money.ZERO) { a, b -> a + b }
        val appliedDiscount = minOf(discount, subtotal)
        val taxable = subtotal - appliedDiscount
        val tax = if (taxRateBp == null || taxRateBp <= 0) Money.ZERO
        else Money(Math.floorDiv(Math.multiplyExact(taxable.minor, taxRateBp.toLong()) + 5_000, 10_000))
        return Totals(lineTotals, subtotal, appliedDiscount, tax, taxable + tax)
    }

    /**
     * Each line's share of the sale total (after discount and tax), pro-rata by line total.
     * Floor for every line but the last, which takes the remainder, so Σ nets == total exactly
     * and no net goes negative.
     */
    fun lineNets(lineTotals: List<Long>, total: Long): List<Long> {
        val subtotal = lineTotals.sum()
        if (lineTotals.isEmpty()) return emptyList()
        if (subtotal == 0L) return lineTotals.map { 0L }.dropLast(1) + total
        val nets = lineTotals.dropLast(1).map { Math.floorDiv(Math.multiplyExact(total, it), subtotal) }
        return nets + (total - nets.sum())
    }

    /**
     * Cumulative rounding (D-37): what returning [nowMilli] more of a line refunds, given what
     * ACTIVE returns already took back. Partial returns always sum to exactly [lineNet].
     */
    fun refund(lineNet: Long, qtyMilli: Long, returnedBeforeMilli: Long, nowMilli: Long, refundedBefore: Long): Long {
        val soFar = returnedBeforeMilli + nowMilli
        return Math.floorDiv(Math.multiplyExact(lineNet, soFar) + qtyMilli / 2, qtyMilli) - refundedBefore
    }
}
