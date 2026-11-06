package io.embodit.quoridorwalls.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.quoridorwalls.BuildConfig
import io.embodit.quoridorwalls.R
import io.embodit.quoridorwalls.domain.AlertKind
import io.embodit.quoridorwalls.domain.ConfirmKind
import io.embodit.quoridorwalls.domain.Engine
import io.embodit.quoridorwalls.domain.FirstPick
import io.embodit.quoridorwalls.domain.GameSetup
import io.embodit.quoridorwalls.domain.Language
import io.embodit.quoridorwalls.domain.Orient
import io.embodit.quoridorwalls.domain.Phase
import io.embodit.quoridorwalls.domain.PlayState
import io.embodit.quoridorwalls.domain.Q
import io.embodit.quoridorwalls.domain.Settings
import io.embodit.quoridorwalls.domain.WallFail
import io.embodit.quoridorwalls.platform.Graph
import io.embodit.quoridorwalls.platform.TAG
import io.embodit.quoridorwalls.ui.Page
import io.embodit.quoridorwalls.ui.Ui
import io.embodit.quoridorwalls.ui.theme.QColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the board, 520 x 400 dp): all 2D UI, four pages switched by the native
 * SpatialUI TabBar (D8): 对局 / 新局 / 规则 / 设置. Every page fills the window top to bottom (blank outside the content
 * block <= 25 %, design D44); confirmations are a whole-page card and lock the volume; every Text logs OVERFLOW.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            graph.window.onPanelLifecycle(e.name)
            // S10: the window is gone; a wall in hand goes back to its rail (design S07 exits)
            if (e == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) graph.flow.cancelHold("window closed")
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // S01: the rules page opens beside the already running easy game.
    LaunchedEffect(state.welcome) { if (state.welcome) Ui.page = Page.RULES }
    LaunchedEffect(state.phase) {
        when (state.phase) {
            Phase.SETUP -> Ui.page = Page.NEW
            Phase.HOLDING, Phase.ANIM -> if (Ui.page == Page.NEW && !Ui.newConfirm) Ui.page = Page.PLAY
            else -> Unit
        }
    }
    LaunchedEffect(Ui.exitConfirm, Ui.newConfirm) { graph.flow.uiLocked = Ui.exitConfirm || Ui.newConfirm }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.exitConfirm = false
        if (Ui.page != Page.NEW) Ui.newConfirm = false
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph)
            Page.NEW -> NewPage(state, settings, graph)
            Page.RULES -> RulesPage(state, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        listOf(
            Triple(Page.PLAY, R.drawable.ic_tab_play, R.string.tab_play),
            Triple(Page.NEW, R.drawable.ic_tab_new, R.string.tab_new),
            Triple(Page.RULES, R.drawable.ic_tab_rules, R.string.tab_rules),
            Triple(Page.SETTINGS, R.drawable.ic_tab_settings, R.string.tab_settings),
        ).forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = stringResource(label)) },
                supportContent = { Txt(stringResource(label), "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}

/** Every page: header at the top, the last row at the bottom, the blocks share the height in between (SpaceBetween). */
@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

@Composable
internal fun levelName(level: Int): String = stringResource(when (level) { Engine.EASY -> R.string.level_1; Engine.MEDIUM -> R.string.level_2; else -> R.string.level_3 })

@Composable
internal fun wallFailText(f: WallFail): String = stringResource(when (f) {
    WallFail.NO_WALLS -> R.string.fail_no_walls
    WallFail.ALREADY -> R.string.fail_already
    WallFail.OVERLAP -> R.string.fail_overlap
    WallFail.CROSS -> R.string.fail_cross
    WallFail.BLOCKS_THEM -> R.string.fail_blocks_them
    WallFail.BLOCKS_YOU -> R.string.fail_blocks_you
})

// ------------------------------------------------------------------ Game

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val cfg = state.config
    if (cfg == null || state.phase == Phase.SETUP || state.phase == Phase.LOADING) {
        PageColumn {
            TurnHeader(state, settings, graph)
            Card { Txt(stringResource(R.string.new_empty), "play/empty", style = PicoTheme.typography.titleMedium, color = QColors.Ink, maxLines = 2) }
            PrimaryButton(stringResource(R.string.btn_start), "play/new", onClick = { Ui.page = Page.NEW }, modifier = Modifier.fillMaxWidth())
            Txt(stringResource(R.string.new_note), "play/note", style = PicoTheme.typography.bodyMedium, color = QColors.InkSecondary)
        }
        return
    }
    if (state.confirm == ConfirmKind.RESIGN) {
        ConfirmPage(R.string.confirm_resign, R.string.confirm_resign_line, R.string.btn_no, R.string.btn_yes_resign, R.drawable.ic_flag, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() })
        return
    }
    if (state.phase == Phase.RESULT) { ResultPage(state, graph); return }
    PageColumn {
        TurnHeader(state, settings, graph)
        StatusCard(state)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // D43 / D13: never a grey-on-grey disabled button; when it can't be used it says why on a press
            SoftButton(stringResource(R.string.btn_undo), null, "play/undo", live = state.canUndo, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f)) {
                if (state.canUndo) flow.requestUndo() else flow.explain(AlertKind.NO_UNDO)
            }
            ToggleButton(stringResource(if (state.hintOn) R.string.hint_on else R.string.hint_off), "play/hint", on = state.hintOn, onClick = { flow.setHint(!state.hintOn) }, modifier = Modifier.weight(1f))
            ToggleButton(stringResource(if (state.bandsOn) R.string.bands_on else R.string.bands_off), "play/bands", on = state.bandsOn, onClick = { flow.setBands(!state.bandsOn) }, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OrientButton(state, graph, Modifier.weight(1f))
            ToggleButton(stringResource(R.string.btn_mute), "play/mute", on = !settings.sound, onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_resign), "play/resign", onClick = { flow.requestConfirm(ConfirmKind.RESIGN) }, icon = R.drawable.ic_flag, modifier = Modifier.weight(1f),
                enabled = state.inGame && state.phase != Phase.ANIM)
        }
        WallsRow(state)
    }
}

