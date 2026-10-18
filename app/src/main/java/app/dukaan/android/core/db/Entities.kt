package app.dukaan.android.core.db

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/*
 * Schema v1. Mirrors docs/ARCHITECTURE.md "Data model"; change both together.
 *
 * Sync-ready rules (D-13, docs/SYNC.md):
 *  - Every id is a UUID string, generated on the device. No auto-increment.
 *  - Every row carries [SyncMeta]. Nothing is ever DELETEd.
 *  - Soft delete (deletedAt) is ONLY for master data: category, product, variant, party, and
 *    expense (no stock or payment rows hang off it, D-59).
 *    Every other table is ledger: never deleted or soft-deleted. A void sets status VOIDED +
 *    voidedAt and writes reversal rows (movements, payments). Ledger queries therefore never
 *    filter on deletedAt (D-27).
 *  - No SQL foreign keys: after sync, rows can arrive in any order. Integrity is enforced in
 *    LedgerService transactions and checked by the invariant tests instead.
 *
 * Units (never Float/Double anywhere):
 *  - `...Paisa`  money, Long minor units of the shop currency (core/money/Money).
 *  - `...Milli`  quantity, Long thousandths of the item's unit: 2.5 kg = 2500, 1 phone = 1000
 *                (core/money/Qty, D-39). SERIAL items always move in 1000s.
 *  - `businessDay` Int yyyyMMdd in the shop's time zone, fixed when the row is written.
 *                Reports group by it and never recompute zones from timestamps (D-36).
 *
 * Until the first build reaches the client (M2) this schema may change in place at version 1.
 * After that, every change is a new version with a tested Migration. Never destructive.
 */

data class SyncMeta(
    val createdAt: Long,
    val updatedAt: Long,
    /** Master data only (D-27). Always null on ledger tables. */
    val deletedAt: Long? = null,
    val deviceId: String,
)

enum class TrackingType { QUANTITY, SERIAL }
enum class PartyType { CUSTOMER, SUPPLIER }
enum class DocStatus { ACTIVE, VOIDED }
/** VOIDED = its purchase was voided; the unit never really entered stock. */
enum class SerialStatus { IN_STOCK, SOLD, DAMAGED, RETURNED_TO_SUPPLIER, VOIDED }
enum class PaymentDirection { IN, OUT }
enum class PaymentMode { CASH, BANK, WALLET }
/** What a payment is for (D-38). REVERSAL always has reversesPaymentId. */
enum class PaymentKind { SALE, PURCHASE, REFUND, PARTY, REVERSAL }
enum class MovementType { OPENING, PURCHASE, PURCHASE_VOID, SALE, VOID, RETURN, RETURN_VOID, ADJUST }
/** A4 / PDF invoice look (D-80). Stored by name: never rename without a migration. */
enum class PageTemplate { MODERN, CLASSIC, MINIMAL }
/** Thermal printer slip look (D-80). */
enum class SlipTemplate { CLASSIC, COMPACT, BOLD }
/** Where a FIFO lot came from (D-34). */
enum class LotSource { PURCHASE, OPENING, ADJUSTMENT, RETURN }
/** What consumed a lot (D-34). */
enum class AllocationConsumer { SALE_LINE, ADJUSTMENT }

// ------------------------------------------------------------------ master data (soft-deletable)

