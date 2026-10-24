package app.dukaan.android.feature.purchase

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.CardRow
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.alsoNotify
import app.dukaan.android.core.ui.LocalNotify
import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.PurchaseEntity
import app.dukaan.android.core.db.PurchaseLineRow
import app.dukaan.android.core.db.PurchaseRow
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.ui.EmptyState
import app.dukaan.android.core.ui.LocalShopFormat
import app.dukaan.android.core.ui.display
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.ReasonDialog
import app.dukaan.android.core.ui.RowDivider
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.Tag
import app.dukaan.android.core.ui.TagKind
import app.dukaan.android.core.ui.formatDate
import app.dukaan.android.core.ui.ltr
import app.dukaan.android.core.ui.qtyLabel
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PurchasesViewModel(container: AppContainer) : ViewModel() {
    /** null = still loading. */
    val rows = container.database.catalogDao().purchases().map<List<PurchaseRow>, List<PurchaseRow>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun PurchasesScreen(vm: PurchasesViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val ink2 = LocalDukaanColors.current.ink2
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.purchases_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
            val list = rows ?: return@Column
            if (list.isEmpty()) {
                EmptyState(stringResource(R.string.purchases_empty_title), stringResource(R.string.purchases_empty_body), stringResource(R.string.purchase_new_title), onNew, icon = R.drawable.ic_truck)
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                items(list, key = { it.id }) { p -> Box(Modifier.animateItem()) {
                    val voided = p.status == DocStatus.VOIDED
                    CardRow({ onOpen(p.id) }) {
                        IconBadge(R.drawable.ic_truck, if (voided) Tone.NEUTRAL else Tone.INFO)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    p.supplierName ?: stringResource(R.string.purchase_no_supplier),
                                    style = MaterialTheme.typography.titleSmall,
                                    textDecoration = if (voided) TextDecoration.LineThrough else null,
                                )
                                if (voided) Tag(TagKind.VOID)
                            }
                            Text(
                                formatDate(p.date) + " · " + pluralStringResource(R.plurals.purchase_lines, p.lineCount, p.lineCount),
                                style = MaterialTheme.typography.bodySmall, color = ink2,
                            )
                        }
                        MoneyText(Money(p.totalPaisa), hideable = true, color = if (voided) ink2 else MaterialTheme.colorScheme.onSurface)
                    }
                } }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            text = { Text(stringResource(R.string.purchase_new_title)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

class PurchaseDetailViewModel(private val container: AppContainer, private val purchaseId: String) : ViewModel() {
    private val catalog = container.database.catalogDao()
    val state = combine(catalog.purchase(purchaseId), catalog.purchaseLines(purchaseId), catalog.purchaseSupplier(purchaseId)) { p, l, s -> Triple(p, l, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun void(context: android.content.Context, reason: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                container.ledger.voidPurchase(purchaseId, reason)
                onResult(null)
            } catch (e: LedgerException) {
                onResult(e.userMessage(context))
            }
        }
    }
}

@Composable
fun PurchaseDetailScreen(vm: PurchaseDetailViewModel, onBack: () -> Unit) {
    val notify = LocalNotify.current
    val state by vm.state.collectAsStateWithLifecycle()
    var voiding by remember { mutableStateOf(false) }
    val voided = stringResource(R.string.msg_voided)
    val ink2 = LocalDukaanColors.current.ink2
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.purchase_detail_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
        val (purchase, lines, supplier) = state ?: return@Column
        if (purchase == null) {
            Text(stringResource(R.string.err_not_found), Modifier.padding(16.dp))
            return@Column
        }
        PurchaseHeader(purchase, supplier)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(lines, key = { it.lineId }) { l -> LineRowView(l); RowDivider() }
        }
        if (purchase.status == DocStatus.ACTIVE) {
            OutlinedButton(onClick = { voiding = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.purchase_void), color = LocalDukaanColors.current.loss)
            }
        } else {
            Text(
                stringResource(R.string.purchase_voided_reason, purchase.voidReason.orEmpty()),
                Modifier.padding(16.dp), color = ink2,
            )
        }
    }
    if (voiding) {
        val context = LocalContext.current
        ReasonDialog(
            stringResource(R.string.purchase_void_title), stringResource(R.string.purchase_void_body), stringResource(R.string.purchase_void),
            onDismiss = { voiding = false },
        ) { reason, done -> vm.void(context, reason, done.alsoNotify(notify, voided)) }
    }
}

@Composable
private fun PurchaseHeader(p: PurchaseEntity, supplier: String?) {
    val ink2 = LocalDukaanColors.current.ink2
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(supplier ?: stringResource(R.string.purchase_no_supplier), style = MaterialTheme.typography.titleMedium)
            Text(formatDate(p.date), style = MaterialTheme.typography.bodySmall, color = ink2)
            p.billNo?.let { Text(stringResource(R.string.purchase_bill, it), style = MaterialTheme.typography.bodySmall, color = ink2) }
            if (p.status == DocStatus.VOIDED) Tag(TagKind.VOID)
        }
        MoneyText(Money(p.totalPaisa), withSymbol = true, hideable = true, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun LineRowView(l: PurchaseLineRow) {
    val ink2 = LocalDukaanColors.current.ink2
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(l.productName, style = MaterialTheme.typography.titleSmall)
            val variant = if (l.variantLabel == "Default") "" else "${l.variantLabel} · "
            val qty = qtyLabel(Qty(l.qtyMilli), l.unit, l.trackingType)
            val format = LocalShopFormat.current
            val cost = if (format.hideCost) "••••" else Money(l.unitCostPaisa).display(format, withSymbol = false)
            Text(
                ltr("$variant$qty × $cost") +
                    if (l.trackingType == TrackingType.SERIAL && l.qtyMilli > 1000) " " + stringResource(R.string.purchase_avg) else "",
                style = MaterialTheme.typography.bodySmall, color = ink2,
            )
        }
        MoneyText(Money(l.unitCostPaisa).times(Qty(l.qtyMilli)), hideable = true)
    }
}
