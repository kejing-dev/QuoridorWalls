"""Quoridor Walls icon (design-package appIcon): deep indigo #27335E disc, an ivory #F2E8D5 turned pawn in the upper
middle, a maple #E3C892 wall lying across in front of it (70 % wide) and a short gold #F2C14E path curling round the
wall's right end. Flat, three colours, no text.

  python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same polygons).
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
INDIGO = (0x27, 0x33, 0x5E)
IVORY = (0xF2, 0xE8, 0xD5)
MAPLE = (0xE3, 0xC8, 0x92)
GOLD = (0xF2, 0xC1, 0x4E)




def circle(cx, cy, r, n=48, a0=0.0, a1=2 * math.pi):
    return [(cx + r * math.cos(a0 + (a1 - a0) * i / n), cy + r * math.sin(a0 + (a1 - a0) * i / n)) for i in range(n + (0 if a1 - a0 >= 2 * math.pi - 1e-6 else 1))]


def rrect(x0, y0, x1, y1, r):
    pts = []
    for cx, cy, a in ((x1 - r, y0 + r, -math.pi / 2), (x1 - r, y1 - r, 0), (x0 + r, y1 - r, math.pi / 2), (x0 + r, y0 + r, math.pi)):
        pts += circle(cx, cy, r, 8, a, a + math.pi / 2)
    return pts


# the pawn: a domed head on a flared body and a foot (108 x 108 viewport)
PAWN_HEAD = circle(54, 30, 10.5)
PAWN_BODY = [(47.5, 38), (60.5, 38), (63.5, 52), (68, 64), (40, 64), (44.5, 52)]
PAWN_FOOT = rrect(35, 62, 73, 70, 3.5)
WALL = rrect(16.2, 73, 91.8, 84, 2.5)


def band():
    """A gold path from under the wall, round its right end and up beside the pawn (a thick quadratic curve)."""
    pts = []
    p0, p1, p2 = (40, 94), (104, 96), (88, 58)
    for i in range(25):
        u = i / 24
        pts.append(((1 - u) ** 2 * p0[0] + 2 * u * (1 - u) * p1[0] + u * u * p2[0], (1 - u) ** 2 * p0[1] + 2 * u * (1 - u) * p1[1] + u * u * p2[1]))
    left, right = [], []
    w = 2.6
    for i, (x, y) in enumerate(pts):
        a = pts[max(0, i - 1)]; b = pts[min(len(pts) - 1, i + 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        l = math.hypot(dx, dy) or 1
        nx, ny = -dy / l * w, dx / l * w
        left.append((x + nx, y + ny)); right.append((x - nx, y - ny))
    return left + list(reversed(right))


BAND = band()


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=INDIGO + (255,)))


def layer_pawn():
    def d(dr, k):
        for p in (PAWN_BODY, PAWN_FOOT, PAWN_HEAD):
            dr.polygon(sc(p, k), fill=IVORY + (255,))
    return big(d)


def layer_wall():
    def d(dr, k):
        dr.polygon(sc(BAND, k), fill=GOLD + (255,))
        dr.polygon(sc(WALL, k), fill=MAPLE + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Quoridor Walls launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(IVORY), path(PAWN_BODY) + path(PAWN_FOOT) + path(PAWN_HEAD)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(GOLD), path(BAND)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(MAPLE), path(WALL)),
        '</vector>',
    ]
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_pawn(), layer_wall()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
