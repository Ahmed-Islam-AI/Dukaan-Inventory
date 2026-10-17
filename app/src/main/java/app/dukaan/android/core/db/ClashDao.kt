package app.dukaan.android.core.db

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Things two phones did offline that can't both be true. Both records are kept; the owner fixes (D-78). */
enum class ClashKind {
    /** One IMEI on two active bills. */
    IMEI_SOLD_TWICE,
    /** One IMEI added to stock on two phones. */
    IMEI_IN_STOCK_TWICE,
    /** An IMEI on an active bill was marked damaged / returned to supplier, or its purchase voided. */
    SOLD_UNIT_REMOVED,
    /** A bill voided on one phone and returned on another. */
    RETURN_ON_VOIDED_BILL,
    /** Returns on two phones add up to more than was sold. */
    RETURNED_TOO_MUCH,
    /** Two phones used the same bill prefix. */
    BILL_NUMBER_TWICE,
    /** Stock corrected down on two phones: together more than there was. */
    STOCK_REMOVED_TWICE,
}

/** [subject]: the IMEI, item name or bill number; [saleIds]: comma-separated bills involved. */
data class Clash(val kind: ClashKind, val subject: String?, val saleIds: String?)

private const val HELD = """sl.qtyMilli > (SELECT COALESCE(SUM(rl.qtyMilli), 0) FROM sale_return_line rl
    JOIN sale_return r ON r.id = rl.returnId WHERE rl.saleLineId = sl.id AND r.status = 'ACTIVE')"""

@Dao
interface ClashDao {
    @Query(
        """SELECT 'IMEI_SOLD_TWICE' AS kind, su.serial AS subject, GROUP_CONCAT(sl.saleId) AS saleIds
           FROM sale_line sl JOIN sale s ON s.id = sl.saleId JOIN serial_unit su ON su.id = sl.serialUnitId
           WHERE s.status = 'ACTIVE' AND $HELD
           GROUP BY sl.serialUnitId HAVING COUNT(*) > 1
           UNION ALL
           SELECT 'IMEI_IN_STOCK_TWICE', serial, NULL FROM serial_unit WHERE status = 'IN_STOCK'
           GROUP BY serial HAVING COUNT(*) > 1
           UNION ALL
           SELECT 'SOLD_UNIT_REMOVED', su.serial, GROUP_CONCAT(sl.saleId)
           FROM sale_line sl JOIN sale s ON s.id = sl.saleId JOIN serial_unit su ON su.id = sl.serialUnitId
           WHERE s.status = 'ACTIVE' AND su.status IN ('VOIDED', 'DAMAGED', 'RETURNED_TO_SUPPLIER') AND $HELD
           GROUP BY su.id
           UNION ALL
           SELECT 'RETURN_ON_VOIDED_BILL', NULL, s.id FROM sale s
           WHERE s.status = 'VOIDED' AND EXISTS (SELECT 1 FROM sale_return r WHERE r.saleId = s.id AND r.status = 'ACTIVE')
           UNION ALL
           SELECT 'RETURNED_TOO_MUCH', sl.nameSnapshot, sl.saleId FROM sale_line sl WHERE NOT ($HELD) AND sl.qtyMilli < (
               SELECT COALESCE(SUM(rl.qtyMilli), 0) FROM sale_return_line rl JOIN sale_return r ON r.id = rl.returnId
               WHERE rl.saleLineId = sl.id AND r.status = 'ACTIVE')
           UNION ALL
           SELECT 'BILL_NUMBER_TWICE', invoicePrefix || '-' || invoiceSeq, GROUP_CONCAT(id) FROM sale
           GROUP BY invoicePrefix, invoiceSeq HAVING COUNT(*) > 1
           UNION ALL
           SELECT 'STOCK_REMOVED_TWICE', pr.name, NULL FROM stock_adjustment a
           JOIN variant v ON v.id = a.variantId JOIN product pr ON pr.id = v.productId
           WHERE a.qtyDeltaMilli < 0 AND pr.trackingType = 'QUANTITY' AND -a.qtyDeltaMilli > (
               SELECT COALESCE(SUM(al.qtyMilli - al.reversedQtyMilli), 0) FROM lot_allocation al
               WHERE al.consumerType = 'ADJUSTMENT' AND al.consumerId = a.id)
           GROUP BY a.variantId"""
    )
    fun clashes(): Flow<List<Clash>>

    /** Prefixes this phone may not take: already on bills from other phones (D-77). */
    @Query("SELECT DISTINCT invoicePrefix FROM sale WHERE deviceId != :deviceId")
    suspend fun prefixesOfOtherPhones(deviceId: String): List<String>
}
