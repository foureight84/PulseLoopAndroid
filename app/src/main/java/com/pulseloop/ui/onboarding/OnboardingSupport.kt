package com.pulseloop.ui.onboarding

import android.content.SharedPreferences
import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.data.entity.UserProfileEntity
import com.pulseloop.settings.UnitSystem
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Ported from OnboardingSupport.swift (iOS #48): the onboarding step model, the persisted
 * progress store, and the profile/goal editor drafts shared between onboarding and Settings.
 * Pure logic — no Compose — so it is unit-testable without Robolectric.
 */

/**
 * Ported from `LocalizedDecimalInput` (OnboardingSupport.swift, iOS #49).
 * Parses decimal-keyboard input without assuming one separator: European users commonly enter
 * `72,5` while other regions enter `72.5`, and Android keyboards follow the device locale.
 */
object LocalizedDecimalInput {
    fun parse(text: String, locale: Locale = Locale.getDefault()): Double? {
        var normalized = text.filterNot { it.isWhitespace() }

        val separator = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
        if (separator != '.') {
            normalized = normalized.replace(separator.toString(), ".")
        }
        normalized = normalized.replace(",", ".")

        if (normalized.isEmpty() || normalized.count { it == '.' } > 1) return null
        return normalized.toDoubleOrNull()
    }

    fun format(value: Double, locale: Locale = Locale.getDefault()): String {
        val formatter = java.text.NumberFormat.getNumberInstance(locale)
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 2
        formatter.isGroupingUsed = false
        return formatter.format(value)
    }
}

/** One step of the 5-step onboarding flow. Raw values match iOS for parity. */
enum class OnboardingStep(val rawValue: String) {
    WELCOME("welcome"),
    RING("ring"),
    PROFILE("profile"),
    GOALS("goals"),
    BASELINE("baseline");

    val index: Int get() = ordinal

    companion object {
        fun fromRawValue(raw: String): OnboardingStep? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Persists the onboarding navigation path so a killed app relaunches on the same step.
 * A path must start at [OnboardingStep.WELCOME]; anything empty/corrupt collapses to `[WELCOME]`.
 */
class OnboardingProgressStore(private val prefs: SharedPreferences) {

    fun loadPath(): List<OnboardingStep> {
        val raw = prefs.getString(STORAGE_KEY, null) ?: return listOf(OnboardingStep.WELCOME)
        val path = raw.split(SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull(OnboardingStep::fromRawValue)
        if (path.firstOrNull() != OnboardingStep.WELCOME) return listOf(OnboardingStep.WELCOME)
        return path
    }

    fun savePath(path: List<OnboardingStep>) {
        val safePath = if (path.firstOrNull() == OnboardingStep.WELCOME) path else listOf(OnboardingStep.WELCOME)
        prefs.edit().putString(STORAGE_KEY, safePath.joinToString(SEPARATOR) { it.rawValue }).apply()
    }

    fun clear() {
        prefs.edit().remove(STORAGE_KEY).apply()
    }

    companion object {
        const val STORAGE_KEY = "onboarding.route.v2"
        private const val SEPARATOR = ","
    }
}

/**
 * Editable profile draft backing [ProfileEditor]. Immutable (Compose-friendly): mutations
 * return copies. Unlike iOS, Android's [UserProfileEntity] has no units column — the caller
 * reads/writes the units preference through
 * [com.pulseloop.settings.ApiKeyStore.unitSystem] instead.
 */
data class ProfileDraft(
    val name: String = "",
    val age: Int? = null,
    val sex: String? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val units: UnitSystem = UnitSystem.METRIC,
) {
    /** Height in the display unit (cm or in), rounded like iOS `heightDisplayValue`. */
    val heightDisplayValue: Int?
        get() = heightCm?.let {
            if (units == UnitSystem.METRIC) it.roundToInt() else (it / CM_PER_INCH).roundToInt()
        }

    /** Weight in the display unit (kg or lb) — decimal like iOS `weightDisplayValue` (iOS #49). */
    val weightDisplayValue: Double?
        get() = weightKg?.let {
            if (units == UnitSystem.METRIC) it else it * LB_PER_KG
        }

    fun settingHeight(displayValue: Int?): ProfileDraft = copy(
        heightCm = displayValue?.let {
            if (units == UnitSystem.METRIC) it.toDouble() else it * CM_PER_INCH
        },
    )

    fun settingWeight(displayValue: Double?): ProfileDraft = copy(
        weightKg = displayValue?.let {
            if (units == UnitSystem.METRIC) it else it / LB_PER_KG
        },
    )

    /** Writes every draft field onto the entity (untouched draft fields overwrite to null). */
    fun applyTo(profile: UserProfileEntity): UserProfileEntity {
        val trimmed = name.trim()
        return profile.copy(
            name = trimmed.ifEmpty { null },
            age = age,
            sex = sex,
            heightCm = heightCm,
            weightKg = weightKg,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        const val CM_PER_INCH = 2.54
        const val LB_PER_KG = 2.2046226

        /** Imperial for US-measurement locales (US/LR/MM), metric otherwise — iOS `preferredUnits`. */
        fun preferredUnits(locale: Locale = Locale.getDefault()): UnitSystem = UnitSystem.fromLocale(locale)

        /** Draft from an existing profile; a stored units preference wins over the locale default. */
        fun from(
            profile: UserProfileEntity? = null,
            existingUnits: UnitSystem? = null,
            locale: Locale = Locale.getDefault(),
        ): ProfileDraft = ProfileDraft(
            name = profile?.name ?: "",
            age = profile?.age,
            sex = profile?.sex,
            heightCm = profile?.heightCm,
            weightKg = profile?.weightKg,
            units = existingUnits ?: preferredUnits(locale),
        )
    }
}

/**
 * Editable goal draft backing [GoalEditor]. Distance is held in *display units*
 * (km or mi to one decimal) and converted to meters on apply — exactly like iOS `GoalDraft`.
 */
data class GoalDraft(
    val steps: Double = 10_000.0,
    val distance: Double = 0.0,
    val calories: Double = 500.0,
    val activeMinutes: Double = 45.0,
    val sleepHours: Double = 8.0,
    val workouts: Double = 4.0,
) {
    fun applyTo(goal: UserGoalEntity, units: UnitSystem, includeWeeklyWorkouts: Boolean): UserGoalEntity =
        goal.copy(
            steps = steps.toInt(),
            distanceMeters = distance * metersPerUnit(units),
            calories = calories.toInt(),
            activeMinutes = activeMinutes.toInt(),
            sleepMinutes = (sleepHours * 60).toInt(),
            workoutsPerWeek = if (includeWeeklyWorkouts) workouts.toInt() else goal.workoutsPerWeek,
            updatedAt = System.currentTimeMillis(),
        )

    companion object {
        const val RECOMMENDED_DISTANCE_METERS = 8_000.0

        fun metersPerUnit(units: UnitSystem): Double =
            if (units == UnitSystem.METRIC) 1_000.0 else 1_609.344

        /** Draft from an existing goal (or the recommended defaults when none exists). */
        fun from(goal: UserGoalEntity? = null, units: UnitSystem): GoalDraft {
            val metersPerUnit = metersPerUnit(units)
            return if (goal != null) {
                GoalDraft(
                    steps = goal.steps.toDouble(),
                    distance = (goal.distanceMeters / metersPerUnit * 10).roundToLong() / 10.0,
                    calories = goal.calories.toDouble(),
                    activeMinutes = goal.activeMinutes.toDouble(),
                    sleepHours = goal.sleepMinutes / 60.0,
                    workouts = goal.workoutsPerWeek.toDouble(),
                )
            } else {
                GoalDraft(distance = (RECOMMENDED_DISTANCE_METERS / metersPerUnit * 10).roundToLong() / 10.0)
            }
        }
    }
}
