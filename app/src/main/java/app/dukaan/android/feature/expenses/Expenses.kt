package app.dukaan.android.feature.expenses

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.CardRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.alsoNotify
import app.dukaan.android.core.ui.LocalNotify
import app.dukaan.android.core.db.ExpenseEntity
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.ui.EmptyState
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.formatDate
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExpensesViewModel(private val container: AppContainer) : ViewModel() {
    private val dao = container.database.partyDao()
    /** null = still loading. */
    val rows = dao.expenses().map<List<ExpenseEntity>, List<ExpenseEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val categories = dao.expenseCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(context: android.content.Context, category: String, amount: String, note: String, onResult: (String?) -> Unit) {
        val m = Money.parse(amount)?.takeIf { it.minor > 0 } ?: return onResult(context.getString(R.string.err_amount))
        viewModelScope.launch {
            try {
                container.ledger.addExpense(category, m, note, System.currentTimeMillis())
                onResult(null)
            } catch (e: LedgerException) {
                onResult(e.userMessage(context))
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { container.ledger.deleteExpense(id) }
    }
}

/** Shop running costs (rent, bills, wages). They lower net profit in M5 reports. */
@Composable
fun ExpensesScreen(vm: ExpensesViewModel, onBack: () -> Unit) {
    val notify = LocalNotify.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ExpenseEntity?>(null) }
    val deleted = stringResource(R.string.msg_deleted)
    val ink2 = LocalDukaanColors.current.ink2
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.expenses_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
            val list = rows ?: return@Column
            if (list.isEmpty()) {
                EmptyState(stringResource(R.string.expenses_empty_title), stringResource(R.string.expenses_empty_body), stringResource(R.string.expenses_add), { adding = true }, icon = R.drawable.ic_wallet)
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                items(list, key = { it.id }) { e -> Box(Modifier.animateItem()) {
                    CardRow({ deleting = e }) {
                        IconBadge(R.drawable.ic_wallet, Tone.LOSS)
                        Column(Modifier.weight(1f)) {
                            Text(e.category, style = MaterialTheme.typography.titleSmall)
                            Text(formatDate(e.date) + (e.note?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = ink2)
                        }
                        MoneyText(Money(e.amountPaisa))
                    }
                } }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            text = { Text(stringResource(R.string.expenses_add)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (adding) AddExpenseDialog(vm, categories) { adding = false }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.expenses_delete_title)) },
            text = { Text(stringResource(R.string.expenses_delete_body, e.category)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(e.id); deleting = null; notify(deleted) }) {
                    Text(stringResource(R.string.expenses_delete), color = LocalDukaanColors.current.loss)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AddExpenseDialog(vm: ExpensesViewModel, categories: List<String>, onDismiss: () -> Unit) {
    val notify = LocalNotify.current
    val context = LocalContext.current
    var category by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val added = stringResource(R.string.msg_expense_added)
    // Past categories first; a new shop gets common ones to tap.
    val defaults = stringResource(R.string.expenses_default_categories).split(',', '،').map { it.trim() } // Urdu comma too
    val chips = (categories + defaults).distinctBy { it.lowercase() }.take(6)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expenses_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(category, { category = it }, label = { Text(stringResource(R.string.expenses_category)) }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { c -> FilterChip(category.equals(c, ignoreCase = true), { category = c }, { Text(c) }) }
                }
                OutlinedTextField(
                    amount, { amount = it }, label = { Text(stringResource(R.string.payment_amount)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.payment_note)) }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.add(context, category, amount, note, { msg: String? -> if (msg == null) onDismiss() else error = msg }.alsoNotify(notify, added)) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
