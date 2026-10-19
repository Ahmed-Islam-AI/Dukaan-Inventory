package app.dukaan.android.core.receipt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.graphics.scale
import app.dukaan.android.core.db.PageTemplate
import app.dukaan.android.core.db.ShopProfileEntity
import app.dukaan.android.core.db.SlipTemplate
import java.io.ByteArrayOutputStream

/** The shop's invoice design (D-80): row ⇄ [InvoiceStyle], and the logo's small synced PNG. */
object InvoiceDesign {
    const val DEFAULT_ACCENT = 0xE8590C

    /** Brand colours offered in Invoice design (saffron first: the app's own). */
    val ACCENTS = listOf(0xE8590C, 0x1971C2, 0x2B8A3E, 0xC92A2A, 0x6741D9, 0x0C8599, 0xD6336C, 0x1A1D21)

    fun style(shop: ShopProfileEntity?) = InvoiceStyle(
        page = shop?.pageTemplate ?: PageTemplate.MODERN,
        slip = shop?.slipTemplate ?: SlipTemplate.CLASSIC,
        accentRgb = shop?.accentRgb ?: DEFAULT_ACCENT,
        logo = shop?.logoPng?.let(::decodeLogo),
    )

    fun decodeLogo(base64: String): Bitmap? = try {
        Base64.decode(base64, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Shrinks a picked image to at most [MAX_LOGO_PX] on its long side and encodes it as PNG
     * (keeps transparency). Small enough to sync inside the shop row.
     */
    fun encodeLogo(picked: Bitmap): String {
        val k = minOf(1f, MAX_LOGO_PX.toFloat() / maxOf(picked.width, picked.height))
        val small = if (k < 1f) picked.scale((picked.width * k).toInt().coerceAtLeast(1), (picked.height * k).toInt().coerceAtLeast(1)) else picked
        val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private const val MAX_LOGO_PX = 240
}
