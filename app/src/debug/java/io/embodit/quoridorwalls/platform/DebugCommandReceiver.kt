package io.embodit.quoridorwalls.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.quoridorwalls.domain.ConfirmKind
import io.embodit.quoridorwalls.domain.Engine
import io.embodit.quoridorwalls.domain.FirstPick
import io.embodit.quoridorwalls.domain.GameConfig
import io.embodit.quoridorwalls.domain.GameSetup
import io.embodit.quoridorwalls.domain.Language
import io.embodit.quoridorwalls.domain.Q
import io.embodit.quoridorwalls.domain.SaveCodec
import io.embodit.quoridorwalls.domain.Settings
import io.embodit.quoridorwalls.domain.Wall
import io.embodit.quoridorwalls.ui.Page
import io.embodit.quoridorwalls.ui.Ui
import io.embodit.quoridorwalls.ui.board.SceneHolder
import io.embodit.quoridorwalls.ui.board.route
import io.embodit.quoridorwalls.ui.board.BoardNames
import io.embodit.quoridorwalls.ui.board.BoardTargets
import io.embodit.quoridorwalls.ui.board.input.BoardInputLog
import io.embodit.quoridorwalls.ui.board.input.InputSource
import io.embodit.quoridorwalls.ui.board.input.resolveUp
import io.embodit.quoridorwalls.domain.Skin

