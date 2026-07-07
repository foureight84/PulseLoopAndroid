package com.pulseloop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// PulseLoop brand colors — ported from AppTheme.swift. The dark scheme maps the iOS
// tokens onto Material slots: background #080A0F, cards #151A23 (neutral near-black,
// not purple-tinted), accent #7C5CFF. Full token set lives in PulseColors.kt.
private val PurplePrimary = Color(0xFF7C5CFF)
private val PurpleDark = Color(0xFF7C5CFF)
private val LightBackground = Color(0xFFF8F7FC)
private val LightSurface = Color(0xFFFFFFFF)

private val DarkColorScheme = darkColorScheme(
    primary = PurplePrimary,
    secondary = PurpleDark,
    background = PulseColors.background,
    surface = PulseColors.secondaryBackground,
    onBackground = PulseColors.textPrimary,
    onSurface = PulseColors.textPrimary,
    surfaceVariant = PulseColors.card,
    onSurfaceVariant = PulseColors.textSecondary,
    outline = PulseColors.borderStrong,
    outlineVariant = PulseColors.borderSubtle,
)

private val LightColorScheme = lightColorScheme(
    primary = PurplePrimary,
    secondary = PurpleDark,
    background = LightBackground,
    surface = LightSurface,
    onBackground = Color(0xFF1A1A2E),
    onSurface = Color(0xFF1A1A2E),
    surfaceVariant = Color(0xFFF0EEFA),
)

@Composable
fun PulseLoopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
