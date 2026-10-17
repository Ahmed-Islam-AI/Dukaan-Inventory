package app.dukaan.android.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

/*
 * No @Upsert (exception-driven in Room; breaks JVM tests). Plain @Insert aborts on conflict,
 * which is what LedgerService wants: a duplicate is a bug or a user error, never a silent replace.
 *
 * Ledger tables are never soft-deleted (D-27), so ledger queries never filter on deletedAt.
 * Master data (category, product, variant, party) always does.
 */

/** Writes and reads used only inside LedgerService transactions. */
@Dao
interface LedgerDao {
    @Insert suspend fun insertCategory(row: CategoryEntity)
    @Insert suspend fun insertProduct(row: ProductEntity)
    @Insert suspend fun insertVariant(row: VariantEntity)
    @Insert suspend fun insertParty(row: PartyEntity)
    @Insert suspend fun insertPurchase(row: PurchaseEntity)
    @Insert suspend fun insertPurchaseLine(row: PurchaseLineEntity)
    @Insert suspend fun insertLot(row: StockLotEntity)
    @Insert suspend fun insertSerial(row: SerialUnitEntity)
    @Insert suspend fun insertSerialEvent(row: SerialEventEntity)
    @Insert suspend fun insertAdjustment(row: StockAdjustmentEntity)
    @Insert suspend fun insertPayment(row: PaymentEntity)
    @Insert suspend fun insertMovement(row: StockMovementEntity)
    @Insert suspend fun insertSale(row: SaleEntity)
    @Insert suspend fun insertSaleLine(row: SaleLineEntity)

    /** D-18: next number = MAX + 1 for this device's prefix; the unique index is the backstop. */
    @Query("SELECT MAX(invoiceSeq) FROM sale WHERE invoicePrefix = :prefix")
    suspend fun maxInvoiceSeq(prefix: String): Int?

    @Query("SELECT * FROM sale WHERE id = :id")
    suspend fun sale(id: String): SaleEntity?

    @Update suspend fun updateProduct(row: ProductEntity)
    @Update suspend fun updateVariant(row: VariantEntity)
    @Update suspend fun updatePurchase(row: PurchaseEntity)

    @Query("SELECT * FROM product WHERE id = :id")
    suspend fun product(id: String): ProductEntity?

    @Query("SELECT * FROM variant WHERE id = :id")
    suspend fun variant(id: String): VariantEntity?

    @Query("SELECT * FROM purchase WHERE id = :id")
    suspend fun purchase(id: String): PurchaseEntity?

    @Query("SELECT * FROM purchase_line WHERE purchaseId = :purchaseId")
    suspend fun purchaseLines(purchaseId: String): List<PurchaseLineEntity>

