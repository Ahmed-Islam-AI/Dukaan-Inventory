package app.dukaan.android

import app.dukaan.android.feature.more.InvoiceDesignViewModel
import app.dukaan.android.feature.more.InvoiceDesignScreen
import app.dukaan.android.feature.more.ClashesViewModel
import app.dukaan.android.feature.more.ClashesScreen
import app.dukaan.android.feature.more.StaffViewModel
import app.dukaan.android.feature.more.StaffScreen
import app.dukaan.android.feature.more.AccountScreen
import app.dukaan.android.feature.auth.message
import app.dukaan.android.feature.auth.AuthViewModel
import app.dukaan.android.feature.auth.AuthScreen
import app.dukaan.android.feature.auth.AccountStatusScreen
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.account.AccountState
import app.dukaan.android.core.account.AccountError
import app.dukaan.android.core.ui.Motion
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import app.dukaan.android.core.ui.LocalNotify
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import app.dukaan.android.core.scan.ScanScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.dukaan.android.core.lock.ConfirmPinDialog
import app.dukaan.android.core.lock.LockScreen
import app.dukaan.android.feature.more.AppLockScreen
import app.dukaan.android.feature.more.BackupScreen
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.dukaan.android.feature.more.BackupViewModel
import app.dukaan.android.feature.more.PrinterScreen
import app.dukaan.android.feature.more.PrinterViewModel
import app.dukaan.android.feature.expenses.ExpensesScreen
import app.dukaan.android.feature.expenses.ExpensesViewModel
import app.dukaan.android.feature.parties.PartiesScreen
import app.dukaan.android.feature.parties.PartiesViewModel
import app.dukaan.android.feature.parties.PartyDetailScreen
import app.dukaan.android.feature.parties.PartyDetailViewModel
import app.dukaan.android.feature.sell.ReceiptScreen
import app.dukaan.android.feature.sell.ReturnScreen
import app.dukaan.android.feature.sell.ReturnViewModel
import app.dukaan.android.feature.sell.ReceiptViewModel
import app.dukaan.android.feature.sell.SalesScreen
import app.dukaan.android.feature.sell.SalesViewModel
import app.dukaan.android.feature.sell.SellScreen
import app.dukaan.android.feature.sell.SellViewModel
import app.dukaan.android.core.db.ShopProfileEntity
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.ui.LocalShopFormat
import app.dukaan.android.core.ui.ShopFormat
import app.dukaan.android.core.receipt.serialLabelFor
import app.dukaan.android.feature.items.ItemDetailScreen
import app.dukaan.android.feature.items.ItemDetailViewModel
import app.dukaan.android.feature.items.ItemFormScreen
import app.dukaan.android.feature.items.ItemFormViewModel
import app.dukaan.android.feature.items.ItemsScreen
import app.dukaan.android.feature.items.ItemsViewModel
import app.dukaan.android.feature.items.StockFilter
import app.dukaan.android.feature.home.HomeActions
import app.dukaan.android.feature.home.HomeScreen
import app.dukaan.android.feature.home.HomeViewModel
import app.dukaan.android.feature.reports.ReportsScreen
import app.dukaan.android.feature.reports.ReportsViewModel
import app.dukaan.android.feature.more.MoreScreen
import app.dukaan.android.feature.onboarding.OnboardingScreen
import app.dukaan.android.feature.onboarding.OnboardingViewModel
import app.dukaan.android.feature.purchase.PurchaseDetailScreen
import app.dukaan.android.feature.purchase.PurchaseDetailViewModel
import app.dukaan.android.feature.purchase.PurchaseFormScreen
import app.dukaan.android.feature.purchase.PurchaseFormViewModel
import app.dukaan.android.feature.purchase.PurchasesScreen
import app.dukaan.android.feature.purchase.PurchasesViewModel
import java.time.ZoneId
import kotlinx.coroutines.flow.map

/** Bottom navigation (DESIGN.md § Navigation): Home · Items · [SELL] · Parties · More. */
private enum class Tab(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    HOME("home", R.string.nav_home, R.drawable.ic_home),
    ITEMS("items", R.string.nav_items, R.drawable.ic_items),
    SELL("sell", R.string.nav_sell, R.drawable.ic_sell),
    PARTIES("parties", R.string.nav_parties, R.drawable.ic_parties),
    MORE("more", R.string.nav_more, R.drawable.ic_more),
}

