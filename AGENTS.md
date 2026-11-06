# 围墙棋 Quoridor Walls: project guidance

Gigamic Quoridor (you vs the AI) for PICO OS 6 Shared Space, PICO Spatial SDK BOM 6.1.9, package `io.embodit.quoridorwalls`, v0.1.0.
Design contract: spatial-app-factory `apps/quoridor-walls/design-package.json` (profile A12: one main loop, everything in `v2[]`
is not built: no two players, no four players, no replay / notation, no tilt / skins, no five levels, no puzzles, no timer, no
pointer-driven wall orientation, no point-hover-point wall path). Skeleton from the Royal Ur project (itself Five Stones / Cloud Air:
two containers, theme, sound pool, level gate, board-input template); rules, engine, scene and pages are new.

## Surfaces
- **Board volume** (default container `QuoridorBoard`, Volumetric 0.56 x 0.36 x 0.62 m, fixed scale, gravity aligned, uniform
  resize). `board.glb` (0.49 x 0.025 x 0.53 m: teal lacquer cells raised 1.5 mm, grooves, walnut frame, two rails, brass inlays)
  hangs from `tiltRoot` at its near-bottom edge (0, -0.18, +0.27), tipped 20°. 20 maple `wall.glb` (9.6 x 2.4 x 0.6 cm) lie
  flat in the rails (5 per layer, 2 layers, the top layer is used first), 2 turned pawns (ivory / cinnabar). Marks (rings, dots,
  path bands, ghost wall, red flash, slot glows) are SDK meshes. Seat card (AttachmentPanel 0.26 x 0.035 m) stands on the
  floor at the front: whose turn + both wall counts, or why the wall in hand can't go there.
- **Control window** (`QuoridorControl`, Planar 520 x 400 dp, `Placement.right(QuoridorBoard)`): native SpatialUI `TabBar`,
  pages 对局 / 新局 / 规则 / 设置. Confirmations (resign / new game / quit) are whole-page cards and lock the volume.
  Settings [重开控制窗] closes and reopens beside the board (profile D44). [退出应用] closes both containers, removes the
  tasks, exits the process.

## Code
| Path | What |
|---|---|
| `domain/Quoridor.kt` | cells, walls, `Pos`, `Board` (steps incl. straight / diagonal jumps, wall fit + BFS legality, the one BFS `dist` / `path`) |
| `domain/Engine.kt` | negamax + alpha-beta, EASY (random top-3 steps, walls only when you are <= 4 away, half the time) / MEDIUM depth 2 / HARD depth 3; candidate walls cut the opponent's shortest path |
| `domain/GameFlow.kt` | the single `PlayState`: S03 idle, S04 selected, S05 anim, S06 AI, S07 wall in hand, S08 bounce, S09 result; commit-before-animate, watchdog, undo, hint, bands, save |
| `domain/Layout.kt` | every metre (board-local frame), `SlotTracker` (slotAt + 3.1 cm hysteresis + EMA), occlusion boxes / eyes |
| `domain/Game.kt` | config, settings, snapshots, `SaveCodec` (`files/game.txt`, atomic + backup) |
| `ui/board/*` | `BoardScene` (entities, per-frame placement in `onFrame` from `BoardFrameSystem`), `BoardScreen` (board-input listener, seat card), `input/BoardInput.kt` (factory template, D50) |
| `ui/control/*` | the window pages; every Text logs `OVERFLOW <tag>` when cut |
| `platform/*` | Application / Graph, SoundPlayer, WindowCtl (+ LevelGate); debug builds add `DebugCommandReceiver` |
| `tools/` | `build_models.py` (all GLBs, `--test-f` = F↑ textures), `import_cc0_sounds.py` (Kenney CC0), `make_sounds.py` (UI cues), `loudness.py`, `build_icon.py`, `blank.py`, `qw.sh`, `shots.py` |

