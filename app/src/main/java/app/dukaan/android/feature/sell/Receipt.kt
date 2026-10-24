package app.dukaan.android.feature.sell

import androidx.compose.ui.draw.shadow
import androidx.compose.material3.FilterChip
import app.dukaan.android.core.receipt.ReceiptModel
import app.dukaan.android.core.receipt.InvoiceStyle
import app.dukaan.android.core.receipt.InvoicePage
import app.dukaan.android.core.ui.Motion
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.CardRow
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.display
import app.dukaan.android.core.ui.LocalShopFormat
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import app.dukaan.android.core.ui.alsoNotify
import app.dukaan.android.core.ui.LocalNotify
import app.dukaan.android.core.ui.ActionTile
import app.dukaan.android.core.ui.ActionBar
import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.SaleRow
import app.dukaan.android.core.ledger.invoiceNumber
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.receipt.PaperWidth
import app.dukaan.android.core.receipt.ReceiptLayout
import app.dukaan.android.core.receipt.ReceiptOutput
import app.dukaan.android.core.receipt.ReceiptRenderer
import app.dukaan.android.core.ui.EmptyState
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.ReasonDialog
import app.dukaan.android.core.ui.userMessage
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.Tag
import app.dukaan.android.core.ui.TagKind
import app.dukaan.android.core.ui.formatDateTime
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.modeLabel
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.dukaan.android.core.printer.PrintError
import app.dukaan.android.feature.more.printErrorMessage
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the receipt screen shows and shares: the A4 invoice or the printer slip (D-80). */
enum class ReceiptView { INVOICE, SLIP }

class ReceiptViewModel(private val container: AppContainer, private val saleId: String, afterSale: Boolean) : ViewModel() {
    /** The printer slip preview. */
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    /** The A4 invoice preview, one bitmap per page. */
    var pages by mutableStateOf<List<Bitmap>>(emptyList())
        private set
    /** A shop with a thermal printer mostly prints slips; everyone else shares invoices. */
    var view by mutableStateOf(if (container.prefs.printerAddress != null) ReceiptView.SLIP else ReceiptView.INVOICE)
    private var style: InvoiceStyle? = null
    var number by mutableStateOf("")
        private set
    /** For the "Sale saved" banner: what was billed, and what went on the customer's account. */
    var total by mutableStateOf<Money?>(null)
        private set
    var onCredit by mutableStateOf<Pair<Money, String>?>(null)
        private set
    var missing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var printStatus by mutableStateOf<String?>(null)
        private set
    var printing by mutableStateOf(false)
        private set
    val hasPrinter get() = container.prefs.printerAddress != null
    private var model: ReceiptModel? = null
    /** Live, so Void/Return buttons disappear as soon as the sale is voided. */
    val status = container.database.partyDao().saleStatus(saleId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { load(afterSale) }

    private fun load(afterSale: Boolean) {
        viewModelScope.launch {
            val context = container.appContext
            val model = container.receipts.model(saleId) { context.getString(modeLabel(it)) }
            if (model == null) {
                missing = true
                return@launch
            }
            this@ReceiptViewModel.model = model
            number = model.number
            total = model.total
            val due = model.total - model.paid
            onCredit = if (due.minor > 0 && model.customer != null) due to model.customer else null
            val style = container.receipts.style().also { style = it }
            withContext(Dispatchers.Default) {
                bitmap = slip(context, model, style, PaperWidth.MM80)
                pages = InvoicePage(context).bitmaps(model, style)
            }
            // D-46: print straight after Charge when a printer is set up (and allowed).
            if (afterSale && hasPrinter && container.prefs.autoPrint && container.printer.hasPermission()) printThermal(context)
        }
    }

    /** D-17: void with a reason; the receipt re-renders with VOID on it. */
    fun void(context: android.content.Context, reason: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                container.ledger.voidSale(saleId, reason)
                load(afterSale = false)
                onResult(null)
            } catch (e: LedgerException) {
                onResult(e.userMessage(context))
            }
        }
    }

    /** Thermal print at the configured paper width. A failure never touches the saved sale. */
    fun printThermal(context: android.content.Context) {
        val m = model ?: return
        val address = container.prefs.printerAddress ?: return
        if (printing) return
        printing = true
        printStatus = context.getString(R.string.printer_printing)
        viewModelScope.launch {
            try {
                val width = if (container.prefs.paperMm == 80) PaperWidth.MM80 else PaperWidth.MM58
                val image = withContext(Dispatchers.Default) { slip(context, m, style ?: container.receipts.style(), width) }
                container.printer.print(address, image)
                printStatus = context.getString(R.string.printer_printed)
            } catch (e: PrintError) {
                printStatus = printErrorMessage(context, e)
            } finally {
                printing = false
            }
        }
    }

    /** Shares what is on screen: the invoice as an A4 PDF, or the slip. */
    fun share(context: android.content.Context) {
        val m = model ?: return
        val s = style ?: return
        val slip = bitmap ?: return
        viewModelScope.launch {
            try {
                val pdf = withContext(Dispatchers.IO) {
                    if (view == ReceiptView.INVOICE) ReceiptOutput.writeInvoicePdf(context, m, s) else ReceiptOutput.writePdf(context, slip, number)
                }
                ReceiptOutput.share(context, pdf, context.getString(R.string.receipt_share_title, number))
            } catch (e: IOException) {
                error = context.getString(R.string.err_receipt_pdf)
            }
        }
    }

    fun printA4(context: android.content.Context) {
        val m = model ?: return
        style?.let { ReceiptOutput.printInvoice(context, m, it) }
    }
}

