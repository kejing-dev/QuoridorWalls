package io.embodit.quoridorwalls.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * design v0.1.7 spatialLayout.occlusionRule, a Kotlin port of apps/quoridor-walls/design/occlusion_check.py run on the
 * app's own Layout (34°, pawn dia 3.2 x 2.4, walls 1.0 cm proud, marks 1.2 cm up, volume 0.66 wide, content x -0.05):
 * 30 eyes x (81 cells x 5 mark points + 81 pawns x 2 points), each against every single occluder (another pawn on any of
 * the other 80 cells, 128 standing wall slots, 20 rail walls, seat card, the control window) -> 0 hidden (mustPass M7);
 * the cell behind a wall stays >= 60 % visible; the control area is only grazed from the one known eye (M8).
 */
class OcclusionRuleTest {
    private val l = Layout()
    private val eyes = l.eyes()

    /** Segment a-b against an axis-aligned box, open interval t in (1e-6, 1 - 1e-6), like the design script. */
    private fun segBox(a: V3, b: V3, box: Box3): Boolean {
        var t0 = 1e-6f; var t1 = 1f - 1e-6f
        val o = floatArrayOf(a.x, a.y, a.z); val d = floatArrayOf(b.x - a.x, b.y - a.y, b.z - a.z)
        val lo = floatArrayOf(box.min.x, box.min.y, box.min.z); val hi = floatArrayOf(box.max.x, box.max.y, box.max.z)
        for (i in 0..2) {
            if (abs(d[i]) < 1e-12f) { if (o[i] <= lo[i] || o[i] >= hi[i]) return false; continue }
            var ta = (lo[i] - o[i]) / d[i]; var tb = (hi[i] - o[i]) / d[i]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t1 <= t0) return false
        }
        return true
    }

    private val wallBoxes: List<Pair<String, Box3>> = Wall.ALL.map { it.notation to l.wallBox(it).box }
    private val railBoxes: List<Pair<String, Box3>> = l.railWallBoxes().map { it.name to it.box }
    private val seat = l.seatBox().box
    private val window = l.windowBox()

    /** Who hides the local point [p] from [eye] (pawns standing on any cell but [ownCell]); null = visible. */
    private fun hiddenBy(eye: V3, p: V3, ownCell: Int): String? {
        val el = l.toLocal(eye)
        for (c in 0 until Q.CELLS) if (c != ownCell && l.cylinderHit(l.cellTop(c), Layout.PAWN_H, el, p)) return "pawn ${Q.name(c)}"
        wallBoxes.firstOrNull { segBox(el, p, it.second) }?.let { return "wall ${it.first}" }
        railBoxes.firstOrNull { segBox(el, p, it.second) }?.let { return it.first }
        val pw = l.toScene(p)
        if (segBox(eye, pw, seat)) return "seat card"
        if (segBox(eye, pw, window)) return "control window"
        return null
    }

    @Test fun noMarkerOrPawnHeadHiddenFromAnyEye() {
        val r = 0.009f
        val bad = ArrayList<String>()
        var rays = 0
        for ((name, eye) in eyes) {
            val el = l.toLocal(eye)
            for (cell in 0 until Q.CELLS) {
                val m = l.mark(cell) + V3(0f, 0.001f, 0f)
                for ((tag, d) in listOf("centre" to V3(0f, 0f, 0f), "+x" to V3(r, 0f, 0f), "-x" to V3(-r, 0f, 0f), "far" to V3(0f, 0f, -r), "near" to V3(0f, 0f, r))) {
                    rays++
                    hiddenBy(eye, m + d, cell)?.let { bad += "$name mark ${Q.name(cell)} $tag <- $it" }
                }
                // the pawn standing on this cell: its top, and its side 2/3 up facing the eye (the AI pawn's head must show)
                val base = l.cellTop(cell)
                val top = base + V3(0f, Layout.PAWN_H, 0f)
                var dx = el.x - base.x; var dz = el.z - base.z
                val n = sqrt(dx * dx + dz * dz) + 1e-9f; dx /= n; dz /= n
                val side = base + V3(dx * (Layout.PAWN_D / 2 - 0.0005f), Layout.PAWN_H * 2 / 3, dz * (Layout.PAWN_D / 2 - 0.0005f))
                for ((tag, p) in listOf("top" to top, "side2/3" to side)) {
                    rays++
                    hiddenBy(eye, p, cell)?.let { bad += "$name pawn ${Q.name(cell)} $tag <- $it" }
                }
            }
        }
        println("occlusionRule: ${eyes.size} eyes, $rays rays, hidden ${bad.size} ${bad.take(5)}")
        assertEquals("hidden marks / pawn heads: ${bad.take(10)}", 0, bad.size)
        assertEquals(30, eyes.size)
    }

    @Test fun theCellBehindAWallStaysAtLeast60PercentVisible() {
        var worst = 0f; var where = ""
        val g = (0 until 5).map { -0.019f + 0.0095f * it }
        for ((name, eye) in eyes) {
            val el = l.toLocal(eye)
            for (cell in 0 until Q.CELLS) {
                val pts = g.flatMap { gx -> g.map { gs -> l.cellTop(cell, 0.001f) + V3(gx, 0f, -gs) } }
                for ((wn, box) in wallBoxes) {
                    val f = pts.count { segBox(el, it, box) } / pts.size.toFloat()
                    if (f > worst) { worst = f; where = "$name ${Q.name(cell)} behind $wn" }
                }
            }
        }
        println("worst hidden part of a cell behind one wall: ${"%.0f".format(worst * 100)} % ($where)")
        assertTrue("worst $worst at $where", worst <= 0.40f + 1e-4f)
    }

    @Test fun controlAreaIsOnlyGrazedFromTheKnownEye() {
        val area = l.windowAreas().first()
        val hits = LinkedHashMap<String, Int>()
        val slab = Box3(V3(-Layout.BOARD_W / 2, 0f, -Layout.BOARD_D), V3(Layout.BOARD_W / 2, Layout.CELL_TOP, 0f))
        for ((name, eye) in eyes) {
            val el = l.toLocal(eye)
            for (p in area.grid()) {
                val pl = l.toLocal(p)
                val hit = segBox(el, pl, slab) || wallBoxes.any { segBox(el, pl, it.second) } || railBoxes.any { segBox(el, pl, it.second) } ||
                    (0 until Q.CELLS).any { l.cylinderHit(l.cellTop(it), Layout.PAWN_H, el, pl) }
                if (hit) hits[name] = (hits[name] ?: 0) + 1
            }
        }
        println("control-area points grazed: $hits")
        assertTrue("unexpected eyes $hits", hits.keys.all { it == "reset-level d1.2 az-30" })
        assertTrue("more than one point ${hits}", (hits["reset-level d1.2 az-30"] ?: 0) <= 1)
    }

    @Test fun seatCardIsSeenWholeFromEveryEye() {
        val boxes = wallBoxes.map { it.second } + railBoxes.map { it.second } +
            listOf(Box3(V3(-Layout.BOARD_W / 2, 0f, -Layout.BOARD_D), V3(Layout.BOARD_W / 2, Layout.CELL_TOP, 0f)))
        for ((name, eye) in eyes) for (c in l.seatCorners()) {
            val cl = l.toLocal(c); val el = l.toLocal(eye)
            assertTrue("$name -> seat corner $c", boxes.none { segBox(el, cl, it) })
            assertTrue("$name -> seat corner $c (pawn)", (0 until Q.CELLS).none { l.cylinderHit(l.cellTop(it), Layout.PAWN_H, el, cl) })
        }
    }

    @Test fun everythingStaysInsideTheVolume() {
        val half = Layout.VOLUME * 0.5f
        fun inside(p: V3, what: String) = assertTrue("$what $p", abs(p.x) <= half.x + 1e-4f && abs(p.y) <= half.y + 1e-4f && abs(p.z) <= half.z + 1e-4f)
        for (cell in 0 until Q.CELLS) inside(l.toScene(l.cellTop(cell, Layout.PAWN_H + Layout.LIFT_JUMP)), "jump top ${Q.name(cell)}")
        val top9 = l.toScene(l.cellTop(Q.cell(4, 8), Layout.PAWN_H + Layout.LIFT_JUMP))
        assertTrue("row 9 jump top ${top9.y}", top9.y < 0.17f)
        for (w in Wall.ALL) for (sunk in listOf(true, false)) {
            val b = l.wallBox(w, sunk).box
            for (p in listOf(b.min, b.max, V3(b.min.x, b.max.y, b.min.z), V3(b.max.x, b.max.y, b.max.z))) inside(l.toScene(p), "wall ${w.notation}")
        }
        for (s in listOf(0f, Layout.BOARD_D)) for (x in listOf(-Layout.BOARD_W / 2, Layout.BOARD_W / 2)) {
            inside(l.toScene(l.surface(x, s)), "board corner"); inside(l.toScene(V3(x, 0f, -s)), "board bottom corner")
        }
        for ((_, tri) in l.standPlates()) for (p in tri) { inside(p, "stand"); assertTrue("stand under the floor", p.y >= -0.18f - 1e-4f) }
        for (p in l.seatCorners()) inside(p, "seat card")
        val near = l.toScene(l.surface(0f, 0f)); val far = l.toScene(l.surface(0f, Layout.BOARD_D))
        assertEquals(-0.1593f, near.y, 1e-3f); assertEquals(0.2840f, near.z, 1e-3f)
        assertEquals(0.1371f, far.y, 2e-3f); assertEquals(-0.1554f, far.z, 2e-3f)
    }
}
