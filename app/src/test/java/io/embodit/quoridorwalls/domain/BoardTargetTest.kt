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
    private fun resolve(chain: List<String>, left: Int = Q.WALLS) = resolvePath(chain) { BoardTargets.resolveName(it, left) }
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

    @Test fun yourRailWallsAreTheRailPlacedWallsAreNothing() {
        assertEquals(BoardTarget.Rail, resolve(listOf("geometry_0", BoardNames.wallModel(0, 9, "CLASSIC"), BoardNames.wall(0, 9))))
        assertEquals(BoardTarget.Rail, resolve(listOf(BoardNames.RAIL, "tiltRoot")))
        // after 3 walls are placed, your walls 7..9 stand on the board: no longer the rail
        assertNull(resolve(listOf(BoardNames.wall(0, 8)), left = 7))
        assertEquals(BoardTarget.Rail, resolve(listOf(BoardNames.wall(0, 6)), left = 7))
        assertNull(resolve(listOf(BoardNames.wall(1, 0))))   // the far rail is the AI's
        assertEquals(BoardTarget.Ghost, resolve(listOf(BoardNames.GHOST)))
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
        assertEquals(BoardTarget.Rail, resolve(parseLoggedPath("geometry_0 < wallModel:0:9:MODERN < wall:0:9 < tiltRoot")))
    }
}
