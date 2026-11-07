package io.embodit.quoridorwalls.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Named states of design states[] that live in the game (S01 / S10 / S11 / S12 / S13 / S16 are pages, overlays or flags). */
enum class Phase(val id: String) {
    LOADING("S00"), SETUP("S02"), IDLE("S03"), SELECTED("S04"), ANIM("S05"), THINKING("S06"), HOLDING("S07"), REJECTED("S08"), RESULT("S09"),
}

enum class Sound { SELECT, PLACE, JUMP, GRAB, WALL, BOUNCE, ILLEGAL, BAND, WIN, LOSE }

/** One-liners for taps that do nothing (design T2 and friends). [cell] flashes red in the volume when set. */
enum class AlertKind { WALL_BETWEEN, DIAGONAL, THEIRS, FAR, NO_WALLS, HOLD_TO_DRAG, NOT_YOUR_TURN, NO_UNDO, ORIENT_NEEDS_WALL }

data class Alert(val kind: AlertKind, val cell: Int?, val at: Long)

/** Something moving in the volume (S05). The position is already committed when it starts. */
sealed interface Anim {
    val side: Int
    val startedAt: Long
    val durationMs: Long

    data class Step(override val side: Int, val step: Move.Step, override val startedAt: Long, override val durationMs: Long) : Anim

    /**
     * A wall going into its slot. [railK] = the rail slot it came from; [fromHand] = board-local centre of the wall in your
     * hand when you let go (null for the AI: its wall lifts out of the far rail first, then flies over).
     */
    data class Wall(
        override val side: Int, val wall: io.embodit.quoridorwalls.domain.Wall, val railK: Int, val fromHand: V3?,
        override val startedAt: Long, override val durationMs: Long,
    ) : Anim
}

/** S07: your wall in hand. [slot] = where the ghost snapped (null = off the board), [fail] = why it can't go there. */
data class Hold(val railK: Int, val orient: Orient, val slot: Wall?, val fail: WallFail?, val lastSlot: Wall?)

/** S08: a rejected wall flashing red, then flying back to rail slot [railK]. */
data class Reject(val wall: Wall, val fail: WallFail, val railK: Int, val fromHand: V3, val startedAt: Long)

/** A wall in hand dropped off the board: it glides back to its rail slot (T6, 220 ms). */
data class Return(val railK: Int, val fromHand: V3, val startedAt: Long)

enum class ConfirmKind { RESIGN, EXIT }

/** The one state every surface reads (board scene, control window, seat card, debug dump; D38). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val config: GameConfig? = null,
    val pos: Pos = Pos.START,
    val side: Int = Q.YOU,
    val steps: List<Move.Step> = emptyList(),
    val anim: Anim? = null,
    val hold: Hold? = null,
    val reject: Reject? = null,
    val back: Return? = null,
    val alert: Alert? = null,
    val turnNo: Int = 0,
    val stats: Stats = Stats(),
    val turnsYou: Int = 0,
    val last: LastMove? = null,
    val winner: Int? = null,
    val resigned: Boolean = false,
    val goalAt: Long = 0,
    val hintOn: Boolean = false,
    val hint: Move? = null,
    val bandsOn: Boolean = true,
    /** The two shortest paths as cells (you / the AI); a legal ghost wall previews them (T8). */
    val bands: List<List<Int>> = listOf(emptyList(), emptyList()),
    val bandsAt: Long = 0,
    val confirm: ConfirmKind? = null,
    val undo: List<Snap> = emptyList(),
    val welcome: Boolean = false,
    val firstGame: Boolean = false,
    /** Coach lines of the first game: shown the first time a wall is lifted / the first time a ghost turns red. */
    val coachHold: Boolean = false,
    val coachRed: Boolean = false,
    val restored: Boolean = false,
    val savedAvailable: Boolean = false,
    val savedTurn: Int = 0,
    val thinkMs: Long = 0,
    /** Debug freeze: animations stop this many ms after their start (only those started after [freezeAt]). */
    val freezeMs: Long? = null,
    val freezeAt: Long = 0,
) {
    val humansTurn: Boolean get() = config?.isHuman(side) == true
    val inGame: Boolean get() = config != null && phase !in setOf(Phase.LOADING, Phase.SETUP, Phase.RESULT)
    val hasProgress: Boolean get() = inGame && (turnNo > 0 || undo.size > 1)
    /** Lit moves of the guided first game (design T13): the first three of your turns. */
    val coachLit: Boolean get() = firstGame && turnsYou < 3
    val wallsLeft: List<Int> get() = pos.left
    val canUndo: Boolean get() = config != null && when (phase) {
        Phase.IDLE, Phase.SELECTED -> humansTurn && undo.size >= 2
        Phase.THINKING -> undo.isNotEmpty()
        else -> false
    }

    fun snap(): Snap = Snap(pos, side, turnNo, stats, last)
}

