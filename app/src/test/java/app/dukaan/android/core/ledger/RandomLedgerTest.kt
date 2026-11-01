package app.dukaan.android.core.ledger

import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.PartyType
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.SerialStatus
import app.dukaan.android.core.ledger.LedgerFixture.Companion.rs
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-31: random sequences of ledger operations; after EVERY step all invariants must hold,
 * including after operations that are expected to fail (they must roll back cleanly).
 * Fixed seeds, so a failure is reproducible: the message names the seed and step.
 *
 * Ops so far: purchase (qty, IMEIs incl. buy-back), sell (incl. beyond stock), ± adjust,
 * take unit out, void purchase, sale void, partial returns, return void, party payments.
 */
class RandomLedgerTest {

    @Test fun seed1() = run(seed = 1)
    @Test fun seed2() = run(seed = 2)
    @Test fun seed3() = run(seed = 3)

    private fun run(seed: Int, steps: Int = 500) = runTest {
        val f = LedgerFixture()
        val r = Random(seed)
        try {
            f.shop()
            val charger = f.quantityItem("Charger", rs(1_200))
            val rice = f.quantityItem("Rice", rs(250), decimal = true, unit = "kg")
            val phone = f.serialItem("Phone", rs(40_000))
            val qtyItems = listOf(charger to false, rice to true)
            var nextImei = 1_000
            val soldImeis = mutableListOf<String>()
            var ok = 0
            var refused = 0

            fun qty(decimal: Boolean) = if (decimal) Qty(r.nextLong(1, 5_000)) else Qty.units(r.nextLong(1, 6))
            fun cost() = rs(r.nextLong(100, 400))

            for (step in 1..steps) {
                val op = r.nextInt(11)
                try {
                    when (op) {
                        0 -> { // purchase quantity
                            val (v, dec) = qtyItems.random(r)
                            f.ledger.purchase(f.buy(PurchaseLineIn(v, qty(dec), cost())))
                        }
                        1 -> { // purchase IMEIs: new ones, sometimes a buy-back of a sold one
                            val serials = List(r.nextInt(1, 3)) {
                                val imei = if (soldImeis.isNotEmpty() && r.nextInt(4) == 0) soldImeis.removeAt(0) else "${nextImei++}"
                                SerialIn(imei, rs(r.nextLong(30_000, 39_000)))
                            }
                            f.ledger.purchase(f.buy(PurchaseLineIn(phone, serials = serials)))
                        }
                        2, 3 -> { // sell quantity (may exceed stock) and maybe a phone
                            val (v, dec) = qtyItems.random(r)
                            val lines = mutableListOf(SaleLineIn(v, qty(dec), rs(r.nextLong(200, 1_500))))
                            val inStock = f.db.auditDao().serials().filter { it.status == SerialStatus.IN_STOCK }
                            if (inStock.isNotEmpty() && r.nextBoolean()) {
                                val unit = inStock.random(r)
                                lines += SaleLineIn(phone, Qty.ONE, rs(42_000), unit.id)
                                soldImeis += unit.serial
                            }
                            val credit = r.nextInt(5) == 0
                            f.ledger.sell(f.sale(*lines.toTypedArray(), paid = if (credit) Money.ZERO else null, customer = if (credit) "Customer ${r.nextInt(3)}" else null))
                        }
                        4 -> { // adjust ±
                            val (v, dec) = qtyItems.random(r)
                            val q = qty(dec)
                            f.ledger.adjustQuantity(v, if (r.nextBoolean()) q else -q, null, "count")
                        }
                        5 -> { // take a unit out
                            val unit = f.db.auditDao().serials().filter { it.status == SerialStatus.IN_STOCK }.randomOrNull(r) ?: continue
                            f.ledger.removeSerial(unit.id, SerialStatus.DAMAGED, "cracked")
                        }
                        6 -> { // void a random active purchase (often refused: stock already used)
                            val p = f.db.auditDao().purchases().filter { it.status == DocStatus.ACTIVE }.randomOrNull(r) ?: continue
                            f.ledger.voidPurchase(p.id, "mistake")
                        }
                        7 -> { // void a random active sale (refused while it has an active return)
                            val s = f.db.auditDao().sales().filter { it.status == DocStatus.ACTIVE }.randomOrNull(r) ?: continue
                            f.ledger.voidSale(s.id, "mistake")
                        }
                        8, 9 -> { // return part of a random line of an active sale
                            val s = f.db.auditDao().sales().filter { it.status == DocStatus.ACTIVE }.randomOrNull(r) ?: continue
                            val line = f.db.auditDao().saleLines().filter { it.saleId == s.id }.random(r)
                            val q = if (line.serialUnitId != null) Qty.ONE else Qty(r.nextLong(1, line.qtyMilli + 1) / 1000 * 1000).takeIf { it.milli > 0 } ?: Qty(line.qtyMilli)
                            f.ledger.returnItems(s.id, listOf(ReturnLineIn(line.id, q)), null, PaymentMode.CASH)
                        }
                        10 -> { // void a return, or a customer pays something
                            val ret = f.db.auditDao().returns().filter { it.status == DocStatus.ACTIVE }.randomOrNull(r)
                            if (ret != null && r.nextBoolean()) f.ledger.voidReturn(ret.id, "mistake")
                            else {
                                val party = f.db.ledgerDao().partyByName(PartyType.CUSTOMER, "Customer ${r.nextInt(3)}") ?: continue
                                f.ledger.recordPayment(party.id, rs(r.nextLong(1, 500)), PaymentMode.CASH, null, f.now)
                            }
                        }
                    }
                    ok++
                } catch (e: LedgerException) {
                    refused++ // expected refusals (insufficient stock, stock used, ...) must roll back
                }
                val v = InvariantChecker(f.db.auditDao()).violations()
                assertTrue("seed $seed step $step op $op broke invariants:\n${v.joinToString("\n")}", v.isEmpty())
                // Units refused by a rolled-back sale were never sold.
                soldImeis.retainAll(f.db.auditDao().serials().filter { it.status == SerialStatus.SOLD }.map { it.serial }.toSet())
            }
            assertTrue("seed $seed: too few successful ops ($ok ok, $refused refused)", ok > steps / 2)
        } finally {
            f.close()
        }
    }
}
