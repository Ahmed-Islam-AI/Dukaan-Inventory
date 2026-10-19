package app.dukaan.android.core.receipt

import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.DukaanDatabase
import app.dukaan.android.core.db.PaymentDirection
import app.dukaan.android.core.db.PaymentKind
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.ledger.invoiceNumber
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Builds a [ReceiptModel] for a saved sale. Returns null if the sale does not exist. */
class ReceiptRepository(private val db: DukaanDatabase) {

    private val stamp = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")

    suspend fun model(saleId: String, modeLabel: (PaymentMode) -> String): ReceiptModel? {
        val dao = db.saleDao()
        val sale = dao.sale(saleId) ?: return null
        val shop = db.shopDao().get()
        val payments = dao.payments(saleId)
        // What was paid at the counter; a void's REVERSAL rows must not make the receipt say "unpaid".
        val paid = payments.filter { it.kind == PaymentKind.SALE }.sumOf { it.amountPaisa }
        val zone = shop?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val lines = dao.lines(saleId).map { l ->
            val unit = dao.unitOf(l.variantId).orEmpty()
            val q = Qty(l.qtyMilli)
            val qty = if (unit.isBlank() || unit == "pcs" || l.serialUnitId != null) q.format() else "${q.format()} $unit"
            ReceiptLine(l.nameSnapshot, qty, Money(l.unitPricePaisa), Money(l.lineTotalPaisa), l.serialUnitId?.let { dao.serialNumber(it) })
        }
        return ReceiptModel(
            shopName = shop?.name.orEmpty(),
            shopAddress = shop?.address,
            shopPhone = shop?.phone,
            footer = shop?.receiptFooter,
            number = invoiceNumber(sale.invoicePrefix, sale.invoiceSeq),
            dateTime = stamp.format(Instant.ofEpochMilli(sale.date).atZone(zone)),
            customer = sale.customerId?.let { dao.party(it)?.name },
            lines = lines,
            subtotal = Money(sale.subtotalPaisa),
            discount = Money(sale.discountPaisa),
            taxRateBp = sale.taxRateBp,
            tax = Money(sale.taxPaisa),
            total = Money(sale.totalPaisa),
            paid = Money(paid),
            paidModeLabel = payments.firstOrNull { it.direction == PaymentDirection.IN }?.mode?.let(modeLabel),
            voided = sale.status == DocStatus.VOIDED,
            symbol = shop?.currencySymbol ?: "Rs",
            lakhGrouping = (shop?.currencyCode ?: "PKR") in Money.LAKH_CURRENCIES,
            taxNumber = shop?.taxNumber,
            terms = shop?.invoiceTerms,
            serialLabel = serialLabelFor(shop?.shopType),
        )
    }

    /** How this shop's invoices look (D-80). */
    suspend fun style(): InvoiceStyle = InvoiceDesign.style(db.shopDao().get())
}
