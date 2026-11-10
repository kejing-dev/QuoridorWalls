"""Loudness evidence (B20 / ui_gates loudness) for an app with short feedback sounds only (2026-11-05 rule):
renders the sounds of a typical game at the default volume (gain 1.0, the SoundPlayer top step) in the app's order
(select, place, grab, wall, band, jump, illegal, bounce, win), with the app's +-5 % rate
variation, keeps only the voiced parts (|x| above -40 dBFS, gaps trimmed to 40 ms), and prints RMS / peak of that
voiced track. Rendered, not recorded: kj-mbp-m5 has no audio loopback from the emulator.

  python3 tools/loudness.py OUT.wav
"""
import math, os, random, struct, sys, wave

SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")


def load(name):
    with wave.open(os.path.join(SND, name + ".wav")) as w:
        sr = w.getframerate()
        x = [v / 32768 for v in struct.unpack("<%dh" % w.getnframes(), w.readframes(w.getnframes()))]
    # v0.1.4: the place / jump recordings are 44.1 kHz (five-stones reference); bring them to the 22.05 kHz track
    if sr == 44100:
        x = [(x[i] + x[i + 1]) / 2 for i in range(0, len(x) - 1, 2)]; sr = 22050
    return sr, x


def rate(x, r):
    return [x[min(len(x) - 1, int(i * r))] for i in range(int(len(x) / r))]


def voiced(x, sr, thr=10 ** (-40 / 20), gap=0.04):
    out, quiet = [], 0
    for v in x:
        if abs(v) < thr:
            quiet += 1
            if quiet > gap * sr:
                continue
        else:
            quiet = 0
        out.append(v)
    return out


def main(out):
    rnd = random.Random(5)
    seq = []
    # v0.1.4: taking a wall is silent (a tap selection, D51 补), so no "grab" in the track
    turn = ["place", "place", "wall", "band", "place"]
    order = turn * 4 + ["jump"] + turn + ["illegal", "bounce"] + turn * 2 + ["place", "win"]
    sr = 22050
    for name in order:
        variants = [n for n in (name, name + "_2", name + "_3") if os.path.exists(os.path.join(SND, n + ".wav"))]
        sr, x = load(rnd.choice(variants))
        if name in ("place", "jump", "grab", "wall", "bounce"):
            x = rate(x, 0.95 + rnd.random() * 0.10)
        seq += voiced(x, sr)
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in seq))
    rms = math.sqrt(sum(v * v for v in seq) / len(seq))
    pk = max(abs(v) for v in seq)
    print("voiced %.1f s  RMS %.1f dBFS  peak %.1f dBFS  (rendered, default volume)" % (len(seq) / sr, 20 * math.log10(rms), 20 * math.log10(pk)))


main(sys.argv[1])