/** Header (48): seat chip + whose turn (+ spinner and the level while the AI thinks). */
@Composable
private fun TurnHeader(state: PlayState, settings: Settings, graph: Graph) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        val cfg = state.config
        if (cfg != null && state.phase != Phase.SETUP) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(if (state.side == Q.YOU) QColors.Ivory else QColors.Cinnabar).border(1.5.dp, QColors.Ink, CircleShape))
            Spacer(Modifier.width(10.dp))
        }
        val title = when {
            cfg == null || state.phase == Phase.SETUP -> stringResource(R.string.tab_play)
            state.humansTurn -> stringResource(R.string.seat_you)
            else -> stringResource(R.string.title_them, levelName(cfg.level))
        }
        Txt(title, "play/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (state.phase == Phase.THINKING) CircularProgressIndicator(modifier = Modifier.size(28.dp))
        if (cfg != null && state.phase != Phase.THINKING) Txt(levelName(cfg.level), "play/level", style = PicoTheme.typography.labelLarge, color = QColors.InkSecondary)
    }
}

/** The one sentence of the moment (status, alerts, the coach lines of the first game, why a wall can't go there). */
@Composable
private fun statusText(state: PlayState): String {
    val alert = state.alert
    val hold = state.hold
    val rej = state.reject
    val myDist = state.bands.getOrNull(Q.YOU)?.size?.minus(1) ?: 99
    return when {
        rej != null -> wallFailText(rej.fail)
        hold != null -> when {
            hold.fail != null && state.coachRed && state.firstGame && hold.fail != WallFail.ALREADY -> wallFailText(hold.fail) + " " + stringResource(R.string.coach_red)
            hold.fail != null -> wallFailText(hold.fail)
            hold.slot == null -> stringResource(R.string.hold_off)
            state.firstGame && state.coachHold && state.turnsYou < 3 -> stringResource(R.string.coach_hold)
            else -> stringResource(if (hold.orient == Orient.H) R.string.hold_ok_h else R.string.hold_ok_v)
        }
        alert != null -> stringResource(when (alert.kind) {
            AlertKind.WALL_BETWEEN -> R.string.alert_wall
            AlertKind.DIAGONAL -> R.string.alert_diag
            AlertKind.THEIRS -> R.string.alert_theirs
            AlertKind.FAR -> R.string.alert_far
            AlertKind.NO_WALLS -> R.string.fail_no_walls
            AlertKind.HOLD_TO_DRAG -> R.string.alert_hold
            AlertKind.NOT_YOUR_TURN -> R.string.alert_turn
            AlertKind.NO_UNDO -> R.string.alert_no_undo
            AlertKind.ORIENT_NEEDS_WALL -> R.string.orient_wait_line
        })
        state.phase == Phase.THINKING -> stringResource(R.string.status_thinking)
        state.phase == Phase.ANIM -> stringResource(R.string.status_moving)
        state.phase == Phase.SELECTED && state.steps.any { it.jump } -> stringResource(R.string.status_selected_jump)
        state.phase == Phase.SELECTED -> stringResource(R.string.status_selected)
        state.restored -> stringResource(R.string.status_restored)
        state.coachLit -> stringResource(R.string.coach_1)
        state.hintOn && state.hint != null -> stringResource(R.string.status_hint)
        state.humansTurn && myDist <= 2 && state.pos.left[Q.AI] == 0 -> stringResource(R.string.status_close)
        state.humansTurn && state.pos.left[Q.YOU] == 0 -> stringResource(R.string.status_idle_nowalls)
        else -> stringResource(R.string.status_idle)
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(20.dp)).background(QColors.TagFace).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) { content() }
}

