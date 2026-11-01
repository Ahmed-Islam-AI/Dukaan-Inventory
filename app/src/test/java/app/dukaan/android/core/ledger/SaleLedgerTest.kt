package app.dukaan.android.core.ledger

import app.dukaan.android.core.db.PaymentKind
import app.dukaan.android.core.db.SerialStatus
import app.dukaan.android.core.ledger.LedgerFixture.Companion.rs
import app.dukaan.android.core.ledger.LedgerFixture.Companion.units
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** LedgerService.sell on real SQLite. Every test ends with the invariant check. */
class SaleLedgerTest {

    private lateinit var f: LedgerFixture

    @Before
    fun setUp() = runTest {
        f = LedgerFixture()
        f.shop()
    }

    @After
    fun tearDown() = f.close()

    private suspend fun lines(saleId: String) = f.db.auditDao().saleLines().filter { it.saleId == saleId }

    @Test
    fun `quantity sale takes FIFO cost across lots`() = runTest {
        val c = f.quantityItem("Charger 25W", rs(1_200), OpeningStock.Quantity(units(10), rs(200)))
        f.ledger.purchase(f.buy(PurchaseLineIn(c, units(10), rs(250))))
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(12), rs(1_200))))
        val line = lines(r.saleId).single()
        assertEquals(rs(10 * 200 + 2 * 250).minor, line.costPaisa)
        assertEquals(0, line.pendingQtyMilli)
        assertEquals(8_000, f.onHand(c))
        assertEquals(rs(14_400), r.totals.total)
        f.assertInvariants()
    }

    @Test
    fun `selling beyond stock is allowed, the rest waits for cost, a purchase fills it`() = runTest {
        val c = f.quantityItem("Charger 25W", rs(1_200), OpeningStock.Quantity(units(10), rs(200)))
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(12), rs(1_200))))
        assertEquals(-2_000, f.onHand(c))
        assertEquals(2_000, lines(r.saleId).single().pendingQtyMilli)
        assertEquals(rs(2_000).minor, lines(r.saleId).single().costPaisa)
        f.assertInvariants()

        f.ledger.purchase(f.buy(PurchaseLineIn(c, units(5), rs(250))))
        val line = lines(r.saleId).single()
        assertEquals(0, line.pendingQtyMilli)
        assertEquals(rs(2_000 + 500).minor, line.costPaisa)
        assertEquals(3_000, f.onHand(c))
        f.assertInvariants()
    }

    @Test
    fun `serial unit is sold and cannot be sold again`() = runTest {
        val p = f.serialItem("Samsung A15", rs(42_000), "111" to rs(38_000), "222" to rs(38_500))
        val unit = f.unitId("111")
        val r = f.ledger.sell(f.sale(SaleLineIn(p, Qty.ONE, rs(42_000), unit)))
        val sold = f.db.ledgerDao().serial(unit)!!
        assertEquals(SerialStatus.SOLD, sold.status)
        assertNull(sold.liveSerial)
        assertEquals(rs(38_000).minor, lines(r.saleId).single().costPaisa)
        assertEquals(1_000, f.onHand(p))
        try {
            f.ledger.sell(f.sale(SaleLineIn(p, Qty.ONE, rs(42_000), unit)))
            fail("expected SerialNotInStock")
        } catch (e: LedgerException.SerialNotInStock) {
            assertEquals("111", e.serial)
        }
        assertEquals(1, f.db.auditDao().sales().size)
        f.assertInvariants()
    }

    @Test
    fun `a failing line rolls back the whole sale`() = runTest {
        val c = f.quantityItem("Charger 25W", rs(1_200), OpeningStock.Quantity(units(10), rs(200)))
        val p = f.serialItem("Samsung A15", rs(42_000), "111" to rs(38_000))
        val unit = f.unitId("111")
        f.ledger.sell(f.sale(SaleLineIn(p, Qty.ONE, rs(42_000), unit)))
        try {
            f.ledger.sell(f.sale(SaleLineIn(c, units(3), rs(1_200)), SaleLineIn(p, Qty.ONE, rs(42_000), unit)))
            fail("expected SerialNotInStock")
        } catch (_: LedgerException.SerialNotInStock) {
        }
        assertEquals(10_000, f.onHand(c))
        assertEquals(1, f.db.auditDao().sales().size)
        f.assertInvariants()
    }

    @Test
    fun `the same unit twice in one cart is refused`() = runTest {
        val p = f.serialItem("Samsung A15", rs(42_000), "111" to rs(38_000))
        val unit = f.unitId("111")
        try {
            f.ledger.sell(f.sale(SaleLineIn(p, Qty.ONE, rs(1), unit), SaleLineIn(p, Qty.ONE, rs(1), unit)))
            fail("expected InvalidInput")
        } catch (e: LedgerException.InvalidInput) {
            assertEquals("serials", e.field)
        }
        f.assertInvariants()
    }

    @Test
    fun `invoice numbers count up per prefix, prefixes are independent`() = runTest {
        val c = f.quantityItem("Cable", rs(300))
        val one = SaleLineIn(c, units(1), rs(300))
        assertEquals("A-0001", f.ledger.sell(f.sale(one)).number)
        assertEquals("A-0002", f.ledger.sell(f.sale(one)).number)
        assertEquals("B-0001", f.ledger.sell(f.sale(one, prefix = "B")).number)
        assertEquals("A-0003", f.ledger.sell(f.sale(one, prefix = "a")).number)
        f.assertInvariants()
    }

    @Test
    fun `credit sale needs a customer and records only the paid part`() = runTest {
        val c = f.quantityItem("Cable", rs(300))
        try {
            f.ledger.sell(f.sale(SaleLineIn(c, units(10), rs(300)), paid = rs(1_000)))
            fail("expected InvalidInput")
        } catch (e: LedgerException.InvalidInput) {
            assertEquals("customer", e.field)
        }
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(10), rs(300)), paid = rs(1_000), customer = "Ahmed Raza"))
        val sale = f.db.ledgerDao().sale(r.saleId)!!
        val customer = f.db.ledgerDao().partyByName(app.dukaan.android.core.db.PartyType.CUSTOMER, "ahmed raza")!!
        assertEquals(customer.id, sale.customerId)
        assertEquals(rs(3_000).minor, sale.totalPaisa)
        val salePayments = paymentsOf(r.saleId)
        assertEquals(listOf(rs(1_000).minor), salePayments.map { it.amountPaisa })
        assertEquals(PaymentKind.SALE, salePayments.single().kind)
        assertEquals(customer.id, salePayments.single().partyId)
        f.assertInvariants()
    }

    private suspend fun paymentsOf(saleId: String) = f.db.saleDao().payments(saleId)

    @Test
    fun `fully unpaid credit sale writes no payment`() = runTest {
        val c = f.quantityItem("Cable", rs(300))
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(1), rs(300)), paid = Money.ZERO, customer = "Bilal"))
        assertEquals(emptyList<Any>(), paymentsOf(r.saleId))
        f.assertInvariants()
    }

    @Test
    fun `paying more than the total is refused`() = runTest {
        val c = f.quantityItem("Cable", rs(300))
        try {
            f.ledger.sell(f.sale(SaleLineIn(c, units(1), rs(300)), paid = rs(301)))
            fail("expected InvalidInput")
        } catch (e: LedgerException.InvalidInput) {
            assertEquals("paid", e.field)
        }
        f.assertInvariants()
    }

    @Test
    fun `sale lines keep their name and price after the item is edited`() = runTest {
        val c = f.quantityItem("Cable", rs(300))
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(1), rs(300))))
        val product = f.db.ledgerDao().variant(c)!!.productId
        f.ledger.updateItem(product, "USB-C Cable 1m", null, "pcs", listOf(VariantEdit(c, "Default", rs(350), null, null)))
        val line = lines(r.saleId).single()
        assertEquals("Cable", line.nameSnapshot)
        assertEquals(rs(300).minor, line.unitPricePaisa)
    }

    @Test
    fun `tax rate is snapshotted on the sale`() = runTest {
        val tf = LedgerFixture()
        try {
            tf.shop(taxRateBp = 1_800)
            val c = tf.quantityItem("Cable", rs(1_000))
            val r = tf.ledger.sell(tf.sale(SaleLineIn(c, units(1), rs(1_000)), discount = rs(100)))
            val sale = tf.db.ledgerDao().sale(r.saleId)!!
            assertEquals(1_800, sale.taxRateBp)
            assertEquals(rs(162).minor, sale.taxPaisa)
            assertEquals(rs(1_062).minor, sale.totalPaisa)
        } finally {
            tf.close()
        }
    }

    @Test
    fun `decimal quantity sale`() = runTest {
        val rice = f.quantityItem("Basmati rice", rs(250), OpeningStock.Quantity(Qty(10_000), rs(210)), decimal = true, unit = "kg")
        val r = f.ledger.sell(f.sale(SaleLineIn(rice, Qty(1_500), rs(250))))
        assertEquals(rs(375), r.totals.total)
        assertEquals(8_500, f.onHand(rice))
        assertEquals(rs(315).minor, lines(r.saleId).single().costPaisa)
        f.assertInvariants()
    }

    @Test
    fun `whole-unit item refuses a decimal quantity`() = runTest {
        val c = f.quantityItem("Cable", rs(300))
        try {
            f.ledger.sell(f.sale(SaleLineIn(c, Qty(2_500), rs(300))))
            fail("expected InvalidInput")
        } catch (e: LedgerException.InvalidInput) {
            assertEquals("qty", e.field)
        }
    }

    @Test
    fun `sold phone bought back, then sold again`() = runTest {
        val p = f.serialItem("Samsung A15", rs(42_000), "444" to rs(38_000))
        f.ledger.sell(f.sale(SaleLineIn(p, Qty.ONE, rs(42_000), f.unitId("444"))))
        f.ledger.purchase(f.buy(PurchaseLineIn(p, serials = listOf(SerialIn("444", rs(30_000))))))
        val again = f.ledger.sell(f.sale(SaleLineIn(p, Qty.ONE, rs(36_000), f.unitId("444"))))
        assertEquals(rs(30_000).minor, lines(again.saleId).single().costPaisa)
        assertEquals(2, f.db.auditDao().serials().count { it.serial == "444" && it.status == SerialStatus.SOLD })
        f.assertInvariants()
    }

    @Test
    fun `sales list shows newest first with the invoice number`() = runTest {
        val c = f.quantityItem("Cable", rs(300))
        f.ledger.sell(f.sale(SaleLineIn(c, units(1), rs(300))))
        f.ledger.sell(f.sale(SaleLineIn(c, units(2), rs(300)), paid = Money.ZERO, customer = "Bilal"))
        val rows = f.db.saleDao().sales().first()
        assertEquals(listOf(2, 1), rows.map { it.invoiceSeq })
        assertEquals("Bilal", rows.first().customerName)
        assertEquals(rs(600).minor, rows.first().totalPaisa)
        assertEquals(0L, rows.first().paidPaisa)
    }
}
