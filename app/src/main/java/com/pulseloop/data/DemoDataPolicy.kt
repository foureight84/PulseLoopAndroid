package com.pulseloop.data

/**
 * How readers treat seeded demo rows now that a connect deletes nothing (PR #52).
 *
 * Before #52, `EventPersistenceSubscriber` purged demo rows on every CONNECTED transition, so
 * "demo rows and a paired ring's real rows in the same table" was a state that only existed for
 * the few seconds between a reseed and the next reconnect. Removing that purge — correctly, it
 * was wiping seeded data every ~5 minutes under a paired R10 — makes the mixed state permanent,
 * and "Reseed Demo Data" ships in Settings → Privacy & Data, so any user can reach it.
 *
 * The rule, mirroring iOS (which detects the mix rather than cleaning it up): **real data wins.**
 * A reader surfaces demo rows only while the corresponding real series is empty. As soon as a ring
 * has synced anything for that series, demo rows are excluded rather than blended in — blending
 * is what produced 13-hour "nights" (demo night + real night collapsed onto one date), demo-fed
 * resting-HR baselines, and demo-inflated calorie estimates written onto real rows.
 *
 * Derived values that are persisted or exported (`hrRestingBaseline`, `estimatedActiveCalories`,
 * Health Connect records) go further: they read the `*Real` queries unconditionally, since a
 * demo-derived number outlives the demo data that produced it.
 */
object DemoDataPolicy {
    /** Every `source` / `sourceRaw` value that marks a row as seeded rather than synced. */
    val DEMO_SOURCES = setOf("demo", "mock")

    /**
     * The seeder writes `"demo"`; `"mock"` is the iOS spelling and is accepted alongside it.
     * Checking only one of the two is how `MetricsService.isDemo` silently reported seeded data
     * as live.
     */
    fun isDemo(source: String?): Boolean = source != null && source in DEMO_SOURCES
}
