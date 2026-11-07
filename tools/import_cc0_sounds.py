"""Physical sounds from Kenney CC0 packs (casino-audio / impact-sounds), profile B20 / D51 + ui_gates sound-source.

  python3 tools/import_cc0_sounds.py [~/Downloads/cc0-sounds]

Decodes the .ogg files with macOS afconvert (22.05 kHz mono), trims silence, low-passes the harsh top (6.5 kHz,
two passes), fades the tail, cuts long ones, normalises to -1 dBFS peak and writes app/src/main/assets/sounds/<name>[_n].wav.
Variants: 2-4 recordings per event; the app adds a ±5 % random pitch per play (SoundPlayer). The UI cues (illegal,
band, win, lose) stay synthesised by tools/make_sounds.py.
"""
import math, os, subprocess, sys, tempfile, wave
import numpy as np

SRC = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Downloads/cc0-sounds")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
RATE = 22050

# event -> (max seconds, [pack/file, ...]); picked for short, light, clear sounds (no dull thuds): spectral centroid
# 1.1-1.8 kHz for the wood knocks, 60-180 ms long (profile D51; numbers in apps/quoridor-walls/build/sound-sources.md)
MAP = {
    # UI cues are Kenney interface-sounds CC0 recordings too (design sound.list): no synthesis except the band swish
    "illegal": (0.3, ["kenney_interface-sounds/Audio/error_004.ogg"]),
    "win": (1.2, ["kenney_interface-sounds/Audio/confirmation_002.ogg"]),
    "lose": (1.2, ["kenney_interface-sounds/Audio/back_002.ogg"]),
    "place": (0.2, ["kenney_impact-sounds/Audio/impactWood_light_000.ogg", "kenney_impact-sounds/Audio/impactWood_light_002.ogg", "kenney_impact-sounds/Audio/impactWood_light_003.ogg"]),
    "grab": (0.3, ["kenney_casino-audio/Audio/card-slide-1.ogg", "kenney_casino-audio/Audio/card-slide-5.ogg", "kenney_casino-audio/Audio/card-slide-6.ogg"]),
    "bounce": (0.25, ["kenney_impact-sounds/Audio/impactGeneric_light_001.ogg", "kenney_impact-sounds/Audio/impactGeneric_light_002.ogg"]),
}
# the wall seating in its groove ("咔", three variants): a plank knock for the wood body + a light tap on top for the click
WALL = [("kenney_impact-sounds/Audio/impactPlank_medium_000.ogg", "kenney_impact-sounds/Audio/impactGeneric_light_004.ogg"),
        ("kenney_impact-sounds/Audio/impactPlank_medium_002.ogg", "kenney_impact-sounds/Audio/impactGeneric_light_003.ogg"),
        ("kenney_impact-sounds/Audio/impactPlank_medium_004.ogg", "kenney_impact-sounds/Audio/impactGeneric_light_001.ogg")]
# two light wood knocks 90 ms apart ("哒哒", design sound.list: jump)
JUMP = [("kenney_impact-sounds/Audio/impactWood_light_001.ogg", "kenney_impact-sounds/Audio/impactWood_light_004.ogg"),
        ("kenney_impact-sounds/Audio/impactWood_light_004.ogg", "kenney_impact-sounds/Audio/impactWood_light_000.ogg")]


