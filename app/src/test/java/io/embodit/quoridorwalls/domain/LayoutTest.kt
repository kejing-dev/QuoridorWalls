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

    @Test fun railWallsLieInTwoRowsOfFiveWithoutOverlap() {
        for (side in 0..1) {
            val boxes = (0 until Q.WALLS).map { l.railWallBoxes()[side * Q.WALLS + it].box }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val a = boxes[i]; val b = boxes[j]
                val overlap = a.min.x < b.max.x - 1e-5f && b.min.x < a.max.x - 1e-5f && a.min.z < b.max.z - 1e-5f && b.min.z < a.max.z - 1e-5f
                assertTrue("rail $side walls $i / $j overlap", !overlap)
            }
            assertEquals(1, boxes.map { it.min.y }.distinct().size)   // one layer, all at the rail top
            assertTrue(boxes.all { -it.min.z <= Layout.RAIL + 1e-4f || -it.max.z >= Layout.BOARD_D - Layout.RAIL - 1e-4f })
        }
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
