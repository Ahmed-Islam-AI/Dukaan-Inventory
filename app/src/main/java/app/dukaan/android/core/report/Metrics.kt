package app.dukaan.android.core.report

import app.dukaan.android.core.db.CategoryTotal
import app.dukaan.android.core.db.ItemSalesRow
import app.dukaan.android.core.db.ModeTotal
import app.dukaan.android.core.db.PartyRow
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.SummaryRow
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import java.time.LocalDate

/** Inclusive range of shop days (yyyyMMdd ints, D-36). */
data class DayRange(val from: LocalDate, val to: LocalDate) {
    val fromDay get() = day(from)
    val toDay get() = day(to)

    companion object {
        fun day(d: LocalDate) = d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth
        fun today(today: LocalDate) = DayRange(today, today)
        fun yesterday(today: LocalDate) = today.minusDays(1).let { DayRange(it, it) }
        fun thisMonth(today: LocalDate) = DayRange(today.withDayOfMonth(1), today)
        fun lastMonth(today: LocalDate) = today.minusMonths(1).let { DayRange(it.withDayOfMonth(1), it.withDayOfMonth(it.lengthOfMonth())) }
    }
}

/**
 * The figures every screen shows (ARCHITECTURE.md § Business metrics, D-61):
 *   sales  = ACTIVE bills of these days − ACTIVE return refunds of these days
 *   cost   = cost of what was sold on these days − cost of what came back on these days
 *   profit = sales − cost; net = profit − expenses
 *   collection = payments IN − OUT of these days, per mode
 */
data class Figures(
    val sales: Money,
    val bills: Int,
    val cost: Money,
    val expenses: Money,
    val pendingLines: Int,
    val collection: Map<PaymentMode, Money>,
) {
    val grossProfit get() = sales - cost
    val netProfit get() = grossProfit - expenses
    val collected get() = collection.values.fold(Money.ZERO) { a, b -> a + b }

    companion object {
        fun of(s: SummaryRow, modes: List<ModeTotal>) = Figures(
            sales = Money(s.salesPaisa - s.refundsPaisa),
            bills = s.bills,
            cost = Money(s.costPaisa - s.costBackPaisa),
            expenses = Money(s.expensesPaisa),
            pendingLines = s.pendingLines,
            collection = PaymentMode.entries.associateWith { m -> Money(modes.filter { it.mode == m }.sumOf { it.paisa }) },
        )
    }
}

/** One CSV file with every report section; opens in Excel / Google Sheets. */
object ReportCsv {
    fun build(
        shop: String, range: DayRange, f: Figures, items: List<ItemSalesRow>,
        expenses: List<CategoryTotal>, credit: List<PartyRow>, stockValue: Money, modeLabel: (PaymentMode) -> String,
    ): String = buildString {
        fun row(vararg cells: Any) = appendLine(cells.joinToString(",") { cell(it) })
        row(shop, "${range.from} to ${range.to}")
        appendLine()
        row("Summary", "Amount")
        row("Sales", f.sales)
        row("Bills", f.bills)
        row("Cost of goods sold", f.cost)
        row("Gross profit", f.grossProfit)
        row("Expenses", f.expenses)
        row("Net profit", f.netProfit)
        PaymentMode.entries.forEach { row("Money in - out ${modeLabel(it)}", f.collection.getValue(it)) }
        row("Money in - out total", f.collected)
        row("Stock value at cost (today)", stockValue)
        if (f.pendingLines > 0) row("Lines with cost pending", f.pendingLines)
        appendLine()
        row("Item", "Quantity", "Unit", "Sales", "Cost", "Profit")
        items.forEach { row(it.name, Qty(it.qtyMilli).format(), it.unit, Money(it.salesPaisa), Money(it.costPaisa), Money(it.salesPaisa - it.costPaisa)) }
        appendLine()
        row("Expense", "Amount")
        expenses.forEach { row(it.category, Money(it.paisa)) }
        appendLine()
        row("Customer", "Phone", "Owes (today)")
        credit.forEach { row(it.name, it.phone.orEmpty(), Money(it.balancePaisa)) }
    }

    /** Plain 1234.50 (no grouping or symbol) so spreadsheets read numbers; quotes only when needed. */
    private fun cell(v: Any): String {
        val text = when (v) {
            is Money -> plain(v)
            else -> v.toString()
        }
        return if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }

    fun plain(m: Money): String {
        val abs = kotlin.math.abs(m.minor)
        return (if (m.minor < 0) "-" else "") + "${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
}
