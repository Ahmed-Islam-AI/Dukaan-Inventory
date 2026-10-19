package app.dukaan.android.core.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import app.dukaan.android.R
import app.dukaan.android.core.db.PageTemplate

/** How this shop's invoices look (D-80): from the shop row, logo already decoded. */
data class InvoiceStyle(
    val page: PageTemplate,
    val slip: app.dukaan.android.core.db.SlipTemplate,
    /** 0xRRGGBB. */
    val accentRgb: Int,
    val logo: Bitmap?,
)

/**
 * A4 invoice pages (D-80) drawn in PDF points (595 × 842) onto any Canvas: a PdfDocument page for
 * sharing, a print page for A4, or a bitmap for the preview. Vector text, so it stays sharp when
 * zoomed or printed. Labels are English (D-08); names print as typed (Urdu shapes via StaticLayout).
 *
 *   MODERN   brand-colour band with logo and "INVOICE", tinted table head, total in a colour bar
 *   CLASSIC  centred letterhead, bordered boxes, full table grid
 *   MINIMAL  white page, thin lines, the total in brand colour
 *
 * Long bills continue on more pages (short header, table head repeated, "Page n of m").
 */
class InvoicePage(context: Context) {
    private val sans: Typeface = ResourcesCompat.getFont(context, R.font.plex_sans) ?: Typeface.SANS_SERIF
    private val mono: Typeface = ResourcesCompat.getFont(context, R.font.plex_mono_medium) ?: Typeface.MONOSPACE

    fun pageCount(m: ReceiptModel, s: InvoiceStyle): Int = Painter(m, s, null).plan().size

    /** Draws page [index] (0-based) at 1 unit = 1 pt. */
    fun draw(canvas: Canvas, m: ReceiptModel, s: InvoiceStyle, index: Int) = Painter(m, s, canvas).page(index)

    /** Every page as a bitmap, [scale] pixels per point (preview, slip-less sharing). */
    fun bitmaps(m: ReceiptModel, s: InvoiceStyle, scale: Float = 1.5f): List<Bitmap> =
        (0 until pageCount(m, s)).map { i ->
            createBitmap((W * scale).toInt(), (H * scale).toInt()).also { b ->
                val c = Canvas(b)
                c.drawColor(Color.WHITE)
                c.scale(scale, scale)
                draw(c, m, s, i)
            }
        }

    /** One invoice being measured (canvas null) or drawn. */
    private inner class Painter(private val m: ReceiptModel, private val s: InvoiceStyle, private val c: Canvas?) {
        private val t = s.page
        private val accent = 0xFF000000.toInt() or s.accentRgb
        private val tint = ColorUtils.blendARGB(Color.WHITE, accent, 0.12f)
        private val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.6) INK else Color.WHITE

