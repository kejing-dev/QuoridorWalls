"""QA / self-test screenshot driver (debug builds): runs debug-broadcast steps, captures, crops window / board.

  python3 tools/shots.py OUTDIR plan.json      plan: [{"name": .., "pose": "win"|"board"|null, "steps": [[cmd, arg], ["sleep", s]], "crop": "win"|"board"|"full"}]
"""
import json, os, subprocess, sys, time
from PIL import Image

DEV = os.environ.get("DEV", "emulator-5560")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
POSES = {"reset": "", "left30": "move left 0.95 meters then move forward 0.25 meters then turn right 30 degrees", "right30": "move right 0.95 meters then move forward 0.25 meters then turn left 30 degrees", "fwd12": "move forward 1.2 meters", "win": "turn right 29 degrees then move forward 1.0 meters", "board": "move forward 1.2 meters"}
CROPS = {"win": (330, 500, 1750, 2260), "board": (380, 1150, 2560, 2350), "full": None}
pose_now = os.environ.get("POSE")


def cmd(c, a=""):
    subprocess.run([ADB, "-s", DEV, "shell", "am", "broadcast", "-a", "io.embodit.quoridorwalls.DEBUG", "-p", "io.embodit.quoridorwalls",
                    "--es", "cmd", c, "--es", "a", '"%s"' % a], capture_output=True)


def pose(p):
    global pose_now
    if p is None or p == pose_now:
        return
    subprocess.run(["pico-cli", "camera", "reset", "-d", DEV], capture_output=True)
    if POSES[p]:
        subprocess.run(["pico-cli", "camera", "move", "-d", DEV, "--text", POSES[p]], capture_output=True)
    pose_now = p


def shot(path):
    subprocess.run(["pico-cli", "capture", "screenshot", "-d", DEV, "-o", path, "--replace"], capture_output=True)


def main(out, plan):
    os.makedirs(out, exist_ok=True)
    for item in json.load(open(plan)):
        pose(item.get("pose"))
        for st in item.get("steps", []):
            if st[0] == "sleep":
                time.sleep(float(st[1]))
            elif st[0] == "adb":
                subprocess.run([ADB, "-s", DEV] + st[1].split(), capture_output=True)
            else:
                cmd(st[0], st[1] if len(st) > 1 else "")
                time.sleep(0.15)
        raw = os.path.join(out, item["name"] + "-raw.png")
        shot(raw)
        im = Image.open(raw)
        c = CROPS[item.get("crop", item.get("pose") or "full")]
        (im.crop(c) if c else im).save(os.path.join(out, item["name"] + ".png"))
        os.remove(raw)
        print("shot", item["name"], flush=True)


main(sys.argv[1], sys.argv[2])
