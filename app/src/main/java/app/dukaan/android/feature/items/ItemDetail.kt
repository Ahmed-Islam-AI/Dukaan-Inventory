package app.dukaan.android.feature.items

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.alsoNotify
import app.dukaan.android.core.ui.LocalNotify
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.CardRow
import app.dukaan.android.core.ui.ActionTile
import app.dukaan.android.core.ui.ActionBar
import app.dukaan.android.core.db.MovementRow
import app.dukaan.android.core.db.MovementType
import app.dukaan.android.core.db.ProductEntity
import app.dukaan.android.core.db.SerialStatus
import app.dukaan.android.core.db.SerialUnitEntity
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.db.VariantEntity
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.SectionLabel
import app.dukaan.android.core.ui.StockBadge
import app.dukaan.android.core.ui.formatDateTime
import app.dukaan.android.core.ui.ltr
import app.dukaan.android.core.ui.qtyLabel
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.theme.MonoFamily
import app.dukaan.android.core.ui.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ItemDetailState(
    val loading: Boolean = true,
    val product: ProductEntity? = null,
    val variants: List<VariantEntity> = emptyList(),
    val serials: List<SerialUnitEntity> = emptyList(),
    val history: List<MovementRow> = emptyList(),
)

class ItemDetailViewModel(private val container: AppContainer, productId: String) : ViewModel() {
    private val catalog = container.database.catalogDao()

    val state = combine(
        catalog.product(productId), catalog.variants(productId), catalog.inStockSerials(productId), catalog.movements(productId),
    ) { p, v, s, h -> ItemDetailState(false, p, v, s, h) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemDetailState())

    /** Runs a ledger call; returns the user message on failure, null on success. */
    private fun launchLedger(context: android.content.Context, onResult: (String?) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                onResult(null)
            } catch (e: LedgerException) {
                onResult(e.userMessage(context))
            }
        }
    }

    fun adjust(context: android.content.Context, variantId: String, delta: Qty, cost: Money?, reason: String, onResult: (String?) -> Unit) =
        launchLedger(context, onResult) { container.ledger.adjustQuantity(variantId, delta, cost, reason) }

    fun removeSerial(context: android.content.Context, unitId: String, status: SerialStatus, reason: String, onResult: (String?) -> Unit) =
        launchLedger(context, onResult) { container.ledger.removeSerial(unitId, status, reason) }
}

@Composable
fun ItemDetailScreen(vm: ItemDetailViewModel, hideCost: Boolean, onBack: () -> Unit, onEdit: () -> Unit, onAddStock: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val product = state.product
    var adjusting by remember { mutableStateOf<VariantEntity?>(null) }
    var removing by remember { mutableStateOf<SerialUnitEntity?>(null) }
    val ink2 = LocalDukaanColors.current.ink2

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(product?.name.orEmpty(), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
        if (state.loading) return@Column
        if (product == null) {
            Text(stringResource(R.string.err_not_found), Modifier.padding(16.dp))
            return@Column
        }
        ActionBar(Modifier.padding(horizontal = 16.dp)) {
            ActionTile(R.drawable.ic_truck, Tone.INFO, stringResource(R.string.item_add_stock), Modifier.weight(1f), onClick = onAddStock)
            // One-variant quantity items: correct stock straight from here (several variants: tap the variant).
            if (product.trackingType == TrackingType.QUANTITY && state.variants.size == 1) {
                ActionTile(R.drawable.ic_verified, Tone.WARNING, stringResource(R.string.action_correct), Modifier.weight(1f)) { adjusting = state.variants.single() }
            }
            ActionTile(R.drawable.ic_edit, Tone.NEUTRAL, stringResource(R.string.item_edit), Modifier.weight(1f), onClick = onEdit)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item { SectionLabel(stringResource(R.string.item_section_stock)) }
            items(state.variants, key = { it.id }) { v ->
                CardRow(if (product.trackingType == TrackingType.QUANTITY) ({ adjusting = v }) else null, Modifier.padding(horizontal = 16.dp)) {
                    Column(Modifier.weight(1f)) {
                        // A single variant is just "the item": no "Default" label, but say what the number is.
                        Text(
                            if (state.variants.size > 1) v.label else stringResource(R.string.item_sale_price),
                            style = if (state.variants.size > 1) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
                            color = if (state.variants.size > 1) MaterialTheme.colorScheme.onSurface else ink2,
                        )
                        MoneyText(
                            Money(v.salePricePaisa), withSymbol = true,
                            style = if (state.variants.size > 1) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
                            color = if (state.variants.size > 1) ink2 else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val low = v.lowStockMilli?.let { v.onHandMilli <= it } ?: false
                    StockBadge(Qty(v.onHandMilli), product.unit, product.trackingType, low)
                }
                // Buy vs sell (D-85); hidden with cost (D-45).
                val buy = v.purchasePricePaisa
                if (!hideCost && buy != null) {
                    Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(stringResource(R.string.item_purchase_price), style = MaterialTheme.typography.labelSmall, color = ink2)
                            MoneyText(Money(buy), withSymbol = true, style = MaterialTheme.typography.titleSmall)
                        }
                        ProfitLine(Money(buy), Money(v.salePricePaisa), Modifier.weight(1f))
                    }
                }
            }
            if (product.trackingType == TrackingType.SERIAL) {
                item { SectionLabel(stringResource(R.string.item_section_units, state.serials.size)) }
                if (state.serials.isEmpty()) {
                    item { Text(stringResource(R.string.item_no_units), Modifier.padding(horizontal = 16.dp), color = ink2) }
                }
                items(state.serials, key = { it.id }) { s ->
                    CardRow({ removing = s }, Modifier.padding(horizontal = 16.dp)) {
                        IconBadge(R.drawable.ic_phone_android, Tone.INFO, size = 36.dp)
                        Text(ltr(s.serial), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily))
                        MoneyText(Money(s.costPaisa), hideable = true, color = ink2)
                    }
                }
            }
            item { SectionLabel(stringResource(R.string.item_section_history)) }
            if (state.history.isEmpty()) {
                item { Text(stringResource(R.string.item_no_history), Modifier.padding(horizontal = 16.dp), color = ink2) }
            }
            items(state.history, key = { it.id }) { m ->
                val q = Qty(m.qtyDeltaMilli)
                CardRow(null, Modifier.padding(horizontal = 16.dp)) {
                    // Stock in = green plus, stock out = red minus.
                    IconBadge(if (q.milli > 0) R.drawable.ic_add else R.drawable.ic_remove, if (q.milli > 0) Tone.PROFIT else Tone.LOSS, size = 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(movementLabel(m.type)), style = MaterialTheme.typography.titleSmall)
                        val variant = if (state.variants.size > 1) "${m.variantLabel} · " else ""
                        Text(variant + formatDateTime(m.createdAt), style = MaterialTheme.typography.bodySmall, color = ink2)
                    }
                    Text(
                        ltr((if (q.milli > 0) "+" else "") + qtyLabel(q, product.unit, product.trackingType)),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (q.milli < 0) LocalDukaanColors.current.loss else LocalDukaanColors.current.profit,
                    )
                }
            }
        }
    }

    adjusting?.let { v -> AdjustDialog(vm, product!!, v) { adjusting = null } }
    removing?.let { s -> RemoveUnitDialog(vm, s) { removing = null } }
}

