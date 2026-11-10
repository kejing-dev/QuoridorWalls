"""Builds the visible models of the Quoridor volume as GLB, in Python + Pillow + numpy (no Blender on this machine; zero Tripo,
design referenceRepo.assets: "棋盘、墙、子 全部程序化"). PBR materials with procedural textures (profile B12 reflection: the
board and the pieces are the hero assets and need real materials), all OPAQUE (D24).

  python3 tools/build_models.py [--test-f]      # --test-f: an "F↑" glyph on every cell and rail slot (D10 orientation check)

Writes app/src/main/assets/models/:
  board.glb     0.49 x 0.025 x 0.53 m lacquered board, origin = the near-bottom edge centre (board-local space of Layout.kt:
                +Y the board normal, -Z toward the far edge). Teal lacquer #1F4F4A cells 4.2 cm raised 1.5 mm, grooves 2 mm
                below the frame top, walnut #4A3222 frame (2 cm sides, 4 cm rails with 5 slot marks per layer), brass #C9A24A
                3 mm inlays outside rows 1 and 9.
  wall.glb      9.6 x 2.4 x 0.6 cm maple #E3C892 with 1 mm chamfers and grain, origin at its centre, long axis X, height Y.
  pawn_you.glb  turned pawn dia 3.4 x 4.5 cm, ivory #F2E8D5 + brass foot ring, origin at the bottom centre.
  pawn_ai.glb   same shape, cinnabar orange #FF8C42 body + ivory crown (top third) + brass foot ring.
"""
import io
import numpy as np
import json
import math
import os
import random
import struct
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")
TEST_F = "--test-f" in sys.argv

LACQUER = (0x1F, 0x4F, 0x4A)
WALNUT = (0x4A, 0x32, 0x22)
MAPLE = (0xE3, 0xC8, 0x92)
IVORY = (0xF2, 0xE8, 0xD5)
CINNABAR = (0xFF, 0x8C, 0x42)
BRASS = (0xC9, 0xA2, 0x4A)

# domain/Layout.kt
BW, BD, BT = 0.49, 0.53, 0.025
PITCH, CELL, RAIL, SIDE = 0.05, 0.042, 0.04, 0.02
CELL_TOP = BT + 0.0015
GROOVE = BT - 0.002
WALL_L, WALL_T, WALL_H = 0.096, 0.006, 0.012
PAWN_R, PAWN_H = 0.016, 0.024
TILT = math.radians(34)
CONTENT_X = -0.05

# skins (design v0.1.7 skins): cells, grooves, frame, baseline inlay
SKINS = {
    "modern": {"cells": (0xF0, 0xF4, 0xF1), "grooves": (0xC8, 0xD2, 0xCC), "frame": (0xE2, 0xD3, 0xB8), "line": (0xF7, 0xF2, 0xE8), "cellRough": 0.72, "frameRough": 0.6, "lineMetal": 0.0, "grain": 0.06},
    "classic": {"cells": (0x15, 0x2E, 0x29), "grooves": (0x0E, 0x20, 0x1C), "frame": (0x4A, 0x32, 0x22), "line": BRASS, "cellRough": 0.35, "frameRough": 0.62, "lineMetal": 0.85, "grain": 0.16},
}
P = SKINS["modern"]


def srgb_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.views.append(v)
        return len(self.views) - 1

    def floats(self, items, n, minmax=False):
        flat = [c for it in items for c in it]
        view = self._view(struct.pack("<%df" % len(flat), *flat), 34962)
        acc = {"bufferView": view, "componentType": 5126, "count": len(items), "type": {2: "VEC2", 3: "VEC3"}[n]}
        if minmax:
            acc["min"] = [min(it[k] for it in items) for k in range(n)]
            acc["max"] = [max(it[k] for it in items) for k in range(n)]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def indices(self, idx):
        view = self._view(struct.pack("<%dI" % len(idx), *idx), 34963)
        self.accessors.append({"bufferView": view, "componentType": 5125, "count": len(idx), "type": "SCALAR"})
        return len(self.accessors) - 1

    def image(self, im):
        b = io.BytesIO()
        im.convert("RGB").save(b, "PNG", optimize=True)
        return self._view(b.getvalue())

    def write(self, path, gltf):
        gltf["asset"] = {"version": "2.0", "generator": "quoridor-walls tools/build_models.py"}
        gltf["buffers"] = [{"byteLength": len(self.bin)}]
        gltf["bufferViews"] = self.views
        gltf["accessors"] = self.accessors
        js = json.dumps(gltf, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        while len(self.bin) % 4:
            self.bin.append(0)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin))


