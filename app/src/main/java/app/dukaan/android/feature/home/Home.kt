package app.dukaan.android.feature.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.db.DocStatus
import app.dukaan.android.core.db.ItemRow
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.SaleRow
import app.dukaan.android.core.ledger.invoiceNumber
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.report.DayRange
import app.dukaan.android.core.report.Figures
import app.dukaan.android.core.ui.ActionTile
import app.dukaan.android.core.ui.CountUpMoney
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.LocalShopFormat
import app.dukaan.android.core.ui.MenuRow
import app.dukaan.android.core.ui.MoneyText
import app.dukaan.android.core.ui.Motion
import app.dukaan.android.core.ui.Panel
import app.dukaan.android.core.ui.Reveal
import app.dukaan.android.core.ui.RowDivider
import app.dukaan.android.core.ui.SectionLabel
import app.dukaan.android.core.ui.Tag
import app.dukaan.android.core.ui.TagKind
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.display
import app.dukaan.android.core.ui.formatDateTime
import app.dukaan.android.core.ui.ltr
import app.dukaan.android.core.ui.modeLabel
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.theme.MoneyTextStyle
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class Period { TODAY, MONTH }

data class HomeState(
    val shopName: String,
    val figures: Figures,
    val pendingLines: Int,
    val negative: List<ItemRow>,
    val lowCount: Int,
    val itemCount: Int,
    val recent: List<SaleRow>,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val period = MutableStateFlow(Period.TODAY)
    private val reports = container.database.reportDao()
    val today: LocalDate get() = LocalDate.now(container.shopZone)

    /** The dashboard cascades in once per visit to the app, not on every return to Home (D-83). */
    var introShown = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val figures = period.flatMapLatest { p ->
        val r = if (p == Period.TODAY) DayRange.today(today) else DayRange.thisMonth(today)
        combine(reports.summary(r.fromDay, r.toDay), reports.collection(r.fromDay, r.toDay)) { s, c -> Figures.of(s, c) }
    }

    /** null = still loading. */
    val state = combine(
        container.database.shopDao().observe(),
        figures,
        reports.pendingLines(),
        container.database.catalogDao().items(""),
        container.database.saleDao().sales(),
    ) { shop, f, pending, items, sales ->
        HomeState(
            shopName = shop?.name.orEmpty(),
            figures = f,
            pendingLines = pending,
            negative = items.filter { it.onHandMilli < 0 },
            lowCount = items.count { it.lowCount > 0 && it.onHandMilli >= 0 },
            itemCount = items.size,
            recent = sales.take(5),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Sales of the last 7 shop days, oldest first, days without sales as zero. */
    val week = today.let { end ->
        val days = (6 downTo 0).map { end.minusDays(it.toLong()) }
        reports.salesByDay(DayRange.day(days.first()), DayRange.day(end)).map { rows ->
            val byDay = rows.associate { it.day to it.paisa }
            days.map { it to Money(byDay[DayRange.day(it)] ?: 0L) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Owner only: problems two phones created offline (D-78). */
    val clashes = (if (container.account.isOwner) container.database.clashDao().clashes().map { it.size } else flowOf(0))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Days since the last backup file, or null = never exported. */
    fun daysSinceExport(): Long? = container.prefs.lastExportAt.takeIf { it > 0 }
        ?.let { TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - it) }

    val hasPrinter get() = container.prefs.printerAddress != null
}

/** Where Home's "needs attention" rows and shortcuts go. */
class HomeActions(
    val onParties: () -> Unit,
    val onExpenses: () -> Unit,
    val onReports: () -> Unit,
    val onSale: (String) -> Unit,
    val onAllSales: () -> Unit,
    val onItem: (String) -> Unit,
    val onLowStock: () -> Unit,
    val onNegativeStock: () -> Unit,
    val onAddStock: () -> Unit,
    val onAddItem: () -> Unit,
    val onSell: () -> Unit,
    val onPrinter: () -> Unit,
    val onBackup: () -> Unit,
    val onClashes: () -> Unit,
)

private val dateFormat = DateTimeFormatter.ofPattern("EEE, d MMM")

/**
 * DESIGN.md § Home (D-40, D-67, D-83): greeting header → money card (collection hero, period
 * switch, Sales · Profit · Bills) → quick actions → last 7 days → needs attention → recent sales.
 * Sections cascade in on first open; money counts up; chart bars grow; the period thumb slides.
 */
@Composable
fun HomeScreen(vm: HomeViewModel, hideCost: Boolean, onToggleHideCost: () -> Unit, go: HomeActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val clashes by vm.clashes.collectAsStateWithLifecycle()
    val s = state ?: return
    val c = LocalDukaanColors.current
    val animate = remember { !vm.introShown }
    LaunchedEffect(Unit) { vm.introShown = true }
    var section = 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Reveal(section++, animate) { Header(s.shopName, vm.today, hideCost, onToggleHideCost) }
        Reveal(section++, animate) { MoneyCard(s.figures, period, hideCost) { vm.period.value = it } }

        // Quick actions (D-67): the everyday jobs, one tap each, picture first.
        Reveal(section++, animate) {
            Column {
                SectionLabel(stringResource(R.string.home_quick))
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 6.dp, end = 6.dp)) {
                        ActionTile(R.drawable.ic_cart, Tone.BRAND, stringResource(R.string.quick_sale), Modifier.weight(1f), onClick = go.onSell)
                        ActionTile(R.drawable.ic_truck, Tone.INFO, stringResource(R.string.quick_stock), Modifier.weight(1f), onClick = go.onAddStock)
                        ActionTile(R.drawable.ic_money_in, Tone.PROFIT, stringResource(R.string.quick_payment), Modifier.weight(1f), onClick = go.onParties)
                        ActionTile(R.drawable.ic_wallet, Tone.LOSS, stringResource(R.string.quick_expense), Modifier.weight(1f), onClick = go.onExpenses)
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp, start = 6.dp, end = 6.dp)) {
                        ActionTile(R.drawable.ic_add_box, Tone.BRAND, stringResource(R.string.quick_item), Modifier.weight(1f), onClick = go.onAddItem)
                        ActionTile(R.drawable.ic_receipt, Tone.NEUTRAL, stringResource(R.string.quick_bills), Modifier.weight(1f), onClick = go.onAllSales)
                        ActionTile(R.drawable.ic_report, Tone.INFO, stringResource(R.string.quick_reports), Modifier.weight(1f), onClick = go.onReports)
                        ActionTile(R.drawable.ic_backup, Tone.PROFIT, stringResource(R.string.quick_backup), Modifier.weight(1f), onClick = go.onBackup)
                    }
                }
            }
        }

        // Last 7 days: the shape of the week at a glance (only once there is something to draw).
        if (s.recent.isNotEmpty() && week.isNotEmpty()) {
            Reveal(section++, animate) { WeekChart(week, vm.today, go.onReports) }
        }

        // Needs attention: hidden when nothing needs fixing.
        val rows = buildList<@Composable () -> Unit> {
            if (clashes > 0) add { Attention(R.drawable.ic_sync, Tone.LOSS, stringResource(R.string.home_clashes, clashes), stringResource(R.string.home_fix), go.onClashes) }
            if (s.pendingLines > 0) add { Attention(R.drawable.ic_receipt, Tone.WARNING, pluralStringResource(R.plurals.home_cost_pending, s.pendingLines, s.pendingLines), stringResource(R.string.home_add_stock), go.onAddStock) }
            when (s.negative.size) {
                0 -> Unit
                1 -> s.negative.single().let { n -> add { Attention(R.drawable.ic_warning, Tone.LOSS, stringResource(R.string.home_negative_one, n.name, ltr(Qty(n.onHandMilli).format())), stringResource(R.string.home_fix)) { go.onItem(n.productId) } } }
                else -> add { Attention(R.drawable.ic_warning, Tone.LOSS, pluralStringResource(R.plurals.home_negative_many, s.negative.size, s.negative.size), stringResource(R.string.home_fix), go.onNegativeStock) }
            }
            if (s.lowCount > 0) add { Attention(R.drawable.ic_warning, Tone.WARNING, pluralStringResource(R.plurals.home_low, s.lowCount, s.lowCount), stringResource(R.string.home_view), go.onLowStock) }
        }
        if (rows.isNotEmpty()) {
            Reveal(section++, animate) {
                Column {
                    SectionLabel(stringResource(R.string.home_attention))
                    Panel(Modifier.padding(horizontal = 16.dp)) { rows.forEachIndexed { i, row -> if (i > 0) RowDivider(); row() } }
                }
            }
        }

        if (s.recent.isEmpty()) {
            // First run (DESIGN.md § States): a short checklist instead of empty numbers.
            Reveal(section++, animate) {
                Column {
                    SectionLabel(stringResource(R.string.home_get_started))
                    Panel(Modifier.padding(horizontal = 16.dp)) {
                        if (s.itemCount == 0) { MenuRow(R.drawable.ic_add_box, Tone.BRAND, stringResource(R.string.home_step_item), onClick = go.onAddItem); RowDivider() }
                        MenuRow(R.drawable.ic_cart, Tone.BRAND, stringResource(R.string.home_step_sale), onClick = go.onSell)
                        if (!vm.hasPrinter) { RowDivider(); MenuRow(R.drawable.ic_print, Tone.NEUTRAL, stringResource(R.string.home_step_printer), onClick = go.onPrinter) }
                    }
                }
            }
        } else {
            Reveal(section++, animate) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.home_recent), Modifier.weight(1f))
                        Text(
                            stringResource(R.string.home_all), Modifier.clickable(onClick = go.onAllSales).padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Panel(Modifier.padding(horizontal = 16.dp)) {
                        s.recent.forEachIndexed { i, sale ->
                            if (i > 0) RowDivider()
                            // Each bill slides in just after the one above it.
                            Reveal(section + i, animate) { RecentSale(sale) { go.onSale(sale.id) } }
                        }
                    }
                }
            }
        }

        // Backup reminder: only when the last backup file is over a week old, or never made.
        val days = vm.daysSinceExport()
        if (s.recent.isNotEmpty() && (days == null || days > 7)) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sunk)
                    .clickable(onClick = go.onBackup).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.ic_backup), contentDescription = null, tint = c.ink2, modifier = Modifier.size(20.dp))
                Text(
                    if (days == null) stringResource(R.string.home_backup_never) else pluralStringResource(R.plurals.home_backup_days, days.toInt(), days.toInt()),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.ink2,
                )
                Text(stringResource(R.string.home_export), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Shop badge, greeting by time of day, date, and the hide-cost eye (D-45). */
@Composable
private fun Header(shopName: String, today: LocalDate, hideCost: Boolean, onToggleHideCost: () -> Unit) {
    val c = LocalDukaanColors.current
    val brand = MaterialTheme.colorScheme.primary
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> R.string.home_greeting_morning
        in 12..16 -> R.string.home_greeting_afternoon
        else -> R.string.home_greeting_evening
    }
    val initials = shopName.trim().split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "D" }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(brand, Color(0xFFB8420A)))),
            contentAlignment = Alignment.Center,
        ) { Text(initials, style = MaterialTheme.typography.titleMedium, color = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(greeting) + " · " + today.format(dateFormat), style = MaterialTheme.typography.bodySmall, color = c.ink2, maxLines = 1)
            Text(shopName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onToggleHideCost) {
            Icon(
                painterResource(if (hideCost) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                stringResource(if (hideCost) R.string.cost_show else R.string.cost_hide),
                tint = c.ink2,
            )
        }
    }
}

