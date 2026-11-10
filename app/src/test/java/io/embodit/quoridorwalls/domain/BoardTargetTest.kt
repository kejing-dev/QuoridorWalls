package io.embodit.quoridorwalls.domain

import io.embodit.quoridorwalls.ui.board.BoardNames
import io.embodit.quoridorwalls.ui.board.BoardTarget
import io.embodit.quoridorwalls.ui.board.BoardTargets
import io.embodit.quoridorwalls.ui.board.input.parseLoggedPath
import io.embodit.quoridorwalls.ui.board.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * board-input 必带单测（命中回放）: every kind of hit the scene can produce -- a cell holder, its visible gaze plate, a pawn
 * holder, any model mesh below it, a rail wall, the rail, the ghost, placed walls, the board -- resolves through the real
 * names (BoardNames) to the right target and cell; pawns follow the board; logged `path=` values replay.
 */
class BoardTargetTest {
    private val start: (Int) -> Int = { Pos.START.pawns[it] }
    private fun resolve(chain: List<String>, left: List<Int> = listOf(Q.WALLS, Q.WALLS)) = resolvePath(chain) { BoardTargets.resolveName(it, left) }
    private fun cell(chain: List<String>, pawnCell: (Int) -> Int = start) = BoardTargets.cellFor(resolve(chain), pawnCell)

    @Test fun everyCellHolderAndItsGazePlateResolve() {
        for (c in 0 until Q.CELLS) {
            assertEquals(c, cell(listOf(BoardNames.square(c), "tiltRoot", "sceneRoot", "root")))
            assertEquals(c, cell(listOf(BoardNames.marker(c), BoardNames.square(c), "tiltRoot")))
        }
    }

    @Test fun everyPawnMeshResolvesToTheCellItStandsOn() {
        for (side in 0..1) for (skin in Skin.entries) {
            val want = start(side)
            assertEquals(want, cell(listOf(BoardNames.pawn(side), "tiltRoot")))
            assertEquals(want, cell(listOf("geometry_0", "world", BoardNames.pawnModel(side, skin.name), BoardNames.pawn(side), "tiltRoot")))
        }
    }

    @Test fun pawnsFollowTheBoard() {
        assertEquals(Q.parseCell("e4"), cell(listOf("geometry_0", BoardNames.pawnModel(0, "MODERN"), BoardNames.pawn(0))) { if (it == 0) Q.parseCell("e4")!! else -1 })
    }

    /** §15.6: every wall still lying in a tray (both sides, any mesh below it) and each tray block resolves to that tray. */
    @Test fun everyTrayWallAndTrayBlockResolves() {
        for (side in 0..1) {
            for (k in 0 until Q.WALLS) for (skin in Skin.entries) {
                assertEquals(BoardTarget.Rail(side), resolve(listOf("geometry_0", BoardNames.wallModel(side, k, skin.name), BoardNames.wall(side, k), "tiltRoot")))
            }
            assertEquals(BoardTarget.Rail(side), resolve(listOf(BoardNames.rail(side), "tiltRoot")))
        }
        // after 3 walls are placed, walls 7..9 stand on the board: no longer the tray (and they lose their click box)
        assertNull(resolve(listOf(BoardNames.wall(0, 8)), left = listOf(7, 10)))
        assertEquals(BoardTarget.Rail(0), resolve(listOf(BoardNames.wall(0, 6)), left = listOf(7, 10)))
        assertNull(resolve(listOf(BoardNames.wall(1, 9)), left = listOf(10, 9)))
        assertNull(resolve(listOf("wall:2:0"))); assertNull(resolve(listOf("rail:2")))
    }

    /** §15.6: every slot (64 crossings) via its holder and via its visible stud; the ghost. */
    @Test fun everySlotAndItsStudResolve() {
        for (r in 0..7) for (c in 0..7) {
            assertEquals(BoardTarget.Slot(c, r), resolve(listOf(BoardNames.slot(c, r), "tiltRoot")))
            assertEquals(BoardTarget.Slot(c, r), resolve(listOf(BoardNames.slotMarker(c, r), BoardNames.slot(c, r), "tiltRoot")))
        }
        assertNull(resolve(listOf("slot:8:0"))); assertNull(resolve(listOf("slot:1")))
        assertEquals(BoardTarget.Ghost, resolve(listOf(BoardNames.GHOST, "tiltRoot")))
        assertEquals("slot=e6", BoardTargets.logOf(BoardTarget.Slot(4, 5)) { 0 })
        assertEquals("rail=1", BoardTargets.logOf(BoardTarget.Rail(1)) { 0 })
        assertEquals("square=e1", BoardTargets.logOf(BoardTarget.Pawn(0)) { Pos.START.pawns[it] })
    }

    @Test fun boardAndForeignNodesResolveToNothing() {
        assertEquals(-1, cell(listOf("${BoardNames.BOARD}:MODERN", "tiltRoot", "sceneRoot", "root")))
        assertEquals(-1, cell(listOf("keyLight", "root")))
        assertNull(resolve(listOf("sq:81"))); assertNull(resolve(listOf("pawn:2"))); assertNull(resolve(listOf("sq:12:x")))
    }

    /** Paste real `path=` values from the headset files/input-log.txt here after every device round. */
    @Test fun replayLoggedPaths() {
        assertEquals(Q.parseCell("e2"), cell(parseLoggedPath("mk:sq:13:0 < sq:13 < tiltRoot < sceneRoot < root")))
        assertEquals(Q.parseCell("e1"), cell(parseLoggedPath("geometry_0 < pawnModel:0:MODERN < pawn:0 < tiltRoot")))
        assertEquals(BoardTarget.Rail(0), resolve(parseLoggedPath("geometry_0 < wallModel:0:9:MODERN < wall:0:9 < tiltRoot")))
        // v0.1.3 headset input-log (2026-11-10, PB324XJGL2090081G, hand ray): taps on the far tray's walls logged hit=null
        // (the old resolver only knew your rail); now they are the far tray (an alert in a game against the AI)
        assertEquals(BoardTarget.Rail(1), resolve(parseLoggedPath("wall:1:6 < tiltRoot < sceneRoot < root < RootEntity_View_50658744119328781")))
        assertEquals(BoardTarget.Rail(1), resolve(parseLoggedPath("wall:1:7 < tiltRoot < sceneRoot < root < RootEntity_View_50658744119328781")))
        assertEquals(BoardTarget.Rail(0), resolve(parseLoggedPath("rail")))
        assertEquals(Q.parseCell("e2"), cell(parseLoggedPath("sq:13")))
        assertEquals(Q.parseCell("e1"), cell(parseLoggedPath("pawn:0")))
    }
}
