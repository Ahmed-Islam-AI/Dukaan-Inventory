package app.dukaan.android.core.ledger

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.dukaan.android.core.db.DukaanDatabase
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.ShopProfileEntity
import app.dukaan.android.core.db.SyncMeta
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue

/**
 * In-memory DB + LedgerService with a fixed clock and sequential ids, for ledger tests.
 * Call [close] in @After.
 */
class LedgerFixture {
    val db: DukaanDatabase = Room.inMemoryDatabaseBuilder<DukaanDatabase>().setDriver(BundledSQLiteDriver()).build()
    private var ids = 0
    var now = 1_760_000_000_000L // 2025-10-09 in Asia/Karachi
    val ledger = LedgerService(
        db = db,
        deviceId = { "dev-A" },
        zone = { ZoneId.of("Asia/Karachi") },
        clock = { now++ },
        newId = { "id-${++ids}" },
    )

    fun close() = db.close()

    private var taxBp: Int? = null

    suspend fun shop(taxRateBp: Int? = null) {
        taxBp = taxRateBp
        db.shopDao().insert(
            ShopProfileEntity(
                "shop", "Ali Mobiles", "mobiles", null, null, null, "PKR", "Rs", "Asia/Karachi", taxRateBp, null,
                SyncMeta(1, 1, null, "dev-A"),
            )
        )
    }

    suspend fun assertInvariants() {
        val v = InvariantChecker(db.auditDao()).violations()
        assertTrue("invariants broken:\n" + v.joinToString("\n"), v.isEmpty())
    }

    suspend fun onHand(variantId: String) = db.ledgerDao().variant(variantId)!!.onHandMilli

    suspend fun variantOf(productId: String) = db.catalogDao().variants(productId).first().single().id

    suspend fun quantityItem(name: String, price: Money, opening: OpeningStock? = null, decimal: Boolean = false, unit: String = "pcs") =
        variantOf(ledger.createItem(NewItem(name, null, TrackingType.QUANTITY, unit, decimal, listOf(NewVariant("Default", price, opening = opening)))))

    suspend fun serialItem(name: String, price: Money, vararg serials: Pair<String, Money>) =
        variantOf(
            ledger.createItem(
                NewItem(name, null, TrackingType.SERIAL, "pcs", false,
                    listOf(NewVariant("Default", price, opening = serials.takeIf { it.isNotEmpty() }?.let { s -> OpeningStock.Serials(s.map { SerialIn(it.first, it.second) }) })))
            )
        )

    suspend fun unitId(serial: String) = db.auditDao().serials().single { it.serial == serial && it.liveSerial != null }.id

    /** A sale; [paid] defaults to exactly the total (a plain cash sale). */
    fun sale(vararg lines: SaleLineIn, paid: Money? = null, customer: String? = null, discount: Money = Money.ZERO, prefix: String = "A"): SaleIn {
        val total = SaleMath.totals(lines.map { it.unitPrice to if (it.serialUnitId != null) Qty.ONE else it.qty }, discount, taxBp).total
        return SaleIn(prefix, customer, now, lines.toList(), discount, paid ?: total, PaymentMode.CASH)
    }

    fun buy(vararg lines: PurchaseLineIn, paid: Money = Money.ZERO) =
        PurchaseIn("Hall Road Traders", null, now, lines.toList(), paid, PaymentMode.CASH)

    companion object {
        fun rs(r: Long) = Money(r * 100)
        fun units(n: Long) = Qty.units(n)
    }
}
