package app.dukaan.android.feature.more

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.MenuRow
import app.dukaan.android.core.ui.Panel
import app.dukaan.android.core.ui.RowDivider
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.SectionLabel
import app.dukaan.android.core.ui.Tone
import kotlinx.coroutines.launch

/** More: two icon menus, Business and Settings (D-67). Current values sit at the row end. */
@Composable
fun MoreScreen(
    container: AppContainer,
    onSales: () -> Unit, onPurchases: () -> Unit, onExpenses: () -> Unit, onReports: () -> Unit,
    onPrinter: () -> Unit, onBackup: () -> Unit, onLock: () -> Unit, onAccount: () -> Unit, onStaff: () -> Unit, onInvoice: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var checking by rememberSaveable { mutableStateOf(false) }
    var language by rememberSaveable { mutableStateOf(false) }
    val okText = stringResource(R.string.check_ok)
    val badText = stringResource(R.string.check_bad)
    val prefs = container.prefs

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader(stringResource(R.string.nav_more))
        // Signed-in account (M7); hidden in local-only mode (no Firebase config).
        if (container.account.repo.configured) {
            SectionLabel(stringResource(R.string.account_title))
            Panel(Modifier.padding(horizontal = 16.dp)) {
                MenuRow(R.drawable.ic_person, Tone.BRAND, stringResource(R.string.account_title), stringResource(if (container.account.isOwner) R.string.account_owner else R.string.account_staff), onAccount)
                // Owner-only (phase 2): who may use this shop.
                if (container.account.isOwner) {
                    RowDivider()
                    MenuRow(R.drawable.ic_person_add, Tone.INFO, stringResource(R.string.staff_title), onClick = onStaff)
                }
            }
        }
        SectionLabel(stringResource(R.string.more_business))
        Panel(Modifier.padding(horizontal = 16.dp)) {
            MenuRow(R.drawable.ic_report, Tone.INFO, stringResource(R.string.reports_title), onClick = onReports)
            RowDivider()
            MenuRow(R.drawable.ic_receipt, Tone.BRAND, stringResource(R.string.sales_title), onClick = onSales)
            RowDivider()
            MenuRow(R.drawable.ic_truck, Tone.INFO, stringResource(R.string.purchases_title), onClick = onPurchases)
            RowDivider()
            MenuRow(R.drawable.ic_wallet, Tone.LOSS, stringResource(R.string.expenses_title), onClick = onExpenses)
        }
        SectionLabel(stringResource(R.string.more_settings))
        Panel(Modifier.padding(horizontal = 16.dp)) {
            MenuRow(R.drawable.ic_print, Tone.NEUTRAL, stringResource(R.string.printer_title), prefs.printerName ?: stringResource(R.string.value_not_set), onPrinter)
            RowDivider()
            // Shop settings and restore are the owner's (phase 2); invoice design is a shop setting (D-80).
            if (container.account.isOwner || !container.account.repo.configured) {
                MenuRow(R.drawable.ic_palette, Tone.BRAND, stringResource(R.string.invoice_title), onClick = onInvoice)
                RowDivider()
                MenuRow(R.drawable.ic_backup, Tone.PROFIT, stringResource(R.string.backup_title), onClick = onBackup)
                RowDivider()
            }
            MenuRow(R.drawable.ic_lock, Tone.NEUTRAL, stringResource(R.string.lock_settings_title), stringResource(if (container.lock.enabled) R.string.value_on else R.string.value_off), onLock)
            RowDivider()
            MenuRow(
                R.drawable.ic_language, Tone.NEUTRAL, stringResource(R.string.language_title),
                stringResource(when (prefs.language) { "en" -> R.string.language_english; "ur" -> R.string.language_urdu; else -> R.string.language_phone }),
            ) { language = true }
            RowDivider()
            MenuRow(R.drawable.ic_verified, Tone.PROFIT, stringResource(R.string.check_title)) {
                if (checking) return@MenuRow
                checking = true
                scope.launch {
                    val problems = container.invariants.violations()
                    result = if (problems.isEmpty()) okText else badText + "\n\n" + problems.take(5).joinToString("\n")
                    checking = false
                }
            }
        }
    }
    if (language) LanguageDialog(container) { language = false }
    result?.let {
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(stringResource(R.string.check_title)) },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { result = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
