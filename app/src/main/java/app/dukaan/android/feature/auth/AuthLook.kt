package app.dukaan.android.feature.auth

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dukaan.android.R
import app.dukaan.android.core.ui.ButtonLabel
import app.dukaan.android.core.ui.Motion
import app.dukaan.android.core.ui.theme.LocalDukaanColors
import kotlinx.coroutines.delay

/*
 * The look of the signed-out screens (D-84): one saffron brand surface (gradient + soft circles,
 * the same as Home's money card) behind a landing carousel and the login / sign-up headers, with
 * the forms on a white sheet that overlaps it.
 */

private val Ember = Color(0xFFB8420A)

/** Saffron gradient with two soft circles for depth. */
@Composable
fun Modifier.brandSurface(): Modifier {
    val brand = MaterialTheme.colorScheme.primary
    return background(Brush.linearGradient(listOf(brand, Ember))).drawBehind {
        drawCircle(Color.White.copy(alpha = 0.07f), radius = size.width * 0.55f, center = Offset(size.width * 1.05f, size.height * 0.02f))
        drawCircle(Color.White.copy(alpha = 0.05f), radius = size.width * 0.3f, center = Offset(-size.width * 0.05f, size.height * 0.85f))
    }
}

/** The white rounded logo tile with the store mark. */
@Composable
fun LogoTile(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Color.White), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_store), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.56f))
    }
}

/**
 * Saffron header of the login / sign-up / shop steps: back, an optional "Step n of m" pill, a big
 * title and a short line. The form sheet below overlaps its bottom edge.
 */
@Composable
fun AuthHeader(title: String, subtitle: String, onBack: () -> Unit, step: String? = null) {
    Column(Modifier.fillMaxWidth().brandSurface().statusBarsPadding().padding(bottom = 40.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back), tint = Color.White) }
            Box(Modifier.weight(1f))
            step?.let {
                Text(
                    it, Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium, color = Color.White,
                )
            }
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.88f))
        }
    }
}

/** The white sheet that rises over the header's bottom edge and holds a form. */
@Composable
fun AuthSheet(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().offset(y = (-24).dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(MaterialTheme.colorScheme.background),
    ) { content() }
}

/** Phone / Email as one pill whose thumb slides to the choice. */
@Composable
fun MethodSwitch(method: AuthMethod, onChange: (AuthMethod) -> Unit) {
    val c = LocalDukaanColors.current
    val brand = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(c.sunk).padding(4.dp)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val half = maxWidth / 2
            val thumb by animateDpAsState(if (method == AuthMethod.PHONE) 0.dp else half, tween(Motion.MEDIUM, easing = FastOutSlowInEasing), label = "method")
            Box(Modifier.offset(x = thumb).width(half).fillMaxHeight().clip(CircleShape).background(Color.White))
            Row(Modifier.fillMaxSize()) {
                AuthMethod.entries.forEach { m ->
                    val selected = m == method
                    Row(
                        Modifier.weight(1f).fillMaxHeight().clip(CircleShape).selectable(selected, role = Role.Tab) { onChange(m) },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painterResource(if (m == AuthMethod.PHONE) R.drawable.ic_call else R.drawable.ic_message), contentDescription = null,
                            tint = if (selected) brand else c.ink2, modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(if (m == AuthMethod.PHONE) R.string.auth_with_phone else R.string.auth_with_email),
                            Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = if (selected) brand else c.ink2,
                        )
                    }
                }
            }
        }
    }
}

private class Feature(@DrawableRes val icon: Int, @DrawableRes val badge: Int, @StringRes val title: Int, @StringRes val body: Int)

private val features = listOf(
    Feature(R.drawable.ic_receipt, R.drawable.ic_share, R.string.landing_bills_title, R.string.landing_bills_body),
    Feature(R.drawable.ic_items, R.drawable.ic_scan, R.string.landing_stock_title, R.string.landing_stock_body),
    Feature(R.drawable.ic_wallet, R.drawable.ic_money_in, R.string.landing_udhaar_title, R.string.landing_udhaar_body),
    Feature(R.drawable.ic_sync, R.drawable.ic_backup, R.string.landing_sync_title, R.string.landing_sync_body),
)

/**
 * The first page (D-84): brand mark, a carousel of what the app does (swipe or wait), and the two
 * ways in. Logo pops in, the illustration floats, the sheet with the buttons rises.
 */
@Composable
fun Landing(localShopName: String?, onGetStarted: () -> Unit, onLogIn: () -> Unit) {
    val pager = rememberPagerState { features.size }
    // Auto-advance; any swipe restarts the wait for the new page.
    LaunchedEffect(pager.currentPage) {
        delay(3_800)
        pager.animateScrollToPage((pager.currentPage + 1) % features.size, animationSpec = tween(Motion.LONG, easing = FastOutSlowInEasing))
    }
    val float by rememberInfiniteTransition(label = "float").animateFloat(
        -6f, 6f, infiniteRepeatable(tween(2_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob",
    )
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }

    Column(Modifier.fillMaxSize().brandSurface()) {
        // Brand row.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(shown, enter = scaleIn(tween(Motion.LONG), initialScale = 0.6f) + fadeIn(tween(Motion.LONG))) { LogoTile(44.dp) }
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(stringResource(R.string.auth_tagline), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
            }
        }

        // Carousel.
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            val f = features[page]
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Illustration(f, Modifier.graphicsLayer { translationY = float * density })
                Text(stringResource(f.title), Modifier.padding(top = 32.dp), style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(stringResource(f.body), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.88f), textAlign = TextAlign.Center)
            }
        }
        Dots(features.size, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp))

        // The way in: rises from the bottom.
        AnimatedVisibility(shown, enter = slideInVertically(tween(Motion.LONG, delayMillis = 150, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(Motion.LONG, delayMillis = 150))) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A new account starts empty and removes a shop left on this phone (D-82).
                localShopName?.let {
                    Text(stringResource(R.string.auth_existing_data, it), style = MaterialTheme.typography.bodySmall, color = LocalDukaanColors.current.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                Button(onClick = onGetStarted, Modifier.fillMaxWidth().height(56.dp)) { ButtonLabel(R.drawable.ic_person_add, stringResource(R.string.landing_get_started)) }
                OutlinedButton(onClick = onLogIn, Modifier.fillMaxWidth().height(56.dp)) { ButtonLabel(R.drawable.ic_person, stringResource(R.string.landing_have_account)) }
            }
        }
    }
}

/** A white disc with the feature's icon and a small badge on its edge. */
@Composable
private fun Illustration(f: Feature, modifier: Modifier) {
    val brand = MaterialTheme.colorScheme.primary
    Box(modifier.size(184.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(184.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)))
        Box(Modifier.size(136.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(painterResource(f.icon), contentDescription = null, tint = brand, modifier = Modifier.size(64.dp))
        }
        Badge(f.badge, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 14.dp))
    }
}

@Composable
private fun BoxScope.Badge(@DrawableRes icon: Int, modifier: Modifier) {
    Box(modifier.size(52.dp).clip(CircleShape).background(Color(0xFF1A1D21)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** Page dots; the current one stretches into a pill. */
@Composable
private fun Dots(count: Int, current: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 22.dp else 8.dp, tween(Motion.MEDIUM), label = "dot")
            Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(Color.White.copy(alpha = if (i == current) 1f else 0.45f)))
        }
    }
}
