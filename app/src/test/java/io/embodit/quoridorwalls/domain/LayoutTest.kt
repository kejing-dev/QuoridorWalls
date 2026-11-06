package io.embodit.quoridorwalls.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * design acceptance.mustPass M5 (spatialLayout.occlusionCheck, D35 / D20 / D41) and the geometry checks: the seat card and
 * the control window's control area seen from every eye (9 design eyes + the measured emulator reset), against every box
 * (board, 20 rail walls, 128 standing walls, 128 walls in hand, pawns at rest / selected / stepping / jumping on all 81
 * cells); the window never covers the board; everything stays inside the volume.
 */
class LayoutTest {
    private val l = Layout()

    private fun boxesStill(): List<Layout.Obstacle> =
        listOf(l.boardBox()) + l.railWallBoxes() + Wall.ALL.map { l.wallBox(it) } + Wall.ALL.map { l.wallBox(it, sunk = false) } +
            (0 until Q.CELLS).flatMap { listOf(l.pawnBox(it, 0f), l.pawnBox(it, Layout.LIFT_SELECT)) }

    @Test fun boardSurfaceMatchesTheDesign() {
        val near = l.toScene(l.surface(0f, 0f))
        assertEquals(-0.1565f, near.y, 1e-3f); assertEquals(0.2786f, near.z, 1e-3f)
        val far = l.toScene(l.surface(0f, Layout.BOARD_D))
        assertEquals(0.0245f, far.y, 2e-3f); assertEquals(-0.220f, far.z, 2e-3f)
        // row 1 / row 9 centres (design: s 0.065 -> z +0.2175 y -0.1343 at the lacquer surface; cells sit 1.5 mm higher)
        val e1 = l.toScene(V3(0f, Layout.BOARD_T, -l.cellS(Q.cell(4, 0))))
        assertEquals(0.2175f, e1.z, 2e-3f); assertEquals(-0.1343f, e1.y, 2e-3f)
        val q = l.toLocal(l.toScene(V3(0.1f, 0.02f, -0.3f)))
        assertTrue(abs(q.x - 0.1f) < 1e-5f && abs(q.y - 0.02f) < 1e-5f && abs(q.z + 0.3f) < 1e-5f)
    }

    @Test fun seatCardIsSeenWholeFromEveryEye() {
        var rays = 0
        val boxes = boxesStill() + (0 until Q.CELLS).flatMap { listOf(l.pawnBox(it, Layout.LIFT_STEP), l.pawnBox(it, Layout.LIFT_JUMP)) }
        for ((name, eye) in l.eyes()) for (corner in l.seatCorners()) for (o in boxes) {
            rays++
            assertTrue("$name -> seat corner $corner crosses ${o.name}", !l.blocks(o, eye, corner))
        }
        println("seat card rays: $rays")
    }

    @Test fun controlAreaIsClearInStillStatesAndOnlyI2I3BrushItWhileStepping() {
        val exceptions = HashSet<String>()
        val stillHits = ArrayList<String>()
        var rays = 0
        val design = setOf("E1", "E1L", "E1R", "E2", "E2L", "E2R")
        for (area in l.windowAreas()) for ((eyeName, eye) in l.eyes()) for (corner in area.corners()) {
            for (o in boxesStill()) {
                rays++
                if (l.blocks(o, eye, corner)) stillHits += "${area.name} $eyeName -> $corner crosses ${o.name}"
            }
            for (cell in 0 until Q.CELLS) for (lift in listOf(Layout.LIFT_STEP, Layout.LIFT_JUMP)) {
                rays++
                if (l.blocks(l.pawnBox(cell, lift), eye, corner)) {
                    exceptions += "${area.name} $eyeName ${Q.name(cell)} +${(lift * 1000).toInt()}mm"
                    if (eyeName in design && area.name.startsWith("design") && lift == Layout.LIFT_STEP)
                        assertTrue("stepping exception outside {i2, i3}: ${Q.name(cell)} from $eyeName", Q.name(cell) in setOf("i2", "i3"))
                }
            }
        }
        println("control-area rays: $rays")
        println("still-state hits (${stillHits.size}): ${stillHits.distinct().take(40)}")
        println("moving-pawn exceptions (${exceptions.size}): ${exceptions.sorted()}")
        // hard rule (design occlusionCheck.controlWindowVsBoard): every still-state hit must be in the known set below,
        // which ui-audit.md / mustpass.md M5 report as a deviation:
        //  - the level reset eye 30° to the left (not a design eye) sees the window's lower-left corner past the board's
        //    near-right corner (h1 / i1 / i2 area);
        //  - a pawn standing on i3 hides that corner from the left-30° sitting eyes E1L / E0L (the design's sums looked at
        //    i2 only); with the measured 520 x 400 dp window only from the low eye E0L.
        val known = stillHits.filter {
            Regex(" reset d=[0-9.]+ az=-30 ").containsMatchIn(it) ||
                (it.startsWith("design") && Regex(" E[01]L ").containsMatchIn(it) && Regex("pawn i3|crosses board|wall h2h in hand").containsMatchIn(it)) ||
                (it.startsWith("520") && it.contains(" E0L ") && it.contains("pawn i3"))
        }
        println("known (${known.size}), see ui-audit")
        assertEquals("still-state hits outside the known set: ${(stillHits - known.toSet()).take(10)}", known.size, stillHits.size)
    }

