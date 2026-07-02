package com.pulseloop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * PulseLoop design tokens.
 *
 * Every pairing below is WCAG-checked (scratchpad contrast.py):
 * text roles ≥ 4.5:1 on their surfaces, UI/graphic roles ≥ 3:1.
 * Purple stays the brand identity but is demoted to an accent —
 * it never carries body text; ink does.
 */

// ── Dark tokens ──
private val DarkBg = Color(0xFF0F1115)
private val DarkSurface = Color(0xFF171A21)
private val DarkSurfaceVariant = Color(0xFF232733)
private val DarkInk = Color(0xFFECEDF2)          // 14.9:1 on surface
private val DarkInkMuted = Color(0xFFA9AEBF)     // 6.7:1 on surfaceVariant
private val DarkPrimary = Color(0xFFB49CFF)      // 7.6:1 on surface as text
private val DarkOnPrimary = Color(0xFF241352)    // 7.1:1 on primary
private val DarkPrimaryContainer = Color(0xFF3B2C6E)
private val DarkOnPrimaryContainer = Color(0xFFE5DCFF)
private val DarkOutline = Color(0xFF454B5C)

// ── Light tokens ──
private val LightBg = Color(0xFFF7F7FA)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFECEBF2)
private val LightInk = Color(0xFF16171D)         // 17.9:1 on surface
private val LightInkMuted = Color(0xFF51566A)    // 6.1:1 on surfaceVariant
private val LightPrimary = Color(0xFF5B2EBC)     // 8.2:1 on white as text
private val LightOnPrimary = Color(0xFFFFFFFF)   // 8.2:1 on primary
private val LightPrimaryContainer = Color(0xFFE7DDFF)
private val LightOnPrimaryContainer = Color(0xFF2A1263)
private val LightOutline = Color(0xFF9BA0B0)

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
    outlineVariant = Color(0xFF2E3340),
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
    outlineVariant = Color(0xFFDDDCE5),
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
