package com.pulseloop.service

import com.pulseloop.data.entity.SleepStageBlockEntity

/**
 * Splits a waking-day's sleep into distinct sessions — the main night vs. daytime naps —
 * so each can surface as its own Day-view carousel page. Pure and testable; the Room-facing
 * reconciliation lives in [com.pulseloop.service.EventPersistenceSubscriber].
 *
 * Ported from iOS `SleepSegmentation.swift` (PR #83 / issue #59).
 */
object SleepSegmentation {
    /**
     * A run of >= this many minutes with no recorded sleep data marks a boundary between two
     * sessions. The ring writes a contiguous per-minute block for each timeline packet, so short
     * mid-night awakenings stay in one session while a nap hours after the night (a genuine gap
     * in the blocks) splits into its own.
     */
    const val SESSION_GAP_MINUTES = 60
    private const val GAP_MILLIS = SESSION_GAP_MINUTES * 60_000L

    /**
     * Group blocks into chronological sessions, splitting wherever the gap from one block's end to
     * the next block's start is >= [SESSION_GAP_MINUTES]. Input need not be sorted. Returns
     * time-ordered groups; empty in -> empty out.
     */
    fun segment(blocks: List<SleepStageBlockEntity>): List<List<SleepStageBlockEntity>> {
        val sorted = blocks.sortedBy { it.startAt }
        val first = sorted.firstOrNull() ?: return emptyList()

        val groups = mutableListOf<List<SleepStageBlockEntity>>()
        var current = mutableListOf(first)
        var prevEnd = first.startAt + first.durationMinutes * 60_000L

        for (block in sorted.drop(1)) {
            if (block.startAt - prevEnd >= GAP_MILLIS) {
                groups.add(current)
                current = mutableListOf(block)
            } else {
                current.add(block)
            }
            // maxOf guards against nested/overlapping blocks pulling the running end backwards.
            prevEnd = maxOf(prevEnd, block.startAt + block.durationMinutes * 60_000L)
        }
        groups.add(current)
        return groups
    }
}