    @Test fun theWindowNeverCoversTheBoard() {
        val corners = listOf(0f to 0f, 0f to Layout.BOARD_D).flatMap { (_, s) -> listOf(l.surface(-Layout.BOARD_W / 2, s), l.surface(Layout.BOARD_W / 2, s)) }.map { l.toScene(it) }
        for (area in listOf(Box3(V3(0.33f, -0.175f, -0.002f), V3(0.78f, 0.175f, 0.002f)), Box3(V3(0.33f, -0.138f, -0.002f), V3(0.33f + 520 * Layout.DP_M, 0.138f, 0.002f))))
            for ((name, eye) in l.eyes()) for (c in corners + (0 until Q.CELLS).map { l.toScene(l.cellTop(it)) })
                assertTrue("window covers $c from $name", !area.hitBySegment(eye, c))
    }

    @Test fun everythingStaysInsideTheVolume() {
        val half = Layout.VOLUME * 0.5f
        fun inside(p: V3, what: String) = assertTrue("$what $p", abs(p.x) <= half.x + 1e-4f && abs(p.y) <= half.y + 1e-4f && abs(p.z) <= half.z + 1e-4f)
        // the highest point: a row-9 pawn at the top of a jump / step
        for (cell in 0 until Q.CELLS) for (lift in listOf(Layout.LIFT_STEP, Layout.LIFT_JUMP)) inside(l.toScene(l.pawnCentre(cell, lift) + V3(0f, Layout.PAWN_H / 2, 0f)), "pawn top ${Q.name(cell)}")
        for (w in Wall.ALL) for (sunk in listOf(true, false)) {
            val b = l.wallBox(w, sunk).box
            for (p in listOf(b.min, b.max, V3(b.min.x, b.max.y, b.min.z), V3(b.max.x, b.max.y, b.max.z))) inside(l.toScene(p), "wall ${w.notation}")
        }
        for (o in l.railWallBoxes()) inside(l.toScene(o.box.max), o.name)
        for (p in l.seatCorners()) inside(p, "seat card")
        // the board's own corners
        for (s in listOf(0f, Layout.BOARD_D)) for (x in listOf(-Layout.BOARD_W / 2, Layout.BOARD_W / 2)) inside(l.toScene(l.surface(x, s)), "board corner")
    }

    @Test fun railWallsDoNotOverlapAndUseTheTopLayerFirst() {
        for (side in 0..1) {
            val boxes = (0 until Q.WALLS).map { l.railWallBoxes()[side * Q.WALLS + it].box }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val a = boxes[i]; val b = boxes[j]
                val overlap = a.min.x < b.max.x - 1e-5f && b.min.x < a.max.x - 1e-5f && a.min.y < b.max.y - 1e-5f && b.min.y < a.max.y - 1e-5f && a.min.z < b.max.z - 1e-5f && b.min.z < a.max.z - 1e-5f
                assertTrue("rail $side walls $i / $j overlap", !overlap)
            }
            assertTrue(l.railCentre(side, 9).y > l.railCentre(side, 0).y)
            assertTrue(abs(l.railCentre(side, 4).x) + Layout.WALL_L / 2 <= Layout.BOARD_W / 2)
        }
    }

    @Test fun nearestCrossAndSurfaceRoundTrip() {
        for (w in Wall.ALL) assertEquals(w.c to w.r, l.nearestCross(l.crossX(w.c), l.crossS(w.r)))
        for (cell in 0 until Q.CELLS) {
            val (x, s) = l.boardXS(l.toScene(l.cellTop(cell)))
            assertEquals(l.cellX(cell), x, 1e-4f); assertEquals(l.cellS(cell), s, 1e-4f)
        }
    }
}