private object Route {
    const val ONBOARDING = "onboarding"
    const val ITEM_NEW = "items/new"
    const val ITEM = "items/{id}"
    const val ITEM_EDIT = "items/{id}/edit"
    const val PURCHASES = "purchases"
    const val PURCHASE_NEW = "purchases/new"
    const val PURCHASE_NEW_FOR = "purchases/new/{productId}"
    fun purchaseNewFor(productId: String) = "purchases/new/$productId"
    const val PURCHASE = "purchases/{id}"
    const val RECEIPT = "receipt/{id}?afterSale={afterSale}"
    const val SALES = "sales"
    const val BACKUP = "backup"
    const val PRINTER = "printer"
    const val PARTY = "parties/{id}"
    const val RETURN = "returns/{saleId}"
    const val EXPENSES = "expenses"
    const val REPORTS = "reports"
    const val LOCK = "lock"
    const val ACCOUNT = "account"
    const val STAFF = "staff"
    const val CLASHES = "clashes"
    const val INVOICE_DESIGN = "invoice-design"
    fun party(id: String) = "parties/$id"
    fun saleReturn(saleId: String) = "returns/$saleId"
    const val SCAN = "scan/{target}?continuous={continuous}"
    fun scan(target: String, continuous: Boolean) = "scan/$target?continuous=$continuous"
    fun receipt(id: String, afterSale: Boolean) = "receipt/$id?afterSale=$afterSale"
    fun item(id: String) = "items/$id"
    fun itemEdit(id: String) = "items/$id/edit"
    fun purchase(id: String) = "purchases/$id"
}

private class Loaded(val profile: ShopProfileEntity?)

private const val SCAN_RESULT = "scan_result"
private const val ITEMS_FILTER = "items_filter"

/** Delivers codes from the scanner back to this destination once: (target field, codes). */
@Composable
private fun ScanResults(entry: NavBackStackEntry, onResult: (String, List<String>) -> Unit) {
    val result by entry.savedStateHandle.getStateFlow<ArrayList<String>?>(SCAN_RESULT, null).collectAsStateWithLifecycle()
    LaunchedEffect(result) {
        val r = result ?: return@LaunchedEffect
        entry.savedStateHandle[SCAN_RESULT] = null
        if (r.size > 1) onResult(r.first(), r.drop(1))
    }
}

/**
 * Account gate (M7, docs/ACCOUNTS.md): sign-up/login, status screens, or the shop app.
 * LOCAL = no Firebase config, the app behaves as before accounts existed.
 */
@Composable
fun DukaanRoot() {
    val container = LocalContext.current.dukaanContainer()
    val account by container.account.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Account screens have no Scaffold: paint the app background behind them.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    when (account) {
        AccountState.LOCAL, AccountState.READY -> ShopApp()
        AccountState.CHECKING -> AccountStatusScreen(R.drawable.ic_store, Tone.BRAND, stringResource(R.string.app_name), stringResource(R.string.auth_checking), null, busy = true)
        AccountState.SIGNED_OUT, AccountState.NEEDS_SHOP -> {
            val vm: AuthViewModel = viewModel(factory = viewModelFactory { initializer { AuthViewModel(container) } })
            AuthScreen(vm)
        }
        AccountState.BLOCKED -> {
            // Blocked by the app owner, or removed from the shop by its owner (phase 2).
            val removedFrom = container.account.removedFromShop
            AccountStatusScreen(
                R.drawable.ic_block, Tone.LOSS,
                if (removedFrom != null) stringResource(R.string.auth_removed_title, removedFrom) else stringResource(R.string.auth_blocked_title),
                stringResource(if (removedFrom != null) R.string.auth_removed_body else R.string.auth_blocked_body),
                stringResource(R.string.ok),
            ) { container.account.acknowledgeBlocked() }
        }
        AccountState.NEEDS_INTERNET -> {
            val context = LocalContext.current
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            AccountStatusScreen(
                R.drawable.ic_warning, Tone.WARNING, stringResource(R.string.auth_connect_title), error ?: stringResource(R.string.auth_connect_body),
                stringResource(R.string.auth_try_again), busy,
            ) {
                scope.launch {
                    busy = true
                    try { container.account.retry() } catch (e: AccountError) { error = e.message(context) } finally { busy = false }
                }
            }
        }
    }
    }
}

