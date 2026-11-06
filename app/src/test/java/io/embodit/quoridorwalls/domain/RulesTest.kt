package io.embodit.quoridorwalls.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** design acceptance.mustPass M1: perft 131, 12 step / jump cases, 8 wall cases, one shared BFS. */
class RulesTest {
    private fun c(n: String) = Q.parseCell(n)!!
    private fun w(n: String) = Q.parseWall(n)!!
    private fun pos(you: String, ai: String, walls: String = "", left: List<Int>? = null): Pos {
        val placed = if (walls.isBlank()) emptyList() else walls.split(" ").map { Placed(w(it.take(3)), it.drop(3).toIntOrNull() ?: 0) }
        return Pos(listOf(c(you), c(ai)), placed, left ?: listOf(Q.WALLS - placed.count { it.side == 0 }, Q.WALLS - placed.count { it.side == 1 }))
    }
    private fun targets(p: Pos, side: Int = Q.YOU) = p.board(side).steps(side).map { Q.name(it.to) }.toSet()

    // ------------------------------------------------------------------ perft

    @Test fun openingHas131LegalMoves() {
        val b = Pos.START.board()
        val moves = b.legalMoves(Q.YOU)
        assertEquals(setOf("d1", "f1", "e2"), moves.filterIsInstance<Move.Step>().map { Q.name(it.to) }.toSet())
        assertEquals(128, moves.count { it is Move.Place })
        assertEquals(131, moves.size)
        assertEquals(131, Independent.countMoves(Pos.START, Q.YOU))
    }

    /** An independent implementation (edge sets + segment overlap + its own BFS) agrees on random positions. */
    @Test fun independentImplementationAgreesOnRandomPositions() {
        val rnd = kotlin.random.Random(5)
        var checked = 0
        repeat(300) {
            var p = Pos.START
            var side = Q.YOU
            repeat(rnd.nextInt(2, 30)) {
                val b = p.board(side)
                if (p.winner() != null) return@repeat
                val moves = b.legalMoves(side)
                val m = moves[rnd.nextInt(moves.size)]
                p = p.apply(side, m); side = Q.other(side)
            }
            if (p.winner() == null) {
                assertEquals("moves of $p side $side", Independent.countMoves(p, side), p.board(side).legalMoves(side).size)
                assertEquals(Independent.dist(p, 0), p.board().dist(0))
                assertEquals(Independent.dist(p, 1), p.board().dist(1))
                checked++
            }
        }
        assertTrue(checked > 100)
    }

    // ------------------------------------------------------------------ 12 step / jump cases

    @Test fun s01_fourNeighbours() = assertEquals(setOf("e4", "e6", "d5", "f5"), targets(pos("e5", "a9")))

    @Test fun s02_noDiagonalStep() {
        val b = pos("e5", "a9").board()
        assertEquals(StepFail.DIAGONAL, b.stepFail(Q.YOU, c("f6")))
        assertEquals(StepFail.FAR, b.stepFail(Q.YOU, c("e7")))
    }

    @Test fun s03_wallBetweenStopsTheStep() {
        val p = pos("e5", "a9", "e5h")   // horizontal wall on the far side of e5 (and f5)
        assertFalse("e6" in targets(p))
        assertEquals(StepFail.WALL, p.board().stepFail(Q.YOU, c("e6")))
        val q = pos("e5", "a9", "d4v")   // vertical wall between d and e for rows 4-5
        assertFalse("d5" in targets(q))
    }

    @Test fun s04_noStepOffTheBoard() = assertEquals(setOf("b1", "a2"), targets(pos("a1", "i9")))

    @Test fun s05_straightJump() {
        val t = targets(pos("e4", "e5"))
        assertTrue("e6" in t); assertFalse("e5" in t)
        assertEquals(StepFail.THEIRS, pos("e4", "e5").board().stepFail(Q.YOU, c("e5")))
    }

    @Test fun s06_wallBehindGivesDiagonalJumps() {
        val t = targets(pos("e4", "e5", "e5h"))   // wall behind the AI pawn
        assertTrue("d5" in t && "f5" in t); assertFalse("e6" in t)
    }

    @Test fun s07_boardEdgeBehindGivesDiagonalJumps() {
        val t = targets(pos("e8", "e9"))
        assertTrue("d9" in t && "f9" in t)
    }

    @Test fun s08_sideWallBlocksOneDiagonal() {
        // wall behind e5, and a vertical wall between e5 and f5 (rows 4-5): only d5
        val t = targets(pos("e4", "e5", "e5h e4v"))
        assertTrue("d5" in t); assertFalse("f5" in t)
    }

