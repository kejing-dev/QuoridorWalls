package io.embodit.quoridorwalls.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Layout bookkeeping; the occlusion rule itself is OcclusionRuleTest. */
class LayoutTest {
    private val l = Layout()

    @Test fun localAndSceneRoundTrip() {
        val q = l.toLocal(l.toScene(V3(0.1f, 0.02f, -0.3f)))
        assertTrue(abs(q.x - 0.1f) < 1e-5f && abs(q.y - 0.02f) < 1e-5f && abs(q.z + 0.3f) < 1e-5f)
    }

    /** §15.6: both trays in the front margin, lying flat, >= 1 cm apart, click boxes not overlapping, away from the volume sides. */
    @Test fun trayWallsLieFlatInTheFrontMarginOneCentimetreApart() {
        val all = l.railWallBoxes().map { it.box }
        for (i in all.indices) for (j in i + 1 until all.size) {
            val a = all[i]; val b = all[j]
            val gapX = maxOf(b.min.x - a.max.x, a.min.x - b.max.x)
            val gapS = maxOf(b.min.z - a.max.z, a.min.z - b.max.z)
            assertTrue("walls $i / $j only ${maxOf(gapX, gapS)} m apart", maxOf(gapX, gapS) >= 0.0099f)
        }
        assertEquals(1, all.map { it.min.y }.distinct().size)   // one layer, all on the frame top
        assertTrue(all.all { -it.min.z <= Layout.NEAR - 0.005f && -it.max.z >= 0f })   // in the front margin, before the grid
        // click boxes (one ring bigger) of neighbours do not overlap
        for (side in 0..1) for (k in 0 until Q.WALLS) for (k2 in k + 1 until Q.WALLS) {
            val a = l.railCentre(side, k); val b = l.railCentre(side, k2)
            assertTrue(abs(a.x - b.x) >= Layout.WALL_HIT.x - 1e-4f || abs(a.z - b.z) >= Layout.WALL_HIT.y - 1e-4f)
        }
        // not at the volume sides: >= 4 cm in from them (scene x, content shifted 5 cm left)
        for (b in all) assertTrue(Layout.CONTENT_X + b.min.x >= -Layout.VOLUME.x / 2 + 0.04f && Layout.CONTENT_X + b.max.x <= Layout.VOLUME.x / 2 - 0.04f)
        assertTrue(Layout.WALL_HIT.y >= 0.02f && Layout.WALL_HIT.z >= 0.02f)
    }

    @Test fun slotsSitOnTheCrossings() {
        for (w in Wall.ALL) { val p = l.slotCentre(w.c, w.r); assertEquals(l.crossX(w.c), p.x, 1e-6f); assertEquals(-l.crossS(w.r), p.z, 1e-6f) }
        assertTrue(Layout.STUD_D <= 0.005f + 1e-6f)
    }

    @Test fun nearestCrossAndSurfaceRoundTrip() {
        for (w in Wall.ALL) assertEquals(w.c to w.r, l.nearestCross(l.crossX(w.c), l.crossS(w.r)))
        for (cell in 0 until Q.CELLS) {
            val (x, s) = l.boardXS(l.toScene(l.cellTop(cell)))
            assertEquals(l.cellX(cell), x, 1e-4f); assertEquals(l.cellS(cell), s, 1e-4f)
        }
    }

    @Test fun wallsShowOneCentimetreAndPawnsAreSquat() {
        assertEquals(0.010f, Layout.WALL_BASE + Layout.WALL_H - Layout.CELL_TOP, 1e-5f)
        assertEquals(0.024f, Layout.PAWN_H, 0f); assertEquals(0.032f, Layout.PAWN_D, 0f)
        assertEquals(0f, Layout.LIFT_SELECT, 0f)
        assertEquals(34f, Layout.TILT_DEG, 0f)
    }
}
