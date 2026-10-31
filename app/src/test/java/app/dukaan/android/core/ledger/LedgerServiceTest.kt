package app.dukaan.android.core.ledger

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.DukaanDatabase
import app.dukaan.android.core.db.MovementType
import app.dukaan.android.core.db.PaymentKind
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.SaleEntity
import app.dukaan.android.core.db.SaleLineEntity
import app.dukaan.android.core.db.SerialStatus
import app.dukaan.android.core.db.StockMovementEntity
import app.dukaan.android.core.db.SyncMeta
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * LedgerService on real SQLite (bundled driver, JVM). Every test ends with [assertInvariants]:
 * the ledger must be consistent after every operation, including failed ones.
 *
 * Fixture used by most tests:
 *   "Charger 25W"  QUANTITY, whole units
 *   "Basmati rice" QUANTITY, decimals (kg)
 *   "Samsung A15"  SERIAL (IMEI)
 */
class LedgerServiceTest {

    private lateinit var db: DukaanDatabase
    private lateinit var ledger: LedgerService
    private var ids = 0
    private var now = 1_760_000_000_000L // fixed clock, advanced per call

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<DukaanDatabase>().setDriver(BundledSQLiteDriver()).build()
        ledger = LedgerService(
            db = db,
            deviceId = { "dev-A" },
            zone = { ZoneId.of("Asia/Karachi") },
            clock = { now++ },
            newId = { "id-${++ids}" },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun assertInvariants() {
        val v = InvariantChecker(db.auditDao()).violations()
        assertTrue("invariants broken:\n" + v.joinToString("\n"), v.isEmpty())
    }

    private suspend fun onHand(variantId: String) = db.ledgerDao().variant(variantId)!!.onHandMilli

    private suspend fun variantOf(productId: String) = db.catalogDao().variants(productId).first().single().id

    private suspend fun charger(opening: OpeningStock? = null): String = variantOf(
        ledger.createItem(
            NewItem("Charger 25W", "Chargers", TrackingType.QUANTITY, "pcs", false,
                listOf(NewVariant("Default", Money(120_000), opening = opening)))
        )
    )

    private suspend fun phone(opening: OpeningStock? = null): String = variantOf(
        ledger.createItem(
            NewItem("Samsung A15", "Phones", TrackingType.SERIAL, "pcs", false,
                listOf(NewVariant("128GB Black", Money(4_200_000), opening = opening)))
        )
    )

    private fun buy(vararg lines: PurchaseLineIn, paid: Money = Money.ZERO, supplier: String? = "Hall Road Traders") =
        PurchaseIn(supplier, "B-77", now, lines.toList(), paid, PaymentMode.CASH)

    // ------------------------------------------------------------------ opening stock

    @Test
    fun `item with opening quantity creates an OPENING lot and stock`() = runTest {
        val v = charger(OpeningStock.Quantity(Qty.units(10), Money(80_000)))
        assertEquals(10_000, onHand(v))
        val lot = db.ledgerDao().openLots(v).single()
        assertEquals(80_000, lot.unitCostPaisa)
        assertInvariants()
    }

    @Test
    fun `item with opening IMEIs creates one unit row each`() = runTest {
        val v = phone(OpeningStock.Serials(listOf(SerialIn("356789012345678", Money(3_800_000)), SerialIn("356789012345679", Money(3_850_000)))))
        assertEquals(2_000, onHand(v))
        assertInvariants()
    }

    @Test
    fun `category is found case-insensitively, not duplicated`() = runTest {
        charger()
        ledger.createItem(NewItem("Cable", "chargers", TrackingType.QUANTITY, "pcs", false, listOf(NewVariant("Default", Money(30_000)))))
        assertEquals(listOf("Chargers"), db.catalogDao().categoryNames().first())
    }

    @Test
    fun `decimal opening stock is refused for a whole-unit item`() = runTest {
        try {
            charger(OpeningStock.Quantity(Qty(2_500), Money(80_000)))
            fail("expected InvalidInput")
        } catch (e: LedgerException.InvalidInput) {
            assertEquals("qty", e.field)
        }
        assertEquals(emptyList<Any>(), db.catalogDao().items("").first())
        assertInvariants()
    }

    // ------------------------------------------------------------------ purchase

    @Test
    fun `purchase creates lots, serial units, movements and a payment`() = runTest {
        val c = charger()
        val p = phone()
        val purchaseId = ledger.purchase(
            buy(
                PurchaseLineIn(c, Qty.units(10), Money(80_000)),
                PurchaseLineIn(p, serials = listOf(SerialIn("111", Money(3_800_000)), SerialIn("222", Money(3_900_000)))),
                paid = Money(5_000_000),
            )
        )
        assertEquals(10_000, onHand(c))
        assertEquals(2_000, onHand(p))
        val purchase = db.ledgerDao().purchase(purchaseId)!!
        assertEquals(800_000 + 7_700_000L, purchase.totalPaisa)
        assertEquals(20251009, purchase.businessDay) // fixed clock 2025-10-09 in Asia/Karachi
        val pay = db.ledgerDao().purchasePayments(purchaseId).single()
        assertEquals(PaymentKind.PURCHASE, pay.kind)
        assertEquals(5_000_000, pay.amountPaisa)
        assertEquals(listOf("Hall Road Traders"), db.catalogDao().supplierNames().first())
        assertInvariants()
    }

    @Test
    fun `decimal purchase on a kg item`() = runTest {
        val rice = variantOf(
            ledger.createItem(NewItem("Basmati rice", null, TrackingType.QUANTITY, "kg", true, listOf(NewVariant("Default", Money(25_000)))))
        )
        ledger.purchase(buy(PurchaseLineIn(rice, Qty(12_500), Money(21_000))))
        assertEquals(12_500, onHand(rice))
        assertInvariants()
    }

    @Test
    fun `paying more than the total is refused and nothing is written`() = runTest {
        val c = charger()
        try {
            ledger.purchase(buy(PurchaseLineIn(c, Qty.units(1), Money(80_000)), paid = Money(90_000)))
            fail("expected InvalidInput")
        } catch (e: LedgerException.InvalidInput) {
            assertEquals("paid", e.field)
        }
        assertEquals(0, onHand(c))
        assertEquals(emptyList<Any>(), db.catalogDao().purchases().first())
        assertInvariants()
    }

    // ------------------------------------------------------------------ IMEIs

    @Test
    fun `an IMEI already in stock is rejected and nothing is written`() = runTest {
        val p = phone(OpeningStock.Serials(listOf(SerialIn("111", Money(3_800_000)))))
        try {
            ledger.purchase(buy(PurchaseLineIn(p, serials = listOf(SerialIn("222", Money(1)), SerialIn("111", Money(1))))))
            fail("expected DuplicateSerial")
        } catch (e: LedgerException.DuplicateSerial) {
            assertEquals(listOf("111"), e.serials)
        }
        assertEquals(1_000, onHand(p))
        assertEquals(emptyList<Any>(), db.catalogDao().purchases().first())
        assertInvariants()
    }

    @Test
    fun `the same IMEI twice in one purchase is rejected`() = runTest {
        val p = phone()
        try {
            ledger.purchase(buy(PurchaseLineIn(p, serials = listOf(SerialIn("333", Money(1)), SerialIn("333", Money(1))))))
            fail("expected DuplicateSerial")
        } catch (e: LedgerException.DuplicateSerial) {
            assertEquals(listOf("333"), e.serials)
        }
        assertInvariants()
    }

    @Test
    fun `buy-back of a sold IMEI is a new row and the old row stays SOLD`() = runTest {
        val p = phone(OpeningStock.Serials(listOf(SerialIn("444", Money(3_800_000)))))
        // Sell that unit: SOLD, liveSerial cleared, stock −1 (status follows the sale, D-75).
        val unit = db.auditDao().serials().single()
        ledger.sell(SaleIn("A", null, 1L, listOf(SaleLineIn(p, Qty.ONE, Money(4_000_000), unit.id)), Money.ZERO, Money(4_000_000), PaymentMode.CASH))
        assertInvariants()

        ledger.purchase(buy(PurchaseLineIn(p, serials = listOf(SerialIn("444", Money(3_000_000))))))

        val rows = db.auditDao().serials().filter { it.serial == "444" }
        assertEquals(2, rows.size)
        assertEquals(SerialStatus.SOLD, rows.single { it.id == unit.id }.status)
        assertEquals(3_800_000, rows.single { it.id == unit.id }.costPaisa)
        assertEquals(3_000_000, rows.single { it.id != unit.id }.costPaisa)
        assertEquals(1_000, onHand(p))
        assertInvariants()
    }

    @Test
    fun `damaged unit leaves stock and its IMEI can be bought again`() = runTest {
        val p = phone(OpeningStock.Serials(listOf(SerialIn("555", Money(3_800_000)))))
        val unit = db.auditDao().serials().single()
        ledger.removeSerial(unit.id, SerialStatus.DAMAGED, "screen cracked")
        assertEquals(0, onHand(p))
        assertNull(db.ledgerDao().serial(unit.id)!!.liveSerial)
        ledger.purchase(buy(PurchaseLineIn(p, serials = listOf(SerialIn("555", Money(1_000_000))))))
        assertEquals(1_000, onHand(p))
        assertInvariants()
    }

    // ------------------------------------------------------------------ pending cost fill (D-16)

    /** What an M2 sale of [qtyMilli] with no stock leaves behind: a pending line and −qty stock. */
    private suspend fun simulateSaleWithoutStock(variantId: String, qtyMilli: Long, saleId: String, date: Long): String {
        val audit = db.auditDao()
        val dao = db.ledgerDao()
        val m = SyncMeta(date, date, null, "dev-A")
        audit.insertSale(SaleEntity(saleId, "A", saleId.hashCode(), null, date, 20251009, 0, 0, null, 0, 0, DocStatus.ACTIVE, null, null, m))
        val lineId = "$saleId-line"
        audit.insertSaleLine(SaleLineEntity(lineId, saleId, variantId, "Charger 25W", qtyMilli, 120_000, 0, 0, qtyMilli, null, m))
        dao.insertMovement(StockMovementEntity("$saleId-mv", variantId, MovementType.SALE, -qtyMilli, saleId, m))
        dao.updateVariant(dao.variant(variantId)!!.let { it.copy(onHandMilli = it.onHandMilli - qtyMilli) })
        return lineId
    }

    @Test
    fun `purchase fills pending sale lines oldest first, then stocks the rest`() = runTest {
        val c = charger()
        val older = simulateSaleWithoutStock(c, 2_000, "s1", date = 100)
        val newer = simulateSaleWithoutStock(c, 3_000, "s2", date = 200)
        assertEquals(-5_000, onHand(c))
        assertInvariants()

        ledger.purchase(buy(PurchaseLineIn(c, Qty.units(4), Money(80_000))))

        val audit = db.auditDao()
        assertEquals(0, audit.saleLine(older)!!.pendingQtyMilli)
        assertEquals(160_000, audit.saleLine(older)!!.costPaisa) // 2 × Rs 800
        assertEquals(1_000, audit.saleLine(newer)!!.pendingQtyMilli)
        assertEquals(160_000, audit.saleLine(newer)!!.costPaisa)
        assertEquals(0, db.ledgerDao().openLots(c).size) // all 4 went to pending lines
        assertEquals(-1_000, onHand(c))
        assertInvariants()
    }

    @Test
    fun `voided sales never receive cost`() = runTest {
        val c = charger()
        simulateSaleWithoutStock(c, 2_000, "s1", date = 100)
        val audit = db.auditDao()
        ledger.voidSale("s1", "mistake")
        assertEquals(0, audit.saleLine("s1-line")!!.pendingQtyMilli)
        assertInvariants()

        ledger.purchase(buy(PurchaseLineIn(c, Qty.units(3), Money(80_000))))
        assertEquals(0, audit.saleLine("s1-line")!!.costPaisa)
        assertEquals(3_000, onHand(c))
        assertInvariants()
    }

    // ------------------------------------------------------------------ purchase void

    @Test
    fun `untouched purchase can be voided, stock and payment reversed`() = runTest {
        val c = charger()
        val p = phone()
        val id = ledger.purchase(
            buy(
                PurchaseLineIn(c, Qty.units(5), Money(80_000)),
                PurchaseLineIn(p, serials = listOf(SerialIn("777", Money(3_800_000)))),
                paid = Money(1_000_000),
            )
        )
        ledger.voidPurchase(id, "entered twice")

        assertEquals(0, onHand(c))
        assertEquals(0, onHand(p))
        val purchase = db.ledgerDao().purchase(id)!!
        assertEquals(DocStatus.VOIDED, purchase.status)
        assertEquals("entered twice", purchase.voidReason)
        assertEquals(SerialStatus.VOIDED, db.auditDao().serials().single().status)
        val payments = db.ledgerDao().purchasePayments(id)
        assertEquals(1, db.ledgerDao().reversalCount(payments.single().id))
        // The voided IMEI can be entered again correctly.
        ledger.purchase(buy(PurchaseLineIn(p, serials = listOf(SerialIn("777", Money(3_700_000))))))
        assertInvariants()
    }

    @Test
    fun `purchase whose stock was used cannot be voided`() = runTest {
        val c = charger()
        val id = ledger.purchase(buy(PurchaseLineIn(c, Qty.units(5), Money(80_000))))
        ledger.adjustQuantity(c, Qty.units(-1), null, "broken")
        try {
            ledger.voidPurchase(id, "mistake")
            fail("expected PurchaseStockUsed")
        } catch (_: LedgerException.PurchaseStockUsed) {
        }
        assertEquals(DocStatus.ACTIVE, db.ledgerDao().purchase(id)!!.status)
        assertEquals(4_000, onHand(c))
        assertInvariants()
    }

    @Test
    fun `voiding twice is refused`() = runTest {
        val c = charger()
        val id = ledger.purchase(buy(PurchaseLineIn(c, Qty.units(1), Money(80_000))))
        ledger.voidPurchase(id, "mistake")
        try {
            ledger.voidPurchase(id, "again")
            fail("expected AlreadyVoided")
        } catch (_: LedgerException.AlreadyVoided) {
        }
        assertInvariants()
    }

    // ------------------------------------------------------------------ adjustments (D-34)

    @Test
    fun `negative adjustment consumes lots FIFO`() = runTest {
        val c = charger(OpeningStock.Quantity(Qty.units(3), Money(70_000)))
        ledger.purchase(buy(PurchaseLineIn(c, Qty.units(5), Money(80_000))))
        ledger.adjustQuantity(c, Qty.units(-4), null, "stock count")
        val lots = db.ledgerDao().openLots(c)
        assertEquals(1, lots.size)
        assertEquals(4_000, lots.single().qtyRemainingMilli)
        assertEquals(80_000, lots.single().unitCostPaisa)
        assertEquals(4_000, onHand(c))
        assertInvariants()
    }

    @Test
    fun `negative adjustment beyond stock is refused and rolled back`() = runTest {
        val c = charger(OpeningStock.Quantity(Qty.units(2), Money(70_000)))
        try {
            ledger.adjustQuantity(c, Qty.units(-3), null, "stock count")
            fail("expected InsufficientStock")
        } catch (e: LedgerException.InsufficientStock) {
            assertEquals(2_000, e.availableMilli)
        }
        assertEquals(2_000, onHand(c))
        // The adjustment row written before the failure must be gone too.
        assertEquals(1, db.auditDao().movements().size)
        assertInvariants()
    }

    @Test
    fun `positive adjustment defaults to the last cost`() = runTest {
        val c = charger(OpeningStock.Quantity(Qty.units(2), Money(70_000)))
        ledger.adjustQuantity(c, Qty.units(1), null, "found in drawer")
        assertEquals(listOf(70_000L, 70_000L), db.ledgerDao().openLots(c).map { it.unitCostPaisa })
        assertEquals(3_000, onHand(c))
        assertInvariants()
    }

    @Test
    fun `adjustment needs a reason`() = runTest {
        val c = charger()
        try {
            ledger.adjustQuantity(c, Qty.units(1), Money(1), " ")
            fail("expected InvalidInput")
        } catch (e: LedgerException.InvalidInput) {
            assertEquals("reason", e.field)
        }
        assertInvariants()
    }
}