@Entity(tableName = "shop_profile")
data class ShopProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val shopType: String,
    val phone: String?,
    val address: String?,
    /** Copied into filesDir: a picker URI does not survive reinstall or restore. */
    val logoPath: String?,
    val currencyCode: String,
    val currencySymbol: String,
    /** IANA zone used to compute businessDay, e.g. "Asia/Karachi". */
    val timeZone: String,
    /** Basis points (1800 = 18%). Null = tax off, the default (D-08). */
    val taxRateBp: Int?,
    val receiptFooter: String?,
    @Embedded val meta: SyncMeta,
    // Invoice design (v3, D-80): part of the shop row so every phone prints the same invoice.
    @ColumnInfo(defaultValue = "MODERN") val pageTemplate: PageTemplate = PageTemplate.MODERN,
    @ColumnInfo(defaultValue = "CLASSIC") val slipTemplate: SlipTemplate = SlipTemplate.CLASSIC,
    /** Brand colour, 0xRRGGBB (saffron by default). */
    @ColumnInfo(defaultValue = "15227148") val accentRgb: Int = 0xE8590C,
    /** NTN / STRN / GST number, printed under the address. */
    val taxNumber: String? = null,
    /** Terms, warranty or return policy, printed above the footer. */
    val invoiceTerms: String? = null,
    /** Logo as a small PNG in base64, so it syncs with this row (logoPath, a local file, can't). */
    val logoPng: String? = null,
)

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @Embedded val meta: SyncMeta,
)

@Entity(tableName = "product", indices = [Index("categoryId"), Index("name")])
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val categoryId: String?,
    val trackingType: TrackingType,
    /** Display unit: pcs, kg, m, box. Free text, shop-defined. */
    val unit: String,
    /** False = whole units only (qtyMilli multiple of 1000). Always false for SERIAL. */
    val allowDecimalQty: Boolean,
    @Embedded val meta: SyncMeta,
)

/** Every product has at least one variant ("Default"). Stock and price live here. */
@Entity(tableName = "variant", indices = [Index("productId"), Index("barcode"), Index("sku")])
data class VariantEntity(
    @PrimaryKey val id: String,
    val productId: String,
    val label: String,
    val sku: String?,
    val barcode: String?,
    /** Price per 1 unit (per 1000 milli). */
    val salePricePaisa: Long,
    val lowStockMilli: Long?,
    /** Cache of SUM(stock_movement.qtyDeltaMilli). Same transaction. May be negative (D-14). */
    val onHandMilli: Long,
    @Embedded val meta: SyncMeta,
    /**
     * What the shop usually pays for 1 unit (v4, D-85): pre-fills Add stock and shows profit per unit.
     * Only a default: profit always uses what each batch really cost (FIFO lots, D-15).
     */
    val purchasePricePaisa: Long? = null,
)

@Entity(tableName = "party", indices = [Index("type", "name")])
data class PartyEntity(
    @PrimaryKey val id: String,
    val type: PartyType,
    val name: String,
    val phone: String?,
    /** Positive = they owe the shop. */
    val openingBalancePaisa: Long,
    @Embedded val meta: SyncMeta,
)

// ------------------------------------------------------------------ ledger (append-only)

@Entity(tableName = "purchase", indices = [Index("supplierId"), Index("businessDay")])
data class PurchaseEntity(
    @PrimaryKey val id: String,
    val supplierId: String?,
    val billNo: String?,
    val date: Long,
    val businessDay: Int,
    val totalPaisa: Long,
    val status: DocStatus,
    val voidReason: String?,
    val voidedAt: Long?,
    @Embedded val meta: SyncMeta,
)

@Entity(tableName = "purchase_line", indices = [Index("purchaseId"), Index("variantId")])
data class PurchaseLineEntity(
    @PrimaryKey val id: String,
    val purchaseId: String,
    val variantId: String,
    val qtyMilli: Long,
    /** Cost per 1 unit. */
    val unitCostPaisa: Long,
    @Embedded val meta: SyncMeta,
)

/**
 * FIFO source for QUANTITY items (D-15, D-34). Consumed in (receivedAt, createdAt, id) order so
 * ties are deterministic. A voided purchase sets qtyRemainingMilli = 0; nothing is deleted.
 */
@Entity(
    tableName = "stock_lot",
    indices = [Index("variantId", "receivedAt", "createdAt"), Index("sourceType", "sourceId")],
)
data class StockLotEntity(
    @PrimaryKey val id: String,
    val variantId: String,
    val sourceType: LotSource,
    /** purchase_line / stock_adjustment / sale_return_line id; null for OPENING. */
    val sourceId: String?,
    val qtyInMilli: Long,
    val qtyRemainingMilli: Long,
    val unitCostPaisa: Long,
    val receivedAt: Long,
    @Embedded val meta: SyncMeta,
)

