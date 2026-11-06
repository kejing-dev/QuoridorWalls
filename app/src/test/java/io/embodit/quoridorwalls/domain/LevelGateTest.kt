package io.embodit.quoridorwalls.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelGateTest {
    @Test fun levelGateWaitsForALevelHeadAndFallsBack() {
        val g = LevelGate()
        g.ask("start", 0)
        assertNull(g.step(10, -30f))
        assertTrue(g.hintWanted(-30f))
        assertNull(g.step(100, 2f))
        assertEquals("start", g.step(450, 1f))
        g.ask("reopen", 1000)
        assertNull(g.step(1000, null))
        assertEquals("reopen/nohead", g.step(1000 + g.noHeadMs, null))
    }
}