    @Test fun s09_noJumpOverADiagonalNeighbour() {
        val t = targets(pos("e4", "f5"))
        assertEquals(setOf("e5", "d4", "f4", "e3"), t)
    }

    @Test fun s10_noWallsLeftOnlySteps() {
        val p = pos("e3", "e7", left = listOf(0, 4))
        val moves = p.board().legalMoves(Q.YOU)
        assertTrue(moves.all { it is Move.Step })
        assertEquals(WallFail.NO_WALLS, p.board().wallFail(Q.YOU, w("a1h")))
    }

    @Test fun s11_eachSideWinsOnTheFarRow() {
        assertEquals(Q.YOU, pos("c9", "e5").winner())
        assertEquals(Q.AI, pos("e5", "g1").winner())
        assertNull(pos("e8", "e2").winner())
    }

    @Test fun s12_theAiMovesTowardRowOne() {
        val t = targets(pos("e1", "e9"), Q.AI)
        assertEquals(setOf("d9", "f9", "e8"), t)
        assertEquals(8, Pos.START.board().dist(Q.AI))
        assertEquals(8, Pos.START.board().dist(Q.YOU))
    }

    // ------------------------------------------------------------------ 8 wall cases

    @Test fun w01_halfOverlapIsRefused() {
        val b = pos("e1", "e9", "d4h").board()
        assertEquals(WallFail.OVERLAP, b.wallFail(Q.YOU, w("e4h")))
        assertEquals(WallFail.OVERLAP, b.wallFail(Q.YOU, w("c4h")))
        assertEquals(WallFail.ALREADY, b.wallFail(Q.YOU, w("d4h")))
        val v = pos("e1", "e9", "d4v").board()
        assertEquals(WallFail.OVERLAP, v.wallFail(Q.YOU, w("d5v")))
        assertEquals(WallFail.OVERLAP, v.wallFail(Q.YOU, w("d3v")))
    }

    @Test fun w02_crossingAtTheSameCentreIsRefused() {
        assertEquals(WallFail.CROSS, pos("e1", "e9", "d4h").board().wallFail(Q.YOU, w("d4v")))
        assertEquals(WallFail.CROSS, pos("e1", "e9", "d4v").board().wallFail(Q.YOU, w("d4h")))
    }

    @Test fun w03_parallelNeighboursAreLegal() {
        val b = pos("e1", "e9", "d4h").board()
        assertNull(b.wallFail(Q.YOU, w("f4h")))   // end to end
        assertNull(b.wallFail(Q.YOU, w("d5h")))   // one row further
        assertNull(b.wallFail(Q.YOU, w("e4v")))   // a T next to it
    }

    @Test fun w04_centresOnColumnIOrRow9DoNotExist() {
        assertNull(Q.parseWall("i3h")); assertNull(Q.parseWall("e9v"))
        assertEquals(128, Wall.ALL.size)
        assertTrue(Wall.ALL.all { it.c in 0..7 && it.r in 0..7 })
    }

    @Test fun w05_boxingTheOpponentInIsRefused() {
        // AI on e9 with d8v (left) and e8h (below e9-f9); f8v would close f9 off too
        val b = pos("e2", "e9", "d8v1 e8h0").board()
        assertEquals(WallFail.BLOCKS_THEM, b.wallFail(Q.YOU, w("f8v")))
    }

    @Test fun w06_boxingYourselfInIsRefused() {
        val b = pos("e1", "e9", "d1v e1h").board()
        assertEquals(WallFail.BLOCKS_YOU, b.wallFail(Q.YOU, w("f1v")))
    }

    @Test fun w07_theEleventhWallIsRefused() {
        var p = Pos.START
        val ws = listOf("a1h", "c1h", "e2h", "g1h", "a3h", "c3h", "e4h", "g3h", "a5h", "c5h")
        for (x in ws) p = p.apply(Q.YOU, Move.Place(w(x)))
        assertEquals(0, p.left[Q.YOU])
        assertEquals(WallFail.NO_WALLS, p.board().wallFail(Q.YOU, w("a7h")))
        assertNull(p.board().wallFail(Q.AI, w("a7h")))
    }

    @Test fun w08_undoRestoresTheWallCount() = FlowTest().undoPutsTheWallBackAndRestoresTheCount()

    // ------------------------------------------------------------------ one BFS everywhere

