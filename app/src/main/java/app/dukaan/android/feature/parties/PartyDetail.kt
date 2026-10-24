package app.dukaan.android.feature.parties

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.core.net.toUri
import app.dukaan.android.R
import app.dukaan.android.core.ui.alsoNotify
import app.dukaan.android.core.ui.LocalNotify
import app.dukaan.android.core.ui.ModePicker
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.Panel
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.CardRow
import app.dukaan.android.core.ui.Avatar
import app.dukaan.android.core.ui.ActionTile
import app.dukaan.android.core.ui.ActionBar
import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.PartyEntity
import app.dukaan.android.core.db.PartyType
import app.dukaan.android.core.db.PaymentDirection
import app.dukaan.android.core.db.PaymentKind
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.StatementRow
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.ledger.invoiceNumber
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.ui.LocalShopFormat
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.ReasonDialog
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.SectionLabel
import app.dukaan.android.core.ui.Tag
import app.dukaan.android.core.ui.TagKind
import app.dukaan.android.core.ui.display
import app.dukaan.android.core.ui.formatDateTime
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PartyDetail(val party: PartyEntity?, val balancePaisa: Long, val statement: List<StatementRow>, val shopName: String)

class PartyDetailViewModel(private val container: AppContainer, private val partyId: String) : ViewModel() {
    private val dao = container.database.partyDao()
    val state = combine(dao.party(partyId), dao.balance(partyId), dao.statement(partyId), container.database.shopDao().observe()) { p, b, s, shop ->
        PartyDetail(p, b ?: 0, s, shop?.name.orEmpty())
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun run(context: android.content.Context, onResult: (String?) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                onResult(null)
            } catch (e: LedgerException) {
                onResult(e.userMessage(context))
            }
        }
    }

    fun pay(context: android.content.Context, amount: String, mode: PaymentMode, note: String, onResult: (String?) -> Unit) {
        val m = Money.parse(amount)?.takeIf { it.minor > 0 } ?: return onResult(context.getString(R.string.err_amount))
        run(context, onResult) { container.ledger.recordPayment(partyId, m, mode, note, System.currentTimeMillis()) }
    }

    fun voidPayment(context: android.content.Context, paymentId: String, reason: String, onResult: (String?) -> Unit) =
        run(context, onResult) { container.ledger.voidPayment(paymentId, reason) }

    fun update(context: android.content.Context, type: PartyType, form: PartyForm, onResult: (String?) -> Unit) {
        val opening = form.openingPaisa(type) ?: return onResult(context.getString(R.string.err_price))
        run(context, onResult) { container.ledger.updateParty(partyId, form.name, form.phone, opening) }
    }
}

@Composable
fun PartyDetailScreen(vm: PartyDetailViewModel, onBack: () -> Unit, onOpenSale: (String) -> Unit) {
    val notify = LocalNotify.current
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var paying by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var voidingPayment by remember { mutableStateOf<String?>(null) }
    val paid = stringResource(R.string.msg_payment_saved)
    val saved = stringResource(R.string.msg_saved)
    val cancelled = stringResource(R.string.msg_cancelled)
    val s = state
    val party = s?.party
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            party?.name.orEmpty(), party?.phone,
            navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
        )
        if (s == null) return@Column
        if (party == null) {
            Text(stringResource(R.string.err_not_found), Modifier.padding(16.dp))
            return@Column
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Avatar(party.name, size = 52.dp)
                    BalanceText(s.balancePaisa, party.type, horizontal = Alignment.Start, big = true)
                }
            }
            val customer = party.type == PartyType.CUSTOMER
            ActionBar {
                ActionTile(
                    if (customer) R.drawable.ic_money_in else R.drawable.ic_money_out, if (customer) Tone.PROFIT else Tone.LOSS,
                    stringResource(if (customer) R.string.action_receive else R.string.action_pay), Modifier.weight(1f),
                ) { paying = true }
                if (customer && s.balancePaisa > 0) {
                    val format = LocalShopFormat.current
                    val text = stringResource(R.string.party_reminder_text, party.name, Money(s.balancePaisa).display(format, withSymbol = true), s.shopName)
                    ActionTile(R.drawable.ic_message, Tone.BRAND, stringResource(R.string.action_remind), Modifier.weight(1f)) { remind(context, text) }
                }
                party.phone?.let { phone -> ActionTile(R.drawable.ic_call, Tone.INFO, stringResource(R.string.action_call), Modifier.weight(1f)) { call(context, phone) } }
                ActionTile(R.drawable.ic_edit, Tone.NEUTRAL, stringResource(R.string.edit), Modifier.weight(1f)) { editing = true }
            }
        }
        SectionLabel(stringResource(R.string.party_history))
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            items(s.statement, key = { it.kind + it.refId }) { row ->
                StatementRowView(row) {
                    when {
                        row.kind == "SALE" -> onOpenSale(row.refId)
                        row.kind == "PAY" && row.payKind == PaymentKind.PARTY && !row.reversed -> voidingPayment = row.refId
                    }
                }
            }
            if (s.statement.isEmpty()) item { Text(stringResource(R.string.party_no_history), color = LocalDukaanColors.current.ink2) }
        }
    }
    if (paying && party != null) PaymentDialog(party.type, onDismiss = { paying = false }) { amount, mode, note, done -> vm.pay(context, amount, mode, note, done.alsoNotify(notify, paid)) }
    if (editing && party != null) {
        val opening = if (party.type == PartyType.SUPPLIER) -party.openingBalancePaisa else party.openingBalancePaisa
        PartyFormDialog(
            stringResource(R.string.party_edit), party.type,
            PartyForm(party.name, party.phone.orEmpty(), if (opening == 0L) "" else Money(opening).format(symbol = null, lakhGrouping = false)),
            onDismiss = { editing = false },
        ) { form, done -> vm.update(context, party.type, form, done.alsoNotify(notify, saved)) }
    }
    voidingPayment?.let { id ->
        ReasonDialog(
            stringResource(R.string.payment_void_title), stringResource(R.string.payment_void_body), stringResource(R.string.payment_void),
            onDismiss = { voidingPayment = null },
        ) { reason, done -> vm.voidPayment(context, id, reason, done.alsoNotify(notify, cancelled)) }
    }
}

