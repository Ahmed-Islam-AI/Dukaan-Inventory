package app.dukaan.android.feature.sell

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.feature.items.Initials
import app.dukaan.android.feature.items.PickerMode
import app.dukaan.android.feature.items.ItemPickerSheet
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.FormCard
import app.dukaan.android.core.ledger.invoiceNumber
import app.dukaan.android.core.ui.SearchField
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.Panel
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.CardRow
import app.dukaan.android.core.ui.Avatar
import app.dukaan.android.core.ui.ModePicker
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.PickItem
import app.dukaan.android.core.db.SerialUnitEntity
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.ledger.SaleIn
import app.dukaan.android.core.ledger.SaleLineIn
import app.dukaan.android.core.ledger.SaleMath
import app.dukaan.android.core.ledger.Totals
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.ui.LocalShopFormat
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.display
import app.dukaan.android.core.ui.RowDivider
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.ltr
import app.dukaan.android.core.ui.qtyLabel
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.theme.MonoFamily
import app.dukaan.android.core.ui.userMessage
import app.dukaan.android.feature.items.NumberField
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One cart line. Price and qty are editable; SERIAL lines are one exact unit. */
class CartLine(
    val pick: PickItem,
    val serialUnitId: String? = null,
    val serial: String? = null,
) {
    var qty by mutableStateOf(Qty.ONE)
    var price by mutableStateOf(Money(pick.salePricePaisa))
    val name get() = pick.name
    /** Selling more than the shelf holds: allowed, but say so (D-14). */
    val overStock get() = pick.trackingType == TrackingType.QUANTITY && qty.milli > pick.onHandMilli
}

@OptIn(ExperimentalCoroutinesApi::class)
class SellViewModel(private val container: AppContainer) : ViewModel() {
    val picks = container.database.pickDao()

    /** The category → item picker (D-85); opens by itself when Sell starts with an empty bill. */
    var showPicker by mutableStateOf(false)
    /** A scan that matched nothing exactly: the picker opens searching for it. */
    var pickerQuery by mutableStateOf("")
        private set

    fun openPicker(query: String = "") {
        pickerQuery = query
        showPicker = true
    }

    /** How many of this item are on the bill (the picker's badge). */
    fun picked(variantId: String): Int = cart.filter { it.pick.variantId == variantId }.sumOf { (it.qty.milli / 1000).toInt().coerceAtLeast(1) }

    val cart = mutableStateListOf<CartLine>()
    var discount by mutableStateOf(Money.ZERO)
    var taxBp by mutableStateOf<Int?>(null)
        private set
    var prefix by mutableStateOf(container.prefs.invoicePrefix)
        private set
    var editingPrefix by mutableStateOf(false)
    /** A prefix another phone of the shop already prints (D-77); shown in the prefix dialog. */
    var prefixTaken by mutableStateOf<String?>(null)
        private set
    /** First letter no other phone uses: the dialog's starting value. */
    var freePrefix by mutableStateOf("A")
        private set
    /** "B-0003": the number this sale will get (MAX + 1 for the prefix, D-18). */
    var nextNumber by mutableStateOf("")
        private set

    private fun refreshNextNumber() {
        val p = prefix ?: return
        viewModelScope.launch { nextNumber = invoiceNumber(p, (container.database.ledgerDao().maxInvoiceSeq(p) ?: 0) + 1) }
    }

    // Pay step
    var paying by mutableStateOf(false)
    var paid by mutableStateOf("")
    var mode by mutableStateOf(PaymentMode.CASH)
    var customer by mutableStateOf("")
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** IMEI picker: units of a SERIAL variant to choose from. */
    var picking by mutableStateOf<Pair<PickItem, List<SerialUnitEntity>>?>(null)
        private set

    private suspend fun otherPhonesPrefixes() = container.database.clashDao().prefixesOfOtherPhones(container.prefs.deviceId).toSet()

    init {
        viewModelScope.launch { taxBp = container.database.shopDao().get()?.taxRateBp }
        viewModelScope.launch { otherPhonesPrefixes().let { taken -> freePrefix = ('A'..'Z').map { it.toString() }.firstOrNull { it !in taken } ?: "A" } }
        refreshNextNumber()
    }

    val totals: Totals get() = SaleMath.totals(cart.map { it.price to it.qty }, discount, taxBp)

    fun choosePrefix(value: String) {
        val p = value.trim().uppercase().take(3).ifEmpty { return }
        viewModelScope.launch {
            // Two phones printing "A-0012" would be two different bills with one number.
            if (p in otherPhonesPrefixes()) {
                prefixTaken = p
                return@launch
            }
            prefixTaken = null
            container.prefs.invoicePrefix = p
            prefix = p
            editingPrefix = false
            refreshNextNumber()
        }
    }

