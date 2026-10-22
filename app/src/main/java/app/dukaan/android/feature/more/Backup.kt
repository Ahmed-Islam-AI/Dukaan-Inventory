package app.dukaan.android.feature.more

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.MainActivity
import app.dukaan.android.R
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.backup.ImportError
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.formatDateTime
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupViewModel(private val container: AppContainer) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    /** A validated backup waiting for the user's confirmation. */
    var pending by mutableStateOf<File?>(null)
        private set
    var lastSnapshot by mutableStateOf(container.backup.snapshotFile.takeIf { it.exists() }?.lastModified())
        private set

    val suggestedName get() = "dukaan-backup-${LocalDate.now()}.dukaan"

    fun export(context: Context, uri: Uri) = work {
        try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { container.backup.export(it) } ?: throw IOException("no stream")
            }
            lastSnapshot = container.backup.snapshotFile.lastModified()
            container.prefs.lastExportAt = System.currentTimeMillis()
            message = context.getString(R.string.backup_exported)
        } catch (e: IOException) {
            message = context.getString(R.string.backup_export_failed)
        }
    }

    fun check(context: Context, uri: Uri) = work {
        try {
            pending = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use {
                    container.backup.validate(it, File(context.cacheDir, "import"))
                } ?: throw IOException("no stream")
            }
        } catch (e: ImportError.NewerVersion) {
            message = context.getString(R.string.import_newer)
        } catch (e: ImportError.Damaged) {
            message = context.getString(R.string.import_damaged)
        } catch (e: ImportError.NotABackup) {
            message = context.getString(R.string.import_not_backup)
        } catch (e: IOException) {
            message = context.getString(R.string.import_not_backup)
        }
    }

    fun cancelImport() {
        pending = null
    }

    /** Swaps the database and restarts the app so every screen opens the restored data. */
    fun confirmImport(context: Context) {
        val file = pending ?: return
        container.replaceDatabase(file)
        val restart = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(restart)
        Runtime.getRuntime().exit(0)
    }

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() } finally { busy = false }
        }
    }
}

@Composable
fun BackupScreen(vm: BackupViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val ink2 = LocalDukaanColors.current.ink2
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.export(context, uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.check(context, uri)
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.backup_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.backup_auto_title), style = MaterialTheme.typography.titleSmall)
            Text(
                vm.lastSnapshot?.let { stringResource(R.string.backup_auto_last, formatDateTime(it)) } ?: stringResource(R.string.backup_auto_none),
                style = MaterialTheme.typography.bodyMedium, color = ink2,
            )
            Text(stringResource(R.string.backup_export_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.backup_export_body), style = MaterialTheme.typography.bodyMedium, color = ink2)
            Button(onClick = { exporter.launch(vm.suggestedName) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                ButtonLabel(R.drawable.ic_backup, stringResource(R.string.backup_export))
            }
            Text(stringResource(R.string.backup_import_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.backup_import_body), style = MaterialTheme.typography.bodyMedium, color = ink2)
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                ButtonLabel(R.drawable.ic_download, stringResource(R.string.backup_import))
            }
            vm.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (vm.pending != null) {
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = { Text(stringResource(R.string.import_confirm_body)) },
            confirmButton = { TextButton(onClick = { vm.confirmImport(context) }) { Text(stringResource(R.string.import_confirm)) } },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