/** The shop app itself (tabs, screens) once the account allows it. */
@Composable
private fun ShopApp() {
    val container = LocalContext.current.dukaanContainer()
    val loaded by remember { container.database.shopDao().observe().map { Loaded(it) } }
        .collectAsStateWithLifecycle(initialValue = null)
    var hideCost by remember { mutableStateOf(container.prefs.hideCost) }
    var confirmShowCost by remember { mutableStateOf(false) }
    val locked by container.lock.locked.collectAsStateWithLifecycle()
    // D-45: hiding is one tap; showing cost again needs the PIN when the app lock is on.
    val toggleHideCost = {
        if (hideCost && container.lock.enabled) confirmShowCost = true
        else { hideCost = !hideCost; container.prefs.hideCost = hideCost }
    }

    // null = still loading: a blank frame beats showing onboarding and yanking it away.
    val profile = (loaded ?: return).profile
    // Decided once: changing NavHost's start destination later would rebuild the graph.
    val start = remember { if (profile == null) Route.ONBOARDING else Tab.HOME.route }
    LaunchedEffect(profile?.timeZone) {
        profile?.timeZone?.let { container.shopZone = runCatching { ZoneId.of(it) }.getOrDefault(ZoneId.systemDefault()) }
    }
    val format = ShopFormat(
        symbol = profile?.currencySymbol ?: "Rs",
        lakhGrouping = (profile?.currencyCode ?: "PKR") in Money.LAKH_CURRENCIES,
        hideCost = hideCost,
        serialLabel = serialLabelFor(profile?.shopType),
    )
    val nav = rememberNavController()
    // Confirmations ("Item saved") for every screen through LocalNotify.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { msg ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }
    CompositionLocalProvider(LocalShopFormat provides format, LocalNotify provides notify) {
        Box(Modifier.fillMaxSize()) {
            // While locked, the app underneath is hidden from TalkBack too.
            Scaffold(
                Modifier.then(if (locked) Modifier.clearAndSetSemantics {} else Modifier),
                bottomBar = { BottomBar(nav) },
            ) { padding ->
                NavHost(
                    nav, startDestination = start, Modifier.padding(padding),
                    // D-69: screens fade in and settle from 97% (direction-free, so RTL needs nothing special).
                    enterTransition = { fadeIn(tween(Motion.MEDIUM)) + scaleIn(tween(Motion.MEDIUM), initialScale = 0.97f) },
                    exitTransition = { fadeOut(tween(Motion.SHORT)) },
                    popEnterTransition = { fadeIn(tween(Motion.MEDIUM)) },
                    popExitTransition = { fadeOut(tween(Motion.SHORT)) + scaleOut(tween(Motion.SHORT), targetScale = 0.97f) },
                ) {
                    composable(Route.ONBOARDING) {
                        val vm: OnboardingViewModel = viewModel(factory = viewModelFactory { initializer { OnboardingViewModel(container) } })
                        OnboardingScreen(vm) {
                            nav.navigate(Tab.HOME.route) { popUpTo(Route.ONBOARDING) { inclusive = true } }
                        }
                    }
                    composable(Tab.HOME.route) {
                        val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
                        fun itemsWith(filter: StockFilter) {
                            nav.navigateToTab(Tab.ITEMS)
                            nav.getBackStackEntry(Tab.ITEMS.route).savedStateHandle[ITEMS_FILTER] = filter.name
                        }
                        HomeScreen(
                            vm, hideCost,
                            onToggleHideCost = toggleHideCost,
                            go = HomeActions(
                            onParties = { nav.navigateToTab(Tab.PARTIES) },
                            onExpenses = { nav.navigate(Route.EXPENSES) },
                            onReports = { nav.navigate(Route.REPORTS) },
                                onSale = { nav.navigate(Route.receipt(it, afterSale = false)) },
                                onAllSales = { nav.navigate(Route.SALES) },
                                onItem = { nav.navigate(Route.item(it)) },
                                onLowStock = { itemsWith(StockFilter.LOW) },
                                onNegativeStock = { itemsWith(StockFilter.NEGATIVE) },
                                onAddStock = { nav.navigate(Route.PURCHASE_NEW) },
                                onAddItem = { nav.navigate(Route.ITEM_NEW) },
                                onSell = { nav.navigateToTab(Tab.SELL) },
                                onPrinter = { nav.navigate(Route.PRINTER) },
                                onBackup = { nav.navigate(Route.BACKUP) },
                                onClashes = { nav.navigate(Route.CLASHES) },
                            ),
                        )
                    }
                    composable(Tab.ITEMS.route) { entry ->
                        val vm: ItemsViewModel = viewModel(factory = viewModelFactory { initializer { ItemsViewModel(container) } })
                        ScanResults(entry) { _, codes -> vm.query.value = codes.first() }
                        // Home's "needs attention" opens Items already filtered (low / negative).
                        val filter by entry.savedStateHandle.getStateFlow<String?>(ITEMS_FILTER, null).collectAsStateWithLifecycle()
                        LaunchedEffect(filter) {
                            filter?.let { vm.filter.value = StockFilter.valueOf(it); entry.savedStateHandle[ITEMS_FILTER] = null }
                        }
                        ItemsScreen(
                            onScan = { nav.navigate(Route.scan("items", continuous = false)) },
                            vm = vm, hideCost = hideCost,
                            onToggleHideCost = toggleHideCost,
                            onOpen = { nav.navigate(Route.item(it)) },
                            onAdd = { nav.navigate(Route.ITEM_NEW) },
                        )
                    }
                    composable(Route.ITEM_NEW) { entry ->
                        val vm: ItemFormViewModel = viewModel(factory = viewModelFactory { initializer { ItemFormViewModel(container, null) } })
                        ScanResults(entry, vm::onScanned)
                        val saved = stringResource(R.string.msg_item_saved)
                        ItemFormScreen(vm, onScan = { t, c -> nav.navigate(Route.scan(t, c)) }, onBack = { nav.popBackStack() }, onSaved = { id ->
                            notify(saved)
                            nav.navigate(Route.item(id)) { popUpTo(Route.ITEM_NEW) { inclusive = true } }
                        })
                    }
                    composable(Route.ITEM) { entry ->
                        val id = entry.arguments?.getString("id") ?: return@composable
                        val vm: ItemDetailViewModel = viewModel(key = id, factory = viewModelFactory { initializer { ItemDetailViewModel(container, id) } })
                        ItemDetailScreen(
                            vm, hideCost,
                            onBack = { nav.popBackStack() },
                            onEdit = { nav.navigate(Route.itemEdit(id)) },
                            onAddStock = { nav.navigate(Route.purchaseNewFor(id)) },
                        )
                    }
                    composable(Route.ITEM_EDIT) { entry ->
                        val id = entry.arguments?.getString("id") ?: return@composable
                        val vm: ItemFormViewModel = viewModel(key = "edit-$id", factory = viewModelFactory { initializer { ItemFormViewModel(container, id) } })
                        ScanResults(entry, vm::onScanned)
                        val saved = stringResource(R.string.msg_saved)
                        ItemFormScreen(vm, onScan = { t, c -> nav.navigate(Route.scan(t, c)) }, onBack = { nav.popBackStack() }, onSaved = { notify(saved); nav.popBackStack() })
                    }
                    composable(Tab.MORE.route) {
                        MoreScreen(
                            container,
                            onSales = { nav.navigate(Route.SALES) },
                            onPurchases = { nav.navigate(Route.PURCHASES) },
                            onExpenses = { nav.navigate(Route.EXPENSES) },
                            onReports = { nav.navigate(Route.REPORTS) },
                            onPrinter = { nav.navigate(Route.PRINTER) },
                            onBackup = { nav.navigate(Route.BACKUP) },
                            onLock = { nav.navigate(Route.LOCK) },
                            onAccount = { nav.navigate(Route.ACCOUNT) },
                            onStaff = { nav.navigate(Route.STAFF) },
                            onInvoice = { nav.navigate(Route.INVOICE_DESIGN) },
                        )
                    }
                    composable(Tab.SELL.route) { entry ->
                        // Activity-scoped: a cart in progress survives leaving the Sell tab or pressing Back.
                        val vm: SellViewModel = viewModel(
                            viewModelStoreOwner = LocalActivity.current as ComponentActivity,
                            factory = viewModelFactory { initializer { SellViewModel(container) } },
                        )
                        ScanResults(entry) { _, codes -> vm.onScanned(codes.first()) }
                        SellScreen(vm, onScan = { nav.navigate(Route.scan("sell", continuous = false)) }, onSaved = { id -> nav.navigate(Route.receipt(id, afterSale = true)) })
                    }
                    composable(
                        Route.RECEIPT,
                        arguments = listOf(navArgument("afterSale") { type = NavType.BoolType; defaultValue = false }),
                    ) { entry ->
                        val id = entry.arguments?.getString("id") ?: return@composable
                        val afterSale = entry.arguments?.getBoolean("afterSale") ?: false
                        val vm: ReceiptViewModel = viewModel(key = "receipt-$id", factory = viewModelFactory { initializer { ReceiptViewModel(container, id, afterSale) } })
                        ReceiptScreen(
                            vm, afterSale,
                            // Back from a fresh receipt lands on an empty cart, ready for the next customer.
                            onNewSale = { nav.popBackStack() },
                            onBack = { nav.popBackStack() },
                            onReturn = { nav.navigate(Route.saleReturn(id)) },
                        )
                    }
                    composable(Route.RETURN) { entry ->
                        val saleId = entry.arguments?.getString("saleId") ?: return@composable
                        val vm: ReturnViewModel = viewModel(key = "return-$saleId", factory = viewModelFactory { initializer { ReturnViewModel(container, saleId) } })
                        ReturnScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable(Tab.PARTIES.route) {
                        val vm: PartiesViewModel = viewModel(factory = viewModelFactory { initializer { PartiesViewModel(container) } })
                        PartiesScreen(vm, onOpen = { nav.navigate(Route.party(it)) })
                    }
                    composable(Route.PARTY) { entry ->
                        val id = entry.arguments?.getString("id") ?: return@composable
                        val vm: PartyDetailViewModel = viewModel(key = "party-$id", factory = viewModelFactory { initializer { PartyDetailViewModel(container, id) } })
                        PartyDetailScreen(vm, onBack = { nav.popBackStack() }, onOpenSale = { nav.navigate(Route.receipt(it, afterSale = false)) })
                    }
                    composable(Route.REPORTS) {
                        val vm: ReportsViewModel = viewModel(factory = viewModelFactory { initializer { ReportsViewModel(container) } })
                        ReportsScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable(Route.EXPENSES) {
                        val vm: ExpensesViewModel = viewModel(factory = viewModelFactory { initializer { ExpensesViewModel(container) } })
                        ExpensesScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable(Route.SALES) {
                        val vm: SalesViewModel = viewModel(factory = viewModelFactory { initializer { SalesViewModel(container) } })
                        SalesScreen(
                            vm,
                            onBack = { nav.popBackStack() },
                            onOpen = { nav.navigate(Route.receipt(it, afterSale = false)) },
                            onSell = { nav.navigate(Tab.SELL.route) },
                        )
                    }
                    composable(
                        Route.SCAN,
                        arguments = listOf(navArgument("continuous") { type = NavType.BoolType; defaultValue = false }),
                    ) { entry ->
                        val target = entry.arguments?.getString("target") ?: return@composable
                        val continuous = entry.arguments?.getBoolean("continuous") ?: false
                        ScanScreen(
                            continuous,
                            onResult = { codes ->
                                // Hand the codes back to the screen that asked, tagged with its field.
                                nav.previousBackStackEntry?.savedStateHandle?.set(SCAN_RESULT, arrayListOf(target) + codes)
                                nav.popBackStack()
                            },
                            onClose = { nav.popBackStack() },
                        )
                    }
                    composable(Route.LOCK) { AppLockScreen(container, onBack = { nav.popBackStack() }) }
                    composable(Route.ACCOUNT) { AccountScreen(container, onBack = { nav.popBackStack() }) }
                    composable(Route.INVOICE_DESIGN) {
                        val vm: InvoiceDesignViewModel = viewModel(factory = viewModelFactory { initializer { InvoiceDesignViewModel(container) } })
                        InvoiceDesignScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable(Route.CLASHES) {
                        val vm: ClashesViewModel = viewModel(factory = viewModelFactory { initializer { ClashesViewModel(container) } })
                        ClashesScreen(vm, onBack = { nav.popBackStack() }, onSale = { nav.navigate(Route.receipt(it, afterSale = false)) })
                    }
                    composable(Route.STAFF) {
                        val vm: StaffViewModel = viewModel(factory = viewModelFactory { initializer { StaffViewModel(container) } })
                        StaffScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable(Route.PRINTER) {
                        val vm: PrinterViewModel = viewModel(factory = viewModelFactory { initializer { PrinterViewModel(container) } })
                        PrinterScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable(Route.BACKUP) {
                        val vm: BackupViewModel = viewModel(factory = viewModelFactory { initializer { BackupViewModel(container) } })
                        BackupScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable(Route.PURCHASES) {
                        val vm: PurchasesViewModel = viewModel(factory = viewModelFactory { initializer { PurchasesViewModel(container) } })
                        PurchasesScreen(vm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Route.purchase(it)) }, onNew = { nav.navigate(Route.PURCHASE_NEW) })
                    }
                    composable(Route.PURCHASE_NEW) { entry ->
                        val vm: PurchaseFormViewModel = viewModel(factory = viewModelFactory { initializer { PurchaseFormViewModel(container) } })
                        ScanResults(entry, vm::onScanned)
                        val added = stringResource(R.string.msg_stock_added)
                        PurchaseFormScreen(vm, onScan = { t, c -> nav.navigate(Route.scan(t, c)) }, onBack = { nav.popBackStack() }, onSaved = { notify(added); nav.popBackStack() })
                    }
                    composable(Route.PURCHASE_NEW_FOR) { entry ->
                        val productId = entry.arguments?.getString("productId") ?: return@composable
                        val vm: PurchaseFormViewModel = viewModel(key = "buy-$productId", factory = viewModelFactory { initializer { PurchaseFormViewModel(container, productId) } })
                        ScanResults(entry, vm::onScanned)
                        val added = stringResource(R.string.msg_stock_added)
                        PurchaseFormScreen(vm, onScan = { t, c -> nav.navigate(Route.scan(t, c)) }, onBack = { nav.popBackStack() }, onSaved = { notify(added); nav.popBackStack() })
                    }
                    composable(Route.PURCHASE) { entry ->
                        val id = entry.arguments?.getString("id") ?: return@composable
                        val vm: PurchaseDetailViewModel = viewModel(key = id, factory = viewModelFactory { initializer { PurchaseDetailViewModel(container, id) } })
                        PurchaseDetailScreen(vm, onBack = { nav.popBackStack() })
                    }
                }
            }
            // Top of the screen: never covers a Save button, a floating "Add" button or the bottom bar.
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding()) { data -> Confirmation(data.visuals.message) }
            if (locked && profile != null) LockScreen(container.lock, container.prefs.deviceUnlock, profile.name)
            if (confirmShowCost) {
                ConfirmPinDialog(container.lock, stringResource(R.string.cost_show), onDismiss = { confirmShowCost = false }) {
                    confirmShowCost = false
                    hideCost = false
                    container.prefs.hideCost = false
                }
            }
        }
    }
}

/** Tab switch that keeps each tab's own back stack and state (bottom bar behaviour). */
private fun NavHostController.navigateToTab(tab: Tab) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun BottomBar(nav: NavHostController) {
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    // Bottom bar only on top-level tabs; forms and details get the full screen.
    if (Tab.entries.none { it.route == route }) return
    NavigationBar {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = route == tab.route,
                onClick = { nav.navigateToTab(tab) },
                icon = {
                    if (tab == Tab.SELL) {
                        Box(
                            Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(tab.icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
                    } else {
                        Icon(painterResource(tab.icon), contentDescription = null)
                    }
                },
                label = { Text(stringResource(tab.label)) },
                // The raised Sell button is its own highlight; no extra pill behind it.
                colors = if (tab == Tab.SELL) NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent) else NavigationBarItemDefaults.colors(),
            )
        }
    }
}

/** Dark pill with a green check: "it worked", readable at arm's length. */
@Composable
private fun Confirmation(message: String) {
    Row(
        Modifier.padding(16.dp).background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = Color(0xFF69DB7C))
        Text(message, Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.surface, style = MaterialTheme.typography.bodyLarge)
    }
}