/**
 * The brand card: money in hand (counts up), the payment split as pills, a sliding Today / Month
 * switch, and Sales · Profit (with margin) · Bills underneath. Soft circles give it depth.
 */
@Composable
private fun MoneyCard(f: Figures, period: Period, hideCost: Boolean, onPeriod: (Period) -> Unit) {
    val onBrand = Color.White
    val brand = MaterialTheme.colorScheme.primary
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(brand, Color(0xFFB8420A))))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.07f), radius = size.width * 0.42f, center = Offset(size.width * 1.02f, -size.height * 0.12f))
                drawCircle(Color.White.copy(alpha = 0.05f), radius = size.width * 0.16f, center = Offset(size.width * 0.72f, size.height * 0.42f))
            },
    ) {
        Column(Modifier.padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (period == Period.TODAY) R.string.home_collection_today else R.string.home_collection_month),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = onBrand.copy(alpha = 0.88f),
                )
                PeriodSwitch(period, onPeriod)
            }
            CountUpMoney(f.collected, withSymbol = true, style = MaterialTheme.typography.displaySmall, color = onBrand)
            // How the money came in: one pill per mode that moved (cash always).
            val format = LocalShopFormat.current
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PaymentMode.entries.filter { f.collection.getValue(it).minor != 0L || it == PaymentMode.CASH }.forEach { m ->
                    Text(
                        stringResource(modeLabel(m)) + "  " + ltr(f.collection.getValue(m).display(format, withSymbol = false)),
                        Modifier.clip(CircleShape).background(onBrand.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = onBrand,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.14f)).height(IntrinsicSize.Min)) {
            Stat(R.drawable.ic_sell, stringResource(R.string.home_sales), Modifier.weight(1f)) {
                CountUpMoney(f.sales, withSymbol = false, style = MaterialTheme.typography.titleMedium, color = onBrand)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(onBrand.copy(alpha = 0.18f)))
            Stat(R.drawable.ic_report, stringResource(R.string.home_profit), Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CountUpMoney(f.grossProfit, withSymbol = false, style = MaterialTheme.typography.titleMedium, color = onBrand, hideable = true)
                    // Margin: profit as a share of sales (hidden with cost, D-45).
                    if (!hideCost && f.sales.minor > 0) {
                        Text(
                            ltr("${f.grossProfit.minor * 100 / f.sales.minor}%"), Modifier.padding(bottom = 2.dp),
                            style = MaterialTheme.typography.labelSmall, color = onBrand.copy(alpha = 0.8f),
                        )
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(onBrand.copy(alpha = 0.18f)))
            Stat(R.drawable.ic_receipt, stringResource(R.string.home_bills), Modifier.weight(1f)) {
                Text(ltr(f.bills.toString()), style = MaterialTheme.typography.titleMedium.merge(MoneyTextStyle), color = onBrand)
            }
        }
    }
}

