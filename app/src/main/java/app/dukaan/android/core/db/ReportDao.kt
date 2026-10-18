package app.dukaan.android.core.db

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Raw sums for a businessDay range; [app.dukaan.android.core.report.Metrics] turns them into figures. */
data class SummaryRow(
    val salesPaisa: Long,
    val bills: Int,
    val refundsPaisa: Long,
    /** Cost of what was sold on these days, before any return (line cost + cost later reversed). */
    val costPaisa: Long,
    /** Cost of what came back on these days. */
    val costBackPaisa: Long,
    val expensesPaisa: Long,
    /** Lines sold on these days still waiting for a purchase to know their cost (D-16). */
    val pendingLines: Int,
)

data class ModeTotal(val mode: PaymentMode, val paisa: Long)

data class ItemSalesRow(
    val name: String,
    val unit: String,
    val trackingType: TrackingType,
    val qtyMilli: Long,
    val salesPaisa: Long,
    val costPaisa: Long,
)

data class CategoryTotal(val category: String, val paisa: Long)

/** Sales of one shop day (yyyyMMdd): ACTIVE bills − ACTIVE return refunds, like [ReportDao.summary]. */
data class DaySales(val day: Int, val paisa: Long)

/**
 * Business metrics (ARCHITECTURE.md § Business metrics, D-29, D-61). Ranges are inclusive
 * businessDay ints (yyyyMMdd, D-36); nothing here derives days from timestamps.
 */
@Dao
interface ReportDao {

    @Query(
        """SELECT
             COALESCE((SELECT SUM(totalPaisa) FROM sale WHERE status = 'ACTIVE' AND businessDay BETWEEN :from AND :to), 0) AS salesPaisa,
             (SELECT COUNT(*) FROM sale WHERE status = 'ACTIVE' AND businessDay BETWEEN :from AND :to) AS bills,
             COALESCE((SELECT SUM(refundPaisa) FROM sale_return WHERE status = 'ACTIVE' AND businessDay BETWEEN :from AND :to), 0) AS refundsPaisa,
             COALESCE((SELECT SUM(l.costPaisa + COALESCE((SELECT SUM(rl.costReversedPaisa) FROM sale_return_line rl
                                                             JOIN sale_return r ON r.id = rl.returnId
                                                             WHERE rl.saleLineId = l.id AND r.status = 'ACTIVE'), 0))
                       FROM sale_line l JOIN sale s ON s.id = l.saleId
                       WHERE s.status = 'ACTIVE' AND s.businessDay BETWEEN :from AND :to), 0) AS costPaisa,
             COALESCE((SELECT SUM(rl.costReversedPaisa) FROM sale_return_line rl JOIN sale_return r ON r.id = rl.returnId
                       WHERE r.status = 'ACTIVE' AND r.businessDay BETWEEN :from AND :to), 0) AS costBackPaisa,
             COALESCE((SELECT SUM(amountPaisa) FROM expense WHERE deletedAt IS NULL AND businessDay BETWEEN :from AND :to), 0) AS expensesPaisa,
             (SELECT COUNT(*) FROM sale_line l JOIN sale s ON s.id = l.saleId
              WHERE s.status = 'ACTIVE' AND l.pendingQtyMilli > 0 AND s.businessDay BETWEEN :from AND :to) AS pendingLines"""
    )
    fun summary(from: Int, to: Int): Flow<SummaryRow>

    /** Collection = money IN − money OUT by payment day, per mode (reversals included, D-29). */
    @Query(
        """SELECT mode, SUM(CASE WHEN direction = 'IN' THEN amountPaisa ELSE -amountPaisa END) AS paisa
           FROM payment WHERE businessDay BETWEEN :from AND :to GROUP BY mode"""
    )
    fun collection(from: Int, to: Int): Flow<List<ModeTotal>>

    /** Item-wise, by sale day, net of everything returned since. Amount is before the bill discount. */
    @Query(
        """SELECT p.name, p.unit, p.trackingType,
                  SUM(l.qtyMilli - $RETURNED_QTY) AS qtyMilli,
                  SUM(l.lineTotalPaisa * (l.qtyMilli - $RETURNED_QTY) / l.qtyMilli) AS salesPaisa,
                  SUM(l.costPaisa) AS costPaisa
           FROM sale_line l
           JOIN sale s ON s.id = l.saleId
           JOIN variant v ON v.id = l.variantId
           JOIN product p ON p.id = v.productId
           WHERE s.status = 'ACTIVE' AND s.businessDay BETWEEN :from AND :to AND l.qtyMilli > 0
           GROUP BY p.id
           HAVING SUM(l.qtyMilli - $RETURNED_QTY) > 0
           ORDER BY salesPaisa DESC"""
    )
    fun itemSales(from: Int, to: Int): Flow<List<ItemSalesRow>>

    @Query(
        """SELECT category, SUM(amountPaisa) AS paisa FROM expense
           WHERE deletedAt IS NULL AND businessDay BETWEEN :from AND :to
           GROUP BY category COLLATE NOCASE ORDER BY paisa DESC"""
    )
    fun expensesByCategory(from: Int, to: Int): Flow<List<CategoryTotal>>

    /** All-time: lines of ACTIVE sales still waiting for cost (Home "needs attention"). */
    /** Home's week chart: sales per day, refunds on the day they happened (D-61). Days with nothing are absent. */
    @Query(
        """SELECT day, SUM(amount) AS paisa FROM (
             SELECT businessDay AS day, totalPaisa AS amount FROM sale WHERE status = 'ACTIVE' AND businessDay BETWEEN :from AND :to
             UNION ALL
             SELECT businessDay, -refundPaisa FROM sale_return WHERE status = 'ACTIVE' AND businessDay BETWEEN :from AND :to)
           GROUP BY day"""
    )
    fun salesByDay(from: Int, to: Int): Flow<List<DaySales>>

    @Query("SELECT COUNT(*) FROM sale_line l JOIN sale s ON s.id = l.saleId WHERE s.status = 'ACTIVE' AND l.pendingQtyMilli > 0")
    fun pendingLines(): Flow<Int>
}

/** Quantity of sale line `l` taken back by ACTIVE returns. */
private const val RETURNED_QTY = """COALESCE((SELECT SUM(rl.qtyMilli) FROM sale_return_line rl
    JOIN sale_return r ON r.id = rl.returnId WHERE rl.saleLineId = l.id AND r.status = 'ACTIVE'), 0)"""
