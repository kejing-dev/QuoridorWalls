package io.embodit.quoridorwalls.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The middle layer: commit-before-animate, the wall-in-hand state machine, bounce lock, watchdog, undo, save / restore. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    class MemStore : GameStore {
        var saved: SavedGame? = null
        override fun load() = saved
        override fun save(game: SavedGame) { saved = game }
        override fun clear() { saved = null }
    }

    class Harness(scope: TestScope, val store: MemStore = MemStore(), var settings: Settings = Settings(firstRunDone = true, coachDone = true)) {
        val sounds = ArrayList<Sound>()
        val log = ArrayList<String>()
        val flow = GameFlow(
            scope = scope.backgroundScope,
            engineDispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime },
            store = store,
            settings = { settings },
            onSettings = { settings = it },
            onSound = { sounds += it },
            log = { log += it },
        )
        val st get() = flow.state.value
    }

    private fun c(n: String) = Q.parseCell(n)!!
    private fun w(n: String) = Q.parseWall(n)!!
    private fun TestScope.settle(ms: Long = 3000) { runCurrent(); advanceTimeBy(ms); runCurrent() }

    private fun TestScope.game(h: Harness, setup: String = "", level: Int = Engine.EASY) {
        h.flow.newGame(GameConfig(level, Q.YOU)); runCurrent()
        if (setup.isNotBlank()) { val (p, side) = SaveCodec.parseSetup(setup); h.flow.setup(p, side ?: Q.YOU); runCurrent() }
    }

    @Test fun firstRunOpensAnEasyGameWithTheRulesPage() = runTest {
        val h = Harness(this, settings = Settings())
        h.flow.start(); runCurrent()
        assertEquals(Phase.IDLE, h.st.phase)
        assertTrue(h.st.welcome)
        assertEquals(Engine.EASY, h.st.config!!.level)
        assertTrue(h.st.coachLit)
        assertEquals(setOf("d1", "f1", "e2"), h.st.steps.map { Q.name(it.to) }.toSet())
        h.flow.welcomeDone()
        assertFalse(h.st.welcome); assertTrue(h.settings.firstRunDone)
    }

    @Test fun aStepIsCommittedAndSavedBeforeItMoves() = runTest {
        val h = Harness(this); game(h)
        h.flow.tapCell(c("e2"))   // a lit target is taken straight away (S03 "点亮格直接走")
        assertEquals(Phase.ANIM, h.st.phase)
        assertEquals(c("e2"), h.st.pos.pawns[Q.YOU])
        val saved = h.store.saved!!
        assertEquals(c("e2"), saved.now.pos.pawns[Q.YOU])
        assertEquals(Q.AI, saved.now.side)
        settle(GameFlow.STEP_MS + 10)
        assertEquals(Phase.THINKING, h.st.phase)
        settle()
        assertEquals(Phase.IDLE, h.st.phase)
        assertEquals(Q.YOU, h.st.side)
    }

    @Test fun selectThenIllegalCellsAnswerWithAReason() = runTest {
        val h = Harness(this); game(h, "you=e4 ai=e5 walls=d4v0 side=0")
        h.flow.tapPawn(Q.YOU)
        assertEquals(Phase.SELECTED, h.st.phase)
        h.flow.tapCell(c("d4")); assertEquals(AlertKind.WALL_BETWEEN, h.st.alert!!.kind)
        h.flow.tapCell(c("f5")); assertEquals(AlertKind.DIAGONAL, h.st.alert!!.kind)
        h.flow.tapCell(c("e5")); assertEquals(AlertKind.THEIRS, h.st.alert!!.kind)
        h.flow.tapCell(c("e7")); assertEquals(AlertKind.FAR, h.st.alert!!.kind)
        assertEquals(Phase.SELECTED, h.st.phase)
        assertEquals(c("e4"), h.st.pos.pawns[Q.YOU])
        h.flow.tapCell(c("e6"))   // the jump
        assertEquals(Phase.ANIM, h.st.phase)
        assertTrue((h.st.anim as Anim.Step).step.jump)
    }

    @Test fun holdOverASlotThenLetGoSetsTheWall() = runTest {
        val h = Harness(this); game(h, "you=e3 ai=e7 side=0")
        val before = h.st.bands[Q.AI].size
        assertTrue(h.flow.pickWall())
        assertEquals(Phase.HOLDING, h.st.phase)
        assertEquals(9, h.st.hold!!.railK)
        h.flow.holdOver(w("e6h"))
        assertEquals(w("e6h"), h.st.hold!!.slot)
        assertNull(h.st.hold!!.fail)
        assertTrue("the legal ghost previews the detour (T8)", h.st.bands[Q.AI].size > before)
        h.flow.dropWall()
        assertEquals(Phase.ANIM, h.st.phase)
        assertEquals(9, h.st.pos.left[Q.YOU])
        assertEquals(9, h.store.saved!!.now.pos.left[Q.YOU])
        assertTrue(h.st.bands[Q.AI].size > before)
        assertTrue(h.st.stats.detourGiven > 0)
        settle(GameFlow.SINK_MS + 10)
        assertTrue(Sound.WALL in h.sounds)
    }

    @Test fun orientationFlipsOnlyWithAWallInHandAndIsRechecked() = runTest {
        val h = Harness(this); game(h, "you=e3 ai=e7 walls=e6v1 side=0")
        h.flow.toggleOrient()   // nothing in hand: ignored
        assertTrue(h.flow.pickWall())
        h.flow.holdOver(w("d6h"))
        assertNull(h.st.hold!!.fail)
        h.flow.holdOver(w("e6h"))   // crosses e6v
        assertEquals(WallFail.CROSS, h.st.hold!!.fail)
        h.flow.toggleOrient()
        assertEquals(Orient.V, h.st.hold!!.orient)
        assertEquals(WallFail.ALREADY, h.st.hold!!.fail)
    }

    @Test fun aBoxingWallFlashesRedFliesBackAndLocksInputMeanwhile() = runTest {
        val h = Harness(this); game(h, "you=e2 ai=e9 walls=d8v1.e8h0 left=9,9 side=0")
        assertTrue(h.flow.pickWall())
        h.flow.holdOver(w("f8v"))
        assertEquals(WallFail.BLOCKS_THEM, h.st.hold!!.fail)
        h.flow.dropWall()
        assertEquals(Phase.REJECTED, h.st.phase)
        // locked: no pick, no step while it bounces
        assertFalse(h.flow.pickWall())
        h.flow.tapCell(c("e3"))
        assertEquals(c("e2"), h.st.pos.pawns[Q.YOU])
        assertEquals(9, h.st.pos.left[Q.YOU])
        settle(GameFlow.FLASH_MS + GameFlow.FLY_BACK_MS + 10)
        assertEquals(Phase.IDLE, h.st.phase)
        assertTrue(Sound.BOUNCE in h.sounds)
        assertEquals(9, h.st.pos.left[Q.YOU])
    }

    @Test fun lettingGoOffTheBoardPutsTheWallBack() = runTest {
        val h = Harness(this); game(h, "you=e3 ai=e7 side=0")
        assertTrue(h.flow.pickWall())
        h.flow.holdAt(0.4f, 0.02f)   // beside the board
        assertNull(h.st.hold!!.slot)
        h.flow.dropWall()
        assertEquals(Phase.IDLE, h.st.phase)
        assertNotNull(h.st.back)
        assertEquals(10, h.st.pos.left[Q.YOU])
        assertTrue(h.flow.pickWall())   // and it can be picked again
    }

    @Test fun goingToTheBackgroundWithAWallInHandPutsItBack() = runTest {
        val h = Harness(this); game(h, "you=e3 ai=e7 side=0")
        h.flow.pickWall(); h.flow.holdOver(w("c4h"))
        h.flow.onBackground()
        assertEquals(Phase.IDLE, h.st.phase)
        assertNull(h.st.hold)
        assertEquals(10, h.store.saved!!.now.pos.left[Q.YOU])
    }

    @Test fun theWatchdogSettlesAStuckAnimation() = runTest {
        val h = Harness(this); game(h)
        h.flow.tapCell(c("e2"))
        // simulate a lost animation job
        val f = GameFlow::class.java.getDeclaredField("turnJob").apply { isAccessible = true }
        (f.get(h.flow) as Job).cancel()
        runCurrent()
        advanceTimeBy(GameFlow.STEP_MS + 100); runCurrent()
        assertEquals(Phase.ANIM, h.st.phase)
        advanceTimeBy(GameFlow.GOAL_MS + GameFlow.WATCHDOG_MS); runCurrent()
        assertTrue(h.log.any { it.startsWith("WATCHDOG") })
        assertTrue(h.st.phase != Phase.ANIM)
    }

    @Test fun undoPutsTheWallBackAndRestoresTheCount() = runTest {
        val h = Harness(this); game(h, "you=e3 ai=e7 side=0")
        h.flow.pickWall(); h.flow.holdOver(w("e6h")); h.flow.dropWall()
        settle()   // AI answers
        assertEquals(Phase.IDLE, h.st.phase)
        assertEquals(9, h.st.pos.left[Q.YOU])
        assertTrue(h.st.canUndo)
        h.flow.requestUndo()
        assertEquals(10, h.st.pos.left[Q.YOU])
        assertTrue(h.st.pos.walls.none { it.side == Q.YOU })
        assertEquals(c("e3"), h.st.pos.pawns[Q.YOU]); assertEquals(c("e7"), h.st.pos.pawns[Q.AI])
        assertEquals(Phase.IDLE, h.st.phase)
    }

    @Test fun undoWhileTheAiThinksTakesBackOnlyYourMove() = runTest {
        val h = Harness(this); game(h, "you=e3 ai=e7 side=0")
        h.flow.holdThinking = true
        h.flow.tapCell(c("e4"))
        settle(GameFlow.STEP_MS + 10)
        assertEquals(Phase.THINKING, h.st.phase)
        h.flow.requestUndo()
        assertEquals(Phase.IDLE, h.st.phase)
        assertEquals(c("e3"), h.st.pos.pawns[Q.YOU])
        h.flow.holdThinking = false
        settle()
        assertEquals(c("e7"), h.st.pos.pawns[Q.AI])   // the cancelled search never moved
    }

    @Test fun restoreWhileTheAiWasThinkingSearchesAgain() = runTest {
        val store = MemStore()
        val h = Harness(this, store); game(h, "you=e3 ai=e7 side=0")
        h.flow.holdThinking = true
        h.flow.tapCell(c("e4")); settle(GameFlow.STEP_MS + 10)
        assertEquals(Phase.THINKING, h.st.phase)
        h.flow.onBackground()
        val h2 = Harness(this, store)
        h2.flow.start(); runCurrent()
        assertTrue(h2.st.restored)
        assertEquals(Phase.THINKING, h2.st.phase)
        settle()
        assertEquals(Phase.IDLE, h2.st.phase)
        assertEquals(Q.YOU, h2.st.side)
    }

    @Test fun reachingRow9WinsAndOnlyYourWinPlaysTheFanfare() = runTest {
        val h = Harness(this); game(h, "you=e8 ai=c3 side=0")
        h.flow.tapCell(c("e9"))
        settle(GameFlow.STEP_MS + GameFlow.GOAL_MS + 50)
        assertEquals(Phase.RESULT, h.st.phase)
        assertEquals(Q.YOU, h.st.winner)
        assertTrue(Sound.WIN in h.sounds); assertFalse(Sound.LOSE in h.sounds)
        assertNull(h.store.saved)
        val h2 = Harness(this); game(h2)
        h2.flow.requestConfirm(ConfirmKind.RESIGN); h2.flow.confirm()
        assertEquals(Q.AI, h2.st.winner); assertTrue(h2.st.resigned)
        assertTrue(Sound.LOSE in h2.sounds); assertFalse(Sound.WIN in h2.sounds)
    }

    @Test fun aConfirmLocksTheVolume() = runTest {
        val h = Harness(this); game(h)
        h.flow.requestConfirm(ConfirmKind.RESIGN)
        h.flow.tapCell(c("e2"))
        assertFalse(h.flow.pickWall())
        assertEquals(c("e1"), h.st.pos.pawns[Q.YOU])
        h.flow.cancelConfirm()
        h.flow.uiLocked = true
        h.flow.tapCell(c("e2"))
        assertEquals(c("e1"), h.st.pos.pawns[Q.YOU])
    }

    @Test fun hintUsesTheMediumEngineAndBandsDefaultByLevel() = runTest {
        val h = Harness(this); game(h, "you=e3 ai=e7 side=0")
        h.flow.setHint(true); settle(100)
        assertNotNull(h.st.hint)
        assertTrue(h.st.bandsOn)
        game(h, level = Engine.HARD)
        assertFalse(h.st.bandsOn)
    }

    @Test fun firstGameCoachLinesShowOnceAndTheLitMovesStopAfterThree() = runTest {
        val h = Harness(this, settings = Settings(firstRunDone = true, coachDone = false)); game(h, "you=e2 ai=e9 walls=d8v1.e8h0 left=9,9 side=0")
        assertTrue(h.st.coachLit)
        h.flow.pickWall(); assertTrue(h.st.coachHold)
        h.flow.holdOver(w("f8v")); assertTrue(h.st.coachRed)
        h.flow.holdAt(1f, 1f); h.flow.dropWall()
        repeat(3) {
            h.flow.tapCell(h.st.steps.first { s -> Q.row(s.to) > Q.row(s.from) || true }.to)
            settle()
        }
        assertEquals(3, h.st.turnsYou)
        assertFalse(h.st.coachLit)
    }

    /** design mustPass M9 (QA q1-2): fresh install, never press [开始下棋], play two moves from the tab, quit, cold start -> S16. */
    @Test fun firstRunViaTabIsRestored() = runTest {
        val store = MemStore()
        var shared = Settings()
        val h = Harness(this, store, settings = shared)
        h.flow.start(); runCurrent()
        assertTrue(h.st.welcome); assertFalse(h.settings.firstRunDone)
        h.flow.tapCell(c("e2")); settle()
        assertTrue("the first move counts as seeing the welcome", h.settings.firstRunDone)
        assertFalse(h.st.welcome)
        h.flow.tapCell(c("e3")); settle()
        val pos = h.st.pos
        h.flow.onBackground()            // quit / force-stop: the game is on disk
        shared = h.settings
        val h2 = Harness(this, store, settings = shared.copy(firstRunDone = false))   // even if the flag had not been saved
        h2.flow.start(); runCurrent()
        assertTrue(h2.st.restored)
        assertFalse(h2.st.welcome)
        assertEquals(pos, h2.st.pos)
        assertEquals(c("e3"), h2.st.pos.pawns[Q.YOU])
    }
}
