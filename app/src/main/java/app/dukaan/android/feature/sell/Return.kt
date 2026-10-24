package app.dukaan.android.feature.sell

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.alsoNotify
import app.dukaan.android.core.ui.LocalNotify
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.Avatar
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.CardRow
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.ui.ModePicker
import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.ReturnRow
import app.dukaan.android.core.db.SaleLineEntity
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.ledger.ReturnLineIn
import app.dukaan.android.core.ledger.ReturnResult
import app.dukaan.android.core.ledger.SaleMath
import app.dukaan.android.core.ledger.invoiceNumber
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.ui.LocalShopFormat
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.ReasonDialog
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.SectionLabel
import app.dukaan.android.core.ui.Tag
import app.dukaan.android.core.ui.TagKind
import app.dukaan.android.core.ui.display
import app.dukaan.android.core.ui.formatDateTime
import app.dukaan.android.core.ui.ltr
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A sold line as the return screen needs it: how much can still come back, at what refund. */
data class ReturnableLine(
    val line: SaleLineEntity,
    val unit: String,
    val serial: String?,
    val returnedMilli: Long,
    val refundedPaisa: Long,
    val netPaisa: Long,
) {
    val leftMilli get() = line.qtyMilli - returnedMilli
}

class ReturnViewModel(private val container: AppContainer, private val saleId: String) : ViewModel() {
    var number by mutableStateOf("")
        private set
    var customer by mutableStateOf<String?>(null)
        private set
    var lines by mutableStateOf<List<ReturnableLine>?>(null)
        private set
    /** Typed quantity per sale line id (SERIAL lines: "1" when ticked). */
    val qty = mutableStateMapOf<String, String>()
    var reason by mutableStateOf("")
    var mode by mutableStateOf(PaymentMode.CASH)
    var error by mutableStateOf<String?>(null)
    /** Set after a save: what happened to the money, shown once. */
    var done by mutableStateOf<ReturnResult?>(null)
    val returns = container.database.partyDao().returns(saleId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val sales = container.database.saleDao()
            val ledger = container.database.ledgerDao()
            val sale = sales.sale(saleId) ?: return@launch
            number = invoiceNumber(sale.invoicePrefix, sale.invoiceSeq)
            customer = sale.customerId?.let { sales.party(it)?.name }
            val rows = sales.lines(saleId)
            val nets = SaleMath.lineNets(rows.map { it.lineTotalPaisa }, sale.totalPaisa)
            lines = rows.mapIndexed { i, l ->
                ReturnableLine(
                    l, sales.unitOf(l.variantId).orEmpty(), l.serialUnitId?.let { sales.serialNumber(it) },
                    ledger.returnedQtyMilli(l.id), ledger.refundedPaisa(l.id), nets[i],
                )
            }
            qty.clear()
        }
    }

    private fun parsed(l: ReturnableLine): Qty? = qty[l.line.id]?.takeIf { it.isNotBlank() }?.let { Qty.parse(it, allowDecimal = true) }

    /** Same cumulative rounding the ledger uses (D-37), so the preview is the real refund. */
    fun preview(): Money = Money(
        lines.orEmpty().sumOf { l ->
            val q = parsed(l)?.milli?.takeIf { it in 1..l.leftMilli } ?: return@sumOf 0L
            SaleMath.refund(l.netPaisa, l.line.qtyMilli, l.returnedMilli, q, l.refundedPaisa)
        }
    )

    fun save(context: android.content.Context) {
        val list = lines ?: return
        val wanted = mutableListOf<ReturnLineIn>()
        for (l in list) {
            val text = qty[l.line.id]?.takeIf { it.isNotBlank() } ?: continue
            val q = Qty.parse(text, allowDecimal = true)
            if (q == null || q.milli > l.leftMilli) {
                error = context.getString(R.string.return_err_qty, l.line.nameSnapshot)
                return
            }
            wanted += ReturnLineIn(l.line.id, q)
        }
        viewModelScope.launch {
            try {
                done = container.ledger.returnItems(saleId, wanted, reason, mode)
                error = null
                reason = ""
                load()
            } catch (e: LedgerException) {
                error = e.userMessage(context)
            }
        }
    }

    fun voidReturn(context: android.content.Context, returnId: String, reason: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                container.ledger.voidReturn(returnId, reason)
                load()
                onResult(null)
            } catch (e: LedgerException) {
                onResult(e.userMessage(context))
            }
        }
    }
}

