"""Largest continuous blank band of the control window in a screenshot (QA q1-3: <= 25 % of the window height).

  python3 tools/blank.py SHOT.png [...]      prints window box, blank band (px and % of window height) per file

The window is found as the big light, low-saturation rounded rectangle; a row is blank when its luminance spread
across the inner 90 % of the window width is tiny (no text, no control edge)."""
import sys
from PIL import Image
import numpy as np


def analyse(path):
    """Frontal shot: window width from the glass columns, height = width x 620 / 560, bottom = where the left padding
    strip turns dark (floor / bed below the window)."""
    im = np.asarray(Image.open(path).convert("RGB")).astype(float)
    lum = 0.2126 * im[..., 0] + 0.7152 * im[..., 1] + 0.0722 * im[..., 2]
    sat = im.max(axis=2) - im.min(axis=2)
    glass = (lum > 170) & (sat < 22)
    cols = glass.mean(axis=0)
    xs = np.where(cols > 0.3)[0]
    if len(xs) == 0:
        return None
    best = (xs[0], xs[0]); start = prev = xs[0]
    for v in xs[1:]:
        if v != prev + 1:
            if prev - start > best[1] - best[0]: best = (start, prev)
            start = v
        prev = v
    if prev - start > best[1] - best[0]: best = (start, prev)
    x0, x1 = int(best[0]), int(best[1])
    # window rows = the longest run where the glass padding strip just inside the left edge is light (the TabBar is
    # narrower than the window, so its rows show wall there)
    strip = np.median(lum[:, x0 + 8:x0 + 24], axis=1)
    on = strip > 185
    best = (0, 0); start = None
    for i, v in enumerate(list(on) + [False]):
        if v and start is None: start = i
        if not v and start is not None:
            if i - start > best[1] - best[0]: best = (start, i)
            start = None
    y0, y1 = best
    return fixed(path, (x0, y0, x1, y1))


def fixed(path, box):
    """Unified measure (prompts/qa.md 2026-11-05): blank = (content top - window top) + (window bottom - content bottom),
    divided by the window height; content = first..last row that holds any control / text edge. Also returns the largest
    blank band inside the content block."""
    x0, y0, x1, y1 = box
    im = np.asarray(Image.open(path).convert("RGB")).astype(float)
    lum = 0.2126 * im[..., 0] + 0.7152 * im[..., 1] + 0.0722 * im[..., 2]
    h = y1 - y0; w = x1 - x0
    m = int(w * 0.09)  # stay clear of the 40 dp rounded corners
    inner = lum[y0:y1, x0 + m:x1 - m]
    edge = np.abs(np.diff(inner, axis=1)).max(axis=1)  # strongest horizontal step in the row
    rows = np.where(edge > 18)[0]
    rows = rows[(rows > 20) & (rows < h - 20)]  # skip the outline
    if len(rows) == 0:
        return box, h, 1.0, 1.0
    top, bot = rows[0], rows[-1]
    gaps = np.diff(rows)
    internal = (gaps.max() - 1) / h if len(gaps) else 0.0
    return box, top + (h - bot), (top + (h - bot)) / h, internal


BOX = None
args = sys.argv[1:]
if args and args[0] == "--box":
    BOX = tuple(int(v) for v in args[1].split(",")); args = args[2:]
for p in args:
    r = fixed(p, BOX) if BOX else analyse(p)
    if r is None:
        print(p, "no window found"); continue
    box, px, frac, internal = r
    print("%s  window=%s  outside=%dpx  %.0f%%  inside-max=%.0f%%" % (p.split('/')[-1], box, px, frac * 100, internal * 100))