    fun add(pick: PickItem) {
        when {
            pick.trackingType == TrackingType.QUANTITY -> {
                val existing = cart.firstOrNull { it.pick.variantId == pick.variantId }
                if (existing != null) existing.qty += Qty.ONE else cart += CartLine(pick)
            }
            pick.serialUnitId != null -> addUnit(pick, pick.serialUnitId, pick.serial)
            else -> viewModelScope.launch {
                val inCart = cart.mapNotNull { it.serialUnitId }.toSet()
                val units = picks.inStockUnits(pick.variantId).filter { it.id !in inCart }
                picking = pick to units
            }
        }
    }

    /**
     * A scanned barcode (or a unit's serial) goes straight onto the bill; anything else opens the
     * picker searching for it (D-85).
     */
    fun onScanned(code: String) {
        viewModelScope.launch {
            val found = picks.search(code).first()
            val exact = found.firstOrNull { it.serialUnitId != null } ?: found.filter { it.barcode == code }.singleOrNull() ?: found.singleOrNull()
            if (exact != null) add(exact) else openPicker(code)
        }
    }

    fun addUnit(pick: PickItem, unitId: String, serial: String?) {
        picking = null
        if (cart.none { it.serialUnitId == unitId }) cart += CartLine(pick, unitId, serial)
    }

    fun dismissPicker() {
        picking = null
    }

    fun step(line: CartLine, up: Boolean) {
        val next = if (up) line.qty + Qty.ONE else line.qty - Qty.ONE
        if (next.milli <= 0) cart.remove(line) else line.qty = next
    }

    fun startPay() {
        error = null
        paid = totals.total.format(null, false).replace(",", "")
        paying = true
    }

    /** Money given minus total, when the customer hands over more (cash). */
    fun change(): Money? = Money.parse(paid)?.let { it - totals.total }?.takeIf { it.minor > 0 }

    fun save(context: android.content.Context, onSaved: (String) -> Unit) {
        if (saving) return
        val prefixNow = prefix ?: return
        val given = Money.parse(paid.ifBlank { "0" }) ?: run { error = context.getString(R.string.err_paid_amount); return }
        val total = totals.total
        val recorded = minOf(given, total) // change goes back to the customer; only the total is collected
        if (recorded < total && customer.isBlank()) {
            error = context.getString(R.string.err_customer_for_credit)
            return
        }
        saving = true
        error = null
        viewModelScope.launch {
            try {
                val result = container.ledger.sell(
                    SaleIn(
                        invoicePrefix = prefixNow,
                        customerName = customer.ifBlank { null },
                        date = System.currentTimeMillis(),
                        lines = cart.map { SaleLineIn(it.pick.variantId, it.qty, it.price, it.serialUnitId) },
                        discount = discount,
                        paid = recorded,
                        mode = mode,
                    )
                )
                cart.clear()
                discount = Money.ZERO
                customer = ""
                paying = false
                refreshNextNumber()
                onSaved(result.saleId)
            } catch (e: LedgerException) {
                error = e.userMessage(context)
            } finally {
                saving = false
            }
        }
    }
}

@Composable
fun SellScreen(vm: SellViewModel, onScan: () -> Unit, onSaved: (String) -> Unit) {
    if (vm.prefix == null || vm.editingPrefix) {
        PrefixDialog(initial = vm.prefix ?: vm.freePrefix, taken = vm.prefixTaken, onSave = vm::choosePrefix, onDismiss = if (vm.prefix != null) ({ vm.editingPrefix = false }) else null)
        if (vm.prefix == null) return
    }
    // Tapping Sell starts with choosing items (D-85).
    LaunchedEffect(Unit) { if (vm.cart.isEmpty() && !vm.paying) vm.openPicker() }
    if (vm.paying) PayStep(vm, onSaved) else CartStep(vm, onScan)
    if (vm.showPicker && !vm.paying) {
        val format = LocalShopFormat.current
        ItemPickerSheet(
            dao = vm.picks, mode = PickerMode.SELL, title = stringResource(R.string.picker_title_sell),
            picked = vm::picked, onPick = vm::add, onScan = onScan, onDismiss = { vm.showPicker = false },
            initialQuery = vm.pickerQuery,
            footer = {
                if (vm.cart.isNotEmpty()) {
                    Button(onClick = { vm.showPicker = false }, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp)) {
                        ButtonLabel(R.drawable.ic_cart, stringResource(R.string.picker_done, vm.cart.size, vm.totals.total.display(format, withSymbol = true)))
                    }
                }
            },
        )
    }
    vm.picking?.let { (pick, units) -> UnitPicker(pick, units, vm::addUnit, vm::dismissPicker) }
}

