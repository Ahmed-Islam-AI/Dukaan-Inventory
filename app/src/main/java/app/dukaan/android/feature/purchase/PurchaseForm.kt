package app.dukaan.android.feature.purchase

import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.res.pluralStringResource
import app.dukaan.android.core.ledger.NewVariant
import app.dukaan.android.core.ledger.NewItem
import app.dukaan.android.feature.items.PickerMode
import app.dukaan.android.feature.items.ItemPickerSheet
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.Avatar
import app.dukaan.android.core.ui.FormCard
import androidx.compose.material3.OutlinedButton
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.ui.ModePicker
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.db.PickItem
import app.dukaan.android.core.db.CategoryCount
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.ledger.PurchaseIn
import app.dukaan.android.core.ledger.PurchaseLineIn
import app.dukaan.android.core.ledger.SerialIn
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.ltr
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.theme.MonoFamily
import app.dukaan.android.core.ui.userMessage
import app.dukaan.android.feature.items.NumberField
import app.dukaan.android.feature.items.SerialDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One line on the purchase form. QUANTITY uses qty/cost; SERIAL uses the IMEI rows. */
class LineDraft(val pick: PickItem) {
    /** The item's usual purchase price, editable for this batch (D-85). */
    private val lastCost = pick.purchasePricePaisa?.let { Money(it).format(null, false).replace(",", "") }.orEmpty()
    var qty by mutableStateOf("")
    var cost by mutableStateOf(lastCost)
    val serials = mutableStateListOf(SerialDraft(cost = lastCost))
}

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseFormViewModel(private val container: AppContainer, productId: String? = null) : ViewModel() {
    var supplier by mutableStateOf("")
    var billNo by mutableStateOf("")
    val lines = mutableStateListOf<LineDraft>()
    var paid by mutableStateOf("")
    var paidEdited by mutableStateOf(false)
    var mode by mutableStateOf(PaymentMode.CASH)
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
        private set

    val picks = container.database.pickDao()
    /** Category → item picker (D-85), with "+ New category" / "+ New item". */
    var showPicker by mutableStateOf(productId == null)
    var pickerQuery by mutableStateOf("")
        private set
    /** "+ New item" open for this category. */
    var newItemIn by mutableStateOf<CategoryCount?>(null)
    val suppliers = container.database.catalogDao().supplierNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Opened from an item's page: start with that item's variants already listed.
        if (productId != null) viewModelScope.launch {
            picks.forProduct(productId).forEach(::add)
        }
    }

    /** Picking an item already on the form adds one more of it instead of a second line. */
    fun add(pick: PickItem) {
        val existing = lines.firstOrNull { it.pick.variantId == pick.variantId }
        if (existing == null) lines += LineDraft(pick).apply { if (pick.trackingType == TrackingType.QUANTITY) qty = "1" }
        else if (pick.trackingType == TrackingType.QUANTITY) existing.qty = ((existing.qty.toLongOrNull() ?: 0L) + 1).toString()
    }

    fun picked(variantId: String) = lines.firstOrNull { it.pick.variantId == variantId }?.let { l ->
        if (l.pick.trackingType == TrackingType.QUANTITY) l.qty.toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: 1 else l.serials.count { it.serial.isNotBlank() }.coerceAtLeast(1)
    } ?: 0

    suspend fun newCategory(name: String): String = container.ledger.addCategory(name)

    /** "+ New item": both prices and a barcode; it goes onto the form at once (D-85). */
    fun createItem(context: android.content.Context, category: CategoryCount, name: String, purchase: String, sale: String, barcode: String, onDone: (String?) -> Unit) {
        val buy = Money.parse(purchase) ?: return onDone(context.getString(R.string.err_cost))
        val sell = Money.parse(sale) ?: return onDone(context.getString(R.string.err_price))
        if (name.isBlank()) return onDone(context.getString(R.string.err_name_required))
        viewModelScope.launch {
            try {
                val id = container.ledger.createItem(
                    NewItem(
                        name.trim(), category.name, TrackingType.QUANTITY, "pcs", false,
                        listOf(NewVariant("Default", sell, barcode = barcode.trim().ifEmpty { null }, purchasePrice = buy)),
                    )
                )
                picks.forProduct(id).forEach(::add)
                onDone(null)
            } catch (e: LedgerException) {
                onDone(e.userMessage(context))
            }
        }
    }

    /** Barcode scanned from the picker: that item goes onto the form; else the picker searches it. */
    private fun onPickScanned(code: String) {
        viewModelScope.launch {
            val found = picks.search(code).first()
            val exact = found.filter { it.barcode == code }.singleOrNull() ?: found.singleOrNull()
            if (exact != null) add(exact) else { pickerQuery = code; showPicker = true }
        }
    }

    /** Scanned IMEIs for line [target] = "line:<index>": fill blank rows first, then add rows. */
    fun onScanned(target: String, codes: List<String>) {
        if (target == "pick") return codes.firstOrNull()?.let(::onPickScanned) ?: Unit
        val line = target.removePrefix("line:").toIntOrNull()?.let { lines.getOrNull(it) } ?: return
        val existing = line.serials.map { it.serial.trim() }.toSet()
        val cost = line.serials.lastOrNull()?.cost.orEmpty()
        codes.filter { it !in existing }.forEach { code ->
            val blank = line.serials.firstOrNull { it.serial.isBlank() }
            if (blank != null) blank.serial = code else line.serials += SerialDraft(code, cost)
        }
    }

    /** Running total; null while a field is not a valid number yet. */
    val total: Money?
        get() = lines.fold<LineDraft, Money?>(Money.ZERO) { acc, l ->
            acc ?: return@fold null
            if (l.pick.trackingType == TrackingType.SERIAL) {
                l.serials.filter { it.serial.isNotBlank() }.fold<SerialDraft, Money?>(acc) { a, s -> a?.let { m -> Money.parse(s.cost)?.let { m + it } } }
            } else {
                val q = Qty.parse(l.qty, l.pick.allowDecimalQty) ?: return@fold null
                val c = Money.parse(l.cost) ?: return@fold null
                acc + c.times(q)
            }
        }

    fun save(context: android.content.Context, onSaved: () -> Unit) {
        if (saving) return
        error = null
        val built = lines.map { l ->
            if (l.pick.trackingType == TrackingType.SERIAL) {
                val rows = l.serials.filter { it.serial.isNotBlank() }
                if (rows.isEmpty()) return fail(context.getString(R.string.err_serials_for, l.pick.productName))
                PurchaseLineIn(l.pick.variantId, serials = rows.map {
                    SerialIn(it.serial.trim(), Money.parse(it.cost) ?: return fail(context.getString(R.string.err_cost)))
                })
            } else {
                val q = Qty.parse(l.qty, l.pick.allowDecimalQty) ?: return fail(context.getString(R.string.err_qty_for, l.pick.productName))
                val c = Money.parse(l.cost) ?: return fail(context.getString(R.string.err_cost))
                PurchaseLineIn(l.pick.variantId, q, c)
            }
        }
        val totalNow = total ?: return fail(context.getString(R.string.err_invalid))
        val paidMoney = if (!paidEdited) totalNow else Money.parse(paid.ifBlank { "0" }) ?: return fail(context.getString(R.string.err_paid))
        saving = true
        viewModelScope.launch {
            try {
                container.ledger.purchase(PurchaseIn(supplier, billNo, System.currentTimeMillis(), built, paidMoney, mode))
                onSaved()
            } catch (e: LedgerException) {
                error = e.userMessage(context)
            } finally {
                saving = false
            }
        }
    }

    private fun fail(message: String) {
        error = message
    }
}

