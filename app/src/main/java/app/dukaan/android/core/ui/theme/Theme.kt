package app.dukaan.android.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dukaan.android.R

// Tokens from docs/DESIGN.md § Tokens. Change them there first, then here.

/** Colours Material's scheme has no slot for: money state, quiet text, rows and tags. */
@Immutable
data class DukaanColors(
    val ink2: Color,
    val line: Color,
    val raised: Color,
    val sunk: Color,
    val profit: Color,
    val loss: Color,
    val warning: Color,
    val profitBg: Color,
    val lossBg: Color,
    val warningBg: Color,
    /** Neutral-blue accent for icon badges (bank, reports, purchases); never for money state. */
    val info: Color,
    val infoBg: Color,
    /** Saffron tint behind brand-coloured icon badges. */
    val brandBg: Color,
)

private val Light = DukaanColors(
    ink2 = Color(0xFF5A5650), line = Color(0xFFE6E0D8), raised = Color(0xFFFFFFFF), sunk = Color(0xFFF1ECE5),
    profit = Color(0xFF2B8A3E), loss = Color(0xFFC92A2A), warning = Color(0xFFB35C00),
    profitBg = Color(0xFFE8F5EA), lossBg = Color(0xFFFDECEC), warningBg = Color(0xFFFFF1DC),
    info = Color(0xFF1864AB), infoBg = Color(0xFFE7F0FA), brandBg = Color(0xFFFFE8DA),
)

private val Dark = DukaanColors(
    ink2 = Color(0xFFA8A097), line = Color(0xFF2E3134), raised = Color(0xFF1B1E21), sunk = Color(0xFF0D0F10),
    profit = Color(0xFF69DB7C), loss = Color(0xFFFF8787), warning = Color(0xFFFFC078),
    profitBg = Color(0xFF15301B), lossBg = Color(0xFF3A1A1A), warningBg = Color(0xFF3A2A12),
    info = Color(0xFF74C0FC), infoBg = Color(0xFF15283A), brandBg = Color(0xFF3A2216),
)

val LocalDukaanColors = staticCompositionLocalOf { Light }

private val LightScheme = lightColorScheme(
    primary = Color(0xFFE8590C), onPrimary = Color.White,
    background = Color(0xFFF4F0EA), surface = Color(0xFFF4F0EA),
    onBackground = Color(0xFF1A1D21), onSurface = Color(0xFF1A1D21),
    onSurfaceVariant = Color(0xFF5A5650), outline = Color(0xFFE6E0D8), outlineVariant = Color(0xFFE6E0D8),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFFFFFFF),
    // Selected chips and the nav indicator: a saffron tint, never Material's default lavender.
    secondary = Color(0xFF1A1D21), // selected nav label; Material default is lavender
    secondaryContainer = Color(0xFFFFE3D1), onSecondaryContainer = Color(0xFF1A1D21),
    error = Color(0xFFC92A2A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF8A4C), onPrimary = Color(0xFF1A1D21),
    background = Color(0xFF121416), surface = Color(0xFF121416),
    onBackground = Color(0xFFECE7E1), onSurface = Color(0xFFECE7E1),
    onSurfaceVariant = Color(0xFFA8A097), outline = Color(0xFF2E3134), outlineVariant = Color(0xFF2E3134),
    surfaceContainer = Color(0xFF1B1E21), surfaceContainerHigh = Color(0xFF1B1E21),
    secondary = Color(0xFFECE7E1),
    secondaryContainer = Color(0xFF4A2A18), onSecondaryContainer = Color(0xFFECE7E1),
    error = Color(0xFFFF8787),
)

// D-41: IBM Plex Sans (variable, bundled) for Latin; Noto Nastaliq Urdu for Urdu; Plex Mono for IMEIs.
@OptIn(ExperimentalTextApi::class)
private fun plex(weight: Int) = Font(
    R.font.plex_sans, weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val PlexSans = FontFamily(plex(400), plex(500), plex(600), plex(700))

@OptIn(ExperimentalTextApi::class)
private fun nastaliq(weight: Int) = Font(
    R.font.noto_nastaliq_urdu, weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Nastaliq = FontFamily(nastaliq(400), nastaliq(600), nastaliq(700))

/** IMEI / serial numbers: easy to read aloud and compare with the box. */
val MonoFamily = FontFamily(Font(R.font.plex_mono_medium, FontWeight.Medium))

/** Amounts: Plex tabular digits in every language; Nastaliq's Latin digits read poorly (DESIGN.md § Type, D-44). */
val MoneyTextStyle = TextStyle(fontFamily = PlexSans, fontFeatureSettings = "tnum")

private fun typography(family: FontFamily, urdu: Boolean): Typography {
    // Nastaliq needs ~1.9× line height (DESIGN.md § Type).
    val lh = if (urdu) 1.9f else 1.35f
    fun s(size: Int, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
        fontFamily = family, fontWeight = weight, fontSize = size.sp,
        lineHeight = (size * lh).sp, letterSpacing = spacing.em,
    )
    // Start from Material's full set with our family on every slot, so components we don't
    // restyle (dialog titles, snackbars) never fall back to the system font.
    val base = Typography().let { t ->
        fun TextStyle.f() = copy(fontFamily = family, lineHeight = (fontSize.value * lh).sp)
        Typography(
            t.displayLarge.f(), t.displayMedium.f(), t.displaySmall.f(),
            t.headlineLarge.f(), t.headlineMedium.f(), t.headlineSmall.f(),
            t.titleLarge.f(), t.titleMedium.f(), t.titleSmall.f(),
            t.bodyLarge.f(), t.bodyMedium.f(), t.bodySmall.f(),
            t.labelLarge.f(), t.labelMedium.f(), t.labelSmall.f(),
        )
    }
    return base.copy(
        displaySmall = s(34, FontWeight.Bold, -0.015),   // hero amount
        headlineMedium = s(30, FontWeight.Bold, -0.01),  // cart total
        titleLarge = s(20, FontWeight.Bold),             // screen title
        titleMedium = s(16, FontWeight.SemiBold),
        titleSmall = s(14, FontWeight.SemiBold),         // row title
        bodyLarge = s(16, FontWeight.Normal),
        bodyMedium = s(14, FontWeight.Normal),
        bodySmall = s(13, FontWeight.Normal),            // meta (12.5 rounded)
        labelLarge = s(15, FontWeight.SemiBold),         // buttons
        labelMedium = s(12, FontWeight.SemiBold, if (urdu) 0.0 else 0.06), // section label
        labelSmall = s(11, FontWeight.Medium),
    )
}

// No dynamic colour: brand and money colours carry meaning (DESIGN.md principle 6).
@Composable
fun DukaanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val urdu = LocalConfiguration.current.locales[0].language == "ur"
    CompositionLocalProvider(LocalDukaanColors provides if (darkTheme) Dark else Light) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = typography(if (urdu) Nastaliq else PlexSans, urdu),
            content = content,
        )
    }
}
