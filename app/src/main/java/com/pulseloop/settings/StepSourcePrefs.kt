package com.pulseloop.settings

import android.content.Context

/**
 * The user's step-source preference ("ring" or "phone").
 *
 * Deliberately uses plain (unencrypted) [android.content.SharedPreferences] rather than
 * [ApiKeyStore]'s EncryptedSharedPreferences: this is a non-sensitive UI preference, and the
 * encryption layer is expensive enough that callers reading it on every ring activity event
 * previously needed an in-memory cache. Plain prefs are cheap to read, so the cache is gone.
 */
class StepSourcePrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("pulseloop_prefs", Context.MODE_PRIVATE)

    var stepSource: String
        get() = prefs.getString(KEY_STEP_SOURCE, "ring") ?: "ring"
        set(value) { prefs.edit().putString(KEY_STEP_SOURCE, value).apply() }

    companion object {
        private const val KEY_STEP_SOURCE = "step_source"
    }
}