def decode(path):
    """afconvert -> 16-bit PCM wav; the chunks are parsed by hand (afconvert writes WAVE_FORMAT_EXTENSIBLE for some files)."""
    import struct
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "x.wav")
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@%d" % RATE, "-c", "1", "--mix", path, out], check=True)
        b = open(out, "rb").read()
    i, ch, data = 12, 1, b""
    while i + 8 <= len(b):
        cid, n = b[i:i + 4], struct.unpack("<I", b[i + 4:i + 8])[0]
        if cid == b"fmt ":
            ch = struct.unpack("<H", b[i + 10:i + 12])[0]
        elif cid == b"data":
            data = b[i + 8:i + 8 + n]
        i += 8 + n + (n & 1)
    x = np.frombuffer(data[:len(data) // 2 * 2], dtype="<i2").astype(float) / 32768
    return x.reshape(-1, ch).mean(axis=1) if ch > 1 else x


def lowpass(x, f, passes=2):
    w = 2 * math.pi * f / RATE; cw, sw = math.cos(w), math.sin(w); alpha = sw / (2 * 0.707)
    b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]; a = [1 + alpha, -2 * cw, 1 - alpha]
    b = [v / a[0] for v in b]; a1, a2 = a[1] / a[0], a[2] / a[0]
    for _ in range(passes):
        y = np.zeros_like(x); x1 = x2 = y1 = y2 = 0.0
        for i, v in enumerate(x):
            o = b[0] * v + b[1] * x1 + b[2] * x2 - a1 * y1 - a2 * y2
            x2, x1, y2, y1 = x1, v, y1, o; y[i] = o
        x = y
    return x


def highpass(x, f, passes=2):
    """Removes rumble / DC so the knocks stay clear, not a dull thud (profile D51: 短、轻、柔、清亮)."""
    w = 2 * math.pi * f / RATE; cw, sw = math.cos(w), math.sin(w); alpha = sw / (2 * 0.707)
    b = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]; a = [1 + alpha, -2 * cw, 1 - alpha]
    b = [v / a[0] for v in b]; a1, a2 = a[1] / a[0], a[2] / a[0]
    for _ in range(passes):
        y = np.zeros_like(x); x1 = x2 = y1 = y2 = 0.0
        for i, v in enumerate(x):
            o = b[0] * v + b[1] * x1 + b[2] * x2 - a1 * y1 - a2 * y2
            x2, x1, y2, y1 = x1, v, y1, o; y[i] = o
        x = y
    return x


def process(x, max_s):
    thr = 10 ** (-45 / 20)
    idx = np.where(np.abs(x) > thr)[0]
    if len(idx): x = x[max(0, idx[0] - 40):idx[-1] + 1]
    x = x[:int(max_s * RATE)]
    x = lowpass(x, 6500)
    x = highpass(x, 160)
    k = min(len(x), int(0.04 * RATE)); x[-k:] *= np.linspace(1, 0, k)
    k = min(len(x), int(0.003 * RATE)); x[:k] *= np.linspace(0, 1, k)
    return x * (10 ** (-1 / 20) / max(1e-9, np.abs(x).max()))


def write(name, x):
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


for old in [f for f in os.listdir(OUT) if f.startswith("select") and f.endswith(".wav")]:
    os.remove(os.path.join(OUT, old))   # D51 补: picking a pawn / hover make no sound
for event, (max_s, files) in MAP.items():
    for old in [f for f in os.listdir(OUT) if f.startswith(event) and f.endswith(".wav")]:
        os.remove(os.path.join(OUT, old))
    for i, rel in enumerate(files):
        name = event if i == 0 else f"{event}_{i + 1}"
        write(name, process(decode(os.path.join(SRC, rel)), max_s))
        print(f"{name}.wav <- Kenney {rel}")

for old in [f for f in os.listdir(OUT) if f.startswith("jump") and f.endswith(".wav")]:
    os.remove(os.path.join(OUT, old))
for i, (a, b) in enumerate(JUMP):
    x1 = process(decode(os.path.join(SRC, a)), 0.12); x2 = process(decode(os.path.join(SRC, b)), 0.12) * 0.8
    gap = int(0.09 * RATE)
    y = np.zeros(gap + len(x2)); y[:len(x1)] += x1; y[gap:gap + len(x2)] += x2
    name = "jump" if i == 0 else f"jump_{i + 1}"
    write(name, y * (10 ** (-1 / 20) / max(1e-9, np.abs(y).max())))
    print(f"{name}.wav <- Kenney {a} + {b} (90 ms apart)")

for old in [f for f in os.listdir(OUT) if f.startswith("wall") and f.endswith(".wav")]:
    os.remove(os.path.join(OUT, old))
for i, (a, b) in enumerate(WALL):
    x1 = process(decode(os.path.join(SRC, a)), 0.25) * 0.75; x2 = process(decode(os.path.join(SRC, b)), 0.15) * 0.6
    y = np.zeros(max(len(x1), len(x2))); y[:len(x1)] += x1; y[:len(x2)] += x2
    name = "wall" if i == 0 else f"wall_{i + 1}"
    write(name, y * (10 ** (-1 / 20) / max(1e-9, np.abs(y).max())))
    print(f"{name}.wav <- Kenney {a} + {b} (layered)")