@Composable
private fun CartStep(vm: SellViewModel, onScan: () -> Unit) {
    val ink2 = LocalDukaanColors.current.ink2
    var editingPrice by remember { mutableStateOf<CartLine?>(null) }
    var editingQty by remember { mutableStateOf<CartLine?>(null) }
    var editingDiscount by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(stringResource(R.string.sell_title), stringResource(R.string.sell_next_invoice, vm.nextNumber), onSubtitleClick = { vm.editingPrefix = true })
        // Add items by category (the picker) or by scanning a barcode.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.openPicker() }, Modifier.weight(1f).height(52.dp)) { ButtonLabel(R.drawable.ic_add_box, stringResource(R.string.sell_add_items)) }
            OutlinedButton(onClick = onScan, Modifier.height(52.dp)) { ButtonLabel(R.drawable.ic_scan, stringResource(R.string.scan_action)) }
        }
        if (vm.cart.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                IconBadge(R.drawable.ic_cart, Tone.BRAND, size = 88.dp)
                Text(stringResource(R.string.sell_empty), color = ink2, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            }
            return@Column
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(vm.cart, key = { it.serialUnitId ?: it.pick.variantId }) { line -> Box(Modifier.animateItem()) { CartRow(line, vm, { editingPrice = line }, { editingQty = line }) } }
        }
        PayZone(vm.totals, onDiscount = { editingDiscount = true }, onCharge = vm::startPay)
    }

    editingPrice?.let { line ->
        AmountDialog(stringResource(R.string.sell_price_title, line.name), line.price.format(null, false).replace(",", ""), onDismiss = { editingPrice = null }) {
            line.price = it; editingPrice = null
        }
    }
    editingQty?.let { line ->
        QtyDialog(line, onDismiss = { editingQty = null }) { line.qty = it; editingQty = null }
    }
    if (editingDiscount) {
        AmountDialog(stringResource(R.string.sell_discount), if (vm.discount.minor == 0L) "" else vm.discount.format(null, false).replace(",", ""), onDismiss = { editingDiscount = false }) {
            vm.discount = it; editingDiscount = false
        }
    }
}

@Composable
private fun CartRow(line: CartLine, vm: SellViewModel, onPrice: () -> Unit, onQty: () -> Unit) {
    val c = LocalDukaanColors.current
    Panel(Modifier.padding(vertical = 4.dp)) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Initials(line.name, if (line.serial != null) Tone.INFO else Tone.BRAND, size = 36)
            Text(line.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            MoneyText(line.price.times(line.qty), style = MaterialTheme.typography.titleSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (line.serial != null) {
                Text(ltr("${LocalShopFormat.current.serialLabel} ${line.serial}"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), color = c.ink2)
            } else {
                Stepper(line, vm, onQty)
                Box(Modifier.weight(1f))
            }
            Text(
                ltr("× ") + line.price.format(null, false),
                Modifier.clickable(onClick = onPrice).padding(horizontal = 4.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = c.ink2,
            )
            IconButton(onClick = { vm.cart.remove(line) }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.remove), Modifier.size(18.dp))
            }
        }
        if (line.overStock) {
            Row(
                Modifier.fillMaxWidth().background(c.warningBg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = c.warning, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.sell_over_stock, ltr(Qty(line.pick.onHandMilli.coerceAtLeast(0)).format())), style = MaterialTheme.typography.bodySmall, color = c.warning)
            }
        }
    }
    }
}

@Composable
private fun Stepper(line: CartLine, vm: SellViewModel, onQty: () -> Unit) {
    val c = LocalDukaanColors.current
    Row(
        Modifier.background(c.sunk, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.step(line, up = false) }) { Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.qty_less)) }
        Text(
            ltr(qtyLabel(line.qty, line.pick.unit, line.pick.trackingType)),
            Modifier.clickable(onClick = onQty).padding(horizontal = 8.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        IconButton(onClick = { vm.step(line, up = true) }) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.qty_more)) }
    }
}

/** Fixed bottom panel (DESIGN.md PaySheet): subtotal, discount, TOTAL, Charge repeats the amount. */
@Composable
private fun PayZone(t: Totals, onDiscount: () -> Unit, onCharge: () -> Unit) {
    val c = LocalDukaanColors.current
    Column(Modifier.fillMaxWidth().background(c.raised).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(color = c.line)
        TotalRow(stringResource(R.string.sell_subtotal), t.subtotal)
        Row(Modifier.fillMaxWidth().clickable(onClick = onDiscount), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sell_discount), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.ink2)
            if (t.discount.minor > 0) MoneyText(-t.discount, style = MaterialTheme.typography.bodySmall, color = c.ink2)
            else Text(stringResource(R.string.sell_add_discount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (t.tax.minor > 0) TotalRow(stringResource(R.string.sell_tax), t.tax)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.total).uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.ink2)
            MoneyText(t.total, withSymbol = true, style = MaterialTheme.typography.headlineMedium)
        }
        Button(onClick = onCharge, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.sell_charge, t.total.display(LocalShopFormat.current, withSymbol = true)))
        }
    }
}

