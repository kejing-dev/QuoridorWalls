package io.embodit.quoridorwalls.ui.board

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.quoridorwalls.R
import io.embodit.quoridorwalls.domain.Layout
import io.embodit.quoridorwalls.domain.Phase
import io.embodit.quoridorwalls.domain.PlayState
import io.embodit.quoridorwalls.domain.Q
import io.embodit.quoridorwalls.domain.Skin
import io.embodit.quoridorwalls.ui.board.input.BoardInputLog
import io.embodit.quoridorwalls.domain.V3
import io.embodit.quoridorwalls.platform.Graph
import io.embodit.quoridorwalls.platform.TAG
import io.embodit.quoridorwalls.ui.board.input.BoardInputConfig
import io.embodit.quoridorwalls.ui.board.input.BoardInputListener
import io.embodit.quoridorwalls.ui.board.input.InputSource
import io.embodit.quoridorwalls.ui.board.input.Offset3M
import io.embodit.quoridorwalls.ui.board.input.TargetResolver
import io.embodit.quoridorwalls.ui.board.input.boardInput
import io.embodit.quoridorwalls.ui.control.wallFailText
import io.embodit.quoridorwalls.ui.theme.QColors
import io.embodit.quoridorwalls.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Runs the board's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
    @Volatile var listener: BoardListener? = null
    @Volatile var resolver: TargetResolver<BoardTarget>? = null
}

private const val SEAT = "seatCard"

/**
 * The board volume (default container). Opens the control window beside it through the level gate, shows the board,
 * and routes every pinch / trigger / drag on a collider to [io.embodit.quoridorwalls.domain.GameFlow] through the factory
 * board-input template (D50).
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BoardScene(graph.flow) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.request("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            // D50: the factory board-input template (taps, short drags reported as drags, hover, targets resolved up the
            // parent chain) for hand ray, controller ray and eye-hand alike. No other tap / drag detector on this view.
            .boardInput(remember(graph) { boardResolver(graph).also { SceneHolder.resolver = it } }, remember(graph) { BoardListener(graph, scene).also { SceneHolder.listener = it } }, BoardInputConfig(logTag = TAG)),
        attachments = {
            AttachmentPanel(SEAT) { TagRoot(graph) { st -> SeatCard(st, graph) } }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design lifecycle "首帧兜底 一块深青绿平板"), removed when the GLBs are in
                    val slab = MeshResource.createBox(Vector3(Layout.BOARD_W, Layout.BOARD_T, Layout.BOARD_D), cornerRadius = 0.003f)
                    // the stand-in takes the saved skin's cell colour, so nothing changes colour when the models arrive
                    val c = if (graph.settings.value.skin == Skin.CLASSIC) Color4(0.063f, 0.145f, 0.125f, 1f) else Color4(0.94f, 0.96f, 0.95f, 1f)
                    val mat = PhysicallyBasedMaterial.create().apply { setBaseColor(c); setRoughness(0.7f) }
                    slab to mat
                }.getOrNull()
            }
            quick?.let { (s, m) -> scene.showFallback(s, m) }
            val t0 = android.os.SystemClock.elapsedRealtime()
            suspend fun load(n: String): Entity = Entity.loadSuspend("asset://models/$n.glb").also { it.enabled = false; content.addEntity(it) }
            val models = runCatching {
                Models(Skin.entries.associateWith { s ->
                    val k = s.name.lowercase()
                    SkinModels(load("board_$k"), load("stand_$k"), load("wall_$k"), load("pawn_${k}_you"), load("pawn_${k}_ai"))
                })
            }.onFailure { Log.e(TAG, "MODELS failed", it) }.getOrNull()
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            Log.i(TAG, "MODELS loaded=${models != null} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            // QA q2-1: the scene gets the saved game and skin before it is built and again right after, so the first frame
            // already shows them; `initial` returns at once (the update block only runs after it)
            scene.apply(graph.flow.state.value, graph.settings.value)
            if (models != null) scene.install(resources, models, attachments.entity(SEAT))
            scene.apply(graph.flow.state.value, graph.settings.value)
            Log.i(TAG, "SCENE first frame ${scene.firstFrame()}")
            // D1 "一打开就要看得见": where things are 1 / 3 / 5 s after start, logged off the initial block
            graph.scope.launch {
                for (s in listOf(1, 2, 2)) { kotlinx.coroutines.delay(s * 1000L); Log.i(TAG, "PLACEMENT t+${android.os.SystemClock.elapsedRealtime() - t0}ms window=${graph.window.panelState} ${scene.evidence()}") }
            }
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(state, settings)
        },
    )
}

/** Entity name -> target (pure [BoardTargets.resolveName]; the same function the JVM replay test runs). */
internal fun boardResolver(graph: Graph): TargetResolver<BoardTarget> =
    TargetResolver.byName { name -> BoardTargets.resolveName(name, graph.flow.state.value.pos.left[Q.YOU]) }

