package io.embodit.quoridorwalls.ui.board.input

// Factory template taste/templates/board-input (D50): 3D pick / drag for board & tabletop apps in a
// Shared Space volume. Copy this file, change the package, implement TargetResolver + BoardInputListener.

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.data.InteractionKind
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import kotlin.math.sqrt

/**
 * Where an input came from. The system reports it as an InteractionKind on every tap / drag / pointer event.
 * Verified on PICO OS (Swan B3110, 2026-11-05): hand ray = RayBasedPinch, eye-hand = GazePinch, direct = DirectPinch /
 * Poke; the controller is expected as Pointer (see README "known limits").
 */
enum class InputSource(val label: String) {
    HAND_RAY("hand-ray"), EYE_HAND("eye-hand"), CONTROLLER("controller"), DIRECT("direct-pinch"), POKE("poke"), UNKNOWN("unknown");

    companion object {
        fun of(kind: InteractionKind?): InputSource = when (kind) {
            InteractionKind.RayBasedPinch -> HAND_RAY
            InteractionKind.GazePinch -> EYE_HAND
            InteractionKind.Pointer -> CONTROLLER
            InteractionKind.DirectPinch -> DIRECT
            InteractionKind.Poke -> POKE
            else -> UNKNOWN
        }
    }
}

/** Maps a hit entity (or one of its ancestors) to the app's own target (a square, a piece …). null = not ours. */
fun interface TargetResolver<T : Any> {
    fun resolve(entity: Entity): T?
}

/** What the app does with resolved input. All calls arrive on the main thread. */
interface BoardInputListener<T : Any> {
    /**
     * A tap — a real system tap, or a press-and-release that the system classified as a tiny drag.
     * [position] is the hit point in the SpatialView's local (px) space when the system gave one (null for drag-taps);
     * use it to place hits on meshes that are not one of your targets (e.g. the board surface).
     */
    fun onTap(target: T?, source: InputSource, position: Offset3D?)

    /** The pointer travelled past [BoardInputConfig.dragStartM] while held on [target]. Return true to take the drag. */
    fun onDragStart(target: T, source: InputSource): Boolean = false

    /** Total movement since the press, metres, volume axes (+X right, +Y up, +Z toward the user). */
    fun onDragMove(totalM: Offset3M) {}

    fun onDragEnd() {}

    fun onDragCancel() {}

    /**
     * Hover (D50 補): the gaze / ray is over [target] (null = over nothing of ours). Called on every hover move, so the
     * app can expire the highlight when updates stop (pointer left the volume). Hint only — never change game state here.
     */
    fun onHover(target: T?, source: InputSource) {}
}

data class Offset3M(val x: Float, val y: Float, val z: Float) {
    val length: Float get() = sqrt(x * x + y * y + z * z)
}

data class BoardInputConfig(
    /** A held pointer becomes a drag only after this much travel (metres); less than that is a tap (D27 forgiving). */
    val dragStartM: Float = 0.025f,
    /** How far up the parent chain to look for an entity the resolver knows (GLB meshes hang below the collider). */
    val maxResolveDepth: Int = 10,
    val logTag: String = "BoardInput",
)

/**
 * Logging switch + file sink. On PICO OS 6.2 headsets the app's own logcat lines can vanish (an SDK thread prints
 * `RmClient: threadLoop reply error, reconnect` ~50 000 times a second in the app process and logd drops the rest), so
 * every line also goes to files/input-log.txt: `adb shell run-as <pkg> cat files/input-log.txt` (debug builds).
 */
object BoardInputLog {
    @Volatile var enabled: Boolean = true
    @Volatile var verbosePointer: Boolean = true
    private var file: java.io.File? = null
    private const val MAX_BYTES = 512 * 1024
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    /** Call once (Application.onCreate) to mirror log lines into files/input-log.txt. */
    fun attach(context: android.content.Context) { file = java.io.File(context.filesDir, "input-log.txt") }

    @Synchronized
    fun write(tag: String, msg: String) {
        Log.i(tag, msg)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.renameTo(java.io.File(f.parentFile, "input-log.old.txt"))
            f.appendText("${stamp.format(java.util.Date())} $tag: $msg\n")
        }
    }
}

/** Shared between the tap and drag detectors so one press is never handled twice. */
private class TapGuard { @Volatile var lastTapMs = 0L }

/** Last hovered target, so hover changes are logged once. */
private class HoverState { var last: Any? = null }

/** Walks from [entity] up its parents until [resolver] recognises one. */
fun <T : Any> resolveUp(entity: Entity?, resolver: TargetResolver<T>, maxDepth: Int = 10): Pair<T?, String> {
    var e = entity
    var depth = 0
    val path = StringBuilder()
    while (e != null && depth <= maxDepth) {
        val name = runCatching { e.getName() }.getOrDefault("?")
        if (path.isNotEmpty()) path.append(" < ")
        path.append(name)
        resolver.resolve(e)?.let { return it to path.toString() }
        e = if (e.hasParent()) e.getParent() else null
        depth++
    }
    return null to (if (path.isEmpty()) "<none>" else path.toString())
}