@Composable
private fun TotalRow(label: String, amount: Money) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = LocalDukaanColors.current.ink2)
        MoneyText(amount, style = MaterialTheme.typography.bodySmall, color = LocalDukaanColors.current.ink2)
    }
}

/** DESIGN.md § Pay sheet: TOTAL huge, paid prefilled, mode, customer when on credit. */
@Composable
private fun PayStep(vm: SellViewModel, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val c = LocalDukaanColors.current
    val total = vm.totals.total
    val given = Money.parse(vm.paid.ifBlank { "0" })
    val balance = given?.let { total - it }?.takeIf { it.minor > 0 }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(stringResource(R.string.pay_title), navigation = { IconButton(onClick = { vm.paying = false }) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Panel {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.total).uppercase(), style = MaterialTheme.typography.labelMedium, color = c.ink2)
                    MoneyText(total, withSymbol = true, style = MaterialTheme.typography.displaySmall)
                }
            }
            FormCard(stringResource(R.string.form_payment)) {
                NumberField(vm.paid, { vm.paid = it }, stringResource(R.string.pay_paid), Modifier.fillMaxWidth())
                ModePicker(vm.mode) { vm.mode = it }
                vm.change()?.let { change ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.pay_change), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        MoneyText(change, withSymbol = true, style = MaterialTheme.typography.titleMedium, color = c.profit)
                    }
                }
            }
            FormCard(stringResource(R.string.form_customer)) {
                OutlinedTextField(
                    vm.customer, { vm.customer = it },
                    label = { Text(stringResource(if (balance != null) R.string.pay_customer_required else R.string.pay_customer)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_person), contentDescription = null) },
                    isError = balance != null && vm.customer.isBlank() && vm.error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (balance != null) {
                    Text(
                        stringResource(R.string.pay_balance_note, balance.display(LocalShopFormat.current, withSymbol = true), vm.customer.ifBlank { stringResource(R.string.pay_the_customer) }),
                        style = MaterialTheme.typography.bodyMedium, color = c.warning,
                    )
                }
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button(
            onClick = { vm.save(context, onSaved) },
            enabled = !vm.saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) {
            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null)
            Text(stringResource(if (vm.saving) R.string.pay_saving else R.string.pay_save), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun UnitPicker(pick: PickItem, units: List<SerialUnitEntity>, onPick: (PickItem, String, String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sell_pick_unit)) },
        text = {
            Column {
                Text(pick.productName, style = MaterialTheme.typography.titleSmall)
                if (units.isEmpty()) Text(stringResource(R.string.item_no_units), color = LocalDukaanColors.current.ink2)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(units, key = { it.id }) { u ->
                        Text(
                            ltr(u.serial),
                            Modifier.fillMaxWidth().clickable { onPick(pick, u.id, u.serial) }.padding(vertical = 14.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                        )
                        RowDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AmountDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (Money) -> Unit) {
    var bad by remember { mutableStateOf(false) }
    NumberDialog(title, initial, if (bad) stringResource(R.string.err_price) else null, onDismiss) { text ->
        Money.parse(text.ifBlank { "0" })?.let(onSave) ?: run { bad = true }
    }
}

@Composable
private fun QtyDialog(line: CartLine, onDismiss: () -> Unit, onSave: (Qty) -> Unit) {
    var bad by remember { mutableStateOf(false) }
    NumberDialog(stringResource(R.string.adjust_qty, line.pick.unit), line.qty.format(), if (bad) stringResource(R.string.err_qty) else null, onDismiss) { text ->
        Qty.parse(text, line.pick.allowDecimalQty)?.let(onSave) ?: run { bad = true }
    }
}

/**
 * One number to change at the counter: opens focused with the old value selected, so typing
 * replaces it (no "20000" from typing 2000 after a leftover 0).
 */
@Composable
private fun NumberDialog(title: String, initial: String, error: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it }, singleLine = true, isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(value.text) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value.text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** First sale on this phone (or after a restore): choose the receipt prefix (D-18). */
@Composable
private fun PrefixDialog(initial: String, taken: String?, onSave: (String) -> Unit, onDismiss: (() -> Unit)?) {
    var value by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text(stringResource(R.string.prefix_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.prefix_body))
                OutlinedTextField(
                    value, { if (it.text.length <= 3) value = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    supportingText = { Text(stringResource(R.string.shop_prefix_hint, "${value.text.trim().uppercase().ifEmpty { "A" }}-0001")) },
                    modifier = Modifier.focusRequester(focus),
                )
                taken?.let { Text(stringResource(R.string.prefix_taken, it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = onDismiss?.let { { TextButton(onClick = it) { Text(stringResource(R.string.cancel)) } } },
    )
}