/**
 * Turn sequencing (rules - state - render: the middle layer). Main-thread confined: every public call and every coroutine
 * it launches runs on [scope]; the AI runs on [engineDispatcher]. The volume, the control window and the debug broadcast
 * all call these same functions (profile D29).
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound) -> Unit = {},
    private val log: (String) -> Unit = {},
    seed: Int = 20261105,
) {
    companion object {
        const val STEP_MS = 220L
        const val JUMP_MS = 320L
        const val SINK_MS = 60L
        const val AI_LIFT_MS = 150L
        const val AI_FLY_MS = 300L
        const val FLASH_MS = 300L
        const val FLY_BACK_MS = 300L
        const val RETURN_MS = 220L
        const val GOAL_MS = 400L
        const val BAND_MS = 250L
        const val REDUCED_MS = 120L
        const val ALERT_MS = 1600L
        const val MIN_THINK_MS = 400L
        const val WATCHDOG_MS = 1500L
        const val UNDO_LIMIT = 120
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value
    private val layout = Layout()
    private val tracker = SlotTracker(layout)

    private var rng = Random(seed)
    var aiSeed: Int = seed
    var holdThinking: Boolean = false
    /** A window-only confirmation (exit / new game) is open: the volume is locked too. */
    @Volatile var uiLocked: Boolean = false
    /** Where the wall in your hand is (board-local centre). Read by the scene every frame; never goes through [state]. */
    @Volatile var holdPoint: V3? = null
    /** Engine time scale (tests run the AI with a frozen clock and node budgets). */
    var engineClock: () -> Long = { System.nanoTime() / 1_000_000 }

    private var turnJob: Job? = null
    private var alertJob: Job? = null
    private var hintJob: Job? = null
    private var watchdog: Job? = null
    private var pendingSnap: Snap? = null

    private fun set(next: PlayState) { _state.value = next }
    private val reduced: Boolean get() = settings().reducedMotion

    // ------------------------------------------------------------------ lifecycle

    /**
     * S00 exits (design q1-2): an unfinished saved game always comes back (S16), whatever firstRunDone says; otherwise the
     * first run opens a default easy game (you first) with the rules page (S01); otherwise S02.
     */
    fun start() {
        val saved = store.load()
        if (saved != null && saved.now.pos.winner() == null) { restore(saved); return }
        if (!settings().firstRunDone) {
            newGame(GameConfig(Engine.EASY, Q.YOU))
            set(s.copy(welcome = true))
            log("STATE S01 (default game, rules page open)")
            return
        }
        set(PlayState(phase = Phase.SETUP)); log("STATE S02")
    }

    /** The welcome is over: [开始下棋] or the first move you make (design q1-2: "走过第一步或点开始即算看过引导"). */
    private fun firstRunSeen(why: String) {
        if (!settings().firstRunDone) { onSettings(settings().copy(firstRunDone = true)); log("FIRSTRUN done ($why)") }
        if (s.welcome) set(s.copy(welcome = false))
    }

    /** [开始下棋] on the rules page: the welcome is over (the easy game is already set up). */
    fun welcomeDone() { firstRunSeen("start button"); log("WELCOME done") }

    fun continueSaved(): Boolean {
        val saved = store.load() ?: return false
        if (saved.now.pos.winner() != null) return false
        restore(saved)
        return true
    }

    private fun restore(saved: SavedGame) {
        cancelJobs()
        val n = saved.now
        set(PlayState(
            phase = Phase.IDLE, config = saved.config, pos = n.pos, side = n.side, turnNo = n.turnNo, stats = n.stats, last = n.last,
            undo = saved.undo, hintOn = saved.hintOn, bandsOn = saved.bandsOn, restored = true, savedAvailable = true,
            turnsYou = 3,
        ))
        refreshBands()
        log("GAME restored side=${n.side} turn=${n.turnNo} walls=${n.pos.walls.size} S16")
        nextTurn()
    }

    fun newGame(setup: GameSetup) {
        val first = when (setup.first) { FirstPick.ME -> Q.YOU; FirstPick.THEM -> Q.AI; FirstPick.RANDOM -> if (rng.nextBoolean()) Q.YOU else Q.AI }
        onSettings(settings().copy(lastSetup = setup))
        newGame(GameConfig(setup.level.coerceIn(1, 3), first))
    }

    fun newGame(config: GameConfig) {
        cancelJobs()
        set(PlayState(
            phase = Phase.IDLE, config = config, side = config.firstSide, firstGame = !settings().coachDone && config.level != Engine.HARD,
            hintOn = s.hintOn, bandsOn = config.bandsDefault, welcome = s.welcome, freezeMs = s.freezeMs, freezeAt = s.freezeAt,
        ))
        refreshBands()
        log("GAME new level=${config.level} first=${config.firstSide}")
        persist()
        nextTurn()
    }

    /** [再来一局]: same level, the other side starts. */
    fun again() { s.config?.let { newGame(it.copy(firstSide = Q.other(it.firstSide))) } }

    fun toMenu() {
        cancelJobs()
        val saved = store.load()
        set(PlayState(phase = Phase.SETUP, savedAvailable = saved != null, savedTurn = saved?.now?.turnNo ?: 0, hintOn = s.hintOn))
        log("STATE S02")
    }

    /** Background / the window closed mid-hold: a wall in hand goes back to its rail, the AI stops, moves land, the game is saved. */
    fun onBackground() {
        if (s.phase == Phase.HOLDING) cancelHold("background")
        if (s.anim != null || s.reject != null) { turnJob?.cancel(); settle() }
        if (s.phase == Phase.THINKING) turnJob?.cancel()
        persist()
        log("BACKGROUND phase=${s.phase.id}")
    }

    fun onForeground() {
        val st = s
        if (st.config == null || turnJob?.isActive == true) return
        if (st.phase == Phase.THINKING) aiTurn()
    }

    /** Finishes an animation at once (background, watchdog). */
    private fun settle() {
        when {
            s.reject != null -> { set(s.copy(reject = null, phase = Phase.IDLE)); refreshHint() }
            s.anim != null -> afterMove()
        }
    }

    // ------------------------------------------------------------------ input: cells and pawns

    private fun locked(what: String): Boolean {
        if (s.confirm != null || uiLocked) { log("LOCKED by confirm (S13) $what"); return true }
        return false
    }

    /** A pinch / trigger on a cell (or on a pawn, which is its cell). */
    fun tapCell(cell: Int) {
        val st = s
        if (locked("cell")) return
        log("TAP cell ${Q.name(cell)} phase=${st.phase.id}")
        if (st.config == null) return
        if (!st.humansTurn || st.phase !in setOf(Phase.IDLE, Phase.SELECTED)) {
            if (st.phase == Phase.THINKING) alert(AlertKind.NOT_YOUR_TURN, null)
            return
        }
        val mine = st.pos.pawns[st.side]
        if (cell == mine) {
            if (st.phase == Phase.SELECTED) { set(st.copy(phase = Phase.IDLE)); log("DESELECT -> S03") } else select()
            return
        }
        val step = st.steps.firstOrNull { it.to == cell }
        // D27 (forgiving): a lit target is taken straight away, selected or not (S03 "点亮格直接走").
        if (step != null) { commitStep(step); return }
        val fail = st.pos.board(st.side).stepFail(st.side, cell)
        alert(when (fail) {
            StepFail.WALL -> AlertKind.WALL_BETWEEN
            StepFail.DIAGONAL -> AlertKind.DIAGONAL
            StepFail.THEIRS -> AlertKind.THEIRS
            else -> AlertKind.FAR
        }, cell)
    }

    fun tapPawn(side: Int) = tapCell(s.pos.pawns[side])

    /** A window button that can't act right now answers with a sentence instead of silence (D43). */
    fun explain(kind: AlertKind) { if (s.config != null) alert(kind, null) }

    /** A pinch on the board frame / anywhere that is not a cell, a pawn or the rail: drop the selection. */
    fun tapEmpty() {
        if (locked("empty")) return
        log("TAP empty phase=${s.phase.id}")
        if (s.phase == Phase.SELECTED) set(s.copy(phase = Phase.IDLE))
    }

    /** A tap (not a drag) on your rail: walls are moved by holding and dragging (design input.wall, v1 one path). */
    fun tapRail() {
        if (locked("rail")) return
        val st = s
        log("TAP rail phase=${st.phase.id}")
        if (!st.humansTurn || st.phase !in setOf(Phase.IDLE, Phase.SELECTED)) return
        if (st.phase == Phase.SELECTED) set(st.copy(phase = Phase.IDLE))
        alert(if (st.pos.left[st.side] <= 0) AlertKind.NO_WALLS else AlertKind.HOLD_TO_DRAG, null)
    }

    private fun select() {
        set(s.copy(phase = Phase.SELECTED, alert = null))   // D51 补: selecting is silent
        log("SELECT ${Q.name(s.pos.pawns[s.side])} steps=${s.steps.joinToString(",")} S04")
    }

    private fun alert(kind: AlertKind, cell: Int?) {
        val at = clock()
        set(s.copy(alert = Alert(kind, cell, at)))
        onSound(Sound.ILLEGAL)
        log("ALERT $kind ${cell?.let { Q.name(it) } ?: ""}")
        alertJob?.cancel()
        alertJob = scope.launch {
            delay(ALERT_MS)
            awaitUnfrozen()
            if (s.alert?.at == at) set(s.copy(alert = null))
        }
    }

    // ------------------------------------------------------------------ input: the wall in hand (S07)

    /** Hold-and-drag started on your rail: the top wall lifts and follows the hand / ray. Returns true when taken. */
    fun pickWall(): Boolean {
        val st = s
        if (locked("pick")) return false
        if (!st.humansTurn || st.phase !in setOf(Phase.IDLE, Phase.SELECTED)) { log("PICK refused phase=${st.phase.id}"); return false }
        val k = st.pos.left[st.side] - 1
        if (k < 0) { alert(AlertKind.NO_WALLS, null); return false }
        tracker.reset()
        holdPoint = layout.railCentre(st.side, k) + V3(0f, Layout.HOLD_UP + Layout.WALL_H / 2, 0f)
        val coach = st.firstGame && !st.coachHold
        set(st.copy(phase = Phase.HOLDING, hold = Hold(k, Orient.H, null, null, null), alert = null, coachHold = st.coachHold || coach))
        onSound(Sound.GRAB)
        log("PICK rail=$k S07")
        return true
    }

    /**
     * The hand / ray moved: [x], [s] = board-local position of the wall in hand on the board plane. The ghost snaps to the
     * nearest slot with position hysteresis; legality and the band preview are recomputed only when the slot changes.
     */
    fun holdAt(x: Float, sAlong: Float) {
        val st = s
        val h = st.hold ?: return
        if (st.phase != Phase.HOLDING) return
        val cross = tracker.update(x, sAlong)
        holdPoint = V3(tracker.smoothX, Layout.BOARD_T + Layout.HOLD_UP + Layout.WALL_H / 2, -tracker.smoothS)
        val slot = cross?.let { Wall(it.first, it.second, h.orient) }
        if (slot == h.slot) return
        setHoldSlot(slot)
    }

    /** Debug `hover e3h` / the same as holding the pointer over that slot. */
    fun holdOver(w: Wall?) {
        val h = s.hold ?: return
        if (w == null) { tracker.reset(); holdAt(0f, -0.03f); return }   // just in front of the board: off the grid
        tracker.reset()
        holdAt(layout.crossX(w.c), layout.crossS(w.r))
        if (w.o != h.orient) toggleOrient()
    }

    private fun setHoldSlot(slot: Wall?) {
        val st = s
        val h = st.hold ?: return
        val fail = slot?.let { st.pos.board(st.side).wallFail(st.side, it) }
        val redCoach = fail != null && fail != WallFail.NO_WALLS && st.firstGame && !st.coachRed
        set(st.copy(hold = h.copy(slot = slot, fail = fail, lastSlot = slot ?: h.lastSlot), coachRed = st.coachRed || redCoach))
        refreshBands()
        log("HOLD slot=${slot ?: "-"} fail=${fail ?: "-"}")
    }

    /** [横 / 竖], or a pinch on the ghost with the other hand (T5): turns the ghost 90° and keeps it until the wall is back. */
    fun toggleOrient() {
        val st = s
        val h = st.hold ?: run { log("ORIENT ignored (no wall in hand)"); return }
        if (st.phase != Phase.HOLDING) return
        val o = h.orient.flip()
        set(st.copy(hold = h.copy(orient = o)))
        log("ORIENT $o")
        setHoldSlot(h.slot?.copy(o = o))
    }

    /** Let go (T3 / T4 / T6): legal slot -> the wall sinks in; red ghost -> S08; off the board -> back to the rail. */
    fun dropWall() {
        val st = s
        val h = st.hold ?: return
        if (st.phase != Phase.HOLDING) return
        val from = holdPoint ?: layout.railCentre(st.side, h.railK)
        holdPoint = null
        val slot = h.slot
        when {
            slot == null -> {
                set(st.copy(phase = Phase.IDLE, hold = null, back = Return(h.railK, from, clock())))
                log("DROP off board -> rail S03")
                refreshBands(); refreshHint()
            }
            h.fail != null -> reject(slot, h.fail, h.railK, from)
            else -> commitWall(slot, st.side, h.railK, from)
        }
    }

    /** The wall in hand goes back without being placed (background, the window closed, debug `back`). */
    fun cancelHold(why: String) {
        val st = s
        val h = st.hold ?: return
        val from = holdPoint ?: layout.railCentre(st.side, h.railK)
        holdPoint = null
        set(st.copy(phase = Phase.IDLE, hold = null, back = Return(h.railK, from, clock())))
        refreshBands()
        log("HOLD cancel ($why) -> rail")
    }

    private fun reject(w: Wall, fail: WallFail, railK: Int, from: V3) {
        val st = s
        set(st.copy(phase = Phase.REJECTED, hold = null, reject = Reject(w, fail, railK, from, clock())))
        refreshBands()
        onSound(Sound.ILLEGAL)
        log("REJECT $w $fail S08")
        turnJob?.cancel()
        turnJob = scope.launch {
            delay(if (reduced) FLASH_MS else FLASH_MS + FLY_BACK_MS)
            awaitUnfrozen()
            onSound(Sound.BOUNCE)
            set(s.copy(phase = Phase.IDLE, reject = null))
            log("STATE S03 (after reject)")
            refreshHint()
        }
    }

    // ------------------------------------------------------------------ committing moves

    private fun commitStep(step: Move.Step) {
        val st = s
        val side = st.side
        val after = st.pos.apply(side, step)
        val dur = if (reduced) REDUCED_MS else if (step.jump) JUMP_MS else STEP_MS
        val stats = if (side == Q.YOU) st.stats.copy(movesYou = st.stats.movesYou + 1) else st.stats
        startAnim(st, after, Anim.Step(side, step, clock(), dur), stats, LastMove(side, step))
        log("MOVE side=$side ${Q.name(step.from)}-${Q.name(step.to)}${if (step.jump) " jump" else ""} by=${if (st.humansTurn) "human" else "ai"} S05")
        turnJob?.cancel()
        turnJob = scope.launch {
            delay(dur)
            awaitUnfrozen()
            onSound(if (step.jump) Sound.JUMP else Sound.PLACE)
            if (after.winner() == side) {
                set(s.copy(goalAt = clock()))
                delay(if (reduced) 0 else GOAL_MS)
                awaitUnfrozen()
            }
            afterMove()
        }
    }

    private fun commitWall(w: Wall, side: Int, railK: Int, fromHand: V3?) {
        val st = s
        val after = st.pos.apply(side, Move.Place(w))
        val dur = if (reduced) REDUCED_MS else if (fromHand != null) SINK_MS else AI_LIFT_MS + AI_FLY_MS
        var stats = st.stats
        if (side == Q.YOU) {
            val before = st.pos.board(Q.AI).dist(Q.AI); val now = after.board(Q.AI).dist(Q.AI)
            stats = stats.copy(detourGiven = stats.detourGiven + (now - before).coerceAtLeast(0))
        }
        startAnim(st, after, Anim.Wall(side, w, railK, fromHand, clock(), dur), stats, LastMove(side, Move.Place(w)))
        log("WALL side=$side $w rail=$railK by=${if (st.humansTurn) "human" else "ai"} left=${after.left} S05")
        turnJob?.cancel()
        turnJob = scope.launch {
            delay(dur)
            awaitUnfrozen()
            onSound(Sound.WALL)
            if (s.bandsOn) onSound(Sound.BAND)
            afterMove()
        }
    }

    /** Commit first, animate second (Cloud Air rule): the new position is saved before anything moves. */
    private fun startAnim(st0: PlayState, after: Pos, anim: Anim, stats: Stats, last: LastMove) {
        if (anim.side == Q.YOU) firstRunSeen("first move")
        val st = if (anim.side == Q.YOU) s else st0
        val turnsYou = if (anim.side == Q.YOU) st.turnsYou + 1 else st.turnsYou
        set(st.copy(phase = Phase.ANIM, pos = after, anim = anim, hold = null, alert = null, hint = null, stats = stats, last = last,
            turnsYou = turnsYou, restored = false, back = null))
        refreshBands()
        val win = after.winner()
        pendingSnap = if (win == null) Snap(after, Q.other(anim.side), st.turnNo + 1, stats, last) else null
        if (win == null) persist() else store.clear()
        watchdog?.cancel()
        val started = anim
        watchdog = scope.launch {
            delay(anim.durationMs + GOAL_MS + WATCHDOG_MS)
            if (s.freezeMs == null && s.anim === started) { log("WATCHDOG anim stuck, settling"); turnJob?.cancel(); afterMove() }
        }
    }

    private fun afterMove() {
        val st = s
        val anim = st.anim ?: return
        pendingSnap = null
        watchdog?.cancel()
        set(st.copy(anim = null))
        st.pos.winner()?.let { finish(it, resigned = false); return }
        set(s.copy(side = Q.other(anim.side), turnNo = st.turnNo + 1))
        log("TURN -> side=${s.side} turn=${s.turnNo}")
        nextTurn()
    }

    private fun nextTurn() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT) return
        if (cfg.isHuman(st.side)) {
            val steps = st.pos.board(st.side).steps(st.side)
            val snap = st.snap()
            val undo = (if (st.undo.lastOrNull() == snap) st.undo else st.undo + snap).takeLast(UNDO_LIMIT)
            set(st.copy(phase = Phase.IDLE, steps = steps, undo = undo))
            log("STATE S03 side=${st.side} steps=${steps.joinToString(",")} walls=${st.pos.left}")
            persist()
            refreshHint()
        } else aiTurn()
    }

    // ------------------------------------------------------------------ AI (S06)

    private fun aiTurn() {
        val st = s
        val cfg = st.config ?: return
        set(st.copy(phase = Phase.THINKING, steps = emptyList(), hint = null))
        log("THINK level=${cfg.level} S06")
        persist()
        val pos = st.pos
        val side = st.side
        turnJob?.cancel()
        turnJob = scope.launch {
            val t0 = clock()
            val choice = withContext(engineDispatcher) { Engine.choose(pos, side, cfg.level, Random(aiSeed + st.turnNo * 31), engineClock) }
            val waited = clock() - t0
            if (waited < MIN_THINK_MS) delay(MIN_THINK_MS - waited)
            while (holdThinking) delay(100)
            if (!coroutineContext.isActive || s.phase != Phase.THINKING || s.pos != pos) return@launch
            log("AI ${choice.move} score=${choice.score} depth=${choice.depth} nodes=${choice.nodes} ${choice.ms}ms level=${cfg.level}")
            set(s.copy(thinkMs = choice.ms))
            when (val m = choice.move) {
                is Move.Step -> commitStep(m)
                is Move.Place -> commitWall(m.wall, side, pos.left[side] - 1, null)
            }
        }
    }

    // ------------------------------------------------------------------ bands, hint

    private fun refreshBands() {
        val st = s
        val h = st.hold
        val preview = if (h?.slot != null && h.fail == null) st.pos.apply(st.side, Move.Place(h.slot)) else st.pos
        val b = preview.board()
        val bands = listOf(b.path(Q.YOU), b.path(Q.AI))
        if (bands != st.bands) set(st.copy(bands = bands, bandsAt = clock()))
    }

    private fun refreshHint() {
        val st = s
        hintJob?.cancel()
        if (!st.hintOn || !st.humansTurn || st.phase !in setOf(Phase.IDLE, Phase.SELECTED)) { if (st.hint != null) set(st.copy(hint = null)); return }
        val pos = st.pos; val side = st.side
        hintJob = scope.launch {
            // design gameSpec.hint: always the medium engine (depth 2), whatever the opponent's level
            val c = withContext(engineDispatcher) { Engine.choose(pos, side, Engine.MEDIUM, Random(aiSeed + 7), engineClock) }
            if (s.pos == pos && s.side == side && s.hintOn) { set(s.copy(hint = c.move)); log("HINT ${c.move}") }
        }
    }

    fun setHint(on: Boolean) {
        set(s.copy(hintOn = on, hint = if (on) s.hint else null))
        log("HINT ${if (on) "on" else "off"}")
        refreshHint()
        persist()
    }

    fun setBands(on: Boolean) {
        set(s.copy(bandsOn = on, bandsAt = clock()))
        log("BANDS ${if (on) "on" else "off"}")
        persist()
    }

    // ------------------------------------------------------------------ undo (T9)

    /** One whole turn back (yours + the AI's). While the AI thinks: stop it and take back only your move. */
    fun requestUndo() {
        val st = s
        if (locked("undo")) return
        if (!st.canUndo) { log("UNDO not available phase=${st.phase.id}"); return }
        val target: Snap
        val keep: List<Snap>
        if (st.phase == Phase.THINKING) {
            target = st.undo.last(); keep = st.undo
        } else {
            target = st.undo[st.undo.size - 2]; keep = st.undo.dropLast(1)
        }
        cancelJobs()
        set(st.copy(
            phase = Phase.IDLE, pos = target.pos, side = target.side, turnNo = target.turnNo, stats = target.stats, last = target.last,
            undo = keep, anim = null, hold = null, reject = null, alert = null, hint = null, steps = target.pos.board(target.side).steps(target.side),
            back = null,
        ))
        refreshBands()
        log("UNDO -> turn=${target.turnNo} walls=${target.pos.walls.size} left=${target.pos.left}")
        persist()
        refreshHint()
    }

    // ------------------------------------------------------------------ confirm (S13), result (S09)

    fun requestConfirm(kind: ConfirmKind) {
        if (s.phase == Phase.HOLDING) cancelHold("confirm")
        set(s.copy(confirm = kind)); log("CONFIRM $kind S13")
    }

    fun cancelConfirm() { set(s.copy(confirm = null)); log("CONFIRM cancel") }

    /** Confirms the open dialog. EXIT is handled by the window (it owns the navigator); it only saves here. */
    fun confirm() {
        val kind = s.confirm ?: return
        set(s.copy(confirm = null))
        log("CONFIRM yes $kind")
        when (kind) {
            ConfirmKind.RESIGN -> finish(Q.AI, resigned = true)
            ConfirmKind.EXIT -> onBackground()
        }
    }

    private fun finish(winner: Int, resigned: Boolean) {
        cancelJobs()
        val st = s
        set(st.copy(phase = Phase.RESULT, winner = winner, resigned = resigned, anim = null, hold = null, reject = null, steps = emptyList(),
            hint = null, confirm = null, goalAt = if (resigned) 0 else st.goalAt))
        // the opponent's win is not a fanfare (profile D44)
        onSound(if (winner == Q.YOU) Sound.WIN else Sound.LOSE)
        log("RESULT winner=$winner resigned=$resigned moves=${st.stats.movesYou} detour=${st.stats.detourGiven} S09")
        if (st.firstGame) onSettings(settings().copy(coachDone = true))
        store.clear()
    }

    // ------------------------------------------------------------------ debug

    fun freeze(ms: Long?) { set(s.copy(freezeMs = ms, freezeAt = clock())); log("FREEZE ${ms ?: "off"}") }

    private suspend fun awaitUnfrozen() { while (coroutineContext.isActive && s.freezeMs != null) delay(50) }

    /** Debug `setup`: replace the position, back to the side to move's turn. */
    fun setup(pos: Pos, side: Int = s.side) {
        val cfg = s.config ?: GameConfig()
        cancelJobs()
        set(s.copy(config = cfg, pos = pos, side = side, anim = null, hold = null, reject = null, winner = null, phase = Phase.IDLE,
            undo = emptyList(), confirm = null, alert = null, steps = emptyList(), back = null))
        refreshBands()
        log("SETUP ${SaveCodec.snap(s.snap())}")
        persist()
        nextTurn()
    }

    fun debugState(mutator: (PlayState) -> PlayState) = set(mutator(s))

    fun reseed(seed: Int) { rng = Random(seed); aiSeed = seed; log("SEED $seed") }

    fun dump(): String {
        val st = s
        return "phase=${st.phase.id} cfg=${st.config} side=${st.side} turn=${st.turnNo} pos=${SaveCodec.snap(st.snap())} steps=${st.steps.joinToString(",")} " +
            "hold=${st.hold} reject=${st.reject?.let { "${it.wall} ${it.fail}" }} alert=${st.alert?.kind} hint=${st.hint} bands=${st.bandsOn}:${st.bands.map { it.size - 1 }} " +
            "confirm=${st.confirm} undo=${st.undo.size} welcome=${st.welcome} first=${st.firstGame}/${st.turnsYou} stats=${st.stats} winner=${st.winner} freeze=${st.freezeMs}"
    }

    private fun persist() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT || st.phase == Phase.SETUP) return
        val snap = if (st.anim != null) pendingSnap ?: return else st.snap()
        store.save(SavedGame(cfg, snap, st.undo, st.hintOn, st.bandsOn))
    }

    private fun cancelJobs() {
        turnJob?.cancel(); alertJob?.cancel(); hintJob?.cancel(); watchdog?.cancel()
        pendingSnap = null
        holdPoint = null
        tracker.reset()
    }
}