        private fun paint(size: Float, bold: Boolean = false, color: Int = INK, typeface: Typeface = sans) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            this.typeface = Typeface.create(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        private val body = paint(9.5f)
        private val bodyBold = paint(9.5f, bold = true)
        private val small = paint(8f, color = INK2)
        private val label = paint(7.5f, bold = true, color = if (t == PageTemplate.MINIMAL) INK2 else accent).apply { letterSpacing = 0.08f }
        private val monoSmall = paint(7.5f, color = INK2, typeface = mono)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.7f; color = if (t == PageTemplate.CLASSIC) INK else LINE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // Table columns: # | item | qty | rate | amount (right edges for numbers).
        private val itemX = M + 26f
        private val itemEnd = W - M - 210f
        private val qtyEnd = itemEnd + 46f
        private val rateEnd = qtyEnd + 78f
        private val amountEnd = W - M

        // ------------------------------------------------------------ text helpers
        private fun layout(text: String, p: TextPaint, width: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
            StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt().coerceAtLeast(1)).setAlignment(align).setIncludePad(false).build()

        /** Wrapped text at (x, y) top-left; returns its height. */
        private fun block(text: String, p: TextPaint, x: Float, y: Float, width: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): Float {
            val l = layout(text, p, width, align)
            c?.withTranslation(x, y) { l.draw(this) }
            return l.height.toFloat()
        }

        /** One line with its top at [y]; [x] is the left, right or centre edge per [align]. */
        private fun line(text: String, p: TextPaint, x: Float, y: Float, align: Paint.Align = Paint.Align.LEFT): Float {
            val w = p.measureText(text)
            val left = when (align) { Paint.Align.RIGHT -> x - w; Paint.Align.CENTER -> x - w / 2; else -> x }
            c?.drawText(text, left, y - p.fontMetrics.ascent, p)
            return p.fontMetrics.descent - p.fontMetrics.ascent
        }

        private fun rect(l: Float, top: Float, r: Float, b: Float, color: Int, radius: Float = 0f) {
            fill.color = color
            c?.drawRoundRect(RectF(l, top, r, b), radius, radius, fill)
        }

        private fun hline(y: Float, from: Float = M, to: Float = W - M, p: Paint = stroke) { c?.drawLine(from, y, to, y, p) }

        private fun logo(x: Float, y: Float, maxW: Float, maxH: Float): Float {
            val b = s.logo ?: return 0f
            val k = minOf(maxW / b.width, maxH / b.height)
            c?.drawBitmap(b, null, RectF(x, y, x + b.width * k, y + b.height * k), Paint(Paint.FILTER_BITMAP_FLAG))
            return b.width * k
        }

        private val shopLines: List<String> get() = listOfNotNull(
            m.shopAddress?.takeIf { it.isNotBlank() },
            m.shopPhone?.takeIf { it.isNotBlank() }?.let { "Phone: $it" },
            m.taxNumber?.takeIf { it.isNotBlank() }?.let { "Tax no: $it" },
        )

        // ------------------------------------------------------------ page plan

        /** Items on each page; the totals and terms go after the last item (or on a page of their own). */
        fun plan(): List<IntRange> {
            val heights = m.lines.map { rowHeight(it) }
            val limit = H - M - 24f
            val pages = mutableListOf<IntRange>()
            var start = 0
            var y = header(measureOnly = true) + HEAD_ROW
            heights.forEachIndexed { i, h ->
                if (y + h > limit && i > start) {
                    pages += start until i
                    start = i
                    y = CONT_TOP + HEAD_ROW
                }
                y += h
            }
            pages += start until m.lines.size
            if (y + footer(0f, measureOnly = true) > limit) pages += IntRange.EMPTY
            return pages
        }

        private fun rowHeight(l: ReceiptLine) = layout(l.name, body, itemEnd - itemX - 6f).height + (if (l.serial != null) 11f else 0f) + 12f

        fun page(index: Int) {
            val pages = plan()
            val items = pages[index]
            var y = if (index == 0) header(measureOnly = false) else continuationHeader(index, pages.size)
            if (!items.isEmpty()) {
                y = tableHead(y)
                for (i in items) y = itemRow(i, m.lines[i], y)
            }
            if (index == pages.lastIndex) footer(y + 14f, measureOnly = false)
            if (pages.size > 1) line("Page ${index + 1} of ${pages.size}", small, W - M, H - M - 8f, Paint.Align.RIGHT)
            if (m.voided) watermark()
        }

        // ------------------------------------------------------------ header (page 1)

        /** Draws (or measures) the first-page header; returns where the table starts. */
        private fun header(measureOnly: Boolean): Float {
            val canvas = if (measureOnly) null else c
            return Painter(m, s, canvas).run {
                when (t) {
                    PageTemplate.MODERN -> modernHeader()
                    PageTemplate.CLASSIC -> classicHeader()
                    PageTemplate.MINIMAL -> minimalHeader()
                }
            }
        }

        private fun modernHeader(): Float {
            val white = paint(8.5f, color = ColorUtils.setAlphaComponent(onAccent, 225))
            val nameP = paint(18f, bold = true, color = onAccent)
            val linesH = shopLines.size * 12f
            val bandH = maxOf(112f, 44f + linesH + 22f)
            rect(0f, 0f, W, bandH, accent)
            var x = M
            if (s.logo != null) {
                rect(M, 26f, M + 64f, 90f, Color.WHITE, 8f)
                logo(M + 6f, 32f, 52f, 52f)
                x = M + 78f
            }
            block(m.shopName, nameP, x, 26f, W - M - x - 150f)
            var ly = 52f
            shopLines.forEach { ly += line(it, white, x, ly) + 1f }
            line("INVOICE", paint(22f, bold = true, color = onAccent), W - M, 26f, Paint.Align.RIGHT)
            line("# ${m.number}", paint(10f, bold = true, color = onAccent), W - M, 56f, Paint.Align.RIGHT)
            line(m.dateTime, white, W - M, 71f, Paint.Align.RIGHT)
            return parties(bandH + 22f)
        }

        private fun classicHeader(): Float {
            var y = M
            if (s.logo != null) {
                val b = s.logo
                val k = minOf(120f / b.width, 52f / b.height)
                logo(W / 2 - b.width * k / 2, y, 120f, 52f)
                y += b.height * k + 8f
            }
            y += block(m.shopName, paint(20f, bold = true, color = accent), M, y, W - 2 * M, Layout.Alignment.ALIGN_CENTER) + 4f
            shopLines.forEach { y += block(it, small, M, y, W - 2 * M, Layout.Alignment.ALIGN_CENTER) + 1f }
            y += 8f
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 1.2f }
            hline(y, p = rule)
            hline(y + 3f, p = rule)
            y += 16f
            val titleP = paint(12f, bold = true).apply { letterSpacing = 0.2f }
            val tw = titleP.measureText("INVOICE") + 40f
            c?.drawRect(W / 2 - tw / 2, y, W / 2 + tw / 2, y + 22f, stroke)
            line("INVOICE", titleP, W / 2, y + 4f, Paint.Align.CENTER)
            y += 34f
            // Two bordered boxes: who it is for, and the invoice details.
            val mid = W / 2
            val boxH = 58f
            c?.drawRect(M, y, mid - 6f, y + boxH, stroke)
            c?.drawRect(mid + 6f, y, W - M, y + boxH, stroke)
            line("BILL TO", label, M + 8f, y + 8f)
            block(m.customer?.takeIf { it.isNotBlank() } ?: "Walk-in customer", bodyBold, M + 8f, y + 22f, mid - M - 22f)
            detailRows(mid + 14f, y + 8f, W - M - 8f)
            return y + boxH + 18f
        }

