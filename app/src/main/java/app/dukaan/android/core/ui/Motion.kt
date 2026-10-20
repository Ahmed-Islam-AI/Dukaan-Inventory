package app.dukaan.android.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import app.dukaan.android.R
import app.dukaan.android.core.money.Money

/*
 * Motion (D-69): short, calm, purposeful. 150-600 ms, ease-out, never blocks a tap.
 * Durations live here so every screen moves the same way.
 */
object Motion {
    const val SHORT = 150
    const val MEDIUM = 220
    const val LONG = 600
}

/** Clickable that dips to 96% while pressed: the tap is felt before the next screen appears. */
fun Modifier.pressable(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(Motion.SHORT), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = ripple(), enabled = enabled, role = role, onClick = onClick)
}

/**
 * Money that counts up when first shown and glides when it changes (Today / Month).
 * Long arithmetic on a 0..1 progress, so large amounts stay exact (never Float money).
 * TalkBack hears only the final amount.
 */
@Composable
fun CountUpMoney(target: Money, withSymbol: Boolean, style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified, hideable: Boolean = false) {
    val start = remember { LongArray(2) } // [0] = value the glide starts from, [1] = value on screen
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target.minor) {
        start[0] = start[1]
        progress.snapTo(0f)
        progress.animateTo(1f, tween(Motion.LONG, easing = FastOutSlowInEasing))
    }
    var shown = start[0] + ((target.minor - start[0]) * progress.value).toLong()
    // A whole-rupee target counts in whole rupees: no flickering paisa mid-count.
    if (target.minor % 100 == 0L && progress.value < 1f) shown -= shown % 100
    start[1] = shown
    val format = LocalShopFormat.current
    val spoken = if (hideable && format.hideCost) stringResource(R.string.a11y_hidden_amount)
    else stringResource(R.string.a11y_amount, target.display(format, withSymbol = false))
    MoneyText(Money(shown), Modifier.clearAndSetSemantics { contentDescription = spoken }, withSymbol, style, color, hideable)
}

/**
 * A section that rises and fades in, [index] × 70 ms after the screen opens (staggered dashboard,
 * D-83). [animate] false shows it at once (e.g. coming back to a screen already seen).
 */
@Composable
fun Reveal(index: Int, animate: Boolean = true, content: @Composable () -> Unit) {
    val state = remember { androidx.compose.animation.core.MutableTransitionState(!animate) }.apply { targetState = true }
    val delay = index * 70
    androidx.compose.animation.AnimatedVisibility(
        visibleState = state,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.MEDIUM + 120, delayMillis = delay)) +
            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(Motion.LONG - 180, delayMillis = delay, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 5 },
    ) { content() }
}
