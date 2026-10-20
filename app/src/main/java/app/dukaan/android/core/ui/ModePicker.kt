package app.dukaan.android.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dukaan.android.R
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.ui.theme.LocalDukaanColors

fun modeLabel(mode: PaymentMode) = when (mode) {
    PaymentMode.CASH -> R.string.mode_cash
    PaymentMode.BANK -> R.string.mode_bank
    PaymentMode.WALLET -> R.string.mode_wallet
}

fun modeIcon(mode: PaymentMode) = when (mode) {
    PaymentMode.CASH -> R.drawable.ic_cash
    PaymentMode.BANK -> R.drawable.ic_bank
    PaymentMode.WALLET -> R.drawable.ic_wallet
}

/** Cash / Bank / Wallet as three picture tiles; the chosen one is outlined in saffron (D-67). */
@Composable
fun ModePicker(selected: PaymentMode, modifier: Modifier = Modifier, onSelect: (PaymentMode) -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaymentMode.entries.forEach { m ->
            ChoiceTile(modeIcon(m), stringResource(modeLabel(m)), m == selected, Modifier.weight(1f).fillMaxHeight()) { onSelect(m) }
        }
    }
}

/** One selectable picture tile (payment mode, shop type). Selected = saffron outline and tint. */
@Composable
fun ChoiceTile(@DrawableRes icon: Int, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalDukaanColors.current
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.heightIn(min = 76.dp).clip(shape)
            .background(if (selected) c.brandBg else c.raised).border(if (selected) 2.dp else 1.dp, if (selected) primary else c.line, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (selected) primary else c.ink2, modifier = Modifier.size(26.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
    }
}
