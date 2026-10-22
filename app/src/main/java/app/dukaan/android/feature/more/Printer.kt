package app.dukaan.android.feature.more

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.printer.PairedPrinter
import app.dukaan.android.core.printer.PrintError
import app.dukaan.android.core.receipt.PaperWidth
import app.dukaan.android.core.receipt.ReceiptRenderer
import app.dukaan.android.core.receipt.Row as ReceiptRow
import app.dukaan.android.core.ui.RowDivider
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrinterViewModel(private val container: AppContainer) : ViewModel() {
    private val prefs = container.prefs
    var devices by mutableStateOf<List<PairedPrinter>>(emptyList())
        private set
    var selected by mutableStateOf(prefs.printerAddress)
        private set
    var paperMm by mutableIntStateOf(prefs.paperMm)
        private set
    var autoPrint by mutableStateOf(prefs.autoPrint)
        private set
    var message by mutableStateOf<String?>(null)
    var printing by mutableStateOf(false)
        private set

    fun load(context: android.content.Context) {
        message = null
        devices = try {
            container.printer.pairedDevices()
        } catch (e: PrintError) {
            message = printErrorMessage(context, e)
            emptyList()
        }
    }

    fun select(p: PairedPrinter) {
        prefs.printerAddress = p.address
        prefs.printerName = p.name
        selected = p.address
    }

    fun setPaper(mm: Int) {
        prefs.paperMm = mm
        paperMm = mm
    }

    fun setAuto(on: Boolean) {
        prefs.autoPrint = on
        autoPrint = on
    }

    /** Prints a short sample so the owner can check paper width and darkness. */
    fun testPrint(context: android.content.Context) {
        val address = selected ?: return
        if (printing) return
        printing = true
        message = context.getString(R.string.printer_printing)
        viewModelScope.launch {
            try {
                val shop = container.database.shopDao().get()
                val rows = listOf(
                    ReceiptRow.Title(shop?.name ?: "Dukaan"),
                    ReceiptRow.Center(context.getString(R.string.printer_test_line)),
                    ReceiptRow.Divider,
                    ReceiptRow.Pair("1 x 1,000", "1,000"),
                    ReceiptRow.Center("اردو  ·  English  ·  1234567890"),
                    ReceiptRow.Divider,
                )
                val width = if (paperMm == 80) PaperWidth.MM80 else PaperWidth.MM58
                val bitmap = withContext(Dispatchers.Default) { ReceiptRenderer(context).render(rows, width) }
                container.printer.print(address, bitmap)
                message = context.getString(R.string.printer_test_done)
            } catch (e: PrintError) {
                message = printErrorMessage(context, e)
            } finally {
                printing = false
            }
        }
    }

    /** Android 12+ needs BLUETOOTH_CONNECT before paired devices can even be listed. */
    fun needsPermission() = !container.printer.hasPermission()
}

fun printErrorMessage(context: android.content.Context, e: PrintError): String = context.getString(
    when (e) {
        is PrintError.PermissionDenied -> R.string.print_err_permission
        is PrintError.BluetoothOff -> R.string.print_err_bt_off
        is PrintError.NotPaired -> R.string.print_err_not_paired
        is PrintError.ConnectionFailed -> R.string.print_err_connect
    }
)

@Composable
fun PrinterScreen(vm: PrinterViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val ink2 = LocalDukaanColors.current.ink2
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.load(context) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vm.needsPermission()) {
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            vm.load(context)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.printer_title), navigation = {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.printer_pair_hint), style = MaterialTheme.typography.bodyMedium, color = ink2)
            if (vm.devices.isEmpty()) {
                Text(stringResource(R.string.printer_none), style = MaterialTheme.typography.bodyMedium)
            }
            vm.devices.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { vm.select(d) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = vm.selected == d.address, onClick = { vm.select(d) })
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall)
                        Text(d.address, style = MaterialTheme.typography.bodySmall, color = ink2)
                    }
                }
                RowDivider()
            }
            OutlinedButton(onClick = { vm.load(context) }) { ButtonLabel(R.drawable.ic_history, stringResource(R.string.printer_refresh)) }

            Text(stringResource(R.string.printer_paper), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(vm.paperMm == 58, { vm.setPaper(58) }, { Text("58 mm") })
                FilterChip(vm.paperMm == 80, { vm.setPaper(80) }, { Text("80 mm") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.printer_auto), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.printer_auto_hint), style = MaterialTheme.typography.bodySmall, color = ink2)
                }
                Switch(checked = vm.autoPrint, onCheckedChange = vm::setAuto)
            }
            vm.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        Button(
            onClick = { vm.testPrint(context) },
            enabled = vm.selected != null && !vm.printing,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) { ButtonLabel(R.drawable.ic_print, stringResource(R.string.printer_test)) }
    }
}
