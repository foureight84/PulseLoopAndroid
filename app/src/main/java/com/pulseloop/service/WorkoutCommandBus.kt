package com.pulseloop.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide channel for workout commands originating outside the composition (the foreground
 * service's notification actions) — the Android analog of iOS's WorkoutAppGroup command store,
 * whose PauseWorkoutIntent/ResumeWorkoutIntent/FinishWorkoutIntent the Live Activity posts into
 * and `LiveWorkoutManager.applyPendingCommand()` dispatches from.
 *
 * The service posts; the manager (which owns the real pause/resume/finish logic) applies. Without
 * this, the notification actions only mutated the notification's own label — Stop hid the card
 * while the workout silently kept recording (second-pass review finding #27).
 */
object WorkoutCommandBus {
    const val COMMAND_PAUSE = "pause"
    const val COMMAND_RESUME = "resume"
    const val COMMAND_FINISH = "finish"

    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val commands: SharedFlow<String> = _commands.asSharedFlow()

    /** Fire-and-forget; a command with no active workout is ignored by the collector. */
    fun post(command: String) {
        _commands.tryEmit(command)
    }
}