/**
 * One physical unit (IMEI/serial) for one ownership cycle. A buy-back of a sold phone is a NEW
 * row with its own cost and purchase (D-33); the old row stays SOLD forever, so voiding or
 * returning the old sale touches only that row.
 *
 * [liveSerial] = serial while IN_STOCK, else null. Its unique index makes "same IMEI in stock
 * twice" impossible; SQLite allows many NULLs in a unique index (D-35). No raw SQL needed.
 */
@Entity(
    tableName = "serial_unit",
    indices = [
        Index("serial"),
        Index("liveSerial", unique = true),
        Index("variantId", "status"),
        Index("sourceId"),
    ],
)
data class SerialUnitEntity(
    @PrimaryKey val id: String,
    val serial: String,
    val liveSerial: String?,
    val variantId: String,
    val sourceType: LotSource,
    val sourceId: String?,
    val costPaisa: Long,
    val status: SerialStatus,
    @Embedded val meta: SyncMeta,
)

/** Append-only lifecycle history of a serial unit: what happened, by which document. */
@Entity(tableName = "serial_event", indices = [Index("serialUnitId")])
data class SerialEventEntity(
    @PrimaryKey val id: String,
    val serialUnitId: String,
    val status: SerialStatus,
    val refId: String,
    @Embedded val meta: SyncMeta,
)

/** Number is "$invoicePrefix-$invoiceSeq"; next seq = MAX(seq for prefix) + 1 (D-18). */
@Entity(
    tableName = "sale",
    indices = [
        // Not unique since sync (D-77): two phones sharing a prefix offline must not block a merge;
        // the owner sees the clash instead. Locally MAX(seq) + 1 still never repeats a number.
        Index("invoicePrefix", "invoiceSeq"),
        Index("customerId"),
        Index("businessDay"),
    ],
)
data class SaleEntity(
    @PrimaryKey val id: String,
    val invoicePrefix: String,
    val invoiceSeq: Int,
    val customerId: String?,
    val date: Long,
    val businessDay: Int,
    val subtotalPaisa: Long,
    /** Invoice-level discount, applied before tax (D-37). */
    val discountPaisa: Long,
    /** Snapshot of the rate used, so reprints stay correct after the shop changes it. */
    val taxRateBp: Int?,
    val taxPaisa: Long,
    val totalPaisa: Long,
    val status: DocStatus,
    val voidReason: String?,
    val voidedAt: Long?,
    @Embedded val meta: SyncMeta,
)

// (variantId, pendingQtyMilli): purchase looks up "pending lines for this variant" to fill cost.
@Entity(tableName = "sale_line", indices = [Index("saleId"), Index("variantId", "pendingQtyMilli")])
data class SaleLineEntity(
    @PrimaryKey val id: String,
    val saleId: String,
    val variantId: String,
    /** Snapshot: renaming or repricing an item later must not change old receipts. */
    val nameSnapshot: String,
    val qtyMilli: Long,
    val unitPricePaisa: Long,
    /** Line total before invoice discount/tax = round(unitPrice × qty). */
    val lineTotalPaisa: Long,
    /** Known cost so far (sum of allocations, or the serial unit's cost). */
    val costPaisa: Long,
    /** Quantity sold with no lot to cost it yet (D-16). Filled by the next lot. */
    val pendingQtyMilli: Long,
    /** SERIAL lines: exactly one unit, qtyMilli = 1000. */
    val serialUnitId: String?,
    @Embedded val meta: SyncMeta,
)

/**
 * Which lot paid for what (D-34): a sale line, or a negative stock adjustment.
 * Reversal (void/return) increases the lot back and records `reversedQtyMilli` here, so a row
 * can never be reversed twice.
 */
@Entity(
    tableName = "lot_allocation",
    indices = [Index("consumerType", "consumerId"), Index("lotId")],
)
data class LotAllocationEntity(
    @PrimaryKey val id: String,
    val lotId: String,
    val consumerType: AllocationConsumer,
    val consumerId: String,
    val qtyMilli: Long,
    val reversedQtyMilli: Long,
    val unitCostPaisa: Long,
    @Embedded val meta: SyncMeta,
)