Input (D50): one `Modifier.boardInput` on the SpatialView. Targets: `sq:<cell 0..80>`, `pawn:<side>`, `rail` (your rail and the
walls lying in it), `ghost`, `board`. A pinch on a lit cell steps (selected or not, D27); on your pawn selects; holding on the
rail and dragging past 2.5 cm lifts the top wall (S07): drag deltas are added to the wall's start point and projected on the
board plane, `SlotTracker` snaps the ghost, letting go sets / bounces / returns it. A pinch on the ghost (other hand) or the
window's [横 / 竖] flips it. With the control window closed (S10) the first pinch on the volume only reopens it.

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest      # 57 JVM tests: rules (perft 131 + independent implementation), AI strength, slotAt jitter, flow, occlusion
python3 tools/build_models.py ; python3 tools/import_cc0_sounds.py ; python3 tools/make_sounds.py ; python3 tools/build_icon.py --icon512 icon.png
DEV=emulator-5560 tools/qw.sh install
```
Emulators are shared: `python3 -I bin/emu_borrow.py borrow quoridor-walls` in the factory, `return` after. Reset the camera
(`pico-cli camera reset`) before launching: the windows open where the camera looks.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.quoridorwalls.DEBUG -p io.embodit.quoridorwalls --es cmd <cmd> --es a "<arg>"`
(or `tools/qw.sh cmd <cmd> [arg]`). Log tag `Quoridor` (`STATE`, `MOVE`, `WALL`, `HOLD`, `REJECT`, `AI`, `UNDO`, `RESULT`,
`WINDOW`, `PAGE`, `OVERFLOW`, `PLACEMENT`, `WATCHDOG`; input lines `TAP / DRAG / HOVER / POINTER` also in `files/input-log.txt`).

| State | Command | Notes |
|---|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture within 10 s, `s00hold off` | teal slab stand-in |
| S01 第一次 | `goto S01` | easy game (you first) + rules page; [开始下棋] |
| S02 新局 | `goto S02` | [继续上一盘] on top when a save exists |
| S03 待走 | `goto S03` | `coach on` = guided first game (lit cells / slots); `hint on` = yellow ring / ghost |
| S04 已选子 | `goto S04` | you e4, AI e5: dots + jump target |
| S05 走子中 | `goto S05` | jump e4-e6 held at 160 ms (`freeze off` after) |
| S06 AI 在想 | `goto S06` | expert, held (`hold off` lets it move) |
| S07 墙在手 | `goto S07` (legal e6h) · `goto S07 c7h` (red: crosses c7v) · `hover ""` (off the board, grey) | `orient` flips |
| S08 违规弹回 | `goto S08` | f8v would box the AI in; red flash held at 120 ms (`freeze off` after) |
| S09 终局 | `goto S09` · `resign` + `confirm` | you step onto e9 |
| S10 面板已关 | `goto S10` / `closepanel` · `reopen` | |
| S11 设置 / S12 规则 | `goto S11` / `goto S12` · `exitask` | |
| S13 确认 | `goto S13` (resign) · `exitask` · `newask` (only with progress) · `confirm` / `no` | |
| S16 存档恢复 | force-stop mid game, relaunch | |
Also: `tap e5|pawn:0|rail|ghost|board`, `move e2`, `pick` / `hover e3h` / `drop` / `back`, `wall e3h`, `setup "you=e4 ai=e6 walls=e3h0.d5v1 left=9,9 side=0"`,
`new "you|ai|random 1-3"`, `undo`, `hint on|off`, `bands on|off`, `lang zh|en|system`, `sound on|off`, `volume 0-2`, `reduced on|off`,
`coach on|off`, `seed N`, `freeze MS|off`, `hold on|off`, `hover-cell e5`, `dump`, `evidence`.

## QA screenshot list
S00 S01 S02 S03 (+首局照亮 / 提示 / 墙用完 / 静音) S04 S05 S06 S07 (合法 / 红 / 盘外灰 / 首局亮槽) S08 (+减少动态) S09 (赢 / 认输)
S10 S11 S12 S13 (认输 / 新局 / 退出) S16, each in 中文 and English. S14 / S15 are not in v1 (design: 不适用).

## Known
- 3D taps / drags are not reachable by `pico-cli interact` on the Mac emulator (only the window publishes nodes); the volume was
  driven by the debug broadcast through the same `GameFlow` calls. Controller ray / hand ray / eye-hand on the board: headset only.
- Controller secondary button (A / X) for the wall orientation is not wired (no key events reach a Shared Space volume that we could
  verify); orientation = [横 / 竖] or a pinch on the ghost. Pawn drag-to-move is not built (tap path only).
