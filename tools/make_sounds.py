"""Generates the app's short sound effects (16-bit mono WAV, 22.05 kHz) from code, no samples used.

  python tools/make_sounds.py            writes app/src/main/assets/sounds/*.wav
  python tools/make_sounds.py --check    prints peak / loudness / spectrum of every file

Quoridor (design sound.list): wood / lacquer / brass, soft-attack and low-passed, no electronic beeps and no
ambient bed (B24). Peaks -1 ... -3 dBFS with a gentle tanh drive so the short sounds carry in a headset (B20).
Building blocks from Kings Row tools/make_sounds.py.
"""
import math
import os
import random
import struct
import sys
import wave

import numpy as np

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'sounds')


# ---- building blocks ----

def silence(seconds):
    return np.zeros(int(RATE * seconds))


def t_axis(seconds):
    return np.arange(int(RATE * seconds)) / RATE


def biquad(x, kind, freq, q=0.707):
    """RBJ cookbook biquad, direct form I."""
    w = 2 * math.pi * freq / RATE
    cw, sw = math.cos(w), math.sin(w)
    alpha = sw / (2 * q)
    if kind == 'low':
        b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]
    elif kind == 'high':
        b = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]
    else:  # band, constant 0 dB peak
        b = [alpha, 0.0, -alpha]
    a = [1 + alpha, -2 * cw, 1 - alpha]
    b = [v / a[0] for v in b]
    a1, a2 = a[1] / a[0], a[2] / a[0]
    y = np.zeros_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        out = b[0] * v + b[1] * x1 + b[2] * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, out
        y[i] = out
    return y


def lowpass(x, freq, passes=2):
    for _ in range(passes):
        x = biquad(x, 'low', freq)
    return x


def attack_env(n, attack):
    """Raised-cosine attack of `attack` seconds, 1 afterwards: no step at the start."""
    e = np.ones(n)
    k = max(2, int(RATE * attack))
    e[:k] = 0.5 - 0.5 * np.cos(np.linspace(0, math.pi, k))
    return e


def fade_tail(x, seconds=0.006):
    k = min(len(x), int(RATE * seconds))
    x = x.copy()
    x[-k:] *= 0.5 + 0.5 * np.cos(np.linspace(0, math.pi, k))
    return x


def place(dst, src, at):
    i = int(RATE * at)
    if i + len(src) > len(dst):
        dst = np.concatenate([dst, np.zeros(i + len(src) - len(dst))])
    dst[i:i + len(src)] += src
    return dst


def mode(freq, decay, seconds, attack=0.0015, phase=0.0):
    """A damped sinusoid: one resonance of a struck body."""
    t = t_axis(seconds)
    # The tail is tapered to zero: a resonance cut off mid-decay clicks.
    return fade_tail(np.sin(2 * math.pi * freq * t + phase) * np.exp(-t / decay) * attack_env(len(t), attack), seconds * 0.4)


def knock(strength, brightness, rnd, seconds=0.12):
    """One contact of the die: a felt-damped thud plus a few short wood/resin modes."""
    d = 1 + rnd.uniform(-0.06, 0.06)
    # Headset speakers barely reproduce < 300 Hz, so the body of the knock sits at 600 Hz - 1.3 kHz.
    x = 0.45 * mode(260 * d, 0.02, seconds, attack=0.002)
    x += 0.8 * mode(640 * d, 0.028, seconds, phase=rnd.uniform(0, 6.28))
    x += 0.45 * brightness * mode(1260 * d, 0.014, seconds, phase=rnd.uniform(0, 6.28))
    x += 0.16 * brightness * mode(2150 * d, 0.006, seconds, phase=rnd.uniform(0, 6.28))
    burst = np.array([rnd.uniform(-1, 1) for _ in range(int(RATE * 0.008))])
    burst = biquad(burst, 'band', 1100, q=1.2) * attack_env(len(burst), 0.0015) * np.linspace(1, 0, len(burst))
    x = place(x, 0.35 * brightness * burst, 0)
    return strength * fade_tail(lowpass(x, 2600), seconds * 0.3)


