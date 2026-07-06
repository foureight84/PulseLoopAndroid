package com.pulseloop.ui.onboarding

import android.content.SharedPreferences
import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.data.entity.UserProfileEntity
import com.pulseloop.settings.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Ported from OnboardingSupportTests.swift (iOS #48): progress-store persistence,
 * ProfileDraft unit handling/conversions, and GoalDraft defaults/apply semantics.
 * Pure JVM — the SharedPreferences dependency is a plain in-memory fake.
 */
class OnboardingSupportTest {

    // ── OnboardingProgressStore ─────────────────────────────────────────

    @Test
    fun `progress store round-trips a path and clear resets to welcome`() {
        val store = OnboardingProgressStore(FakeSharedPreferences())
        val path = listOf(OnboardingStep.WELCOME, OnboardingStep.RING, OnboardingStep.PROFILE)

        store.savePath(path)
        assertEquals(path, store.loadPath())

        store.clear()
        assertEquals(listOf(OnboardingStep.WELCOME), store.loadPath())
    }

    @Test
    fun `progress store rejects a route that does not start at welcome`() {
        val store = OnboardingProgressStore(FakeSharedPreferences())

        store.savePath(listOf(OnboardingStep.PROFILE, OnboardingStep.GOALS))
        assertEquals(listOf(OnboardingStep.WELCOME), store.loadPath())
    }

    @Test
    fun `progress store falls back to welcome for empty or corrupt storage`() {
        val prefs = FakeSharedPreferences()
        val store = OnboardingProgressStore(prefs)

        assertEquals(listOf(OnboardingStep.WELCOME), store.loadPath())

        prefs.edit().putString(OnboardingProgressStore.STORAGE_KEY, "bogus,junk").apply()
        assertEquals(listOf(OnboardingStep.WELCOME), store.loadPath())

        prefs.edit().putString(OnboardingProgressStore.STORAGE_KEY, "").apply()
        assertEquals(listOf(OnboardingStep.WELCOME), store.loadPath())
    }

    @Test
    fun `step raw values match iOS`() {
        assertEquals(
            listOf("welcome", "ring", "profile", "goals", "baseline"),
            OnboardingStep.entries.map { it.rawValue },
        )
    }

    // ── ProfileDraft units ──────────────────────────────────────────────

    @Test
    fun `locale defaults - US imperial, FR metric`() {
        assertEquals(UnitSystem.IMPERIAL, ProfileDraft.preferredUnits(Locale.US))
        assertEquals(UnitSystem.METRIC, ProfileDraft.preferredUnits(Locale.FRANCE))

        assertEquals(UnitSystem.IMPERIAL, ProfileDraft.from(locale = Locale.US).units)
        assertEquals(UnitSystem.METRIC, ProfileDraft.from(locale = Locale.FRANCE).units)
    }

    @Test
    fun `existing units preference wins over the locale default`() {
        val draft = ProfileDraft.from(existingUnits = UnitSystem.METRIC, locale = Locale.US)
        assertEquals(UnitSystem.METRIC, draft.units)
    }

    // ── ProfileDraft apply ──────────────────────────────────────────────

    @Test
    fun `untouched draft fields overwrite profile fields to null`() {
        val profile = UserProfileEntity(
            name = "Alex", age = 41, sex = "male", heightCm = 180.0, weightKg = 82.0,
        )
        val updated = ProfileDraft(units = UnitSystem.METRIC).applyTo(profile)

        assertNull(updated.name)
        assertNull(updated.age)
        assertNull(updated.sex)
        assertNull(updated.heightCm)
        assertNull(updated.weightKg)
    }

    @Test
    fun `blank name trims to null and non-blank name is trimmed`() {
        val profile = UserProfileEntity()
        assertNull(ProfileDraft(name = "   ").applyTo(profile).name)
        assertEquals("Sam", ProfileDraft(name = "  Sam  ").applyTo(profile).name)
    }

    // ── ProfileDraft conversions ────────────────────────────────────────

    @Test
    fun `imperial height round-trips - 70 in is 177_8 cm`() {
        var draft = ProfileDraft(units = UnitSystem.IMPERIAL).settingHeight(70)
        assertEquals(177.8, draft.heightCm!!, 0.0001)
        assertEquals(70, draft.heightDisplayValue)

        draft = draft.settingHeight(null)
        assertNull(draft.heightCm)
        assertNull(draft.heightDisplayValue)
    }

    @Test
    fun `imperial weight round-trips - 165 lb is about 74_84 kg`() {
        var draft = ProfileDraft(units = UnitSystem.IMPERIAL).settingWeight(165)
        assertEquals(74.8427, draft.weightKg!!, 0.001)
        assertEquals(165, draft.weightDisplayValue)

        draft = draft.settingWeight(null)
        assertNull(draft.weightKg)
        assertNull(draft.weightDisplayValue)
    }

    @Test
    fun `metric height and weight pass through unconverted`() {
        val draft = ProfileDraft(units = UnitSystem.METRIC)
            .settingHeight(178)
            .settingWeight(75)
        assertEquals(178.0, draft.heightCm!!, 0.0001)
        assertEquals(75.0, draft.weightKg!!, 0.0001)
        assertEquals(178, draft.heightDisplayValue)
        assertEquals(75, draft.weightDisplayValue)
    }

    // ── GoalDraft ───────────────────────────────────────────────────────

    @Test
    fun `goal defaults are canonical in both unit systems`() {
        val metric = GoalDraft.from(units = UnitSystem.METRIC)
        assertEquals(10_000.0, metric.steps, 0.0001)
        assertEquals(8.0, metric.distance, 0.0001)
        assertEquals(500.0, metric.calories, 0.0001)
        assertEquals(45.0, metric.activeMinutes, 0.0001)
        assertEquals(8.0, metric.sleepHours, 0.0001)
        assertEquals(4.0, metric.workouts, 0.0001)

        val imperial = GoalDraft.from(units = UnitSystem.IMPERIAL)
        assertEquals(5.0, imperial.distance, 0.0001) // 8000 m ≈ 4.97 mi → 5.0 to one decimal
    }

    @Test
    fun `goal draft round-trips an existing goal`() {
        val goal = UserGoalEntity(
            steps = 12_000, distanceMeters = 10_000.0, calories = 650,
            sleepMinutes = 450, activeMinutes = 60, workoutsPerWeek = 5,
        )
        val draft = GoalDraft.from(goal, UnitSystem.METRIC)
        assertEquals(12_000.0, draft.steps, 0.0001)
        assertEquals(10.0, draft.distance, 0.0001)
        assertEquals(650.0, draft.calories, 0.0001)
        assertEquals(60.0, draft.activeMinutes, 0.0001)
        assertEquals(7.5, draft.sleepHours, 0.0001)
        assertEquals(5.0, draft.workouts, 0.0001)
    }

    @Test
    fun `apply writes all daily fields and converts distance to meters`() {
        val draft = GoalDraft.from(units = UnitSystem.IMPERIAL).copy(distance = 3.0, sleepHours = 7.5)
        val updated = draft.applyTo(UserGoalEntity(), UnitSystem.IMPERIAL, includeWeeklyWorkouts = true)

        assertEquals(10_000, updated.steps)
        assertEquals(3.0 * 1_609.344, updated.distanceMeters, 0.0001)
        assertEquals(500, updated.calories)
        assertEquals(45, updated.activeMinutes)
        assertEquals(450, updated.sleepMinutes)
        assertEquals(4, updated.workoutsPerWeek)
    }

    @Test
    fun `apply without weekly workouts preserves the stored workouts value`() {
        val goal = UserGoalEntity(workoutsPerWeek = 6)
        val draft = GoalDraft.from(units = UnitSystem.METRIC).copy(workouts = 2.0)
        val updated = draft.applyTo(goal, UnitSystem.METRIC, includeWeeklyWorkouts = false)

        assertEquals(6, updated.workoutsPerWeek)
    }
}

/** Plain in-memory SharedPreferences — no Robolectric needed. */
private class FakeSharedPreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals.add(key) }
        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            return true
        }

        override fun apply() { commit() }
    }
}
