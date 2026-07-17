package com.pulseloop.ui.dashboard

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Reactive, SharedPreferences-backed store for [MetricPrefs], ported from iOS #64
 * (`MetricPrefsStore`). A single process-wide instance keeps Settings and both dashboards in sync;
 * mutations update the exposed [prefs] flow *and* persist, so any hide/show/reorder recomposes the
 * affected tab immediately — which is also the reactivity iOS #70 added (no summary-signature needed
 * on Android, since the screens read this flow directly).
 *
 * The prefs are non-sensitive UI state, so a plain prefs file is used (not the encrypted store).
 */
class MetricPrefsStore internal constructor(private val prefsStore: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _prefs = MutableStateFlow(load())
    val prefs: StateFlow<MetricPrefs> = _prefs.asStateFlow()

    val current: MetricPrefs get() = _prefs.value

    fun isHidden(card: DashboardCard, scope: MetricScope): Boolean = current.isHidden(card, scope)

    fun setHidden(card: DashboardCard, hidden: Boolean, scope: MetricScope) =
        update(current.withHidden(card, hidden, scope))

    fun order(scope: MetricScope): List<String> = current.order(scope)

    fun setOrder(order: List<String>, scope: MetricScope) = update(current.withOrder(order, scope))

    fun resolvedOrder(visible: Set<String>, defaultOrder: List<String>, scope: MetricScope): List<String> =
        current.resolvedOrder(visible, defaultOrder, scope)

    private fun update(next: MetricPrefs) {
        if (next == current) return
        _prefs.value = next
        persist(next)
    }

    private fun load(): MetricPrefs {
        val raw = prefsStore.getString(KEY, null) ?: return MetricPrefs.DEFAULT
        // Tolerant decode: a blob written before a field existed falls back to defaults.
        return try {
            json.decodeFromString(MetricPrefs.serializer(), raw)
        } catch (_: Exception) {
            MetricPrefs.DEFAULT
        }
    }

    private fun persist(prefs: MetricPrefs) {
        prefsStore.edit().putString(KEY, json.encodeToString(MetricPrefs.serializer(), prefs)).apply()
    }

    companion object {
        private const val KEY = "pulseloop.metricprefs.v1"
        private const val FILE = "pulseloop_prefs"

        @Volatile
        private var instance: MetricPrefsStore? = null

        /** Process-wide shared instance so Settings and both dashboards mutate the same flow. */
        fun get(context: Context): MetricPrefsStore =
            instance ?: synchronized(this) {
                instance ?: MetricPrefsStore(
                    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
