package com.pulseloop.diagnostics

/**
 * Keeps one capture table (`raw_packets` or `wearable_logs`) to its newest [limit] rows.
 *
 * Both tables are written for every packet a verbose driver sends or receives, and both ride in
 * the data archive, so without a bound they grow for the life of the install. Trimming runs every
 * [trimEvery] inserts rather than after each one, keeping a DELETE off the per-packet path, and
 * once on the first insert so a table that grew before this existed is cut down at startup.
 */
class DiagnosticsRetention(
    private val limit: Int,
    private val trimEvery: Int = DEFAULT_TRIM_EVERY,
    private val trim: suspend (keep: Int) -> Unit,
) {
    private var insertsSinceTrim = trimEvery

    /** Call after each insert. Not thread-safe: the diagnostics subscriber is a single collector. */
    suspend fun inserted() {
        if (++insertsSinceTrim < trimEvery) return
        insertsSinceTrim = 0
        trim(limit)
    }

    companion object {
        const val RAW_PACKET_LIMIT = 1_000
        const val WEARABLE_LOG_LIMIT = 2_000
        const val DEFAULT_TRIM_EVERY = 50
    }
}