private fun movementLabel(type: MovementType) = when (type) {
    MovementType.OPENING -> R.string.mv_opening
    MovementType.PURCHASE -> R.string.mv_purchase
    MovementType.PURCHASE_VOID -> R.string.mv_purchase_void
    MovementType.SALE -> R.string.mv_sale
    MovementType.VOID -> R.string.mv_void
    MovementType.RETURN -> R.string.mv_return
    MovementType.RETURN_VOID -> R.string.mv_return_void
    MovementType.ADJUST -> R.string.mv_adjust
}

@Composable
private fun AdjustDialog(vm: ItemDetailViewModel, product: ProductEntity, variant: VariantEntity, onDismiss: () -> Unit) {
    val notify = LocalNotify.current
    val context = LocalContext.current
    var adding by remember { mutableStateOf(true) }
    var qty by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val qtyError = stringResource(R.string.err_qty)
    val corrected = stringResource(R.string.msg_stock_corrected)
    val costError = stringResource(R.string.err_cost)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adjust_title, variant.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = adding, onClick = { adding = true }, label = { Text(stringResource(R.string.adjust_add)) })
                    FilterChip(selected = !adding, onClick = { adding = false }, label = { Text(stringResource(R.string.adjust_remove)) })
                }
                NumberField(qty, { qty = it }, stringResource(R.string.adjust_qty, product.unit))
                if (adding) NumberField(cost, { cost = it }, stringResource(R.string.adjust_cost_hint))
                OutlinedTextField(reason, { reason = it }, label = { Text(stringResource(R.string.adjust_reason)) }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val q = Qty.parse(qty, product.allowDecimalQty)
                val c = if (cost.isBlank()) null else Money.parse(cost)
                when {
                    q == null -> error = qtyError
                    adding && cost.isNotBlank() && c == null -> error = costError
                    else -> {
                        busy = true
                        vm.adjust(context, variant.id, if (adding) q else -q, if (adding) c else null, reason) { msg ->
                            busy = false
                            if (msg == null) notify(corrected)
                            if (msg == null) onDismiss() else error = msg
                        }
                    }
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RemoveUnitDialog(vm: ItemDetailViewModel, unit: SerialUnitEntity, onDismiss: () -> Unit) {
    val notify = LocalNotify.current
    val context = LocalContext.current
    var status by remember { mutableStateOf(SerialStatus.DAMAGED) }
    val done = stringResource(R.string.msg_stock_corrected)
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_unit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ltr(unit.serial), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(status == SerialStatus.DAMAGED, { status = SerialStatus.DAMAGED }, { Text(stringResource(R.string.remove_unit_damaged)) })
                    FilterChip(status == SerialStatus.RETURNED_TO_SUPPLIER, { status = SerialStatus.RETURNED_TO_SUPPLIER }, { Text(stringResource(R.string.remove_unit_returned)) })
                }
                OutlinedTextField(reason, { reason = it }, label = { Text(stringResource(R.string.adjust_reason)) }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.removeSerial(context, unit.id, status, reason, { msg: String? -> if (msg == null) onDismiss() else error = msg }.alsoNotify(notify, done))
            }) { Text(stringResource(R.string.remove_unit_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
