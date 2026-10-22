package app.dukaan.android.feature.items

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import app.dukaan.android.core.ui.ltr
import app.dukaan.android.core.ui.display
import app.dukaan.android.core.ui.LocalShopFormat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.FormCard
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.ledger.LedgerException
import app.dukaan.android.core.ledger.NewItem
import app.dukaan.android.core.ledger.NewVariant
import app.dukaan.android.core.ledger.OpeningStock
import app.dukaan.android.core.ledger.SerialIn
import app.dukaan.android.core.ledger.VariantEdit
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.userMessage
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.theme.MonoFamily
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One IMEI row in the opening-stock list. */
class SerialDraft(serial: String = "", cost: String = "") {
    var serial by mutableStateOf(serial)
    var cost by mutableStateOf(cost)
}

/** One variant (size/colour) being added or edited. Fields are raw text until Save. */
class VariantDraft(val id: String? = null, label: String = "", price: String = "") {
    var label by mutableStateOf(label)
    var price by mutableStateOf(price)
    /** What the shop pays for 1 unit (D-85); also the opening stock's cost. */
    var purchase by mutableStateOf("")
    var barcode by mutableStateOf("")
    var lowStock by mutableStateOf("")
    var openingQty by mutableStateOf("")
    var openingCost by mutableStateOf("")
    val serials = mutableStateListOf(SerialDraft())
}

/**
 * Add item (productId == null) or edit item. Editing changes names/prices/codes only; stock is
 * changed by purchases and adjustments (CLAUDE.md rule 3).
 */
class ItemFormViewModel(private val container: AppContainer, private val productId: String?) : ViewModel() {
    val isEdit = productId != null
    var name by mutableStateOf("")
    var category by mutableStateOf("")
    var unit by mutableStateOf("pcs")
    var serialTracked by mutableStateOf(false)
    var allowDecimal by mutableStateOf(false)
    var moreOptions by mutableStateOf(false)
    val variants = mutableStateListOf(VariantDraft(label = "Default"))
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(!isEdit)
        private set
    /** Categories to choose from (D-85); "Other" (items without one) is not offered. */
    val categories = container.database.pickDao().categories()
    var choosingCategory by mutableStateOf(false)

    fun newCategory(name: String) {
        viewModelScope.launch {
            container.ledger.addCategory(name)
            category = name.trim()
        }
    }

    init {
        viewModelScope.launch {
            val shop = container.database.shopDao().get()
            if (!isEdit) return@launch
            val catalog = container.database.catalogDao()
            val product = catalog.product(productId!!).first() ?: return@launch
            name = product.name
            category = product.categoryId?.let { catalog.category(it)?.name }.orEmpty()
            unit = product.unit
            serialTracked = product.trackingType == TrackingType.SERIAL
            allowDecimal = product.allowDecimalQty
            variants.clear()
            catalog.variants(productId).first().forEach { v ->
                variants += VariantDraft(v.id, v.label, Money(v.salePricePaisa).format(null, false).replace(",", "")).apply {
                    barcode = v.barcode.orEmpty()
                    lowStock = v.lowStockMilli?.let { Qty(it).format() }.orEmpty()
                    purchase = v.purchasePricePaisa?.let { Money(it).format(null, false).replace(",", "") }.orEmpty()
                }
            }
            moreOptions = variants.size > 1 || variants.any { it.lowStock.isNotEmpty() }
            loaded = true
        }
    }

    /** "imei:<variant>" = opening IMEIs (bulk), "barcode:<variant>" = that variant's barcode. */
    fun onScanned(target: String, codes: List<String>) {
        val (kind, index) = target.split(":").let { it.getOrNull(0) to it.getOrNull(1)?.toIntOrNull() }
        val d = index?.let { variants.getOrNull(it) } ?: return
        when (kind) {
            "barcode" -> d.barcode = codes.first()
            "imei" -> {
                val existing = d.serials.map { it.serial.trim() }.toSet()
                val cost = d.serials.lastOrNull()?.cost.orEmpty()
                codes.filter { it !in existing }.forEach { code ->
                    val blank = d.serials.firstOrNull { it.serial.isBlank() }
                    if (blank != null) blank.serial = code else d.serials += SerialDraft(code, cost)
                }
            }
        }
    }

    fun addVariant() {
        if (variants.size == 1 && variants[0].label == "Default") variants[0].label = ""
        variants += VariantDraft()
    }