    @Query("SELECT * FROM category WHERE deletedAt IS NULL AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun categoryByName(name: String): CategoryEntity?

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun categoryById(id: String): CategoryEntity?

    @Query("SELECT * FROM party WHERE deletedAt IS NULL AND type = :type AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun partyByName(type: PartyType, name: String): PartyEntity?

    /** FIFO order (D-36): received, then created, then id so ties are deterministic. */
    @Query(
        """SELECT * FROM stock_lot WHERE variantId = :variantId AND qtyRemainingMilli > 0
           ORDER BY receivedAt, createdAt, id"""
    )
    suspend fun openLots(variantId: String): List<StockLotEntity>

    @Query("SELECT * FROM stock_lot WHERE sourceType = 'PURCHASE' AND sourceId IN (:lineIds)")
    suspend fun lotsForPurchaseLines(lineIds: List<String>): List<StockLotEntity>

    @Query("SELECT * FROM serial_unit WHERE sourceType = 'PURCHASE' AND sourceId IN (:lineIds)")
    suspend fun serialsForPurchaseLines(lineIds: List<String>): List<SerialUnitEntity>

    @Query("SELECT * FROM serial_unit WHERE id = :id")
    suspend fun serial(id: String): SerialUnitEntity?

    @Query("SELECT serial FROM serial_unit WHERE liveSerial IN (:serials)")
    suspend fun liveSerials(serials: List<String>): List<String>

    /** Pending sale lines of a variant, oldest sale first (D-16). Voided sales never get cost. */
    @Query(
        """SELECT sl.* FROM sale_line sl JOIN sale s ON s.id = sl.saleId
           WHERE sl.variantId = :variantId AND sl.pendingQtyMilli > 0 AND s.status = 'ACTIVE'
           ORDER BY s.date, s.createdAt, sl.id"""
    )
    suspend fun pendingSaleLines(variantId: String): List<SaleLineEntity>

    @Query("SELECT * FROM payment WHERE purchaseId = :purchaseId AND kind = 'PURCHASE'")
    suspend fun purchasePayments(purchaseId: String): List<PaymentEntity>

    @Query("SELECT COUNT(*) FROM payment WHERE reversesPaymentId = :paymentId")
    suspend fun reversalCount(paymentId: String): Int

    /** Last purchase cost per unit for a variant, used as the default cost. */
    @Query(
        """SELECT unitCostPaisa FROM stock_lot WHERE variantId = :variantId
           ORDER BY receivedAt DESC, createdAt DESC LIMIT 1"""
    )
    suspend fun lastUnitCost(variantId: String): Long?

    // ---------------------------------------------------------------- M4: void, return, parties, expenses

    @Insert suspend fun insertReturn(row: SaleReturnEntity)
    @Insert suspend fun insertReturnLine(row: SaleReturnLineEntity)
    @Insert suspend fun insertExpense(row: ExpenseEntity)
    @Update suspend fun updateSale(row: SaleEntity)
    @Update suspend fun updateReturn(row: SaleReturnEntity)
    @Update suspend fun updateParty(row: PartyEntity)
    @Update suspend fun updateExpense(row: ExpenseEntity)

    @Query("SELECT * FROM sale_line WHERE saleId = :saleId ORDER BY createdAt, id")
    suspend fun saleLines(saleId: String): List<SaleLineEntity>

    @Query("SELECT * FROM sale_return WHERE id = :id")
    suspend fun saleReturn(id: String): SaleReturnEntity?

    @Query("SELECT * FROM sale_return_line WHERE returnId = :returnId")
    suspend fun returnLines(returnId: String): List<SaleReturnLineEntity>

    @Query("SELECT COUNT(*) FROM sale_return WHERE saleId = :saleId AND status = 'ACTIVE'")
    suspend fun activeReturnCount(saleId: String): Int

    @Query(
        """SELECT COALESCE(SUM(l.qtyMilli), 0) FROM sale_return_line l JOIN sale_return r ON r.id = l.returnId
           WHERE l.saleLineId = :lineId AND r.status = 'ACTIVE'"""
    )
    suspend fun returnedQtyMilli(lineId: String): Long

    @Query(
        """SELECT COALESCE(SUM(l.refundPaisa), 0) FROM sale_return_line l JOIN sale_return r ON r.id = l.returnId
           WHERE l.saleLineId = :lineId AND r.status = 'ACTIVE'"""
    )
    suspend fun refundedPaisa(lineId: String): Long

    @Query("SELECT * FROM payment WHERE saleId = :saleId AND kind = 'SALE'")
    suspend fun salePayments(saleId: String): List<PaymentEntity>

    @Query("SELECT * FROM payment WHERE returnId = :returnId AND kind = 'REFUND'")
    suspend fun returnPayments(returnId: String): List<PaymentEntity>

    @Query("SELECT * FROM payment WHERE id = :id")
    suspend fun payment(id: String): PaymentEntity?

    @Query("SELECT * FROM party WHERE id = :id")
    suspend fun party(id: String): PartyEntity?

    @Query("SELECT * FROM expense WHERE id = :id")
    suspend fun expense(id: String): ExpenseEntity?

    @Query("SELECT $PARTY_BALANCE FROM party p WHERE p.id = :partyId")
    suspend fun partyBalance(partyId: String): Long?
}

/**
 * What a party owes the shop (negative = the shop owes them), ARCHITECTURE.md § Business metrics:
 * opening + ACTIVE sales − ACTIVE return refunds − ACTIVE purchases + payments OUT − payments IN.
 * Reversal payments carry the opposite direction, so voids cancel out.
 */
const val PARTY_BALANCE = """p.openingBalancePaisa
    + COALESCE((SELECT SUM(s.totalPaisa) FROM sale s WHERE s.customerId = p.id AND s.status = 'ACTIVE'), 0)
    - COALESCE((SELECT SUM(r.refundPaisa) FROM sale_return r JOIN sale s ON s.id = r.saleId
                WHERE s.customerId = p.id AND r.status = 'ACTIVE'), 0)
    - COALESCE((SELECT SUM(pu.totalPaisa) FROM purchase pu WHERE pu.supplierId = p.id AND pu.status = 'ACTIVE'), 0)
    + COALESCE((SELECT SUM(CASE WHEN pay.direction = 'OUT' THEN pay.amountPaisa ELSE -pay.amountPaisa END)
                FROM payment pay WHERE pay.partyId = p.id), 0)"""

// ------------------------------------------------------------------ read models for the UI

data class ItemRow(
    val productId: String,
    val name: String,
    val trackingType: TrackingType,
    val unit: String,
    val variantCount: Int,
    val minPricePaisa: Long,
    val maxPricePaisa: Long,
    val onHandMilli: Long,
    val lowCount: Int,
    /** Null when the item has no (live) category: listed under "Other". */
    val categoryName: String? = null,
    /** Lowest usual purchase price among its variants (D-85). */
    val minPurchasePaisa: Long? = null,
)

data class MovementRow(
    val id: String,
    val variantLabel: String,
    val type: MovementType,
    val qtyDeltaMilli: Long,
    val createdAt: Long,
)

data class PurchaseRow(
    val id: String,
    val supplierName: String?,
    val billNo: String?,
    val date: Long,
    val totalPaisa: Long,
    val status: DocStatus,
    val lineCount: Int,
)

/** Reads for screens. Flows re-emit when the underlying tables change. */
@Dao
interface CatalogDao {

    @Query(
        """SELECT p.id AS productId, p.name, p.trackingType, p.unit,
                  COUNT(v.id) AS variantCount,
                  MIN(v.salePricePaisa) AS minPricePaisa, MAX(v.salePricePaisa) AS maxPricePaisa,
                  SUM(v.onHandMilli) AS onHandMilli,
                  SUM(CASE WHEN v.lowStockMilli IS NOT NULL AND v.onHandMilli <= v.lowStockMilli
                           THEN 1 ELSE 0 END) AS lowCount,
                  (SELECT c.name FROM category c WHERE c.id = p.categoryId AND c.deletedAt IS NULL) AS categoryName,
                  MIN(v.purchasePricePaisa) AS minPurchasePaisa
           FROM product p JOIN variant v ON v.productId = p.id AND v.deletedAt IS NULL
           WHERE p.deletedAt IS NULL
             AND (:query = '' OR p.name LIKE '%' || :query || '%' OR v.barcode = :query
                  OR v.sku = :query
                  OR EXISTS (SELECT 1 FROM serial_unit s WHERE s.variantId = v.id AND s.liveSerial = :query))
           GROUP BY p.id
           ORDER BY p.name COLLATE NOCASE"""
    )
    fun items(query: String): Flow<List<ItemRow>>

    /** Stock value at cost (D-29): open lots + in-stock serial units. */
    @Query(
        """SELECT COALESCE((SELECT SUM(qtyRemainingMilli * unitCostPaisa / 1000) FROM stock_lot WHERE qtyRemainingMilli > 0), 0)
                + COALESCE((SELECT SUM(costPaisa) FROM serial_unit WHERE status = 'IN_STOCK'), 0)"""
    )
    fun stockValuePaisa(): Flow<Long>

    @Query("SELECT * FROM product WHERE id = :id")
    fun product(id: String): Flow<ProductEntity?>

    @Query("SELECT * FROM variant WHERE productId = :productId AND deletedAt IS NULL ORDER BY createdAt, id")
    fun variants(productId: String): Flow<List<VariantEntity>>

    @Query(
        """SELECT s.* FROM serial_unit s JOIN variant v ON v.id = s.variantId
           WHERE v.productId = :productId AND s.status = 'IN_STOCK' ORDER BY s.createdAt"""
    )
    fun inStockSerials(productId: String): Flow<List<SerialUnitEntity>>

    @Query(
        """SELECT m.id, v.label AS variantLabel, m.type, m.qtyDeltaMilli, m.createdAt
           FROM stock_movement m JOIN variant v ON v.id = m.variantId
           WHERE v.productId = :productId ORDER BY m.createdAt DESC, m.id DESC LIMIT 200"""
    )
    fun movements(productId: String): Flow<List<MovementRow>>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun category(id: String): CategoryEntity?

    @Query("SELECT name FROM category WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun categoryNames(): Flow<List<String>>

    @Query("SELECT name FROM party WHERE deletedAt IS NULL AND type = 'SUPPLIER' ORDER BY name COLLATE NOCASE")
    fun supplierNames(): Flow<List<String>>

    @Query(
        """SELECT pu.id, pa.name AS supplierName, pu.billNo, pu.date, pu.totalPaisa, pu.status,
                  (SELECT COUNT(*) FROM purchase_line l WHERE l.purchaseId = pu.id) AS lineCount
           FROM purchase pu LEFT JOIN party pa ON pa.id = pu.supplierId
           ORDER BY pu.date DESC, pu.createdAt DESC"""
    )
    fun purchases(): Flow<List<PurchaseRow>>

    @Query("SELECT * FROM purchase WHERE id = :id")
    fun purchase(id: String): Flow<PurchaseEntity?>

    @Query("SELECT p.name FROM purchase pu JOIN party p ON p.id = pu.supplierId WHERE pu.id = :id")
    fun purchaseSupplier(id: String): Flow<String?>

    @Query(
        """SELECT l.id AS lineId, p.name AS productName, v.label AS variantLabel, l.qtyMilli,
                  l.unitCostPaisa, p.unit, p.trackingType
           FROM purchase_line l JOIN variant v ON v.id = l.variantId JOIN product p ON p.id = v.productId
           WHERE l.purchaseId = :purchaseId ORDER BY l.createdAt, l.id"""
    )
    fun purchaseLines(purchaseId: String): Flow<List<PurchaseLineRow>>
}

data class PurchaseLineRow(
    val lineId: String,
    val productName: String,
    val variantLabel: String,
    val qtyMilli: Long,
    val unitCostPaisa: Long,
    val unit: String,
    val trackingType: TrackingType,
)

/** Whole-table reads for the invariant checker. Shop-scale data; see InvariantChecker. */
@Dao
interface AuditDao {
    @Query("SELECT * FROM variant") suspend fun variants(): List<VariantEntity>
    @Query("SELECT * FROM product") suspend fun products(): List<ProductEntity>
    @Query("SELECT * FROM stock_movement") suspend fun movements(): List<StockMovementEntity>
    @Query("SELECT * FROM stock_lot") suspend fun lots(): List<StockLotEntity>
    @Query("SELECT * FROM lot_allocation") suspend fun allocations(): List<LotAllocationEntity>
    @Query("SELECT * FROM serial_unit") suspend fun serials(): List<SerialUnitEntity>
    @Query("SELECT * FROM sale_line") suspend fun saleLines(): List<SaleLineEntity>
    @Query("SELECT * FROM sale") suspend fun sales(): List<SaleEntity>
    @Query("SELECT * FROM purchase_line") suspend fun purchaseLines(): List<PurchaseLineEntity>
    @Query("SELECT * FROM purchase") suspend fun purchases(): List<PurchaseEntity>
    @Query("SELECT * FROM sale_return") suspend fun returns(): List<SaleReturnEntity>
    @Query("SELECT * FROM sale_return_line") suspend fun returnLines(): List<SaleReturnLineEntity>
    @Query("SELECT * FROM payment") suspend fun payments(): List<PaymentEntity>

    // Test helpers for M1 (sales arrive in M2): insert pending sale lines directly.
    @Insert suspend fun insertSale(row: SaleEntity)
    @Insert suspend fun insertSaleLine(row: SaleLineEntity)
    @Query("SELECT * FROM sale_line WHERE id = :id") suspend fun saleLine(id: String): SaleLineEntity?
}