@Composable
fun ReturnScreen(vm: ReturnViewModel, onBack: () -> Unit) {
    val notify = LocalNotify.current
    val context = LocalContext.current
    val returns by vm.returns.collectAsStateWithLifecycle()
    var cancelling by remember { mutableStateOf<String?>(null) }
    val cancelled = stringResource(R.string.msg_cancelled)
    val ink2 = LocalDukaanColors.current.ink2
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(
            stringResource(R.string.return_title), vm.number,
            navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
        )
        val lines = vm.lines ?: return@Column
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(lines, key = { it.line.id }) { l -> ReturnLineRow(vm, l) }
            if (returns.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.return_past), Modifier.padding(top = 8.dp)) }
                items(returns, key = { it.id }) { r -> PastReturn(r) { cancelling = r.id } }
            }
        }
        val refund = vm.preview()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vm.reason, { vm.reason = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.return_reason)) }, singleLine = true)
            Text(stringResource(R.string.return_refund_by), style = MaterialTheme.typography.bodySmall, color = ink2)
            ModePicker(vm.mode) { vm.mode = it }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.save(context) }, enabled = lines.any { vm.qty[it.line.id]?.isNotBlank() == true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                ButtonLabel(R.drawable.ic_return, stringResource(R.string.return_save, refund.display(LocalShopFormat.current, withSymbol = true)))
            }
        }
    }
    vm.done?.let { r ->
        val format = LocalShopFormat.current
        val credit = r.refund - r.cashOut
        val msg = when {
            credit.minor == 0L -> stringResource(R.string.return_done_cash, r.cashOut.display(format, true))
            r.cashOut.minor == 0L -> stringResource(R.string.return_done_credit, credit.display(format, true), vm.customer.orEmpty())
            else -> stringResource(R.string.return_done_both, credit.display(format, true), vm.customer.orEmpty(), r.cashOut.display(format, true))
        }
        AlertDialog(
            onDismissRequest = { vm.done = null },
            title = { Text(stringResource(R.string.return_saved)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.done = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
    cancelling?.let { id ->
        ReasonDialog(
            stringResource(R.string.return_void_title), stringResource(R.string.return_void_body), stringResource(R.string.return_void),
            onDismiss = { cancelling = null },
        ) { reason, done -> vm.voidReturn(context, id, reason, done.alsoNotify(notify, cancelled)) }
    }
}

@Composable
private fun ReturnLineRow(vm: ReturnViewModel, l: ReturnableLine) {
    val ink2 = LocalDukaanColors.current.ink2
    CardRow(null) {
        if (l.serial != null) IconBadge(R.drawable.ic_phone_android, Tone.INFO) else Avatar(l.line.nameSnapshot)
        Column(Modifier.weight(1f)) {
            Text(l.line.nameSnapshot, style = MaterialTheme.typography.titleSmall)
            l.serial?.let { Text(ltr(it), style = MaterialTheme.typography.bodySmall, color = ink2) }
            Text(
                stringResource(R.string.return_sold_line, Qty(l.line.qtyMilli).format(), Qty(l.returnedMilli).format()),
                style = MaterialTheme.typography.bodySmall, color = ink2,
            )
        }
        when {
            l.leftMilli <= 0 -> Text(stringResource(R.string.return_all_back), style = MaterialTheme.typography.bodySmall, color = ink2)
            l.serial != null -> Checkbox(vm.qty[l.line.id] == "1", { vm.qty[l.line.id] = if (it) "1" else "" })
            else -> OutlinedTextField(
                vm.qty[l.line.id].orEmpty(), { vm.qty[l.line.id] = it }, Modifier.width(96.dp),
                placeholder = { Text(ltr("0")) }, singleLine = true, suffix = { if (l.unit != "pcs") Text(l.unit) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
    }
}

@Composable
private fun PastReturn(r: ReturnRow, onCancel: () -> Unit) {
    val ink2 = LocalDukaanColors.current.ink2
    val voided = r.status == DocStatus.VOIDED
    CardRow(null) {
        IconBadge(R.drawable.ic_return, if (voided) Tone.NEUTRAL else Tone.WARNING)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MoneyText(Money(r.refundPaisa), style = MaterialTheme.typography.titleSmall)
                if (voided) Tag(TagKind.VOID)
            }
            val why = if (voided) r.voidReason else r.reason
            Text(formatDateTime(r.date) + (why?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = ink2,
                textDecoration = if (voided) TextDecoration.LineThrough else null)
        }
        if (!voided) TextButton(onClick = onCancel) { Text(stringResource(R.string.return_void), color = LocalDukaanColors.current.loss) }
    }
}
