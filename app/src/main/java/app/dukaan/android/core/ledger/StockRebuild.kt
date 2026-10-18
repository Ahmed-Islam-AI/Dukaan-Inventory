package app.dukaan.android.core.ledger

import app.dukaan.android.core.db.DukaanDatabase
import app.dukaan.android.core.db.LotAllocationEntity
import app.dukaan.android.core.db.SerialStatus
import app.dukaan.android.core.db.SyncMeta
import app.dukaan.android.core.db.TrackingType
import java.util.UUID

/**
 * Writes [StockReplay]'s result for the given variants: lot balances, allocations, sale line
 * cost/pending, return cost, IMEI status/liveSerial and onHandMilli. Call inside the write
 * transaction that changed the facts. Only rows whose value changed are written, and updatedAt is
 * never touched (derived state is not an edit and never syncs, D-75).
 *
 * ponytail: replays a variant's whole history each time; fine for a shop's thousands of rows. Add
 * per-variant checkpoints if a long-lived item makes sales noticeably slow.
 */
class StockRebuild(private val db: DukaanDatabase) {
    private val dao get() = db.rebuildDao()

    suspend fun variants(ids: Collection<String>) {
        ids.toSet().forEach { variant(it) }
    }

    private suspend fun variant(variantId: String) {
        when (dao.trackingType(variantId)) {
            TrackingType.QUANTITY -> quantity(variantId)
            TrackingType.SERIAL -> serial(variantId)
            null -> Unit // product not here yet (sync); rebuilt when it arrives
        }
        dao.refreshOnHand(variantId)
    }

    private suspend fun quantity(variantId: String) {
        val lots = dao.lots(variantId)
        val lines = dao.lines(variantId)
        val returns = dao.returns(variantId)
        val r = StockReplay.quantity(lots, lines, returns, dao.negativeAdjustments(variantId))
        lots.forEach { l -> r.lotRemaining[l.id]?.takeIf { it != l.qtyRemainingMilli }?.let { dao.setLotRemaining(l.id, it) } }
        lines.forEach { l ->
            val cost = r.lineCost.getValue(l.id)
            val pending = r.linePending.getValue(l.id)
            if (cost != l.costPaisa || pending != l.pendingQtyMilli) dao.setLineCost(l.id, cost, pending)
        }
        returns.forEach { rt -> r.returnCostReversed[rt.id]?.takeIf { it != rt.costReversedPaisa }?.let { dao.setReturnCost(rt.id, it) } }

        val wanted = r.allocations.mapIndexed { i, a ->
            LotAllocationEntity(
                id = UUID.nameUUIDFromBytes("alloc:$variantId:$i".toByteArray()).toString(),
                lotId = a.lotId, consumerType = a.consumerType, consumerId = a.consumerId,
                qtyMilli = a.qtyMilli, reversedQtyMilli = a.reversedMilli, unitCostPaisa = a.unitCostPaisa,
                meta = SyncMeta(createdAt = a.at, updatedAt = a.at, deviceId = DERIVED),
            )
        }
        if (wanted.map(::shape).sorted() != dao.allocations(variantId).map(::shape).sorted()) {
            dao.deleteAllocations(variantId)
            if (wanted.isNotEmpty()) dao.insertAllocations(wanted)
        }
    }

    private suspend fun serial(variantId: String) {
        val units = dao.units(variantId)
        val lines = dao.serialLines(variantId)
        val r = StockReplay.serial(units, lines)
        units.forEach { u -> r.status.getValue(u.id).takeIf { it != u.status }?.let { dao.setUnitStatus(u.id, it) } }
        lines.forEach { l ->
            val cost = r.lineCost.getValue(l.id)
            if (cost != l.costPaisa) dao.setSerialLineCost(l.id, cost)
            // A returned IMEI line gives back the unit's cost.
            units.firstOrNull { it.id == l.serialUnitId }?.let { dao.setReturnCostForLine(l.id, it.costPaisa) }
        }
        liveSerials(units.map { it.serial }.distinct())
    }

    /**
     * liveSerial = serial for the IN_STOCK unit of each IMEI (D-35), across all variants. Two units
     * of one IMEI in stock (added on two phones) can't both hold it: the newest does, and the owner
     * is shown the clash. Cleared first so the unique index never sees two at once.
     */
    private suspend fun liveSerials(serials: List<String>) {
        if (serials.isEmpty()) return
        val all = serials.chunked(500).flatMap { dao.unitsWithSerials(it) }
        val winners = all.filter { it.status == SerialStatus.IN_STOCK }
            .groupBy { it.serial }
            .mapValues { (_, units) -> units.maxWith(compareBy({ it.createdAt }, { it.id })).id }
        val wanted = all.associate { it.id to if (winners[it.serial] == it.id) it.serial else null }
        val changed = all.filter { it.liveSerial != wanted[it.id] }
        changed.filter { it.liveSerial != null }.forEach { dao.setLiveSerial(it.id, null) }
        changed.forEach { u -> wanted[u.id]?.let { dao.setLiveSerial(u.id, it) } }
    }

    private fun shape(a: LotAllocationEntity) = "${a.lotId}|${a.consumerType}|${a.consumerId}|${a.qtyMilli}|${a.reversedQtyMilli}|${a.unitCostPaisa}"

    private companion object {
        const val DERIVED = "derived"
    }
}
