package com.pulseloop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The full iOS design-token set, ported 1:1 from AppTheme.swift (PulseColors).
 * Every screen chrome/card/accent color goes through here so Android and iOS
 * read the same color language. Zone colors live in [MetricColors]/ZonePalette.
 */
object PulseColors {
    val background = Color(0xFF080A0F)
    val secondaryBackground = Color(0xFF0E1118)
    val card = Color(0xFF151A23)
    val cardSoft = Color(0xFF1B2230)
    val elevated = Color(0xFF202838)
    val textPrimary = Color(0xFFF5F7FA)
    val textSecondary = Color(0xFFAAB3C2)
    val textMuted = Color(0xFF6F7A8C)
    val accent = Color(0xFF7C5CFF)
    val accentSoft = Color(0x2E7C5CFF)   // accent @ 18%
    val success = Color(0xFF35E0A1)
    val warning = Color(0xFFFFB86B)
    val danger = Color(0xFFFF4D6D)
    val info = Color(0xFF4DDCFF)

    // Per-metric accents
    val steps = Color(0xFF35E0A1)
    val heartRate = Color(0xFFFF4D6D)
    val spo2 = Color(0xFF4DDCFF)
    val sleep = Color(0xFF8B7CFF)
    val calories = Color(0xFFFF8A4C)
    val distance = Color(0xFF4DA3FF)
    val battery = Color(0xFFA7F3D0)
    val stress = Color(0xFFFF8A4C)
    val hrv = Color(0xFF9D7CFF)
    val temperature = Color(0xFF2DD4D8)
    val bloodPressure = Color(0xFFFF6B9D)
    val bloodSugar = Color(0xFFFFB84D)
    val fatigue = Color(0xFFC77DFF)

    // Sleep stage colors (DesignSystem/Charts.swift SleepStageColors)
    val stageDeep = Color(0xFF3F2DD8)
    val stageLight = Color(0xFF7C5CFF)
    val stageRem = Color(0xFF2DD4D8)
    val stageAwake = Color(0xFFFFB86B)

    val borderSubtle = Color(0x14FFFFFF)  // white @ 8%
    val borderStrong = Color(0x29FFFFFF)  // white @ 16%
}
