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
 * Every metre of the volume (design spatialLayout). Scene space = the design's volume space: origin at the volume centre,
 * +X right, +Y up, +Z toward the user; volume 0.56 x 0.36 x 0.62 (floor y -0.18, front face z +0.31).
 *
 * The board hangs from tiltRoot at its near-bottom edge (0, -0.18, +0.27), tipped 20° about X (far edge up). Board-local
 * space: origin on that edge, +Y the board normal, -Z along the board toward the far edge, so a point on the top surface
 * at distance s from the near edge is local (x, BOARD_T, -s) (design boardRoot: near top edge (x, -0.1565, +0.2786)).
 *
 *   rails   near rail s 0..0.04 (your 10 walls), far rail s 0.49..0.53 (the AI's 10), walls lying flat, 5 per layer, 2 layers
 *   grid    9 x 9 cells, pitch 5 cm (4.2 cm face + 0.8 cm groove), row r centre s = 0.04 + (r + 0.5) x 0.05, column x = (c - 4) x 0.05
 *   walls   9.6 x 0.6 x 2.4 cm, centred on crossing (c, r): x = (c - 3.5) x 0.05, s = 0.04 + (r + 1) x 0.05, sunk 2 mm
 */
class Layout {
    companion object {
        const val PITCH = 0.05f
        const val CELL = 0.042f
        const val BOARD_W = 0.49f
        const val BOARD_D = 0.53f
        const val BOARD_T = 0.025f
        const val RAIL = 0.04f
        const val CELL_RAISE = 0.0015f
        const val GROOVE_SINK = 0.002f
        /** Cell faces (local y). */
        const val CELL_TOP = BOARD_T + CELL_RAISE
        const val WALL_L = 0.096f
        const val WALL_T = 0.006f
        const val WALL_H = 0.024f
        /** A standing wall's bottom: 2 mm into the groove below the cell faces (its top is 2.2 cm above them). */
        const val WALL_BASE = CELL_TOP - GROOVE_SINK
        const val PAWN_D = 0.034f
        const val PAWN_H = 0.045f
        const val LIFT_SELECT = 0.008f
        const val LIFT_STEP = 0.03f
        const val LIFT_JUMP = 0.05f
        const val LIFT_GOAL = 0.015f
        /** A wall in hand floats with its bottom 2 cm above the board surface. */
        const val HOLD_UP = 0.02f
        const val TILT_DEG = 20f
        val VOLUME = V3(0.56f, 0.36f, 0.62f)
        val PIVOT = V3(0f, -0.18f, 0.27f)
        /**
         * Seat card (AttachmentPanel, design coordinates.seatCard 0.20 x 0.035 m) widened to 0.26 m so the English line
         * "Your move · Walls you 10 / them 10" fits at the design's letter height (deviation, within tolerance).
         */
        val SEAT_SIZE = V3(0.26f, 0.035f, 0f)
        val SEAT_CENTRE = V3(0f, -0.18f + 0.0175f, 0.31f)
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
    fun cellS(cell: Int) = 0.04f + (Q.row(cell) + 0.5f) * PITCH
    fun crossX(c: Int) = (c - 3.5f) * PITCH
    fun crossS(r: Int) = 0.04f + (r + 1) * PITCH

    /** Local centre of a cell face. */
    fun cellTop(cell: Int, up: Float = 0f): V3 = V3(cellX(cell), CELL_TOP + up, -cellS(cell))

    /** Local centre of a pawn standing on [cell], lifted by [lift]. */
    fun pawnCentre(cell: Int, lift: Float = 0f): V3 = cellTop(cell, PAWN_H / 2 + lift)

    /** Local centre of a wall standing in its slot. */
    fun wallCentre(w: Wall, sunk: Boolean = true): V3 = V3(crossX(w.c), (if (sunk) WALL_BASE else CELL_TOP + HOLD_UP) + WALL_H / 2, -crossS(w.r))

    /** Local centre of rail slot [k] (0..9) of [side]: lying flat, 5 per layer, the top layer is used first. */
    fun railCentre(side: Int, k: Int): V3 {
        val layer = k / 5; val i = k % 5
        val s = if (side == Q.YOU) RAIL / 2 else BOARD_D - RAIL / 2
        return V3((i - 2) * 0.098f, BOARD_T + WALL_T / 2 + layer * WALL_T, -s)
    }

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
        abs(x) <= 4.5f * PITCH + GRID_MARGIN && s >= 0.04f - GRID_MARGIN && s <= 0.04f + 9 * PITCH + GRID_MARGIN

    /** The nearest crossing (design input.slotAt), clamped to 0..7. */
    fun nearestCross(x: Float, s: Float): Pair<Int, Int> =
        ((x / PITCH + 3.5f).roundToInt().coerceIn(0, 7)) to (((s - 0.04f) / PITCH - 1f).roundToInt().coerceIn(0, 7))

    // ------------------------------------------------------------------ occlusion (D35 / D20 / D41)

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

    /** A pawn is a turned cylinder (radius PAWN_D / 2), not its bounding box: segment vs vertical (board-normal) cylinder. */
    private fun cylinderHit(base: V3, height: Float, a: V3, b: V3): Boolean {
        val r = PAWN_D / 2
        if (!Box3(base - V3(r, 0f, r), base + V3(r, height, r)).hitBySegment(a, b)) return false
        val n = 400
        for (i in 0..n) {
            val p = a.lerp(b, i / n.toFloat())
            val dx = p.x - base.x; val dz = p.z - base.z
            if (p.y >= base.y && p.y <= base.y + height && dx * dx + dz * dz < r * r) return true
        }
        return false
    }

    /**
     * Eyes (design occlusionCheck.eye, the volume standing on a desk): sit E1 (0, 0.32, 0.85), low sit E0 (0, 0.22, 0.85),
     * stand E2 (0, 0.70, 0.85), each also 30° left / right at the same distance; plus the measured emulator reset (eye
     * level with the volume centre, 1.2 / 1.9 m, azimuth 0 / ±30°; royal-ur QA q1-1).
     */
    fun eyes(): Map<String, V3> {
        val out = LinkedHashMap<String, V3>()
        for ((n, y) in listOf("E0" to 0.22f, "E1" to 0.32f, "E2" to 0.70f)) {
            out[n] = V3(0f, y, 0.85f)
            out[n + "L"] = V3(-0.425f, y, 0.736f)
            out[n + "R"] = V3(0.425f, y, 0.736f)
        }
        for (d in listOf(1.2f, 1.9f)) for (az in listOf(0, -30, 30)) {
            val a = Math.toRadians(az.toDouble())
            out["reset d=$d az=$az"] = V3((d * sin(a)).toFloat(), 0f, (d * cos(a)).toFloat())
        }
        return out
    }

    /** The control window's control area (design coordinates.controlWindow): [x0, x1] x [y0, y1] in the plane z = 0. */
    data class WindowArea(val name: String, val x0: Float, val x1: Float, val y0: Float, val y1: Float) {
        fun corners() = listOf(V3(x0, y0, 0f), V3(x1, y0, 0f), V3(x1, y1, 0f), V3(x0, y1, 0f))
    }

    /**
     * Two control-window assumptions: the design's conservative 0.45 x 0.35 m at x +0.33..+0.78 (controls 32 dp in), and
     * 520 x 400 dp at the measured 0.00069 m / dp with its left edge at the same x +0.33.
     */
    fun windowAreas(): List<WindowArea> {
        val inset = 32 * DP_M
        val w = 520 * DP_M; val h = 400 * DP_M
        return listOf(
            WindowArea("design 0.45x0.35", 0.356f, 0.754f, -0.149f, 0.149f),
            WindowArea("520x400dp", 0.33f + inset, 0.33f + w - inset, -h / 2 + inset, h / 2 - inset),
        )
    }
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
