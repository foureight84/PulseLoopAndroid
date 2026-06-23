package com.pulseloop.coach.orchestration

/**
 * Ported from CoachTraceEvent.swift.
 * In-process progress event emitted by the orchestrator.
 * The view model collects these to show "Reading today's data…" style status
 * while a turn runs. Note: CoachToolCallTrace already defined in CoachOrchestrator.
 */
data class CoachTraceEvent(
    val label: String,
    val status: TraceStatus,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class TraceStatus {
        THINKING,
        RUNNING_TOOL,
        COMPLETED_TOOL,
        FAILED_TOOL,
        WRITING_ANSWER,
        DONE,
    }

    val statusLabel: String get() = when (status) {
        TraceStatus.THINKING -> "Thinking…"
        TraceStatus.RUNNING_TOOL -> "Running $toolName…"
        TraceStatus.COMPLETED_TOOL -> "Done: $toolName"
        TraceStatus.FAILED_TOOL -> "Failed: $toolName"
        TraceStatus.WRITING_ANSWER -> "Writing answer…"
        TraceStatus.DONE -> "Done"
    }
}
