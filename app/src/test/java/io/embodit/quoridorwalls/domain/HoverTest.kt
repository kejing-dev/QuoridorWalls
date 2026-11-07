package io.embodit.quoridorwalls.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** design mustPass M10: hover stays lit until exit / another target; one lone event still shows 1 s later. */
class HoverTest {
    @Test fun oneHoverEventStillLitAfterOneSecond() {
        val h = HoverTracker()
        h.hover("sq:40", 1000)
        assertEquals("sq:40", h.current(2000))
        assertEquals("sq:40", h.current(2499))
        assertNull(h.current(2501))   // no exit ever came: lapses after 1.5 s
    }

    @Test fun exitOrAnotherTargetEndsIt() {
        val h = HoverTracker()
        h.hover("sq:40", 0); h.hover("rail", 100)
        assertEquals("rail", h.current(200))
        h.exit(); assertNull(h.current(201))
        h.hover("pawn:0", 300); h.hover(null, 310); assertNull(h.current(320))
    }

    @Test fun everyEventRefreshes() {
        val h = HoverTracker()
        h.hover("sq:1", 0); h.hover("sq:1", 1400)
        assertEquals("sq:1", h.current(2800))
    }
}