/**
 * The board input layer: tap + drag + raw pointer logging on the SpatialView that holds the board.
 *
 * Why three detectors (斗兽棋 v0.1.2 headset logs): a pinch / trigger that moves a few millimetres during the press
 * is delivered by the system as a DragEvent3D, not a TapEvent3D (about half of the hand-ray presses; far more with a
 * controller ray or eye-hand). A tap detector alone silently drops those presses, so short drags are turned back into
 * taps here. Hits on GLB meshes report the mesh node, not the collider entity, so targets are resolved up the parent
 * chain.
 */
fun <T : Any> Modifier.boardInput(
    resolver: TargetResolver<T>,
    listener: BoardInputListener<T>,
    config: BoardInputConfig = BoardInputConfig(),
): Modifier = composed {
    val context = LocalContext.current
    val converter = LocalPhysicalLengthConverter.current
    val currentListener by rememberUpdatedState(listener)
    val currentResolver by rememberUpdatedState(resolver)
    fun pxToM(px: Float): Float = with(converter) { converter.dpToLength(px.toDp(), LengthUnit.Meters) }
    fun toMeters(o: Offset3D) = Offset3M(pxToM(o.x), -pxToM(o.y), pxToM(o.z))
    val tag = config.logTag
    val guard = androidx.compose.runtime.remember { TapGuard() }
    val hover = androidx.compose.runtime.remember { HoverState() }
    fun log(msg: String) { if (BoardInputLog.enabled) BoardInputLog.write(tag, msg) }

    this
        // 1. raw pointer events: logging only (source, pressed, hit entity), never consumed
        .pointerInput(Unit) {
            detectSpatialPointerEvent(context, TargetEntity.any()) { infos ->
                // hover: not pressed, not a down / up -> highlight what the pointer is over
                infos.lastOrNull { !it.pressed && !it.isDownEvent() && !it.isUpEvent() }?.let { h ->
                    val (hit, _) = resolveUp(h.targetedEntity, currentResolver, config.maxResolveDepth)
                    val src = InputSource.of(h.kind)
                    if (hit != hover.last) { hover.last = hit; log("HOVER src=${src.label} hit=$hit") }
                    currentListener.onHover(hit, src)
                }
                if (BoardInputLog.enabled && BoardInputLog.verbosePointer) {
                    for (i in infos) {
                        if (i.isDownEvent() || i.isUpEvent()) {
                            val (hit, path) = resolveUp(i.targetedEntity, currentResolver, config.maxResolveDepth)
                            log("POINTER ${if (i.isDownEvent()) "down" else "up"} src=${InputSource.of(i.kind).label} kind=${i.kind} hit=$hit path=$path")
                        }
                    }
                }
                false
            }
        }
        // 2. system taps
        .pointerInput(Unit) {
            detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { tap ->
                val src = InputSource.of(tap.interactionKind)
                val (hit, path) = resolveUp(tap.targetEntity, currentResolver, config.maxResolveDepth)
                log("TAP src=${src.label} hit=$hit path=$path pos=(${"%.0f".format(tap.position.x)}, ${"%.0f".format(tap.position.y)}, ${"%.0f".format(tap.position.z)})")
                guard.lastTapMs = SystemClock.uptimeMillis()
                currentListener.onTap(hit, src, tap.position)
            }
        }
        // 3. drags: real drags past dragStartM, short ones become taps
        .pointerInput(Unit) {
            var startHit: T? = null
            var startPath = ""
            var startSrc = InputSource.UNKNOWN
            var startMs = 0L
            var maxTravel = 0f
            var dragging = false
            detectSpatialDragGesture(
                context = context,
                targetedToEntity = TargetEntity.any(),
                onDragStart = { _, v ->
                    val (hit, path) = resolveUp(v.targetEntity, currentResolver, config.maxResolveDepth)
                    startHit = hit; startPath = path; startSrc = InputSource.of(v.interactionKind)
                    startMs = SystemClock.uptimeMillis(); maxTravel = 0f; dragging = false
                    log("DRAG press src=${startSrc.label} hit=$hit path=$path")
                },
                onDragEnd = {
                    val ms = SystemClock.uptimeMillis() - startMs
                    if (dragging) {
                        log("DRAG end src=${startSrc.label} travel=${"%.3f".format(maxTravel)}m ${ms}ms")
                        currentListener.onDragEnd()
                    } else if (SystemClock.uptimeMillis() - guard.lastTapMs < 250) {
                        log("DRAG->TAP skipped: the system already sent a tap for this press")
                    } else {
                        log("DRAG->TAP src=${startSrc.label} hit=$startHit path=$startPath travel=${"%.3f".format(maxTravel)}m ${ms}ms")
                        currentListener.onTap(startHit, startSrc, null)
                    }
                    dragging = false
                },
                onDragCancel = {
                    log("DRAG cancel src=${startSrc.label} dragging=$dragging")
                    if (dragging) currentListener.onDragCancel()
                    dragging = false
                },
            ) { v ->
                val d = toMeters(v.dragAmount)
                if (d.length > maxTravel) maxTravel = d.length
                val hit = startHit
                if (!dragging && hit != null && d.length > config.dragStartM) {
                    dragging = currentListener.onDragStart(hit, startSrc)
                    log("DRAG start src=${startSrc.label} hit=$hit taken=$dragging")
                }
                if (dragging) currentListener.onDragMove(d)
            }
        }
}
