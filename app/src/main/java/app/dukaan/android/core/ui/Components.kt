package app.dukaan.android.core.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dukaan.android.R
import app.dukaan.android.core.db.TrackingType
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import app.dukaan.android.core.ui.theme.MoneyTextStyle

/** How this shop writes money (D-42). Provided at the root from shop_profile. */
/** [serialLabel]: what a unit's serial is called here: "IMEI" in phone shops, "S/N" elsewhere (D-85). */
data class ShopFormat(val symbol: String = "Rs", val lakhGrouping: Boolean = true, val hideCost: Boolean = false, val serialLabel: String = "IMEI")

val LocalShopFormat = staticCompositionLocalOf { ShopFormat() }

/** Left-to-right isolate: amounts and IMEIs stay LTR inside Urdu text (DESIGN.md § RTL). */
fun ltr(text: String) = "$LRI$text$PDI"

private val LRI = Char(0x2066) // LEFT-TO-RIGHT ISOLATE
private val PDI = Char(0x2069) // POP DIRECTIONAL ISOLATE

fun Money.display(format: ShopFormat, withSymbol: Boolean) =
    format(if (withSymbol) format.symbol else null, format.lakhGrouping)

/**
 * Money, per DESIGN.md § Money display: tabular, LTR, symbol only on totals (D-43).
 * [hideable] amounts (cost, profit, stock value) show "••••" while cost is hidden (D-45).
 */
@Composable
fun MoneyText(
    amount: Money,
    modifier: Modifier = Modifier,
    withSymbol: Boolean = false,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    hideable: Boolean = false,
) {
    val format = LocalShopFormat.current
    val hidden = hideable && format.hideCost
    val text = if (hidden) "••••" else amount.display(format, withSymbol)
    val spoken = if (hidden) stringResource(R.string.a11y_hidden_amount)
    else stringResource(R.string.a11y_amount, amount.display(format, withSymbol = false))
    Text(
        text = ltr(text),
        modifier = modifier.semantics { contentDescription = spoken },
        style = style.merge(MoneyTextStyle),
        color = color,
    )
}

/** "3" or "1.5 kg" (DESIGN.md § Money display). */
fun qtyLabel(qty: Qty, unit: String, tracking: TrackingType, showUnit: Boolean = true): String = when {
    tracking == TrackingType.SERIAL -> qty.format()
    showUnit && unit.isNotBlank() && unit != "pcs" -> "${qty.format()} $unit"
    else -> qty.format()
}

/** End-aligned stock number + caption; amber when low, red when negative. */
@Composable
fun StockBadge(onHand: Qty, unit: String, tracking: TrackingType, low: Boolean, modifier: Modifier = Modifier) {
    val c = LocalDukaanColors.current
    val (color, caption) = when {
        onHand.milli < 0 -> c.loss to stringResource(R.string.stock_fix)
        low -> c.warning to stringResource(R.string.stock_low)
        tracking == TrackingType.SERIAL -> MaterialTheme.colorScheme.onSurface to stringResource(R.string.stock_imei_tracked)
        else -> MaterialTheme.colorScheme.onSurface to unit
    }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(ltr(onHand.format()), style = MaterialTheme.typography.titleMedium.merge(MoneyTextStyle), color = color)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = if (color == MaterialTheme.colorScheme.onSurface) c.ink2 else color)
    }
}

enum class TagKind { VOID, CREDIT, PENDING }

@Composable
fun Tag(kind: TagKind, modifier: Modifier = Modifier) {
    val c = LocalDukaanColors.current
    val (fg, bg, label) = when (kind) {
        TagKind.VOID -> Triple(c.loss, c.lossBg, R.string.tag_void)
        TagKind.CREDIT -> Triple(c.warning, c.warningBg, R.string.tag_credit)
        TagKind.PENDING -> Triple(c.warning, c.warningBg, R.string.tag_pending)
    }
    Text(
        stringResource(label),
        modifier = modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
    )
}

/** Big icon + one sentence of context + one primary action (DESIGN.md § States, D-67). */
@Composable
fun EmptyState(title: String, body: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) Box(Modifier.padding(bottom = 8.dp)) { IconBadge(icon, Tone.BRAND, size = 72.dp) }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalDukaanColors.current.ink2, textAlign = TextAlign.Center)
        if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = LocalDukaanColors.current.ink2,
    )
}

/** Screen title row: title + optional subtitle, actions at the end. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onSubtitleClick: (() -> Unit)? = null,
    /** Back or close, drawn before the title (start side; mirrors in RTL). */
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (navigation != null) 4.dp else 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigation?.invoke()
        Column(Modifier.weight(1f)) {
            // Top-level tabs get a bold page title; screens reached from them a quieter one (D-86).
            Text(
                title,
                style = if (navigation == null) MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                else MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(
                subtitle,
                modifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier,
                style = MaterialTheme.typography.bodySmall,
                color = if (onSubtitleClick != null) MaterialTheme.colorScheme.primary else LocalDukaanColors.current.ink2,
            )
        }
        actions()
    }
}

@Composable
fun RowDivider() = HorizontalDivider(color = LocalDukaanColors.current.line)
