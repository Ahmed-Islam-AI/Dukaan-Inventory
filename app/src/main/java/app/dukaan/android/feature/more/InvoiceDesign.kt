package app.dukaan.android.feature.more

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dukaan.android.AppContainer
import app.dukaan.android.R
import app.dukaan.android.core.db.PageTemplate
import app.dukaan.android.core.db.ShopProfileEntity
import app.dukaan.android.core.db.SlipTemplate
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.receipt.InvoiceDesign
import app.dukaan.android.core.receipt.InvoicePage
import app.dukaan.android.core.receipt.InvoiceStyle
import app.dukaan.android.core.receipt.PaperWidth
import app.dukaan.android.core.receipt.ReceiptLine
import app.dukaan.android.core.receipt.ReceiptModel
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.ui.ChoiceTile
import app.dukaan.android.core.ui.FormCard
import app.dukaan.android.core.ui.LocalNotify
import app.dukaan.android.core.ui.ScreenHeader
import app.dukaan.android.core.ui.pressable
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.feature.sell.ReceiptView
import app.dukaan.android.feature.sell.slip
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owner: how invoices and slips look (D-80). Saved on the shop row, so every phone of the shop
 * prints the same; previewed live with a sample bill in the shop's own name and currency.
 */
class InvoiceDesignViewModel(private val container: AppContainer) : ViewModel() {
    private var shop: ShopProfileEntity? = null

    var page by mutableStateOf(PageTemplate.MODERN)
    var slipTemplate by mutableStateOf(SlipTemplate.CLASSIC)
    var accent by mutableStateOf(InvoiceDesign.DEFAULT_ACCENT)
    var logo by mutableStateOf<Bitmap?>(null)
        private set
    private var logoPng: String? = null
    var address by mutableStateOf("")
    var phone by mutableStateOf("")
    var taxNumber by mutableStateOf("")
    var terms by mutableStateOf("")
    var footer by mutableStateOf("")

    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var view by mutableStateOf(ReceiptView.INVOICE)
    var previewPage by mutableStateOf<Bitmap?>(null)
        private set
    var previewSlip by mutableStateOf<Bitmap?>(null)
        private set
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            shop = container.database.shopDao().get()
            shop?.let { s ->
                page = s.pageTemplate
                slipTemplate = s.slipTemplate
                accent = s.accentRgb
                logoPng = s.logoPng
                logo = s.logoPng?.let(InvoiceDesign::decodeLogo)
                address = s.address.orEmpty()
                phone = s.phone.orEmpty()
                taxNumber = s.taxNumber.orEmpty()
                terms = s.invoiceTerms.orEmpty()
                footer = s.receiptFooter.orEmpty()
            }
            loaded = true
            refresh(container.appContext, wait = false)
        }
    }

    /** Re-renders the preview; typing waits a moment so every key press doesn't redraw. */
    fun refresh(context: Context, wait: Boolean = true) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (wait) delay(350)
            val m = sample()
            val style = InvoiceStyle(page, slipTemplate, accent, logo)
            withContext(Dispatchers.Default) {
                previewPage = InvoicePage(context).bitmaps(m, style, scale = 1.2f).first()
                previewSlip = slip(context, m, style, PaperWidth.MM58)
            }
        }
    }

    /** A believable bill in this shop's own name, currency and wording. */
    private fun sample(): ReceiptModel {
        val s = shop
        val mobiles = s?.shopType == "mobiles"
        val lines = if (mobiles) listOf(
            ReceiptLine("Samsung A15 128GB", "1", Money(4_200_000), Money(4_200_000), "356789012345678"),
            ReceiptLine("Charger 25W", "2", Money(120_000), Money(240_000), null),
        ) else listOf(
            ReceiptLine("Item one", "2", Money(45_000), Money(90_000), null),
            ReceiptLine("Item two", "1.5 kg", Money(60_000), Money(90_000), null),
        )
        val total = Money(lines.sumOf { it.total.minor })
        return ReceiptModel(
            shopName = s?.name ?: "Dukaan", shopAddress = address, shopPhone = phone, footer = footer,
            number = "${container.prefs.invoicePrefix ?: "A"}-0001", dateTime = "15-10-2026 18:30", customer = "Ahmed Raza",
            lines = lines, subtotal = total, discount = Money.ZERO, taxRateBp = null, tax = Money.ZERO, total = total,
            paid = total, paidModeLabel = "Cash", voided = false, symbol = s?.currencySymbol ?: "Rs",
            lakhGrouping = (s?.currencyCode ?: "PKR") in Money.LAKH_CURRENCIES, taxNumber = taxNumber, terms = terms,
        )
    }

    /** Photo picker result → small PNG; a file we can't read leaves the old logo. */
    fun pickLogo(context: Context, uri: Uri) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    // Decode near the target size: a 12 MP photo must not fill the memory.
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                } catch (e: IOException) {
                    null
                }
            } ?: return@launch
            logoPng = withContext(Dispatchers.Default) { InvoiceDesign.encodeLogo(picked) }
            logo = logoPng?.let(InvoiceDesign::decodeLogo)
            refresh(context, wait = false)
        }
    }

    fun removeLogo(context: Context) {
        logoPng = null
        logo = null
        refresh(context, wait = false)
    }

    /** Saves on the shop row; updatedAt moves, so the design syncs to the shop's other phones. */
    fun save(onDone: () -> Unit) {
        val s = shop ?: return
        saving = true
        viewModelScope.launch {
            val updated = s.copy(
                pageTemplate = page, slipTemplate = slipTemplate, accentRgb = accent, logoPng = logoPng,
                address = address.trim().ifEmpty { null }, phone = phone.trim().ifEmpty { null },
                taxNumber = taxNumber.trim().ifEmpty { null }, invoiceTerms = terms.trim().ifEmpty { null },
                receiptFooter = footer.trim().ifEmpty { null },
                meta = s.meta.copy(updatedAt = System.currentTimeMillis()),
            )
            container.database.shopDao().update(updated)
            shop = updated
            saving = false
            onDone()
        }
    }
}