/** Opens the dialer with the number filled in (no CALL_PHONE permission needed). */
private fun call(context: android.content.Context, phone: String) {
    context.startActivity(Intent(Intent.ACTION_DIAL, ("tel:" + phone.filter { it.isDigit() || it == '+' }).toUri()))
}

/** Plain share sheet: the shopkeeper picks WhatsApp (or SMS) and the customer. */
private fun remind(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

@Composable
private fun StatementRowView(row: StatementRow, onClick: () -> Unit) {
    val c = LocalDukaanColors.current
    val cancelled = row.status == DocStatus.VOIDED || row.reversed
    val number = if (row.invoicePrefix != null && row.invoiceSeq != null) invoiceNumber(row.invoicePrefix, row.invoiceSeq) else ""
    val title = when (row.kind) {
        "SALE" -> stringResource(R.string.statement_sale, number)
        "RETURN" -> stringResource(R.string.statement_return, number)
        "PURCHASE" -> row.note?.let { stringResource(R.string.statement_purchase_bill, it) } ?: stringResource(R.string.statement_purchase)
        else -> stringResource(
            when (row.payKind) {
                PaymentKind.SALE -> R.string.statement_paid_with_bill
                PaymentKind.PURCHASE -> R.string.statement_paid_with_purchase
                PaymentKind.REFUND -> R.string.statement_refund
                else -> if (row.direction == PaymentDirection.IN) R.string.statement_payment_in else R.string.statement_payment_out
            }
        )
    }
    // Money coming to the shop is green; what adds to a debt stays ink.
    val color = when {
        cancelled -> c.ink2
        row.kind == "PAY" && row.direction == PaymentDirection.IN -> c.profit
        else -> MaterialTheme.colorScheme.onSurface
    }
    // Picture per kind: bill, return, purchase, money in, money out.
    val (icon, tone) = when {
        row.kind == "SALE" -> R.drawable.ic_receipt to Tone.BRAND
        row.kind == "RETURN" -> R.drawable.ic_return to Tone.WARNING
        row.kind == "PURCHASE" -> R.drawable.ic_truck to Tone.INFO
        row.direction == PaymentDirection.IN -> R.drawable.ic_money_in to Tone.PROFIT
        else -> R.drawable.ic_money_out to Tone.LOSS
    }
    CardRow(onClick) {
        IconBadge(icon, if (cancelled) Tone.NEUTRAL else tone)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, textDecoration = if (cancelled) TextDecoration.LineThrough else null)
                if (cancelled) Tag(TagKind.VOID)
            }
            val sub = formatDateTime(row.date) + if (row.kind == "PAY" && row.note != null) " · ${row.note}" else ""
            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
        MoneyText(Money(row.amountPaisa), color = color)
    }
}

@Composable
private fun PaymentDialog(type: PartyType, onDismiss: () -> Unit, onSave: (String, PaymentMode, String, (String?) -> Unit) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(PaymentMode.CASH) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (type == PartyType.CUSTOMER) R.string.party_receive else R.string.party_pay)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    amount, { amount = it }, label = { Text(stringResource(R.string.payment_amount)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                ModePicker(mode) { mode = it }
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.payment_note)) }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(amount, mode, note) { msg -> if (msg == null) onDismiss() else error = msg } }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
