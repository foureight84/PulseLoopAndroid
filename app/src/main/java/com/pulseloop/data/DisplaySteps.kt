package com.pulseloop.data

import com.pulseloop.data.entity.ActivityDailyEntity

/**
 * The one place the step-source preference is applied to display values.
 *
 * Path B keeps phone and ring steps in separate columns on [ActivityDailyEntity]
 * (`phoneSteps` vs `steps`). Every screen, the widgets, notifications, the coach
 * context and the coach tools must pick between them the same way, from the
 * user's "Ring" / "Phone" preference. Doing that at each call site meant fifteen
 * places had to agree — and the coach disagreed with Today for two weeks
 * (review item 5, PR #98).
 *
 * This object is that place. Callers pass `preferPhone: Boolean` — resolved once
 * at the boundary (a ViewModel's `preferPhone()` lambda, `AppServices`' single
 * `StepSourcePrefs`), threaded down as a plain parameter. It does not read
 * SharedPreferences itself, so callers can control when the preference is
 * consulted (per emission, per composition) and tests can drive it directly.
 *
 * **Not for the exporter or the archive.** Those must serialize the ring's
 * actual `steps` column, not whatever the user has chosen to display. Passing
 * a `preferPhone` value into an export path would silently write phone steps
 * into the ring's column on the next restore.
 */
object DisplaySteps {

    /**
     * The step count a screen should show for [row] under the user's preference.
     *
     * Null-safe (a missing row has no count). Phone mode returns [ActivityDailyEntity.phoneSteps]
     * when it has one, else the ring's [ActivityDailyEntity.steps]; ring mode always returns
     * `steps`. That fallback is what makes an un-backfilled day still show something in Phone
     * mode instead of a spurious zero.
     */
    fun count(row: ActivityDailyEntity?, preferPhone: Boolean): Int? =
        row?.displaySteps(preferPhone)

    /**
     * The step counts for [rows], in the same order, under the user's preference.
     * Vector form of [count] — used by the 7-day hero series and week widgets.
     */
    fun counts(rows: List<ActivityDailyEntity>, preferPhone: Boolean): List<Int> =
        rows.map { it.displaySteps(preferPhone) }

    /**
     * [row] with its `steps` column projected to the display value, for callers
     * that hold a whole entity in their state (`ActivityViewModel.recentDays`,
     * the widget snapshot). Only `steps` is rewritten; `distanceMeters`,
     * `calories` and the rest of the row stay as the ring wrote them.
     */
    fun project(row: ActivityDailyEntity?, preferPhone: Boolean): ActivityDailyEntity? =
        row?.forDisplay(preferPhone)

    /** [rows] projected the same way, same order. */
    fun project(rows: List<ActivityDailyEntity>, preferPhone: Boolean): List<ActivityDailyEntity> =
        rows.map { it.forDisplay(preferPhone) }
}