@Composable
private fun StatusCard(state: PlayState) {
    val red = state.reject != null || state.hold?.fail != null || state.alert != null
    Card { Txt(statusText(state), "play/status", style = PicoTheme.typography.titleMedium, color = if (red) QColors.ErrorText else QColors.Ink, maxLines = 2) }
}

/** [横 / 竖] (T5): live only with a wall in hand (it writes the current way); otherwise it says why on a press (D43). */
@Composable
private fun OrientButton(state: PlayState, graph: Graph, modifier: Modifier) {
    val hold = state.hold?.takeIf { state.phase == Phase.HOLDING }
    val label = stringResource(when { hold == null -> R.string.orient_idle; hold.orient == Orient.H -> R.string.orient_h; else -> R.string.orient_v })
    SoftButton(label, stringResource(if (hold != null) R.string.orient_flip else R.string.orient_wait), "play/orient", live = hold != null, icon = R.drawable.ic_swap, modifier = modifier, primary = true) {
        if (hold != null) graph.flow.toggleOrient() else graph.flow.explain(AlertKind.ORIENT_NEEDS_WALL)
    }
}

/**
 * A button that is never system-disabled (grey on grey fails 4.5:1, D13): live = filled; not live = light face with
 * secondary ink, and a press explains why instead of doing nothing (D43).
 */
@Composable
private fun SoftButton(text: String, sub: String?, tag: String, live: Boolean, icon: Int, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val fg = when { live && primary -> PicoTheme.colorScheme.labelPrimaryLight; live -> PicoTheme.colorScheme.labelPrimary; else -> QColors.InkSecondary }
    val bg = when { live && primary -> PicoTheme.colorScheme.interaction; live -> PicoTheme.colorScheme.fillSecondary; else -> QColors.TagFace }
    Button(
        onClick = onClick, modifier = modifier,
        size = ButtonDefaults.Regular.copy(height = 52.dp, minWidth = 96.dp), shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // design-style: inherited-content-color Button
            Txt(text, "$tag/label", style = PicoTheme.typography.labelLarge, color = fg)
            if (sub != null) Txt(sub, "$tag/sub", style = PicoTheme.typography.labelSmall, color = fg)
        }
    }
}

