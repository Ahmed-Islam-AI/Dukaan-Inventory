package app.dukaan.android.core.sync

import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.PaymentKind
import app.dukaan.android.core.db.SerialStatus
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.ledger.LedgerFixture.Companion.rs
import app.dukaan.android.core.ledger.LedgerFixture.Companion.units
import app.dukaan.android.core.ledger.NewItem
import app.dukaan.android.core.ledger.NewVariant
import app.dukaan.android.core.ledger.OpeningStock
import app.dukaan.android.core.ledger.PurchaseIn
import app.dukaan.android.core.ledger.PurchaseLineIn
import app.dukaan.android.core.ledger.SaleLineIn
import app.dukaan.android.core.ledger.SerialIn
import app.dukaan.android.core.ledger.VariantEdit
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two or three phones of one shop merging through the cloud log (D-75..D-77). */
class SyncLocalTest {
    private val clock = Clock()
    private val cloud = Cloud()
    private val a = Phone("A", clock)
    private val b = Phone("B", clock)

    @After fun tearDown() { a.close(); b.close() }

    private suspend fun charger(p: Phone, opening: Long, cost: Long = 200): String {
        val productId = p.ledger.createItem(
            NewItem("Charger", null, TrackingType.QUANTITY, "pcs", false, listOf(NewVariant("Default", rs(300), opening = OpeningStock.Quantity(units(opening), rs(cost)))))
        )
        return p.db.catalogDao().variants(productId).first().single().id
    }

    private suspend fun assertSame(vararg phones: Phone) {
        val first = phones.first().digest()
        phones.drop(1).forEach { assertEquals("${it.name} differs from ${phones.first().name}", first, it.digest()) }
        phones.forEach { assertEquals("${it.name} invariants", emptyList<String>(), it.violations()) }
        phones.forEach { assertEquals("${it.name} still has rows to push", 0, it.db.syncDao().waitingNow()) }
    }

    @Test fun `rows survive the codec`() {
        val rows = listOf(SyncRow("party", linkedMapOf("id" to "p1", "name" to "علی موبائل", "phone" to null, "openingBalancePaisa" to -5L, "note" to "x".repeat(70_000))))
        assertEquals(rows, RowCodec.decode(RowCodec.encode(rows)))
    }

    @Test fun `existing data seeds once and a second phone gets all of it`() = runTest {
        a.shop()
        val v = charger(a, 10)
        a.ledger.sell(a.sale(SaleLineIn(v, units(3), rs(300))))
        a.sync.seedIfNeeded()
        a.sync.seedIfNeeded() // second call queues nothing new
        cloud.syncAll(a, b)
        assertSame(a, b)
        assertEquals(7_000, b.db.ledgerDao().variant(v)!!.onHandMilli)
        // Merging queued nothing on B (no echo) and A pulled nothing back.
        assertEquals(1, cloud.changes.size)
    }

    @Test fun `offline sales of one lot on two phones merge in time order`() = runTest {
        a.shop()
        val v = charger(a, 5)
        cloud.syncAll(a, b)
        a.ledger.sell(a.sale(SaleLineIn(v, units(3), rs(300)))) // first
        b.ledger.sell(b.sale(SaleLineIn(v, units(3), rs(300)))) // later, offline: B still sees 5
        cloud.syncAll(a, b)
        assertSame(a, b)
        assertEquals(-1_000, a.db.ledgerDao().variant(v)!!.onHandMilli)
        val lines = a.db.auditDao().saleLines().sortedBy { it.meta.createdAt }
        assertEquals(listOf(0L, 1_000L), lines.map { it.pendingQtyMilli }) // B's sale waits for cost
        assertEquals(listOf(60_000L, 40_000L), lines.map { it.costPaisa })

        // A purchase on either phone fills B's pending unit everywhere.
        a.ledger.purchase(PurchaseIn(null, null, clock.now, listOf(PurchaseLineIn(v, units(10), rs(250))), Money.ZERO, PaymentMode.CASH))
        cloud.syncAll(a, b)
        assertSame(a, b)
        assertEquals(65_000L, b.db.auditDao().saleLines().maxBy { it.meta.createdAt }.costPaisa)
    }

