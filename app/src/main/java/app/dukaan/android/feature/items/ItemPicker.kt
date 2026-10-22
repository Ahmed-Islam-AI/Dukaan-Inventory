package app.dukaan.android.feature.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dukaan.android.R
import app.dukaan.android.core.db.CategoryCount
import app.dukaan.android.core.db.PickDao
import app.dukaan.android.core.db.PickItem
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.ui.LocalShopFormat
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.Panel
import app.dukaan.android.core.ui.RowDivider
import app.dukaan.android.core.ui.SearchField
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.ltr
import app.dukaan.android.core.ui.pressable
import app.dukaan.android.core.ui.qtyLabel
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.toneColors
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Sell shows prices and hides empty categories; Stock shows stock and lets you create (D-85). */
enum class PickerMode { SELL, STOCK }

/**
 * Category → item picker (D-85), as a sheet over Sell and Add stock:
 *
 *   search / scan ─▶ matches          (a scanned barcode is handled by the screen and adds at once)
 *   categories grid ─▶ items of one ─▶ onPick (the sheet stays open to pick more)
 *   Stock mode: "+ New category" tile, "+ New item" row
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemPickerSheet(
    dao: PickDao,
    mode: PickerMode,
    title: String,
    picked: (variantId: String) -> Int,
    onPick: (PickItem) -> Unit,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
    onNewCategory: (suspend (String) -> String)? = null,
    onNewItem: ((CategoryCount) -> Unit)? = null,
    initialQuery: String = "",
    footer: @Composable () -> Unit = {},
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var open by remember { mutableStateOf<CategoryCount?>(null) }
    var naming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val c = LocalDukaanColors.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val inCategory = open != null && query.isBlank()
                if (inCategory) IconButton(onClick = { open = null }) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) }
                Text(
                    if (inCategory) open?.name ?: stringResource(R.string.picker_other) else title,
                    Modifier.weight(1f).padding(start = if (inCategory) 0.dp else 16.dp),
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) }
            }
            SearchField(query, { query = it }, stringResource(R.string.picker_search), Modifier.padding(horizontal = 16.dp), onScan = onScan)
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
                when {
                    query.isNotBlank() -> {
                        val q = query.trim()
                        val results by remember(q) { dao.search(q) }.collectAsState(emptyList())
                        if (results.isEmpty()) Text(stringResource(R.string.picker_no_match, q), Modifier.padding(16.dp), color = c.ink2)
                        else ItemList(results, mode, picked, onPick)
                    }
                    open == null -> {
                        val all by remember { dao.categories() }.collectAsState(null)
                        // Selling from an empty category is a dead end; when stocking, it's where new items go.
                        val shown = all.orEmpty().filter { it.items > 0 || (mode == PickerMode.STOCK && it.id != null) }
                        LazyVerticalGrid(
                            GridCells.Fixed(2), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(shown, key = { it.id ?: "other" }) { cat -> CategoryTile(cat) { open = cat } }
                            if (onNewCategory != null) item { NewTile(stringResource(R.string.picker_new_category)) { naming = true } }
                        }
                        if (all != null && shown.isEmpty() && onNewCategory == null) {
                            Text(stringResource(R.string.picker_nothing), Modifier.padding(24.dp), color = c.ink2)
                        }
                    }
                    else -> {
                        val cat = open!!
                        val items by remember(cat.id) { if (cat.items == 0 && cat.id == null) flowOf(emptyList()) else dao.inCategory(cat.id) }.collectAsState(emptyList())
                        Column {
                            if (items.isEmpty()) Text(stringResource(R.string.picker_empty_category), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = c.ink2)
                            // New items need a real category ("Other" is only where old items wait).
                            if (onNewItem != null && cat.id != null) {
                                Panel(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth().pressable { onNewItem(cat) }.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                            Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                        Text(stringResource(R.string.picker_new_item), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            ItemList(items, mode, picked, onPick)
                        }
                    }
                }
            }
            footer()
        }
    }

    if (naming && onNewCategory != null) {
        NameDialog(stringResource(R.string.picker_new_category), stringResource(R.string.category_name), onDismiss = { naming = false }) { name ->
            naming = false
            scope.launch { open = CategoryCount(onNewCategory(name), name.trim(), 0) }
        }
    }
}

@Composable
private fun ItemList(items: List<PickItem>, mode: PickerMode, picked: (String) -> Int, onPick: (PickItem) -> Unit) {
    val c = LocalDukaanColors.current
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        item {
            Panel {
                items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider()
                    val count = picked(item.variantId)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).pressable { onPick(item) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Initials(item.productName, if (item.trackingType == TrackingType.SERIAL) Tone.INFO else Tone.BRAND)
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val stock = qtyLabel(Qty(item.onHandMilli), item.unit, item.trackingType)
                            Text(
                                item.serial?.let { ltr("${LocalShopFormat.current.serialLabel} $it") } ?: stringResource(R.string.purchase_pick_stock, ltr(stock)),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.onHandMilli <= 0 && item.serial == null) c.warning else c.ink2,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (mode == PickerMode.SELL) MoneyText(Money(item.salePricePaisa), style = MaterialTheme.typography.titleSmall)
                            else item.purchasePricePaisa?.let { MoneyText(Money(it), style = MaterialTheme.typography.titleSmall, color = c.ink2) }
                        }
                        // How many are already on the bill / form; a plain + until then.
                        Box(
                            Modifier.size(30.dp).clip(CircleShape)
                                .background(if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (count > 0) Text(ltr(count.toString()), style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White)
                            else Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(cat: CategoryCount, onClick: () -> Unit) {
    val name = cat.name ?: stringResource(R.string.picker_other)
    Panel {
        Column(Modifier.fillMaxWidth().pressable(onClick = onClick).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Initials(name, toneFor(name), size = 44)
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.picker_items, cat.items, cat.items), style = MaterialTheme.typography.bodySmall, color = LocalDukaanColors.current.ink2)
            }
        }
    }
}

@Composable
private fun NewTile(label: String, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().heightIn(min = 112.dp).clip(RoundedCornerShape(16.dp))
            .background(primary.copy(alpha = 0.08f)).pressable(onClick = onClick).padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = primary)
            Text(label, style = MaterialTheme.typography.titleSmall, color = primary)
        }
    }
}

/** Rounded square with up to two initials in the tone's colours. */
@Composable
fun Initials(name: String, tone: Tone, size: Int = 40) {
    val (fg, bg) = toneColors(tone)
    val initials = name.trim().split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { name.trim().take(1).uppercase().ifEmpty { "?" } }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(initials, style = if (size >= 44) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, color = fg)
    }
}

/** A steady colour per category name, so "Chargers" is always the same tile colour. */
fun toneFor(name: String): Tone = listOf(Tone.BRAND, Tone.INFO, Tone.PROFIT, Tone.WARNING, Tone.LOSS)[Math.floorMod(name.trim().lowercase().hashCode(), 5)]

/** One-field dialog: a name (category). */
@Composable
fun NameDialog(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it }, label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
        },
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onSave(value) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