/** Bottom row: both wall counts (same format and source as the seat card and the result card, D38). */
@Composable
private fun WallsRow(state: PlayState) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(stringResource(R.string.walls_count, state.wallsLeft[Q.YOU], state.wallsLeft[Q.AI]), "play/walls", style = PicoTheme.typography.titleSmall, color = QColors.Ink, modifier = Modifier.weight(1f))
        WallPips(state.wallsLeft[Q.YOU])
    }
}

@Composable
private fun WallPips(n: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(Q.WALLS) { i -> Box(Modifier.size(width = 8.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(if (i < n) QColors.Maple else QColors.TagFace).border(1.dp, QColors.Walnut, RoundedCornerShape(2.dp))) }
    }
}

/** S13: a whole-page confirmation card; the volume is locked while it is open. */
@Composable
private fun ConfirmPage(q: Int, line: Int, no: Int, yes: Int, icon: Int, onYes: () -> Unit, onNo: () -> Unit) {
    PageColumn {
        Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp)).background(QColors.TagFace).padding(22.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(48.dp), tint = QColors.Ink)
            Txt(stringResource(q), "confirm/q", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp), color = QColors.Ink, maxLines = 2)
            Txt(stringResource(line), "confirm/line", style = PicoTheme.typography.bodyLarge, color = QColors.InkSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(no), "confirm/no", onClick = onNo, modifier = Modifier.weight(1f).height(60.dp))
                SecondaryButton(stringResource(yes), "confirm/yes", danger = true, onClick = onYes, modifier = Modifier.weight(1f).height(60.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Txt(stringResource(R.string.lock_line), "confirm/lock", style = PicoTheme.typography.bodyMedium, color = QColors.InkSecondary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** S09: the result card (design gameSpec.end): win / lose + why + your steps + the detour your walls cost them. */
@Composable
private fun ResultPage(state: PlayState, graph: Graph) {
    val w = state.winner ?: return
    PageColumn {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(if (w == Q.YOU) QColors.Ivory else QColors.Cinnabar).border(1.5.dp, QColors.Ink, CircleShape))
            Spacer(Modifier.width(12.dp))
            Txt(stringResource(if (w == Q.YOU) R.string.result_win else R.string.result_lose), "result/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold), color = QColors.Ink, modifier = Modifier.weight(1f))
            state.config?.let { Txt(levelName(it.level), "result/level", style = PicoTheme.typography.labelLarge, color = QColors.InkSecondary) }
        }
        val reason = stringResource(when { state.resigned -> R.string.reason_resigned; w == Q.YOU -> R.string.reason_you_first; else -> R.string.reason_they_first })
        Txt(reason + " " + stringResource(R.string.result_again_line), "result/reason", style = PicoTheme.typography.titleMedium, color = QColors.Ink, maxLines = 2)
        Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCell(stringResource(R.string.res_moves), stringResource(R.string.res_moves_v, state.stats.movesYou), Modifier.weight(1f))
            StatCell(stringResource(R.string.res_detour), stringResource(R.string.res_detour_v, state.stats.detourGiven), Modifier.weight(1f))
            StatCell(stringResource(R.string.res_walls), "${state.wallsLeft[Q.YOU]} / ${state.wallsLeft[Q.AI]}", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_menu), "result/menu", onClick = { graph.flow.toMenu() }, modifier = Modifier.weight(1f).height(56.dp))
        }
        Txt(stringResource(R.string.result_footnote), "result/footnote", style = PicoTheme.typography.bodySmall, color = QColors.InkSecondary, maxLines = 2)
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(QColors.TagFace).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Txt(label, "result/stat", style = PicoTheme.typography.labelMedium, color = QColors.InkSecondary)
        Txt(value, "result/stat-v", style = PicoTheme.typography.titleLarge, color = QColors.Ink)
    }
}

// ------------------------------------------------------------------ New game