    @Test fun `the same IMEI sold on two phones keeps both bills`() = runTest {
        a.shop()
        val productId = a.ledger.createItem(
            NewItem("Phone", null, TrackingType.SERIAL, "pcs", false, listOf(NewVariant("Default", rs(40_000), opening = OpeningStock.Serials(listOf(SerialIn("356789", rs(35_000)))))))
        )
        val v = a.db.catalogDao().variants(productId).first().single().id
        cloud.syncAll(a, b)
        val unit = a.db.auditDao().serials().single().id
        a.ledger.sell(a.sale(SaleLineIn(v, Qty.ONE, rs(40_000), unit)))
        b.ledger.sell(b.sale(SaleLineIn(v, Qty.ONE, rs(41_000), unit)))
        cloud.syncAll(a, b)
        assertEquals(a.digest(), b.digest())
        for (p in listOf(a, b)) {
            assertEquals(listOf(DocStatus.ACTIVE, DocStatus.ACTIVE), p.db.auditDao().sales().map { it.status })
            assertEquals(SerialStatus.SOLD, p.db.auditDao().serials().single().status)
            assertEquals(-1_000, p.db.ledgerDao().variant(v)!!.onHandMilli) // shows the clash in stock too
        }
    }

    @Test fun `voiding one sale on two phones counts once`() = runTest {
        a.shop()
        val v = charger(a, 10)
        val sale = a.ledger.sell(a.sale(SaleLineIn(v, units(2), rs(300)))).saleId
        cloud.syncAll(a, b)
        a.ledger.voidSale(sale, "wrong item")
        b.ledger.voidSale(sale, "customer left")
        cloud.syncAll(a, b)
        assertSame(a, b)
        assertEquals(10_000, a.db.ledgerDao().variant(v)!!.onHandMilli)
        assertEquals(1, a.db.auditDao().payments().count { it.kind == PaymentKind.REVERSAL })
    }

    @Test fun `the later edit of an item wins on every phone`() = runTest {
        a.shop()
        val v = charger(a, 1)
        cloud.syncAll(a, b)
        val productId = a.db.ledgerDao().variant(v)!!.productId
        a.ledger.updateItem(productId, "Charger", null, "pcs", listOf(VariantEdit(v, "Default", rs(310), null, null)))
        b.ledger.updateItem(productId, "Charger 20W", null, "pcs", listOf(VariantEdit(v, "Default", rs(350), null, null)))
        // A sale on A afterwards must not resurrect A's older price (derived writes don't edit).
        a.ledger.sell(a.sale(SaleLineIn(v, units(1), rs(310))))
        cloud.syncAll(a, b)
        assertSame(a, b)
        assertEquals(35_000, a.db.ledgerDao().variant(v)!!.salePricePaisa)
        assertEquals("Charger 20W", a.db.ledgerDao().product(productId)!!.name)
    }

    @Test fun `a customer typed on two phones offline is one customer`() = runTest {
        a.shop()
        val v = charger(a, 10)
        cloud.syncAll(a, b)
        a.ledger.sell(a.sale(SaleLineIn(v, units(1), rs(300)), customer = "Bilal", paid = Money.ZERO))
        b.ledger.sell(b.sale(SaleLineIn(v, units(1), rs(300)), customer = "bilal", paid = Money.ZERO))
        cloud.syncAll(a, b)
        assertSame(a, b)
        assertEquals(1, a.db.auditDao().sales().map { it.customerId }.toSet().size)
    }

    @Test fun `apply refuses nothing it can take and never echoes`() = runTest {
        a.shop()
        charger(a, 3)
        cloud.push(a)
        cloud.pull(b)
        assertTrue(b.db.syncDao().waitingNow() == 0)
        cloud.push(b)
        assertEquals(1, cloud.changes.size)
    }
}