/** Today / Month as one pill whose white thumb slides to the choice. */
@Composable
private fun PeriodSwitch(period: Period, onPeriod: (Period) -> Unit) {
    val brand = MaterialTheme.colorScheme.primary
    val half = 64.dp
    val thumb by animateDpAsState(if (period == Period.TODAY) 0.dp else half, tween(Motion.MEDIUM, easing = FastOutSlowInEasing), label = "period")
    Box(Modifier.width(half * 2).height(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(3.dp)) {
        Box(Modifier.offset(x = thumb).width(half - 3.dp).fillMaxHeight().clip(CircleShape).background(Color.White))
        Row(Modifier.fillMaxSize()) {
            Period.entries.forEach { p ->
                val selected = p == period
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                        .selectable(selected, role = Role.Tab) { onPeriod(p) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(if (p == Period.TODAY) R.string.home_today else R.string.home_month),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) brand else Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(@DrawableRes icon: Int, label: String, modifier: Modifier, value: @Composable () -> Unit) {
    Column(modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
        }
        value()
    }
}

/** Last 7 days of sales as bars that grow in one after another; today in brand colour. */
@Composable
private fun WeekChart(days: List<Pair<LocalDate, Money>>, today: LocalDate, onOpen: () -> Unit) {
    val c = LocalDukaanColors.current
    val brand = MaterialTheme.colorScheme.primary
    val format = LocalShopFormat.current
    val max = days.maxOf { it.second.minor }.coerceAtLeast(1L)
    val total = Money(days.sumOf { it.second.minor })
    var grown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { grown = true }
    val summary = stringResource(R.string.home_week) + ": " + total.display(format, withSymbol = true)
    Column {
        SectionLabel(stringResource(R.string.home_week))
        Panel(Modifier.padding(horizontal = 16.dp).clickable(onClick = onOpen).clearAndSetSemantics { contentDescription = summary }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_week_total), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.ink2)
                    MoneyText(total, withSymbol = true, style = MaterialTheme.typography.titleMedium)
                }
                Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    days.forEachIndexed { i, (day, sales) ->
                        val target = (sales.minor.coerceAtLeast(0L).toFloat() / max).coerceIn(0.03f, 1f)
                        val height by animateFloatAsState(if (grown) target else 0f, tween(Motion.LONG, delayMillis = 120 + i * 60, easing = FastOutSlowInEasing), label = "bar")
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier.fillMaxWidth(0.62f).fillMaxHeight(height).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(if (day == today) brand else brand.copy(alpha = 0.22f)),
                            )
                        }
                    }
                }
                // The app's language (Urdu / English), observed so a language switch redraws.
                val locale = LocalConfiguration.current.locales[0]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    days.forEach { (day, _) ->
                        val isToday = day == today
                        Text(
                            day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                            Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) brand else c.ink2, fontWeight = if (isToday) FontWeight.Bold else null,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Attention(@DrawableRes icon: Int, tone: Tone, text: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon, tone, size = 36.dp)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RecentSale(s: SaleRow, onClick: () -> Unit) {
    val c = LocalDukaanColors.current
    val voided = s.status == DocStatus.VOIDED
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(R.drawable.ic_receipt, if (voided) Tone.NEUTRAL else Tone.BRAND, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ltr(invoiceNumber(s.invoicePrefix, s.invoiceSeq)) + " · " + (s.customerName ?: stringResource(R.string.sales_walk_in)),
                    style = MaterialTheme.typography.bodyMedium, textDecoration = if (voided) TextDecoration.LineThrough else null,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (voided) Tag(TagKind.VOID) else if (s.paidPaisa < s.totalPaisa) Tag(TagKind.CREDIT)
            }
            Text(formatDateTime(s.date), style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
        MoneyText(Money(s.totalPaisa), color = if (voided) c.ink2 else MaterialTheme.colorScheme.onSurface)
    }
}
