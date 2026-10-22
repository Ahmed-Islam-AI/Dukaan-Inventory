package app.dukaan.android.feature.auth

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.credentials.exceptions.NoCredentialException
import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.account.AccountError
import app.dukaan.android.core.account.AccountState
import app.dukaan.android.core.account.Currencies
import app.dukaan.android.core.account.Invite
import app.dukaan.android.core.account.ShopInfo
import app.dukaan.android.core.account.currencyOf
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.ui.ChoiceTile
import app.dukaan.android.core.ui.FormCard
import app.dukaan.android.core.ui.IconBadge
import app.dukaan.android.core.ui.Motion
import app.dukaan.android.core.ui.Tone
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.feature.onboarding.CurrencyPicker
import app.dukaan.android.feature.onboarding.ShopType
import app.dukaan.android.feature.onboarding.ShopTypePicker
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

enum class AuthStep { WELCOME, LOGIN, SIGN_UP, SHOP, JOIN }
enum class AuthMethod { PHONE, EMAIL }

/** Sign up / log in (M7 phase 1, docs/ACCOUNTS.md). One VM for the whole flow so fields survive going back. */
class AuthViewModel(private val container: AppContainer) : ViewModel() {
    private val account get() = container.account
    private val repo get() = account.repo

    var step by mutableStateOf(AuthStep.WELCOME)
    var method by mutableStateOf(AuthMethod.PHONE)
    var phone by mutableStateOf("")
    var email by mutableStateOf("")
    /** Phone sign-up only: optional real email for password resets. */
    var resetEmail by mutableStateOf("")
    var password by mutableStateOf("")
    var ownerName by mutableStateOf("")
    var ownerPhone by mutableStateOf("")
    var shopName by mutableStateOf("")
    var shopType by mutableStateOf(ShopType.MOBILES)
    var shopAddress by mutableStateOf("")
    var shopPhone by mutableStateOf("")
    var currency by mutableStateOf(Currencies.first())
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    /** A staff invite addressed to this person (phase 2): offered before creating a shop. */
    var invite by mutableStateOf<Invite?>(null)
        private set

    /**
     * This phone still holds a shop from before accounts (or a signed-out session). A new account
     * starts empty and removes it (D-82); Welcome says so.
     */
    var hasLocalShop by mutableStateOf(false)
        private set
    var localShopName by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            container.database.shopDao().get()?.let { p ->
                hasLocalShop = true
                localShopName = p.name
            }
        }
        if (account.state.value == AccountState.NEEDS_SHOP) {
            step = AuthStep.SHOP
            viewModelScope.launch { offerInvite() }
        }
    }

    /** Signed in without a shop: an invite for this person turns sign-up into "join as staff". */
    private suspend fun offerInvite() {
        val found = try { repo.findInvite(phone.ifBlank { null }) } catch (e: AccountError) { null } ?: return
        invite = found
        if (ownerName.isBlank()) ownerName = found.name
        step = AuthStep.JOIN
    }

    fun go(to: AuthStep) {
        error = null
        info = null
        step = to
    }

    private fun run(context: Context, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        info = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: AccountError) {
                error = e.message(context)
            } finally {
                busy = false
            }
        }
    }

    /** After auth: an existing account links this phone; a new one goes on to shop details. */
    private suspend fun afterAuth() {
        account.afterSignIn()
        if (account.state.value == AccountState.NEEDS_SHOP) {
            if (ownerName.isBlank()) ownerName = repo.currentName.orEmpty()
            if (ownerPhone.isBlank() && method == AuthMethod.PHONE) ownerPhone = phone
            step = AuthStep.SHOP
            offerInvite()
        }
    }

    fun logIn(context: Context) = run(context) {
        if (password.isEmpty()) throw AccountError.WrongPassword()
        if (method == AuthMethod.PHONE) repo.signInPhone(phone, password) else repo.signInEmail(email, password)
        afterAuth()
    }

    fun signUp(context: Context) = run(context) {
        if (password.length < 6) throw AccountError.WeakPassword()
        if (method == AuthMethod.PHONE) repo.signUpPhone(phone, password, resetEmail) else repo.signUpEmail(email, password)
        afterAuth()
    }

    fun forgotPassword(context: Context) = run(context) {
        repo.sendPasswordReset(if (method == AuthMethod.PHONE) phone else email)
        info = context.getString(R.string.auth_reset_sent)
    }

    fun google(context: Context) = run(context) {
        val clientId = webClientId(context) ?: throw AccountError.Other(IllegalStateException("Google sign-in is not set up"))
        val option = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false).setServerClientId(clientId).build()
        val credential = try {
            CredentialManager.create(context).getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
        } catch (e: GetCredentialCancellationException) {
            return@run
        } catch (e: NoCredentialException) {
            throw AccountError.NoGoogleAccount()
        } catch (e: GetCredentialException) {
            throw AccountError.Other(e)
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return@run
        repo.signInGoogle(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        afterAuth()
    }

    fun finish(context: Context) {
        if (ownerName.isBlank() || shopName.isBlank()) {
            error = context.getString(R.string.auth_err_names)
            return
        }
        run(context) { finishShop() }
    }

    private suspend fun finishShop() {
        account.finishSignUp(
            ownerName.trim(), ownerPhone,
            ShopInfo(shopName.trim(), shopType.key, shopAddress.trim().ifEmpty { null }, shopPhone.trim().ifEmpty { null }, currency.code),
            prefix = "A",
        )
    }

    fun join(context: Context) {
        val inv = invite ?: return
        if (ownerName.isBlank()) {
            error = context.getString(R.string.auth_err_names)
            return
        }
        run(context) { account.joinShop(inv, ownerName.trim(), ownerPhone.ifBlank { null }) }
    }

    /** "Create my own shop instead": ignore the invite (it stays until the owner cancels it). */
    fun declineInvite() = go(AuthStep.SHOP)

    /** Started over (e.g. back from shop details): sign the half-made account out. */
    fun cancelSignUp() {
        repo.signOut()
        go(AuthStep.WELCOME)
    }
}

