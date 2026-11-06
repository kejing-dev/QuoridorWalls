package io.embodit.quoridorwalls.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.quoridorwalls.domain.Anim
import io.embodit.quoridorwalls.domain.GameFlow
import io.embodit.quoridorwalls.domain.Layout
import io.embodit.quoridorwalls.domain.Move
import io.embodit.quoridorwalls.domain.Orient
import io.embodit.quoridorwalls.domain.Phase
import io.embodit.quoridorwalls.domain.PlayState
import io.embodit.quoridorwalls.domain.Pos
import io.embodit.quoridorwalls.domain.Q
import io.embodit.quoridorwalls.domain.Settings
import io.embodit.quoridorwalls.domain.V3
import io.embodit.quoridorwalls.domain.Wall
import io.embodit.quoridorwalls.platform.TAG
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** The GLB templates (tools/build_models.py). */
internal class Models(val board: Entity, val wall: Entity, val pawnYou: Entity, val pawnAi: Entity)

/**
 * Every entity of the board volume. Compose hands over [PlayState] + [Settings] through [apply]; everything that moves
 * (pawns, walls, the wall in hand, the ghost, bands, rings) is placed in [onFrame], driven by [BoardFrameSystem].
 *
 * ```
 * root (SpatialView origin = volume centre)
 * └ sceneRoot (shifted so design y -0.18 sits on the real volume floor)
 *   ├ tiltRoot (the board's near-bottom edge, tipped 20° about X): board-local space of Layout.kt
 *   │   board.glb, colliders (board, 81 "sq:<cell>", "rail"), 20 walls "wall:<side>:<k>", 2 pawns "pawn:<side>",
 *   │   rings / dots / bands / ghost / slot glows
 *   ├ seat card (AttachmentPanel, standing on the floor in front)
 *   └ key light
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
    private var seat: Entity? = null
    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f

    private val walls = List(2) { ArrayList<Entity>() }
    private val pawns = ArrayList<Entity>()
    private val dots = ArrayList<ModelEntity>()
    private val jumpRings = ArrayList<ModelEntity>()
    private val bands = List(2) { ArrayList<ModelEntity>() }
    private val slotGlows = ArrayList<ModelEntity>()
    private var selectRing: ModelEntity? = null
    private var hintRing: ModelEntity? = null
    private var alertRing: ModelEntity? = null
    private var hoverRing: ModelEntity? = null
    private var goalRing: ModelEntity? = null
    private val lastRings = ArrayList<ModelEntity>()
    private var ghost: ModelEntity? = null
    private var hintGhost: ModelEntity? = null
    private var flash: ModelEntity? = null

    /** Per-entity glide (undo, restore): when a target jumps without an explicit animation, slide there in 220 ms. */
    private class Glide(var target: V3? = null, var from: V3 = V3(0f, 0f, 0f), var to: V3 = V3(0f, 0f, 0f), var start: Long = -1, var shown: V3? = null)
    private val wallGlide = List(2) { List(Q.WALLS) { Glide() } }
    private val pawnGlide = List(2) { Glide() }

    @Volatile private var hover: String? = null
    @Volatile private var hoverAt = 0L
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

    /** D50 hover: what a resting pointer is over (null = nothing). Light feedback only; expires after 250 ms. */
    fun setHover(name: String?) {
        hoverAt = SystemClock.uptimeMillis()
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
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

    private fun collider(name: String, shape: ShapeResource, r: SceneResources): Entity = Entity().apply {
        setName(name)
        components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = r.physics))
        components.set(InteractableComponent())
    }

    private fun clone(e: Entity, name: String): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true; it.setName(name) }

    private fun mark(mesh: MeshResource, m: Material, name: String): ModelEntity =
        ModelEntity(mesh, m).apply { setName(name); enabled = false; tiltRoot.addChild(this) }

    /** Built in a few frames' worth of work on the main thread (design budget.startup: 20 walls + 81 colliders in chunks). */
    fun install(r: SceneResources, models: Models, seatCard: Entity?) {
        if (built) { r.close(); return }
        res = r
        val t0 = SystemClock.elapsedRealtime()
        tiltRoot.addChild(clone(models.board, "boardModel"))
        tiltRoot.addChild(collider("board", r.boardShape, r).also { place(it, V3(0f, Layout.BOARD_T / 2, -Layout.BOARD_D / 2)) })
        for (cell in 0 until Q.CELLS) tiltRoot.addChild(collider("sq:$cell", r.cellShape, r).also { place(it, lay.cellTop(cell, 0.001f)) })
        tiltRoot.addChild(collider("rail", r.railShape, r).also { place(it, V3(0f, Layout.BOARD_T + 0.012f, -Layout.RAIL / 2)) })
        for (side in 0..1) repeat(Q.WALLS) { k ->
            val e = clone(models.wall, "wall:$side:$k")
            e.addChild(collider("wall:$side:$k:hit", r.wallShapeH, r))
            tiltRoot.addChild(e)
            walls[side] += e
        }
        for (side in 0..1) {
            val p = clone(if (side == Q.YOU) models.pawnYou else models.pawnAi, "pawn:$side")
            p.addChild(collider("pawn:$side:hit", r.pawnShape, r).also { place(it, V3(0f, Layout.PAWN_H / 2, 0f)) })
            tiltRoot.addChild(p)
            pawns += p
        }
        repeat(5) { i -> dots += mark(r.dot, r.blue, "dot:$i"); jumpRings += mark(r.ringJump, r.blue, "jump:$i") }
        for (side in 0..1) repeat(24) { i -> bands[side] += mark(r.unitPlane, if (side == Q.YOU) r.bandGold else r.bandBlue, "band:$side:$i") }
        repeat(64) { i -> slotGlows += mark(r.unitPlane, r.slotGlow, "slotGlow:$i") }
        selectRing = mark(r.ringSelect, r.cream, "selectRing")
        hintRing = mark(r.ringCell, r.amber, "hintRing")
        alertRing = mark(r.ringCell, r.red, "alertRing")
        hoverRing = mark(r.ringCell, r.creamFaint, "hoverRing")
        goalRing = mark(r.ringGoal, r.gold, "goalRing")
        repeat(2) { i -> lastRings += mark(r.ringCell, r.faint, "lastRing:$i") }
        ghost = mark(r.ghost, r.ghostOk, "ghost").also { it.addChild(collider("ghost", r.wallShapeH, r)) }
        hintGhost = mark(r.ghost, r.ghostHint, "hintGhost")
        flash = mark(r.flash, r.redFlash, "flash")
        seat = seatCard?.also { sceneRoot.addChild(it); place(it, Layout.SEAT_CENTRE) }
        buildLight()
        fallback.forEach { it.removeFromParent() }
        fallback = emptyList()
        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms seat=${Layout.SEAT_CENTRE} ${Layout.SEAT_W_DP}x${Layout.SEAT_H_DP}dp")
        onFrame(0f)
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

    /** Pin an explicitly animated entity's glide to where it is drawn, so the glide never replays it. */
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

        // ---------------- walls
        val anim = st.anim
        for (side in 0..1) {
            val placed = pos.walls.filter { it.side == side }
            walls[side].forEachIndexed { k, e ->
                val inRail = k < pos.left[side]
                val placedWall: Wall? = if (inRail) null else placed.getOrNull(Q.WALLS - 1 - k)?.wall
                var target = if (placedWall != null) lay.wallCentre(placedWall) else lay.railCentre(side, k)
                var q = if (placedWall == null) qFlat else if (placedWall.o == Orient.H) qH else qV
                var shown: V3? = null
                val hold = st.hold
                val rej = st.reject
                val back = st.back
                when {
                    side == Q.YOU && hold != null && k == hold.railK -> {
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
                            // your wall sinks from the hand into the groove (60 ms), a small settle at the end
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
                }
                val g = wallGlide[side][k]
                val at = if (shown != null) { pin(g, target, shown); shown } else glide(g, target, now, reduced)
                place(e, at)
                e.transform().setQuaternion(q)
                e.enabled = true
            }
        }

        // ---------------- pawns
        val sel = st.phase == Phase.SELECTED
        for (side in 0..1) {
            val e = pawns[side]
            val cell = pos.pawns[side]
            var lift = if (sel && side == st.side) Layout.LIFT_SELECT else 0f
            val won = st.winner == side && !st.resigned || (st.goalAt > 0 && pos.winner() == side)
            if (won) lift = Layout.LIFT_GOAL
            val target = lay.pawnCentre(cell, lift) - V3(0f, Layout.PAWN_H / 2, 0f)
            var shown: V3? = null
            if (anim is Anim.Step && anim.side == side) {
                val e2 = elapsed(anim.startedAt)
                val t = if (reduced) 1f else (e2.toFloat() / anim.durationMs).coerceIn(0f, 1f)
                val a = lay.pawnCentre(anim.step.from) - V3(0f, Layout.PAWN_H / 2, 0f)
                val b = lay.pawnCentre(anim.step.to) - V3(0f, Layout.PAWN_H / 2, 0f)
                shown = lay.arc(a, b, lay.easeOut(t), if (anim.step.jump) Layout.LIFT_JUMP else Layout.LIFT_STEP)
            } else if (won && st.goalAt > 0 && !reduced) {
                val t = (elapsed(st.goalAt).toFloat() / GameFlow.GOAL_MS).coerceIn(0f, 1f)
                shown = lay.pawnCentre(cell) - V3(0f, Layout.PAWN_H / 2, 0f) + V3(0f, Layout.LIFT_GOAL * lay.easeOut(t), 0f)
            }
            val g = pawnGlide[side]
            val at = if (shown != null) { pin(g, target, shown); shown } else glide(g, target, now, reduced)
            place(e, at)
        }

        // ---------------- selection, legal steps (S04 / guided first game), hint, alert, last move, hover, goal
        val human = st.humansTurn
        val showSteps = human && (sel || (st.coachLit && st.phase == Phase.IDLE))
        val steps = if (showSteps) st.steps else emptyList()
        var di = 0; var ji = 0
        for (s in steps) {
            if (s.jump) jumpRings.getOrNull(ji++)?.let { place(it, lay.cellTop(s.to, 0.0012f)); it.transform().setQuaternion(qH); it.enabled = true }
            else dots.getOrNull(di++)?.let { place(it, lay.cellTop(s.to, 0.0012f)); it.enabled = true }
        }
        for (i in di until dots.size) dots[i].enabled = false
        for (i in ji until jumpRings.size) jumpRings[i].enabled = false
        selectRing?.let { ring ->
            ring.enabled = sel && human
            if (ring.enabled) place(ring, lay.cellTop(pos.pawns[st.side], 0.0012f))
        }
        val hint = st.hint?.takeIf { st.hintOn && human && (st.phase == Phase.IDLE || st.phase == Phase.SELECTED) }
        hintRing?.let { ring ->
            val h = hint as? Move.Step
            ring.enabled = h != null
            if (h != null) {
                place(ring, lay.cellTop(h.to, 0.0016f))
                val k = if (reduced) 1f else 1f + 0.05f * sin(clock * 5f)
                ring.transform().setScaleVector(Vector3(k, 1f, k))
            }
        }
        hintGhost?.let { g ->
            val h = hint as? Move.Place
            g.enabled = h != null
            if (h != null) { place(g, lay.wallCentre(h.wall)); g.transform().setQuaternion(if (h.wall.o == Orient.H) qH else qV) }
        }
        val al = st.alert
        alertRing?.let { ring ->
            val flashing = al?.cell != null && (st.freezeMs != null || (now - al.at < 600 && ((now - al.at) / 100) % 2 == 0L))
            ring.enabled = flashing
            if (flashing) place(ring, lay.cellTop(al!!.cell!!, 0.0018f))
        }
        val last = st.last?.move as? Move.Step
        // last move: a faint ring on the square the pawn came from
        val lastFrom = last?.from?.takeIf { st.anim == null && st.phase != Phase.RESULT && it != pos.pawns[0] && it != pos.pawns[1] }
        lastRings.forEachIndexed { i, ring ->
            ring.enabled = i == 0 && lastFrom != null
            if (ring.enabled) place(ring, lay.cellTop(lastFrom!!, 0.0008f))
        }
        hoverRing?.let { ring ->
            val h = hover?.takeIf { now - hoverAt < 250 }
            val cell = when {
                h == null -> null
                h.startsWith("sq:") -> h.removePrefix("sq:").toIntOrNull()
                h.startsWith("pawn:") -> h.removePrefix("pawn:").toIntOrNull()?.let { pos.pawns[it] }
                else -> null
            }
            ring.enabled = cell != null
            if (cell != null) place(ring, lay.cellTop(cell, 0.0010f))
        }
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
                g.transform().setQuaternion(if (w.o == Orient.H) qPlane else Quat(Vector3(0f, 1f, 0f), (PI / 2).toFloat()).times(qPlane))
                g.transform().setScaleVector(Vector3(Layout.WALL_L, 0.0035f, 1f))
            }
        }

        // ---------------- path bands (T8): grow from the pawn over 250 ms after a change
        val showBands = st.bandsOn && st.config != null && st.phase != Phase.SETUP
        val grow = if (reduced) 1f else (elapsed(st.bandsAt).toFloat() / GameFlow.BAND_MS).coerceIn(0f, 1f)
        for (side in 0..1) {
            val path = if (showBands) st.bands[side] else emptyList()
            val n = (path.size - 1).coerceAtLeast(0)
            val visible = kotlin.math.ceil(n * grow).toInt()
            bands[side].forEachIndexed { i, seg ->
                if (i >= visible || i >= n) { seg.enabled = false; return@forEachIndexed }
                val a = lay.cellTop(path[i], 0.001f + side * 0.0002f); val b = lay.cellTop(path[i + 1], 0.001f + side * 0.0002f)
                val horizontal = abs(a.x - b.x) > abs(a.z - b.z)
                val off = if (side == Q.YOU) -0.0025f else 0.0025f
                val mid = a.lerp(b, 0.5f) + if (horizontal) V3(0f, 0f, off) else V3(off, 0f, 0f)
                place(seg, mid)
                seg.transform().setQuaternion(if (horizontal) qPlane else Quat(Vector3(0f, 1f, 0f), (PI / 2).toFloat()).times(qPlane))
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

    fun evidence(): String {
        val p = pawns.mapIndexed { i, e -> "pawn$i=${e.transform().position}" }.joinToString(" ")
        return "halfY=$regionHalfY seat=${Layout.SEAT_CENTRE} e1=${lay.toScene(lay.cellTop(Q.cell(4, 0)))} e9=${lay.toScene(lay.cellTop(Q.cell(4, 8)))} $p"
    }

    fun dispose() {
        res?.close()
        res = null
    }

    companion object {
        sealed interface Hit
        data class CellHit(val cell: Int) : Hit
        data class PawnHit(val side: Int) : Hit
        data object RailHit : Hit
        data object GhostHit : Hit
        data object EmptyHit : Hit

        /** A collider / entity name -> what was tapped. */
        fun hitOf(name: String?): Hit {
            if (name == null) return EmptyHit
            val f = name.split(":")
            return when (f[0]) {
                "sq" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until Q.CELLS }?.let { CellHit(it) } ?: EmptyHit
                "pawn" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..1 }?.let { PawnHit(it) } ?: EmptyHit
                "rail" -> RailHit
                "ghost" -> GhostHit
                else -> EmptyHit
            }
        }
    }
}
