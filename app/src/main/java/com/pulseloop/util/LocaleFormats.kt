package com.pulseloop.util

import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Locale-aware date/time patterns for user-visible labels.
 *
 * A fixed pattern such as `"MMM d"` or `"h:mm a"` hard-codes English field order and the 12-hour
 * clock. A skeleton asks the platform for the locale's own arrangement of the same fields:
 * `"MMMd"` is "Nov 11" in English and "11 нояб." in Russian; `"jmm"` is "3:45 PM" in en-US and
 * "15:45" in Russian.
 *
 * The platform pattern is only used if `java.time` accepts it. Some locales get symbols that
 * `DateTimeFormatter` on older Android releases rejects (zh-TW's `Bh:mm` day period, for
 * example); those fall back to [fallback] rather than crash the screen.
 *
 * In JVM unit tests the Android call returns null (`isReturnDefaultValues`), so the skeleton
 * falls back to [fallback], keeping test expectations on the original English patterns.
 *
 * Patterns and formatters are cached per (skeleton, fallback, locale): charts and sleep rows ask
 * for them on every tick.
 */
object LocaleFormats {
    private data class Key(val skeleton: String, val fallback: String, val locale: Locale)

    private val patterns = ConcurrentHashMap<Key, String>()
    private val formatters = ConcurrentHashMap<Key, DateTimeFormatter>()

    fun pattern(skeleton: String, fallback: String, locale: Locale = Locale.getDefault()): String =
        patterns.getOrPut(Key(skeleton, fallback, locale)) { resolve(skeleton, fallback, locale).first }

    fun formatter(skeleton: String, fallback: String, locale: Locale = Locale.getDefault()): DateTimeFormatter =
        formatters.getOrPut(Key(skeleton, fallback, locale)) { resolve(skeleton, fallback, locale).second }

    /** Hours and minutes in the locale's preferred clock ("3:45 PM" / "15:45"). */
    fun time(): DateTimeFormatter = formatter("jmm", "h:mm a")

    /** A new [java.text.SimpleDateFormat] each call: the class is not thread-safe. */
    fun simpleDateFormat(skeleton: String, fallback: String, locale: Locale = Locale.getDefault()) =
        java.text.SimpleDateFormat(pattern(skeleton, fallback, locale), locale)

    private fun resolve(skeleton: String, fallback: String, locale: Locale): Pair<String, DateTimeFormatter> {
        val platform = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        if (platform != null) {
            val formatter = runCatching { DateTimeFormatter.ofPattern(platform, locale) }.getOrNull()
            // SimpleDateFormat parses patterns with its own rules; it must accept the pattern too.
            val legacyOk = runCatching { java.text.SimpleDateFormat(platform, locale) }.isSuccess
            if (formatter != null && legacyOk) return platform to formatter
        }
        return fallback to DateTimeFormatter.ofPattern(fallback, locale)
    }
}