/** The Google button only shows when google-services.json has a web OAuth client (Google enabled + SHA-1). */
@SuppressLint("DiscouragedApi")
fun webClientId(context: Context): String? {
    val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    return if (id == 0) null else context.getString(id)
}

fun AccountError.message(context: Context): String = context.getString(
    when (this) {
        is AccountError.Offline -> R.string.auth_err_offline
        is AccountError.WrongPassword -> R.string.auth_err_password
        is AccountError.NoAccount -> R.string.auth_err_no_account
        is AccountError.AlreadyUsed -> R.string.auth_err_used
        is AccountError.WeakPassword -> R.string.auth_err_weak
        is AccountError.BadPhone -> R.string.auth_err_phone
        is AccountError.BadEmail -> R.string.auth_err_email
        is AccountError.Blocked -> R.string.auth_blocked_title
        is AccountError.NoResetEmail -> R.string.auth_err_no_reset
        is AccountError.NoGoogleAccount -> R.string.auth_err_no_google
        is AccountError.Other -> R.string.auth_err_other
    }
)

@Composable
fun AuthScreen(vm: AuthViewModel) {
    AnimatedContent(
        vm.step,
        transitionSpec = { (fadeIn(tween(Motion.MEDIUM)) + slideInVertically(tween(Motion.MEDIUM)) { it / 16 }) togetherWith fadeOut(tween(Motion.SHORT)) },
        label = "auth",
    ) { step ->
        when (step) {
            AuthStep.WELCOME -> Landing(
                localShopName = vm.localShopName.takeIf { vm.hasLocalShop },
                onGetStarted = { vm.go(AuthStep.SIGN_UP) },
                onLogIn = { vm.go(AuthStep.LOGIN) },
            )
            AuthStep.LOGIN -> Credentials(vm, signUp = false)
            AuthStep.SIGN_UP -> Credentials(vm, signUp = true)
            AuthStep.SHOP -> ShopDetails(vm)
            AuthStep.JOIN -> JoinShop(vm)
        }
    }
}

/**
 * Log in / sign up (D-84): saffron header with the title, then the form on a white sheet:
 * sliding Phone / Email switch, fields, the main button, "or", Google, and the other way in.
 */
