package app.dukaan.android.core.ledger

import app.dukaan.android.core.db.AllocationConsumer
import app.dukaan.android.core.db.AuditDao
import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.SerialStatus
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty

/**
 * Checks the ledger invariants (1-6) in docs/ARCHITECTURE.md § Invariants. Used by every ledger test
 * after every operation, and by Settings → "Check stock" (M2).
 *
 * ponytail: loads whole tables and checks in Kotlin. Fine at shop scale (tens of thousands of
 * rows); move to SQL aggregates if a large shop makes it slow.
 */
class InvariantChecker(private val dao: AuditDao) {

    suspend fun violations(): List<String> {
        val products = dao.products().associateBy { it.id }
        val variants = dao.variants()
        val movementSum = dao.movements().groupBy { it.variantId }.mapValues { (_, m) -> m.sumOf { it.qtyDeltaMilli } }
        val lots = dao.lots()
        val allocations = dao.allocations()
        val serials = dao.serials()
        val activeSales = dao.sales().filter { it.status == DocStatus.ACTIVE }.map { it.id }.toSet()
        val saleLines = dao.saleLines()
        val out = mutableListOf<String>()

        for (v in variants) {
            val tracking = products[v.productId]?.trackingType
            // 1. cache == ledger
            val moved = movementSum[v.id] ?: 0
            if (v.onHandMilli != moved) out += "I1 variant ${v.id}: onHand ${v.onHandMilli} != movements $moved"
            when (tracking) {
                // 2. QUANTITY: onHand == lots − pending
                TrackingType.QUANTITY -> {
                    val inLots = lots.filter { it.variantId == v.id }.sumOf { it.qtyRemainingMilli }
                    val pending = saleLines.filter { it.variantId == v.id && it.saleId in activeSales }.sumOf { it.pendingQtyMilli }
                    if (v.onHandMilli != inLots - pending) out += "I2 variant ${v.id}: onHand ${v.onHandMilli} != lots $inLots - pending $pending"
                }
                // 3. SERIAL: onHand == 1000 × in-stock units
                TrackingType.SERIAL -> {
                    val units = serials.count { it.variantId == v.id && it.status == SerialStatus.IN_STOCK }
                    if (v.onHandMilli != units * 1000L) out += "I3 variant ${v.id}: onHand ${v.onHandMilli} != $units units"
                }
                null -> out += "variant ${v.id}: product missing"
            }
        }

        // 4. sale line cost == its live allocations (QUANTITY lines)
        val allocByConsumer = allocations.groupBy { it.consumerType to it.consumerId }
        for (line in saleLines.filter { it.serialUnitId == null }) {
            val cost = allocByConsumer[AllocationConsumer.SALE_LINE to line.id].orEmpty()
                .sumOf { Money(it.unitCostPaisa).times(Qty(it.qtyMilli - it.reversedQtyMilli)).minor }
            if (line.costPaisa != cost) out += "I4 sale_line ${line.id}: cost ${line.costPaisa} != allocations $cost"
        }

        // 5. lot remaining == qtyIn − live allocations (0 for a voided purchase)
        val voidedLines = voidedPurchaseLineIds()
        val allocByLot = allocations.groupBy { it.lotId }
        for (lot in lots) {
            val used = allocByLot[lot.id].orEmpty().sumOf { it.qtyMilli - it.reversedQtyMilli }
            val expected = if (lot.sourceId in voidedLines) 0 else lot.qtyInMilli - used
            if (lot.qtyRemainingMilli != expected) out += "I5 lot ${lot.id}: remaining ${lot.qtyRemainingMilli} != $expected"
            if (lot.qtyRemainingMilli < 0) out += "I5 lot ${lot.id}: negative remaining"
        }

        // 6. QUANTITY line: what is still sold == pending + live allocations; voided sales hold nothing
        val activeReturns = dao.returns().filter { it.status == DocStatus.ACTIVE }.map { it.id }.toSet()
        val returned = dao.returnLines().filter { it.returnId in activeReturns }.groupBy { it.saleLineId }
            .mapValues { (_, l) -> l.sumOf { it.qtyMilli } }
        for (line in saleLines.filter { it.serialUnitId == null }) {
            val live = allocByConsumer[AllocationConsumer.SALE_LINE to line.id].orEmpty().sumOf { it.qtyMilli - it.reversedQtyMilli }
            val held = if (line.saleId in activeSales) line.qtyMilli - (returned[line.id] ?: 0) else 0
            if (held < 0) out += "I6 sale_line ${line.id}: returned more than sold"
            if (line.pendingQtyMilli + live != held) out += "I6 sale_line ${line.id}: pending ${line.pendingQtyMilli} + allocated $live != held $held"
        }

        // liveSerial mirrors status (D-35)
        for (s in serials) {
            val expected = if (s.status == SerialStatus.IN_STOCK) s.serial else null
            if (s.liveSerial != expected) out += "serial ${s.id}: liveSerial ${s.liveSerial} != $expected"
        }
        return out
    }

    private suspend fun voidedPurchaseLineIds(): Set<String> {
        val voided = dao.purchases().filter { it.status == DocStatus.VOIDED }.map { it.id }.toSet()
        return dao.purchaseLines().filter { it.purchaseId in voided }.map { it.id }.toSet()
    }
}