/** Every resolved tap / drag goes through [route] and the hold calls (the same calls the window and the debug broadcast make). */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<BoardTarget> {
    private var anchor: V3? = null

    /** S10: with the control window closed, the first pinch on the volume only reopens it and is used up. */
    private fun consumedByClosedWindow(): Boolean {
        if (graph.window.panelOpen) return false
        Log.i(TAG, "PINCH reopens the control window (S10)")
        graph.window.request("pinch")
        return true
    }

    override fun onTap(target: BoardTarget?, source: InputSource, position: Offset3D?) {
        val cell = BoardTargets.cellFor(target) { graph.flow.state.value.pos.pawns[it] }
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> square=${if (cell >= 0) Q.name(cell) else "-"}")
        if (consumedByClosedWindow()) return
        route(graph, target)
    }

    override fun onDragStart(target: BoardTarget, source: InputSource): Boolean {
        if (target != BoardTarget.Rail) return false
        if (consumedByClosedWindow()) return false
        if (!graph.flow.pickWall()) return false
        anchor = graph.flow.holdPoint?.let { scene.toScene(it) }
        Log.i(TAG, "HOLD start src=${source.label} anchor=$anchor")
        return true
    }

    override fun onDragMove(totalM: Offset3M) {
        val a = anchor ?: return
        val p = a + V3(totalM.x, totalM.y, totalM.z) * Layout.DRAG_GAIN
        val (x, s) = Layout().boardXS(p)
        graph.flow.holdAt(x, s)
    }

    override fun onDragEnd() { anchor = null; graph.flow.dropWall() }

    override fun onDragCancel() { anchor = null; graph.flow.cancelHold("drag cancel") }

    override fun onHover(target: BoardTarget?, source: InputSource) { scene.setHover(BoardTargets.nameOf(target)) }
}

/** Target -> the one flow call (the same calls the debug broadcast makes). */
internal fun route(graph: Graph, target: BoardTarget?) {
    val flow = graph.flow
    when (target) {
        is BoardTarget.Cell -> flow.tapCell(target.cell)
        is BoardTarget.Pawn -> flow.tapPawn(target.side)
        BoardTarget.Rail -> flow.tapRail()
        BoardTarget.Ghost -> flow.toggleOrient()
        null -> flow.tapEmpty()
    }
}

/** A tag is its own composition: it reads state + language itself so a language switch always reaches it. */
@Composable
private fun TagRoot(graph: Graph, content: @Composable (PlayState) -> Unit) {
    val base = LocalContext.current
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    key(s.language) {
        CompositionLocalProvider(LocalContext provides base.localized(s.language)) { content(st) }
    }
}

@Composable
private fun tagText(text: String, tag: String, size: Int, weight: FontWeight = FontWeight.SemiBold, color: Color = QColors.Ink) {
    Text(text, color = color, fontSize = size.sp, fontWeight = weight, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") })
}

/**
 * Seat card (design coordinates.seatCard): whose turn + both wall counts, or why a wall bounced; never a button except
 * "open the panel" after the system × (S10). Reads the same state as the play page (D38).
 */
@Composable
private fun SeatCard(st: PlayState, graph: Graph) {
    val window = graph.window
    Box(
        Modifier.size(Layout.SEAT_W_DP.dp, Layout.SEAT_H_DP.dp).clip(RoundedCornerShape(12.dp)).background(QColors.TagFace),
        contentAlignment = Alignment.Center,
    ) {
        when {
            window.levelHint -> tagText(stringResource(R.string.level_hint), "seat/level", 15)
            window.panelState == "ON_DESTROY" -> Button(onClick = { window.request("tag") }) {
                // design-style: inherited-content-color Button
                Text(stringResource(R.string.open_panel), fontSize = 14.sp)
            }
            else -> {
                val walls = stringResource(R.string.walls_count, st.wallsLeft[Q.YOU], st.wallsLeft[Q.AI])
                val fail = st.hold?.fail ?: st.reject?.fail
                val red = fail != null
                val text = when {
                    st.config == null || st.phase == Phase.SETUP || st.phase == Phase.LOADING -> stringResource(R.string.seat_ready)
                    fail != null -> wallFailText(fail)
                    st.winner == Q.YOU -> stringResource(R.string.seat_you_won)
                    st.winner == Q.AI -> stringResource(R.string.seat_they_won)
                    st.humansTurn -> stringResource(R.string.seat_you) + " · " + walls
                    else -> stringResource(R.string.seat_them) + " · " + walls
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 10.dp)) {
                    if (st.config != null && st.winner == null && !red && st.phase != Phase.SETUP) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(if (st.side == Q.YOU) QColors.Ivory else QColors.Cinnabar).border(1.5.dp, QColors.Ink, CircleShape))
                    }
                    tagText(text, "seat/line", if (text.length > 34) 15 else 17, color = if (red) QColors.ErrorText else QColors.Ink)
                }
            }
        }
    }
}
