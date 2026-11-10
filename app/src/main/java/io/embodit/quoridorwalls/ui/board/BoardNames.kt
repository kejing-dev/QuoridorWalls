package io.embodit.quoridorwalls.ui.board

import io.embodit.quoridorwalls.domain.Q

/**
 * Every entity name the scene sets comes from here (board-input README「命中回放」: never build a name inside
 * `Entity().apply {}` where `id` would be the ECS id). [BoardTargets.resolveName] is pure so the JVM test replays real logged paths.
 */
object BoardNames {
    fun square(cell: Int) = "sq:$cell"
    /** The visible gaze marker plate under a cell holder (GazeTargets: "mk:<holder>:<i>"). */
    fun marker(cell: Int, i: Int = 0) = "mk:${square(cell)}:$i"
    fun pawn(side: Int) = "pawn:$side"
    fun pawnModel(side: Int, skin: String) = "pawnModel:$side:$skin"
    fun wall(side: Int, k: Int) = "wall:$side:$k"
    fun wallModel(side: Int, k: Int, skin: String) = "wallModel:$side:$k:$skin"
    /** The tray block of [side] (click area behind its lying walls). */
    fun rail(side: Int) = "rail:$side"
    /** The slot target at crossing (c, r) (shown only with a wall in hand) and its visible stud. */
    fun slot(c: Int, r: Int) = "slot:$c:$r"
    fun slotMarker(c: Int, r: Int) = "mk:${slot(c, r)}:0"
    /** v0.1.3 and older logs: the single near rail. */
    const val RAIL = "rail"
    const val GHOST = "ghost"
    const val BOARD = "boardModel"
}

/** What a hit means to the game. */
sealed interface BoardTarget {
    data class Cell(val cell: Int) : BoardTarget
    data class Pawn(val side: Int) : BoardTarget
    /** A wall tray (a wall still lying in it, or the tray block) of [side]. */
    data class Rail(val side: Int) : BoardTarget
    /** A slot (crossing c, r) with a wall in hand. */
    data class Slot(val c: Int, val r: Int) : BoardTarget
    data object Ghost : BoardTarget
}

object BoardTargets {
    /**
     * Entity name -> target. [left] = walls still in each tray: a wall entity with k < left[side] lies in its tray (then it
     * is that tray); placed walls resolve to null (= nothing of ours, the template keeps looking up / gives null).
     * Indices out of range give null so a bad name shows up in the log instead of mapping somewhere wrong.
     */
    fun resolveName(name: String, left: List<Int> = listOf(Q.WALLS, Q.WALLS)): BoardTarget? {
        val f = name.split(":")
        fun int(i: Int) = f.getOrNull(i)?.toIntOrNull()
        return when (f[0]) {
            "sq" -> int(1)?.takeIf { it in 0 until Q.CELLS && f.size == 2 }?.let { BoardTarget.Cell(it) }
            "pawn" -> int(1)?.takeIf { it in 0..1 && f.size == 2 }?.let { BoardTarget.Pawn(it) }
            "wall" -> {
                val side = int(1); val k = int(2)
                if (f.size == 3 && side != null && side in 0..1 && k != null && k in 0 until left[side]) BoardTarget.Rail(side) else null
            }
            "rail" -> when {
                f.size == 1 -> BoardTarget.Rail(Q.YOU)
                f.size == 2 -> int(1)?.takeIf { it in 0..1 }?.let { BoardTarget.Rail(it) }
                else -> null
            }
            "slot" -> {
                val c = int(1); val r = int(2)
                if (f.size == 3 && c != null && r != null && c in 0..7 && r in 0..7) BoardTarget.Slot(c, r) else null
            }
            BoardNames.GHOST -> BoardTarget.Ghost.takeIf { f.size == 1 }
            else -> null
        }
    }

    /** Target -> the cell it means ([pawnCell] = where that pawn stands now); -1 = not a cell (tray, slot, ghost, nothing). */
    fun cellFor(t: BoardTarget?, pawnCell: (Int) -> Int): Int = when (t) {
        is BoardTarget.Cell -> t.cell
        is BoardTarget.Pawn -> pawnCell(t.side)
        else -> -1
    }

    /**
     * What the input log writes after `->`: `square=e2` for cells and pawns, `rail=0`, `slot=e3` (the crossing's notation
     * without orientation), `ghost`; `square=-` only for a genuine miss (null), which must stay 0 on the headset.
     */
    fun logOf(t: BoardTarget?, pawnCell: (Int) -> Int): String = when (t) {
        is BoardTarget.Cell, is BoardTarget.Pawn -> "square=" + Q.name(cellFor(t, pawnCell))
        is BoardTarget.Rail -> "rail=${t.side}"
        is BoardTarget.Slot -> "slot=" + slotName(t.c, t.r)
        BoardTarget.Ghost -> "ghost"
        null -> "square=-"
    }

    /** a1-h8 crossing name (the wall notation without h / v). */
    fun slotName(c: Int, r: Int) = "${'a' + c}${r + 1}"

    /** The name a target is logged / hovered as. */
    fun nameOf(t: BoardTarget?): String? = when (t) {
        is BoardTarget.Cell -> BoardNames.square(t.cell)
        is BoardTarget.Pawn -> BoardNames.pawn(t.side)
        is BoardTarget.Rail -> BoardNames.rail(t.side)
        is BoardTarget.Slot -> BoardNames.slot(t.c, t.r)
        BoardTarget.Ghost -> BoardNames.GHOST
        null -> null
    }
}
