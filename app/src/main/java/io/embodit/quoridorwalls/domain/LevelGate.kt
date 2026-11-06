package io.embodit.quoridorwalls.domain

import kotlin.math.abs

/**
 * D9 (prompts/snippets/native-window-move.md §7 item 9): a window opened while the head is pitched opens pitched,
 * so every openWindowContainer first asks here and only opens once the head has been level (|pitch| <= 10°) for
 * 300 ms. Pure Kotlin: the caller feeds the time and the head pitch (degrees, + up; null when there is no valid
 * head pose). With no valid head pose for [noHeadMs] it opens anyway, so the window is never stuck.
 *
 * Quoridor opens its control window with Placement.right(board volume); the system places it beside the
 * gravity-aligned volume, so the gate mostly guards the reopen / "bring it back" paths.
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 1500L) {
    var pending: String? = null; private set
    private var askedMs = 0L
    private var levelSinceMs = -1L
    private var noHeadSinceMs = -1L
    val waiting: Boolean get() = pending != null

    fun ask(why: String, now: Long) {
        if (pending == null) askedMs = now
        pending = why
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun cancel() { pending = null; levelSinceMs = -1L; noHeadSinceMs = -1L }

    fun waitedMs(now: Long): Long = if (pending == null) 0L else now - askedMs

    /** Returns why to open now, or null to keep waiting. */
    fun step(now: Long, pitchDeg: Float?): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel()
            return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg) { levelSinceMs = -1L; return null }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        cancel()
        return why
    }

    fun hintWanted(pitchDeg: Float?): Boolean = pending != null && pitchDeg != null && abs(pitchDeg) > maxPitchDeg
}
