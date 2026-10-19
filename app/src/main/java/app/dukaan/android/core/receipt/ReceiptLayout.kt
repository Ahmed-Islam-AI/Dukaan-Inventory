package app.dukaan.android.core.receipt

import app.dukaan.android.core.db.SlipTemplate
import app.dukaan.android.core.money.Money

/** Everything printed on a receipt, already resolved from the database (ReceiptRepository). */
data class ReceiptModel(
    val shopName: String,
    val shopAddress: String?,
    val shopPhone: String?,
    val footer: String?,
    val number: String,
    val dateTime: String,
    val customer: String?,
    val lines: List<ReceiptLine>,
    val subtotal: Money,
    val discount: Money,
    val taxRateBp: Int?,
    val tax: Money,
    val total: Money,
    val paid: Money,
    val paidModeLabel: String?,
    val voided: Boolean,
    val symbol: String,
    val lakhGrouping: Boolean,
    /** NTN / STRN line under the address (D-80). */
    val taxNumber: String? = null,
    /** Terms / warranty text above the footer (D-80). */
    val terms: String? = null,
    /** "IMEI" for phone shops, "S/N" for others (D-85). */
    val serialLabel: String = "IMEI",
) {
    val balance: Money get() = if (voided) Money.ZERO else (total - paid).let { if (it.minor > 0) it else Money.ZERO }
    fun money(x: Money, symbol: Boolean = false) = x.format(if (symbol) this.symbol else null, lakhGrouping)
    val footerText: String get() = footer?.takeIf { it.isNotBlank() } ?: "Thank you!"
}

/** Phone shops call a unit's serial an IMEI; every other shop a serial number (D-85). */
fun serialLabelFor(shopType: String?) = if (shopType == "mobiles") "IMEI" else "S/N"

data class ReceiptLine(val name: String, val qty: String, val unitPrice: Money, val total: Money, val serial: String?)

/** Layout rows, drawn top to bottom by ReceiptRenderer at any paper width. */
sealed interface Row {
    data class Title(val text: String) : Row
    data class Center(val text: String, val small: Boolean = false) : Row
    data class Text(val text: String, val indent: Boolean = false, val mono: Boolean = false, val small: Boolean = false) : Row
    data class Pair(val left: String, val right: String, val emphasis: Boolean = false, val indent: Boolean = false, val small: Boolean = false) : Row
    /** Dashed line. */
    data object Divider : Row
    /** Solid heavy line. */
    data object Rule : Row
    /** White text on a black band (Bold slip). */
    data class Banner(val left: String, val right: String? = null) : Row
    /** The shop's logo, centred (drawn only when the shop has one). */
    data object Logo : Row
}

/**
 * Printer slips (docs/DESIGN.md § Receipt layout, D-80). Labels are English (D-08); item and
 * customer names print as typed (Urdu fine, D-21). Amounts use the shop's grouping (lakh, D-42).
 *
 *   CLASSIC  the original slip: centred header, dashed lines, item then "qty x price" under it
 *   COMPACT  one line per item, smaller text: saves paper on long bills
 *   BOLD     black name band, column heads, total in a black band
 */
object ReceiptLayout {
    fun rows(m: ReceiptModel, template: SlipTemplate = SlipTemplate.CLASSIC, logo: Boolean = false): List<Row> = when (template) {
        SlipTemplate.CLASSIC -> classic(m, logo)
        SlipTemplate.COMPACT -> compact(m, logo)
        SlipTemplate.BOLD -> bold(m, logo)
    }

    private fun classic(m: ReceiptModel, logo: Boolean) = buildList {
        if (logo) add(Row.Logo)
        add(Row.Title(m.shopName))
        m.shopAddress?.takeIf { it.isNotBlank() }?.let { add(Row.Center(it)) }
        m.shopPhone?.takeIf { it.isNotBlank() }?.let { add(Row.Center(it)) }
        m.taxNumber?.takeIf { it.isNotBlank() }?.let { add(Row.Center("Tax no: $it")) }
        if (m.voided) add(Row.Center("*** VOID ***"))
        add(Row.Divider)
        add(Row.Pair("Invoice: ${m.number}", m.dateTime))
        m.customer?.takeIf { it.isNotBlank() }?.let { add(Row.Text("Customer: $it")) }
        add(Row.Divider)
        m.lines.forEach { line ->
            add(Row.Text(line.name))
            add(Row.Pair("${line.qty} x ${m.money(line.unitPrice)}", m.money(line.total), indent = true))
            line.serial?.let { add(Row.Text("${m.serialLabel} $it", indent = true, mono = true)) }
        }
        add(Row.Divider)
        totals(m, small = false)
        add(Row.Divider)
        m.terms?.takeIf { it.isNotBlank() }?.let { add(Row.Text(it, small = true)); add(Row.Divider) }
        add(Row.Center(m.footerText))
    }

