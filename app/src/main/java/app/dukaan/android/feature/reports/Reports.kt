package app.dukaan.android.feature.reports

import androidx.compose.runtime.setValue
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.ui.Panel
import app.dukaan.android.core.db.CategoryTotal
import app.dukaan.android.core.db.ItemSalesRow
import app.dukaan.android.core.db.PartyRow
import app.dukaan.android.core.db.PartyType
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.report.DayRange
import app.dukaan.android.core.report.Figures
import app.dukaan.android.core.report.ReportCsv
import app.dukaan.android.core.ui.LocalShopFormat
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.RowDivider
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.SectionLabel
import app.dukaan.android.core.ui.ltr
import app.dukaan.android.core.ui.qtyLabel
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.theme.MoneyTextStyle
import app.dukaan.android.core.ui.modeLabel
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReportData(
    val figures: Figures,
    val items: List<ItemSalesRow>,
    val expenses: List<CategoryTotal>,
    val credit: List<PartyRow>,
    val stockValue: Money,
)

class ReportsViewModel(private val container: AppContainer) : ViewModel() {
    val today: LocalDate get() = LocalDate.now(container.shopZone)
    val range = MutableStateFlow(DayRange.thisMonth(today))
    private val reports = container.database.reportDao()

    /** null = still loading. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val data = range.flatMapLatest { r ->
        combine(
            combine(reports.summary(r.fromDay, r.toDay), reports.collection(r.fromDay, r.toDay)) { s, c -> Figures.of(s, c) },
            reports.itemSales(r.fromDay, r.toDay),
            reports.expensesByCategory(r.fromDay, r.toDay),
            container.database.partyDao().parties(PartyType.CUSTOMER, "").map { all -> all.filter { it.balancePaisa > 0 }.sortedByDescending { it.balancePaisa } },
            container.database.catalogDao().stockValuePaisa(),
        ) { f, items, expenses, credit, stock -> ReportData(f, items, expenses, credit, Money(stock)) }
    }.map<ReportData, ReportData?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var message by mutableStateOf<String?>(null)

    val suggestedName get() = range.value.let { "dukaan-report-${it.from}-to-${it.to}.csv" }

    fun export(context: Context, uri: Uri) {
        val d = data.value ?: return
        val r = range.value
        viewModelScope.launch {
            try {
                val shop = container.database.shopDao().get()?.name.orEmpty()
                val csv = ReportCsv.build(shop, r, d.figures, d.items, d.expenses, d.credit, d.stockValue) { context.getString(modeLabel(it)) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } ?: throw IOException("no stream")
                }
                message = context.getString(R.string.reports_exported)
            } catch (e: IOException) {
                message = context.getString(R.string.reports_export_failed)
            }
        }
    }
}

private val rangeFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

/** DESIGN.md § Reports: date range at top, one table per report, CSV export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(vm: ReportsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val data by vm.data.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { vm.export(context, it) } }
    val today = vm.today
    val presets = listOf(
        R.string.reports_today to DayRange.today(today),
        R.string.reports_yesterday to DayRange.yesterday(today),
        R.string.reports_this_month to DayRange.thisMonth(today),
        R.string.reports_last_month to DayRange.lastMonth(today),
    )
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            stringResource(R.string.reports_title),
            if (range.from == range.to) range.from.format(rangeFormat) else "${range.from.format(rangeFormat)} – ${range.to.format(rangeFormat)}",
            navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
            // The CSV holds cost and profit, so it is not offered while they are hidden (D-45).
            actions = { TextButton(onClick = { export.launch(vm.suggestedName) }, enabled = data != null && !LocalShopFormat.current.hideCost) { ButtonLabel(R.drawable.ic_download, stringResource(R.string.reports_export)) } },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (label, r) -> FilterChip(range == r, { vm.range.value = r }, { Text(stringResource(label)) }) }
            FilterChip(presets.none { it.second == range }, { picking = true }, { Text(stringResource(R.string.reports_pick)) })
        }
        vm.message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
        val d = data ?: return@Column
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Summary(d)
            ItemsTable(d.items)
            if (d.expenses.isNotEmpty()) {
                SectionLabel(stringResource(R.string.reports_expenses))
                ReportCard { d.expenses.forEach { Line(it.category, Money(it.paisa)) } }
            }
            SectionLabel(stringResource(R.string.reports_credit))
            ReportCard {
                if (d.credit.isEmpty()) Text(stringResource(R.string.reports_credit_none), color = LocalDukaanColors.current.ink2)
                d.credit.forEach { Line(it.name, Money(it.balancePaisa), color = LocalDukaanColors.current.warning) }
                if (d.credit.size > 1) Line(stringResource(R.string.reports_total), Money(d.credit.sumOf { it.balancePaisa }), bold = true)
            }
        }
    }
    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = range.from.utcMillis(), initialSelectedEndDateMillis = range.to.utcMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val from = state.selectedStartDateMillis?.toUtcDate()
                        if (from != null) vm.range.value = DayRange(from, state.selectedEndDateMillis?.toUtcDate() ?: from)
                        picking = false
                    },
                    enabled = state.selectedStartDateMillis != null,
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DateRangePicker(state, Modifier.height(480.dp)) }
    }
}

// The date picker works in UTC midnight millis.
private fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.toUtcDate() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun Summary(d: ReportData) {
    val f = d.figures
    val c = LocalDukaanColors.current
    SectionLabel(stringResource(R.string.reports_summary))
    ReportCard {
        if (f.bills == 0 && f.sales.minor == 0L) Text(stringResource(R.string.reports_no_sales), Modifier.padding(vertical = 8.dp), color = c.ink2)
        Line(stringResource(R.string.reports_sales), f.sales, bold = true)
        Line(stringResource(R.string.reports_bills), null, text = f.bills.toString())
        Line(stringResource(R.string.reports_cost), f.cost, hideable = true)
        if (f.pendingLines > 0) Text(pluralStringResource(R.plurals.reports_cost_pending, f.pendingLines, f.pendingLines), style = MaterialTheme.typography.bodySmall, color = c.warning)
        Line(stringResource(R.string.reports_gross), f.grossProfit, hideable = true, bold = true, color = if (f.grossProfit.isNegative) c.loss else c.profit)
        Line(stringResource(R.string.reports_expenses), f.expenses)
        Line(stringResource(R.string.reports_net), f.netProfit, hideable = true, bold = true, color = if (f.netProfit.isNegative) c.loss else c.profit)
        RowDivider()
        PaymentMode.entries.forEach { Line(stringResource(R.string.reports_collected_mode, stringResource(modeLabel(it))), f.collection.getValue(it)) }
        Line(stringResource(R.string.reports_collected), f.collected, bold = true)
        RowDivider()
        Line(stringResource(R.string.reports_stock_value), d.stockValue, hideable = true)
    }
}

@Composable
private fun ItemsTable(items: List<ItemSalesRow>) {
    if (items.isEmpty()) return
    val c = LocalDukaanColors.current
    SectionLabel(stringResource(R.string.reports_items))
    ReportCard {
        items.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ltr(qtyLabel(Qty(r.qtyMilli), r.unit, r.trackingType)) + " · " + stringResource(R.string.reports_profit_short), style = MaterialTheme.typography.bodySmall, color = c.ink2)
                        MoneyText(Money(r.salesPaisa - r.costPaisa), hideable = true, style = MaterialTheme.typography.bodySmall, color = c.ink2)
                    }
                }
                MoneyText(Money(r.salesPaisa))
            }
            RowDivider()
        }
        Text(stringResource(R.string.reports_items_note), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = c.ink2)
    }
}

@Composable
private fun Line(label: String, amount: Money?, text: String? = null, hideable: Boolean = false, bold: Boolean = false, color: Color = Color.Unspecified) {
    val style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = style)
        if (amount != null) MoneyText(amount, hideable = hideable, style = style, color = color)
        else Text(ltr(text.orEmpty()), style = style.merge(MoneyTextStyle))
    }
}

/** A report section as a white card (D-67). */
@Composable
private fun ReportCard(content: @Composable () -> Unit) {
    Panel(Modifier.padding(horizontal = 16.dp)) { Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) { content() } }
}
