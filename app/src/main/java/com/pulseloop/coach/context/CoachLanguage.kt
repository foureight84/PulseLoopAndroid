package com.pulseloop.coach.context

import java.util.Locale

/**
 * The language the coach should write in, taken from the app's current locale.
 *
 * Prompts, tool schemas and tool results stay in English: the model reads English most
 * reliably, and several parsers depend on exact English tokens. Only the *output language* is
 * steered, by appending a directive to each system prompt that produces user-visible text
 * (chat, summaries, check-in notifications, meal estimates). For an English locale the directive
 * is empty, so the prompts are byte-for-byte what they were before localization.
 */
object CoachLanguage {
    /** English name of the user's language ("Russian"), or null when it is English. */
    fun languageName(locale: Locale = Locale.getDefault()): String? =
        locale.language.takeIf { it.isNotEmpty() && it != "en" }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }

    /**
     * A sentence to append to a system prompt, or "" for English. [what] names the output,
     * e.g. "all replies" or "the meal name and assumptions".
     */
    fun directive(what: String = "all replies", locale: Locale = Locale.getDefault()): String {
        val language = languageName(locale) ?: return ""
        return "\n\nWrite $what in $language — the user's app language — even though these " +
            "instructions and the data you receive are in English. Keep numbers, units and " +
            "proper names as they are."
    }
}