@Composable
private fun NewPage(state: PlayState, settings: Settings, graph: Graph) {
    val setup = settings.lastSetup
    fun set(change: (GameSetup) -> GameSetup) = graph.settings.update { it.copy(lastSetup = change(it.lastSetup)) }
    fun start() { Ui.newConfirm = false; graph.flow.newGame(setup); Ui.page = Page.PLAY }
    if (Ui.newConfirm) {
        // D44: starting over drops the running game, ask first (whole-page card).
        ConfirmPage(R.string.confirm_new, R.string.confirm_new_line, R.string.btn_cancel, R.string.btn_yes_new, R.drawable.ic_replay, onYes = { start() }, onNo = { Ui.newConfirm = false })
        return
    }
    PageColumn {
        if (state.savedAvailable && state.phase == Phase.SETUP) {
            SecondaryButton(stringResource(R.string.btn_continue, state.savedTurn + 1), "new/continue", onClick = { graph.flow.continueSaved(); Ui.page = Page.PLAY }, icon = R.drawable.ic_replay, modifier = Modifier.fillMaxWidth().height(52.dp))
        } else {
            Txt(stringResource(R.string.new_title), "new/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.height(40.dp))
        }
        Group(stringResource(R.string.new_first), "new/first") {
            // gate:allow tabs-segment same-page option of the new game (titled group: who starts), not a page switch
            Segments(listOf(FirstPick.ME to stringResource(R.string.first_me), FirstPick.THEM to stringResource(R.string.first_them), FirstPick.RANDOM to stringResource(R.string.first_random)),
                setup.first, "new/first", { f -> set { it.copy(first = f) } }, Modifier.fillMaxWidth())
        }
        Group(stringResource(R.string.new_level), "new/level") {
            // gate:allow tabs-segment same-page option of the new game (titled group: opponent level), not a page switch
            Segments(Engine.LEVELS.map { it to levelName(it) }, setup.level, "new/level", { l -> set { it.copy(level = l) } }, Modifier.fillMaxWidth())
        }
        Txt(stringResource(when (setup.level) { Engine.EASY -> R.string.level_1_line; Engine.MEDIUM -> R.string.level_2_line; else -> R.string.level_3_line }),
            "new/level-line", style = PicoTheme.typography.bodyMedium, color = QColors.InkSecondary, maxLines = 2, modifier = Modifier.height(44.dp))
        PrimaryButton(stringResource(R.string.btn_start), "new/start", onClick = {
            if (state.hasProgress) { Ui.newConfirm = true; Log.i(TAG, "CONFIRM NEW_GAME S13") } else start()
        }, modifier = Modifier.fillMaxWidth())
    }
}

/** A titled option group: title 20 + a full-width segment 52 (same-page option switch, never a page switch). */
@Composable
private fun Group(title: String, tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(title, "$tag/title", style = PicoTheme.typography.labelLarge, color = QColors.Ink, modifier = Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) { content() }
    }
}

// ------------------------------------------------------------------ Rules (S01 / S12)

@Composable
private fun RulesPage(state: PlayState, graph: Graph) {
    PageColumn {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RulesDiagram()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(stringResource(if (state.welcome) R.string.welcome_title else R.string.rules_title), "rules/title", style = PicoTheme.typography.titleLarge)
                listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3).forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(QColors.Primary), contentAlignment = Alignment.Center) {
                            Txt("${i + 1}", "rules/n", style = PicoTheme.typography.labelMedium, color = QColors.OnPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Txt(stringResource(id), "rules/line", style = PicoTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f))
                    }
                }
                Txt(stringResource(R.string.rules_how), "rules/how", style = PicoTheme.typography.bodySmall, color = QColors.InkSecondary, maxLines = 3)
            }
        }
        Txt(stringResource(R.string.rules_mistakes), "rules/mistakes", style = PicoTheme.typography.titleSmall)
        listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3, R.string.mistake_4).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { id ->
                    Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(QColors.TagFace).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                        Txt(stringResource(id), "rules/mistake", style = PicoTheme.typography.bodySmall, color = QColors.Ink, maxLines = 2)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.rules_source), "rules/source", style = PicoTheme.typography.bodySmall, color = QColors.InkSecondary, maxLines = 2, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            PrimaryButton(stringResource(if (state.welcome) R.string.btn_go else R.string.btn_back_game), "rules/go", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY })
        }
    }
}

