package com.pulseloop.coach.context

import com.pulseloop.R
import com.pulseloop.util.L10n

/**
 * The language the coach should write in: the language of the strings the app is showing.
 *
 * Prompts, tool schemas and tool results stay in English: the model reads English most
 * reliably, and several parsers depend on exact English tokens. Only the *output language* is
 * steered, by appending a directive to each system prompt that produces user-visible text
 * (chat, summaries, check-in notifications, meal estimates). For English the directive is
 * empty, so the prompts are byte-for-byte what they were before localization.
 *
 * The name comes from the `coach_language` string resource rather than from the device locale,
 * so it follows whichever translation Android actually resolved. A phone set to a language the
 * app has no translation for shows English UI and gets an English coach, not a coach in a
 * language the rest of the app isn't speaking.
 */
object CoachLanguage {
    /** English name of the app's resolved language ("Russian"), or null when it is English. */
    fun languageName(): String? =
        L10n.get(R.string.coach_language).trim().takeIf { it.isNotEmpty() && !it.equals("English", ignoreCase = true) }

    /**
     * A sentence to append to a system prompt, or "" for English. [what] names the output,
     * e.g. "all replies" or "the meal name and assumptions".
     */
    fun directive(what: String = "all replies"): String {
        val language = languageName() ?: return ""
        return "\n\nWrite $what in $language — the user's app language — even though these " +
            "instructions and the data you receive are in English. Keep numbers, units and " +
            "proper names as they are."
    }
}
