package app.dukaan.android.core.ledger

import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.PartyType
import app.dukaan.android.core.db.PaymentDirection
import app.dukaan.android.core.db.PaymentKind
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.SerialStatus
import app.dukaan.android.core.ledger.LedgerFixture.Companion.rs
import app.dukaan.android.core.ledger.LedgerFixture.Companion.units
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** M4: sale void, returns, return void, party payments, expenses. Invariants after every step. */
class MoneyFlowsTest {

    private lateinit var f: LedgerFixture

    @Before
    fun setUp() = runTest {
        f = LedgerFixture()
        f.shop()
    }

    @After
    fun tearDown() = f.close()

    private suspend fun line(saleId: String) = f.db.auditDao().saleLines().single { it.saleId == saleId }
    private suspend fun lots(variantId: String) = f.db.auditDao().lots().filter { it.variantId == variantId }.sortedBy { it.receivedAt }
    private suspend fun balance(name: String, type: PartyType = PartyType.CUSTOMER) =
        f.db.ledgerDao().partyBalance(f.db.ledgerDao().partyByName(type, name)!!.id)!!
    private suspend fun payments(kind: PaymentKind) = f.db.auditDao().payments().filter { it.kind == kind }

    private suspend inline fun <reified T : LedgerException> refused(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName}")
        } catch (e: LedgerException) {
            if (e !is T) throw e
        }
        f.assertInvariants()
    }

    // ---------------------------------------------------------------- sale void

    @Test
    fun `void puts stock and lots back, cancels the payment, and only once`() = runTest {
        val c = f.quantityItem("Charger", rs(1_200), OpeningStock.Quantity(units(10), rs(200)))
        val p = f.serialItem("Samsung A15", rs(42_000), "111" to rs(38_000))
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(12), rs(1_200)), SaleLineIn(p, Qty.ONE, rs(42_000), f.unitId("111"))))
        assertEquals(-2_000, f.onHand(c))

        f.ledger.voidSale(r.saleId, "wrong customer")
        assertEquals(10_000, f.onHand(c))
        assertEquals(10_000, lots(c).single().qtyRemainingMilli)
        assertEquals(1_000, f.onHand(p))
        assertEquals(SerialStatus.IN_STOCK, f.db.ledgerDao().serial(f.unitId("111"))!!.status)
        val sale = f.db.ledgerDao().sale(r.saleId)!!
        assertEquals(DocStatus.VOIDED, sale.status)
        assertNotNull(sale.voidedAt)
        val reversal = payments(PaymentKind.REVERSAL).single()
        assertEquals(PaymentDirection.OUT, reversal.direction)
        assertEquals(r.totals.total.minor, reversal.amountPaisa)
        f.assertInvariants()

        refused<LedgerException.AlreadyVoided> { f.ledger.voidSale(r.saleId, "again") }
        assertEquals(1, payments(PaymentKind.REVERSAL).size)
    }

    @Test
    fun `void is refused while a return is active, allowed after the return is voided`() = runTest {
        val c = f.quantityItem("Charger", rs(1_200), OpeningStock.Quantity(units(10), rs(200)))
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(3), rs(1_200))))
        val ret = f.ledger.returnItems(r.saleId, listOf(ReturnLineIn(line(r.saleId).id, units(1))), null, PaymentMode.CASH)
        refused<LedgerException.HasReturns> { f.ledger.voidSale(r.saleId, "x") }

        f.ledger.voidReturn(ret.returnId, "typo")
        f.assertInvariants()
        f.ledger.voidSale(r.saleId, "x")
        assertEquals(10_000, f.onHand(c))
        // Sale payment, refund and both reversals net to zero cash.
        val net = f.db.auditDao().payments().sumOf { if (it.direction == PaymentDirection.IN) it.amountPaisa else -it.amountPaisa }
        assertEquals(0, net)
        f.assertInvariants()
    }

    // ---------------------------------------------------------------- returns

    @Test
    fun `return takes pending first, then the newest allocation`() = runTest {
        val c = f.quantityItem("Charger", rs(1_200), OpeningStock.Quantity(units(5), rs(200)))
        f.ledger.purchase(f.buy(PurchaseLineIn(c, units(5), rs(250))))
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(12), rs(1_200))))
        assertEquals(2_000, line(r.saleId).pendingQtyMilli)

        f.ledger.returnItems(r.saleId, listOf(ReturnLineIn(line(r.saleId).id, units(3))), "spare", PaymentMode.CASH)
        val l = line(r.saleId)
        assertEquals(0, l.pendingQtyMilli)
        assertEquals(rs(5 * 200 + 4 * 250).minor, l.costPaisa)
        assertEquals(listOf(0L, 1_000L), lots(c).map { it.qtyRemainingMilli }) // the Rs 250 lot got 1 back
        assertEquals(rs(250).minor, f.db.auditDao().returnLines().single().costReversedPaisa)
        assertEquals(1_000, f.onHand(c))
        f.assertInvariants()

        refused<LedgerException.InvalidInput> {
            f.ledger.returnItems(r.saleId, listOf(ReturnLineIn(l.id, units(10))), null, PaymentMode.CASH)
        }
    }

    @Test
    fun `partial refunds sum exactly to the line net, with discount and tax`() = runTest {
        f.close()
        f = LedgerFixture()
        f.shop(taxRateBp = 1_700)
        val c = f.quantityItem("Cover", rs(100), OpeningStock.Quantity(units(10), rs(40)))
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(3), rs(100)), discount = rs(1)))
        assertEquals(34_983, r.totals.total.minor) // (300 − 1) × 1.17
        val id = line(r.saleId).id
        val refunds = List(3) { f.ledger.returnItems(r.saleId, listOf(ReturnLineIn(id, units(1))), null, PaymentMode.CASH).refund.minor }
        assertEquals(listOf(11_661L, 11_661L, 11_661L), refunds)
        assertEquals(r.totals.total.minor, refunds.sum())
        f.assertInvariants()
    }

    @Test
    fun `line nets split the total pro-rata with the remainder on the last line`() {
        assertEquals(listOf(3_333L, 3_333L, 3_334L), SaleMath.lineNets(listOf(100, 100, 100), 10_000))
        assertEquals(listOf(0L, 0L), SaleMath.lineNets(listOf(0, 0), 0))
        val nets = SaleMath.lineNets(listOf(12_345, 1, 999_999), 1_000_001)
        assertEquals(1_000_001L, nets.sum())
    }

    @Test
    fun `refund reduces credit first, cash only for the excess`() = runTest {
        val c = f.quantityItem("Charger", rs(1_000), OpeningStock.Quantity(units(10), rs(200)))
        // Ahmed buys 4 for Rs 4,000 and pays Rs 3,700: owes Rs 300.
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(4), rs(1_000)), paid = rs(3_700), customer = "Ahmed"))
        assertEquals(rs(300).minor, balance("Ahmed"))

        val ret = f.ledger.returnItems(r.saleId, listOf(ReturnLineIn(line(r.saleId).id, units(1))), null, PaymentMode.CASH)
        assertEquals(rs(1_000), ret.refund)
        assertEquals(rs(700), ret.cashOut)
        assertEquals(0, balance("Ahmed"))
        assertEquals(rs(700).minor, payments(PaymentKind.REFUND).single().amountPaisa)
        f.assertInvariants()

        // Voiding the return brings the Rs 300 debt back and cancels the cash refund.
        f.ledger.voidReturn(ret.returnId, "wrong item")
        assertEquals(rs(300).minor, balance("Ahmed"))
        f.assertInvariants()
    }

    @Test
    fun `return on full credit pays no cash`() = runTest {
        val c = f.quantityItem("Charger", rs(1_000), OpeningStock.Quantity(units(10), rs(200)))
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(2), rs(1_000)), paid = Money.ZERO, customer = "Bilal"))
        val ret = f.ledger.returnItems(r.saleId, listOf(ReturnLineIn(line(r.saleId).id, units(1))), null, PaymentMode.CASH)
        assertEquals(Money.ZERO, ret.cashOut)
        assertEquals(rs(1_000).minor, balance("Bilal"))
        assertEquals(0, payments(PaymentKind.REFUND).size)
        f.assertInvariants()
    }

    @Test
    fun `return void re-takes stock FIFO and goes pending when none is left`() = runTest {
        val c = f.quantityItem("Charger", rs(1_200), OpeningStock.Quantity(units(2), rs(200)))
        val r = f.ledger.sell(f.sale(SaleLineIn(c, units(2), rs(1_200))))
        val ret = f.ledger.returnItems(r.saleId, listOf(ReturnLineIn(line(r.saleId).id, units(1))), null, PaymentMode.CASH)
        // The returned charger is sold to someone else before the return is found to be wrong.
        f.ledger.sell(f.sale(SaleLineIn(c, units(1), rs(1_200))))
        f.ledger.voidReturn(ret.returnId, "mistake")
        val l = line(r.saleId)
        assertEquals(1_000, l.pendingQtyMilli)
        assertEquals(rs(200).minor, l.costPaisa)
        assertEquals(-1_000, f.onHand(c))
        f.assertInvariants()
        refused<LedgerException.AlreadyVoided> { f.ledger.voidReturn(ret.returnId, "again") }
    }

    @Test
    fun `serial return, resale blocks its void, buy-back blocks the return`() = runTest {
        val p = f.serialItem("Samsung A15", rs(42_000), "111" to rs(38_000), "222" to rs(38_000))
        val s1 = f.ledger.sell(f.sale(SaleLineIn(p, Qty.ONE, rs(42_000), f.unitId("111"))))
        val ret = f.ledger.returnItems(s1.saleId, listOf(ReturnLineIn(line(s1.saleId).id, Qty.ONE)), null, PaymentMode.CASH)
        assertEquals(0, line(s1.saleId).costPaisa)
        assertEquals(2_000, f.onHand(p))
        f.assertInvariants()

        f.ledger.sell(f.sale(SaleLineIn(p, Qty.ONE, rs(43_000), f.unitId("111"))))
        refused<LedgerException.SerialNotInStock> { f.ledger.voidReturn(ret.returnId, "x") }

        // 222 is sold, then bought back (new row in stock): the old sale can't take it back too.
        val s2 = f.ledger.sell(f.sale(SaleLineIn(p, Qty.ONE, rs(42_000), f.unitId("222"))))
        f.ledger.purchase(f.buy(PurchaseLineIn(p, serials = listOf(SerialIn("222", rs(30_000))))))
        refused<LedgerException.DuplicateSerial> {
            f.ledger.returnItems(s2.saleId, listOf(ReturnLineIn(line(s2.saleId).id, Qty.ONE)), null, PaymentMode.CASH)
        }
        refused<LedgerException.DuplicateSerial> { f.ledger.voidSale(s2.saleId, "x") }
    }

    // ---------------------------------------------------------------- parties, expenses

    @Test
    fun `party payments move balances and can be reversed once`() = runTest {
        val c = f.quantityItem("Charger", rs(1_000))
        f.ledger.sell(f.sale(SaleLineIn(c, units(5), rs(1_000)), paid = Money.ZERO, customer = "Ahmed"))
        val ahmed = f.db.ledgerDao().partyByName(PartyType.CUSTOMER, "Ahmed")!!.id
        val pay = f.ledger.recordPayment(ahmed, rs(2_000), PaymentMode.CASH, "first", f.now)
        assertEquals(rs(3_000).minor, balance("Ahmed"))

        f.ledger.voidPayment(pay, "typed wrong")
        assertEquals(rs(5_000).minor, balance("Ahmed"))
        refused<LedgerException.AlreadyVoided> { f.ledger.voidPayment(pay, "again") }

        // Supplier: purchase Rs 1,000 paid Rs 400, then a payment of Rs 600 settles it.
        f.ledger.purchase(f.buy(PurchaseLineIn(c, units(5), rs(200)), paid = rs(400)))
        assertEquals(-rs(600).minor, balance("Hall Road Traders", PartyType.SUPPLIER))
        val supplier = f.db.ledgerDao().partyByName(PartyType.SUPPLIER, "Hall Road Traders")!!.id
        f.ledger.recordPayment(supplier, rs(600), PaymentMode.BANK, null, f.now)
        assertEquals(0, balance("Hall Road Traders", PartyType.SUPPLIER))

        val opening = f.ledger.addParty(PartyType.CUSTOMER, "Old Khata", "0300", rs(1_500))
        assertEquals(rs(1_500).minor, f.db.ledgerDao().partyBalance(opening))
        refused<LedgerException.InvalidInput> { f.ledger.addParty(PartyType.CUSTOMER, "ahmed", null, Money.ZERO) }
        f.assertInvariants()
    }

    @Test
    fun `expense is added and soft-deleted`() = runTest {
        val id = f.ledger.addExpense("Rent", rs(25_000), null, f.now)
        f.ledger.deleteExpense(id)
        assertNotNull(f.db.ledgerDao().expense(id)!!.meta.deletedAt)
        refused<LedgerException.InvalidInput> { f.ledger.addExpense("Rent", Money.ZERO, null, f.now) }
    }
}
