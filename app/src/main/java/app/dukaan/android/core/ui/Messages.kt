package app.dukaan.android.core.ui

import android.content.Context
import app.dukaan.android.R
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.money.Qty
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One user-facing sentence per named failure (PLAN.md § Error handling). */
fun LedgerException.userMessage(context: Context): String = when (this) {
    is LedgerException.DuplicateSerial -> context.getString(R.string.err_duplicate_serial, serials.joinToString(", "))
    is LedgerException.InsufficientStock -> context.getString(R.string.err_insufficient_stock, Qty(availableMilli).format())
    is LedgerException.PurchaseStockUsed -> context.getString(R.string.err_purchase_used)
    is LedgerException.AlreadyVoided -> context.getString(R.string.err_already_voided)
    is LedgerException.SerialNotInStock -> context.getString(R.string.err_serial_not_in_stock, serial)
    is LedgerException.NotFound -> context.getString(R.string.err_not_found)
    is LedgerException.HasReturns -> context.getString(R.string.err_has_returns)
    is LedgerException.InvalidInput -> when (field) {
        "name" -> context.getString(if (reason == "exists") R.string.err_name_exists else R.string.err_name_required)
        "amount" -> context.getString(R.string.err_amount)
        "category" -> context.getString(R.string.err_category)
        "qty" -> context.getString(R.string.err_qty)
        "paid" -> context.getString(R.string.err_paid)
        "reason" -> context.getString(R.string.err_reason)
        "serial", "serials" -> context.getString(R.string.err_serials)
        "lines" -> context.getString(R.string.err_lines)
        "customer" -> context.getString(R.string.err_customer_for_credit)
        "price" -> context.getString(R.string.err_price)
        else -> context.getString(R.string.err_invalid)
    }
}

private val dateTime = DateTimeFormatter.ofPattern("d MMM, h:mm a")
private val dateOnly = DateTimeFormatter.ofPattern("d MMM yyyy")

fun formatDateTime(epochMillis: Long): String =
    dateTime.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

fun formatDate(epochMillis: Long): String =
    dateOnly.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
