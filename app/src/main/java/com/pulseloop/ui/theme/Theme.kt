package com.pulseloop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// PulseLoop brand colors — ported from AppTheme.swift
private val PurplePrimary = Color(0xFF863BFF)
private val PurpleDark = Color(0xFF7C5CFF)
private val DarkBackground = Color(0xFF0E1118)
private val DarkSurface = Color(0xFF191331)
private val LightBackground = Color(0xFFF8F7FC)
private val LightSurface = Color(0xFFFFFFFF)

private val DarkColorScheme = darkColorScheme(
    primary = PurplePrimary,
    secondary = PurpleDark,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = DarkSurface,
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