/**
 * Debug builds only. Every command goes through the same GameFlow calls the volume and the window use (D29).
 *
 *   adb shell am broadcast -a io.embodit.quoridorwalls.DEBUG -p io.embodit.quoridorwalls --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S01..S16           reach a named state (AGENTS.md lists what each sets up)
 *   tap <target>            a pinch on a target: sq:<cell 0..80> | e2 (cell name) | pawn:0 | pawn:1 | rail:0 | rail:1 | slot:<c>:<r> | ghost | board
 *   move e2                 a pinch on that cell (the lit-cell path)
 *   pick · hover e3h · drop · orient · back      the wall-in-hand path (= hold on the rail, drag over a slot, let go)
 *   wall e3h                pick + hover + drop in one go          setup "you=e4 ai=e6 walls=e3h0.d5v1 left=9,9 side=0"
 *   new "you 1" | "ai 3" | "random 2" | "you 1 local" (two players on one headset)   undo · hint on|off · bands on|off · resign · confirm · no · exitask · newask
 *   page play|new|rules|settings · lang zh|en|system · sound on|off · volume 0-2 · reduced on|off · coach on|off · seed N
 *   freeze MS|off · hold on|off (AI waits) · s00hold on|off · welcome · reopen · closepanel · hover-cell e5 · dump · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        Log.i(TAG, "CMD $cmd $a")
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun game(level: Int = Engine.EASY, first: Int = Q.YOU) { flow.freeze(null); flow.holdThinking = false; flow.newGame(GameConfig(level, first)); Ui.page = Page.PLAY }
        fun setup(text: String) { val (p, side) = SaveCodec.parseSetup(text); flow.setup(p, side ?: Q.YOU) }
        fun wall(w: Wall) { if (flow.pickWall()) { flow.holdOver(w); flow.dropWall() } }
        when (cmd) {
            "goto" -> when (a.uppercase().substringBefore(' ')) {
                "S00" -> Log.i(TAG, "S00 is the first 0.5 s after launch: s00hold on, force-stop and start, then capture")
                "S01" -> { settings { it.copy(firstRunDone = false, coachDone = false) }; flow.freeze(null); flow.start() }
                "S02" -> { flow.toMenu(); Ui.page = Page.NEW }
                "S03" -> { game(); setup("you=e3 ai=e7 walls=d5h1.f4v0 side=0") }
                "S04" -> { game(); setup("you=e4 ai=e5 walls=c6h1 side=0"); flow.tapPawn(Q.YOU) }
                "S05" -> {
                    // a jump in flight: e4 over e5 to e6, held at the top of its arc
                    game(); setup("you=e4 ai=e5 side=0"); flow.freeze(160); flow.tapCell(Q.parseCell("e6")!!)
                }
                "S06" -> { game(Engine.HARD); flow.holdThinking = true; setup("you=e3 ai=e7 walls=d5h1 side=1") }
                "S07" -> { game(); setup("you=e3 ai=e7 walls=c7v1 side=0"); if (flow.pickWall()) flow.holdOver(Q.parseWall(a.substringAfter(' ', "e6h").ifBlank { "e6h" }) ?: Wall(4, 5, io.embodit.quoridorwalls.domain.Orient.H)) }
                "S08" -> {
                    // a wall that would box the AI in: walls round e9 leave only the f8 gap, the rejected wall closes it
                    game(); setup("you=e2 ai=e9 walls=d8v1.e8h0 left=9,9 side=0"); flow.freeze(120)
                    if (flow.pickWall()) { flow.holdOver(Q.parseWall("f8v")!!); flow.dropWall() }
                }
                "S09" -> { game(); setup("you=e8 ai=c3 side=0"); flow.tapCell(Q.parseCell("e9")!!) }
                "S10" -> graph.window.closeControlOnly()
                "S11" -> Ui.page = Page.SETTINGS
                "S12" -> Ui.page = Page.RULES
                "S13" -> { Ui.page = Page.PLAY; flow.requestConfirm(ConfirmKind.RESIGN) }
                // design occlusionRule.qa: 10 walls across between rows 4 and 5 (the row-5 centres behind them must show)
                "WALLS10" -> { game(); setup("you=e3 ai=e8 walls=a4h0.c4h0.e4h0.g4h0.b6h1.d6h1.f6h1.h6h1.a8h1.c8h1 left=6,6 side=0") }
                "S16" -> { Log.i(TAG, "restore=${flow.continueSaved()}"); Ui.page = Page.PLAY }
                else -> Log.w(TAG, "unknown state $a")
            }
            "tap" -> route(graph, BoardTargets.resolveName(Q.parseCell(a)?.let { BoardNames.square(it) } ?: a, flow.state.value.pos.left))
            // board-input 命中回放: take the real scene entities (gaze plate, cell holder, the pawn's deepest model mesh, the
            // next rail wall's mesh), resolve them up the parent chain like a real hit and hand the deepest to the listener
            "replay" -> {
                val f = a.split(" ").filter { it.isNotBlank() }
                val what = f.getOrNull(0)?.let { w -> Q.parseCell(w)?.let { BoardNames.square(it) } ?: w } ?: "sq:13"
                val src = InputSource.entries.firstOrNull { it.label == f.getOrNull(1) } ?: InputSource.HAND_RAY
                val scene = SceneHolder.scene; val resolver = SceneHolder.resolver; val listener = SceneHolder.listener
                if (scene == null || resolver == null || listener == null) Log.w(TAG, "REPLAY no scene yet")
                else {
                    val ents = scene.replayEntities(what)
                    ents.forEachIndexed { i, e ->
                        val (target, path) = resolveUp(e, resolver)
                        BoardInputLog.write(TAG, "REPLAY src=${src.label} path=$path -> target=$target -> ${BoardTargets.logOf(target) { flow.state.value.pos.pawns[it] }}${if (i == 0) " (delivered)" else ""}")
                    }
                    ents.firstOrNull()?.let { listener.onTap(resolveUp(it, resolver).first, src, null) }
                }
            }
            "light" -> { val f = a.split(" ").mapNotNull { it.toFloatOrNull() }; SceneHolder.scene?.setLighting(f.getOrElse(0) { 1f }, f.getOrElse(1) { io.embodit.quoridorwalls.ui.board.BoardScene.KEY_LIGHT }) }
            "marks" -> SceneHolder.scene?.markFilter = when (a) { "", "all" -> null; "none" -> emptySet(); else -> a.split(",").toSet() }
            "skin" -> settings { it.copy(skin = if (a == "classic") Skin.CLASSIC else Skin.MODERN) }
            "move" -> Q.parseCell(a)?.let { flow.tapCell(it) }
            "pick" -> flow.tapRail(flow.state.value.side)
            "hover" -> flow.holdOver(Q.parseWall(a))
            "drop" -> flow.dropWall()
            "orient" -> flow.toggleOrient()
            "back" -> flow.cancelHold("debug")
            "wall" -> Q.parseWall(a)?.let { wall(it) }
            "setup" -> { if (flow.state.value.config == null) game(); setup(a) }
            "new" -> {
                val f = a.split(" ").filter { it.isNotBlank() }
                flow.newGame(GameSetup(
                    level = f.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 3) ?: Engine.EASY,
                    first = when (f.getOrNull(0)) { "ai", "them", "far" -> FirstPick.THEM; "random" -> FirstPick.RANDOM; else -> FirstPick.ME },
                    mode = if (f.contains("local")) io.embodit.quoridorwalls.domain.Mode.LOCAL else io.embodit.quoridorwalls.domain.Mode.AI,
                ))
                Ui.page = Page.PLAY
            }
            "undo" -> flow.requestUndo()
            "hint" -> flow.setHint(on)
            "bands" -> flow.setBands(on)
            "resign" -> flow.requestConfirm(ConfirmKind.RESIGN)
            "confirm" -> flow.confirm()
            "no" -> flow.cancelConfirm()
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "newask" -> { Ui.page = Page.NEW; Ui.newConfirm = flow.state.value.hasProgress }
            "page" -> Ui.page = when (a) { "new" -> Page.NEW; "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> settings { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "coach" -> { settings { it.copy(coachDone = !on) }; flow.debugState { it.copy(firstGame = on, turnsYou = 0) } }
            "seed" -> a.toIntOrNull()?.let { flow.reseed(it) }
            "freeze" -> flow.freeze(if (a == "off") null else a.toLongOrNull())
            "hold" -> flow.holdThinking = on
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "welcome" -> flow.welcomeDone()
            "reopen" -> graph.window.reopen()
            "closepanel" -> graph.window.closeControlOnly()
            "hover-cell" -> SceneHolder.scene?.setHover(Q.parseCell(a)?.let { BoardNames.square(it) } ?: a.ifBlank { null }, sticky = true)
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}
