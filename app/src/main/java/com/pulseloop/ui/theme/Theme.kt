package com.pulseloop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * PulseLoop design tokens — "Porcelain & Steel".
 *
 * Premium / calm / clinical: porcelain and cool graphite surfaces, one
 * desaturated surgical-steel accent, no saturated hue anywhere in the
 * chrome — the loudest thing on screen is the user's own numbers.
 *
 * Every pairing below is WCAG-checked (scratchpad contrast.py):
 * text roles ≥ 4.5:1 on their surfaces, UI/graphic roles ≥ 3:1.
 * The accent never carries body text; ink does.
 */

// ── Dark tokens ──
private val DarkBg = Color(0xFF0E1214)
private val DarkSurface = Color(0xFF151A1D)
private val DarkSurfaceVariant = Color(0xFF1F2529)
private val DarkInk = Color(0xFFE8EDEF)          // 14.9:1 on surface
private val DarkInkMuted = Color(0xFF9AA7AD)     // 7.1:1 on surface
private val DarkPrimary = Color(0xFF8FBFD4)      // 8.8:1 on surface as text
private val DarkOnPrimary = Color(0xFF06222C)    // 8.3:1 on primary
private val DarkPrimaryContainer = Color(0xFF274B5C)
private val DarkOnPrimaryContainer = Color(0xFFD3E7F0)  // 7.3:1
private val DarkOutline = Color(0xFF46535A)

// ── Light tokens ──
private val LightBg = Color(0xFFF6F8F9)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFEAEFF1)
private val LightInk = Color(0xFF14181A)         // 16.8:1 on surface
private val LightInkMuted = Color(0xFF4E5A60)    // 7.1:1 on surface
private val LightPrimary = Color(0xFF2E5A6B)     // 7.5:1 on white as text
private val LightOnPrimary = Color(0xFFFFFFFF)   // 7.5:1 on primary
private val LightPrimaryContainer = Color(0xFFD7E6ED)
private val LightOnPrimaryContainer = Color(0xFF0E2A36)  // 11.7:1
private val LightOutline = Color(0xFF8FA0A8)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkPrimary,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkInk,
    background = DarkBg,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkInkMuted,
    outline = DarkOutline,
    outlineVariant = Color(0xFF2A3338),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3D0A08),
    errorContainer = Color(0xFF5C1A17),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightPrimary,
    onSecondary = LightOnPrimary,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightInk,
    background = LightBg,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightInkMuted,
    outline = LightOutline,
    outlineVariant = Color(0xFFCBD5DA),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/**
 * Type scale: system sans, but deliberate. Metric numbers are the app's
 * voice — large, tight, heavy. Labels are small and quiet; the contrast
 * in scale does the hierarchy work that color was failing to do.
 */
private val PulseTypography = Typography().let { t ->
    t.copy(
        displayMedium = t.displayMedium.copy(
            fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineLarge = t.headlineLarge.copy(
            fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.6.sp),
        bodySmall = t.bodySmall.copy(lineHeight = 18.sp),
    )
}

@Composable
fun PulseLoopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PulseTypography,
        content = content,
    )
}
