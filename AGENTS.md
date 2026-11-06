# 乌尔王族棋 Royal Game of Ur: project guidance

Finkel-rules Royal Game of Ur for PICO OS 6 Shared Space, PICO Spatial SDK BOM 6.1.9, package `io.embodit.quoridorwalls`, v0.1.1.
Design contract: spatial-app-factory `apps/royal-ur/design-package.json` (v1.2 after QA round 1; profile A12 one main loop, `v2[]` not built:
no Walker rules, no one-tap move, no replay / codes, no skins, no pause menu, no board flip, no third AI level).
Skeleton from the Five Stones project (itself from kejing-dev/CloudAir v1.0.2: two containers, theme, sound pool, level gate);
the tetrahedral dice solver is CloudAir's DiceSolver rewritten for four-sided dice; the Ur rules / AI / flow are new.

## Surfaces
- **Board volume** (default container `RoyalBoard`, Volumetric 0.80 x 0.30 x 0.42 m, fixed scale, gravity aligned, uniform
  resize). `board.glb` (0.53 x 0.03 x 0.23 m inlaid box, 20 raised shell tiles with five patterns, 5 rosettes) on `tiltRoot`
  (0 / 10 / 15°, pivot on the near-bottom edge). v1.2 (QA q1-1, the reset eye is almost level): your start tower stands
  left of the board, your finish tower and the cup right of it (flat, never tilt); the far side's towers stay in the A-row
  gaps; the tray sits front-right, the seat tag + dice tag in the low zone in front (nothing in front of the board is
  taller than 2.8 cm). 14 pieces (obsidian near / red jasper far) and all rings are scene children, tipped with the board
  when on it. Rings: a coloured unlit ring (0.95) on top, a dark under-ring 1 mm wider each side and 0.6 mm lower (q1-2).
- **Control window** (`RoyalControl`, Planar 560 x 620 dp, `Placement.right(RoyalBoard)`): native SpatialUI `TabBar`, four pages
  对局 / 新局 / 规则 / 设置. Every page: header with [静音], content vertically centred (blank <= 25 %, `tools/blank.py`).
  Game page: dice box, then [掷骰] 132 + your-pieces stats, or one button per movable piece (T14: consequence + mini route,
  `GameFlow.panelPick` = a pinch on that piece; [走 · …] when only one move), or a notice card. Confirmations are a whole-page
  card and lock the volume (`GameFlow.uiLocked` / `confirm`). Settings switches are On / Off segments. Settings [重开控制窗] closes and reopens beside the board (profile D44:
  no "in front of you" in Shared Space). [退出应用] closes both containers, removes the tasks, exits the process.

## Code
| Path | What |
|---|---|
| `domain/Ur.kt` | route (progress 0..15), legal moves, capture / rosette / exact bear-off, winner |
| `domain/Dice.kt` | `Tetra` solver (240 Hz rigid tetrahedron, result committed first, symmetry swap, no last-frame flip), `Roll` |
| `domain/Ai.kt` | EASY random / SMART priority table |
| `domain/GameFlow.kt` | the single `PlayState`, phases S03..S11, AI timing, undo (move, not throw) / redo, save, debug freeze |
| `domain/Game.kt` | config, settings, snapshots, `SaveCodec` (`files/game.txt`, atomic + backup) |
| `domain/Layout.kt` | metres for everything, model boxes, tag rectangles, eyes (unit-tested D35 check) |
| `ui/board/*` | `BoardScene` (all entities; per-frame placement in `onFrame`, driven by `BoardFrameSystem`), `BoardScreen` (taps, tags) |
| `ui/control/*` | control window pages; every Text logs `OVERFLOW <tag>` when cut |
| `platform/*` | Application / Graph, SoundPlayer, WindowCtl (+ LevelGate); debug builds add `DebugCommandReceiver` |
| `tools/` | `realtap_game.py` (one whole game with real taps), `blank.py` (window blank band), `build_models.py` (all GLBs, `--test-f` = F↑ tiles), `make_sounds.py`, `build_icon.py`, `loudness.py`, `shots.py`, `ru.sh` |

