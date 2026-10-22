package app.dukaan.android.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.db.ClashKind
import app.dukaan.android.core.ledger.invoiceNumber
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.ui.EmptyState
import app.dukaan.android.core.ui.FormCard
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.ltr
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A clash with its bills resolved to numbers (saleId to "A-0012"). */
data class ClashItem(val kind: ClashKind, val subject: String?, val bills: List<Pair<String, String>>)

/** Owner: what two phones did offline that can't both be true (D-78). Both records are kept. */
class ClashesViewModel(container: AppContainer) : ViewModel() {
    private val sales = container.database.ledgerDao()

    /** null = loading. */
    val items = container.database.clashDao().clashes().map { list ->
        list.map { c ->
            ClashItem(c.kind, c.subject, c.saleIds?.split(',').orEmpty().mapNotNull { id ->
                sales.sale(id)?.let { id to invoiceNumber(it.invoicePrefix, it.invoiceSeq) }
            })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun ClashesScreen(vm: ClashesViewModel, onBack: () -> Unit, onSale: (String) -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.clash_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(stringResource(R.string.clash_none_title), stringResource(R.string.clash_none_body), null, {}, icon = R.drawable.ic_check_circle)
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list) { ClashCard(it, onSale) }
            }
        }
    }
}

@Composable
private fun ClashCard(c: ClashItem, onSale: (String) -> Unit) {
    val subject = ltr(c.subject.orEmpty())
    val (title, fix) = when (c.kind) {
        ClashKind.IMEI_SOLD_TWICE -> stringResource(R.string.clash_imei_sold_twice, subject) to stringResource(R.string.clash_imei_sold_twice_fix)
        ClashKind.IMEI_IN_STOCK_TWICE -> stringResource(R.string.clash_imei_in_stock_twice, subject) to stringResource(R.string.clash_imei_in_stock_twice_fix)
        ClashKind.SOLD_UNIT_REMOVED -> stringResource(R.string.clash_sold_unit_removed, subject) to stringResource(R.string.clash_sold_unit_removed_fix)
        ClashKind.RETURN_ON_VOIDED_BILL -> stringResource(R.string.clash_return_on_voided) to stringResource(R.string.clash_return_on_voided_fix)
        ClashKind.RETURNED_TOO_MUCH -> stringResource(R.string.clash_returned_too_much, c.subject.orEmpty()) to stringResource(R.string.clash_returned_too_much_fix)
        ClashKind.BILL_NUMBER_TWICE -> stringResource(R.string.clash_bill_twice, subject) to stringResource(R.string.clash_bill_twice_fix)
        ClashKind.STOCK_REMOVED_TWICE -> stringResource(R.string.clash_stock_removed_twice, c.subject.orEmpty()) to stringResource(R.string.clash_stock_removed_twice_fix)
    }
    FormCard(null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(R.drawable.ic_warning, Tone.LOSS)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(fix, style = MaterialTheme.typography.bodySmall, color = LocalDukaanColors.current.ink2)
            }
        }
        if (c.bills.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                c.bills.forEach { (id, number) ->
                    OutlinedButton(onClick = { onSale(id) }) { ButtonLabel(R.drawable.ic_receipt, ltr(number)) }
                }
            }
        }
    }
}