class Mesh:
    """Positions / normals / uvs / indices, grouped in primitives by material index."""

    def __init__(self):
        self.pos, self.nor, self.uv = [], [], []
        self.prims = {}

    def tri(self, mat, a, b, c, n=None, uvs=((0, 0), (0, 0), (0, 0))):
        if n is None:
            u = [b[k] - a[k] for k in range(3)]; v = [c[k] - a[k] for k in range(3)]
            n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            l = math.sqrt(sum(x * x for x in n)) or 1.0
            n = tuple(x / l for x in n)
        base = len(self.pos)
        for p, t in zip((a, b, c), uvs):
            self.pos.append(tuple(p)); self.nor.append(tuple(n)); self.uv.append(tuple(t))
        self.prims.setdefault(mat, []).extend([base, base + 1, base + 2])

    def quad(self, mat, a, b, c, d, n=None, uvs=((0, 0), (1, 0), (1, 1), (0, 1))):
        """Counter-clockwise a-b-c-d seen from the front (normal side)."""
        self.tri(mat, a, b, c, n, (uvs[0], uvs[1], uvs[2]))
        self.tri(mat, a, c, d, n, (uvs[0], uvs[2], uvs[3]))

    def smooth(self, mat, verts, norms, uvs, idx):
        base = len(self.pos)
        self.pos += [tuple(v) for v in verts]; self.nor += [tuple(n) for n in norms]; self.uv += [tuple(t) for t in uvs]
        self.prims.setdefault(mat, []).extend(base + i for i in idx)

    def write(self, name, materials, images=()):
        g = Glb()
        pa = g.floats(self.pos, 3, minmax=True); na = g.floats(self.nor, 3); ta = g.floats(self.uv, 2)
        prims = [{"attributes": {"POSITION": pa, "NORMAL": na, "TEXCOORD_0": ta}, "indices": g.indices(idx), "material": m} for m, idx in sorted(self.prims.items())]
        gltf = {"scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": name, "mesh": 0}], "meshes": [{"name": name, "primitives": prims}], "materials": materials}
        if images:
            gltf["images"] = [{"bufferView": g.image(im), "mimeType": "image/png"} for im in images]
            gltf["textures"] = [{"source": i, "sampler": 0} for i in range(len(images))]
            gltf["samplers"] = [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}]
        path = os.path.join(OUT, name + ".glb")
        g.write(path, gltf)
        tris = sum(len(i) for i in self.prims.values()) // 3
        print("%-16s %6d triangles %8d bytes" % (name + ".glb", tris, os.path.getsize(path)))


def mat(name, color=None, rough=0.6, tex=None, metal=0.0):
    pbr = {"metallicFactor": metal, "roughnessFactor": rough}
    if tex is not None:
        pbr["baseColorTexture"] = {"index": tex}
    if color is not None:
        pbr["baseColorFactor"] = [srgb_lin(c) for c in color] + [1.0]
    return {"name": name, "pbrMetallicRoughness": pbr, "alphaMode": "OPAQUE", "doubleSided": False}


