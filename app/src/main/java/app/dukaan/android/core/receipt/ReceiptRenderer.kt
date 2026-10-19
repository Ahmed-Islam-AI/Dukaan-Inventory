package app.dukaan.android.core.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import app.dukaan.android.R

/** Paper widths in printer dots (DESIGN.md § Receipt layout). */
enum class PaperWidth(val px: Int) { MM58(384), MM80(576) }

/**
 * Draws slip [Row]s onto a white bitmap (D-21): the same image goes to the thermal printer and the
 * slip PDF, so Urdu names print exactly as they look on screen. Text goes through StaticLayout, so
 * long names wrap and right-to-left scripts shape correctly. [logo] is drawn in greys (thermal
 * printers only know black and white).
 */
class ReceiptRenderer(context: Context) {

    private val sans: Typeface = ResourcesCompat.getFont(context, R.font.plex_sans) ?: Typeface.SANS_SERIF
    private val mono: Typeface = ResourcesCompat.getFont(context, R.font.plex_mono_medium) ?: Typeface.MONOSPACE

    fun render(rows: List<Row>, width: PaperWidth, logo: Bitmap? = null): Bitmap {
        val w = width.px
        val scale = w / 384f
        val margin = 8f * scale
        val gap = 12f * scale
        val body = paint(22f * scale, bold = false)
        val small = paint(19f * scale, bold = false)
        val bold = paint(22f * scale, bold = true)
        val big = paint(28f * scale, bold = true)
        val title = paint(30f * scale, bold = true)
        val bannerPaint = paint(26f * scale, bold = true).apply { color = Color.WHITE }
        val monoPaint = TextPaint(body).apply { typeface = mono; textSize = 20f * scale }
        val monoSmall = TextPaint(monoPaint).apply { textSize = 17f * scale }
        val indent = 18f * scale
        val content = w - 2 * margin
        val bandPad = 8f * scale

        // Pass 1: build layouts and measure height. Pass 2: draw.
        val blocks = rows.mapNotNull { row ->
            when (row) {
                is Row.Title -> Block.Single(layout(row.text, title, content, Layout.Alignment.ALIGN_CENTER), 0f)
                is Row.Center -> Block.Single(layout(row.text, if (row.small) small else body, content, Layout.Alignment.ALIGN_CENTER), 0f)
                is Row.Text -> {
                    val x = if (row.indent) indent else 0f
                    val p = when { row.mono && row.small -> monoSmall; row.mono -> monoPaint; row.small -> small; else -> body }
                    Block.Single(layout(row.text, p, content - x, Layout.Alignment.ALIGN_NORMAL), x)
                }
                is Row.Pair -> {
                    val p = when {
                        row.emphasis && row.left == "TOTAL" -> big
                        row.emphasis -> bold
                        row.small -> small
                        else -> body
                    }
                    val rightW = p.measureText(row.right)
                    val x = if (row.indent) indent else 0f
                    val left = layout(row.left, p, (content - x - rightW - gap).coerceAtLeast(content / 3), Layout.Alignment.ALIGN_NORMAL)
                    Block.Pair(left, row.right, p, x)
                }
                is Row.Banner -> {
                    val rightW = row.right?.let { bannerPaint.measureText(it) } ?: 0f
                    val align = if (row.right == null) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
                    val left = layout(row.left, bannerPaint, content - 2 * bandPad - rightW - (if (row.right != null) gap else 0f), align)
                    Block.Banner(left, row.right, bannerPaint, left.height + 2 * bandPad)
                }
                Row.Divider -> Block.Divider
                Row.Rule -> Block.Rule
                Row.Logo -> logo?.let {
                    val maxW = content * 0.5f
                    val maxH = 110f * scale
                    val k = minOf(maxW / it.width, maxH / it.height)
                    Block.Image(it, it.width * k, it.height * k)
                }
            }
        }
        val spacing = 4f * scale
        val dividerH = 16f * scale
        val height = (2 * margin + blocks.sumOf { b ->
            when (b) {
                is Block.Single -> (b.layout.height + spacing).toDouble()
                is Block.Pair -> (b.left.height + spacing).toDouble()
                is Block.Banner -> (b.height + spacing * 2).toDouble()
                is Block.Image -> (b.h + spacing * 2).toDouble()
                Block.Divider, Block.Rule -> dividerH.toDouble()
            }
        }).toInt()

        val bitmap = createBitmap(w, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val dash = Paint().apply {
            color = Color.BLACK; strokeWidth = 2f * scale; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6f * scale, 4f * scale), 0f)
        }
        val solid = Paint().apply { color = Color.BLACK; strokeWidth = 4f * scale; style = Paint.Style.STROKE }
        val fill = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        val grey = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        var y = margin
        for (b in blocks) {
            when (b) {
                is Block.Single -> {
                    canvas.withTranslation(margin + b.x, y) { b.layout.draw(this) }
                    y += b.layout.height + spacing
                }
                is Block.Pair -> {
                    canvas.withTranslation(margin + b.x, y) { b.left.draw(this) }
                    val baseline = y - b.paint.fontMetrics.ascent
                    canvas.drawText(b.right, w - margin - b.paint.measureText(b.right), baseline, b.paint)
                    y += b.left.height + spacing
                }
                is Block.Banner -> {
                    y += spacing
                    canvas.drawRect(margin, y, w - margin, y + b.height, fill)
                    canvas.withTranslation(margin + bandPad, y + bandPad) { b.left.draw(this) }
                    b.right?.let { canvas.drawText(it, w - margin - bandPad - b.paint.measureText(it), y + bandPad - b.paint.fontMetrics.ascent, b.paint) }
                    y += b.height + spacing
                }
                is Block.Image -> {
                    y += spacing
                    val left = (w - b.w) / 2
                    canvas.drawBitmap(b.bitmap, null, RectF(left, y, left + b.w, y + b.h), grey)
                    y += b.h + spacing
                }
                Block.Divider -> {
                    canvas.drawLine(margin, y + dividerH / 2, w - margin, y + dividerH / 2, dash)
                    y += dividerH
                }
                Block.Rule -> {
                    canvas.drawLine(margin, y + dividerH / 2, w - margin, y + dividerH / 2, solid)
                    y += dividerH
                }
            }
        }
        return bitmap
    }

    private fun paint(size: Float, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = Typeface.create(sans, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(text: String, paint: TextPaint, width: Float, align: Layout.Alignment) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(align)
            .setIncludePad(false)
            .build()

    private sealed interface Block {
        data class Single(val layout: StaticLayout, val x: Float) : Block
        data class Pair(val left: StaticLayout, val right: String, val paint: TextPaint, val x: Float) : Block
        data class Banner(val left: StaticLayout, val right: String?, val paint: TextPaint, val height: Float) : Block
        data class Image(val bitmap: Bitmap, val w: Float, val h: Float) : Block
        data object Divider : Block
        data object Rule : Block
    }
}
