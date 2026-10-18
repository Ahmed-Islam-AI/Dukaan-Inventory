package app.dukaan.android.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

/*
 * Facts the stock replay (core/ledger/StockReplay) needs for ONE variant, and the writes of its
 * derived columns. Derived writes never touch updatedAt: they are caches, not edits, so they are
 * never pushed to other phones (D-75, sync triggers fire on updatedAt changes only).
 */

/** voidedAt = when its purchase was voided (null = purchase active, or not a purchase lot). */
data class LotFact(
    val id: String,
    val qtyInMilli: Long,
    val qtyRemainingMilli: Long,
    val unitCostPaisa: Long,
    val receivedAt: Long,
    val createdAt: Long,
    val voidedAt: Long?,
)

/** A QUANTITY sale line with its sale's ordering and void time (null = sale active). */
data class LineFact(
    val id: String,
    val qtyMilli: Long,
    val createdAt: Long,
    val costPaisa: Long,
    val pendingQtyMilli: Long,
    val saleDate: Long,
    val saleCreatedAt: Long,
    val saleVoidedAt: Long?,
)

/** A return line; voidedAt = when its return was voided (null = active). */
data class ReturnFact(
    val id: String,
    val saleLineId: String,
    val qtyMilli: Long,
    val costReversedPaisa: Long,
    val createdAt: Long,
    val voidedAt: Long?,
)

/** A negative QUANTITY adjustment: [qtyMilli] taken out (positive). */
data class AdjustFact(val id: String, val qtyMilli: Long, val createdAt: Long)

data class UnitFact(
    val id: String,
    val serial: String,
    val liveSerial: String?,
    val costPaisa: Long,
    val status: SerialStatus,
    val createdAt: Long,
    val purchaseVoided: Boolean,
    /** DAMAGED / RETURNED_TO_SUPPLIER when the unit was taken out, else null. */
    val removedStatus: SerialStatus?,
)

/** A SERIAL sale line: [active] = its sale is ACTIVE; [returnedMilli] by ACTIVE returns. */
data class SerialLineFact(
    val id: String,
    val serialUnitId: String,
    val costPaisa: Long,
    val active: Boolean,
    val returnedMilli: Long,
)

data class UnitStatusRow(val id: String, val serial: String, val status: SerialStatus, val liveSerial: String?, val createdAt: Long)

@Dao
interface RebuildDao {
    @Query("SELECT p.trackingType FROM variant v JOIN product p ON p.id = v.productId WHERE v.id = :variantId")
    suspend fun trackingType(variantId: String): TrackingType?

    @Query(
        """SELECT l.id, l.qtyInMilli, l.qtyRemainingMilli, l.unitCostPaisa, l.receivedAt, l.createdAt,
                  (SELECT pu.voidedAt FROM purchase_line pl JOIN purchase pu ON pu.id = pl.purchaseId
                   WHERE l.sourceType = 'PURCHASE' AND pl.id = l.sourceId AND pu.status = 'VOIDED') AS voidedAt
           FROM stock_lot l WHERE l.variantId = :variantId"""
    )
    suspend fun lots(variantId: String): List<LotFact>

    // LEFT JOIN: after a sync a line can arrive before its sale; it then counts as active.
    @Query(
        """SELECT sl.id, sl.qtyMilli, sl.createdAt, sl.costPaisa, sl.pendingQtyMilli,
                  COALESCE(s.date, sl.createdAt) AS saleDate, COALESCE(s.createdAt, sl.createdAt) AS saleCreatedAt,
                  CASE WHEN s.status = 'VOIDED' THEN COALESCE(s.voidedAt, s.updatedAt) END AS saleVoidedAt
           FROM sale_line sl LEFT JOIN sale s ON s.id = sl.saleId
           WHERE sl.variantId = :variantId AND sl.serialUnitId IS NULL"""
    )
    suspend fun lines(variantId: String): List<LineFact>

    @Query(
        """SELECT rl.id, rl.saleLineId, rl.qtyMilli, rl.costReversedPaisa, r.createdAt,
                  CASE WHEN r.status = 'VOIDED' THEN COALESCE(r.voidedAt, r.updatedAt) END AS voidedAt
           FROM sale_return_line rl JOIN sale_return r ON r.id = rl.returnId JOIN sale_line sl ON sl.id = rl.saleLineId
           WHERE sl.variantId = :variantId"""
    )
    suspend fun returns(variantId: String): List<ReturnFact>