def box(m, mi, x0, x1, y0, y1, z0, z1, uvf=None, sides=True, bottom=True):
    """Axis-aligned box faces; uvf(x, z) gives top uvs; sides take the uv of their top edge (streaks of the edge colour)."""
    uv = uvf or (lambda x, z: (0.5, 0.5))
    m.quad(mi, (x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0), (0, 1, 0), (uv(x0, z1), uv(x1, z1), uv(x1, z0), uv(x0, z0)))
    if sides:
        m.quad(mi, (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (0, 0, 1), (uv(x0, z1), uv(x1, z1), uv(x1, z1), uv(x0, z1)))
        m.quad(mi, (x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (0, 0, -1), (uv(x1, z0), uv(x0, z0), uv(x0, z0), uv(x1, z0)))
        m.quad(mi, (x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (1, 0, 0), (uv(x1, z1), uv(x1, z0), uv(x1, z0), uv(x1, z1)))
        m.quad(mi, (x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (-1, 0, 0), (uv(x0, z0), uv(x0, z1), uv(x0, z1), uv(x0, z0)))
    if bottom:
        m.quad(mi, (x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), (0, -1, 0), (uv(x0, z0), uv(x1, z0), uv(x1, z1), uv(x0, z1)))




TEXW, TEXH = 1536, 1664   # board texture: x -0.245..+0.245 -> 0..TEXW, s 0..0.53 -> TEXH..0 (far edge at the top)


def tex_px(x, s):
    return (x + BW / 2) / BW * TEXW, (1 - s / BD) * TEXH


def tex_uv(x, s):
    return ((x + BW / 2) / BW, 1 - (1 - s / BD))


def grain(w, h, base, horizontal, seed, contrast=0.12, freq=0.06):
    """Wood grain: stretched noise rings along one axis."""
    rng = np.random.default_rng(seed)
    n = rng.random((h // 8 + 2, w // 64 + 2)) if horizontal else rng.random((h // 64 + 2, w // 8 + 2))
    small = Image.fromarray((n * 255).astype("uint8")).resize((w, h), Image.BICUBIC)
    a = np.asarray(small).astype(float) / 255
    yy, xx = np.mgrid[0:h, 0:w]
    coord = yy if horizontal else xx
    rings = 0.5 + 0.5 * np.sin(coord * freq + a * 9.0)
    fine = rng.random((h, w)) * 0.35
    k = (rings * 0.75 + fine * 0.25 - 0.5) * contrast * 2
    rgb = np.stack([np.clip(base[i] * (1 + k), 0, 255) for i in range(3)], axis=-1)
    return Image.fromarray(rgb.astype("uint8"))


def board_texture():
    FR = P["frame"]
    im = grain(TEXW, TEXH, FR, horizontal=True, seed=3, contrast=P["grain"])
    side = grain(TEXW, TEXH, FR, horizontal=False, seed=4, contrast=P["grain"])
    mask = Image.new("L", (TEXW, TEXH), 0)
    md = ImageDraw.Draw(mask)
    x0, _ = tex_px(-BW / 2 + SIDE, 0); x1, _ = tex_px(BW / 2 - SIDE, 0)
    md.rectangle((0, 0, x0, TEXH), fill=255); md.rectangle((x1, 0, TEXW, TEXH), fill=255)
    im.paste(side, (0, 0), mask)
    # lacquer field (grooves) with a soft mottle
    rng = np.random.default_rng(7)
    mott = Image.fromarray((rng.random((TEXH // 32, TEXW // 32)) * 255).astype("uint8")).resize((TEXW, TEXH), Image.BICUBIC)
    m = np.asarray(mott).astype(float) / 255
    GR, CE = P["grooves"], P["cells"]
    groove = np.stack([np.clip(GR[i] * (0.96 + 0.06 * m), 0, 255) for i in range(3)], axis=-1).astype("uint8")
    cellc = np.stack([np.clip(CE[i] * (0.97 + 0.05 * m), 0, 255) for i in range(3)], axis=-1).astype("uint8")
    gx0, gy1 = tex_px(-4.5 * PITCH, 0.04); gx1, gy0 = tex_px(4.5 * PITCH, 0.04 + 9 * PITCH)
    g = Image.fromarray(groove)
    im.paste(g.crop((int(gx0), int(gy0), int(gx1), int(gy1))), (int(gx0), int(gy0)))
    cimg = Image.fromarray(cellc)
    d = ImageDraw.Draw(im)
    for r in range(9):
        for c in range(9):
            cx = (c - 4) * PITCH; cs = 0.04 + (r + 0.5) * PITCH
            a = tex_px(cx - CELL / 2, cs + CELL / 2); b = tex_px(cx + CELL / 2, cs - CELL / 2)
            box_ = (int(a[0]), int(a[1]), int(b[0]), int(b[1]))
            im.paste(cimg.crop(box_), box_[:2])
            # a soft lit bevel: lighter far / left edge, darker near / right edge (lacquer catching the key light)
            d.line((box_[0], box_[1], box_[2], box_[1]), fill=tuple(min(255, int(v * 1.12)) for v in CE), width=3)
            d.line((box_[0], box_[1], box_[0], box_[3]), fill=tuple(min(255, int(v * 1.06)) for v in CE), width=2)
            d.line((box_[0], box_[3], box_[2], box_[3]), fill=tuple(int(v * 0.86) for v in CE), width=3)
            if TEST_F:
                draw_f(d, (box_[0] + box_[2]) / 2, (box_[1] + box_[3]) / 2, (box_[2] - box_[0]) * 0.6)
    # rail slot marks: 2 rows x 5 shallow slots per rail (a lying wall is 1.2 cm wide)
    for s in (0.012, 0.028, BD - 0.012, BD - 0.028):
        for i in range(5):
            x = (i - 2) * 0.10
            a = tex_px(x - WALL_L / 2 - 0.001, s + 0.0068); b = tex_px(x + WALL_L / 2 + 0.001, s - 0.0068)
            d.rounded_rectangle((a[0], a[1], b[0], b[1]), radius=4, fill=tuple(int(v * 0.8) for v in FR), outline=tuple(int(v * 0.6) for v in FR), width=2)
            if TEST_F:
                draw_f(d, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (b[1] - a[1]) * 0.8)
    # brass inlays outside rows 1 and 9
    for s0 in (0.034, 0.493):
        a = tex_px(-4.5 * PITCH, s0 + 0.003); b = tex_px(4.5 * PITCH, s0)
        d.rectangle((a[0], a[1], b[0], b[1]), fill=P["line"])
    return im.filter(ImageFilter.GaussianBlur(0.6))


def draw_f(d, cx, cy, size):
    """An "F" with an arrow, upright when the far edge is up (D10)."""
    w = size * 0.45; h = size
    d.line((cx - w / 2, cy + h / 2, cx - w / 2, cy - h / 2), fill=(255, 255, 255), width=4)
    d.line((cx - w / 2, cy - h / 2, cx + w / 2, cy - h / 2), fill=(255, 255, 255), width=4)
    d.line((cx - w / 2, cy, cx + w / 4, cy), fill=(255, 255, 255), width=4)
    d.polygon([(cx + w, cy - h / 2), (cx + w * 0.75, cy - h / 4), (cx + w * 1.25, cy - h / 4)], fill=(255, 220, 0))


def build_board(name="board"):
    m = Mesh()
    def uv(x, z):
        return ((x + BW / 2) / BW, (-z) / BD * -1 + 1 if False else 1 - (-z) / BD)
    LAC, WAL, BRA = 0, 1, 2
    # base slab: lacquer groove floor over the grid, walnut elsewhere (the frame boxes cover their part)
    gx = 4.5 * PITCH
    s0, s1 = 0.04, 0.04 + 9 * PITCH
    box(m, LAC, -gx, gx, 0.0, GROOVE, -s1, -s0, uv, sides=False, bottom=False)
    box(m, WAL, -BW / 2, BW / 2, 0.0, 0.0001, -BD, 0.0, uv, sides=False, bottom=True)
    # frame: sides and rails (walnut, top at BT)
    box(m, WAL, -BW / 2, -gx, 0.0, BT, -BD, 0.0, uv, bottom=False)
    box(m, WAL, gx, BW / 2, 0.0, BT, -BD, 0.0, uv, bottom=False)
    box(m, WAL, -gx, gx, 0.0, BT, -s0, 0.0, uv, bottom=False)
    box(m, WAL, -gx, gx, 0.0, BT, -BD, -s1, uv, bottom=False)
    # 81 raised cells
    for r in range(9):
        for c in range(9):
            cx = (c - 4) * PITCH; cs = 0.04 + (r + 0.5) * PITCH
            box(m, LAC, cx - CELL / 2, cx + CELL / 2, GROOVE, CELL_TOP, -cs - CELL / 2, -cs + CELL / 2, uv, bottom=False)
    # brass inlays, 0.3 mm proud
    for s in (0.034, 0.493):
        box(m, BRA, -gx, gx, BT, BT + 0.0003, -s - 0.003, -s, uv, bottom=False)
    tex = board_texture()
    materials = [
        {**mat("cells", None, rough=P["cellRough"], tex=0), "name": "cells"},
        {**mat("frame", None, rough=P["frameRough"], tex=0), "name": "frame"},
        mat("baseline", P["line"], rough=0.32 if P["lineMetal"] else 0.6, metal=P["lineMetal"]),
    ]
    m.write(name, materials, [tex])
    return tex


def chamfer_box(m, mi, a, b, d, c, uvf):
    """Box with half-sizes a (x), b (y), d (z) and chamfer c on every edge."""
    def v(sx, sy, sz, t):
        if t == 0: return (sx * a, sy * (b - c), sz * (d - c))
        if t == 1: return (sx * (a - c), sy * b, sz * (d - c))
        return (sx * (a - c), sy * (b - c), sz * d)
    def q(p0, p1, p2, p3):
        cen = [sum(p[k] for p in (p0, p1, p2, p3)) / 4 for k in range(3)]
        u = [p1[k] - p0[k] for k in range(3)]; w = [p2[k] - p0[k] for k in range(3)]
        n = (u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0])
        if sum(n[k] * cen[k] for k in range(3)) < 0:
            p0, p1, p2, p3 = p3, p2, p1, p0
        m.quad(mi, p0, p1, p2, p3, None, tuple(uvf(p) for p in (p0, p1, p2, p3)))
    def t(p0, p1, p2):
        cen = [sum(p[k] for p in (p0, p1, p2)) / 3 for k in range(3)]
        u = [p1[k] - p0[k] for k in range(3)]; w = [p2[k] - p0[k] for k in range(3)]
        n = (u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0])
        if sum(n[k] * cen[k] for k in range(3)) < 0:
            p1, p2 = p2, p1
        m.tri(mi, p0, p1, p2, None, tuple(uvf(p) for p in (p0, p1, p2)))
    S = (-1, 1)
    for sx in S:  # +-x faces
        q(v(sx, -1, -1, 0), v(sx, 1, -1, 0), v(sx, 1, 1, 0), v(sx, -1, 1, 0))
    for sy in S:
        q(v(-1, sy, -1, 1), v(1, sy, -1, 1), v(1, sy, 1, 1), v(-1, sy, 1, 1))
    for sz in S:
        q(v(-1, -1, sz, 2), v(1, -1, sz, 2), v(1, 1, sz, 2), v(-1, 1, sz, 2))
    for sx in S:
        for sy in S:  # x-y edges
            q(v(sx, sy, -1, 0), v(sx, sy, 1, 0), v(sx, sy, 1, 1), v(sx, sy, -1, 1))
        for sz in S:  # x-z edges
            q(v(sx, -1, sz, 0), v(sx, 1, sz, 0), v(sx, 1, sz, 2), v(sx, -1, sz, 2))
    for sy in S:
        for sz in S:  # y-z edges
            q(v(-1, sy, sz, 1), v(1, sy, sz, 1), v(1, sy, sz, 2), v(-1, sy, sz, 2))
    for sx in S:
        for sy in S:
            for sz in S:
                t(v(sx, sy, sz, 0), v(sx, sy, sz, 1), v(sx, sy, sz, 2))


def build_wall_modern():
    """Modern skin wall (design skins.modern): deep charcoal #33383D matte rounded bar, 9.6 x 1.2 x 0.6 cm."""
    m = Mesh()
    a, b, d = WALL_L / 2, WALL_H / 2, WALL_T / 2
    chamfer_box(m, 0, a, b, d, 0.001, lambda p: (0.5, 0.5))
    m.write("wall_modern", [mat("charcoal", (0x33, 0x38, 0x3D), rough=0.62)])


def build_wall_classic():
    """Classic skin wall: maple body with a walnut top edge (the look of Tripo wall-B; the flattened Tripo mesh lost its
    front-face UVs and rendered flat grey, so this is the design's fallback "程序化圆边盒 + 贴图")."""
    m = Mesh()
    a, b, d = WALL_L / 2, WALL_H / 2, WALL_T / 2
    # u along the length, v by height (top = walnut band); top face samples the walnut band too
    chamfer_box(m, 0, a, b, d, 0.001, lambda p: ((p[0] + a) / (2 * a), 0.02 if p[1] >= b - 0.0005 else 1 - (p[1] + b) / (2 * b)))
    # a pale maple (render check v0.1.1: the vertical face renders ~35 L* under the key light; #F6E7C4 keeps it bright)
    tex = grain(512, 128, (0xF6, 0xE7, 0xC4), horizontal=True, seed=11, contrast=0.07, freq=0.22)
    walnut = grain(512, 30, WALNUT, horizontal=True, seed=12, contrast=0.14, freq=0.3)
    tex.paste(walnut, (0, 0))
    m.write("wall_classic", [mat("maple", None, rough=0.5, tex=0)], [tex])


def build_stand(name, color, rough):
    """The book-stand under the tipped board (design coordinates.board): two 2 cm side plates in the y-z plane, 3 cm in
    from each side, filling the triangle under the board's bottom face. Scene (volume) coordinates, not tipped."""
    m = Mesh()
    near_z, floor = 0.27, -0.18
    far_z = near_z - 0.53 * math.cos(TILT) + 0.005
    far_y = floor + 0.53 * math.sin(TILT) - 0.006
    for xc in (CONTENT_X - 0.245 + 0.03, CONTENT_X + 0.245 - 0.03):
        for sx in (-0.01, 0.01):
            x = xc + sx
            tri = [(x, floor, near_z - 0.01), (x, floor, far_z), (x, far_y, far_z)]
            n = (1 if sx > 0 else -1, 0, 0)
            if sx > 0: m.tri(0, tri[0], tri[2], tri[1], n)
            else: m.tri(0, tri[0], tri[1], tri[2], n)
        x0, x1 = xc - 0.01, xc + 0.01
        # back edge (vertical), bottom edge, and the slanted top edge
        m.quad(0, (x0, floor, far_z), (x1, floor, far_z), (x1, far_y, far_z), (x0, far_y, far_z), (0, 0, -1))
        m.quad(0, (x0, floor, near_z - 0.01), (x1, floor, near_z - 0.01), (x1, floor, far_z), (x0, floor, far_z), (0, -1, 0))
        ny, nz = math.cos(TILT), math.sin(TILT)
        m.quad(0, (x0, floor, near_z - 0.01), (x0, far_y, far_z), (x1, far_y, far_z), (x1, floor, near_z - 0.01), (0, ny, nz))
    m.write(name, [mat("stand", color, rough=rough)])


def build_wall():
    m = Mesh()
    a, b, d = WALL_L / 2, WALL_H / 2, WALL_T / 2
    # grain runs along the length: u = x, v = y + z (both broad faces get the grain)
    chamfer_box(m, 0, a, b, d, 0.001, lambda p: ((p[0] + a) / (2 * a), 0.5 + (p[1] + p[2]) / (4 * b)))
    tex = grain(512, 128, MAPLE, horizontal=True, seed=11, contrast=0.10, freq=0.22)
    if TEST_F:
        draw_f(ImageDraw.Draw(tex), 256, 64, 90)
    m.write("wall", [mat("maple", None, rough=0.7, tex=0)], [tex])


def lathe(m, profile, segs, mat_of):
    """Revolve profile [(r, y), ...] (bottom to top) around Y with smooth normals; mat_of(i) picks the material of band i."""
    for i in range(len(profile) - 1):
        (r0, y0), (r1, y1) = profile[i], profile[i + 1]
        dr, dy = r1 - r0, y1 - y0
        l = math.hypot(dr, dy) or 1
        nr, ny = dy / l, -dr / l
        verts, norms, uvs, idx = [], [], [], []
        for k in range(segs + 1):
            a = 2 * math.pi * k / segs
            ca, sa = math.cos(a), math.sin(a)
            verts += [(r0 * ca, y0, r0 * sa), (r1 * ca, y1, r1 * sa)]
            norms += [(nr * ca, ny, nr * sa)] * 2
            uvs += [(k / segs, 0), (k / segs, 1)]
        for k in range(segs):
            p = 2 * k
            idx += [p, p + 1, p + 2, p + 1, p + 3, p + 2]
        m.smooth(mat_of(i), verts, norms, uvs, idx)


def pawn_profile():
    H, R = PAWN_H, PAWN_R
    pts = [(0.0, 0.0), (R, 0.0), (R, 0.0025), (R * 0.97, 0.0035), (R * 0.88, 0.006), (R * 0.80, 0.014), (R * 0.74, 0.022),
           (R * 0.70, 0.028), (R * 0.78, 0.031)]
    # dome: quarter circle from (0.78R, 0.031) to the top
    top = 0.031
    rr = R * 0.78
    for k in range(1, 6):
        a = k / 5 * math.pi / 2
        pts.append((rr * math.cos(a), top + (H - top) * math.sin(a)))
    pts[-1] = (0.0, H)
    return pts


def build_pawn(name, body, crown):
    m = Mesh()
    prof = pawn_profile()
    # bands: 0..1 brass foot (bottom disc + foot ring), the rest body; the AI pawn's dome (y >= 0.031) is ivory
    def mat_of(i):
        y = prof[i][1]
        if i <= 1: return 0
        return 2 if y >= 0.0305 else 1
    lathe(m, prof, 20, mat_of)
    m.write(name, [mat("brass", BRASS, rough=0.3, metal=0.85), mat("body", body, rough=0.35), mat("crown", crown, rough=0.33)])


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for old in ("board.glb", "wall.glb", "pawn_you.glb", "pawn_ai.glb"):
        p = os.path.join(OUT, old)
        if os.path.exists(p): os.remove(p)
    for skin in ("modern", "classic"):
        P = SKINS[skin]
        build_board("board_" + skin)
    build_wall_modern()
    build_wall_classic()
    build_stand("stand_modern", (0xE2, 0xD3, 0xB8), 0.6)
    build_stand("stand_classic", (0x4A, 0x32, 0x22), 0.62)