        private fun minimalHeader(): Float {
            var x = M
            if (s.logo != null) x += logo(M, M, 46f, 46f) + 12f
            var y = M
            y += block(m.shopName, paint(22f, bold = true), x, y, W / 2 - x + 40f) + 6f
            rect(x, y, x + 36f, y + 3f, accent)
            y += 10f
            shopLines.forEach { y += line(it, small, x, y) + 1f }
            line("Invoice", paint(11f, color = INK2), W - M, M, Paint.Align.RIGHT)
            line(m.number, paint(15f, bold = true), W - M, M + 15f, Paint.Align.RIGHT)
            line(m.dateTime, small, W - M, M + 36f, Paint.Align.RIGHT)
            return parties(maxOf(y, M + 52f) + 22f)
        }

        /** Bill to (left) and payment state (right); returns the table top. */
        private fun parties(top: Float): Float {
            line(if (t == PageTemplate.MINIMAL) "Billed to" else "BILL TO", label, M, top)
            block(m.customer?.takeIf { it.isNotBlank() } ?: "Walk-in customer", paint(11f, bold = true), M, top + 13f, W / 2 - M)
            line(if (t == PageTemplate.MINIMAL) "Payment" else "PAYMENT", label, W - M, top, Paint.Align.RIGHT)
            m.paidModeLabel?.let { line(it, body, W - M, top + 13f, Paint.Align.RIGHT) }
            statusChip(W - M, top + 29f)
            return top + 58f
        }

        /** Invoice no / date / payment rows inside the Classic details box. */
        private fun detailRows(x: Float, top: Float, right: Float) {
            var y = top
            listOf("Invoice no" to m.number, "Date" to m.dateTime, "Payment" to (m.paidModeLabel ?: "-")).forEach { (k, v) ->
                line(k, small, x, y)
                line(v, bodyBold, right, y, Paint.Align.RIGHT)
                y += 14f
            }
        }

