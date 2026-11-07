package io.embodit.quoridorwalls.ui.board

import io.embodit.quoridorwalls.domain.Q

/**
 * Every entity name the scene sets comes from here (board-input README「命中回放」: never build a name inside
 * `Entity().apply {}` where `id` would be the ECS id). [resolveName] is pure so the JVM test replays real logged paths.
 */
object BoardNames {
    fun square(cell: Int) = "sq:$cell"
    /** The visible gaze marker plate under a cell holder (GazeTargets: "mk:<holder>:<i>"). */
    fun marker(cell: Int, i: Int = 0) = "mk:${square(cell)}:$i"
    fun pawn(side: Int) = "pawn:$side"
    fun pawnModel(side: Int, skin: String) = "pawnModel:$side:$skin"
    fun wall(side: Int, k: Int) = "wall:$side:$k"
    fun wallModel(side: Int, k: Int, skin: String) = "wallModel:$side:$k:$skin"
    const val RAIL = "rail"
    const val GHOST = "ghost"
    const val BOARD = "boardModel"
}

/** What a hit means to the game. */
sealed interface BoardTarget {
    data class Cell(val cell: Int) : BoardTarget
    data class Pawn(val side: Int) : BoardTarget
    data object Rail : BoardTarget
    data object Ghost : BoardTarget
}

object BoardTargets {
    /**
     * Entity name -> target. [yourWallsLeft] decides whether a wall entity of yours still lies in the rail (then it is the
     * rail); placed walls and the far rail resolve to null (= nothing of ours, the template keeps looking up / gives null).
     * Indices out of range give null so a bad name shows up in the log instead of mapping somewhere wrong.
     */
    fun resolveName(name: String, yourWallsLeft: Int = Q.WALLS): BoardTarget? {
        val f = name.split(":")
        return when (f[0]) {
            "sq" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until Q.CELLS && f.size == 2 }?.let { BoardTarget.Cell(it) }
            "pawn" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..1 && f.size == 2 }?.let { BoardTarget.Pawn(it) }
            "wall" -> {
                val side = f.getOrNull(1)?.toIntOrNull(); val k = f.getOrNull(2)?.toIntOrNull()
                if (f.size == 3 && side == Q.YOU && k != null && k in 0 until yourWallsLeft) BoardTarget.Rail else null
            }
            BoardNames.RAIL -> BoardTarget.Rail
            BoardNames.GHOST -> BoardTarget.Ghost
            else -> null
        }
    }

    /** Target -> the cell it means ([pawnCell] = where that pawn stands now); -1 = not a cell (rail, ghost, nothing). */
    fun cellFor(t: BoardTarget?, pawnCell: (Int) -> Int): Int = when (t) {
        is BoardTarget.Cell -> t.cell
        is BoardTarget.Pawn -> pawnCell(t.side)
        else -> -1
    }

    /** The name a target is logged / hovered as. */
    fun nameOf(t: BoardTarget?): String? = when (t) {
        is BoardTarget.Cell -> BoardNames.square(t.cell)
        is BoardTarget.Pawn -> BoardNames.pawn(t.side)
        BoardTarget.Rail -> BoardNames.RAIL
        BoardTarget.Ghost -> BoardNames.GHOST
        null -> null
    }
}