/** The design's one picture: a 9 x 9 board, your pawn near, a wall across the middle, your gold path going round it. */
@Composable
private fun RulesDiagram() {
    Canvas(Modifier.size(132.dp)) {
        val cell = size.width / 9f
        drawRoundRect(QColors.Walnut, Offset.Zero, size, CornerRadius(8f, 8f))
        for (r in 0..8) for (c in 0..8) drawRoundRect(QColors.Lacquer, Offset(c * cell + 2f, r * cell + 2f), Size(cell - 4f, cell - 4f), CornerRadius(3f, 3f))
        fun ctr(c: Int, r: Int) = Offset((c + 0.5f) * cell, (8 - r + 0.5f) * cell)
        // gold path e1 -> e3 -> c3 (round the wall) -> c9
        val path = listOf(ctr(4, 0), ctr(4, 3), ctr(2, 3), ctr(2, 8))
        val p = Path().apply { moveTo(path[0].x, path[0].y); path.drop(1).forEach { lineTo(it.x, it.y) } }
        drawPath(p, QColors.BandGold, style = Stroke(5f))
        // a wall across d4-e4 / f4 (two squares wide) between rows 4 and 5
        drawRoundRect(QColors.Maple, Offset(3 * cell, (8 - 4) * cell - 3f), Size(2 * cell, 6f), CornerRadius(2f, 2f))
        drawRoundRect(QColors.Maple, Offset(4 * cell, (8 - 3) * cell - 3f), Size(2 * cell, 6f), CornerRadius(2f, 2f))
        drawCircle(QColors.Ivory, cell * 0.36f, ctr(4, 0))
        drawCircle(QColors.Cinnabar, cell * 0.36f, ctr(4, 8))
    }
}

// ------------------------------------------------------------------ Settings (S11)

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    if (Ui.exitConfirm) {
        ConfirmPage(R.string.confirm_exit, R.string.confirm_exit_line, R.string.btn_cancel, R.string.btn_yes_exit, R.drawable.ic_exit, onYes = { exitApp(context, graph) }, onNo = { Ui.exitConfirm = false })
        return
    }
    val onOff = listOf(true to stringResource(R.string.opt_on), false to stringResource(R.string.opt_off))
    PageColumn {
        OptionRow(stringResource(R.string.settings_language), "settings/lang", labelWidth = 104.dp) {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        // QA known issue (glass): every switch is an On / Off segment; volume is a 3-stop slider with %.
        OptionRow(stringResource(R.string.settings_sound), "settings/sound", labelWidth = 104.dp) {
            Segments(onOff, settings.sound, "settings/sound", { v -> upd { it.copy(sound = v) } }, Modifier.width(132.dp))
            Spacer(Modifier.width(12.dp))
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_reduced), "settings/reduced", labelWidth = 104.dp) {
            Segments(onOff, settings.reducedMotion, "settings/reduced", { v -> upd { it.copy(reducedMotion = v) } }, Modifier.width(132.dp))
            Spacer(Modifier.width(12.dp))
            SecondaryButton(stringResource(R.string.settings_reopen), "settings/reopen", onClick = { graph.window.reopen() }, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.settings_about_label), "settings/about-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(104.dp))
            Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = QColors.InkSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        }
        Txt(stringResource(R.string.settings_reopen_hint), "settings/reopen-hint", style = PicoTheme.typography.bodySmall, color = QColors.InkSecondary)
        // D26: quit is the last row; the confirmation is a whole-page card.
        SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT S13") }, icon = R.drawable.ic_exit, modifier = Modifier.fillMaxWidth(), danger = true)
    }
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}