    @Test fun legalityBandsAndAiShareOneBfs() {
        val p = pos("e3", "e7", "d5h1 f5h0 c3v0")
        val b = p.board()
        val path = b.path(Q.YOU)
        assertEquals(b.dist(Q.YOU), path.size - 1)
        assertEquals(b.dist(Q.AI), b.path(Q.AI).size - 1)
        val e = Engine.eval(b, Q.YOU)
        assertEquals((b.dist(Q.AI) - b.dist(Q.YOU)) * 10 + (b.left[0] - b.left[1]) * 2 - b.dist(Q.YOU), e)
        // the path is walkable step by step
        for (i in 0 until path.size - 1) {
            val d = (0..3).first { b.step(path[i], it) == path[i + 1] }
            assertTrue(b.open(path[i], d))
        }
    }

    @Test fun saveCodecRoundTrips() {
        val p = pos("e3", "e7", "d5h1 f5h0 c3v0")
        val snap = Snap(p, Q.AI, 7, Stats(3, 4), LastMove(Q.YOU, Move.Place(w("c3v"))))
        val g = SavedGame(GameConfig(Engine.HARD, Q.AI), snap, listOf(Snap(Pos.START, Q.YOU, 0, Stats(), null), snap), hintOn = true, bandsOn = false)
        assertEquals(g, SaveCodec.decode(SaveCodec.encode(g)))
        val s2 = snap.copy(last = LastMove(Q.YOU, Move.Step(c("e4"), c("e6"), true)))
        assertEquals(s2, SaveCodec.parseSnap(SaveCodec.snap(s2)))
    }
}

/** A second, deliberately different implementation: blocked edges as a set of cell pairs, walls as segments. */
object Independent {
    private fun blockedEdges(p: Pos): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        for (pl in p.walls) {
            val (c, r) = pl.wall.c to pl.wall.r
            if (pl.wall.o == Orient.H) for (cc in c..c + 1) { out += Q.cell(cc, r) to Q.cell(cc, r + 1); out += Q.cell(cc, r + 1) to Q.cell(cc, r) }
            else for (rr in r..r + 1) { out += Q.cell(c, rr) to Q.cell(c + 1, rr); out += Q.cell(c + 1, rr) to Q.cell(c, rr) }
        }
        return out
    }

    private fun neighbours(cell: Int): List<Int> {
        val c = Q.col(cell); val r = Q.row(cell)
        return listOfNotNull(if (r < 8) Q.cell(c, r + 1) else null, if (r > 0) Q.cell(c, r - 1) else null, if (c < 8) Q.cell(c + 1, r) else null, if (c > 0) Q.cell(c - 1, r) else null)
    }

    fun dist(p: Pos, side: Int): Int {
        val bl = blockedEdges(p)
        val goal = if (side == 0) 8 else 0
        val seen = HashMap<Int, Int>()
        val q = ArrayDeque<Int>()
        q += p.pawns[side]; seen[p.pawns[side]] = 0
        while (q.isNotEmpty()) {
            val x = q.removeFirst()
            if (Q.row(x) == goal) return seen[x]!!
            for (n in neighbours(x)) if ((x to n) !in bl && n !in seen) { seen[n] = seen[x]!! + 1; q += n }
        }
        return -1
    }

    private fun segments(w: Wall): Pair<Set<String>, String> {
        // the two half-segments a wall covers, and its centre point
        return if (w.o == Orient.H) setOf("h${w.c},${w.r}", "h${w.c + 1},${w.r}") to "x${w.c},${w.r}" else setOf("v${w.c},${w.r}", "v${w.c},${w.r + 1}") to "x${w.c},${w.r}"
    }

    fun countMoves(p: Pos, side: Int): Int {
        val bl = blockedEdges(p)
        val me = p.pawns[side]; val opp = p.pawns[1 - side]
        val targets = HashSet<Int>()
        for (n in neighbours(me)) {
            if ((me to n) in bl) continue
            if (n != opp) { targets += n; continue }
            val dc = Q.col(n) - Q.col(me); val dr = Q.row(n) - Q.row(me)
            val bc = Q.col(n) + dc; val br = Q.row(n) + dr
            val behind = if (bc in 0..8 && br in 0..8) Q.cell(bc, br) else null
            if (behind != null && (n to behind) !in bl) { targets += behind; continue }
            for (m in neighbours(n)) if (m != me && (n to m) !in bl && (Q.col(m) - Q.col(n) != dc || Q.row(m) - Q.row(n) != dr)) targets += m
        }
        var walls = 0
        if (p.left[side] > 0) {
            val used = HashSet<String>(); val centres = HashSet<String>()
            for (pl in p.walls) { val (s, c) = segments(pl.wall); used += s; centres += c }
            for (w in Wall.ALL) {
                val (s, c) = segments(w)
                if (s.any { it in used } || c in centres) continue
                val q = p.copy(walls = p.walls + Placed(w, side))
                if (dist(q, 0) >= 0 && dist(q, 1) >= 0) walls++
            }
        }
        return targets.size + walls
    }
}