/** The slip in the shop's template, with its logo (D-80). */
fun slip(context: android.content.Context, m: ReceiptModel, style: InvoiceStyle, width: PaperWidth): Bitmap =
    ReceiptRenderer(context).render(ReceiptLayout.rows(m, style.slip, logo = style.logo != null), width, style.logo)

/** DESIGN.md § Receipt: preview at paper width; Share PDF, A4; New sale is primary after a sale. */
@Composable
fun ReceiptScreen(vm: ReceiptViewModel, afterSale: Boolean, onNewSale: () -> Unit, onBack: () -> Unit, onReturn: () -> Unit) {
    val notify = LocalNotify.current
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    var voiding by remember { mutableStateOf(false) }
    val voided = stringResource(R.string.msg_voided)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(vm.number.ifEmpty { stringResource(R.string.receipt_title) }, navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
        if (afterSale) vm.total?.let { SaleSavedBanner(it, vm.onCredit) }
        // Invoice (A4, what Share sends) or Slip (what the printer prints).
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReceiptView.entries.forEach { v ->
                FilterChip(
                    selected = vm.view == v,
                    onClick = { vm.view = v },
                    label = { Text(stringResource(if (v == ReceiptView.INVOICE) R.string.receipt_view_invoice else R.string.receipt_view_slip)) },
                    leadingIcon = { Icon(painterResource(if (v == ReceiptView.INVOICE) R.drawable.ic_receipt else R.drawable.ic_print), contentDescription = null) },
                )
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().background(LocalDukaanColors.current.sunk).verticalScroll(rememberScrollState()).padding(16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                vm.missing -> Text(stringResource(R.string.err_not_found))
                vm.bitmap == null -> Text(stringResource(R.string.receipt_rendering), color = LocalDukaanColors.current.ink2)
                vm.view == ReceiptView.SLIP -> Image(
                    vm.bitmap!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.receipt_preview, vm.number),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    vm.pages.forEach { page ->
                        Image(
                            page.asImageBitmap(),
                            contentDescription = stringResource(R.string.receipt_preview, vm.number),
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().shadow(2.dp),
                        )
                    }
                }
            }
        }
        vm.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        vm.printStatus?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.printThermal(context) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val ready = vm.bitmap != null
            // One row of picture buttons (D-67): print, share, A4, and for a past bill return / void.
            ActionBar {
                if (vm.hasPrinter) ActionTile(R.drawable.ic_print, Tone.BRAND, stringResource(R.string.action_print), Modifier.weight(1f), enabled = ready && !vm.printing) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !app.dukaan.android.dukaanPrinterAllowed(context)) {
                        permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        vm.printThermal(context)
                    }
                }
                ActionTile(R.drawable.ic_share, Tone.PROFIT, stringResource(R.string.action_share), Modifier.weight(1f), enabled = ready) { vm.share(context) }
                ActionTile(R.drawable.ic_receipt, Tone.INFO, stringResource(R.string.action_a4), Modifier.weight(1f), enabled = ready) { vm.printA4(context) }
                if (!afterSale && status == DocStatus.ACTIVE) {
                    ActionTile(R.drawable.ic_return, Tone.WARNING, stringResource(R.string.action_return), Modifier.weight(1f), onClick = onReturn)
                    ActionTile(R.drawable.ic_block, Tone.LOSS, stringResource(R.string.action_void), Modifier.weight(1f)) { voiding = true }
                }
            }
            if (afterSale) {
                Button(onClick = onNewSale, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                    Text(stringResource(R.string.receipt_new_sale), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    if (voiding) {
        ReasonDialog(
            stringResource(R.string.sale_void_title, vm.number), stringResource(R.string.sale_void_body), stringResource(R.string.receipt_void),
            onDismiss = { voiding = false },
        ) { reason, done -> vm.void(context, reason, done.alsoNotify(notify, voided)) }
    }
}

class SalesViewModel(container: AppContainer) : ViewModel() {
    val rows = container.database.saleDao().sales().map<List<SaleRow>, List<SaleRow>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun SalesScreen(vm: SalesViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onSell: () -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val ink2 = LocalDukaanColors.current.ink2
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.sales_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
        val list = rows ?: return@Column
        if (list.isEmpty()) {
            EmptyState(stringResource(R.string.sales_empty_title), stringResource(R.string.sales_empty_body), stringResource(R.string.sell_title), onSell, icon = R.drawable.ic_receipt)
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            items(list, key = { it.id }) { s -> Box(Modifier.animateItem()) {
                val voided = s.status == DocStatus.VOIDED
                CardRow({ onOpen(s.id) }) {
                    IconBadge(R.drawable.ic_receipt, if (voided) Tone.NEUTRAL else Tone.BRAND)
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                invoiceNumber(s.invoicePrefix, s.invoiceSeq) + " · " + (s.customerName ?: stringResource(R.string.sales_walk_in)),
                                style = MaterialTheme.typography.titleSmall,
                                textDecoration = if (voided) TextDecoration.LineThrough else null,
                            )
                            if (voided) Tag(TagKind.VOID) else if (s.paidPaisa < s.totalPaisa) Tag(TagKind.CREDIT)
                        }
                        Text(formatDateTime(s.date), style = MaterialTheme.typography.bodySmall, color = ink2)
                    }
                    MoneyText(Money(s.totalPaisa), color = if (voided) ink2 else MaterialTheme.colorScheme.onSurface)
                }
            } }
        }
    }
}

/** Right after Charge: a clear green "done", the amount, and any credit, before the receipt. */
@Composable
private fun SaleSavedBanner(total: Money, onCredit: Pair<Money, String>?) {
    val c = LocalDukaanColors.current
    val format = LocalShopFormat.current
    // Pops in once (scale from 85% + fade): the moment the shopkeeper knows the sale is done.
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(shown, enter = scaleIn(tween(Motion.MEDIUM), initialScale = 0.85f) + fadeIn(tween(Motion.MEDIUM))) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().background(c.profitBg, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = c.profit, modifier = Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.receipt_saved), style = MaterialTheme.typography.titleMedium, color = c.profit)
            onCredit?.let { (due, who) ->
                Text(stringResource(R.string.receipt_on_credit, due.display(format, withSymbol = true), who), style = MaterialTheme.typography.bodySmall, color = c.warning)
            }
        }
        MoneyText(total, withSymbol = true, style = MaterialTheme.typography.titleLarge)
    }
    }
}
