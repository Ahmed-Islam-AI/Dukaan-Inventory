package app.dukaan.android.core.lock

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.dukaan.android.R
import app.dukaan.android.core.money.normalizeNumberInput
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import kotlinx.coroutines.delay

/** Fingerprint, face or the phone's own screen lock (PIN/pattern), whichever the phone has. */
fun deviceUnlockAvailable(context: Context) =
    BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

fun promptDeviceUnlock(activity: FragmentActivity, title: String, onSuccess: () -> Unit) {
    val prompt = BiometricPrompt(
        activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        },
    )
    prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build())
}

/** Full-screen lock drawn over the whole app while [AppLock.locked]. */
@Composable
fun LockScreen(lock: AppLock, deviceUnlock: Boolean, shopName: String) {
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    val canDevice = remember { deviceUnlockAvailable(context) }
    val title = stringResource(R.string.lock_title)
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var waitUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var forgot by remember { mutableStateOf(false) }
    val c = LocalDukaanColors.current

    fun device() { activity?.let { promptDeviceUnlock(it, title) { lock.unlockedByDevice() } } }
    // Offer the fingerprint straight away; the PIN pad stays underneath.
    LaunchedEffect(Unit) { if (deviceUnlock && canDevice) device() }
    LaunchedEffect(waitUntil) {
        while (System.currentTimeMillis() < waitUntil) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }

    fun submit() {
        when (val r = lock.unlock(pin)) {
            Unlock.Ok -> Unit
            Unlock.Wrong -> { wrong = true; pin = "" }
            is Unlock.Wait -> { waitUntil = r.untilMillis; wrong = false; pin = "" }
        }
    }

    val waiting = now < waitUntil
    Column(
        // pointerInput: taps never reach the app drawn underneath.
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {}.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(shopName, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.lock_enter_pin), style = MaterialTheme.typography.bodyLarge, color = c.ink2)
        // Dots, one per digit typed.
        val dotsLabel = pluralStringResource(R.plurals.lock_digits_typed, pin.length, pin.length)
        Row(Modifier.height(24.dp).semantics { contentDescription = dotsLabel }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(maxOf(pin.length, Pin.MIN_LENGTH)) { i ->
                Box(Modifier.size(14.dp).background(if (i < pin.length) MaterialTheme.colorScheme.onSurface else c.line, CircleShape))
            }
        }
        when {
            waiting -> Text(((waitUntil - now) / 1000 + 1).toInt().let { pluralStringResource(R.plurals.lock_wait, it, it) }, color = c.loss, textAlign = TextAlign.Center)
            wrong -> Text(stringResource(R.string.lock_wrong), color = c.loss)
        }
        PinPad(
            enabled = !waiting,
            onDigit = { if (pin.length < Pin.MAX_LENGTH) { pin += it; wrong = false } },
            onBack = { pin = pin.dropLast(1) },
            onOk = { if (pin.length >= Pin.MIN_LENGTH) submit() },
        )
        if (canDevice) {
            if (deviceUnlock) OutlinedButton(onClick = ::device, Modifier.fillMaxWidth().widthIn(max = 320.dp).height(48.dp)) { Text(stringResource(R.string.lock_use_device)) }
            TextButton(onClick = ::device) { Text(stringResource(R.string.lock_forgot)) }
        } else {
            TextButton(onClick = { forgot = true }) { Text(stringResource(R.string.lock_forgot)) }
        }
    }
    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            title = { Text(stringResource(R.string.lock_forgot)) },
            text = { Text(stringResource(R.string.lock_forgot_no_device)) },
            confirmButton = { TextButton(onClick = { forgot = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onBack: () -> Unit, onOk: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { d -> Key(d.toString(), enabled) { onDigit(d) } } } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("⌫", enabled, stringResource(R.string.lock_delete), onBack)
            Key("0", enabled) { onDigit('0') }
            Key("✓", enabled, stringResource(R.string.lock_unlock), onOk)
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, spoken: String? = null, onClick: () -> Unit) {
    TextButton(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.size(width = 88.dp, height = 64.dp).let { m -> if (spoken != null) m.semantics { contentDescription = spoken } else m },
    ) { Text(label, style = MaterialTheme.typography.headlineSmall) }
}

/**
 * Asks for the current PIN before something sensitive (show cost, turn the lock off). Calls
 * [onOk] only on the right PIN; fingerprint / phone lock works here too.
 */
@Composable
fun ConfirmPinDialog(lock: AppLock, title: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.lock_wrong)
    val wait = stringResource(R.string.lock_wait_short)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(pin, stringResource(R.string.lock_enter_pin)) { pin = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (deviceUnlockAvailable(context)) {
                    TextButton(onClick = { context.findFragmentActivity()?.let { promptDeviceUnlock(it, title) { onOk() } } }) {
                        Text(stringResource(R.string.lock_use_device))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (lock.check(pin)) {
                    Unlock.Ok -> onOk()
                    Unlock.Wrong -> error = wrong
                    is Unlock.Wait -> error = wait
                }
                pin = ""
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun PinField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> onChange(normalizeNumberInput(v).filter { it in '0'..'9' }.take(Pin.MAX_LENGTH)) }, // Urdu keyboards type ۱۲۳
        label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}
