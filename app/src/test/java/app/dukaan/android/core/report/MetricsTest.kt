package app.dukaan.android.core.report

import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.ledger.LedgerFixture
import app.dukaan.android.core.ledger.LedgerFixture.Companion.rs
import app.dukaan.android.core.ledger.LedgerFixture.Companion.units
import app.dukaan.android.core.ledger.OpeningStock
import app.dukaan.android.core.ledger.ReturnLineIn
import app.dukaan.android.core.ledger.SaleLineIn
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.time.businessDay
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ARCHITECTURE.md § Business metrics on real ledger data, across two shop days (D-29, D-61). */
class MetricsTest {

    private lateinit var f: LedgerFixture
    private val zone = ZoneId.of("Asia/Karachi")
    private val dayMs = 86_400_000L

    @Before
    fun setUp() = runTest {
        f = LedgerFixture()
        f.shop()
    }

    @After
    fun tearDown() = f.close()

    private suspend fun figures(from: Int, to: Int): Figures {
        val r = f.db.reportDao()
        return Figures.of(r.summary(from, to).first(), r.collection(from, to).first())
    }

    @Test
    fun `sales, cost, profit and collection by day with a void and a return`() = runTest {
        val c = f.quantityItem("Charger", rs(1_000), OpeningStock.Quantity(units(10), rs(200)))
        val day1 = businessDay(f.now, zone)
        val cash = f.ledger.sell(f.sale(SaleLineIn(c, units(4), rs(1_000))))
        val credit = f.ledger.sell(f.sale(SaleLineIn(c, units(2), rs(1_000)), paid = Money.ZERO, customer = "Ahmed"))
        f.ledger.addExpense("Tea", rs(500), null, f.now)

        f.now += dayMs
        val day2 = businessDay(f.now, zone)
        val line = f.db.auditDao().saleLines().single { it.saleId == cash.saleId }
        f.ledger.returnItems(cash.saleId, listOf(ReturnLineIn(line.id, units(1))), null, PaymentMode.CASH)
        f.ledger.voidSale(credit.saleId, "wrong customer") // a voided bill leaves its day (D-61)

        val d1 = figures(day1, day1)
        assertEquals(rs(4_000), d1.sales)
        assertEquals(1, d1.bills)
        assertEquals(rs(800), d1.cost) // cost of what was sold that day, before the return
        assertEquals(rs(500), d1.expenses)
        assertEquals(rs(4_000), d1.collection.getValue(PaymentMode.CASH))

        val d2 = figures(day2, day2)
        assertEquals(rs(-1_000), d2.sales) // the refund lands on the return day
        assertEquals(rs(-200), d2.cost)
        assertEquals(rs(-1_000), d2.collected)

        val both = figures(day1, day2)
        assertEquals(rs(3_000), both.sales)
        assertEquals(rs(2_400), both.grossProfit)
        assertEquals(rs(1_900), both.netProfit)

        val items = f.db.reportDao().itemSales(day1, day2).first().single()
        assertEquals(units(3).milli, items.qtyMilli)
        assertEquals(rs(3_000).minor, items.salesPaisa)
        assertEquals(rs(600).minor, items.costPaisa)
        f.assertInvariants()
    }

    @Test
    fun `cost pending lines are counted until a purchase fills them`() = runTest {
        val c = f.quantityItem("Cover", rs(300))
        val day = businessDay(f.now, zone)
        f.ledger.sell(f.sale(SaleLineIn(c, units(2), rs(300))))
        assertEquals(1, figures(day, day).pendingLines)
        assertEquals(1, f.db.reportDao().pendingLines().first())
        f.ledger.purchase(f.buy(app.dukaan.android.core.ledger.PurchaseLineIn(c, units(5), rs(100))))
        assertEquals(0, f.db.reportDao().pendingLines().first())
        assertEquals(rs(200), figures(day, day).cost)
    }

    @Test
    fun `deleted expenses do not count`() = runTest {
        val day = businessDay(f.now, zone)
        val id = f.ledger.addExpense("Rent", rs(25_000), null, f.now)
        f.ledger.addExpense("Tea", rs(300), null, f.now)
        f.ledger.deleteExpense(id)
        assertEquals(rs(300), figures(day, day).expenses)
        assertEquals(listOf("Tea"), f.db.reportDao().expensesByCategory(day, day).first().map { it.category })
    }

    @Test
    fun `date ranges`() {
        val d = LocalDate.of(2026, 3, 15)
        assertEquals(DayRange(LocalDate.of(2026, 3, 1), d), DayRange.thisMonth(d))
        assertEquals(DayRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)), DayRange.lastMonth(d))
        assertEquals(20260314, DayRange.yesterday(d).fromDay)
    }

    @Test
    fun `csv quotes names with commas and writes plain numbers`() {
        assertEquals("-1234.05", ReportCsv.plain(Money(-123_405)))
        val csv = ReportCsv.build(
            "Ali, Mobiles", DayRange.today(LocalDate.of(2026, 3, 15)),
            Figures(rs(1_000), 1, rs(400), Money.ZERO, 0, PaymentMode.entries.associateWith { Money.ZERO }),
            emptyList(), emptyList(), emptyList(), rs(5_000),
        ) { it.name }
        assertTrue(csv.startsWith("\"Ali, Mobiles\",2026-03-15 to 2026-03-15"))
        assertTrue(csv.contains("Gross profit,600.00"))
    }
}
