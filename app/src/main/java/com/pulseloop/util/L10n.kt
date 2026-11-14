package com.pulseloop.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.io.File

/**
 * String resources for code that has no [Context] of its own: services, view-model state
 * builders, threshold engines, notification text.
 *
 * Composables use `stringResource`, and anything already holding a Context should call
 * `context.getString` directly. This exists for the layers below the UI, which were written
 * against literals and are exercised by plain JVM unit tests.
 *
 * In the app, [init] is called from `PulseLoopApplication.onCreate`, and lookups go through the
 * application's resources, so they follow the system or per-app language. In a JVM unit test there
 * is no Context, so lookups read the default (English) `values/strings.xml` straight from the
 * module's source tree. Tests asserting on English copy therefore keep passing without
 * duplicating any text in code.
 */
object L10n {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(@StringRes id: Int, vararg args: Any?): String {
        appContext?.let { return if (args.isEmpty()) it.getString(id) else it.getString(id, *args) }
        val raw = JvmFallback.string(id)
        return if (args.isEmpty()) raw else String.format(raw, *args)
    }

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String {
        appContext?.let { return it.resources.getQuantityString(id, count, *args) }
        val raw = JvmFallback.plural(id, count)
        return String.format(raw, *args)
    }

    /** Reads `values/strings.xml` for JVM unit tests, where no Android resources exist. */
    private object JvmFallback {
        private val strings = mutableMapOf<String, String>()
        private val plurals = mutableMapOf<String, Map<String, String>>()

        init {
            val file = listOf(
                "src/main/res/values/strings.xml",
                "app/src/main/res/values/strings.xml",
            ).map(::File).firstOrNull { it.exists() }
            val xml = file?.readText().orEmpty()
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).forEach { strings[it.groupValues[1]] = unescape(it.groupValues[2]) }
            Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).forEach { p ->
                    plurals[p.groupValues[1]] = Regex("""<item quantity="(\w+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(p.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
                }
        }

        private fun name(id: Int, type: String): String {
            val cls = Class.forName("com.pulseloop.R\$$type")
            return cls.fields.firstOrNull { it.getInt(null) == id }?.name
                ?: error("No R.$type resource with id $id")
        }

        fun string(id: Int): String = name(id, "string").let { strings[it] ?: error("Missing string $it") }

        fun plural(id: Int, count: Int): String {
            val forms = name(id, "plurals").let { plurals[it] ?: error("Missing plurals $it") }
            return forms[if (count == 1) "one" else "other"] ?: forms.getValue("other")
        }

        private fun unescape(s: String): String {
            var t = s.trim()
            if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length - 1)
            return t.replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\"").replace("\\@", "@")
                .replace("\\?", "?").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        }
    }
}