@Composable
fun PurchaseFormScreen(vm: PurchaseFormViewModel, onScan: (target: String, continuous: Boolean) -> Unit, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val ink2 = LocalDukaanColors.current.ink2
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(stringResource(R.string.purchase_new_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) } })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormCard(stringResource(R.string.form_supplier)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        vm.supplier, { vm.supplier = it }, label = { Text(stringResource(R.string.purchase_supplier)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.weight(1.4f),
                    )
                    OutlinedTextField(
                        vm.billNo, { vm.billNo = it }, label = { Text(stringResource(R.string.purchase_bill_no)) },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                // Known suppliers as one-tap chips (no dropdown widget needed).
                val matches = suppliers.filter { it.contains(vm.supplier.trim(), ignoreCase = true) && !it.equals(vm.supplier.trim(), true) }.take(4)
                if (matches.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        matches.forEach { s -> FilterChip(false, { vm.supplier = s }, { Text(s) }) }
                    }
                }
            }

            if (vm.lines.isEmpty()) {
                Text(stringResource(R.string.purchase_empty), style = MaterialTheme.typography.bodyMedium, color = ink2)
            }
            vm.lines.forEachIndexed { i, line ->
                FormCard(null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (line.pick.trackingType == TrackingType.SERIAL) IconBadge(R.drawable.ic_phone_android, Tone.INFO, size = 36.dp) else Avatar(line.pick.productName, size = 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(line.pick.productName, style = MaterialTheme.typography.titleSmall)
                            if (line.pick.variantLabel != "Default") Text(line.pick.variantLabel, style = MaterialTheme.typography.bodySmall, color = ink2)
                        }
                        IconButton(onClick = { vm.lines.removeAt(i) }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.remove)) }
                    }
                    if (line.pick.trackingType == TrackingType.SERIAL) {
                        line.serials.forEachIndexed { j, s ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    s.serial, { s.serial = it }, label = { Text(stringResource(R.string.item_imei)) }, singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1.4f),
                                )
                                NumberField(s.cost, { s.cost = it }, stringResource(R.string.item_cost), Modifier.weight(1f))
                                if (line.serials.size > 1) {
                                    IconButton(onClick = { line.serials.removeAt(j) }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.remove)) }
                                }
                            }
                        }
                        Row {
                            TextButton(onClick = { onScan("line:$i", true) }) { ButtonLabel(R.drawable.ic_scan, stringResource(R.string.scan_imeis)) }
                            TextButton(onClick = { line.serials += SerialDraft(cost = line.serials.lastOrNull()?.cost.orEmpty()) }) {
                                ButtonLabel(R.drawable.ic_add, stringResource(R.string.item_add_imei))
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField(line.qty, { line.qty = it }, stringResource(R.string.purchase_qty, line.pick.unit), Modifier.weight(1f))
                            NumberField(line.cost, { line.cost = it }, stringResource(R.string.item_purchase_price), Modifier.weight(1f))
                        }
                    }
                }
            }
            OutlinedButton(onClick = { vm.showPicker = true }, Modifier.fillMaxWidth().height(56.dp)) { ButtonLabel(R.drawable.ic_add_box, stringResource(R.string.purchase_add_item)) }

            if (vm.lines.isNotEmpty()) FormCard(stringResource(R.string.form_payment)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.total).uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = ink2)
                    vm.total?.let { MoneyText(it, withSymbol = true, style = MaterialTheme.typography.headlineMedium) }
                }
                NumberField(
                    if (vm.paidEdited) vm.paid else vm.total?.format(null, false)?.replace(",", "").orEmpty(),
                    { vm.paid = it; vm.paidEdited = true },
                    stringResource(R.string.purchase_paid),
                    Modifier.fillMaxWidth(),
                )
                ModePicker(vm.mode) { vm.mode = it }
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button(
            onClick = { vm.save(context, onSaved) },
            enabled = vm.lines.isNotEmpty() && !vm.saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) { ButtonLabel(R.drawable.ic_check_circle, stringResource(R.string.purchase_save)) }
    }

    if (vm.showPicker) {
        ItemPickerSheet(
            dao = vm.picks, mode = PickerMode.STOCK, title = stringResource(R.string.picker_title_stock),
            picked = vm::picked, onPick = vm::add, onScan = { onScan("pick", false) }, onDismiss = { vm.showPicker = false },
            onNewCategory = vm::newCategory, onNewItem = { vm.newItemIn = it }, initialQuery = vm.pickerQuery,
            footer = {
                if (vm.lines.isNotEmpty()) {
                    Button(onClick = { vm.showPicker = false }, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp)) {
                        ButtonLabel(R.drawable.ic_check_circle, pluralStringResource(R.plurals.picker_done_stock, vm.lines.size, vm.lines.size))
                    }
                }
            },
        )
    }
    vm.newItemIn?.let { cat -> QuickItemDialog(vm, cat) { vm.newItemIn = null } }
}

/** "+ New item" from Add stock: the essentials only; more options later in Items (D-85). */
@Composable
private fun QuickItemDialog(vm: PurchaseFormViewModel, category: CategoryCount, onClose: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var purchase by remember { mutableStateOf("") }
    var sale by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.picker_new_item_in, category.name.orEmpty())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.item_name)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(purchase, { purchase = it }, stringResource(R.string.item_purchase_price), Modifier.weight(1f))
                    NumberField(sale, { sale = it }, stringResource(R.string.item_sale_price), Modifier.weight(1f))
                }
                OutlinedTextField(barcode, { barcode = it }, label = { Text(stringResource(R.string.item_barcode_optional)) }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.createItem(context, category, name, purchase, sale, barcode) { msg -> if (msg == null) onClose() else error = msg } }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}
