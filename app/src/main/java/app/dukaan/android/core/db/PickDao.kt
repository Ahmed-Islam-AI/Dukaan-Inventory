package app.dukaan.android.core.db

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * One sellable / stockable item in the category picker (D-85). [serialUnitId] / [serial] are set
 * only when a search matched one unit's serial (IMEI) in stock.
 */
data class PickItem(
    val variantId: String,
    val productId: String,
    val productName: String,
    val variantLabel: String,
    val trackingType: TrackingType,
    val unit: String,
    val allowDecimalQty: Boolean,
    val salePricePaisa: Long,
    /** Usual buying price, else the newest batch's cost (old items). */
    val purchasePricePaisa: Long?,
    val onHandMilli: Long,
    val barcode: String?,
    val serialUnitId: String?,
    val serial: String?,
) {
    val name get() = if (variantLabel == "Default") productName else "$productName $variantLabel"
}

/** A category with how many items it holds; id/name null = "Other" (items without a category). */
data class CategoryCount(val id: String?, val name: String?, val items: Int)

private const val PICK_COLUMNS = """v.id AS variantId, p.id AS productId, p.name AS productName, v.label AS variantLabel,
    p.trackingType, p.unit, p.allowDecimalQty, v.salePricePaisa,
    COALESCE(v.purchasePricePaisa,
        (SELECT l.unitCostPaisa FROM stock_lot l WHERE l.variantId = v.id ORDER BY l.receivedAt DESC, l.createdAt DESC LIMIT 1),
        (SELECT s.costPaisa FROM serial_unit s WHERE s.variantId = v.id ORDER BY s.createdAt DESC LIMIT 1)) AS purchasePricePaisa,
    v.onHandMilli, v.barcode"""

private const val LIVE = "v.deletedAt IS NULL AND p.deletedAt IS NULL"

/** Items without a live category belong to "Other". */
private const val NO_CATEGORY = "(p.categoryId IS NULL OR p.categoryId NOT IN (SELECT id FROM category WHERE deletedAt IS NULL))"

/** Reads for the category → item picker used by Sell and Add stock (D-85). */
@Dao
interface PickDao {
    @Query(
        """SELECT id, name, items FROM (
             SELECT c.id AS id, c.name AS name,
                    (SELECT COUNT(*) FROM product p WHERE p.categoryId = c.id AND p.deletedAt IS NULL) AS items
             FROM category c WHERE c.deletedAt IS NULL
             UNION ALL
             SELECT NULL, NULL, (SELECT COUNT(*) FROM product p WHERE p.deletedAt IS NULL AND $NO_CATEGORY))
           ORDER BY name IS NULL, name COLLATE NOCASE"""
    )
    fun categories(): Flow<List<CategoryCount>>

    /** [categoryId] null = "Other". */
    @Query(
        """SELECT $PICK_COLUMNS, NULL AS serialUnitId, NULL AS serial
           FROM variant v JOIN product p ON p.id = v.productId
           WHERE $LIVE AND ((:categoryId IS NULL AND $NO_CATEGORY) OR p.categoryId = :categoryId)
           ORDER BY p.name COLLATE NOCASE, v.label COLLATE NOCASE"""
    )
    fun inCategory(categoryId: String?): Flow<List<PickItem>>

    /** Name, barcode, SKU, or a serial (IMEI) in stock (then serialUnitId is set). */
    @Query(
        """SELECT $PICK_COLUMNS,
                  (SELECT s.id FROM serial_unit s WHERE s.variantId = v.id AND s.liveSerial = :query) AS serialUnitId,
                  (SELECT s.serial FROM serial_unit s WHERE s.variantId = v.id AND s.liveSerial = :query) AS serial
           FROM variant v JOIN product p ON p.id = v.productId
           WHERE $LIVE
             AND (p.name LIKE '%' || :query || '%' OR v.label LIKE '%' || :query || '%'
                  OR v.barcode = :query OR v.sku = :query
                  OR EXISTS (SELECT 1 FROM serial_unit s WHERE s.variantId = v.id AND s.liveSerial = :query))
           ORDER BY (serialUnitId IS NULL), (v.barcode IS NOT :query), p.name COLLATE NOCASE, v.label COLLATE NOCASE LIMIT 40"""
    )
    fun search(query: String): Flow<List<PickItem>>

    @Query(
        """SELECT $PICK_COLUMNS, NULL AS serialUnitId, NULL AS serial
           FROM variant v JOIN product p ON p.id = v.productId
           WHERE p.id = :productId AND v.deletedAt IS NULL ORDER BY v.createdAt, v.id"""
    )
    suspend fun forProduct(productId: String): List<PickItem>

    @Query("SELECT * FROM serial_unit WHERE variantId = :variantId AND status = 'IN_STOCK' ORDER BY createdAt")
    suspend fun inStockUnits(variantId: String): List<SerialUnitEntity>
}
