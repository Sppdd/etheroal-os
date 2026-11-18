package com.etheroal.os.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * "Halflight": one dark palette, lit from behind by the [Atmosphere] shader. Etheroal is a home
 * screen, so it keeps one look instead of following the wallpaper's colours.
 */
object Halo {
    val Ink = Color(0xFF06070B)
    val Pearl = Color(0xFFF1F2F6)          // primary text
    val Mist = Color(0xFFA9AEBD)           // secondary text (7:1 on Ink)
    val Periwinkle = Color(0xFF93A0FF)
    val Ember = Color(0xFFFFA57A)
    val Tide = Color(0xFF6FE0D0)
    val Alarm = Color(0xFFFF8E8E)

    /** Glass: a dark veil that keeps text readable over the moving light, plus a thin bright rim. */
    val Glass = Color(0xB30B0D15)
    val GlassRaised = Color(0xCC12141E)
    val RimTop = Color(0x38FFFFFF)
    val RimBottom = Color(0x0AFFFFFF)
}

private val scheme = darkColorScheme(
    primary = Halo.Periwinkle,
    onPrimary = Color(0xFF0A0E2E),
    primaryContainer = Color(0xFF232A5C),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Halo.Tide,
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF16332F),
    onSecondaryContainer = Color(0xFFB9F3EA),
    tertiary = Halo.Ember,
    onTertiary = Color(0xFF331200),
    tertiaryContainer = Color(0xFF4A2412),
    onTertiaryContainer = Color(0xFFFFDBCB),
    error = Halo.Alarm,
    onError = Color(0xFF3A0808),
    errorContainer = Color(0xFF4A1616),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Halo.Ink,
    onBackground = Halo.Pearl,
    surface = Halo.Ink,
    onSurface = Halo.Pearl,
    surfaceVariant = Color(0xFF1A1D29),
    onSurfaceVariant = Halo.Mist,
    outline = Color(0xFF5A6072),
    outlineVariant = Color(0xFF2A2E3C),
    // Stock Material cards (the Lab) sit on the same veil as the glass panels.
    surfaceContainerLowest = Halo.Glass,
    surfaceContainerLow = Halo.Glass,
    surfaceContainer = Halo.GlassRaised,
    surfaceContainerHigh = Halo.GlassRaised,
    surfaceContainerHighest = Halo.GlassRaised,
)

private val base = Typography()
private val type = base.copy(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Thin, letterSpacing = (-0.03).em),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Thin, letterSpacing = (-0.03).em),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Light),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Light),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
)

/** Small spaced capitals for section labels ("URGENT · 2"). */
val Eyebrow = TextStyle(
    fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.14.em,
)

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun EtheroalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
}
