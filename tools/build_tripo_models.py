"""Skin models from the designer's decimated Tripo GLBs (design assetsTripo.lod / assetsTripoModern.lod), run with the factory
venv (trimesh):  ~/.factory-venv/bin/python tools/build_tripo_models.py [~/Downloads/quoridor-walls-tripo/lod]

  pawn_modern_you / pawn_modern_ai   modern-pawn-B-3000 -> dia 3.2 x 2.4 cm, welded + smooth normals, solid matte coral #FF6B57 / navy #1E3A68
  pawn_classic_you                   pawn-A-ivory-3000  -> dia 3.2 x 2.4 cm with its ivory + brass texture
  pawn_classic_ai                    same mesh, solid deep cinnabar lacquer #5A1712 (no texture: ivory x red renders brown)
  (wall_classic is procedural in tools/build_models.py: the pressed-flat Tripo wall-B rendered a flat grey face)
Origins: pawns at the bottom centre, wall at its centre. All OPAQUE.
"""
import os, sys
import numpy as np
import trimesh
from trimesh.visual.material import PBRMaterial

SRC = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Downloads/quoridor-walls-tripo/lod")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "models")


def load(name):
    s = trimesh.load(os.path.join(SRC, name))
    return list(s.geometry.values())[0]


def fit_pawn(g, d=0.032, h=0.024):
    g = g.copy()
    lo, hi = g.bounds
    c = (lo + hi) / 2
    v = g.vertices - [c[0], lo[1], c[2]]
    v *= [d / (hi[0] - lo[0]), h / (hi[1] - lo[1]), d / (hi[2] - lo[2])]
    g.vertices = v
    return g


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def solid(g, rgb, rough):
    g = g.copy()
    # glTF baseColorFactor is linear: convert the sRGB design colour, or it renders washed out (v0.1.1 first try)
    g.visual = trimesh.visual.TextureVisuals(material=PBRMaterial(baseColorFactor=[lin(c) for c in rgb] + [1.0], roughnessFactor=rough, metallicFactor=0.0))
    g.merge_vertices(merge_tex=True, merge_norm=True)
    g.fix_normals()
    return g


def brighten(g, gain, gamma=1.0):
    """Lift a texture so the rendered part keeps its design contrast against the board (skins.check: measured on pixels)."""
    from PIL import Image
    m = g.visual.material
    im = m.baseColorTexture.convert("RGB")
    a = np.asarray(im).astype(float) / 255.0
    a = np.clip((a ** gamma) * gain, 0, 1)
    m.baseColorTexture = Image.fromarray((a * 255).astype("uint8"))
    return g


def export(g, name):
    if hasattr(g.visual, "material"):
        m = g.visual.material
        if isinstance(m, PBRMaterial):
            m.alphaMode = "OPAQUE"; m.doubleSided = False
    p = os.path.join(OUT, name + ".glb")
    trimesh.Scene(g).export(p)
    lo, hi = g.bounds
    print("%-18s %5d tris  %7d bytes  size %s" % (name + ".glb", len(g.faces), os.path.getsize(p), np.round(hi - lo, 4).tolist()))


def wall_classic():
    g = load("wall-B-maple-walnut-6000.glb").copy()
    # Tripo wall stands along z (length) with height y: turn z -> x
    rot = trimesh.transformations.rotation_matrix(np.pi / 2, [0, 1, 0])
    g.apply_transform(rot)
    lo, hi = g.bounds; c = (lo + hi) / 2
    v = g.vertices - c
    v *= [0.096 / (hi[0] - lo[0]), 0.012 / (hi[1] - lo[1]), 1.0]
    # press the thickness flat: front / back plates at +-3 mm (a straight board)
    v[:, 2] = np.where(v[:, 2] >= 0, 0.003, -0.003)
    g.vertices = v
    # box-like normals: faces keep their own flat normal
    g = trimesh.Trimesh(vertices=g.vertices[g.faces].reshape(-1, 3), faces=np.arange(len(g.faces) * 3).reshape(-1, 3),
                        visual=trimesh.visual.TextureVisuals(uv=g.visual.uv[g.faces].reshape(-1, 2), material=g.visual.material), process=False)
    return g


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    mp = fit_pawn(load("modern-pawn-B-3000.glb"))
    export(solid(mp, (0xFF, 0x6B, 0x57), 0.55), "pawn_modern_you")
    export(solid(mp, (0x1E, 0x3A, 0x68), 0.55), "pawn_modern_ai")
    cp = fit_pawn(load("pawn-A-ivory-3000.glb"))
    # v0.1.1 render check: the raw ivory texture rendered at L* 64 against jade cells 35 -> lifted
    export(brighten(cp.copy(), 1.25, 0.8), "pawn_classic_you")
    export(solid(cp, (0x5A, 0x17, 0x12), 0.35), "pawn_classic_ai")
    # wall_classic: tools/build_models.py (the flattened Tripo wall-B lost its front-face UVs and rendered flat grey)
