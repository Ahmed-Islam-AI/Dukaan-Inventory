package app.dukaan.android.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dukaan.android.R
import app.dukaan.android.core.ui.theme.LocalDukaanColors

/*
 * Icon-first building blocks (D-67, DESIGN.md § Components). A shopkeeper should recognise an
 * action by its picture and one or two words, never by reading a sentence.
 */

/** Colour family of an icon badge. Money colours keep their meaning: PROFIT = money in, LOSS = out/void. */
enum class Tone { BRAND, PROFIT, LOSS, WARNING, INFO, NEUTRAL }

/** Foreground and background of a tone (badges, initials tiles). */
@Composable
fun toneColors(tone: Tone): Pair<Color, Color> = tone.fgBg()

@Composable
private fun Tone.fgBg(): Pair<Color, Color> {
    val c = LocalDukaanColors.current
    return when (this) {
        Tone.BRAND -> MaterialTheme.colorScheme.primary to c.brandBg
        Tone.PROFIT -> c.profit to c.profitBg
        Tone.LOSS -> c.loss to c.lossBg
        Tone.WARNING -> c.warning to c.warningBg
        Tone.INFO -> c.info to c.infoBg
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface to c.sunk
    }
}

/** Rounded-square tinted icon: the visual anchor of a row or tile. Decorative (label says it). */
@Composable
fun IconBadge(@DrawableRes icon: Int, tone: Tone, size: Dp = 40.dp) {
    val (fg, bg) = tone.fgBg()
    Box(Modifier.size(size).background(bg, RoundedCornerShape(size / 3.5f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.55f))
    }
}

/** White rounded card that groups related rows on the tinted page background. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalDukaanColors.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.raised).border(1.dp, c.line, RoundedCornerShape(16.dp)),
        content = content,
    )
}

/** Menu entry: icon badge, short title, optional current value, chevron. One tap target. */
@Composable
fun MenuRow(@DrawableRes icon: Int, tone: Tone, title: String, value: String? = null, onClick: () -> Unit) {
    val c = LocalDukaanColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, tone)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = c.ink2, maxLines = 1)
        Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = c.ink2, modifier = Modifier.size(20.dp))
    }
}

/** Big square action: icon on top, one or two words below. Used in rows of 3-4 (Home, receipt, party). */
@Composable
fun ActionTile(@DrawableRes icon: Int, tone: Tone, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).pressable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconBadge(icon, if (enabled) tone else Tone.NEUTRAL, size = 48.dp)
        Text(
            label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalDukaanColors.current.ink2,
        )
    }
}

/** A row of [ActionTile]s sharing the width equally, inside a [Panel]. */
@Composable
fun ActionBar(modifier: Modifier = Modifier, tiles: @Composable RowScope.() -> Unit) {
    Panel(modifier) { Row(Modifier.fillMaxWidth().padding(6.dp), content = tiles) }
}

/** Initials in a coloured circle: tells people apart at a glance in Parties and history. */
@Composable
fun Avatar(name: String, size: Dp = 40.dp) {
    val tones = listOf(Tone.BRAND, Tone.INFO, Tone.PROFIT, Tone.WARNING)
    val (fg, bg) = tones[Math.floorMod(name.trim().lowercase().hashCode(), tones.size)].fgBg()
    val initials = name.trim().split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

/** One list entry as its own white card (lists of items, bills, parties, expenses). */
@Composable
fun CardRow(onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = LocalDukaanColors.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).background(c.raised).border(1.dp, c.line, shape)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Icon + words inside any button, so every action carries a picture (D-67). */
@Composable
fun ButtonLabel(@DrawableRes icon: Int, text: String) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
    Text(text, Modifier.padding(start = 8.dp))
}

/**
 * The one search field (Items, Sell, Parties): white, rounded, search icon, clear button, optional
 * scan button. Same look everywhere so it's recognised at a glance.
 */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onScan: (() -> Unit)? = null) {
    val c = LocalDukaanColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            Row {
                if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear)) }
                if (onScan != null) IconButton(onClick = onScan) { Icon(painterResource(R.drawable.ic_scan), stringResource(R.string.scan_action), tint = MaterialTheme.colorScheme.primary) }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = c.raised, focusedContainerColor = c.raised,
            unfocusedBorderColor = c.line,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A form section: small title, then its fields inside a white card. */
@Composable
fun FormCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = LocalDukaanColors.current.ink2, modifier = Modifier.padding(start = 4.dp))
        Panel { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
    }
}

/**
 * Short confirmation after an action ("Item saved"). Provided by the root Scaffold's snackbar
 * (NavGraph); a no-op in previews and tests.
 */
val LocalNotify = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Wraps a dialog's result callback so success (null error) also shows [text] as a confirmation. */
fun ((String?) -> Unit).alsoNotify(notify: (String) -> Unit, text: String): (String?) -> Unit = { error ->
    if (error == null) notify(text)
    this(error)
}
