package io.embodit.quoridorwalls.domain

import kotlin.random.Random

/**
 * The three AI levels (design gameSpec.ai): negamax + alpha-beta + iterative deepening, cut to Quoridor.
 *
 *  EASY   depth 1; steps are picked at random among the best 3; walls only when you are <= 4 steps from row 9, and then
 *         half the time it does not even look (B27: following your gold band beats it).
 *  MEDIUM depth 2 / 300 ms; candidate walls = the walls that cut your shortest path (+ the ones beside your pawn).
 *  HARD   depth 3 / 800 ms; same candidates, more of them. Depth 4 was tried and plays worse here (17/30 against MEDIUM
 *         vs 21/30 at depth 3, horizon effect of walls at an even depth), so the design's "时间够就深 4" is left out.
 *
 * Evaluation (design ai.eval): (their distance - my distance) x 10 + (my walls - their walls) x 2 + progress (my
 * distance counts once more, so two careful sides never stall) and +-1000 for a finished game. The distance is
 * [Board.dist], the same BFS the legality check uses.
 */
object Engine {
    const val EASY = 1
    const val MEDIUM = 2
    const val HARD = 3
    val LEVELS = listOf(EASY, MEDIUM, HARD)
    private const val WIN = 1000

    data class Limits(val depth: Int, val timeMs: Long, val maxWalls: Int, val maxNodes: Int)

    fun limits(level: Int): Limits = when (level) {
        EASY -> Limits(1, 200, 0, 50_000)
        MEDIUM -> Limits(2, 300, 10, 60_000)
        else -> Limits(3, 800, 16, 400_000)
    }

    /** Result of a search, for logs / tests. */
    data class Choice(val move: Move, val score: Int, val depth: Int, val nodes: Int, val ms: Long)

    fun eval(b: Board, side: Int): Int {
        val me = b.dist(side); val them = b.dist(Q.other(side))
        return (them - me) * 10 + (b.left[side] - b.left[Q.other(side)]) * 2 - me
    }

    /**
     * Candidate walls for [side] (the one setting it): walls that cut an edge of the opponent's current shortest path,
     * plus (hard) the walls around the opponent's pawn. Legal ones only, best first by how much they slow the opponent
     * down compared with how much they cost us; at most [max].
     */
    fun candidateWalls(b: Board, side: Int, max: Int, aroundPawn: Boolean): List<Wall> {
        if (b.left[side] <= 0 || max <= 0) return emptyList()
        val opp = Q.other(side)
        val path = b.path(opp)
        val set = LinkedHashSet<Wall>()
        for (i in 0 until path.size - 1) addCutting(path[i], path[i + 1], set)
        if (aroundPawn) {
            val p = b.pawn[opp]; val pc = Q.col(p); val pr = Q.row(p)
            for (c in pc - 1..pc) for (r in pr - 1..pr) if (c in 0..7 && r in 0..7) { set += Wall(c, r, Orient.H); set += Wall(c, r, Orient.V) }
        }
        val d0Opp = path.size - 1; val d0Me = b.dist(side)
        val scored = ArrayList<Pair<Wall, Int>>()
        for (w in set) {
            if (b.wallFitFail(side, w) != null) continue
            b.set(w, true)
            val dOpp = b.dist(opp); val dMe = b.dist(side)
            b.set(w, false)
            if (dOpp < 0 || dMe < 0) continue
            val gain = (dOpp - d0Opp) * 2 - (dMe - d0Me)
            if (dOpp > d0Opp || gain > 0) scored += w to gain
        }
        scored.sortWith(compareByDescending<Pair<Wall, Int>> { it.second }.thenBy { it.first.index })
        return scored.take(max).map { it.first }
    }

    /** The two walls that can cut the edge a-b. */
    private fun addCutting(a: Int, b: Int, out: MutableSet<Wall>) {
        val ca = Q.col(a); val ra = Q.row(a); val cb = Q.col(b); val rb = Q.row(b)
        if (ca == cb) {
            val r = minOf(ra, rb)
            for (c in listOf(ca - 1, ca)) if (c in 0..7 && r in 0..7) out += Wall(c, r, Orient.H)
        } else {
            val c = minOf(ca, cb)
            for (r in listOf(ra - 1, ra)) if (c in 0..7 && r in 0..7) out += Wall(c, r, Orient.V)
        }
    }

    private class Search(val deadline: Long, val maxNodes: Int, val clock: () -> Long, val lim: Limits, val aroundPawn: Boolean) {
        var nodes = 0
        var aborted = false

        fun moves(b: Board, side: Int): List<Move> {
            val steps = b.steps(side).sortedBy { s -> b.pawn[side] = s.to; val d = b.dist(side); b.pawn[side] = s.from; d }
            return steps + candidateWalls(b, side, lim.maxWalls, aroundPawn).map { Move.Place(it) }
        }

