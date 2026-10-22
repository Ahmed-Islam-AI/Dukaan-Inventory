package app.dukaan.android.feature.items

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.SearchField
import app.dukaan.android.core.db.ItemRow
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.ui.CardRow
import app.dukaan.android.core.ui.EmptyState
import app.dukaan.android.core.ui.LocalShopFormat
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.StockBadge
import app.dukaan.android.core.ui.display
import app.dukaan.android.core.ui.ltr
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

enum class StockFilter { ALL, LOW, NEGATIVE }

data class ItemsState(
    val loading: Boolean = true,
    val rows: List<ItemRow> = emptyList(),
    val total: Int = 0,
    val low: Int = 0,
    val negative: Int = 0,
    val stockValue: Money = Money.ZERO,
    /** Category names in use, [OTHER] last. */
    val categories: List<String> = emptyList(),
)

/** Chip value for items without a category. */
const val OTHER = "\u0000other"

@OptIn(ExperimentalCoroutinesApi::class)
class ItemsViewModel(container: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(StockFilter.ALL)
    /** Category chip: null = every category, [OTHER] = items without one (D-85). */
    val category = MutableStateFlow<String?>(null)
    private val catalog = container.database.catalogDao()

    val state = combine(
        query.flatMapLatest { catalog.items(it.trim()) },
        catalog.items(""), // counts and chips always describe the whole shop, not the search
        combine(filter, category) { f, c -> f to c },
        catalog.stockValuePaisa(),
    ) { matches, all, (f, cat), value ->
        val rows = when (f) {
            StockFilter.ALL -> matches
            StockFilter.LOW -> matches.filter { it.lowCount > 0 && it.onHandMilli >= 0 }
            StockFilter.NEGATIVE -> matches.filter { it.onHandMilli < 0 }
        }.filter { cat == null || (it.categoryName ?: OTHER) == cat }
        ItemsState(
            loading = false,
            rows = rows,
            total = all.size,
            low = all.count { it.lowCount > 0 && it.onHandMilli >= 0 },
            negative = all.count { it.onHandMilli < 0 },
            stockValue = Money(value),
            categories = all.map { it.categoryName ?: OTHER }.distinct().sortedWith(compareBy({ it == OTHER }, { it.lowercase() })),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemsState())
}

@Composable
fun ItemsScreen(
    onScan: () -> Unit,
    vm: ItemsViewModel,
    hideCost: Boolean,
    onToggleHideCost: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val format = LocalShopFormat.current
    val subtitle = if (hideCost) pluralStringResource(R.plurals.items_count, state.total, state.total)
    else pluralStringResource(R.plurals.items_count_value, state.total, state.total, state.stockValue.display(format, withSymbol = true))

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.nav_items), subtitle) {
                IconButton(onClick = onToggleHideCost) {
                    Icon(
                        painterResource(if (hideCost) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        contentDescription = stringResource(if (hideCost) R.string.cost_show else R.string.cost_hide),
                    )
                }
            }
            SearchField(query, { vm.query.value = it }, stringResource(R.string.items_search), Modifier.padding(horizontal = 16.dp), onScan = onScan)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Chip(filter == StockFilter.ALL, stringResource(R.string.filter_all, state.total)) { vm.filter.value = StockFilter.ALL } }
                item { Chip(filter == StockFilter.LOW, stringResource(R.string.filter_low, state.low)) { vm.filter.value = StockFilter.LOW } }
                item { Chip(filter == StockFilter.NEGATIVE, stringResource(R.string.filter_negative, state.negative)) { vm.filter.value = StockFilter.NEGATIVE } }
            }
            // Categories (D-85): tap one to see only its items, tap again for all.
            val category by vm.category.collectAsStateWithLifecycle()
            if (state.categories.any { it != OTHER }) {
                LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.categories) { c ->
                        val label = if (c == OTHER) stringResource(R.string.picker_other) else c
                        FilterChip(
                            selected = category == c, onClick = { vm.category.value = if (category == c) null else c },
                            label = { Text(label) },
                            leadingIcon = { Initials(label, toneFor(label), size = 20) },
                        )
                    }
                }
            }
            when {
                state.loading -> Unit
                state.total == 0 && query.isBlank() -> EmptyState(
                    stringResource(R.string.items_empty_title),
                    stringResource(R.string.items_empty_body),
                    stringResource(R.string.items_add),
                    onAdd,
                    icon = R.drawable.ic_add_box,
                )
                state.rows.isEmpty() -> EmptyState(
                    stringResource(if (query.isNotBlank()) R.string.items_no_match else R.string.items_filter_empty, query),
                    "",
                    null,
                    {},
                )
                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                    items(state.rows, key = { it.productId }) { row -> Box(Modifier.animateItem()) {
                        ItemRowView(row, hideCost, onClick = { onOpen(row.productId) })
                    } }
                }
            }
        }
        if (state.total > 0) ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            text = { Text(stringResource(R.string.items_add)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun Chip(selected: Boolean, label: String, onClick: () -> Unit) =
    FilterChip(selected = selected, onClick = onClick, label = { Text(ltr(label)) })

@Composable
private fun ItemRowView(row: ItemRow, hideCost: Boolean, onClick: () -> Unit) {
    val c = LocalDukaanColors.current
    CardRow(onClick) {
        Initials(row.name, toneFor(row.categoryName ?: row.name))
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                MoneyText(Money(row.minPricePaisa), style = MaterialTheme.typography.bodySmall, color = c.ink2)
                if (row.maxPricePaisa != row.minPricePaisa) {
                    Text("–", style = MaterialTheme.typography.bodySmall, color = LocalDukaanColors.current.ink2)
                    MoneyText(Money(row.maxPricePaisa), style = MaterialTheme.typography.bodySmall, color = LocalDukaanColors.current.ink2)
                }
                if (row.variantCount > 1) {
                    Text(
                        pluralStringResource(R.plurals.items_variants, row.variantCount, row.variantCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.ink2,
                    )
                }
                // What one sale earns (single-price items; hidden with cost, D-45).
                val buy = row.minPurchasePaisa
                if (!hideCost && buy != null && row.variantCount == 1) {
                    Text("·", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                    Text(
                        stringResource(R.string.items_profit_each, Money(row.minPricePaisa - buy).display(LocalShopFormat.current, withSymbol = false)),
                        style = MaterialTheme.typography.bodySmall, color = if (row.minPricePaisa >= buy) c.profit else c.loss,
                    )
                }
            }
        }
        StockBadge(Qty(row.onHandMilli), row.unit, row.trackingType, low = row.lowCount > 0)
    }
}
