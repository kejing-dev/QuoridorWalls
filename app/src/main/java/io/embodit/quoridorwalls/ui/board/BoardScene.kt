package io.embodit.quoridorwalls.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.quoridorwalls.domain.Anim
import io.embodit.quoridorwalls.domain.GameFlow
import io.embodit.quoridorwalls.domain.HoverTracker
import io.embodit.quoridorwalls.domain.Layout
import io.embodit.quoridorwalls.domain.Move
import io.embodit.quoridorwalls.domain.Orient
import io.embodit.quoridorwalls.domain.Phase
import io.embodit.quoridorwalls.domain.PlayState
import io.embodit.quoridorwalls.domain.Pos
import io.embodit.quoridorwalls.domain.Q
import io.embodit.quoridorwalls.domain.Settings
import io.embodit.quoridorwalls.domain.Skin
import io.embodit.quoridorwalls.domain.V3
import io.embodit.quoridorwalls.domain.Wall
import io.embodit.quoridorwalls.platform.TAG
import io.embodit.quoridorwalls.ui.board.input.GazeTargets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** The GLB templates (tools/build_models.py + tools/build_tripo_models.py), one set per skin. */
internal class SkinModels(val board: Entity, val stand: Entity, val wall: Entity, val pawnYou: Entity, val pawnAi: Entity)

internal class Models(val sets: Map<Skin, SkinModels>)

/**
 * Every entity of the board volume (design v0.1.7). Compose hands over [PlayState] + [Settings] through [apply]; everything
 * that moves (pawns, walls, the wall in hand, the ghost, bands, marks) is placed in [onFrame], driven by [BoardFrameSystem].
 *
 * ```
 * root (SpatialView origin = volume centre)
 * └ sceneRoot (shifted so design y -0.18 sits on the real volume floor)
 *   ├ stand (book-stand, one model per skin), seat card, key light
 *   └ tiltRoot (the board's near-bottom edge (-0.05, -0.18, +0.27), tipped 34°): board-local space of Layout.kt
 *       board model per skin (no collider), 81 cell holders "sq:<n>" (click area + visible gaze plate "mk:sq:<n>:0",
 *       system HoverEffect), 20 wall holders "wall:<side>:<k>" + 2 pawn holders "pawn:<side>" (collider + HoverEffect,
 *       one model child per skin), rail holder, marks floating 1.2 cm up on light columns, bands, ghost
 * ```
 */
internal class BoardScene(private val flow: GameFlow) {
    val root: Entity = Entity().apply { setName("root") }
    private val sceneRoot = Entity().apply { setName("sceneRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val lay = Layout()

    private var res: SceneResources? = null
    private var fallback: List<Entity> = emptyList()
    private var built = false
    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f
    private var skinShown: Skin? = null

    private val boards = HashMap<Skin, Entity>()
    private val stands = HashMap<Skin, Entity>()
    private val walls = List(2) { ArrayList<Entity>() }
    private val wallModels = List(2) { ArrayList<Map<Skin, Entity>>() }
    private val pawns = ArrayList<Entity>()
    private val pawnModels = ArrayList<Map<Skin, Entity>>()
    private val gazePlates = ArrayList<ModelEntity>()

    // marks (all float MARK_UP above their cell; each has a light column)
    private class Mark(val e: ModelEntity, val halo: ModelEntity?, val column: ModelEntity)
    private val dots = ArrayList<Mark>()
    private val jumps = ArrayList<Mark>()
    private var hintMark: Mark? = null
    private var alertMark: Mark? = null
    private var hoverMark: Mark? = null
    private var lastMark: Mark? = null
    private var selectRing: ModelEntity? = null
    private var goalRing: ModelEntity? = null
    private val bands = List(2) { ArrayList<ModelEntity>() }
    private val slotGlows = ArrayList<ModelEntity>()
    private var railGlow: ModelEntity? = null
    private var ghost: ModelEntity? = null
    private var hintGhost: ModelEntity? = null
    private var flash: ModelEntity? = null

    /** Per-entity glide (undo, restore): when a target jumps without an explicit animation, slide there in 220 ms. */
    private class Glide(var target: V3? = null, var from: V3 = V3(0f, 0f, 0f), var to: V3 = V3(0f, 0f, 0f), var start: Long = -1, var shown: V3? = null)
    private val wallGlide = List(2) { List(Q.WALLS) { Glide() } }
    private val pawnGlide = List(2) { Glide() }

    private val hover = HoverTracker()
    private var clock = 0f
    private var slotCacheKey: String? = null
    private var slotCache: List<Wall> = emptyList()

    private val qFlat: Quat = Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat())   // a wall lying in its rail
    private val qH: Quat = Quat.identity()
    private val qV: Quat = Quat(Vector3(0f, 1f, 0f), (PI / 2).toFloat())
    private val qPlane: Quat = Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat())   // XY plane -> lying on the board