@Composable
fun InvoiceDesignScreen(vm: InvoiceDesignViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val notify = LocalNotify.current
    val c = LocalDukaanColors.current
    val saved = stringResource(R.string.invoice_saved)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.pickLogo(context, it) } }
    fun change(block: () -> Unit) { block(); vm.refresh(context) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.invoice_title), navigation = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
        if (!vm.loaded) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Live preview: the A4 invoice (what Share sends) or the printer slip.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReceiptView.entries.forEach { v ->
                    FilterChip(
                        selected = vm.view == v, onClick = { vm.view = v },
                        label = { Text(stringResource(if (v == ReceiptView.INVOICE) R.string.receipt_view_invoice else R.string.receipt_view_slip)) },
                        leadingIcon = { Icon(painterResource(if (v == ReceiptView.INVOICE) R.drawable.ic_receipt else R.drawable.ic_print), contentDescription = null) },
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sunk).padding(12.dp).heightIn(min = 200.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                val image = if (vm.view == ReceiptView.INVOICE) vm.previewPage else vm.previewSlip
                if (image == null) CircularProgressIndicator()
                else Image(
                    image.asImageBitmap(), contentDescription = stringResource(R.string.invoice_preview),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.widthIn(max = if (vm.view == ReceiptView.INVOICE) 480.dp else 260.dp).fillMaxWidth().shadow(2.dp),
                )
            }

            FormCard(stringResource(R.string.invoice_page_section)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageTemplate.entries.forEach { t ->
                        ChoiceTile(pageIcon(t), stringResource(pageLabel(t)), vm.page == t, Modifier.weight(1f).fillMaxHeight()) { change { vm.page = t } }
                    }
                }
                Text(stringResource(R.string.invoice_colour), style = MaterialTheme.typography.labelLarge, color = c.ink2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InvoiceDesign.ACCENTS.forEachIndexed { i, rgb ->
                        val selected = vm.accent == rgb
                        val description = stringResource(R.string.invoice_colour_n, i + 1)
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .padding(if (selected) 5.dp else 0.dp).clip(CircleShape)
                                .background(Color(0xFF000000 or rgb.toLong()))
                                .semantics { contentDescription = description; this.selected = selected }
                                .pressable { change { vm.accent = rgb } },
                        )
                    }
                }
            }

            FormCard(stringResource(R.string.invoice_slip_section)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlipTemplate.entries.forEach { t ->
                        ChoiceTile(slipIcon(t), stringResource(slipLabel(t)), vm.slipTemplate == t, Modifier.weight(1f).fillMaxHeight()) { change { vm.slipTemplate = t } }
                    }
                }
            }

            FormCard(stringResource(R.string.invoice_logo)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(c.sunk), contentAlignment = Alignment.Center) {
                        val l = vm.logo
                        if (l != null) Image(l.asImageBitmap(), contentDescription = stringResource(R.string.invoice_logo), Modifier.size(56.dp))
                        else Icon(painterResource(R.drawable.ic_image), contentDescription = null, tint = c.ink2)
                    }
                    OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        ButtonLabel(R.drawable.ic_image, stringResource(if (vm.logo == null) R.string.invoice_logo_add else R.string.invoice_logo_change))
                    }
                    if (vm.logo != null) TextButton(onClick = { vm.removeLogo(context) }) { Text(stringResource(R.string.invoice_logo_remove), color = c.loss) }
                }
            }

            FormCard(stringResource(R.string.invoice_details)) {
                OutlinedTextField(vm.address, { change { vm.address = it } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.shop_address)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                OutlinedTextField(vm.phone, { change { vm.phone = it } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.shop_phone)) }, singleLine = true)
                OutlinedTextField(vm.taxNumber, { change { vm.taxNumber = it } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.invoice_tax_number)) }, singleLine = true)
                OutlinedTextField(vm.terms, { change { vm.terms = it } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.invoice_terms)) },
                    supportingText = { Text(stringResource(R.string.invoice_terms_hint)) }, minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                OutlinedTextField(vm.footer, { change { vm.footer = it } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.invoice_footer)) },
                    placeholder = { Text("Thank you!") }, singleLine = true)
            }
        }
        Button(onClick = { vm.save { notify(saved); onBack() } }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp)) {
            ButtonLabel(R.drawable.ic_check_circle, stringResource(R.string.save))
        }
    }
    LaunchedEffect(Unit) { vm.refresh(context, wait = false) }
}

private fun pageIcon(t: PageTemplate) = when (t) {
    PageTemplate.MODERN -> R.drawable.ic_tpl_modern
    PageTemplate.CLASSIC -> R.drawable.ic_tpl_classic
    PageTemplate.MINIMAL -> R.drawable.ic_tpl_minimal
}

private fun pageLabel(t: PageTemplate) = when (t) {
    PageTemplate.MODERN -> R.string.template_modern
    PageTemplate.CLASSIC -> R.string.template_classic
    PageTemplate.MINIMAL -> R.string.template_minimal
}

private fun slipIcon(t: SlipTemplate) = when (t) {
    SlipTemplate.CLASSIC -> R.drawable.ic_slip_classic
    SlipTemplate.COMPACT -> R.drawable.ic_slip_compact
    SlipTemplate.BOLD -> R.drawable.ic_slip_bold
}

private fun slipLabel(t: SlipTemplate) = when (t) {
    SlipTemplate.CLASSIC -> R.string.template_classic
    SlipTemplate.COMPACT -> R.string.template_compact
    SlipTemplate.BOLD -> R.string.template_bold
}
