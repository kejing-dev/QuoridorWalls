"""Per-skin mark contrast on real screenshots (board-game.md「每套皮肤都要逐个核标记」, QA q3-1), debug builds.

  DEV=emulator-5560 python3 tools/marks_check.py OUTDIR [modern classic]

For each skin and each kind of mark (step dot, jump dot+ring, selection ring, hint ring, illegal ring, hover ring, last-move
ring, goal ring) it sets the state up, shows only that kind (`marks <kind>`) and shoots, then hides all marks (`marks none`)
and shoots again. Pixels that changed between the two shots are the mark; its L* is their median in the first shot, the
background is the median of the same pixels in the second shot. Prints a markdown table (ΔL* >= 25 passes).
The camera must already sit where the board fills the view (tools/qw.sh, walk-in pose)."""
import os, subprocess, sys, time
import numpy as np
from PIL import Image

DEV = os.environ.get("DEV", "emulator-5560")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "io.embodit.quoridorwalls"
OUT = sys.argv[1]
SKINS = sys.argv[2:] or ["modern", "classic"]


def q(cmd, a=""):
    subprocess.run([ADB, "-s", DEV, "shell", "am", "broadcast", "-a", PKG + ".DEBUG", "-p", PKG, "--es", "cmd", cmd, "--es", "a", '"%s"' % a], capture_output=True)
    time.sleep(0.15)


def shot(path):
    subprocess.run(["pico-cli", "capture", "screenshot", "-d", DEV, "-o", path, "--replace"], capture_output=True)


def lstar(a):
    c = a / 255.0
    c = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    y = 0.2126 * c[..., 0] + 0.7152 * c[..., 1] + 0.0722 * c[..., 2]
    return 116 * np.cbrt(y) - 16


SETUPS = {
    "dot": [("freeze", "off"), ("goto", "S04")],
    "jump": [("freeze", "off"), ("goto", "S04")],
    "select": [("freeze", "off"), ("goto", "S04")],
    "hint": [("freeze", "off"), ("goto", "S03"), ("setup", "you=e2 ai=e8 side=0"), ("hint", "on"), ("sleep", "2.5")],
    "alert": [("freeze", "off"), ("hint", "off"), ("goto", "S04"), ("freeze", "600000"), ("tap", "e7")],
    "hover": [("freeze", "off"), ("goto", "S03"), ("hover-cell", "e5")],
    "last": [("freeze", "off"), ("hover-cell", ""), ("goto", "S03"), ("setup", "you=e3 ai=e7 side=0"), ("move", "e4"), ("sleep", "3")],
    "goal": [("freeze", "off"), ("goto", "S09"), ("sleep", "2")],
}
NAMES = {"dot": "可走点", "jump": "跳落点（点 + 环）", "select": "选中底环", "hint": "提示环", "alert": "非法红环", "hover": "hover 环", "last": "上一步环", "goal": "到底线金环"}

os.makedirs(OUT, exist_ok=True)
rows = []
for skin in SKINS:
    q("skin", skin); q("bands", "off"); time.sleep(0.6)
    for kind, steps in SETUPS.items():
        q("marks", "all")
        for c, a in steps:
            if c == "sleep": time.sleep(float(a))
            else: q(c, a)
        q("bands", "off")   # measured against the cells: a new game / setup turns the bands back on
        time.sleep(1.0)
        q("marks", kind); time.sleep(0.8)
        on = os.path.join(OUT, f"{skin}-{kind}-on.png"); shot(on)
        q("marks", "none"); time.sleep(0.8)
        off = os.path.join(OUT, f"{skin}-{kind}-off.png"); shot(off)
        a = np.asarray(Image.open(on).convert("RGB")).astype(float)
        b = np.asarray(Image.open(off).convert("RGB")).astype(float)
        diff = np.abs(a - b).max(axis=2) > 18
        n = int(diff.sum())
        if n < 30:
            rows.append((skin, kind, None, None, n)); continue
        rows.append((skin, kind, float(np.median(lstar(a)[diff])), float(np.median(lstar(b)[diff])), n))
    q("marks", "all"); q("freeze", "off"); q("hover-cell", ""); q("hint", "off"); q("bands", "on")
print("| 皮肤 | 标记 | 标记 L* | 底（格面）L* | ΔL* | 结论（≥ 25） | 像素 |")
print("|---|---|---|---|---|---|---|")
for skin, kind, lm, lb, n in rows:
    if lm is None:
        print(f"| {skin} | {NAMES[kind]} | — | — | — | 未取到 | {n} |"); continue
    d = abs(lm - lb)
    print(f"| {skin} | {NAMES[kind]} | {lm:.0f} | {lb:.0f} | {d:.0f} | {'过' if d >= 25 else '不过'} | {n} |")
