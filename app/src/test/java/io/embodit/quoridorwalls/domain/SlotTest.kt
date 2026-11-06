package io.embodit.quoridorwalls.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * design acceptance.mustPass M3: slotAt under jitter (sigma 4 / 8 mm, 2000 frames at groove-segment midpoints and at
 * crossings): slot jumps < 2 %, orientation flips 0; leaving the 3.1 cm box switches within one frame (raw step); plus the
 * three-colour ghost state machine (legal / three kinds of illegal / off the board).
 */
class SlotTest {
    private val l = Layout()

    private data class Run(val jumps: Int, val frames: Int) { val rate get() = jumps.toDouble() / frames }

    private fun jitter(x0: Float, s0: Float, sigma: Float, seed: Long, frames: Int = 2000): Run {
        val t = SlotTracker(l)
        val rnd = Random(seed)
        var last: Pair<Int, Int>? = null
        var jumps = 0
        repeat(frames) {
            val cur = t.update(x0 + (rnd.nextGaussian() * sigma).toFloat(), s0 + (rnd.nextGaussian() * sigma).toFloat())
            if (last != null && cur != last) jumps++
            last = cur
        }
        return Run(jumps, frames)
    }

    @Test fun jitterAtCrossingsAndGrooveMidpointsRarelyJumps() {
        val report = StringBuilder()
        for (sigma in listOf(0.004f, 0.008f)) {
            var worst = 0.0
            var n = 0
            for (c in listOf(1, 3, 5)) for (r in listOf(1, 4, 6)) {
                val x = l.crossX(c); val s = l.crossS(r)
                for ((tag, px, ps) in listOf(Triple("cross", x, s), Triple("mid-x", x + Layout.PITCH / 2, s), Triple("mid-s", x, s + Layout.PITCH / 2))) {
                    val run = jitter(px, ps, sigma, seed = (c * 100 + r * 10 + n).toLong())
                    n++
                    worst = maxOf(worst, run.rate)
                    assertTrue("sigma ${sigma * 1000} mm $tag ($c,$r): ${run.jumps} jumps / ${run.frames}", run.rate < 0.02)
                }
            }
            report.append("sigma ${(sigma * 1000).toInt()} mm: worst jump rate ${"%.2f".format(worst * 100)} % over $n points x 2000 frames\n")
        }
        println(report)
    }

    @Test fun orientationNeverFlipsFromThePointer() {
        // orientation is not an output of the tracker at all: a hold keeps the button / ghost-pinch orientation (default H)
        val h = Hold(9, Orient.H, null, null, null)
        val t = SlotTracker(l)
        val rnd = Random(3)
        var flips = 0
        var o = h.orient
        repeat(2000) {
            val cross = t.update(l.crossX(3) + (rnd.nextGaussian() * 0.008).toFloat(), l.crossS(3) + (rnd.nextGaussian() * 0.008).toFloat())
            val slot = cross?.let { Wall(it.first, it.second, h.orient) }
            if (slot != null && slot.o != o) { flips++; o = slot.o }
        }
        assertEquals(0, flips)
    }

    @Test fun leavingTheBoxSwitchesAtOnce() {
        val t = SlotTracker(l)
        assertEquals(3 to 3, t.updateRaw(l.crossX(3), l.crossS(3)))
        assertEquals(3 to 3, t.updateRaw(l.crossX(3) + 0.030f, l.crossS(3)))     // inside 3.1 cm: holds
        assertEquals(4 to 3, t.updateRaw(l.crossX(3) + 0.032f, l.crossS(3)))     // out: the very next frame
        assertNull(t.updateRaw(0.40f, 0.20f))                                     // off the grid
    }

    @Test fun ghostColourStateMachine() {
        val b = Pos(listOf(Q.parseCell("e2")!!, Q.parseCell("e9")!!), listOf(Placed(Q.parseWall("d8v")!!, 1), Placed(Q.parseWall("e8h")!!, 0), Placed(Q.parseWall("b4h")!!, 1)), listOf(9, 8)).board()
        assertNull(b.wallFail(Q.YOU, Q.parseWall("e5h")!!))                              // cream: legal
        assertEquals(WallFail.OVERLAP, b.wallFail(Q.YOU, Q.parseWall("c4h")!!))         // red: runs into a wall
        assertEquals(WallFail.CROSS, b.wallFail(Q.YOU, Q.parseWall("b4v")!!))           // red: crosses
        assertEquals(WallFail.BLOCKS_THEM, b.wallFail(Q.YOU, Q.parseWall("f8v")!!))     // red: boxes them in
        assertTrue(!l.onGrid(0.40f, 0.02f))                                              // grey: off the board
    }
}