    init {
        root.addChild(sceneRoot)
        sceneRoot.addChild(tiltRoot)
        tiltRoot.transform().setPosition(Vector3(Layout.PIVOT.x, Layout.PIVOT.y, Layout.PIVOT.z))
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), lay.theta))
    }

    val ready: Boolean get() = built

    /**
     * D50 hover (design input.hover): what a pointer rests on; stays lit until exit / another target, or 1.5 s without
     * events ([HoverTracker]). [sticky] = debug screenshots. Light feedback only, never sound or state.
     */
    fun setHover(name: String?, sticky: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val before = hover.current(now)
        hover.hover(name, if (sticky) now + 600_000 else now)
        if (name != null && name != before) Log.i(TAG, "HOVER $name")
    }

    /** The real volume height: design y -0.18 = the floor. */
    fun setRegion(halfY: Float) {
        if (abs(halfY - regionHalfY) < 1e-4f) return
        regionHalfY = halfY
        sceneRoot.transform().setPosition(Vector3(0f, 0.18f - halfY, 0f))
        Log.i(TAG, "SCENE region halfY=$halfY")
    }

    private fun place(e: Entity, v: V3) = e.transform().setPosition(Vector3(v.x, v.y, v.z))

    /** Scene (design volume) coordinates of a board-local point, for the drag anchor. */
    fun toScene(local: V3): V3 = lay.toScene(local)

    /** S00 stand-in: a plain slab until the models are in (design lifecycle). */
    fun showFallback(slab: MeshResource, material: Material) {
        if (built || fallback.isNotEmpty()) return
        val board = ModelEntity(slab, material).apply { setName("fallbackBoard") }
        place(board, V3(0f, Layout.BOARD_T / 2, -Layout.BOARD_D / 2))
        fallback = listOf(board)
        fallback.forEach { tiltRoot.addChild(it) }
        Log.i(TAG, "SCENE fallback shown S00")
    }

    private fun clone(e: Entity, name: String): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true; it.setName(name) }

    private fun markOf(mesh: MeshResource, m: Material, haloMesh: MeshResource?, r: SceneResources, name: String): Mark {
        val e = ModelEntity(mesh, m).apply { setName(name); enabled = false; tiltRoot.addChild(this) }
        val halo = haloMesh?.let { ModelEntity(it, r.outline).apply { setName("$name:halo"); transform().setPosition(Vector3(0f, -0.0005f, 0f)); e.addChild(this) } }
        val col = ModelEntity(r.column, r.columnMat).apply { setName("$name:column"); enabled = false; tiltRoot.addChild(this) }
        return Mark(e, halo, col)
    }

    private fun flat(mesh: MeshResource, m: Material, name: String): ModelEntity =
        ModelEntity(mesh, m).apply { setName(name); enabled = false; tiltRoot.addChild(this) }

    /** Shows mark [m] floating above [cell] with its light column; hides it when [cell] is null. */
    private fun showMark(m: Mark?, cell: Int?, scale: Float = 1f) {
        m ?: return
        if (cell == null) { m.e.enabled = false; m.column.enabled = false; return }
        place(m.e, lay.mark(cell))
        m.e.transform().setScaleVector(Vector3(scale, 1f, scale))
        m.e.enabled = true
        place(m.column, lay.cellTop(cell, Layout.MARK_UP / 2))
        m.column.transform().setScaleVector(Vector3(1f, Layout.MARK_UP, 1f))
        m.column.enabled = true
    }

    /**
     * Built over several frames (design budget.startup: "20 墙 + 81 格碰撞体分帧建"): boards + stands, cells in two halves,
     * then walls / pawns / marks; each chunk yields a frame.
     */
    suspend fun install(r: SceneResources, models: Models, seatCard: Entity?) {
        if (built) { r.close(); return }
        res = r
        val t0 = SystemClock.elapsedRealtime()
        val chunks = ArrayList<Long>()
        fun lap() { chunks += SystemClock.elapsedRealtime() - t0 - chunks.sum() }
        for ((skin, set) in models.sets) {
            boards[skin] = clone(set.board, "${BoardNames.BOARD}:${skin.name}").also { tiltRoot.addChild(it) }
            stands[skin] = clone(set.stand, "stand:${skin.name}").also { sceneRoot.addChild(it) }
        }
        tiltRoot.addChild(Entity().apply { setName(BoardNames.RAIL); place(this, V3(0f, Layout.BOARD_T + 0.012f, -Layout.RAIL / 2)); GazeTargets.install(this, r.railShape, r.physics) })
        lap(); kotlinx.coroutines.delay(16)
        // GazeTargets (board-input 通用模式): a holder per cell = whole-cell click area, a visible gaze plate as child
        fun cellRange(range: IntRange) {
            val holders = GazeTargets.cells(tiltRoot, range.associate { BoardNames.square(it) to lay.cellTop(it).let { v -> Vector3(v.x, v.y, v.z) } },
                r.cellShape, r.physics, r.gazeParts, r.gaze.getValue(settings.skin))
            holders.values.forEach { h -> h.getChildren().forEach { c -> (c as? ModelEntity)?.let { gazePlates += it } } }
        }
        cellRange(0..40)
        lap(); kotlinx.coroutines.delay(16)
        cellRange(41 until Q.CELLS)
        lap(); kotlinx.coroutines.delay(16)
        for (side in 0..1) repeat(Q.WALLS) { k ->
            val holder = Entity().apply { setName(BoardNames.wall(side, k)) }
            GazeTargets.install(holder, r.wallShapeH, r.physics)
            val kids = models.sets.mapValues { (skin, set) -> clone(set.wall, BoardNames.wallModel(side, k, skin.name)).also { holder.addChild(it) } }
            tiltRoot.addChild(holder)
            walls[side] += holder
            wallModels[side] += kids
        }
        for (side in 0..1) {
            // the pawn's click area is its whole cell, 3 cm tall (design pieces: "碰撞体 = 所在格的格面盒"); models are children
            val holder = Entity().apply { setName(BoardNames.pawn(side)) }
            val kids = models.sets.mapValues { (skin, set) ->
                clone(if (side == Q.YOU) set.pawnYou else set.pawnAi, BoardNames.pawnModel(side, skin.name)).also { m -> holder.addChild(m); place(m, V3(0f, -0.015f, 0f)) }
            }
            GazeTargets.install(holder, r.pawnShape, r.physics)
            tiltRoot.addChild(holder)
            pawns += holder
            pawnModels += kids
        }
        repeat(5) { i -> dots += markOf(r.dot, r.white, r.dotHalo, r, "dot:$i"); jumps += markOf(r.ringJump, r.white, r.ringJumpHalo, r, "jump:$i") }
        hintMark = markOf(r.ringMark, r.amber, null, r, "hintMark")
        alertMark = markOf(r.ringMark, r.red, null, r, "alertMark")
        hoverMark = markOf(r.ringMark, r.creamFaint, null, r, "hoverMark")
        lastMark = markOf(r.ringLast, r.grey, null, r, "lastMark")
        selectRing = flat(r.ringSelect, r.amber, "selectRing")
        goalRing = flat(r.ringGoal, r.gold, "goalRing")
        for (side in 0..1) repeat(24) { i -> bands[side] += flat(r.unitPlane, if (side == Q.YOU) r.bandYou.getValue(settings.skin) else r.bandAi.getValue(settings.skin), "band:$side:$i") }
        repeat(64) { i -> slotGlows += flat(r.unitPlane, r.slotGlow, "slotGlow:$i") }
        railGlow = flat(r.unitPlane, r.amberSoft, "railGlow")
        ghost = flat(r.ghost, r.ghostOk, BoardNames.GHOST).also { GazeTargets.install(it, r.wallShapeH, r.physics) }
        hintGhost = flat(r.ghost, r.ghostHint, "hintGhost")
        flash = flat(r.flash, r.redFlash, "flash")
        seatCard?.let { sceneRoot.addChild(it); place(it, Layout.SEAT_CENTRE) }
        buildLight()
        fallback.forEach { it.removeFromParent() }
        fallback = emptyList()
        built = true
        applySkin(settings.skin)
        lap()
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms chunks=$chunks seat=${Layout.SEAT_CENTRE} ${Layout.SEAT_W_DP}x${Layout.SEAT_H_DP}dp skin=${settings.skin}")
        onFrame(0f)
    }

    /** design skins.switch: only materials and meshes change; colliders, sizes and the occlusion rule stay. */
    private fun applySkin(skin: Skin) {
        if (skin == skinShown) return
        val r = res ?: return
        skinShown = skin
        boards.forEach { (s, e) -> e.enabled = s == skin }
        stands.forEach { (s, e) -> e.enabled = s == skin }
        wallModels.forEach { side -> side.forEach { m -> m.forEach { (s, e) -> e.enabled = s == skin } } }
        pawnModels.forEach { m -> m.forEach { (s, e) -> e.enabled = s == skin } }
        gazePlates.forEach { it.components[ModelComponent::class.java]?.materials?.set(0, r.gaze.getValue(skin)) }
        bands[Q.YOU].forEach { it.components[ModelComponent::class.java]?.materials?.set(0, r.bandYou.getValue(skin)) }
        bands[Q.AI].forEach { it.components[ModelComponent::class.java]?.materials?.set(0, r.bandAi.getValue(skin)) }
        Log.i(TAG, "SKIN $skin")
    }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            // B24: a moderate warm key light from the front left, above (the design's render light (-0.45, 1, 0.55)), with shadows.
            light.components.set(DirectionalLightComponent(Color4(1f, 0.95f, 0.88f, 1f), 0.85f, true))
            val q = Quat(Vector3(0f, 1f, 0f), (-30 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-55 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            root.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    fun apply(next: PlayState, s: Settings) {
        state = next
        settings = s
        if (!built) return
        applySkin(s.skin)
        onFrame(0f)
    }

    // ------------------------------------------------------------------ per frame

    private fun glide(g: Glide, target: V3, now: Long, reduced: Boolean): V3 {
        val prev = g.target
        if (prev == null) { g.target = target; g.shown = target; return target }
        if (dist(prev, target) > 0.001f) {
            g.from = g.shown ?: prev; g.to = target; g.start = if (reduced) -1 else now; g.target = target
        }
        if (g.start < 0) { g.shown = target; return target }
        val t = ((now - g.start).toFloat() / GameFlow.RETURN_MS).coerceIn(0f, 1f)
        val p = lay.arc(g.from, g.to, lay.easeOut(t), 0.02f)
        if (t >= 1f) g.start = -1
        g.shown = p
        return p
    }

    private fun pin(g: Glide, target: V3, shown: V3) { g.target = target; g.shown = shown; g.start = -1 }

    private fun dist(a: V3, b: V3): Float { val d = a - b; return kotlin.math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z) }

    fun onFrame(dt: Float) {
        clock += dt
        if (!built) return
        val r = res ?: return
        val st = state
        val now = SystemClock.uptimeMillis()
        val reduced = settings.reducedMotion
        fun elapsed(start: Long): Long = (now - start).let { e -> st.freezeMs?.takeIf { start >= st.freezeAt }?.let { min(e, it) } ?: e }
        val pos = st.pos
        val human = st.humansTurn
        val hovered = hover.current(now)
        val railHover = hovered == BoardNames.RAIL && human && st.phase in setOf(Phase.IDLE, Phase.SELECTED) && pos.left[Q.YOU] > 0

        // ---------------- walls
        val anim = st.anim
        for (side in 0..1) {
            val placed = pos.walls.filter { it.side == side }
            walls[side].forEachIndexed { k, e ->
                val inRail = k < pos.left[side]
                val placedWall: Wall? = if (inRail) null else placed.getOrNull(Q.WALLS - 1 - k)?.wall
                val target = if (placedWall != null) lay.wallCentre(placedWall) else lay.railCentre(side, k)
                var q = if (placedWall == null) qFlat else if (placedWall.o == Orient.H) qH else qV
                var shown: V3? = null
                var visible = true
                val hold = st.hold
                val rej = st.reject
                val back = st.back
                when {
                    side == Q.YOU && hold != null && k == hold.railK -> {
                        // design walls: once the pointer is over the board only the ghost shows; the solid wall hides
                        visible = hold.slot == null
                        shown = flow.holdPoint ?: lay.railCentre(side, k)
                        q = if (hold.orient == Orient.H) qH else qV
                    }
                    side == Q.YOU && rej != null && k == rej.railK -> {
                        val e2 = elapsed(rej.startedAt)
                        val above = lay.wallCentre(rej.wall, sunk = false)
                        if (reduced || e2 >= GameFlow.FLASH_MS) {
                            val t = if (reduced) 1f else ((e2 - GameFlow.FLASH_MS).toFloat() / GameFlow.FLY_BACK_MS).coerceIn(0f, 1f)
                            shown = lay.arc(above, target, lay.easeOut(t), 0.03f)
                            if (t < 0.5f) q = if (rej.wall.o == Orient.H) qH else qV
                        } else { shown = above; q = if (rej.wall.o == Orient.H) qH else qV }
                    }
                    side == Q.YOU && back != null && k == back.railK && inRail -> {
                        val t = if (reduced) 1f else (elapsed(back.startedAt).toFloat() / GameFlow.RETURN_MS).coerceIn(0f, 1f)
                        shown = lay.arc(back.fromHand, target, lay.easeOut(t), 0.02f)
                        if (t < 0.4f) q = qH
                    }
                    anim is Anim.Wall && anim.side == side && placedWall == anim.wall -> {
                        val e2 = elapsed(anim.startedAt)
                        val hand = anim.fromHand
                        shown = if (reduced) target else if (hand != null) {
                            hand.lerp(target, lay.bounce((e2.toFloat() / GameFlow.SINK_MS).coerceIn(0f, 1f)))
                        } else {
                            val rail = lay.railCentre(side, anim.railK)
                            if (e2 < GameFlow.AI_LIFT_MS) {
                                q = qFlat
                                rail + V3(0f, 0.03f * lay.easeOut(e2.toFloat() / GameFlow.AI_LIFT_MS), 0f)
                            } else {
                                val t = ((e2 - GameFlow.AI_LIFT_MS).toFloat() / GameFlow.AI_FLY_MS).coerceIn(0f, 1f)
                                if (t < 0.25f) q = qFlat
                                lay.arc(rail + V3(0f, 0.03f, 0f), target, lay.bounce(t), 0.04f)
                            }
                        }
                    }
                    // rail hover: the wall a press would lift rises 3 mm (design input.hover ②)
                    side == Q.YOU && railHover && k == pos.left[Q.YOU] - 1 -> shown = target + V3(0f, 0.003f, 0f)
                }
                val g = wallGlide[side][k]
                val at = if (shown != null) { pin(g, target, shown); shown } else glide(g, target, now, reduced)
                place(e, at)
                e.transform().setQuaternion(q)
                e.enabled = visible
            }
        }
        railGlow?.let { glow ->
            glow.enabled = railHover
            if (railHover) {
                val c = lay.railCentre(Q.YOU, pos.left[Q.YOU] - 1)
                place(glow, V3(c.x, Layout.BOARD_T + 0.0004f, c.z))
                glow.transform().setQuaternion(qPlane)
                glow.transform().setScaleVector(Vector3(Layout.WALL_L + 0.008f, Layout.WALL_H + 0.008f, 1f))
            }
        }

        // ---------------- pawns (selection never lifts: a ring at the foot instead)
        val sel = st.phase == Phase.SELECTED
        for (side in 0..1) {
            val e = pawns[side]
            val cell = pos.pawns[side]
            val won = st.winner == side && !st.resigned || (st.goalAt > 0 && pos.winner() == side)
            val lift = if (won) Layout.LIFT_GOAL else 0f
            // holder origin = centre of its 3 cm click box (models sit 1.5 cm below it)
            val target = lay.cellTop(cell, 0.015f + lift)
            var shown: V3? = null
            if (anim is Anim.Step && anim.side == side) {
                val e2 = elapsed(anim.startedAt)
                val t = if (reduced) 1f else (e2.toFloat() / anim.durationMs).coerceIn(0f, 1f)
                shown = lay.arc(lay.cellTop(anim.step.from, 0.015f), lay.cellTop(anim.step.to, 0.015f), lay.easeOut(t), if (anim.step.jump) Layout.LIFT_JUMP else Layout.LIFT_STEP)
            } else if (won && st.goalAt > 0 && !reduced) {
                val t = (elapsed(st.goalAt).toFloat() / GameFlow.GOAL_MS).coerceIn(0f, 1f)
                shown = lay.cellTop(cell, 0.015f + Layout.LIFT_GOAL * lay.easeOut(t))
            }
            val g = pawnGlide[side]
            place(e, if (shown != null) { pin(g, target, shown); shown } else glide(g, target, now, reduced))
        }

        // ---------------- marks: legal steps (S04 / guided first game), hint, alert, last move, hover, selection, goal
        val showSteps = human && (sel || (st.coachLit && st.phase == Phase.IDLE))
        val steps = if (showSteps) st.steps else emptyList()
        var di = 0; var ji = 0
        for (s in steps) if (s.jump) jumps.getOrNull(ji++)?.let { showMark(it, s.to) } else dots.getOrNull(di++)?.let { showMark(it, s.to) }
        for (i in di until dots.size) showMark(dots[i], null)
        for (i in ji until jumps.size) showMark(jumps[i], null)
        selectRing?.let { ring ->
            ring.enabled = sel && human
            if (ring.enabled) place(ring, lay.cellTop(pos.pawns[st.side], 0.0012f))
        }
        val hint = st.hint?.takeIf { st.hintOn && human && (st.phase == Phase.IDLE || st.phase == Phase.SELECTED) }
        showMark(hintMark, (hint as? Move.Step)?.to, if (reduced) 1f else 1f + 0.05f * sin(clock * 5f))
        hintGhost?.let { g ->
            val h = hint as? Move.Place
            g.enabled = h != null
            if (h != null) { place(g, lay.wallCentre(h.wall)); g.transform().setQuaternion(if (h.wall.o == Orient.H) qH else qV) }
        }
        val al = st.alert
        val flashing = al?.cell != null && (st.freezeMs != null || (now - al.at < 600 && ((now - al.at) / 100) % 2 == 0L))
        showMark(alertMark, if (flashing) al!!.cell else null)
        val last = st.last?.move as? Move.Step
        val lastFrom = last?.from?.takeIf { st.anim == null && st.phase != Phase.RESULT && it != pos.pawns[0] && it != pos.pawns[1] }
        showMark(lastMark, lastFrom)
        val hoverCell = when {
            hovered == null -> null
            hovered.startsWith("sq:") -> hovered.removePrefix("sq:").toIntOrNull()
            hovered.startsWith("pawn:") -> hovered.removePrefix("pawn:").toIntOrNull()?.let { pos.pawns[it] }
            else -> null
        }
        showMark(hoverMark, hoverCell)
        goalRing?.let { ring ->
            val w = if (st.goalAt > 0) pos.winner() else null
            ring.enabled = w != null
            if (w != null) place(ring, lay.cellTop(pos.pawns[w], 0.0012f))
        }

        // ---------------- the ghost (S07), the red flash (S08), lit slots of the guided first game
        val hold = st.hold?.takeIf { st.phase == Phase.HOLDING }
        ghost?.let { g ->
            val slot = hold?.slot ?: hold?.lastSlot
            g.enabled = slot != null
            if (slot != null && hold != null) {
                place(g, lay.wallCentre(slot))
                g.transform().setQuaternion(if (slot.o == Orient.H) qH else qV)
                g.components[ModelComponent::class.java]?.materials?.set(0, when { hold.slot == null -> r.ghostOff; hold.fail != null -> r.ghostBad; else -> r.ghostOk })
            }
        }
        flash?.let { f ->
            val rej = st.reject
            val e2 = rej?.let { elapsed(it.startedAt) } ?: Long.MAX_VALUE
            val on = rej != null && e2 < GameFlow.FLASH_MS && (reduced || (e2 / 75) % 2 == 0L || st.freezeMs != null)
            f.enabled = on
            if (on) {
                place(f, if (reduced) lay.wallCentre(rej!!.wall) else lay.wallCentre(rej!!.wall, sunk = false))
                f.transform().setQuaternion(if (rej.wall.o == Orient.H) qH else qV)
            }
        }
        val lit = if (hold != null && st.coachLit) litSlots(pos, hold.orient) else emptyList()
        slotGlows.forEachIndexed { i, g ->
            val w = lit.getOrNull(i)
            g.enabled = w != null
            if (w != null) {
                val c = lay.wallCentre(w)
                place(g, V3(c.x, Layout.CELL_TOP + 0.0006f, c.z))
                g.transform().setQuaternion(if (w.o == Orient.H) qPlane else qV.times(qPlane))
                g.transform().setScaleVector(Vector3(Layout.WALL_L, 0.0035f, 1f))
            }
        }

        // ---------------- path bands (T8): 2 mm above the cells, grow from the pawn over 250 ms after a change
        val showBands = st.bandsOn && st.config != null && st.phase != Phase.SETUP
        val grow = if (reduced) 1f else (elapsed(st.bandsAt).toFloat() / GameFlow.BAND_MS).coerceIn(0f, 1f)
        for (side in 0..1) {
            val path = if (showBands) st.bands[side] else emptyList()
            val n = (path.size - 1).coerceAtLeast(0)
            val visible = kotlin.math.ceil(n * grow).toInt()
            bands[side].forEachIndexed { i, seg ->
                if (i >= visible || i >= n) { seg.enabled = false; return@forEachIndexed }
                val a = lay.cellTop(path[i], 0.002f + side * 0.0002f); val b = lay.cellTop(path[i + 1], 0.002f + side * 0.0002f)
                val horizontal = abs(a.x - b.x) > abs(a.z - b.z)
                val off = if (side == Q.YOU) -0.0025f else 0.0025f
                val mid = a.lerp(b, 0.5f) + if (horizontal) V3(0f, 0f, off) else V3(off, 0f, 0f)
                place(seg, mid)
                seg.transform().setQuaternion(if (horizontal) qPlane else qV.times(qPlane))
                seg.transform().setScaleVector(Vector3(Layout.PITCH + 0.008f, 0.008f, 1f))
                seg.enabled = true
            }
        }
    }

    private fun litSlots(pos: Pos, o: Orient): List<Wall> {
        val key = "${pos.walls}|${pos.pawns}|$o"
        if (key != slotCacheKey) {
            val b = pos.board(Q.YOU)
            slotCache = Wall.ALL.filter { it.o == o && b.wallFail(Q.YOU, it) == null }
            slotCacheKey = key
        }
        return slotCache
    }

    // ------------------------------------------------------------------ replay (board-input 命中回放, debug builds)

    /** The real entities a hit on [cell] / a pawn / the rail can land on, deepest first (for the debug `replay`). */
    fun replayEntities(what: String): List<Entity> {
        fun deepest(e: Entity): Entity { var x = e; while (x.getChildren().isNotEmpty()) x = x.getChildren().first(); return x }
        fun named(n: String): Entity? = findByName(tiltRoot, n)
        return when {
            what.startsWith("sq:") -> listOfNotNull(named(BoardNames.marker(what.removePrefix("sq:").toInt())), named(what))
            what.startsWith("pawn:") -> listOfNotNull(named(what)?.let { deepest(it) }, named(what))
            what == "rail" -> listOfNotNull(walls[Q.YOU].getOrNull(state.pos.left[Q.YOU] - 1)?.let { deepest(it) }, named(BoardNames.RAIL))
            else -> emptyList()
        }
    }

    private fun findByName(e: Entity, name: String): Entity? {
        if (runCatching { e.getName() }.getOrNull() == name) return e
        for (c in e.getChildren()) findByName(c, name)?.let { return it }
        return null
    }

    fun evidence(): String {
        val p = pawns.mapIndexed { i, e -> "pawn$i=${e.transform().position}" }.joinToString(" ")
        return "halfY=$regionHalfY skin=$skinShown seat=${Layout.SEAT_CENTRE} e1=${lay.toScene(lay.cellTop(Q.cell(4, 0)))} e9=${lay.toScene(lay.cellTop(Q.cell(4, 8)))} $p"
    }

    fun dispose() {
        res?.close()
        res = null
    }
}
