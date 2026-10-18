package app.dukaan.android.core.ledger

import androidx.room3.withWriteTransaction
import app.dukaan.android.core.db.CategoryEntity
import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.DukaanDatabase
import app.dukaan.android.core.db.ExpenseEntity
import app.dukaan.android.core.db.LedgerDao
import app.dukaan.android.core.db.LotSource
import app.dukaan.android.core.db.MovementType
import app.dukaan.android.core.db.PartyEntity
import app.dukaan.android.core.db.PartyType
import app.dukaan.android.core.db.PaymentDirection
import app.dukaan.android.core.db.PaymentEntity
import app.dukaan.android.core.db.PaymentKind
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.ProductEntity
import app.dukaan.android.core.db.SaleEntity
import app.dukaan.android.core.db.SaleLineEntity
import app.dukaan.android.core.db.SaleReturnEntity
import app.dukaan.android.core.db.SaleReturnLineEntity
import app.dukaan.android.core.db.PurchaseEntity
import app.dukaan.android.core.db.PurchaseLineEntity
import app.dukaan.android.core.db.SerialEventEntity
import app.dukaan.android.core.db.SerialStatus
import app.dukaan.android.core.db.SerialUnitEntity
import app.dukaan.android.core.db.StockAdjustmentEntity
import app.dukaan.android.core.db.StockLotEntity
import app.dukaan.android.core.db.StockMovementEntity
import app.dukaan.android.core.db.SyncMeta
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.db.VariantEntity
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.time.businessDay
import java.time.ZoneId
import java.util.UUID

/** "A-0042": 4-digit pad, grows past 9999 (D-18). */
fun invoiceNumber(prefix: String, seq: Int) = "$prefix-${seq.toString().padStart(4, '0')}"

/** Named failures (no catch-all). The UI maps each to a specific message. */
sealed class LedgerException(message: String) : Exception(message) {
    class DuplicateSerial(val serials: List<String>) : LedgerException("Already in stock: $serials")
    class InvalidInput(val field: String, val reason: String) : LedgerException("$field: $reason")
    class NotFound(val what: String) : LedgerException("Not found: $what")
    class PurchaseStockUsed : LedgerException("Items from this purchase were sold or adjusted")
    class AlreadyVoided : LedgerException("Already voided")
    class InsufficientStock(val availableMilli: Long) : LedgerException("Only $availableMilli in stock")
    class SerialNotInStock(val serial: String) : LedgerException("$serial is not in stock")
    class HasReturns : LedgerException("Sale has returns; void them first")
}

// ------------------------------------------------------------------ commands

sealed interface OpeningStock {
    data class Quantity(val qty: Qty, val unitCost: Money) : OpeningStock
    data class Serials(val units: List<SerialIn>) : OpeningStock
}

data class SerialIn(val serial: String, val cost: Money)

data class NewVariant(
    val label: String,
    val salePrice: Money,
    val sku: String? = null,
    val barcode: String? = null,
    val lowStock: Qty? = null,
    val opening: OpeningStock? = null,
    /** Usual buying price for 1 unit (D-85); defaults to the opening stock's cost. */
    val purchasePrice: Money? = null,
)

data class NewItem(
    val name: String,
    val category: String?,
    val trackingType: TrackingType,
    val unit: String,
    val allowDecimalQty: Boolean,
    val variants: List<NewVariant>,
)

data class VariantEdit(
    val variantId: String,
    val label: String,
    val salePrice: Money,
    val barcode: String?,
    val lowStock: Qty?,
    /** Usual buying price (D-85); null leaves the saved one as it is. */
    val purchasePrice: Money? = null,
)

data class PurchaseLineIn(
    val variantId: String,
    /** QUANTITY items. Ignored for SERIAL (qty = number of serials). */
    val qty: Qty = Qty.ZERO,
    val unitCost: Money = Money.ZERO,
    /** SERIAL items: one entry per unit, each with its own cost. */
    val serials: List<SerialIn> = emptyList(),
)

/** One cart line. SERIAL lines name the exact unit and always move 1 (D-33). */
data class SaleLineIn(
    val variantId: String,
    val qty: Qty,
    val unitPrice: Money,
    val serialUnitId: String? = null,
)

data class SaleIn(
    val invoicePrefix: String,
    val customerName: String?,
    val date: Long,
    val lines: List<SaleLineIn>,
    val discount: Money,
    val paid: Money,
    val mode: PaymentMode,
)

data class SaleResult(val saleId: String, val number: String, val totals: Totals)

data class ReturnLineIn(val saleLineId: String, val qty: Qty)

/** [refund] = value taken back; [cashOut] = the part paid out in cash (the rest reduced credit). */
data class ReturnResult(val returnId: String, val refund: Money, val cashOut: Money)

data class PurchaseIn(
    val supplierName: String?,
    val billNo: String?,
    val date: Long,
    val lines: List<PurchaseLineIn>,
    val paid: Money,
    val mode: PaymentMode,
)

/**
 * The only code that changes stock (CLAUDE.md rule 3). Every public function is one write
 * transaction that validates, writes the FACTS (documents, stock movements, IMEI events) and then
 * lets [StockRebuild] derive lots, allocations, costs, IMEI status and onHandMilli from them (D-75).
 * The same rebuild runs after merging rows from another phone, so both paths cost stock the same way.
 *
 *   validate (on current derived state) ──▶ facts ──▶ StockRebuild(variants touched)
 *
 * Rows that two phones could both write for the same action (a void's movements, reversal
 * payments, IMEI events; a customer or category found by name) get ids derived from what they
 * are, so the same action done on two phones merges into one row (D-76).
 */
