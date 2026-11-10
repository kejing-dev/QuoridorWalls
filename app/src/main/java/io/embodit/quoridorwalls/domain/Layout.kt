package io.embodit.quoridorwalls.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A small vector for layout maths (metres). Free of SDK types so it can be unit tested. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)
}

/** An axis-aligned box (scene space, or board-local space for things on the tilted board). */
data class Box3(val min: V3, val max: V3) {
    fun contains(p: V3, eps: Float = 0f) = p.x > min.x - eps && p.x < max.x + eps && p.y > min.y - eps && p.y < max.y + eps && p.z > min.z - eps && p.z < max.z + eps

    /** Does the segment a-b pass through the box (slab test)? */
    fun hitBySegment(a: V3, b: V3): Boolean {
        var t0 = 0f; var t1 = 1f
        val d = b - a
        val ad = floatArrayOf(a.x, a.y, a.z); val dd = floatArrayOf(d.x, d.y, d.z)
        val lo = floatArrayOf(min.x, min.y, min.z); val hi = floatArrayOf(max.x, max.y, max.z)
        for (i in 0..2) {
            if (abs(dd[i]) < 1e-9f) { if (ad[i] < lo[i] || ad[i] > hi[i]) return false; continue }
            var ta = (lo[i] - ad[i]) / dd[i]; var tb = (hi[i] - ad[i]) / dd[i]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    companion object {
        fun around(c: V3, size: V3) = Box3(c - size * 0.5f, c + size * 0.5f)
    }
}

/**
 * Every metre of the volume (design v0.1.7 spatialLayout, QA q1-1 geometry; v0.1.4 真机返工: both wall trays moved to
 * the front margin, the board lifted 4.5 cm on its stand so the seat card no longer covers them, volume 0.44 tall).
 * Scene space = the design's volume space: +X right, +Y up, +Z toward the user, the floor at y -0.18 and the volume centre
 * at y [VOL_CENTRE_Y] (volume 0.66 x 0.44 x 0.62, front face z +0.31). The board content sits 5 cm left of the volume centre (contentRoot x -0.05) so the control window that the
 * system places to the right moves away from the board.
 *
 * The board hangs from tiltRoot at its near-bottom edge (-0.05, -0.135, +0.27), tipped 34° about X (far edge up). Board-local
 * space: origin on that edge, +Y the board normal, -Z along the board toward the far edge, so a point on the top surface
 * at distance s from the near edge is local (x, BOARD_T, -s).
 *
 *   trays   front margin s 0..0.12: near pawn's 10 walls on the left (x -0.12), far pawn's on the right (x +0.12), each
 *           2 columns x 5 rows of lying walls (9.6 x 1.2 x 0.6), 1.2 cm between columns, 1.0 cm between rows
 *   grid    9 x 9 cells, pitch 5 cm, row r centre s = NEAR + (r + 0.5) x 0.05, column x = (c - 4) x 0.05; far frame 2 cm
 *   walls   9.6 x 0.6 x 1.2 cm, centred on crossing (c, r), sunk 2 mm: 1.0 cm shows above the cells
 *   pawns   squat turned pawn dia 3.2 x 2.4 cm; selection does not lift (a ring at its foot instead)
 *   marks   every landing / hint / hover mark floats 1.2 cm above its cell on a thin light column
 */
class Layout {
    companion object {
        const val PITCH = 0.05f
        const val CELL = 0.042f
        const val BOARD_W = 0.49f
        /** Front margin (the two wall trays) and far frame. */
        const val NEAR = 0.12f
        const val FAR = 0.02f
        const val BOARD_D = NEAR + 9 * PITCH + FAR
        const val BOARD_T = 0.025f
        /** Depth of the tray margin (kept as RAIL for the old name). */
        const val RAIL = NEAR
        const val CELL_RAISE = 0.0015f
        const val GROOVE_SINK = 0.002f
        /** Cell faces (local y). */
        const val CELL_TOP = BOARD_T + CELL_RAISE
        const val WALL_L = 0.096f
        const val WALL_T = 0.006f
        const val WALL_H = 0.012f
        /** A standing wall's bottom: 2 mm into the groove below the cell faces (its top is 1.0 cm above them). */
        const val WALL_BASE = CELL_TOP - GROOVE_SINK
        /** Slot stud on a crossing: dia 5 mm, 1.5 mm proud of the cell faces (README 标记尺寸 <= 5 mm). */
        const val STUD_D = 0.005f
        const val STUD_H = 0.005f   // from the groove floor (3.5 mm below the cell faces) to 1.5 mm above them
        const val PAWN_D = 0.032f
        const val PAWN_H = 0.024f
        const val LIFT_SELECT = 0f
        const val LIFT_STEP = 0.03f
        const val LIFT_JUMP = 0.04f
        const val LIFT_GOAL = 0.015f
        /** Landing / hint / hover marks float this high above their cell (design markers: 1.2 cm, hard requirement). */
        const val MARK_UP = 0.012f
        /** A wall in hand floats with its bottom 2 cm above the board surface (only while the pointer is off the board). */
        const val HOLD_UP = 0.02f
        const val TILT_DEG = 34f
        const val CONTENT_X = -0.05f
        val VOLUME = V3(0.66f, 0.44f, 0.62f)
        const val FLOOR_Y = -0.18f
        /** The volume centre in scene space (the floor stays at -0.18). */
        const val VOL_CENTRE_Y = FLOOR_Y + 0.22f
        /** The board's near edge sits this high above the floor so the seat card in front never covers the trays. */
        const val BOARD_LIFT = 0.045f
        val PIVOT = V3(CONTENT_X, FLOOR_Y + BOARD_LIFT, 0.27f)
        /** Seat card (AttachmentPanel, design coordinates.seatCard): 0.26 x 0.035 m standing on the floor at z +0.31, centred on the board. */
        val SEAT_SIZE = V3(0.26f, 0.035f, 0f)
        val SEAT_CENTRE = V3(CONTENT_X, -0.18f + 0.0175f, 0.31f)
        /** Measured on the PICO 6 emulator (royal-ur v0.1.0, tag width against a model of known size at the same depth). */
        const val DP_M = 0.00069f
        val SEAT_W_DP = (SEAT_SIZE.x / DP_M).roundToInt()
        val SEAT_H_DP = (SEAT_SIZE.y / DP_M).roundToInt()
        /** Position hysteresis of slotAt (design input.slotAt): stay until the pointer leaves 2.5 cm + 6 mm around the slot. */
        const val SLOT_HOLD = 0.031f
        /** The grid area plus a small margin: inside it the ghost wall snaps to a slot. */
        const val GRID_MARGIN = 0.012f
        /** Drag gain: pointer travel in metres -> travel of the wall in hand on the board. */
        const val DRAG_GAIN = 1.0f
        /** Trays: block centre x per side, column offset, first row s and row pitch (1.0 cm between lying walls). */
        val TRAY_X = floatArrayOf(-0.12f, 0.12f)
        const val TRAY_COL = WALL_L / 2 + 0.006f
        const val TRAY_S0 = 0.014f
        const val TRAY_PITCH = 0.022f
        /** A tray block (both columns, five rows) with a 6 mm margin: the per-side "rail:<side>" click area. */
        val TRAY_SIZE = V3(2 * WALL_L + 0.012f + 0.012f, 0f, 4 * TRAY_PITCH + WALL_H + 0.012f)
        /** Click box of a lying tray wall / the ghost: one ring bigger than the wall (D50 补 §15.6: >= 2 cm across). */
        val WALL_HIT = V3(WALL_L + 0.008f, 0.020f, 0.020f)
    }

    val theta: Float = Math.toRadians(TILT_DEG.toDouble()).toFloat()

    private fun rotX(v: V3, a: Float): V3 {
        val c = cos(a); val s = sin(a)
        return V3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
    }

    fun toScene(local: V3): V3 = PIVOT + rotX(local, theta)
    fun toLocal(scene: V3): V3 = rotX(scene - PIVOT, -theta)
    /** A direction (not a point) from local to scene. */
    fun dirToScene(d: V3): V3 = rotX(d, theta)

    fun surface(x: Float, s: Float, up: Float = 0f): V3 = V3(x, BOARD_T + up, -s)

    fun cellX(cell: Int) = (Q.col(cell) - 4) * PITCH
    fun cellS(cell: Int) = NEAR + (Q.row(cell) + 0.5f) * PITCH
    fun crossX(c: Int) = (c - 3.5f) * PITCH
    fun crossS(r: Int) = NEAR + (r + 1) * PITCH

    /** Local centre of a cell face. */
    fun cellTop(cell: Int, up: Float = 0f): V3 = V3(cellX(cell), CELL_TOP + up, -cellS(cell))

    /** Where a floating mark of [cell] sits (design markers: 1.2 cm above the cell, on a light column). */
    fun mark(cell: Int): V3 = cellTop(cell, MARK_UP)

    /** Local centre of a pawn standing on [cell], lifted by [lift]. */
    fun pawnCentre(cell: Int, lift: Float = 0f): V3 = cellTop(cell, PAWN_H / 2 + lift)

    /** Local centre of a wall standing in its slot ([sunk]) or floating above it in hand. */
    fun wallCentre(w: Wall, sunk: Boolean = true): V3 = V3(crossX(w.c), (if (sunk) WALL_BASE else CELL_TOP + HOLD_UP) + WALL_H / 2, -crossS(w.r))

    /**
     * Local centre of tray slot [k] (0..9) of [side]: lying flat (9.6 long along x, 1.2 wide along s, 0.6 high), in the
     * front margin, side 0 left / side 1 right; row k / 2 (0 = nearest the board edge), column k % 2. The highest k (taken
     * first) lies nearest the grid.
     */
    fun railCentre(side: Int, k: Int): V3 {
        val row = k / 2
        val col = if (k % 2 == 0) -1f else 1f
        return V3(TRAY_X[side] + col * TRAY_COL, BOARD_T + WALL_T / 2, -(TRAY_S0 + row * TRAY_PITCH))
    }

    /** Centre of the tray block of [side] on the frame top (the "rail:<side>" holder). */
    fun trayCentre(side: Int): V3 = V3(TRAY_X[side], BOARD_T + 0.004f, -(TRAY_S0 + 2 * TRAY_PITCH))

    /** Local centre of the slot target at crossing (c, r): a click box over the crossing, its marker stud on the groove. */
    fun slotCentre(c: Int, r: Int, up: Float = 0f): V3 = V3(crossX(c), CELL_TOP + up, -crossS(r))

    /** A flight between two points at time fraction [t]: a parabola peaking [arc] above the chord (local up). */
    fun arc(a: V3, b: V3, t: Float, arc: Float): V3 {
        val k = t.coerceIn(0f, 1f)
        return a.lerp(b, k) + V3(0f, 4f * arc * k * (1f - k), 0f)
    }

    /** Ease-out (decelerate into the landing), for slides and flights (profile D51: physics-like motion). */
    fun easeOut(t: Float): Float { val k = t.coerceIn(0f, 1f); return 1f - (1f - k) * (1f - k) * (1f - k) }

    /** A small landing bounce: 0 -> 1 with a 6 % overshoot hop at the end. */
    fun bounce(t: Float): Float {
        val k = t.coerceIn(0f, 1f)
        return if (k < 0.8f) easeOut(k / 0.8f) else 1f + 0.06f * sin(((k - 0.8f) / 0.2f) * Math.PI.toFloat())
    }

    /** Board-local (x, s) of a scene point projected on the board plane. */
    fun boardXS(scene: V3): Pair<Float, Float> { val l = toLocal(scene); return l.x to -l.z }

    fun onGrid(x: Float, s: Float): Boolean =
        abs(x) <= 4.5f * PITCH + GRID_MARGIN && s >= NEAR - GRID_MARGIN && s <= NEAR + 9 * PITCH + GRID_MARGIN

    /** The nearest crossing (design input.slotAt), clamped to 0..7. */
    fun nearestCross(x: Float, s: Float): Pair<Int, Int> =
        ((x / PITCH + 3.5f).roundToInt().coerceIn(0, 7)) to (((s - NEAR) / PITCH - 1f).roundToInt().coerceIn(0, 7))

    /** The book-stand under the board: two side plates in the y-z plane (front edge up to the lifted near edge). */
    fun standPlates(): List<Pair<Float, List<V3>>> {
        val far = toScene(V3(0f, 0f, -BOARD_D))
        val xs = listOf(CONTENT_X - BOARD_W / 2 + 0.03f, CONTENT_X + BOARD_W / 2 - 0.03f)
        return xs.map { x -> x to listOf(V3(x, FLOOR_Y, PIVOT.z - 0.01f), V3(x, FLOOR_Y, far.z + 0.005f), V3(x, far.y - 0.006f, far.z + 0.005f), V3(x, PIVOT.y - 0.004f, PIVOT.z - 0.01f)) }
    }

    // ------------------------------------------------------------------ occlusion (design occlusionRule, occlusion_check.py)

    /** An obstacle box: [local] = in board-local space (things on the tilted board), else scene space. */
    class Obstacle(val name: String, val box: Box3, val local: Boolean, val cylinder: Pair<V3, Float>? = null)

    fun boardBox(): Obstacle = Obstacle("board", Box3(V3(-BOARD_W / 2, 0f, -BOARD_D), V3(BOARD_W / 2, CELL_TOP, 0f)), true)

    fun railWallBoxes(): List<Obstacle> = (0..1).flatMap { side ->
        (0 until Q.WALLS).map { k -> Obstacle("rail wall $side/$k", Box3.around(railCentre(side, k), V3(WALL_L, WALL_T, WALL_H)), true) }
    }

    fun wallBox(w: Wall, sunk: Boolean = true): Obstacle {
        val size = if (w.o == Orient.H) V3(WALL_L, WALL_H, WALL_T) else V3(WALL_T, WALL_H, WALL_L)
        return Obstacle("wall ${w.notation}${if (sunk) "" else " in hand"}", Box3.around(wallCentre(w, sunk), size), true)
    }

    fun pawnBox(cell: Int, lift: Float): Obstacle =
        Obstacle("pawn ${Q.name(cell)} +${(lift * 1000).roundToInt()}mm", Box3.around(pawnCentre(cell, lift), V3(PAWN_D, PAWN_H, PAWN_D)), true,
            cylinder = cellTop(cell, lift) to PAWN_H)

    fun seatBox(): Obstacle = Obstacle("seat card", Box3.around(SEAT_CENTRE, V3(SEAT_SIZE.x, SEAT_SIZE.y, 0.004f)), false)

    fun seatCorners(): List<V3> = listOf(
        SEAT_CENTRE + V3(-SEAT_SIZE.x / 2, -SEAT_SIZE.y / 2, 0f), SEAT_CENTRE + V3(SEAT_SIZE.x / 2, -SEAT_SIZE.y / 2, 0f),
        SEAT_CENTRE + V3(SEAT_SIZE.x / 2, SEAT_SIZE.y / 2, 0f), SEAT_CENTRE + V3(-SEAT_SIZE.x / 2, SEAT_SIZE.y / 2, 0f),
    )

    fun blocks(o: Obstacle, eye: V3, target: V3): Boolean {
        val cyl = o.cylinder
        if (cyl != null) return cylinderHit(cyl.first, cyl.second, toLocal(eye), toLocal(target))
        return if (o.local) o.box.hitBySegment(toLocal(eye), toLocal(target)) else o.box.hitBySegment(eye, target)
    }

    /** A pawn is a turned cylinder (radius PAWN_D / 2) along the board normal: analytic segment vs cylinder (t in (0, 1)). */
    fun cylinderHit(base: V3, height: Float, a: V3, b: V3, r: Float = PAWN_D / 2): Boolean {
        val dx = b.x - a.x; val dz = b.z - a.z; val dy = b.y - a.y
        val ox = a.x - base.x; val oz = a.z - base.z
        val qa = dx * dx + dz * dz
        val qb = 2 * (ox * dx + oz * dz)
        val qc = ox * ox + oz * oz - r * r
        var t0: Float; var t1: Float
        if (qa < 1e-12f) { if (qc >= 0f) return false; t0 = 0f; t1 = 1f } else {
            val disc = qb * qb - 4 * qa * qc
            if (disc <= 0f) return false
            val sq = kotlin.math.sqrt(disc)
            t0 = (-qb - sq) / (2 * qa); t1 = (-qb + sq) / (2 * qa)
        }
        if (abs(dy) < 1e-9f) { if (a.y < base.y || a.y > base.y + height) return false } else {
            val ta = (base.y - a.y) / dy; val tb = (base.y + height - a.y) / dy
            t0 = maxOf(t0, minOf(ta, tb)); t1 = minOf(t1, maxOf(ta, tb))
        }
        t0 = maxOf(t0, 1e-6f); t1 = minOf(t1, 1f - 1e-6f)
        return t1 > t0
    }

    /**
     * The 30 eyes of design occlusionRule: reset eyes (eye height 0 = level with the volume centre, or 1.9 tan 4° = 4° down)
     * x {1.2, 1.9, 2.5} m x azimuth {0, ±30°}; walking in to 0.7 m (4° height) x {0, ±30°}; design eyes E0 / E1 / E2
     * (0.40 / 0.50 / 0.88 m above the desk, 0.85 m out) x {front, 30° left, 30° right}.
     */
    fun eyes(): Map<String, V3> {
        val out = LinkedHashMap<String, V3>()
        val ye4 = (1.9 * kotlin.math.tan(Math.toRadians(4.0))).toFloat()
        for ((ye, tag) in listOf(0f to "reset-level", ye4 to "reset-4deg")) for (d in listOf(1.2f, 1.9f, 2.5f)) for (az in listOf(0, -30, 30)) {
            val a = Math.toRadians(az.toDouble())
            out["$tag d$d az$az"] = V3((d * sin(a)).toFloat(), VOL_CENTRE_Y + ye, (d * cos(a)).toFloat())
        }
        for (az in listOf(0, -30, 30)) {
            val a = Math.toRadians(az.toDouble())
            out["reset-4deg walk-in d0.7 az$az"] = V3((0.7 * sin(a)).toFloat(), VOL_CENTRE_Y + ye4, (0.7 * cos(a)).toFloat())
        }
        for ((n, hh) in listOf("E0" to 0.40f, "E1" to 0.50f, "E2" to 0.88f)) {
            val y = -0.18f + hh
            out[n] = V3(0f, y, 0.85f); out[n + "L"] = V3(-0.425f, y, 0.736f); out[n + "R"] = V3(0.425f, y, 0.736f)
        }
        return out
    }

    /** The control window's control area (design: 0.45 x 0.35 m, 5 cm right of the volume, controls 32 dp in) in the plane z = 0. */
    data class WindowArea(val name: String, val x0: Float, val x1: Float, val y0: Float, val y1: Float) {
        fun corners() = listOf(V3(x0, y0, 0f), V3(x1, y0, 0f), V3(x1, y1, 0f), V3(x0, y1, 0f))
        fun grid(n: Int = 5): List<V3> = (0 until n).flatMap { i -> (0 until n).map { j -> V3(x0 + (x1 - x0) * i / (n - 1), y0 + (y1 - y0) * j / (n - 1), 0f) } }
    }

    fun windowAreas(): List<WindowArea> {
        val left = VOLUME.x / 2 + 0.05f
        val inset = 0.026f
        val w = 520 * DP_M; val h = 400 * DP_M
        return listOf(
            WindowArea("design 0.45x0.35", left + inset, left + 0.45f - inset, VOL_CENTRE_Y - 0.175f + inset, VOL_CENTRE_Y + 0.175f - inset),
            WindowArea("520x400dp", left + inset, left + w - inset, VOL_CENTRE_Y - h / 2 + inset, VOL_CENTRE_Y + h / 2 - inset),
        )
    }

    /** The whole window as an occluder of the board (D41 reverse check). */
    fun windowBox(): Box3 = Box3(V3(VOLUME.x / 2 + 0.05f, VOL_CENTRE_Y - 0.175f, -0.003f), V3(VOLUME.x / 2 + 0.50f, VOL_CENTRE_Y + 0.175f, 0.003f))
}

/**
 * slotAt with position hysteresis (design input.slotAt): the current slot holds until the pointer leaves the 3.1 cm
 * half-width square around it; then the nearest crossing takes over. Off the grid = no slot. Orientation comes from the
 * caller (button / ghost pinch, default horizontal), never from the pointer (v1).
 *
 * The pointer is first smoothed with an exponential moving average ([alpha] per sample, about 60 ms at 72 Hz): with the
 * design's 3.1 cm box alone a pointer resting on a groove midpoint (2.5 cm from both crossings) flips slot every few frames
 * under 4 mm jitter (the design's own fallback: "放下时取最近 200 ms 的落点中值"; SlotTest measures both).
 */
class SlotTracker(private val layout: Layout = Layout(), private val alpha: Float = 0.2f) {
    var current: Pair<Int, Int>? = null
        private set
    private var fx = Float.NaN
    private var fs = Float.NaN
    val smoothX: Float get() = fx
    val smoothS: Float get() = fs

    fun reset() { current = null; fx = Float.NaN; fs = Float.NaN }

    fun update(x: Float, s: Float): Pair<Int, Int>? {
        if (fx.isNaN() || abs(x - fx) > 0.15f || abs(s - fs) > 0.15f) { fx = x; fs = s } else { fx += alpha * (x - fx); fs += alpha * (s - fs) }
        return updateRaw(fx, fs)
    }

    /** The hysteresis step on an already smoothed point. */
    fun updateRaw(x: Float, s: Float): Pair<Int, Int>? {
        if (!layout.onGrid(x, s)) { current = null; return null }
        val cur = current
        if (cur != null && abs(x - layout.crossX(cur.first)) <= Layout.SLOT_HOLD && abs(s - layout.crossS(cur.second)) <= Layout.SLOT_HOLD) return cur
        current = layout.nearestCross(x, s)
        return current
    }
}