@Composable
private fun Credentials(vm: AuthViewModel, signUp: Boolean) {
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }
    val field = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            AuthHeader(
                title = stringResource(if (signUp) R.string.auth_signup_title else R.string.auth_login_title),
                subtitle = stringResource(if (signUp) R.string.auth_signup_sub else R.string.auth_login_sub),
                onBack = { vm.go(AuthStep.WELCOME) },
                step = if (signUp) stringResource(R.string.auth_step, 1, 2) else null,
            )
            AuthSheet {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MethodSwitch(vm.method) { vm.method = it }
                    AnimatedContent(vm.method, transitionSpec = { fadeIn(tween(Motion.MEDIUM)) togetherWith fadeOut(tween(Motion.SHORT)) }, label = "method") { m ->
                        if (m == AuthMethod.PHONE) {
                            OutlinedTextField(
                                vm.phone, { vm.phone = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.auth_phone)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_call), contentDescription = null) }, singleLine = true, shape = field,
                                placeholder = { Text("0300 1234567") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                            )
                        } else {
                            OutlinedTextField(
                                vm.email, { vm.email = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.auth_email)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_message), contentDescription = null) }, singleLine = true, shape = field,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            )
                        }
                    }
                    OutlinedTextField(
                        vm.password, { vm.password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.auth_password)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) }, singleLine = true, shape = field,
                        supportingText = if (signUp) ({ Text(stringResource(R.string.auth_password_hint)) }) else null,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(painterResource(if (showPassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility), stringResource(if (showPassword) R.string.auth_hide_password else R.string.auth_show_password))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    )
                    if (signUp && vm.method == AuthMethod.PHONE) {
                        OutlinedTextField(
                            vm.resetEmail, { vm.resetEmail = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.auth_reset_email)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_message), contentDescription = null) }, shape = field,
                            supportingText = { Text(stringResource(R.string.auth_reset_email_hint)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        )
                    }
                    if (!signUp) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            TextButton(onClick = { vm.forgotPassword(context) }, enabled = !vm.busy) { Text(stringResource(R.string.auth_forgot)) }
                        }
                    }
                    Messages(vm)
                    Button(onClick = { if (signUp) vm.signUp(context) else vm.logIn(context) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        else ButtonLabel(if (signUp) R.drawable.ic_person_add else R.drawable.ic_check_circle, stringResource(if (signUp) R.string.auth_continue else R.string.auth_login))
                    }
                    if (webClientId(context) != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HorizontalDivider(Modifier.weight(1f), color = LocalDukaanColors.current.line)
                            Text(stringResource(R.string.auth_or), Modifier.padding(horizontal = 12.dp), color = LocalDukaanColors.current.ink2, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider(Modifier.weight(1f), color = LocalDukaanColors.current.line)
                        }
                        OutlinedButton(onClick = { vm.google(context) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text("G", style = MaterialTheme.typography.titleLarge, color = Color(0xFF4285F4))
                            Text(stringResource(R.string.auth_google), Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
        // The other way in, always visible above the keyboard.
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (signUp) R.string.auth_have_account_q else R.string.auth_new_here_q), color = LocalDukaanColors.current.ink2, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { vm.go(if (signUp) AuthStep.LOGIN else AuthStep.SIGN_UP) }) {
                Text(stringResource(if (signUp) R.string.auth_login else R.string.auth_create), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ShopDetails(vm: AuthViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        AuthHeader(stringResource(R.string.auth_shop_title), stringResource(R.string.auth_shop_sub), onBack = vm::cancelSignUp, step = stringResource(R.string.auth_step, 2, 2))
        AuthSheet {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FormCard(stringResource(R.string.auth_about_you)) {
                OutlinedTextField(
                    vm.ownerName, { vm.ownerName = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.auth_your_name)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_person), contentDescription = null) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    vm.ownerPhone, { vm.ownerPhone = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.auth_your_phone)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_call), contentDescription = null) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                )
            }
            FormCard(stringResource(R.string.form_your_shop)) {
                OutlinedTextField(
                    vm.shopName, { vm.shopName = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.shop_name)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_store), contentDescription = null) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                Text(stringResource(R.string.shop_type_question), style = MaterialTheme.typography.titleSmall)
                ShopTypePicker(vm.shopType) { vm.shopType = it }
                OutlinedTextField(vm.shopAddress, { vm.shopAddress = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.shop_address)) })
                OutlinedTextField(
                    vm.shopPhone, { vm.shopPhone = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.shop_phone)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                Text(stringResource(R.string.shop_currency), style = MaterialTheme.typography.titleSmall)
                CurrencyPicker(vm.currency) { vm.currency = it }
            }
            Messages(vm)
        }
        }
        }
        Button(onClick = { vm.finish(context) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp)) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else ButtonLabel(R.drawable.ic_store, stringResource(R.string.auth_create_shop))
        }
    }
}

/** Invited as staff: join that shop, or create your own instead. */
@Composable
private fun JoinShop(vm: AuthViewModel) {
    val context = LocalContext.current
    val invite = vm.invite ?: return
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        AuthHeader(stringResource(R.string.join_question, invite.shopName), stringResource(R.string.join_body), onBack = vm::cancelSignUp)
        AuthSheet {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FormCard(stringResource(R.string.auth_about_you)) {
                OutlinedTextField(
                    vm.ownerName, { vm.ownerName = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.auth_your_name)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_person), contentDescription = null) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    vm.ownerPhone, { vm.ownerPhone = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.auth_your_phone)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_call), contentDescription = null) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
            Messages(vm)
        }
        }
        }
        Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.join(context) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else ButtonLabel(R.drawable.ic_check_circle, stringResource(R.string.join_accept))
            }
            TextButton(onClick = vm::declineInvite, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.join_own_shop)) }
        }
    }
}

@Composable
private fun Messages(vm: AuthViewModel) {
    vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    vm.info?.let { Text(it, color = LocalDukaanColors.current.profit, style = MaterialTheme.typography.bodyMedium) }
}

/** Full-screen status page: big icon, title, one line, one action. Blocked / connect / checking. */
@Composable
fun AccountStatusScreen(icon: Int, tone: Tone, title: String, body: String?, action: String?, busy: Boolean = false, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        IconBadge(icon, tone, size = 88.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        body?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = LocalDukaanColors.current.ink2, textAlign = TextAlign.Center) }
        if (busy) CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        if (action != null && !busy) Button(onClick = onAction, Modifier.fillMaxWidth().height(56.dp)) { Text(action) }
    }
}