        /** PAID / DUE / VOID pill, right edge at [right]. */
        private fun statusChip(right: Float, top: Float) {
            val (text, color) = when {
                m.voided -> "VOID" to INK2
                m.balance.minor > 0 -> "DUE ${m.money(m.balance, symbol = true)}" to LOSS
                else -> "PAID" to PROFIT
            }
            val p = paint(8f, bold = true, color = color).apply { letterSpacing = 0.06f }
            val w = p.measureText(text) + 14f
            rect(right - w, top, right, top + 15f, ColorUtils.setAlphaComponent(color, 30), 7.5f)
            line(text, p, right - w / 2, top + 3f, Paint.Align.CENTER)
        }

        private fun continuationHeader(index: Int, count: Int): Float {
            line(m.shopName, paint(12f, bold = true, color = if (t == PageTemplate.MINIMAL) INK else accent), M, M)
            line("Invoice ${m.number} · continued", small, W - M, M + 2f, Paint.Align.RIGHT)
            hline(M + 22f)
            return CONT_TOP
        }

        // ------------------------------------------------------------ table

        private fun tableHead(top: Float): Float {
            val headP = when (t) {
                PageTemplate.MODERN -> label
                PageTemplate.CLASSIC -> paint(7.5f, bold = true, color = onAccent).apply { letterSpacing = 0.08f }
                PageTemplate.MINIMAL -> label
            }
            when (t) {
                PageTemplate.MODERN -> rect(M, top, W - M, top + HEAD_ROW, tint, 3f)
                PageTemplate.CLASSIC -> rect(M, top, W - M, top + HEAD_ROW, accent)
                PageTemplate.MINIMAL -> hline(top + HEAD_ROW)
            }
            val y = top + 7f
            line("#", headP, M + 6f, y)
            line("ITEM", headP, itemX, y)
            line("QTY", headP, qtyEnd - 6f, y, Paint.Align.RIGHT)
            line("RATE", headP, rateEnd - 6f, y, Paint.Align.RIGHT)
            line("AMOUNT", headP, amountEnd - 6f, y, Paint.Align.RIGHT)
            return top + HEAD_ROW
        }

        private fun itemRow(i: Int, l: ReceiptLine, top: Float): Float {
            val h = rowHeight(l)
            if (t == PageTemplate.MODERN && i % 2 == 1) rect(M, top, W - M, top + h, ZEBRA)
            val y = top + 6f
            line("${i + 1}", small, M + 6f, y + 1f)
            val nameH = block(l.name, body, itemX, y, itemEnd - itemX - 6f)
            l.serial?.let { line("${m.serialLabel} $it", monoSmall, itemX, y + nameH + 1f) }
            line(l.qty, body, qtyEnd - 6f, y, Paint.Align.RIGHT)
            line(m.money(l.unitPrice), body, rateEnd - 6f, y, Paint.Align.RIGHT)
            line(m.money(l.total), bodyBold, amountEnd - 6f, y, Paint.Align.RIGHT)
            when (t) {
                PageTemplate.CLASSIC -> {
                    // Full grid: row line and column lines.
                    hline(top + h)
                    listOf(M, itemX - 4f, itemEnd, qtyEnd, rateEnd, amountEnd).forEach { x -> c?.drawLine(x, top, x, top + h, stroke) }
                }
                PageTemplate.MINIMAL -> hline(top + h)
                PageTemplate.MODERN -> Unit
            }
            return top + h
        }

        // ------------------------------------------------------------ totals, terms, signature

        /** Totals (right), terms (left), signature and thank-you; returns its height. */
        private fun footer(top: Float, measureOnly: Boolean): Float {
            val canvas = if (measureOnly) null else c
            return Painter(m, s, canvas).drawFooter(top)
        }

