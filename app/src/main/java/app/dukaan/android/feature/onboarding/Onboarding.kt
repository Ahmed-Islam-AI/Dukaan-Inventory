package app.dukaan.android.feature.onboarding

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.account.Currencies
import app.dukaan.android.core.account.Currency
import app.dukaan.android.core.account.ShopInfo
import app.dukaan.android.core.account.insertLocalShop
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import app.dukaan.android.core.ui.ChoiceTile
import app.dukaan.android.core.ui.FormCard
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.db.ShopProfileEntity
import app.dukaan.android.core.db.SyncMeta
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch

/** Shop types preset defaults (DESIGN.md § Onboarding): Mobiles defaults new items to IMEI tracking. */
enum class ShopType(val key: String, val label: Int, val icon: Int) {
    MOBILES("mobiles", R.string.shop_mobiles, R.drawable.ic_phone_android),
    GROCERY("grocery", R.string.shop_grocery, R.drawable.ic_cart),
    CLOTHES("clothes", R.string.shop_clothes, R.drawable.ic_clothes),
    PHARMACY("pharmacy", R.string.shop_pharmacy, R.drawable.ic_pharmacy),
    OTHER("other", R.string.shop_other, R.drawable.ic_store),
}

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {
    var name by mutableStateOf("")
    var type by mutableStateOf(ShopType.MOBILES)
    var phone by mutableStateOf("")
    var address by mutableStateOf("")
    var currency by mutableStateOf(Currencies.first())
    var prefix by mutableStateOf("A")
    var saving by mutableStateOf(false)
        private set
    var nameError by mutableStateOf(false)
        private set

    fun save(onDone: () -> Unit) {
        if (name.isBlank()) {
            nameError = true
            return
        }
        if (saving) return
        saving = true
        viewModelScope.launch {
            insertLocalShop(container, ShopInfo(name, type.key, address, phone, currency.code), prefix)
            onDone()
        }
    }

    /** Receipt preview of the prefix the owner is typing: "A-0001". */
    val prefixPreview get() = "${prefix.trim().uppercase().ifEmpty { "A" }}-0001"
}

@Composable
fun OnboardingScreen(vm: OnboardingViewModel, onDone: () -> Unit) {
    val c = LocalDukaanColors.current
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Welcome: the app's mark, one title, one line.
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_store), contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyMedium, color = c.ink2, textAlign = TextAlign.Center)
            }

            FormCard(stringResource(R.string.form_your_shop)) {
                OutlinedTextField(
                    value = vm.name,
                    onValueChange = { vm.name = it },
                    label = { Text(stringResource(R.string.shop_name)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_store), contentDescription = null) },
                    isError = vm.nameError && vm.name.isBlank(),
                    supportingText = if (vm.nameError && vm.name.isBlank()) ({ Text(stringResource(R.string.err_shop_name)) }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.shop_type_question), style = MaterialTheme.typography.titleSmall)
                ShopTypePicker(vm.type) { vm.type = it }
            }

            FormCard(stringResource(R.string.form_optional)) {
                OutlinedTextField(
                    value = vm.phone, onValueChange = { vm.phone = it },
                    label = { Text(stringResource(R.string.shop_phone)) }, singleLine = true,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_call), contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.address, onValueChange = { vm.address = it },
                    label = { Text(stringResource(R.string.shop_address)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.shop_currency), style = MaterialTheme.typography.titleSmall)
                CurrencyPicker(vm.currency) { vm.currency = it }
                OutlinedTextField(
                    value = vm.prefix,
                    onValueChange = { if (it.length <= 3) vm.prefix = it },
                    label = { Text(stringResource(R.string.shop_prefix)) },
                    supportingText = { Text(stringResource(R.string.shop_prefix_hint, vm.prefixPreview)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Button(
            onClick = { vm.save(onDone) },
            enabled = vm.name.isNotBlank() && !vm.saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) { ButtonLabel(R.drawable.ic_store, stringResource(R.string.onboarding_start)) }
    }
}

/** "What do you sell?" as picture tiles, 3 per row (onboarding and account sign-up). */
@Composable
fun ShopTypePicker(selected: ShopType, onSelect: (ShopType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopType.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t -> ChoiceTile(t.icon, stringResource(t.label), selected == t, Modifier.weight(1f).fillMaxHeight()) { onSelect(t) } }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun CurrencyPicker(selected: Currency, onSelect: (Currency) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Currencies.forEach { cur -> FilterChip(selected = selected == cur, onClick = { onSelect(cur) }, label = { Text("${cur.code} ${cur.symbol}") }) }
    }
}
