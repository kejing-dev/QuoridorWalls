package io.embodit.quoridorwalls.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * design acceptance.mustPass M2 (gameSpec.ai.strengthTest, A12): fixed seeds, 100 games each, both sides first 50 times.
 *  - beginner vs "only follow the shortest path, never set a wall": beginner wins <= 50 % (B27: follow your gold path and win)
 *  - beginner vs random legal moves: >= 80 % (it is not a random mover)
 *  - expert vs beginner: >= 90 %
 *  - expert move time on the JVM (the emulator / headset times are logged in the app as "AI ... ms").
 * The engine runs with a frozen clock, so only its node budget limits it and the games are reproducible.
 */
class AiStrengthTest {
    private val frozen: () -> Long = { 0L }

    private fun interface Player { fun move(p: Pos, side: Int, ply: Int): Move }

    private fun engine(level: Int, seed: Int) = Player { p, side, ply -> Engine.choose(p, side, level, Random(seed * 1000 + ply), frozen).move }

    private val pathFollower = Player { p, side, _ ->
        val b = p.board(side)
        val path = b.path(side)
        val steps = b.steps(side)
        steps.firstOrNull { path.size > 1 && it.to == path[1] }
            ?: steps.minBy { s -> b.pawn[side] = s.to; val d = b.dist(side); b.pawn[side] = s.from; d }
    }

    private fun randomMover(seed: Int) = Player { p, side, ply ->
        val moves = p.board(side).legalMoves(side)
        moves[Random(seed * 7919 + ply).nextInt(moves.size)]
    }

    /** Plays one game; returns the winner (or null after 200 plies). */
    private fun play(a: Player, b: Player, aFirst: Boolean): Int? {
        // a always plays side 0 (you) and b side 1; who moves first alternates
        var p = Pos.START
        var side = if (aFirst) Q.YOU else Q.AI
        for (ply in 0 until 200) {
            val m = (if (side == Q.YOU) a else b).move(p, side, ply)
            if (m is Move.Place) check(p.board(side).wallFail(side, m.wall) == null) { "illegal wall $m" }
            if (m is Move.Step) check(p.board(side).steps(side).any { it.to == m.to }) { "illegal step $m" }
            p = p.apply(side, m)
            p.winner()?.let { return it }
            side = Q.other(side)
        }
        return null
    }

    private fun rate(name: String, a: (Int) -> Player, b: (Int) -> Player, games: Int = 100): Double {
        var wins = 0; var draws = 0
        for (g in 0 until games) {
            when (play(a(g), b(g), aFirst = g % 2 == 0)) { Q.YOU -> wins++; null -> draws++; else -> Unit }
        }
        val r = wins.toDouble() / games
        println("$name: ${wins}/$games (${"%.0f".format(r * 100)} %), unfinished $draws")
        return r
    }

    @Test fun beginnerLosesToSomeoneWhoJustFollowsThePath() {
        assertTrue(rate("beginner vs path-follower", { engine(Engine.EASY, it) }, { pathFollower }) <= 0.50)
    }

    @Test fun beginnerBeatsRandomMoves() {
        assertTrue(rate("beginner vs random", { engine(Engine.EASY, it) }, { randomMover(it) }) >= 0.80)
    }

    @Test fun expertBeatsBeginner() {
        assertTrue(rate("expert vs beginner", { engine(Engine.HARD, it) }, { engine(Engine.EASY, it + 500) }) >= 0.90)
    }

    @Test fun mediumBeatsBeginnerAndExpertBeatsMedium() {
        rate("medium vs beginner", { engine(Engine.MEDIUM, it) }, { engine(Engine.EASY, it + 500) }, games = 40)
        rate("expert vs medium", { engine(Engine.HARD, it) }, { engine(Engine.MEDIUM, it + 500) }, games = 40)
    }

    @Test fun expertMoveTimeOnTheJvm() {
        val times = ArrayList<Long>()
        var p = Pos.START; var side = Q.YOU
        for (ply in 0 until 40) {
            val c = Engine.choose(p, side, Engine.HARD, Random(ply))
            times += c.ms
            p = p.apply(side, c.move)
            if (p.winner() != null) break
            side = Q.other(side)
        }
        println("expert ms per move (JVM, real clock, 800 ms limit): avg ${times.average().toInt()} max ${times.max()}")
        assertTrue(times.max() <= 900)
    }
}
