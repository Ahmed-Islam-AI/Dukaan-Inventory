package app.dukaan.android.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.account.AccountError
import app.dukaan.android.core.account.PhoneNumbers
import app.dukaan.android.core.ui.Avatar
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.ui.Panel
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.formatDateTime
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.feature.auth.message
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

/** More → Account (M7): who is signed in, which shop, cloud copy status, log out. */
@Composable
fun AccountScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalDukaanColors.current
    val user = remember { FirebaseAuth.getInstance().currentUser }
    // Phone accounts log in with a hidden email: show the number instead.
    val login = remember {
        user?.email?.let { e -> if (PhoneNumbers.isHiddenEmail(e)) "+" + e.substringBefore('@') else e }.orEmpty()
    }
    var shopName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { shopName = container.database.shopDao().get()?.name.orEmpty() }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.account_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Avatar(user?.displayName ?: shopName, size = 52.dp)
                    Column(Modifier.weight(1f)) {
                        Text(user?.displayName ?: shopName, style = MaterialTheme.typography.titleMedium)
                        Text(login, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                        Text(
                            stringResource(if (container.account.isOwner) R.string.account_role_owner else R.string.account_role_staff, shopName),
                            style = MaterialTheme.typography.bodySmall, color = c.ink2,
                        )
                    }
                }
            }
            // Live sync: what hasn't reached the cloud yet (phase 3).
            val waiting by container.sync.waiting.collectAsStateWithLifecycle(0)
            val needsUpdate by container.sync.needsUpdate.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_sync), contentDescription = null, tint = if (waiting == 0 && !needsUpdate) c.profit else c.warning)
                Text(
                    when {
                        needsUpdate -> stringResource(R.string.account_sync_update)
                        waiting == 0 -> stringResource(R.string.account_sync_ok)
                        else -> stringResource(R.string.account_sync_waiting, waiting)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // The shop's copy (new phones start from it) is the owner's to keep fresh.
            if (container.account.isOwner) {
                val uploaded = container.prefs.cloudUploadedAt
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_backup), contentDescription = null, tint = if (uploaded > 0) c.profit else c.warning)
                    Text(
                        if (uploaded > 0) stringResource(R.string.account_cloud_at, formatDateTime(uploaded)) else stringResource(R.string.account_cloud_never),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { confirming = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else ButtonLabel(R.drawable.ic_back, stringResource(R.string.account_logout))
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.account_logout_title)) },
            text = { Text(stringResource(R.string.account_logout_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            container.account.logOut() // restarts the app on success
                        } catch (e: AccountError) {
                            error = e.message(context)
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.account_logout), color = LocalDukaanColors.current.loss) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