    fun save(context: android.content.Context, onSaved: (String) -> Unit) {
        if (saving) return
        error = null
        val tracking = if (serialTracked) TrackingType.SERIAL else TrackingType.QUANTITY
        val decimal = allowDecimal && !serialTracked
        // Parse every field first; the first bad one becomes the error message.
        fun bad(msg: Int): Nothing = throw FormError(context.getString(msg))
        try {
            if (name.isBlank()) bad(R.string.err_name_required)
            if (category.isBlank()) bad(R.string.err_category_required)
            val parsed = variants.map { d ->
                val price = Money.parse(d.price) ?: bad(R.string.err_price)
                val buy = d.purchase.takeIf { it.isNotBlank() }?.let { Money.parse(it) ?: bad(R.string.err_cost) }
                val low = d.lowStock.takeIf { it.isNotBlank() }?.let { Qty.parse(it, decimal) ?: bad(R.string.err_qty) }
                val opening: OpeningStock? = when {
                    isEdit -> null
                    // Opening stock costs the purchase price (D-85).
                    serialTracked -> d.serials.filter { it.serial.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { rows ->
                        OpeningStock.Serials(rows.map { SerialIn(it.serial.trim(), buy ?: Money.ZERO) })
                    }
                    d.openingQty.isNotBlank() && d.openingQty.trim() != "0" -> OpeningStock.Quantity(
                        Qty.parse(d.openingQty, decimal) ?: bad(R.string.err_qty),
                        buy ?: Money.ZERO,
                    )
                    else -> null
                }
                Parsed(d, price, low, buy) to opening
            }
            saving = true
            viewModelScope.launch {
                try {
                    val id = if (isEdit) {
                        container.ledger.updateItem(
                            productId!!, name, category, unit,
                            parsed.map { (t, _) -> VariantEdit(t.draft.id!!, t.draft.label, t.price, t.draft.barcode, t.low, t.buy) },
                        )
                        productId
                    } else {
                        container.ledger.createItem(
                            NewItem(
                                name = name, category = category, trackingType = tracking, unit = unit,
                                allowDecimalQty = decimal,
                                variants = parsed.map { (t, opening) ->
                                    NewVariant(t.draft.label, t.price, barcode = t.draft.barcode, lowStock = t.low, opening = opening, purchasePrice = t.buy)
                                },
                            )
                        )
                    }
                    onSaved(id)
                } catch (e: LedgerException) {
                    error = e.userMessage(context)
                } finally {
                    saving = false
                }
            }
        } catch (e: FormError) {
            error = e.message
        }
    }

    private class FormError(message: String) : Exception(message)
    private class Parsed(val draft: VariantDraft, val price: Money, val low: Qty?, val buy: Money?)
}

@Composable
fun ItemFormScreen(vm: ItemFormViewModel, onScan: (target: String, continuous: Boolean) -> Unit, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val ink2 = LocalDukaanColors.current.ink2
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(stringResource(if (vm.isEdit) R.string.item_edit_title else R.string.item_add_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) } })
        if (!vm.loaded) return@Column
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormCard(stringResource(R.string.form_item)) {
                OutlinedTextField(
                    value = vm.name, onValueChange = { vm.name = it },
                    label = { Text(stringResource(R.string.item_name)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Category: chosen from the shop's list (required, D-85); selling starts from it.
                // Read-only field that opens the chooser on tap; keeps its label for TalkBack.
                val categoryTaps = remember { MutableInteractionSource() }
                LaunchedEffect(categoryTaps) {
                    categoryTaps.interactions.collect { if (it is PressInteraction.Release) vm.choosingCategory = true }
                }
                OutlinedTextField(
                    value = vm.category, onValueChange = {}, readOnly = true, interactionSource = categoryTaps,
                    label = { Text(stringResource(R.string.item_category_required)) }, singleLine = true,
                    trailingIcon = { Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, modifier = Modifier.rotate(90f)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            vm.variants.forEachIndexed { index, d ->
                FormCard(if (vm.variants.size > 1) stringResource(R.string.item_variant_label, index + 1) else stringResource(R.string.form_price_stock)) {
                    if (vm.variants.size > 1) {
                        OutlinedTextField(
                            value = d.label, onValueChange = { d.label = it },
                            label = { Text(stringResource(R.string.item_variant_label, index + 1)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // The two prices side by side, and what one sale earns (D-85).
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(d.purchase, { d.purchase = it }, stringResource(R.string.item_purchase_price), Modifier.weight(1f))
                        NumberField(d.price, { d.price = it }, stringResource(R.string.item_sale_price), Modifier.weight(1f))
                    }
                    ProfitLine(d.purchase, d.price)
                    OutlinedTextField(
                        value = d.barcode, onValueChange = { d.barcode = it },
                        label = { Text(stringResource(R.string.item_barcode_optional)) }, singleLine = true,
                        leadingIcon = { Icon(painterResource(R.drawable.ic_scan), contentDescription = null) },
                        trailingIcon = { TextButton(onClick = { onScan("barcode:$index", false) }) { Text(stringResource(R.string.scan_action)) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!vm.isEdit) OpeningStockFields(vm, d, index == 0) { onScan("imei:$index", true) }
                    if (vm.moreOptions) {
                        NumberField(d.lowStock, { d.lowStock = it }, stringResource(R.string.item_low_stock), Modifier.fillMaxWidth())
                    }
                }
            }
            if (!vm.moreOptions) {
                TextButton(onClick = { vm.moreOptions = true }) { ButtonLabel(R.drawable.ic_add, stringResource(R.string.item_more_options)) }
            } else if (!vm.isEdit) {
                FormCard(stringResource(R.string.item_more_options)) {
                    // Phones and electronics: every unit has its own serial / IMEI (D-85).
                    Row(
                        Modifier.toggleable(vm.serialTracked, role = Role.Switch) { vm.serialTracked = it },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconBadge(R.drawable.ic_phone_android, Tone.INFO)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.item_track_serial), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.item_track_serial_hint), style = MaterialTheme.typography.bodySmall, color = ink2)
                        }
                        Switch(checked = vm.serialTracked, onCheckedChange = null)
                    }
                    if (!vm.serialTracked) {
                        Row(Modifier.toggleable(vm.allowDecimal, role = Role.Switch) { vm.allowDecimal = it }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.item_decimals), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.item_decimals_hint), style = MaterialTheme.typography.bodySmall, color = ink2)
                            }
                            Switch(checked = vm.allowDecimal, onCheckedChange = null)
                        }
                    }
                    TextButton(onClick = vm::addVariant) { ButtonLabel(R.drawable.ic_add, stringResource(R.string.item_add_variant)) }
                }
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
        Button(
            onClick = { vm.save(context, onSaved) },
            enabled = !vm.saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) { ButtonLabel(R.drawable.ic_check_circle, stringResource(R.string.item_save)) }
    }
    if (vm.choosingCategory) {
        val cats by vm.categories.collectAsStateWithLifecycle(emptyList())
        CategoryChooser(
            names = cats.mapNotNull { it.name }, selected = vm.category,
            onPick = { vm.category = it; vm.choosingCategory = false },
            onNew = { vm.newCategory(it); vm.choosingCategory = false },
            onDismiss = { vm.choosingCategory = false },
        )
    }
}

/** "Profit per unit: Rs 2,000 (12%)" under the two prices; nothing until both are numbers. */
@Composable
private fun ProfitLine(purchase: String, sale: String) {
    ProfitLine(Money.parse(purchase) ?: return, Money.parse(sale) ?: return)
}

/** "Profit per unit  Rs 20 (20%)" strip, green or red. */
@Composable
internal fun ProfitLine(buy: Money, sell: Money, modifier: Modifier = Modifier) {
    val c = LocalDukaanColors.current
    val profit = sell - buy
    val pct = if (sell.minor > 0) profit.minor * 100 / sell.minor else 0
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (profit.isNegative) c.lossBg else c.profitBg).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.item_profit_per_unit), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (profit.isNegative) c.loss else c.profit)
        Text(
            profit.display(LocalShopFormat.current, withSymbol = true) + ltr("  ($pct%)"),
            style = MaterialTheme.typography.titleSmall, color = if (profit.isNegative) c.loss else c.profit,
        )
    }
}