@Entity(tableName = "sale_return", indices = [Index("saleId"), Index("businessDay")])
data class SaleReturnEntity(
    @PrimaryKey val id: String,
    val saleId: String,
    val date: Long,
    val businessDay: Int,
    val refundPaisa: Long,
    val reason: String?,
    /** A wrong return can be voided (D-38); otherwise its sale could never be fixed. */
    val status: DocStatus,
    val voidReason: String?,
    val voidedAt: Long?,
    @Embedded val meta: SyncMeta,
)

@Entity(tableName = "sale_return_line", indices = [Index("returnId"), Index("saleLineId")])
data class SaleReturnLineEntity(
    @PrimaryKey val id: String,
    val returnId: String,
    val saleLineId: String,
    val qtyMilli: Long,
    /**
     * Cumulative rounding (D-37): round(lineNet × returnedSoFar / qty) − refundedSoFar, so any
     * number of partial returns of a line sums to exactly its net.
     */
    val refundPaisa: Long,
    val costReversedPaisa: Long,
    @Embedded val meta: SyncMeta,
)

@Entity(tableName = "stock_adjustment", indices = [Index("variantId"), Index("businessDay")])
data class StockAdjustmentEntity(
    @PrimaryKey val id: String,
    val variantId: String,
    val qtyDeltaMilli: Long,
    /** Cost per unit for a positive adjustment's new lot; null for negative (consumes FIFO). */
    val unitCostPaisa: Long?,
    val reason: String,
    val date: Long,
    val businessDay: Int,
    @Embedded val meta: SyncMeta,
)

/**
 * Money in or out (D-29, D-38). Collection = Σ IN − Σ OUT by businessDay.
 * A REVERSAL points at the payment it cancels; the unique index makes a double reversal impossible.
 */
@Entity(
    tableName = "payment",
    indices = [
        Index("partyId"), Index("saleId"), Index("purchaseId"), Index("returnId"),
        Index("businessDay"),
        Index("reversesPaymentId", unique = true),
    ],
)
data class PaymentEntity(
    @PrimaryKey val id: String,
    val kind: PaymentKind,
    val partyId: String?,
    val saleId: String?,
    val purchaseId: String?,
    val returnId: String?,
    val reversesPaymentId: String?,
    val direction: PaymentDirection,
    val mode: PaymentMode,
    val amountPaisa: Long,
    val date: Long,
    val businessDay: Int,
    val note: String?,
    @Embedded val meta: SyncMeta,
)

@Entity(tableName = "expense", indices = [Index("businessDay")])
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val category: String,
    val amountPaisa: Long,
    val date: Long,
    val businessDay: Int,
    val note: String?,
    @Embedded val meta: SyncMeta,
)

/** The stock ledger. Append-only; onHandMilli is derived from it. */
@Entity(tableName = "stock_movement", indices = [Index("variantId"), Index("refId")])
data class StockMovementEntity(
    @PrimaryKey val id: String,
    val variantId: String,
    val type: MovementType,
    val qtyDeltaMilli: Long,
    val refId: String,
    @Embedded val meta: SyncMeta,
)

// ------------------------------------------------------------------ sync bookkeeping (device-local)
// Not business data: no UUID, no SyncMeta, never synced (D-77). They travel inside snapshots and
// cloud copies on purpose, so a restored copy knows what it already holds.

/** Rows changed on this phone and not yet pushed. Filled by triggers (SyncTables); see core/sync. */
@Entity(tableName = "sync_outbox")
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val tableName: String,
    val rowId: String,
)

/** One row (id = 1): how far this database has pulled the shop's change log. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    /** Server time (microseconds) of the last change applied; 0 = none yet. */
    val pulledUpTo: Long,
    /** Every existing row has been queued once (first sync on this database). */
    val seeded: Boolean,
)
