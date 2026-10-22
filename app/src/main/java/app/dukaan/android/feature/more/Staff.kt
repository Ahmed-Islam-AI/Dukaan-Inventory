package app.dukaan.android.feature.more

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.account.AccountError
import app.dukaan.android.core.account.Invite
import app.dukaan.android.core.account.Member
import app.dukaan.android.core.ui.Avatar
import app.dukaan.android.core.ui.CardRow
import app.dukaan.android.core.ui.EmptyState
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.LocalNotify
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.SectionLabel
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.feature.auth.message
import kotlinx.coroutines.launch

/** Owner's staff list and invites (M7 phase 2). Always read from the server: it's who may use the shop. */
class StaffViewModel(private val container: AppContainer) : ViewModel() {
    private val repo get() = container.account.repo
    private val shopId get() = container.prefs.accountShopId.orEmpty()

    var members by mutableStateOf<List<Member>?>(null)
        private set
    var invites by mutableStateOf<List<Invite>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    fun load(context: android.content.Context) = work(context) {
        members = repo.members(shopId)
        invites = repo.invites(shopId)
    }

    fun invite(context: android.content.Context, phoneOrEmail: String, name: String, onDone: (String?) -> Unit) {
        if (name.isBlank() || repo.inviteKey(phoneOrEmail) == null) {
            onDone(context.getString(R.string.staff_err_fields))
            return
        }
        viewModelScope.launch {
            try {
                val shopName = container.database.shopDao().get()?.name.orEmpty()
                repo.invite(shopId, shopName, phoneOrEmail, name)
                invites = repo.invites(shopId)
                onDone(null)
            } catch (e: AccountError) {
                onDone(if (e is AccountError.AlreadyUsed) context.getString(R.string.staff_err_invited) else e.message(context))
            }
        }
    }

    fun remove(context: android.content.Context, uid: String) = work(context) {
        repo.removeMember(uid)
        members = repo.members(shopId)
    }

    fun cancel(context: android.content.Context, key: String) = work(context) {
        repo.cancelInvite(key)
        invites = repo.invites(shopId)
    }

    private fun work(context: android.content.Context, block: suspend () -> Unit) {
        busy = true
        error = null
        viewModelScope.launch {
            try { block() } catch (e: AccountError) { error = e.message(context) } finally { busy = false }
        }
    }
}

@Composable
fun StaffScreen(vm: StaffViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val notify = LocalNotify.current
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Member?>(null) }
    val ink2 = LocalDukaanColors.current.ink2
    LaunchedEffect(Unit) { vm.load(context) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.staff_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
            vm.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            val members = vm.members
            if (members == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                items(members, key = { it.uid }) { m ->
                    CardRow(null) {
                        Avatar(m.name)
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall)
                            Text((m.phone?.let { "+$it" } ?: m.email).orEmpty(), style = MaterialTheme.typography.bodySmall, color = ink2)
                        }
                        if (m.role == "owner") Text(stringResource(R.string.account_owner), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        else TextButton(onClick = { removing = m }, enabled = !vm.busy) { Text(stringResource(R.string.staff_remove), color = LocalDukaanColors.current.loss) }
                    }
                }
                if (vm.invites.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.staff_pending), Modifier.padding(start = 0.dp)) }
                    items(vm.invites, key = { it.key }) { inv ->
                        CardRow(null) {
                            IconBadge(R.drawable.ic_person_add, Tone.WARNING)
                            Column(Modifier.weight(1f)) {
                                Text(inv.name, style = MaterialTheme.typography.titleSmall)
                                Text(if (inv.key.contains('@')) inv.key else "+" + inv.key, style = MaterialTheme.typography.bodySmall, color = ink2)
                            }
                            TextButton(onClick = { vm.cancel(context, inv.key) }, enabled = !vm.busy) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
                if (members.size <= 1 && vm.invites.isEmpty()) {
                    item { EmptyState(stringResource(R.string.staff_empty_title), stringResource(R.string.staff_empty_body), null, {}, icon = R.drawable.ic_person_add) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(painterResource(R.drawable.ic_person_add), contentDescription = null) },
            text = { Text(stringResource(R.string.staff_add)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (adding) {
        val invited = stringResource(R.string.staff_invited)
        AddStaffDialog(onDismiss = { adding = false }) { who, name, done ->
            vm.invite(context, who, name) { msg -> if (msg == null) notify(invited); done(msg) }
        }
    }
    removing?.let { m ->
        val removed = stringResource(R.string.msg_deleted)
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.staff_remove_title, m.name)) },
            text = { Text(stringResource(R.string.staff_remove_body)) },
            confirmButton = {
                TextButton(onClick = { vm.remove(context, m.uid); removing = null; notify(removed) }) {
                    Text(stringResource(R.string.staff_remove), color = LocalDukaanColors.current.loss)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AddStaffDialog(onDismiss: () -> Unit, onSave: (String, String, (String?) -> Unit) -> Unit) {
    var who by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.staff_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text(stringResource(R.string.party_name)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                OutlinedTextField(who, { who = it }, label = { Text(stringResource(R.string.staff_phone_or_email)) }, singleLine = true)
                Text(stringResource(R.string.staff_add_hint), style = MaterialTheme.typography.bodySmall, color = LocalDukaanColors.current.ink2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(who, name) { msg -> if (msg == null) onDismiss() else error = msg } }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