        fun negamax(b: Board, side: Int, depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
            nodes++
            if (nodes and 255 == 0 && (clock() > deadline || nodes > maxNodes)) aborted = true
            if (aborted) return 0
            b.winner()?.let { w -> return if (w == side) WIN - ply else -(WIN - ply) }
            if (depth == 0) return eval(b, side)
            var alpha = alphaIn
            var best = -WIN * 2
            for (m in moves(b, side)) {
                b.play(side, m)
                val v = -negamax(b, Q.other(side), depth - 1, -beta, -alpha, ply + 1)
                b.unplay(side, m)
                if (aborted) return 0
                if (v > best) best = v
                if (v > alpha) alpha = v
                if (alpha >= beta) break
            }
            return best
        }
    }

    /**
     * Best move for [side] at [level]. [rng] is only used by EASY (and to break exact ties the same way every time).
     * [clock] / time limit make the app responsive; tests pass a frozen clock so only the node budget counts.
     */
    fun choose(pos: Pos, side: Int, level: Int, rng: Random, clock: () -> Long = { System.nanoTime() / 1_000_000 }, timeScale: Float = 1f, depthOverride: Int? = null): Choice {
        val t0 = clock()
        val b = pos.board(side)
        if (level <= EASY) return easy(b, side, rng, t0, clock)
        val lim = limits(level)
        val s = Search(t0 + (lim.timeMs * timeScale).toLong(), lim.maxNodes, clock, lim, aroundPawn = level >= HARD)
        var bestMove: Move? = null
        var bestScore = 0
        var reached = 0
        val root = s.moves(b, side)
        // a step that wins at once
        root.firstOrNull { it is Move.Step && Q.row(it.to) == Q.goalRow(side) }?.let { return Choice(it, WIN, 0, 1, clock() - t0) }
        var ordered = root
        var lastScores: Map<Move, Int> = emptyMap()
        val maxDepth = depthOverride ?: lim.depth
        for (depth in 1..maxDepth) {
            var alpha = -WIN * 2
            var iterBest: Move? = null
            val scores = HashMap<Move, Int>()
            for (m in ordered) {
                b.play(side, m)
                // a full window for ties: moves equal to the best keep their exact score (alpha - 1)
                val v = -s.negamax(b, Q.other(side), depth - 1, -WIN * 2, -(alpha - 1), 1)
                b.unplay(side, m)
                if (s.aborted) break
                scores[m] = v
                if (iterBest == null || v > alpha) { alpha = v; iterBest = m }
            }
            if (s.aborted && depth > 1) break
            if (iterBest != null) { bestMove = iterBest; bestScore = alpha; reached = depth; lastScores = scores }
            ordered = ordered.sortedByDescending { scores[it] ?: Int.MIN_VALUE }
            if (s.aborted) break
            if (level == MEDIUM && depth >= 2 && depthOverride == null) break
            if (level == HARD && depth >= 3 && clock() - t0 > lim.timeMs * timeScale / 3) break
        }
        // ties: a step that brings us closer beats a wall (walls are scarce), then the seeded rng picks (design: fixed seeds)
        val ties = lastScores.filter { it.value == bestScore }.keys.toList()
        if (ties.size > 1) {
            fun key(m: Move): Int = if (m is Move.Step) { b.pawn[side] = m.to; val d = b.dist(side); b.pawn[side] = m.from; d } else 100
            val bestKey = ties.minOf { key(it) }
            val pool = ties.filter { key(it) == bestKey }.sortedBy { it.toString() }
            bestMove = pool[rng.nextInt(pool.size)]
        }
        return Choice(bestMove ?: root.first(), bestScore, reached, s.nodes, clock() - t0)
    }

    private fun easy(b: Board, side: Int, rng: Random, t0: Long, clock: () -> Long): Choice {
        val steps = b.steps(side)
        steps.firstOrNull { Q.row(it.to) == Q.goalRow(side) }?.let { return Choice(it, WIN, 1, 1, clock() - t0) }
        val scored = steps.map { s -> b.play(side, s); val v = eval(b, side); b.unplay(side, s); s to v }.sortedByDescending { it.second }
        val oppDist = b.dist(Q.other(side))
        if (oppDist <= 4 && b.left[side] > 0 && rng.nextBoolean()) {
            val walls = candidateWalls(b, side, 6, aroundPawn = false)
            val best = walls.map { w -> b.set(w, true); b.left[side]--; val v = eval(b, side); b.set(w, false); b.left[side]++; w to v }.maxByOrNull { it.second }
            if (best != null && best.second > (scored.firstOrNull()?.second ?: Int.MIN_VALUE)) return Choice(Move.Place(best.first), best.second, 1, walls.size, clock() - t0)
        }
        val top = scored.take(3)
        val pick = top[rng.nextInt(top.size)]
        return Choice(pick.first, pick.second, 1, steps.size, clock() - t0)
    }
}
