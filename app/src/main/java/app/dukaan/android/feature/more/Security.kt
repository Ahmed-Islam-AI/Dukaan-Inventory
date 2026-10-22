package app.dukaan.android.feature.more

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.dukaan.android.AppContainer
import app.dukaan.android.MainActivity
import app.dukaan.android.R
import app.dukaan.android.core.ui.LocalNotify
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.lock.ConfirmPinDialog
import app.dukaan.android.core.lock.Pin
import app.dukaan.android.core.lock.PinField
import app.dukaan.android.core.lock.deviceUnlockAvailable
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.theme.LocalDukaanColors

/** More → App lock: set/change/remove the PIN, fingerprint, how soon it locks again. */
@Composable
fun AppLockScreen(container: AppContainer, onBack: () -> Unit) {
    val notify = LocalNotify.current
    val context = LocalContext.current
    val lock = container.lock
    val prefs = container.prefs
    var enabled by remember { mutableStateOf(lock.enabled) }
    var deviceUnlock by remember { mutableStateOf(prefs.deviceUnlock) }
    var lockAfter by remember { mutableIntStateOf(prefs.lockAfterMinutes) }
    var dialog by remember { mutableStateOf<LockDialog?>(null) }
    val canDevice = remember { deviceUnlockAvailable(context) }
    val pinSet = stringResource(R.string.msg_pin_set)
    val lockOff = stringResource(R.string.msg_lock_off)
    val ink2 = LocalDukaanColors.current.ink2
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.lock_settings_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.lock_settings_body), style = MaterialTheme.typography.bodyMedium, color = ink2)
            if (!enabled) {
                Button(onClick = { dialog = LockDialog.SET }, Modifier.fillMaxWidth().height(48.dp)) { ButtonLabel(R.drawable.ic_lock, stringResource(R.string.lock_set_pin)) }
            } else {
                if (canDevice) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(deviceUnlock, role = Role.Switch) { deviceUnlock = it; prefs.deviceUnlock = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.lock_device_switch), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Switch(checked = deviceUnlock, onCheckedChange = null)
                    }
                }
                Text(stringResource(R.string.lock_after), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 5, 15).forEach { m ->
                        FilterChip(lockAfter == m, { lockAfter = m; prefs.lockAfterMinutes = m }, {
                            Text(if (m == 0) stringResource(R.string.lock_after_now) else pluralStringResource(R.plurals.lock_after_minutes, m, m))
                        })
                    }
                }
                OutlinedButton(onClick = { dialog = LockDialog.CONFIRM_CHANGE }, Modifier.fillMaxWidth().height(48.dp)) { ButtonLabel(R.drawable.ic_edit, stringResource(R.string.lock_change_pin)) }
                OutlinedButton(onClick = { dialog = LockDialog.CONFIRM_OFF }, Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.lock_turn_off), color = LocalDukaanColors.current.loss)
                }
            }
            Text(stringResource(if (canDevice) R.string.lock_forgot_note else R.string.lock_forgot_note_no_device), style = MaterialTheme.typography.bodySmall, color = ink2)
        }
    }
    when (dialog) {
        LockDialog.SET -> SetPinDialog(onDismiss = { dialog = null }) { pin -> lock.setPin(pin); enabled = true; dialog = null; notify(pinSet) }
        LockDialog.CONFIRM_CHANGE -> ConfirmPinDialog(lock, stringResource(R.string.lock_change_pin), onDismiss = { dialog = null }) { dialog = LockDialog.SET }
        LockDialog.CONFIRM_OFF -> ConfirmPinDialog(lock, stringResource(R.string.lock_turn_off), onDismiss = { dialog = null }) {
            lock.removePin(); enabled = false; dialog = null; notify(lockOff)
        }
        null -> Unit
    }
}

private enum class LockDialog { SET, CONFIRM_CHANGE, CONFIRM_OFF }

@Composable
private fun SetPinDialog(onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val short = stringResource(R.string.lock_err_length, Pin.MIN_LENGTH, Pin.MAX_LENGTH)
    val mismatch = stringResource(R.string.lock_err_mismatch)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ButtonLabel(R.drawable.ic_lock, stringResource(R.string.lock_set_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(pin, stringResource(R.string.lock_new_pin)) { pin = it }
                PinField(again, stringResource(R.string.lock_repeat_pin)) { again = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    !Pin.isValid(pin) -> short
                    pin != again -> mismatch
                    else -> null
                }
                if (error == null) onSet(pin)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "" = phone's language. Urdu names are shown in Urdu so a non-English reader can find them. */
private val languages = listOf("" to R.string.language_phone, "en" to R.string.language_english, "ur" to R.string.language_urdu)

@Composable
fun LanguageDialog(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = container.prefs.language
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_title)) },
        text = {
            Column {
                languages.forEach { (tag, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(tag == current, role = Role.RadioButton) {
                            if (tag != current) switchLanguage(context, container, tag) else onDismiss()
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tag == current, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Saves the choice and restarts so every screen and the app context switch together. */
private fun switchLanguage(context: Context, container: AppContainer, tag: String) {
    container.prefs.language = tag
    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    Runtime.getRuntime().exit(0)
}
