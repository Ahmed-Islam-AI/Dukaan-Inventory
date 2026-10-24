package app.dukaan.android.feature.parties

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import app.dukaan.android.core.ui.CardRow
import app.dukaan.android.core.ui.Avatar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.alsoNotify
import app.dukaan.android.core.ui.LocalNotify
import app.dukaan.android.core.ui.SearchField
import app.dukaan.android.core.db.PartyRow
import app.dukaan.android.core.db.PartyType
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.ui.EmptyState
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.userMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PartiesViewModel(private val container: AppContainer) : ViewModel() {
    val type = MutableStateFlow(PartyType.CUSTOMER)
    val query = MutableStateFlow("")

    /** null = still loading. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rows = combine(type, query) { t, q -> t to q.trim() }
        .flatMapLatest { (t, q) -> container.database.partyDao().parties(t, q) }
        .map<List<PartyRow>, List<PartyRow>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(context: android.content.Context, form: PartyForm, onResult: (String?) -> Unit) {
        val opening = form.openingPaisa(type.value) ?: return onResult(context.getString(R.string.err_price))
        viewModelScope.launch {
            try {
                container.ledger.addParty(type.value, form.name, form.phone, opening)
                onResult(null)
            } catch (e: LedgerException) {
                onResult(e.userMessage(context))
            }
        }
    }
}

/** Name, phone and opening balance as typed. Opening is entered as a positive amount either way. */
data class PartyForm(val name: String = "", val phone: String = "", val opening: String = "") {
    /** Stored sign: positive = they owe the shop. A supplier's opening is what the shop owes. */
    fun openingPaisa(type: PartyType): Money? {
        val m = if (opening.isBlank()) Money.ZERO else Money.parse(opening) ?: return null
        return if (type == PartyType.SUPPLIER) Money(-m.minor) else m
    }
}

@Composable
fun PartiesScreen(vm: PartiesViewModel, onOpen: (String) -> Unit) {
    val notify = LocalNotify.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val saved = stringResource(R.string.msg_saved)
    val customers = type == PartyType.CUSTOMER
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.nav_parties))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(customers, { vm.type.value = PartyType.CUSTOMER }, { Text(stringResource(R.string.parties_customers)) })
                FilterChip(!customers, { vm.type.value = PartyType.SUPPLIER }, { Text(stringResource(R.string.parties_suppliers)) })
            }
            SearchField(query, { vm.query.value = it }, stringResource(R.string.parties_search), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            val list = rows ?: return@Column
            if (list.isEmpty()) {
                if (query.isBlank()) EmptyState(
                    stringResource(if (customers) R.string.parties_empty_customers else R.string.parties_empty_suppliers),
                    stringResource(R.string.parties_empty_body),
                    stringResource(if (customers) R.string.parties_add_customer else R.string.parties_add_supplier),
                    { adding = true }, icon = if (customers) R.drawable.ic_person_add else R.drawable.ic_truck,
                ) else Text(stringResource(R.string.parties_no_match), Modifier.padding(16.dp), color = LocalDukaanColors.current.ink2)
                return@Column
            }
            // Customers: what's out on credit. Suppliers: what the shop still has to pay.
            val due = if (customers) list.sumOf { maxOf(it.balancePaisa, 0) } else list.sumOf { maxOf(-it.balancePaisa, 0) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (customers) R.string.parties_to_collect else R.string.parties_to_pay), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                MoneyText(Money(due), withSymbol = true, style = MaterialTheme.typography.titleMedium, color = if (customers) LocalDukaanColors.current.warning else MaterialTheme.colorScheme.onSurface)
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                items(list, key = { it.id }) { p -> Box(Modifier.animateItem()) { PartyRowView(p, type) { onOpen(p.id) } } }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(painterResource(R.drawable.ic_person_add), contentDescription = null) },
            text = { Text(stringResource(if (customers) R.string.parties_add_customer else R.string.parties_add_supplier)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (adding) {
        val context = LocalContext.current
        PartyFormDialog(
            title = stringResource(if (customers) R.string.parties_add_customer else R.string.parties_add_supplier),
            type = type, initial = PartyForm(), onDismiss = { adding = false },
        ) { form, done -> vm.add(context, form, done.alsoNotify(notify, saved)) }
    }
}

@Composable
private fun PartyRowView(p: PartyRow, type: PartyType, onClick: () -> Unit) {
    val c = LocalDukaanColors.current
    CardRow(onClick) {
        Avatar(p.name)
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall)
            p.phone?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink2) }
        }
        BalanceText(p.balancePaisa, type, horizontal = Alignment.End)
    }
}

/** Amount (always positive) with "owes you" / "you owe" / "settled" underneath. */
@Composable
fun BalanceText(balancePaisa: Long, type: PartyType, horizontal: Alignment.Horizontal, big: Boolean = false) {
    val c = LocalDukaanColors.current
    val (caption, color) = when {
        balancePaisa > 0 -> R.string.balance_owes_you to c.warning
        balancePaisa < 0 -> (if (type == PartyType.SUPPLIER) R.string.balance_you_owe else R.string.balance_advance) to MaterialTheme.colorScheme.onSurface
        else -> R.string.balance_settled to c.ink2
    }
    Column(horizontalAlignment = horizontal) {
        MoneyText(
            Money(kotlin.math.abs(balancePaisa)), withSymbol = big,
            style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium, color = color,
        )
        Text(stringResource(caption), style = MaterialTheme.typography.labelSmall, color = c.ink2)
    }
}

@Composable
fun PartyFormDialog(title: String, type: PartyType, initial: PartyForm, onDismiss: () -> Unit, onSave: (PartyForm, (String?) -> Unit) -> Unit) {
    var form by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.name, { form = form.copy(name = it) }, label = { Text(stringResource(R.string.party_name)) }, singleLine = true)
                OutlinedTextField(
                    form.phone, { form = form.copy(phone = it) }, label = { Text(stringResource(R.string.party_phone)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                OutlinedTextField(
                    form.opening, { form = form.copy(opening = it) }, singleLine = true,
                    label = { Text(stringResource(if (type == PartyType.CUSTOMER) R.string.party_opening_customer else R.string.party_opening_supplier)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(form) { msg -> if (msg == null) onDismiss() else error = msg } }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
