package io.embodit.quoridorwalls.domain

/**
 * Quoridor rules (Gigamic official rules, design gameSpec.specialRules). Pure Kotlin, no SDK types.
 *
 * Cells: c = column 0..8 (a..i, left to right), r = row 0..8 (row 1 near = your home row, row 9 far). cell = r * 9 + c.
 * You (side 0) start on e1 and win on row 9; the AI (side 1) starts on e9 and wins on row 1.
 * Wall centres: the 8 x 8 groove crossings, (c, r) in 0..7 = the far-right corner of cell (c, r). A horizontal wall at (c, r)
 * blocks the edges between rows r and r + 1 for columns c and c + 1; a vertical one blocks the edges between columns c
 * and c + 1 for rows r and r + 1. Notation `e3h` = centre at the far-right corner of e3, horizontal (design dataModel.notation).
 */
object Q {
    const val N = 9
    const val CELLS = 81
    const val YOU = 0
    const val AI = 1
    const val WALLS = 10
    val START = intArrayOf(cell(4, 0), cell(4, 8))

    fun other(side: Int) = 1 - side
    fun cell(c: Int, r: Int) = r * N + c
    fun col(cell: Int) = cell % N
    fun row(cell: Int) = cell / N
    fun goalRow(side: Int) = if (side == YOU) N - 1 else 0
    fun name(cell: Int) = "${'a' + col(cell)}${row(cell) + 1}"

    fun parseCell(s: String): Int? {
        if (s.length != 2) return null
        val c = s[0] - 'a'; val r = s[1] - '1'
        return if (c in 0..8 && r in 0..8) cell(c, r) else null
    }

    fun parseWall(s: String): Wall? {
        if (s.length != 3) return null
        val c = s[0] - 'a'; val r = s[1] - '1'
        val o = when (s[2]) { 'h' -> Orient.H; 'v' -> Orient.V; else -> return null }
        return if (c in 0..7 && r in 0..7) Wall(c, r, o) else null
    }
}

enum class Orient { H, V; fun flip() = if (this == H) V else H }

/** A wall centred on groove crossing (c, r). */
data class Wall(val c: Int, val r: Int, val o: Orient) {
    val index: Int get() = (if (o == Orient.H) 0 else 64) + r * 8 + c
    val notation: String get() = "${'a' + c}${r + 1}${if (o == Orient.H) 'h' else 'v'}"
    override fun toString() = notation

    companion object {
        val ALL: List<Wall> = Orient.entries.flatMap { o -> (0..7).flatMap { r -> (0..7).map { c -> Wall(c, r, o) } } }
        fun ofIndex(i: Int) = Wall(i % 8, (i % 64) / 8, if (i < 64) Orient.H else Orient.V)
    }
}

/** A wall on the board and who set it (its home rail when it is undone). */
data class Placed(val wall: Wall, val side: Int)

sealed interface Move {
    /** A pawn step to [to]; [jump] when it hops over the other pawn (straight or diagonal). */
    data class Step(val from: Int, val to: Int, val jump: Boolean) : Move {
        override fun toString() = Q.name(to)
    }

    data class Place(val wall: Wall) : Move {
        override fun toString() = wall.notation
    }
}

/** Why a wall can't go into a slot (design S07 / S08 one-liners). */
enum class WallFail { NO_WALLS, ALREADY, OVERLAP, CROSS, BLOCKS_THEM, BLOCKS_YOU }

/** Why a tapped cell is not a legal step (design T2). */
enum class StepFail { WALL, DIAGONAL, THEIRS, FAR }

/** An immutable position: both pawns, the placed walls (in order), walls left per side. */
data class Pos(
    val pawns: List<Int> = listOf(Q.START[0], Q.START[1]),
    val walls: List<Placed> = emptyList(),
    val left: List<Int> = listOf(Q.WALLS, Q.WALLS),
) {
    fun board(toMove: Int = Q.YOU): Board = Board.of(this, toMove)

    fun winner(): Int? = when {
        Q.row(pawns[Q.YOU]) == Q.goalRow(Q.YOU) -> Q.YOU
        Q.row(pawns[Q.AI]) == Q.goalRow(Q.AI) -> Q.AI
        else -> null
    }

    fun apply(side: Int, m: Move): Pos = when (m) {
        is Move.Step -> copy(pawns = pawns.toMutableList().also { it[side] = m.to })
        is Move.Place -> copy(walls = walls + Placed(m.wall, side), left = left.toMutableList().also { it[side]-- })
    }

    companion object { val START = Pos() }
}

