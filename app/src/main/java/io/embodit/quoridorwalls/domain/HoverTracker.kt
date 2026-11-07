package io.embodit.quoridorwalls.domain

/**
 * Hover that lasts (design input.hover, board-input README「Hover 持续」): the highlight stays until a hover-exit or a
 * different target; when no exit ever comes it lapses [timeoutMs] after the last hover event, and every event refreshes
 * it. Hint only: never changes the game. Pure, so the "one event, still lit 1 s later" rule is unit tested.
 */
class HoverTracker(private val timeoutMs: Long = 1500L) {
    private var target: String? = null
    private var lastMs = 0L

    fun hover(name: String?, now: Long) {
        if (name == null) { exit(); return }
        target = name; lastMs = now
    }

    fun exit() { target = null }

    fun current(now: Long): String? = target?.takeIf { now - lastMs < timeoutMs }
}
