package app.dukaan.android.core.receipt

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Receipt delivery that needs no printer driver of ours: PDF via the share sheet (WhatsApp) and
 * A4 via Android's print framework. Bluetooth thermal printing lives in core/printer.
 */
object ReceiptOutput {

    /** Writes a receipt-length PDF (1 px = 1 pt) to cache/receipts and returns the file. */
    @Throws(IOException::class)
    fun writePdf(context: Context, bitmap: Bitmap, number: String): File {
        val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
        val file = File(dir, "$number.pdf")
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create())
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdf.finishPage(page)
            FileOutputStream(file).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        return file
    }

    /** Opens the share sheet with the PDF attached (WhatsApp, email, Drive...). */
    fun share(context: Context, pdf: File, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", pdf)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The A4 invoice as a real multi-page PDF (vector text), for sharing (D-80). */
    @Throws(IOException::class)
    fun writeInvoicePdf(context: Context, m: ReceiptModel, style: InvoiceStyle): File {
        val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
        val file = File(dir, "${m.number}.pdf")
        val renderer = InvoicePage(context)
        val pdf = PdfDocument()
        try {
            repeat(renderer.pageCount(m, style)) { i ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(InvoicePage.W.toInt(), InvoicePage.H.toInt(), i + 1).create())
                renderer.draw(page.canvas, m, style, i)
                pdf.finishPage(page)
            }
            FileOutputStream(file).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        return file
    }

    /** Prints the A4 invoice through Android's print framework, page by page (D-80). */
    fun printInvoice(context: Context, m: ReceiptModel, style: InvoiceStyle) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attrs = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
        manager.print("Invoice ${m.number}", InvoiceAdapter(context, m, style), attrs)
    }

    /** Draws each invoice page scaled to the printer's page (A4 = 1:1 in points). */
    private class InvoiceAdapter(private val context: Context, private val m: ReceiptModel, private val style: InvoiceStyle) : PrintDocumentAdapter() {
        private var attributes: PrintAttributes? = null
        private val renderer = InvoicePage(context)

        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            attributes = new
            val info = PrintDocumentInfo.Builder("invoice-${m.number}.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(renderer.pageCount(m, style))
                .build()
            cb.onLayoutFinished(info, true)
        }

        override fun onWrite(pages: Array<out PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
            val attrs = attributes ?: run { cb.onWriteFailed("no layout"); return }
            val doc = PrintedPdfDocument(context, attrs)
            try {
                repeat(renderer.pageCount(m, style)) { i ->
                    if (cancel?.isCanceled == true) { cb.onWriteCancelled(); return }
                    val page = doc.startPage(i)
                    val c = page.canvas
                    val k = minOf(c.width / InvoicePage.W, c.height / InvoicePage.H)
                    c.scale(k, k)
                    renderer.draw(c, m, style, i)
                    doc.finishPage(page)
                }
                FileOutputStream(dest.fileDescriptor).use { doc.writeTo(it) }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: IOException) {
                cb.onWriteFailed(e.message)
            } finally {
                doc.close()
            }
        }
    }
}