class LedgerService(
    private val db: DukaanDatabase,
    private val deviceId: () -> String,
    private val zone: () -> ZoneId,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao: LedgerDao get() = db.ledgerDao()
    private val rebuild = StockRebuild(db)

    private fun meta(now: Long) = SyncMeta(createdAt = now, updatedAt = now, deviceId = deviceId())

    // -------------------------------------------------------------- item + opening stock

    /** Creates a product with its variants and any opening stock. Returns the product id. */
    suspend fun createItem(item: NewItem): String {
        val name = item.name.trim()
        if (name.isEmpty()) throw LedgerException.InvalidInput("name", "required")
        if (item.variants.isEmpty()) throw LedgerException.InvalidInput("variants", "at least one")
        val allowDecimal = item.allowDecimalQty && item.trackingType == TrackingType.QUANTITY
        item.variants.forEach { v ->
            if (v.salePrice.isNegative) throw LedgerException.InvalidInput("price", "negative")
            if (v.purchasePrice?.isNegative == true) throw LedgerException.InvalidInput("cost", "negative")
            when (val o = v.opening) {
                is OpeningStock.Quantity -> {
                    if (item.trackingType != TrackingType.QUANTITY) throw LedgerException.InvalidInput("opening", "use serials")
                    checkQty(o.qty, allowDecimal)
                    if (o.unitCost.isNegative) throw LedgerException.InvalidInput("cost", "negative")
                }
                is OpeningStock.Serials -> {
                    if (item.trackingType != TrackingType.SERIAL) throw LedgerException.InvalidInput("opening", "not serial")
                }
                null -> Unit
            }
        }
        val allSerials = item.variants.flatMap { (it.opening as? OpeningStock.Serials)?.units.orEmpty() }
        checkNewSerials(allSerials)

        return db.withWriteTransaction {
            val now = clock()
            val productId = newId()
            dao.insertProduct(
                ProductEntity(
                    id = productId,
                    name = name,
                    categoryId = item.category?.trim()?.takeIf { it.isNotEmpty() }?.let { findOrCreateCategory(it, now) },
                    trackingType = item.trackingType,
                    unit = item.unit.trim().ifEmpty { "pcs" },
                    allowDecimalQty = allowDecimal,
                    meta = meta(now),
                )
            )
            val variantIds = item.variants.map { v ->
                val variantId = newId()
                dao.insertVariant(
                    VariantEntity(
                        id = variantId,
                        productId = productId,
                        label = v.label.trim().ifEmpty { "Default" },
                        sku = v.sku?.trim()?.takeIf { it.isNotEmpty() },
                        barcode = v.barcode?.trim()?.takeIf { it.isNotEmpty() },
                        salePricePaisa = v.salePrice.minor,
                        lowStockMilli = v.lowStock?.milli,
                        onHandMilli = 0,
                        meta = meta(now),
                        purchasePricePaisa = v.purchasePrice?.minor ?: when (val o = v.opening) {
                            is OpeningStock.Quantity -> o.unitCost.minor
                            is OpeningStock.Serials -> o.units.lastOrNull()?.cost?.minor
                            null -> null
                        },
                    )
                )
                when (val o = v.opening) {
                    is OpeningStock.Quantity -> lotIn(variantId, LotSource.OPENING, null, o.qty.milli, o.unitCost.minor, now, MovementType.OPENING, refId = variantId)
                    is OpeningStock.Serials -> o.units.forEach {
                        serialIn(variantId, it, LotSource.OPENING, null, now, MovementType.OPENING, refId = variantId)
                    }
                    null -> Unit
                }
                variantId
            }
            rebuild.variants(variantIds)
            productId
        }
    }

    /** Edits master data only (names, prices, codes). Stock changes go through purchase/adjust. */
    suspend fun updateItem(productId: String, name: String, category: String?, unit: String, variants: List<VariantEdit>) {
        if (name.isBlank()) throw LedgerException.InvalidInput("name", "required")
        if (variants.any { it.salePrice.isNegative }) throw LedgerException.InvalidInput("price", "negative")
        if (variants.any { it.purchasePrice?.isNegative == true }) throw LedgerException.InvalidInput("cost", "negative")
        db.withWriteTransaction {
            val now = clock()
            val product = dao.product(productId) ?: throw LedgerException.NotFound("product $productId")
            dao.updateProduct(
                product.copy(
                    name = name.trim(),
                    categoryId = category?.trim()?.takeIf { it.isNotEmpty() }?.let { findOrCreateCategory(it, now) },
                    unit = unit.trim().ifEmpty { "pcs" },
                    meta = product.meta.copy(updatedAt = now),
                )
            )
            variants.forEach { edit ->
                val v = dao.variant(edit.variantId) ?: throw LedgerException.NotFound("variant ${edit.variantId}")
                dao.updateVariant(
                    v.copy(
                        label = edit.label.trim().ifEmpty { "Default" },
                        salePricePaisa = edit.salePrice.minor,
                        barcode = edit.barcode?.trim()?.takeIf { it.isNotEmpty() },
                        lowStockMilli = edit.lowStock?.milli,
                        purchasePricePaisa = edit.purchasePrice?.minor ?: v.purchasePricePaisa,
                        meta = v.meta.copy(updatedAt = now),
                    )
                )
            }
        }
    }

    // -------------------------------------------------------------- purchase

    /** Records stock received from a supplier. Returns the purchase id. */
    suspend fun purchase(input: PurchaseIn): String {
        if (input.lines.isEmpty()) throw LedgerException.InvalidInput("lines", "at least one")
        if (input.paid.isNegative) throw LedgerException.InvalidInput("paid", "negative")
        checkNewSerials(input.lines.flatMap { it.serials })

        return db.withWriteTransaction {
            val now = clock()
            val purchaseId = newId()
            val supplierId = input.supplierName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                findOrCreateParty(PartyType.SUPPLIER, it, now)
            }
            var total = Money.ZERO
            // Lines first, so the purchase row is written with its final total.
            val lineRows: List<Pair<PurchaseLineEntity, PurchaseLineIn>> = input.lines.map { line ->
                val variant = dao.variant(line.variantId) ?: throw LedgerException.NotFound("variant ${line.variantId}")
                val product = dao.product(variant.productId) ?: throw LedgerException.NotFound("product ${variant.productId}")
                val row = when (product.trackingType) {
                    TrackingType.QUANTITY -> {
                        if (line.serials.isNotEmpty()) throw LedgerException.InvalidInput("serials", "item is not IMEI-tracked")
                        checkQty(line.qty, product.allowDecimalQty)
                        if (line.unitCost.isNegative) throw LedgerException.InvalidInput("cost", "negative")
                        total += line.unitCost.times(line.qty)
                        PurchaseLineEntity(newId(), purchaseId, variant.id, line.qty.milli, line.unitCost.minor, meta(now))
                    }
                    TrackingType.SERIAL -> {
                        if (line.serials.isEmpty()) throw LedgerException.InvalidInput("serials", "at least one")
                        val lineTotal = line.serials.fold(Money.ZERO) { acc, s -> acc + s.cost }
                        total += lineTotal
                        // Average cost on the line row; each serial_unit keeps its exact cost.
                        val avg = lineTotal.minor / line.serials.size
                        PurchaseLineEntity(newId(), purchaseId, variant.id, line.serials.size * 1000L, avg, meta(now))
                    }
                }
                row to line
            }
            if (input.paid > total) throw LedgerException.InvalidInput("paid", "more than total")

            dao.insertPurchase(
                PurchaseEntity(
                    id = purchaseId,
                    supplierId = supplierId,
                    billNo = input.billNo?.trim()?.takeIf { it.isNotEmpty() },
                    date = input.date,
                    businessDay = businessDay(input.date, zone()),
                    totalPaisa = total.minor,
                    status = DocStatus.ACTIVE,
                    voidReason = null,
                    voidedAt = null,
                    meta = meta(now),
                )
            )
            lineRows.forEach { (row, line) ->
                dao.insertPurchaseLine(row)
                // The newest buying price becomes the item's usual one, pre-filled next time (D-85).
                dao.variant(row.variantId)?.takeIf { it.purchasePricePaisa != row.unitCostPaisa }?.let { v ->
                    dao.updateVariant(v.copy(purchasePricePaisa = row.unitCostPaisa, meta = v.meta.copy(updatedAt = now)))
                }
                if (line.serials.isEmpty()) {
                    lotIn(row.variantId, LotSource.PURCHASE, row.id, row.qtyMilli, row.unitCostPaisa, input.date, MovementType.PURCHASE, refId = purchaseId)
                } else {
                    line.serials.forEach { serialIn(row.variantId, it, LotSource.PURCHASE, row.id, now, MovementType.PURCHASE, refId = purchaseId) }
                }
            }
            if (input.paid.minor > 0) {
                dao.insertPayment(
                    PaymentEntity(
                        id = newId(), kind = PaymentKind.PURCHASE, partyId = supplierId, saleId = null,
                        purchaseId = purchaseId, returnId = null, reversesPaymentId = null,
                        direction = PaymentDirection.OUT, mode = input.mode, amountPaisa = input.paid.minor,
                        date = now, businessDay = businessDay(now, zone()), note = null, meta = meta(now),
                    )
                )
            }
            rebuild.variants(lineRows.map { it.first.variantId })
            purchaseId
        }
    }

    /**
     * Voids a purchase entered by mistake (D-17). Allowed only while every unit it brought in is
     * still untouched; otherwise the user corrects with a stock adjustment instead.
     */
    suspend fun voidPurchase(purchaseId: String, reason: String) {
        if (reason.isBlank()) throw LedgerException.InvalidInput("reason", "required")
        db.withWriteTransaction {
            val now = clock()
            val purchase = dao.purchase(purchaseId) ?: throw LedgerException.NotFound("purchase $purchaseId")
            if (purchase.status == DocStatus.VOIDED) throw LedgerException.AlreadyVoided()
            val lines = dao.purchaseLines(purchaseId)
            val lineIds = lines.map { it.id }
            val lots = if (lineIds.isEmpty()) emptyList() else dao.lotsForPurchaseLines(lineIds)
            val serials = if (lineIds.isEmpty()) emptyList() else dao.serialsForPurchaseLines(lineIds)
            if (lots.any { it.qtyRemainingMilli != it.qtyInMilli } || serials.any { it.status != SerialStatus.IN_STOCK }) {
                throw LedgerException.PurchaseStockUsed()
            }
            lots.forEach { lot -> move(lot.variantId, MovementType.PURCHASE_VOID, -lot.qtyInMilli, purchaseId, now, id = nameId("pvoid", lot.id)) }
            serials.forEach { unit ->
                serialEvent(unit.id, SerialStatus.VOIDED, purchaseId, now)
                move(unit.variantId, MovementType.PURCHASE_VOID, -1000, purchaseId, now, id = nameId("pvoid", unit.id))
            }
            reversePayments(dao.purchasePayments(purchaseId), reason, now)
            dao.updatePurchase(
                purchase.copy(status = DocStatus.VOIDED, voidReason = reason.trim(), voidedAt = now, meta = purchase.meta.copy(updatedAt = now))
            )
            rebuild.variants(lines.map { it.variantId })
        }
    }

    // -------------------------------------------------------------- sale

    /**
     * Records a sale (docs/ARCHITECTURE.md § Transactions, SALE).
     *
     *   validate ──▶ number = prefix + (MAX(seq)+1) ──▶ sale row + lines (snapshots) ──▶ IMEI events
     *   ──▶ SALE movements ──▶ payment(SALE, IN) for the paid part; rest is customer credit
     *   ──▶ rebuild: FIFO cost / pending (D-16), IMEI → SOLD
     *
     * Selling a QUANTITY item with no stock is allowed and leaves onHand negative (D-14).
     */
    suspend fun sell(input: SaleIn): SaleResult {
        if (input.lines.isEmpty()) throw LedgerException.InvalidInput("lines", "at least one")
        if (input.paid.isNegative) throw LedgerException.InvalidInput("paid", "negative")
        if (input.discount.isNegative) throw LedgerException.InvalidInput("discount", "negative")
        val prefix = input.invoicePrefix.trim().uppercase()
        if (prefix.isEmpty()) throw LedgerException.InvalidInput("prefix", "required")
        val serialIds = input.lines.mapNotNull { it.serialUnitId }
        if (serialIds.size != serialIds.toSet().size) throw LedgerException.InvalidInput("serials", "same unit twice")

        return db.withWriteTransaction {
            val now = clock()
            val shop = db.shopDao().get()
            val taxBp = shop?.taxRateBp
            // Validate every line before writing anything.
            val checked = input.lines.map { line ->
                if (line.unitPrice.isNegative) throw LedgerException.InvalidInput("price", "negative")
                val variant = dao.variant(line.variantId) ?: throw LedgerException.NotFound("variant ${line.variantId}")
                val product = dao.product(variant.productId) ?: throw LedgerException.NotFound("product ${variant.productId}")
                when (product.trackingType) {
                    TrackingType.SERIAL -> {
                        val unitId = line.serialUnitId ?: throw LedgerException.InvalidInput("serials", "choose the unit")
                        val unit = dao.serial(unitId) ?: throw LedgerException.NotFound("serial $unitId")
                        if (unit.variantId != variant.id) throw LedgerException.InvalidInput("serials", "unit of another item")
                        if (unit.status != SerialStatus.IN_STOCK) throw LedgerException.SerialNotInStock(unit.serial)
                        CheckedLine(line.copy(qty = Qty.ONE), variant, product, unit)
                    }
                    TrackingType.QUANTITY -> {
                        if (line.serialUnitId != null) throw LedgerException.InvalidInput("serials", "item is not IMEI-tracked")
                        checkQty(line.qty, product.allowDecimalQty)
                        CheckedLine(line, variant, product, null)
                    }
                }
            }
            val totals = SaleMath.totals(checked.map { it.line.unitPrice to it.line.qty }, input.discount, taxBp)
            if (input.paid > totals.total) throw LedgerException.InvalidInput("paid", "more than total")
            val customerId = input.customerName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                findOrCreateParty(PartyType.CUSTOMER, it, now)
            }
            if (input.paid < totals.total && customerId == null) throw LedgerException.InvalidInput("customer", "required for credit")

            val saleId = newId()
            val seq = (dao.maxInvoiceSeq(prefix) ?: 0) + 1
            val day = businessDay(input.date, zone())
            dao.insertSale(
                SaleEntity(
                    id = saleId, invoicePrefix = prefix, invoiceSeq = seq, customerId = customerId,
                    date = input.date, businessDay = day,
                    subtotalPaisa = totals.subtotal.minor, discountPaisa = totals.discount.minor,
                    taxRateBp = taxBp?.takeIf { it > 0 }, taxPaisa = totals.tax.minor, totalPaisa = totals.total.minor,
                    status = DocStatus.ACTIVE, voidReason = null, voidedAt = null, meta = meta(now),
                )
            )
            checked.forEachIndexed { i, c ->
                val name = if (c.variant.label == "Default") c.product.name else "${c.product.name} ${c.variant.label}"
                c.unit?.let { serialEvent(it.id, SerialStatus.SOLD, saleId, now, id = newId()) }
                dao.insertSaleLine(
                    SaleLineEntity(
                        id = newId(), saleId = saleId, variantId = c.variant.id, nameSnapshot = name,
                        qtyMilli = c.line.qty.milli, unitPricePaisa = c.line.unitPrice.minor,
                        lineTotalPaisa = totals.lineTotals[i].minor, costPaisa = 0, pendingQtyMilli = 0,
                        serialUnitId = c.unit?.id, meta = meta(now),
                    )
                )
                move(c.variant.id, MovementType.SALE, -c.line.qty.milli, saleId, now)
            }
            if (input.paid.minor > 0) {
                dao.insertPayment(
                    PaymentEntity(
                        id = newId(), kind = PaymentKind.SALE, partyId = customerId, saleId = saleId, purchaseId = null,
                        returnId = null, reversesPaymentId = null, direction = PaymentDirection.IN, mode = input.mode,
                        amountPaisa = input.paid.minor, date = input.date, businessDay = day, note = null, meta = meta(now),
                    )
                )
            }
            rebuild.variants(checked.map { it.variant.id })
            SaleResult(saleId, invoiceNumber(prefix, seq), totals)
        }
    }

    private data class CheckedLine(
        val line: SaleLineIn,
        val variant: VariantEntity,
        val product: ProductEntity,
        val unit: SerialUnitEntity?,
    )

    // -------------------------------------------------------------- adjustments

    /**
     * Corrects stock of a QUANTITY variant. Positive: a new lot at [unitCost] (default: last cost).
     * Negative: consumes open lots FIFO; refused if more than what lots hold.
     */
    suspend fun adjustQuantity(variantId: String, delta: Qty, unitCost: Money?, reason: String) {
        if (reason.isBlank()) throw LedgerException.InvalidInput("reason", "required")
        if (delta.milli == 0L) throw LedgerException.InvalidInput("qty", "zero")
        db.withWriteTransaction {
            val now = clock()
            val variant = dao.variant(variantId) ?: throw LedgerException.NotFound("variant $variantId")
            val product = dao.product(variant.productId) ?: throw LedgerException.NotFound("product")
            if (product.trackingType != TrackingType.QUANTITY) throw LedgerException.InvalidInput("variant", "serial item")
            checkQty(Qty(kotlin.math.abs(delta.milli)), product.allowDecimalQty)
            val adjustmentId = newId()
            val cost = if (delta.milli > 0) (unitCost?.minor ?: dao.lastUnitCost(variantId) ?: 0L) else null
            if (cost != null && cost < 0) throw LedgerException.InvalidInput("cost", "negative")
            if (delta.milli < 0) {
                val available = dao.openLots(variantId).sumOf { it.qtyRemainingMilli }
                if (available < -delta.milli) throw LedgerException.InsufficientStock(available)
            }
            dao.insertAdjustment(
                StockAdjustmentEntity(
                    id = adjustmentId, variantId = variantId, qtyDeltaMilli = delta.milli, unitCostPaisa = cost,
                    reason = reason.trim(), date = now, businessDay = businessDay(now, zone()), meta = meta(now),
                )
            )
            if (delta.milli > 0) {
                lotIn(variantId, LotSource.ADJUSTMENT, adjustmentId, delta.milli, cost!!, now, MovementType.ADJUST, refId = adjustmentId)
            } else {
                move(variantId, MovementType.ADJUST, delta.milli, adjustmentId, now)
            }
            rebuild.variants(listOf(variantId))
        }
    }

    /** Takes one IMEI unit out of stock as damaged or returned to supplier. */
    suspend fun removeSerial(serialUnitId: String, status: SerialStatus, reason: String) {
        if (status != SerialStatus.DAMAGED && status != SerialStatus.RETURNED_TO_SUPPLIER) {
            throw LedgerException.InvalidInput("status", "DAMAGED or RETURNED_TO_SUPPLIER")
        }
        if (reason.isBlank()) throw LedgerException.InvalidInput("reason", "required")
        db.withWriteTransaction {
            val now = clock()
            val unit = dao.serial(serialUnitId) ?: throw LedgerException.NotFound("serial $serialUnitId")
            if (unit.status != SerialStatus.IN_STOCK) throw LedgerException.SerialNotInStock(unit.serial)
            val adjustmentId = newId()
            dao.insertAdjustment(
                StockAdjustmentEntity(
                    id = adjustmentId, variantId = unit.variantId, qtyDeltaMilli = -1000, unitCostPaisa = null,
                    reason = reason.trim(), date = now, businessDay = businessDay(now, zone()), meta = meta(now),
                )
            )
            serialEvent(unit.id, status, adjustmentId, now)
            move(unit.variantId, MovementType.ADJUST, -1000, adjustmentId, now)
            rebuild.variants(listOf(unit.variantId))
        }
    }

    // -------------------------------------------------------------- void, return (M4)

    /**
     * Voids a sale (D-17). Refused while it has an ACTIVE return: void the return first (D-38).
     *   lines ──▶ SERIAL: refused if a buy-back of that IMEI is in stock ──▶ VOID movements
     *   ──▶ REVERSAL for each sale payment ──▶ status VOIDED ──▶ rebuild (lots back, IMEI in stock)
     */
    suspend fun voidSale(saleId: String, reason: String) {
        if (reason.isBlank()) throw LedgerException.InvalidInput("reason", "required")
        db.withWriteTransaction {
            val now = clock()
            val sale = dao.sale(saleId) ?: throw LedgerException.NotFound("sale $saleId")
            if (sale.status == DocStatus.VOIDED) throw LedgerException.AlreadyVoided()
            if (dao.activeReturnCount(saleId) > 0) throw LedgerException.HasReturns()
            val lines = dao.saleLines(saleId)
            lines.forEach { line ->
                line.serialUnitId?.let { restockSerial(it, saleId, now) }
                move(line.variantId, MovementType.VOID, line.qtyMilli, saleId, now, id = nameId("void", line.id))
            }
            reversePayments(dao.salePayments(saleId), reason, now)
            dao.updateSale(sale.copy(status = DocStatus.VOIDED, voidReason = reason.trim(), voidedAt = now, meta = sale.meta.copy(updatedAt = now)))
            rebuild.variants(lines.map { it.variantId })
        }
    }

    /**
     * Customer brings items back (partial or full, D-22).
     *   per line: RETURN movement; SERIAL: unit must still be this sale's (no buy-back in stock)
     *   refund = cumulative-rounded share of the line's net (D-37)
     *   ──▶ customer's balance takes the refund first; payment(REFUND, OUT) only for the excess
     *   ──▶ rebuild: pending first, then newest allocations back to their lots; IMEI in stock
     */
    suspend fun returnItems(saleId: String, lines: List<ReturnLineIn>, reason: String?, mode: PaymentMode): ReturnResult {
        val wanted = lines.filter { it.qty.milli != 0L }
        if (wanted.isEmpty()) throw LedgerException.InvalidInput("lines", "at least one")
        if (wanted.map { it.saleLineId }.toSet().size != wanted.size) throw LedgerException.InvalidInput("lines", "same line twice")
        return db.withWriteTransaction {
            val now = clock()
            val sale = dao.sale(saleId) ?: throw LedgerException.NotFound("sale $saleId")
            if (sale.status == DocStatus.VOIDED) throw LedgerException.AlreadyVoided()
            val saleLines = dao.saleLines(saleId)
            val nets = SaleMath.lineNets(saleLines.map { it.lineTotalPaisa }, sale.totalPaisa)
            val returnId = newId()
            val rows = wanted.map { w ->
                val i = saleLines.indexOfFirst { it.id == w.saleLineId }
                if (i < 0) throw LedgerException.NotFound("sale line ${w.saleLineId}")
                val line = saleLines[i]
                val before = dao.returnedQtyMilli(line.id)
                if (w.qty.milli < 0 || w.qty.milli > line.qtyMilli - before) throw LedgerException.InvalidInput("qty", "more than sold")
                if (line.serialUnitId == null) {
                    val variant = dao.variant(line.variantId) ?: throw LedgerException.NotFound("variant ${line.variantId}")
                    checkQty(w.qty, dao.product(variant.productId)?.allowDecimalQty ?: false)
                } else {
                    restockSerial(line.serialUnitId, returnId, now)
                }
                val refund = SaleMath.refund(nets[i], line.qtyMilli, before, w.qty.milli, dao.refundedPaisa(line.id))
                move(line.variantId, MovementType.RETURN, w.qty.milli, returnId, now)
                // costReversedPaisa is derived: the rebuild fills it in.
                SaleReturnLineEntity(newId(), returnId, line.id, w.qty.milli, refund, 0, meta(now))
            }
            val refund = rows.sumOf { it.refundPaisa }
            // Credit first (D-22): only what the customer does not owe goes out as cash.
            val owed = sale.customerId?.let { dao.partyBalance(it) } ?: 0L
            val cash = refund - refund.coerceAtMost(owed.coerceAtLeast(0))
            val day = businessDay(now, zone())
            dao.insertReturn(
                SaleReturnEntity(
                    id = returnId, saleId = saleId, date = now, businessDay = day, refundPaisa = refund,
                    reason = reason?.trim()?.takeIf { it.isNotEmpty() }, status = DocStatus.ACTIVE,
                    voidReason = null, voidedAt = null, meta = meta(now),
                )
            )
            rows.forEach { dao.insertReturnLine(it) }
            if (cash > 0) {
                // No saleId: the sale's "paid" stays what was paid at the counter.
                dao.insertPayment(
                    PaymentEntity(
                        id = newId(), kind = PaymentKind.REFUND, partyId = sale.customerId, saleId = null, purchaseId = null,
                        returnId = returnId, reversesPaymentId = null, direction = PaymentDirection.OUT, mode = mode,
                        amountPaisa = cash, date = now, businessDay = day, note = null, meta = meta(now),
                    )
                )
            }
            rebuild.variants(saleLines.filter { l -> wanted.any { it.saleLineId == l.id } }.map { it.variantId })
            ReturnResult(returnId, Money(refund), Money(cash))
        }
    }

    /**
     * Undoes a wrong return (D-38). QUANTITY lines take stock again at today's FIFO cost (D-58);
     * a SERIAL unit must still be IN_STOCK (not resold) and goes back to SOLD.
     */
    suspend fun voidReturn(returnId: String, reason: String) {
        if (reason.isBlank()) throw LedgerException.InvalidInput("reason", "required")
        db.withWriteTransaction {
            val now = clock()
            val ret = dao.saleReturn(returnId) ?: throw LedgerException.NotFound("return $returnId")
            if (ret.status == DocStatus.VOIDED) throw LedgerException.AlreadyVoided()
            val saleLines = dao.saleLines(ret.saleId).associateBy { it.id }
            val touched = dao.returnLines(returnId).map { rl ->
                val line = saleLines[rl.saleLineId] ?: throw LedgerException.NotFound("sale line ${rl.saleLineId}")
                if (line.serialUnitId != null) {
                    val unit = dao.serial(line.serialUnitId) ?: throw LedgerException.NotFound("serial ${line.serialUnitId}")
                    if (unit.status != SerialStatus.IN_STOCK) throw LedgerException.SerialNotInStock(unit.serial)
                    serialEvent(unit.id, SerialStatus.SOLD, returnId, now)
                }
                move(line.variantId, MovementType.RETURN_VOID, -rl.qtyMilli, returnId, now, id = nameId("rvoid", rl.id))
                line.variantId
            }
            reversePayments(dao.returnPayments(returnId), reason, now)
            dao.updateReturn(ret.copy(status = DocStatus.VOIDED, voidReason = reason.trim(), voidedAt = now, meta = ret.meta.copy(updatedAt = now)))
            rebuild.variants(touched)
        }
    }

    /** A category by name, created if new ("+ New category", D-85). Returns its id. */
    suspend fun addCategory(name: String): String {
        val clean = name.trim()
        if (clean.isEmpty()) throw LedgerException.InvalidInput("name", "required")
        return db.withWriteTransaction { findOrCreateCategory(clean, clock()) }
    }

    // -------------------------------------------------------------- parties, payments, expenses (M4)

    suspend fun addParty(type: PartyType, name: String, phone: String?, openingBalance: Money): String {
        val clean = name.trim()
        if (clean.isEmpty()) throw LedgerException.InvalidInput("name", "required")
        return db.withWriteTransaction {
            if (dao.partyByName(type, clean) != null) throw LedgerException.InvalidInput("name", "exists")
            partyId(type, clean).also { dao.insertParty(PartyEntity(it, type, clean, phone?.trim()?.takeIf { p -> p.isNotEmpty() }, openingBalance.minor, meta(clock()))) }
        }
    }

    suspend fun updateParty(partyId: String, name: String, phone: String?, openingBalance: Money) {
        val clean = name.trim()
        if (clean.isEmpty()) throw LedgerException.InvalidInput("name", "required")
        db.withWriteTransaction {
            val party = dao.party(partyId) ?: throw LedgerException.NotFound("party $partyId")
            val clash = dao.partyByName(party.type, clean)
            if (clash != null && clash.id != partyId) throw LedgerException.InvalidInput("name", "exists")
            dao.updateParty(
                party.copy(
                    name = clean, phone = phone?.trim()?.takeIf { it.isNotEmpty() }, openingBalancePaisa = openingBalance.minor,
                    meta = party.meta.copy(updatedAt = clock()),
                )
            )
        }
    }

    /** Money received from a customer (IN) or paid to a supplier (OUT), not tied to one bill. */
    suspend fun recordPayment(partyId: String, amount: Money, mode: PaymentMode, note: String?, date: Long): String {
        if (amount.minor <= 0) throw LedgerException.InvalidInput("amount", "must be more than zero")
        return db.withWriteTransaction {
            val now = clock()
            val party = dao.party(partyId) ?: throw LedgerException.NotFound("party $partyId")
            val direction = if (party.type == PartyType.CUSTOMER) PaymentDirection.IN else PaymentDirection.OUT
            newId().also {
                dao.insertPayment(
                    PaymentEntity(
                        id = it, kind = PaymentKind.PARTY, partyId = partyId, saleId = null, purchaseId = null, returnId = null,
                        reversesPaymentId = null, direction = direction, mode = mode, amountPaisa = amount.minor, date = date,
                        businessDay = businessDay(date, zone()), note = note?.trim()?.takeIf { n -> n.isNotEmpty() }, meta = meta(now),
                    )
                )
            }
        }
    }

    /** Cancels a party payment entered by mistake with a REVERSAL row (never deleted). */
    suspend fun voidPayment(paymentId: String, reason: String) {
        if (reason.isBlank()) throw LedgerException.InvalidInput("reason", "required")
        db.withWriteTransaction {
            val payment = dao.payment(paymentId) ?: throw LedgerException.NotFound("payment $paymentId")
            if (payment.kind != PaymentKind.PARTY) throw LedgerException.InvalidInput("payment", "void its bill instead")
            if (dao.reversalCount(paymentId) > 0) throw LedgerException.AlreadyVoided()
            reversePayments(listOf(payment), reason, clock())
        }
    }

    suspend fun addExpense(category: String, amount: Money, note: String?, date: Long): String {
        val clean = category.trim()
        if (clean.isEmpty()) throw LedgerException.InvalidInput("category", "required")
        if (amount.minor <= 0) throw LedgerException.InvalidInput("amount", "must be more than zero")
        return db.withWriteTransaction {
            newId().also {
                dao.insertExpense(
                    ExpenseEntity(it, clean, amount.minor, date, businessDay(date, zone()), note?.trim()?.takeIf { n -> n.isNotEmpty() }, meta(clock()))
                )
            }
        }
    }

    /** Expenses touch no stock or cash ledger, so a mistake is soft-deleted (D-59). */
    suspend fun deleteExpense(expenseId: String) {
        db.withWriteTransaction {
            val e = dao.expense(expenseId) ?: throw LedgerException.NotFound("expense $expenseId")
            if (e.meta.deletedAt == null) {
                val now = clock()
                dao.updateExpense(e.copy(meta = e.meta.copy(updatedAt = now, deletedAt = now)))
            }
        }
    }

    // -------------------------------------------------------------- internals (inside a transaction)

    /** A new lot holding all of [qtyMilli]; the rebuild then fills pending sale lines from it (D-16). */
    private suspend fun lotIn(
        variantId: String, source: LotSource, sourceId: String?, qtyMilli: Long, unitCostPaisa: Long,
        receivedAt: Long, movementType: MovementType, refId: String,
    ) {
        val now = clock()
        dao.insertLot(
            StockLotEntity(
                id = newId(), variantId = variantId, sourceType = source, sourceId = sourceId,
                qtyInMilli = qtyMilli, qtyRemainingMilli = qtyMilli, unitCostPaisa = unitCostPaisa,
                receivedAt = receivedAt, meta = meta(now),
            )
        )
        move(variantId, movementType, qtyMilli, refId, now)
    }

    private suspend fun serialIn(
        variantId: String, unit: SerialIn, source: LotSource, sourceId: String?, now: Long,
        movementType: MovementType, refId: String,
    ) {
        val serial = unit.serial.trim()
        if (unit.cost.isNegative) throw LedgerException.InvalidInput("cost", "negative")
        // Buy-back of a sold IMEI is a NEW row (D-33). The liveSerial unique index (D-35) is the
        // last line of defence; checkNewSerials already gave a readable error.
        val row = SerialUnitEntity(
            id = newId(), serial = serial, liveSerial = serial, variantId = variantId, sourceType = source,
            sourceId = sourceId, costPaisa = unit.cost.minor, status = SerialStatus.IN_STOCK, meta = meta(now),
        )
        dao.insertSerial(row)
        dao.insertSerialEvent(SerialEventEntity(newId(), row.id, SerialStatus.IN_STOCK, refId, meta(now)))
        move(variantId, movementType, 1000, refId, now)
    }

    /**
     * History row of an IMEI. Status itself is derived by the rebuild; only DAMAGED /
     * RETURNED_TO_SUPPLIER events are read back (D-75). Same unit + document + status = same row.
     */
    private suspend fun serialEvent(unitId: String, status: SerialStatus, refId: String, now: Long, id: String = nameId("ev", unitId, refId, status.name)) {
        dao.insertSerialEvent(SerialEventEntity(id, unitId, status, refId, meta(now)))
    }

    /** A sold unit comes back. A buy-back of the same IMEI already in stock blocks it (D-33). */
    private suspend fun restockSerial(unitId: String, refId: String, now: Long) {
        val unit = dao.serial(unitId) ?: throw LedgerException.NotFound("serial $unitId")
        if (unit.status != SerialStatus.SOLD) throw LedgerException.InvalidInput("serial", "not sold")
        if (dao.liveSerials(listOf(unit.serial)).isNotEmpty()) throw LedgerException.DuplicateSerial(listOf(unit.serial))
        serialEvent(unit.id, SerialStatus.IN_STOCK, refId, now)
    }

    /** One REVERSAL per payment not yet reversed, opposite direction (D-38); id from the payment (D-76). */
    private suspend fun reversePayments(payments: List<PaymentEntity>, reason: String, now: Long) {
        payments.filter { it.kind != PaymentKind.REVERSAL && dao.reversalCount(it.id) == 0 }.forEach { p ->
            dao.insertPayment(
                p.copy(
                    id = nameId("rev", p.id), kind = PaymentKind.REVERSAL, reversesPaymentId = p.id,
                    direction = if (p.direction == PaymentDirection.IN) PaymentDirection.OUT else PaymentDirection.IN,
                    date = now, businessDay = businessDay(now, zone()), note = reason.trim(), meta = meta(now),
                )
            )
        }
    }

    /** The stock ledger row; onHandMilli follows from it in the rebuild (invariant 1). */
    private suspend fun move(variantId: String, type: MovementType, deltaMilli: Long, refId: String, now: Long, id: String = newId()) {
        dao.insertMovement(StockMovementEntity(id, variantId, type, deltaMilli, refId, meta(now)))
    }

    private suspend fun findOrCreateCategory(name: String, now: Long): String =
        dao.categoryByName(name)?.id ?: nameId("category", name.lowercase()).let { id ->
            (if (dao.categoryById(id) == null) id else newId()).also { dao.insertCategory(CategoryEntity(it, name, meta(now))) }
        }

    private suspend fun findOrCreateParty(type: PartyType, name: String, now: Long): String =
        dao.partyByName(type, name)?.id ?: partyId(type, name).also {
            dao.insertParty(PartyEntity(it, type, name, phone = null, openingBalancePaisa = 0, meta = meta(now)))
        }

    /**
     * "Ali" typed on two phones offline is one customer (D-76). Falls back to a random id when the
     * name's id is taken (that party was renamed or deleted).
     */
    private suspend fun partyId(type: PartyType, name: String): String =
        nameId("party", type.name, name.lowercase()).let { if (dao.party(it) == null) it else newId() }

    private fun checkQty(qty: Qty, allowDecimal: Boolean) {
        if (qty.milli <= 0) throw LedgerException.InvalidInput("qty", "must be more than zero")
        if (!allowDecimal && !qty.isWhole) throw LedgerException.InvalidInput("qty", "whole units only")
    }

    /** Blank, repeated-in-input, or already-in-stock serials are rejected before any write. */
    private suspend fun checkNewSerials(units: List<SerialIn>) {
        if (units.isEmpty()) return
        val serials = units.map { it.serial.trim() }
        if (serials.any { it.isEmpty() }) throw LedgerException.InvalidInput("serial", "blank")
        val repeated = serials.groupBy { it }.filter { it.value.size > 1 }.keys
        if (repeated.isNotEmpty()) throw LedgerException.DuplicateSerial(repeated.toList())
        val live = dao.liveSerials(serials)
        if (live.isNotEmpty()) throw LedgerException.DuplicateSerial(live)
    }
}

/** Same parts → same UUID on every phone (D-76). */
fun nameId(vararg parts: String): String = UUID.nameUUIDFromBytes(parts.joinToString(":").toByteArray()).toString()
