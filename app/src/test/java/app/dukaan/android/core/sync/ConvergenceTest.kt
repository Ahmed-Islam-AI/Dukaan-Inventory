package app.dukaan.android.core.sync

import app.dukaan.android.core.db.ClashKind
import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.PartyType
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.SerialStatus
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.ledger.LedgerFixture.Companion.rs
import app.dukaan.android.core.ledger.NewItem
import app.dukaan.android.core.ledger.NewVariant
import app.dukaan.android.core.ledger.PurchaseIn
import app.dukaan.android.core.ledger.PurchaseLineIn
import app.dukaan.android.core.ledger.ReturnLineIn
import app.dukaan.android.core.ledger.SaleLineIn
import app.dukaan.android.core.ledger.SerialIn
import app.dukaan.android.core.ledger.VariantEdit
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-75: three phones do random ledger work offline and sync at random moments. Whatever the
 * order, after a final full sync every phone holds exactly the same rows, derived costs included,
 * and the ledger invariants that conflicts can't break still hold. Fixed seeds.
 */
class ConvergenceTest {

    @Test fun seed1() = run(1)
    @Test fun seed2() = run(2)
    @Test fun seed3() = run(3)
    @Test fun seed4() = run(4)

    private fun run(seed: Int, steps: Int = 300) = runTest {
        val r = Random(seed)
        val clock = Clock()
        val cloud = Cloud()
        val phones = listOf(Phone("A", clock), Phone("B", clock), Phone("C", clock))
        val a = phones[0]
        try {
            a.shop()
            suspend fun variant(name: String, tracking: TrackingType, decimal: Boolean): String {
                val id = a.ledger.createItem(NewItem(name, null, tracking, "pcs", decimal, listOf(NewVariant("Default", rs(300)))))
                return a.db.catalogDao().variants(id).first().single().id
            }
            val charger = variant("Charger", TrackingType.QUANTITY, false)
            val rice = variant("Rice", TrackingType.QUANTITY, true)
            val phone = variant("Phone", TrackingType.SERIAL, false)
            phones.forEach { it.sync.seedIfNeeded() }
            cloud.syncAll(*phones.toTypedArray())
            var nextImei = 1_000

            for (step in 1..steps) {
                val p = phones.random(r)
                val audit = p.db.auditDao()
                fun qty(decimal: Boolean) = if (decimal) Qty(r.nextLong(1, 5_000)) else Qty.units(r.nextLong(1, 6))
                val (v, dec) = listOf(charger to false, rice to true).random(r)
                try {
                    when (r.nextInt(14)) {
                        0, 1 -> p.ledger.purchase(PurchaseIn("Hall Road", null, clock.now, listOf(PurchaseLineIn(v, qty(dec), rs(r.nextLong(100, 400)))), Money.ZERO, PaymentMode.CASH))
                        // Sometimes the same IMEI is bought on two phones: a clash the owner sees.
                        2 -> p.ledger.purchase(PurchaseIn(null, null, clock.now, listOf(PurchaseLineIn(phone, serials = listOf(SerialIn("${if (r.nextInt(5) == 0) nextImei - 1 else nextImei++}", rs(30_000))))), Money.ZERO, PaymentMode.CASH))
                        3, 4, 5 -> {
                            val lines = mutableListOf(SaleLineIn(v, qty(dec), rs(r.nextLong(200, 1_500))))
                            audit.serials().filter { it.status == SerialStatus.IN_STOCK && it.liveSerial != null }.randomOrNull(r)?.takeIf { r.nextBoolean() }
                                ?.let { lines += SaleLineIn(phone, Qty.ONE, rs(42_000), it.id) }
                            val credit = r.nextInt(4) == 0
                            p.ledger.sell(p.sale(*lines.toTypedArray(), customer = if (credit) "Customer ${r.nextInt(3)}" else null, paid = if (credit) Money.ZERO else null))
                        }
                        6 -> p.ledger.adjustQuantity(v, if (r.nextBoolean()) qty(dec) else -qty(dec), null, "count")
                        7 -> audit.serials().filter { it.status == SerialStatus.IN_STOCK }.randomOrNull(r)?.let { p.ledger.removeSerial(it.id, SerialStatus.DAMAGED, "cracked") }
                        8 -> audit.purchases().filter { it.status == DocStatus.ACTIVE }.randomOrNull(r)?.let { p.ledger.voidPurchase(it.id, "mistake") }
                        9 -> audit.sales().filter { it.status == DocStatus.ACTIVE }.randomOrNull(r)?.let { p.ledger.voidSale(it.id, "mistake") }
                        10 -> audit.sales().filter { it.status == DocStatus.ACTIVE }.randomOrNull(r)?.let { s ->
                            val line = audit.saleLines().filter { it.saleId == s.id }.random(r)
                            val q = if (line.serialUnitId != null) Qty.ONE else Qty((r.nextLong(1, line.qtyMilli + 1) / 1000 * 1000).coerceAtLeast(1_000).coerceAtMost(line.qtyMilli))
                            p.ledger.returnItems(s.id, listOf(ReturnLineIn(line.id, q)), null, PaymentMode.CASH)
                        }
                        11 -> audit.returns().filter { it.status == DocStatus.ACTIVE }.randomOrNull(r)?.let { p.ledger.voidReturn(it.id, "mistake") }
                        12 -> p.db.ledgerDao().partyByName(PartyType.CUSTOMER, "Customer ${r.nextInt(3)}")?.let {
                            p.ledger.recordPayment(it.id, rs(r.nextLong(1, 500)), PaymentMode.CASH, null, clock.now)
                        }
                        13 -> p.ledger.updateItem(p.db.ledgerDao().variant(v)!!.productId, "Item ${r.nextInt(100)}", null, "pcs", listOf(VariantEdit(v, "Default", rs(r.nextLong(200, 900)), null, null)))
                    }
                } catch (e: LedgerException) {
                    // refused on this phone's view (no stock, already voided, ...): nothing written
                }
                // Each phone's own view stays consistent... except for clashes merged in from others.
                // Random network: a phone pushes and/or pulls now and then.
                if (r.nextInt(4) == 0) cloud.push(phones.random(r))
                if (r.nextInt(4) == 0) cloud.pull(phones.random(r))
            }

            cloud.syncAll(*phones.toTypedArray())
            val digest = a.digest()
            phones.drop(1).forEach { assertEquals("seed $seed: ${it.name} differs from A", digest, it.digest()) }
            phones.forEach { assertEquals("seed $seed: ${it.name} has unpushed rows", 0, it.db.syncDao().waitingNow()) }
            // Merging can create clashes (IMEI sold twice, returned on a voided bill...) which show in
            // stock counts, but costs always add up: cache = ledger, cost = allocations, lots balance.
            val broken = a.violations().filter { it.startsWith("I1") || it.startsWith("I4") || it.startsWith("I5") }
            assertTrue("seed $seed:\n" + broken.joinToString("\n"), broken.isEmpty())
            // Stock that doesn't add up after a merge is always explained to the owner (D-78).
            val kinds = a.db.clashDao().clashes().first().map { it.kind }.toSet()
            val explains = mapOf(
                "I2" to setOf(ClashKind.STOCK_REMOVED_TWICE, ClashKind.RETURN_ON_VOIDED_BILL, ClashKind.RETURNED_TOO_MUCH),
                "I3" to setOf(ClashKind.IMEI_SOLD_TWICE, ClashKind.IMEI_IN_STOCK_TWICE, ClashKind.SOLD_UNIT_REMOVED),
                "I6" to setOf(ClashKind.RETURN_ON_VOIDED_BILL, ClashKind.RETURNED_TOO_MUCH),
            )
            val unexplained = a.violations().filter { v -> explains[v.take(2)]?.let { it.intersect(kinds).isEmpty() } ?: false }
            assertTrue("seed $seed: stock is off but no clash says why ($kinds):\n" + unexplained.joinToString("\n"), unexplained.isEmpty())
        } finally {
            phones.forEach { it.close() }
        }
    }
}
