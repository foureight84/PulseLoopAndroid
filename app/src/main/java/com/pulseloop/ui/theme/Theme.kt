package com.pulseloop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pulseloop.R

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
private val DarkPrimary = Color(0xFF4DE8FF)      // 11.9:1 on surface — bright cyan for glossy-screen legibility
private val DarkOnPrimary = Color(0xFF05303B)    // 9.6:1 on primary
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
    // Selected chips/toggles paint with secondaryContainer — it must differ
    // from the surfaces they sit on or selection state becomes invisible.
    secondaryContainer = DarkPrimaryContainer,
    onSecondaryContainer = DarkOnPrimaryContainer,
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
    secondaryContainer = LightPrimaryContainer,
    onSecondaryContainer = LightOnPrimaryContainer,
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
 * IBM Plex Sans — instrument-like clinical character, excellent tabular
 * figures for the metric readouts. Bundled as static TTFs (res/font); the
 * variable font isn't shipped, so weights are wired via distinct Font()
 * entries per FontWeight.
 */
private val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
)

/**
 * Type scale: IBM Plex Sans, but deliberate. Metric numbers are the app's
 * voice — large, tight, heavy. Labels are small and quiet; the contrast
 * in scale does the hierarchy work that color was failing to do.
 */
private val PulseTypography = Typography().let { t ->
    // Every default Material3 role gets the brand font first...
    val withFont = Typography(
        displayLarge = t.displayLarge.copy(fontFamily = PlexSans),
        displayMedium = t.displayMedium.copy(fontFamily = PlexSans),
        displaySmall = t.displaySmall.copy(fontFamily = PlexSans),
        headlineLarge = t.headlineLarge.copy(fontFamily = PlexSans),
        headlineMedium = t.headlineMedium.copy(fontFamily = PlexSans),
        headlineSmall = t.headlineSmall.copy(fontFamily = PlexSans),
        titleLarge = t.titleLarge.copy(fontFamily = PlexSans),
        titleMedium = t.titleMedium.copy(fontFamily = PlexSans),
        titleSmall = t.titleSmall.copy(fontFamily = PlexSans),
        bodyLarge = t.bodyLarge.copy(fontFamily = PlexSans),
        bodyMedium = t.bodyMedium.copy(fontFamily = PlexSans),
        bodySmall = t.bodySmall.copy(fontFamily = PlexSans),
        labelLarge = t.labelLarge.copy(fontFamily = PlexSans),
        labelMedium = t.labelMedium.copy(fontFamily = PlexSans),
        labelSmall = t.labelSmall.copy(fontFamily = PlexSans),
    )
    // ...then the existing weight/letterSpacing/lineHeight overrides layer
    // back on top, unchanged.
    withFont.copy(
        displayMedium = withFont.displayMedium.copy(
            fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineLarge = withFont.headlineLarge.copy(
            fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = withFont.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleMedium = withFont.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = withFont.labelSmall.copy(letterSpacing = 0.6.sp),
        bodySmall = withFont.bodySmall.copy(lineHeight = 18.sp),
    )
}

/**
 * User theme override: SYSTEM follows the OS, LIGHT/DARK force a mode.
 * Backed by SharedPreferences; the mutableStateOf makes every
 * PulseLoopTheme recompose when the user changes it in Settings.
 */
object ThemeController {
    enum class Mode { SYSTEM, LIGHT, DARK }

    var mode by androidx.compose.runtime.mutableStateOf(Mode.SYSTEM)
        private set
    private var loaded = false

    fun load(context: android.content.Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
            .getString("themeMode", null)
        mode = try { Mode.valueOf(raw ?: "SYSTEM") } catch (_: Exception) { Mode.SYSTEM }
    }

    fun set(context: android.content.Context, newMode: Mode) {
        mode = newMode
        context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("themeMode", newMode.name).apply()
    }
}

@Composable
fun PulseLoopTheme(
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.remember { ThemeController.load(context); Unit }
    val darkTheme = when (ThemeController.mode) {
        ThemeController.Mode.LIGHT -> false
        ThemeController.Mode.DARK -> true
        ThemeController.Mode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PulseTypography,
        content = content,
    )
}