Rules - state - render: the scene only renders `PlayState`. Volume input goes through the factory board-input template
(`ui/board/input/BoardInput.kt`, D50: short drags count as taps, targets resolved up the parent chain); a pinch on a collider (`cup`, `tray`, `board`, `sq:B3`, `pc:<side>:<p>`,
`tw:<side>:0|15`) goes through `route()` to the same `GameFlow` calls the panel and the debug broadcast use.

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest      # 28 JVM tests: rules, dice, AI strength, flow / undo / save, occlusion rays (measured reset eye), level gate
python3 tools/build_models.py ; python3 tools/make_sounds.py ; python3 tools/build_icon.py --icon512 icon.png
DEV=emulator-5562 tools/ru.sh install
```
Emulators are shared: borrow with `python3 -I bin/emu_borrow.py borrow royal-ur` in the factory, return after.
Reinstalling over a newer versionCode fails silently in `adb install -r`; `ru.sh install` passes `-d`.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.quoridorwalls.DEBUG -p io.embodit.quoridorwalls --es cmd <cmd> --es a "<arg>"` (or `tools/ru.sh cmd <cmd> [arg]`). Log tag `RoyalUr`.

| State | Command | Notes |
|---|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture within 10 s, `s00hold off` | dark slab + cylinder stand-in |
| S01 第一次 | `goto S01` | default game (AI easy, you first) at S03 + rules page; first pinch on the cup ends it |
| S02 新局 | `goto S02` | [继续上一盘] on top when a save exists |
| S03 待掷 | `goto S03` | cup ring breathes |
| S04 掷骰中 | `goto S04` | frozen 450 ms into the throw (cup tipped, dice in the air) |
| S05 待选子 | `goto S05` (roll 3) · `hint on` = amber ring | lit pieces |
| S06 已选子 | `goto S06` | capture preview: orange target ring, the rival piece flashes |
| S07 走子中 | `goto S07` (capture, rival at the arc top) · step / off: `setup` + `roll` + `pick` + `freeze 110` + `move` | |
| S08 再来一把 | `goto S08` | held by freeze; `freeze off` to go on |
| S09 走不了 | `goto S09` | 0 roll, held |
| S10 对面在想 | `goto S10` | AI held (`hold off` lets it go) |
| S11 终局 | `goto S11` | you bear off the last piece |
| S12 设置 / S13 规则 | `goto S12` / `goto S13` · `exitask` | |
| S14 确认 | `goto S14` (restart) · `resign` · `exitask` · 2P undo: `new "2p - me"`, two moves, `undo` · `confirm` / `no` | |
| S15 非法 | `goto S15` | tapping a rival piece, held |
| S16 恢复 | force-stop after a roll, relaunch | back to S05 with the same roll |
Modifiers: `lang zh|en|system`, `reduced on|off`, `sound on|off`, `volume 0-2`, `hint on|off`, `coach on|off`, `tilt 0|10|15`,
`roll N`, `setup "<14 hex digits> [side]"` (near 7 then far 7, 0 = start tower, f = off), `new "<ai|2p> <easy|smart> <me|them|random>"`,
`pick P`, `move`, `cancel`, `tap <collider>`, `freeze MS|off`, `hold on|off`, `seed N`, `reopen`, `closepanel`, `dump`, `evidence`.
`freeze` only holds animations that start after it.

## Known
- 3D taps (cup, pieces, squares) are not reachable by `pico-cli interact` (only the 2D window publishes nodes); on the emulator they
  were driven by the debug broadcast through the same `route()`. Controller ray / gaze + pinch on 4.5 cm pieces: headset only.
- Sounds are SoundPool (not spatial). Physical sounds are Kenney CC0 recordings (`tools/import_cc0_sounds.py`); UI cues are synthesised (`tools/make_sounds.py`).
- Hover: a faint ring where a resting pointer is (`BoardScene.setHover`, debug `hover <collider>`); whether Shared Space delivers hover pointer events is not verified on a headset. Seat / dice tags face +Z (no head pose in Shared Space for a billboard).
- Shared Space places the volume where the last task was; after `adb install -r` with the app running it may come back off-centre:
  exit with [退出应用] (removes the task), reset the camera, relaunch.