def swish(seconds, f_from, f_to, rnd, attack=0.06):
    """Filtered air: a band of noise whose centre glides from f_from to f_to."""
    n = int(RATE * seconds)
    raw = np.array([rnd.uniform(-1, 1) for _ in range(n)])
    out = np.zeros(n)
    block = 256
    for s in range(0, n, block):
        u = s / n
        f = f_from * (f_to / f_from) ** u
        seg = biquad(raw[max(0, s - 512):s + block], 'band', f, q=1.4)
        out[s:s + block] = seg[-len(out[s:s + block]):]
    env = np.sin(np.linspace(0, math.pi, n)) ** 1.5 * attack_env(n, attack)
    # Overlapping the filter blocks keeps the band continuous; one more low-pass removes block edges.
    return lowpass(out * env, 3000, passes=1)


def note(freq, seconds, decay, partials=((1, 1.0), (2, 0.22), (3, 0.07)), attack=0.004):
    """A soft mallet note (marimba / bell-ish): a few harmonics with their own decays."""
    t = t_axis(seconds)
    x = np.zeros_like(t)
    for k, a in partials:
        x += a * np.sin(2 * math.pi * freq * k * t) * np.exp(-t * k / decay)
    return lowpass(fade_tail(x * attack_env(len(t), attack), seconds * 0.4), 3200, passes=1)


def write(name, x, peak_db, drive=2.2):
    x = biquad(np.asarray(x, dtype=float), 'high', 45)   # no DC, no rumble
    x = fade_tail(x)
    x = x / max(1e-9, np.abs(x).max())
    if drive > 0:
        x = np.tanh(drive * x) / math.tanh(drive)   # soft saturation: more body, same peak
    x = x * (10 ** (peak_db / 20) / max(1e-9, np.abs(x).max()))
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b''.join(struct.pack('<h', int(round(max(-1, min(1, v)) * 32767))) for v in x))


def check():
    print(f"{'file':16s} {'dur':>5s} {'peak':>6s} {'loud':>6s} {'cent':>6s} {'>3k%':>5s} {'>5k%':>5s} {'step':>6s}")
    for name in sorted(os.listdir(OUT)):
        with wave.open(os.path.join(OUT, name)) as w:
            x = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(float) / 32768
        win = int(RATE * 0.05)
        loud = max(np.sqrt(np.mean(x[i:i + win] ** 2)) for i in range(0, max(1, len(x) - win), win // 2))
        s = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2
        f = np.fft.rfftfreq(len(x), 1 / RATE)
        print(f"{name:16s} {len(x) / RATE:5.2f} {20 * np.log10(np.abs(x).max()):6.1f} {20 * np.log10(loud):6.1f} "
              f"{(s * f).sum() / s.sum():6.0f} {s[f > 3000].sum() / s.sum() * 100:5.1f} {s[f > 5000].sum() / s.sum() * 100:5.1f} "
              f"{np.abs(np.diff(x)).max():6.3f}")


if '--check' in sys.argv:
    check()
    sys.exit(0)


if '--check' in sys.argv:
    check()
    sys.exit(0)


# The physical sounds (select, place, jump, grab, wall, bounce) are CC0 recordings (tools/import_cc0_sounds.py, Kenney);
# this script only makes the UI cues below.

bell = ((1, 1.0), (2.0, 0.18), (3.0, 0.05), (4.2, 0.03))

# illegal: a short muted "噔"
write('illegal', lowpass(0.9 * mode(420, 0.025, 0.12) + 0.3 * mode(860, 0.012, 0.12), 1800, 1), -2)

# band: the path bands re-route, a soft airy swish (design: about -20 dBFS, quieter than the knocks)
write('band', lowpass(swish(0.28, 900, 2200, random.Random(4), attack=0.08), 2600, 1), -14, drive=0)

# win: four notes rising, a light brass-bell colour
x = silence(1.4)
for at, f, d in ((0.0, 523.25, 0.25), (0.14, 659.25, 0.25), (0.28, 783.99, 0.25), (0.42, 1046.5, 0.7)):
    x = place(x, note(f, d + 0.3, d, partials=bell, attack=0.006), at)
write('win', x, -1)

# lose: two notes falling, a low wood colour (the opponent's win is not a fanfare, D44)
x = silence(0.9)
for at, f, d in ((0.0, 392.0, 0.3), (0.2, 293.66, 0.5)):
    x = place(x, note(f, d + 0.3, d, partials=bell, attack=0.006), at)
write('lose', x, -2)

print('wrote', sorted(os.listdir(OUT)))
check()
