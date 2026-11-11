package com.pulseloop.util

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Locale-aware date/time patterns for user-visible labels.
 *
 * A fixed pattern such as `"MMM d"` or `"h:mm a"` hard-codes English field order and the 12-hour
 * clock. [skeleton] asks the platform for the locale's own arrangement of the same fields —
 * `"MMMd"` is "Nov 11" in English and "11 нояб." in Russian; `"jmm"` is "3:45 PM" in en-US and
 * "15:45" in Russian.
 *
 * In JVM unit tests the Android call returns null (`isReturnDefaultValues`), so the skeleton
 * falls back to [fallback], keeping test expectations on the original English patterns.
 */
object LocaleFormats {
    fun pattern(skeleton: String, fallback: String, locale: Locale = Locale.getDefault()): String =
        runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: fallback

    fun formatter(skeleton: String, fallback: String, locale: Locale = Locale.getDefault()): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern(skeleton, fallback, locale), locale)

    /** Hours and minutes in the locale's preferred clock ("3:45 PM" / "15:45"). */
    fun time(): DateTimeFormatter = formatter("jmm", "h:mm a")

    fun simpleDateFormat(skeleton: String, fallback: String, locale: Locale = Locale.getDefault()) =
        java.text.SimpleDateFormat(pattern(skeleton, fallback, locale), locale)
}
