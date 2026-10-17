package app.dukaan.android.core.account

import app.dukaan.android.AppContainer
import app.dukaan.android.core.db.ShopProfileEntity
import app.dukaan.android.core.db.SyncMeta
import java.time.ZoneId
import java.util.UUID

data class Currency(val code: String, val symbol: String)

val Currencies = listOf(Currency("PKR", "Rs"), Currency("INR", "₹"), Currency("AED", "AED"), Currency("USD", "$"))

fun currencyOf(code: String) = Currencies.firstOrNull { it.code == code } ?: Currency(code, code)

/**
 * Writes this phone's shop_profile (the "set up" flag for the whole app). Used by local-only
 * onboarding, account sign-up, and a login on a new phone whose shop has no cloud copy yet.
 */
suspend fun insertLocalShop(container: AppContainer, info: ShopInfo, prefix: String?) {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val currency = currencyOf(info.currency)
    container.database.shopDao().insert(
        ShopProfileEntity(
            id = UUID.randomUUID().toString(),
            name = info.name.trim(),
            shopType = info.type,
            phone = info.phone?.trim()?.ifEmpty { null },
            address = info.address?.trim()?.ifEmpty { null },
            logoPath = null,
            currencyCode = currency.code,
            currencySymbol = currency.symbol,
            timeZone = zone.id,
            taxRateBp = null,
            receiptFooter = null,
            meta = SyncMeta(now, now, null, container.prefs.deviceId),
        )
    )
    container.shopZone = zone
    prefix?.let { container.prefs.invoicePrefix = it.trim().uppercase().ifEmpty { "A" } }
}