/** Pick one of the shop's categories, or name a new one. */
@Composable
private fun CategoryChooser(names: List<String>, selected: String, onPick: (String) -> Unit, onNew: (String) -> Unit, onDismiss: () -> Unit) {
    var naming by remember { mutableStateOf(names.isEmpty()) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.item_category)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(names) { n ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(n) }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Initials(n, toneFor(n), size = 36)
                            Text(n, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (n.equals(selected, ignoreCase = true)) Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (naming) {
                    OutlinedTextField(
                        newName, { newName = it }, label = { Text(stringResource(R.string.category_name)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(onClick = { naming = true }) { ButtonLabel(R.drawable.ic_add, stringResource(R.string.picker_new_category)) }
                }
            }
        },
        confirmButton = { if (naming) TextButton(onClick = { if (newName.isNotBlank()) onNew(newName) }, enabled = newName.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OpeningStockFields(vm: ItemFormViewModel, d: VariantDraft, first: Boolean, onScanImeis: () -> Unit) {
    if (vm.serialTracked) {
        Text(stringResource(R.string.item_opening_imeis), style = MaterialTheme.typography.titleSmall)
        d.serials.forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = s.serial, onValueChange = { s.serial = it },
                    label = { Text(stringResource(R.string.item_imei)) }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                if (d.serials.size > 1) {
                    IconButton(onClick = { d.serials.removeAt(i) }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.remove)) }
                }
            }
        }
        Row {
            TextButton(onClick = onScanImeis) { ButtonLabel(R.drawable.ic_scan, stringResource(R.string.scan_imeis)) }
            TextButton(onClick = { d.serials += SerialDraft(cost = d.serials.lastOrNull()?.cost.orEmpty()) }) {
                ButtonLabel(R.drawable.ic_add, stringResource(R.string.item_add_imei))
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(d.openingQty, { d.openingQty = it }, stringResource(R.string.item_in_stock_now), Modifier.weight(1f))
            OutlinedTextField(
                value = vm.unit, onValueChange = { vm.unit = it },
                label = { Text(stringResource(R.string.item_unit)) }, singleLine = true, enabled = first,
                modifier = Modifier.weight(0.7f),
            )
        }
    }
}

@Composable
fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