    @Query("SELECT id, -qtyDeltaMilli AS qtyMilli, createdAt FROM stock_adjustment WHERE variantId = :variantId AND qtyDeltaMilli < 0")
    suspend fun negativeAdjustments(variantId: String): List<AdjustFact>

    @Query("SELECT a.* FROM lot_allocation a JOIN stock_lot l ON l.id = a.lotId WHERE l.variantId = :variantId")
    suspend fun allocations(variantId: String): List<LotAllocationEntity>

    @Query("DELETE FROM lot_allocation WHERE lotId IN (SELECT id FROM stock_lot WHERE variantId = :variantId)")
    suspend fun deleteAllocations(variantId: String)

    @Insert suspend fun insertAllocations(rows: List<LotAllocationEntity>)

    @Query("UPDATE stock_lot SET qtyRemainingMilli = :remaining WHERE id = :id")
    suspend fun setLotRemaining(id: String, remaining: Long)

    @Query("UPDATE sale_line SET costPaisa = :cost, pendingQtyMilli = :pending WHERE id = :id")
    suspend fun setLineCost(id: String, cost: Long, pending: Long)

    @Query("UPDATE sale_return_line SET costReversedPaisa = :cost WHERE id = :id")
    suspend fun setReturnCost(id: String, cost: Long)

    @Query(
        """SELECT su.id, su.serial, su.liveSerial, su.costPaisa, su.status, su.createdAt,
                  EXISTS (SELECT 1 FROM purchase_line pl JOIN purchase pu ON pu.id = pl.purchaseId
                          WHERE su.sourceType = 'PURCHASE' AND pl.id = su.sourceId AND pu.status = 'VOIDED') AS purchaseVoided,
                  (SELECT e.status FROM serial_event e WHERE e.serialUnitId = su.id
                     AND e.status IN ('DAMAGED', 'RETURNED_TO_SUPPLIER') ORDER BY e.createdAt DESC, e.id DESC LIMIT 1) AS removedStatus
           FROM serial_unit su WHERE su.variantId = :variantId"""
    )
    suspend fun units(variantId: String): List<UnitFact>

    @Query(
        """SELECT sl.id, sl.serialUnitId, sl.costPaisa, COALESCE(s.status, 'ACTIVE') = 'ACTIVE' AS active,
                  (SELECT COALESCE(SUM(rl.qtyMilli), 0) FROM sale_return_line rl JOIN sale_return r ON r.id = rl.returnId
                   WHERE rl.saleLineId = sl.id AND r.status = 'ACTIVE') AS returnedMilli
           FROM sale_line sl LEFT JOIN sale s ON s.id = sl.saleId
           WHERE sl.variantId = :variantId AND sl.serialUnitId IS NOT NULL"""
    )
    suspend fun serialLines(variantId: String): List<SerialLineFact>

    @Query("UPDATE sale_line SET costPaisa = :cost WHERE id = :id")
    suspend fun setSerialLineCost(id: String, cost: Long)

    @Query("UPDATE sale_return_line SET costReversedPaisa = :cost WHERE saleLineId = :lineId AND costReversedPaisa != :cost")
    suspend fun setReturnCostForLine(lineId: String, cost: Long)

    @Query("UPDATE serial_unit SET status = :status WHERE id = :id")
    suspend fun setUnitStatus(id: String, status: SerialStatus)

    @Query("SELECT id, serial, status, liveSerial, createdAt FROM serial_unit WHERE serial IN (:serials)")
    suspend fun unitsWithSerials(serials: List<String>): List<UnitStatusRow>

    @Query("UPDATE serial_unit SET liveSerial = :live WHERE id = :id")
    suspend fun setLiveSerial(id: String, live: String?)

    /** Invariant 1: the cache is the sum of the ledger. No write when already equal. */
    @Query(
        """UPDATE variant SET onHandMilli = (SELECT COALESCE(SUM(m.qtyDeltaMilli), 0) FROM stock_movement m WHERE m.variantId = :variantId)
           WHERE id = :variantId AND onHandMilli != (SELECT COALESCE(SUM(m.qtyDeltaMilli), 0) FROM stock_movement m WHERE m.variantId = :variantId)"""
    )
    suspend fun refreshOnHand(variantId: String)
}