/**
 * Mutable board for the rules and the engine: two pawns, 64 + 64 wall flags, walls left, side to move. One BFS is the
 * shortest-path function for legality, the light bands and the AI evaluation (design ai.eval: shared function).
 */
class Board private constructor(
    val pawn: IntArray,
    val h: BooleanArray,
    val v: BooleanArray,
    val left: IntArray,
    var toMove: Int,
) {
    private val queue = IntArray(Q.CELLS)
    private val seen = IntArray(Q.CELLS)
    private var stamp = 0
    private val parent = IntArray(Q.CELLS)

    fun copy() = Board(pawn.copyOf(), h.copyOf(), v.copyOf(), left.copyOf(), toMove)

    fun hasWall(w: Wall) = if (w.o == Orient.H) h[w.r * 8 + w.c] else v[w.r * 8 + w.c]

    private fun hAt(c: Int, r: Int) = c in 0..7 && r in 0..7 && h[r * 8 + c]
    private fun vAt(c: Int, r: Int) = c in 0..7 && r in 0..7 && v[r * 8 + c]

    /** Is the edge from [cell] one step in direction [d] (0 far / +r, 1 right / +c, 2 near / -r, 3 left / -c) open and on the board? */
    fun open(cell: Int, d: Int): Boolean {
        val c = Q.col(cell); val r = Q.row(cell)
        return when (d) {
            0 -> r < 8 && !hAt(c, r) && !hAt(c - 1, r)
            1 -> c < 8 && !vAt(c, r) && !vAt(c, r - 1)
            2 -> r > 0 && !hAt(c, r - 1) && !hAt(c - 1, r - 1)
            else -> c > 0 && !vAt(c - 1, r) && !vAt(c - 1, r - 1)
        }
    }

    /** Is there a wall (not the board edge) between [cell] and its neighbour in direction [d]? */
    fun wallBetween(cell: Int, d: Int): Boolean = inBoard(cell, d) && !open(cell, d)

    private fun inBoard(cell: Int, d: Int): Boolean {
        val c = Q.col(cell); val r = Q.row(cell)
        return when (d) { 0 -> r < 8; 1 -> c < 8; 2 -> r > 0; else -> c > 0 }
    }

    fun step(cell: Int, d: Int): Int = when (d) { 0 -> cell + Q.N; 1 -> cell + 1; 2 -> cell - Q.N; else -> cell - 1 }

    /** Legal pawn steps of [side] (four neighbours, straight jump, diagonal jumps). */
    fun steps(side: Int): List<Move.Step> {
        val out = ArrayList<Move.Step>(5)
        val from = pawn[side]
        val opp = pawn[Q.other(side)]
        for (d in 0..3) {
            if (!open(from, d)) continue
            val n = step(from, d)
            if (n != opp) { out += Move.Step(from, n, false); continue }
            if (open(n, d)) { out += Move.Step(from, step(n, d), true); continue }
            for (d2 in intArrayOf((d + 1) % 4, (d + 3) % 4)) if (open(n, d2)) out += Move.Step(from, step(n, d2), true)
        }
        return out
    }

    /** Why [to] is not a legal step for [side] (null = it is legal). */
    fun stepFail(side: Int, to: Int): StepFail? {
        if (steps(side).any { it.to == to }) return null
        val from = pawn[side]
        if (to == pawn[Q.other(side)]) return StepFail.THEIRS
        val dc = Q.col(to) - Q.col(from); val dr = Q.row(to) - Q.row(from)
        if (kotlin.math.abs(dc) + kotlin.math.abs(dr) == 1) return StepFail.WALL
        if (kotlin.math.abs(dc) == 1 && kotlin.math.abs(dr) == 1) return StepFail.DIAGONAL
        return StepFail.FAR
    }

    /** Overlap / crossing / walls-left checks only (no path search). */
    fun wallFitFail(side: Int, w: Wall): WallFail? {
        if (left[side] <= 0) return WallFail.NO_WALLS
        if (h[w.r * 8 + w.c] && w.o == Orient.H || v[w.r * 8 + w.c] && w.o == Orient.V) return WallFail.ALREADY
        if (w.o == Orient.H && (hAt(w.c - 1, w.r) || hAt(w.c + 1, w.r))) return WallFail.OVERLAP
        if (w.o == Orient.V && (vAt(w.c, w.r - 1) || vAt(w.c, w.r + 1))) return WallFail.OVERLAP
        if (w.o == Orient.H && v[w.r * 8 + w.c] || w.o == Orient.V && h[w.r * 8 + w.c]) return WallFail.CROSS
        return null
    }

    /** Full check (design specialRules): fits, and both sides still have a way to their goal row. [side] sets it. */
    fun wallFail(side: Int, w: Wall): WallFail? {
        wallFitFail(side, w)?.let { return it }
        set(w, true)
        val them = dist(Q.other(side)); val me = dist(side)
        set(w, false)
        return when {
            them < 0 -> WallFail.BLOCKS_THEM
            me < 0 -> WallFail.BLOCKS_YOU
            else -> null
        }
    }

    fun set(w: Wall, on: Boolean) { if (w.o == Orient.H) h[w.r * 8 + w.c] = on else v[w.r * 8 + w.c] = on }

    fun legalWalls(side: Int): List<Wall> = Wall.ALL.filter { wallFail(side, it) == null }

    fun legalMoves(side: Int): List<Move> = steps(side) + legalWalls(side).map { Move.Place(it) }

    fun play(side: Int, m: Move) {
        when (m) {
            is Move.Step -> pawn[side] = m.to
            is Move.Place -> { set(m.wall, true); left[side]-- }
        }
    }

    fun unplay(side: Int, m: Move) {
        when (m) {
            is Move.Step -> pawn[side] = m.from
            is Move.Place -> { set(m.wall, false); left[side]++ }
        }
    }

    fun winner(): Int? = when {
        Q.row(pawn[Q.YOU]) == Q.goalRow(Q.YOU) -> Q.YOU
        Q.row(pawn[Q.AI]) == Q.goalRow(Q.AI) -> Q.AI
        else -> null
    }

    /**
     * Shortest number of steps from [side]'s pawn to its goal row, walls only (pawns ignored, the usual Quoridor measure);
     * -1 when walled off. The one BFS used by legality, the bands and the AI.
     */
    fun dist(side: Int): Int = bfs(side, false)

    /** The cells of one shortest path (pawn first, goal-row cell last); empty when walled off. Ties prefer straight ahead. */
    fun path(side: Int): List<Int> {
        val goal = bfs(side, true)
        if (goal < 0) return emptyList()
        val out = ArrayList<Int>()
        var c = lastGoal
        while (c != -1) { out += c; c = parent[c] }
        return out.reversed()
    }

    private var lastGoal = -1

    private fun bfs(side: Int, keepParents: Boolean): Int {
        stamp++
        if (stamp == Int.MAX_VALUE) { seen.fill(0); stamp = 1 }
        val start = pawn[side]
        val goalRow = Q.goalRow(side)
        if (Q.row(start) == goalRow) { lastGoal = start; parent[start] = -1; return 0 }
        val fwd = if (side == Q.YOU) 0 else 2
        val order = intArrayOf(fwd, 1, 3, (fwd + 2) % 4)
        var head = 0; var tail = 0
        queue[tail++] = start; seen[start] = stamp; parent[start] = -1
        var depth = 0
        while (head < tail) {
            val levelEnd = tail
            depth++
            while (head < levelEnd) {
                val cur = queue[head++]
                for (d in order) {
                    if (!open(cur, d)) continue
                    val n = step(cur, d)
                    if (seen[n] == stamp) continue
                    seen[n] = stamp
                    if (keepParents) parent[n] = cur
                    if (Q.row(n) == goalRow) { lastGoal = n; return depth }
                    queue[tail++] = n
                }
            }
        }
        return -1
    }

    companion object {
        fun of(p: Pos, toMove: Int): Board {
            val b = Board(intArrayOf(p.pawns[0], p.pawns[1]), BooleanArray(64), BooleanArray(64), intArrayOf(p.left[0], p.left[1]), toMove)
            for (w in p.walls) b.set(w.wall, true)
            return b
        }
    }
}