    private fun compact(m: ReceiptModel, logo: Boolean) = buildList {
        if (logo) add(Row.Logo)
        add(Row.Title(m.shopName))
        listOfNotNull(m.shopAddress, m.shopPhone).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { add(Row.Center(it.joinToString(" · "), small = true)) }
        m.taxNumber?.takeIf { it.isNotBlank() }?.let { add(Row.Center("Tax no: $it", small = true)) }
        if (m.voided) add(Row.Center("*** VOID ***"))
        add(Row.Divider)
        add(Row.Pair(m.number, m.dateTime, small = true))
        m.customer?.takeIf { it.isNotBlank() }?.let { add(Row.Text("Customer: $it", small = true)) }
        add(Row.Divider)
        m.lines.forEach { line ->
            add(Row.Pair("${line.qty} x ${line.name}", m.money(line.total), small = true))
            line.serial?.let { add(Row.Text("${m.serialLabel} $it", indent = true, mono = true, small = true)) }
        }
        add(Row.Divider)
        totals(m, small = true)
        m.terms?.takeIf { it.isNotBlank() }?.let { add(Row.Divider); add(Row.Text(it, small = true)) }
        add(Row.Center(m.footerText, small = true))
    }

    private fun bold(m: ReceiptModel, logo: Boolean) = buildList {
        if (logo) add(Row.Logo)
        add(Row.Banner(m.shopName))
        m.shopAddress?.takeIf { it.isNotBlank() }?.let { add(Row.Center(it)) }
        m.shopPhone?.takeIf { it.isNotBlank() }?.let { add(Row.Center(it)) }
        m.taxNumber?.takeIf { it.isNotBlank() }?.let { add(Row.Center("Tax no: $it")) }
        if (m.voided) add(Row.Banner("VOID"))
        add(Row.Rule)
        add(Row.Pair("INVOICE ${m.number}", m.dateTime, emphasis = true))
        m.customer?.takeIf { it.isNotBlank() }?.let { add(Row.Text("Customer: $it")) }
        add(Row.Rule)
        add(Row.Pair("ITEM", "AMOUNT", emphasis = true, small = true))
        add(Row.Divider)
        m.lines.forEach { line ->
            add(Row.Text(line.name))
            add(Row.Pair("${line.qty} x ${m.money(line.unitPrice)}", m.money(line.total), indent = true))
            line.serial?.let { add(Row.Text("${m.serialLabel} $it", indent = true, mono = true)) }
        }
        add(Row.Rule)
        if (m.discount.minor > 0 || m.tax.minor > 0) add(Row.Pair("Subtotal", m.money(m.subtotal)))
        if (m.discount.minor > 0) add(Row.Pair("Discount", m.money(-m.discount)))
        if (m.tax.minor > 0) add(Row.Pair("Tax ${formatRate(m.taxRateBp)}", m.money(m.tax)))
        add(Row.Banner("TOTAL", m.money(m.total, symbol = true)))
        if (m.paid.minor > 0) add(Row.Pair(paidLabel(m), m.money(m.paid)))
        if (m.balance.minor > 0) add(Row.Pair("Balance due", m.money(m.balance), emphasis = true))
        add(Row.Rule)
        m.terms?.takeIf { it.isNotBlank() }?.let { add(Row.Text(it, small = true)); add(Row.Divider) }
        add(Row.Center(m.footerText))
    }

    private fun MutableList<Row>.totals(m: ReceiptModel, small: Boolean) {
        if (m.discount.minor > 0 || m.tax.minor > 0) add(Row.Pair("Subtotal", m.money(m.subtotal), small = small))
        if (m.discount.minor > 0) add(Row.Pair("Discount", m.money(-m.discount), small = small))
        if (m.tax.minor > 0) add(Row.Pair("Tax ${formatRate(m.taxRateBp)}", m.money(m.tax), small = small))
        add(Row.Pair("TOTAL", m.money(m.total, symbol = true), emphasis = true))
        if (m.paid.minor > 0) add(Row.Pair(paidLabel(m), m.money(m.paid), small = small))
        // A voided bill owes nothing, whatever was paid.
        if (m.balance.minor > 0) add(Row.Pair("Balance due", m.money(m.balance), emphasis = true))
    }

    fun paidLabel(m: ReceiptModel) = m.paidModeLabel?.let { "Paid ($it)" } ?: "Paid"

    /** 1800 → "18%", 1750 → "17.5%". */
    fun formatRate(bp: Int?): String {
        if (bp == null) return ""
        val whole = bp / 100
        val frac = (bp % 100).toString().padStart(2, '0').trimEnd('0')
        return if (frac.isEmpty()) "$whole%" else "$whole.$frac%"
    }
}