        fun drawFooter(top: Float): Float {
            val boxL = W - M - 210f
            var y = top
            fun row(k: String, v: String, p: TextPaint = body) {
                line(k, small, boxL + 8f, y + 1f)
                line(v, p, W - M - 8f, y, Paint.Align.RIGHT)
                y += 17f
            }
            if (m.discount.minor > 0 || m.tax.minor > 0) row("Subtotal", m.money(m.subtotal))
            if (m.discount.minor > 0) row("Discount", m.money(-m.discount))
            if (m.tax.minor > 0) row("Tax ${ReceiptLayout.formatRate(m.taxRateBp)}", m.money(m.tax))
            // The total: a colour bar (Modern), a double-ruled grid row (Classic), big colour text (Minimal).
            val totalText = m.money(m.total, symbol = true)
            when (t) {
                PageTemplate.MODERN -> {
                    rect(boxL, y, W - M, y + 26f, accent, 3f)
                    line("TOTAL", paint(10f, bold = true, color = onAccent), boxL + 8f, y + 7f)
                    line(totalText, paint(13f, bold = true, color = onAccent), W - M - 8f, y + 5f, Paint.Align.RIGHT)
                    y += 32f
                }
                PageTemplate.CLASSIC -> {
                    hline(y, boxL, W - M)
                    hline(y + 2.5f, boxL, W - M)
                    line("TOTAL", paint(10f, bold = true), boxL + 8f, y + 9f)
                    line(totalText, paint(13f, bold = true, color = accent), W - M - 8f, y + 7f, Paint.Align.RIGHT)
                    y += 28f
                    hline(y, boxL, W - M)
                    y += 6f
                }
                PageTemplate.MINIMAL -> {
                    hline(y, boxL, W - M)
                    line("Total", paint(10f, color = INK2), boxL + 8f, y + 11f)
                    line(totalText, paint(17f, bold = true, color = accent), W - M - 8f, y + 6f, Paint.Align.RIGHT)
                    y += 34f
                }
            }
            if (m.paid.minor > 0) row(ReceiptLayout.paidLabel(m), m.money(m.paid))
            if (m.balance.minor > 0) row("Balance due", m.money(m.balance, symbol = true), paint(10f, bold = true, color = LOSS))

            // Terms beside the totals, on the left.
            var left = top
            m.terms?.takeIf { it.isNotBlank() }?.let {
                // Classic frames the terms: text inset so the box lines up with the table edge.
                val inset = if (t == PageTemplate.CLASSIC) 8f else 0f
                line(if (t == PageTemplate.MINIMAL) "Terms" else "TERMS & CONDITIONS", label, M + inset, left + inset)
                left += 13f + inset
                left += block(it, small, M + inset, left, boxL - M - 24f - 2 * inset)
                if (t == PageTemplate.CLASSIC) {
                    left += inset
                    c?.drawRect(M, top, boxL - 16f, left, stroke)
                }
            }
            y = maxOf(y, left) + 40f
            // Signature line, then the thank-you.
            hline(y, W - M - 150f, W - M, Paint(stroke).apply { color = INK2 })
            line("Authorised signature", small, W - M - 75f, y + 4f, Paint.Align.CENTER)
            y += 30f
            y += block(m.footerText, paint(10f, bold = true, color = if (t == PageTemplate.MINIMAL) INK2 else accent), M, y, W - 2 * M, Layout.Alignment.ALIGN_CENTER)
            return y - top
        }

        private fun watermark() {
            val p = paint(110f, bold = true, color = ColorUtils.setAlphaComponent(LOSS, 40))
            c?.withRotation(-30f, W / 2, H / 2) { line("VOID", p, W / 2, H / 2 - 60f, Paint.Align.CENTER) }
        }
    }

    companion object {
        const val W = 595f
        const val H = 842f
        private const val M = 36f
        private const val HEAD_ROW = 22f
        private const val CONT_TOP = M + 34f
        private const val INK = 0xFF1A1D21.toInt()
        private const val INK2 = 0xFF5C6370.toInt()
        private const val LINE = 0xFFE3E0DA.toInt()
        private const val ZEBRA = 0xFFF7F5F2.toInt()
        private const val LOSS = 0xFFC92A2A.toInt()
        private const val PROFIT = 0xFF2B8A3E.toInt()
    }
}
