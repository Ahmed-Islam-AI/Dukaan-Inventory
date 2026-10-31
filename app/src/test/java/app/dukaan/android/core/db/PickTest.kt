package app.dukaan.android.core.db

import app.dukaan.android.core.ledger.LedgerFixture
import app.dukaan.android.core.ledger.LedgerFixture.Companion.rs
import app.dukaan.android.core.ledger.LedgerFixture.Companion.units
import app.dukaan.android.core.ledger.NewItem
import app.dukaan.android.core.ledger.NewVariant
import app.dukaan.android.core.ledger.OpeningStock
import app.dukaan.android.core.ledger.PurchaseLineIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Category → item picker and the item's two prices (D-85). */
class PickTest {
    private val f = LedgerFixture()
    private val picks get() = f.db.pickDao()

    @After fun tearDown() = f.close()

    private suspend fun item(name: String, category: String?, buy: Long, sell: Long, barcode: String? = null) =
        f.ledger.createItem(NewItem(name, category, TrackingType.QUANTITY, "pcs", false, listOf(NewVariant("Default", rs(sell), barcode = barcode, purchasePrice = rs(buy)))))

    @Test
    fun `categories count their items and items without one wait in Other`() = runTest {
        f.shop()
        item("Charger 25W", "Accessories", 900, 1_200)
        item("Cable", "Accessories", 150, 250)
        item("Old item", null, 10, 20)
        f.ledger.addCategory("Phones") // empty for now
        val cats = picks.categories().first()
        assertEquals(listOf("Accessories" to 2, "Phones" to 0, null to 1), cats.map { it.name to it.items })
        assertEquals(listOf("Old item"), picks.inCategory(null).first().map { it.name })
        val accessories = cats.first().id
        assertEquals(listOf("Cable", "Charger 25W"), picks.inCategory(accessories).first().map { it.name })
    }

    @Test
    fun `a scanned barcode finds its item first, with both prices`() = runTest {
        f.shop()
        item("Coke 500ml", "Drinks", 80, 100, barcode = "8964000123456")
        item("Coke 1.5L", "Drinks", 180, 220)
        val hit = picks.search("8964000123456").first().single()
        assertEquals("Coke 500ml", hit.name)
        assertEquals(8_000L, hit.purchasePricePaisa)
        assertEquals(10_000L, hit.salePricePaisa)
    }

    @Test
    fun `add stock pre-fills the usual purchase price and a new price becomes the usual one`() = runTest {
        f.shop()
        val productId = f.ledger.createItem(
            NewItem("Rice", "Grocery", TrackingType.QUANTITY, "kg", true, listOf(NewVariant("Default", rs(300), opening = OpeningStock.Quantity(units(10), rs(250)))))
        )
        val v = f.variantOf(productId)
        // No purchase price typed: the opening stock's cost is the usual one.
        assertEquals(25_000L, picks.forProduct(productId).single().purchasePricePaisa)
        f.ledger.purchase(f.buy(PurchaseLineIn(v, units(5), rs(270))))
        assertEquals(27_000L, f.db.ledgerDao().variant(v)!!.purchasePricePaisa)
        // Profit still uses what each batch cost: FIFO sells the Rs 250 rice first.
        f.ledger.sell(f.sale(app.dukaan.android.core.ledger.SaleLineIn(v, units(10), rs(300))))
        assertEquals(250_000L, f.db.auditDao().saleLines().single().costPaisa)
        f.assertInvariants()
    }
}
