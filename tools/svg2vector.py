"""Converts single-colour 24 px SVG icons (PICO ICON 7.0 pack) to Android vector drawables.

  python3 tools/svg2vector.py <out_name> <svg_path> [...]
Every <path d=...> becomes a white-filled path (tinted by the Icon composable); fill-rule evenodd is kept.
"""
import re
import sys
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")


def convert(name, svg):
    text = open(svg, encoding="utf-8").read()
    vb = re.search(r'viewBox="([\d.\s-]+)"', text).group(1).split()
    w, h = vb[2], vb[3]
    paths = []
    for m in re.finditer(r'<path\b([^>]*)/?>', text):
        attrs = m.group(1)
        d = re.search(r'\bd="([^"]+)"', attrs).group(1)
        rule = re.search(r'fill-rule="(\w+)"', attrs)
        fr = ' android:fillType="evenOdd"' if rule and rule.group(1) == "evenodd" else ""
        paths.append(f'    <path android:fillColor="#FFFFFFFF"{fr} android:pathData="{d}" />')
    xml = (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {os.path.basename(svg)} from the PICO ICON 7.0 pack -->\n'
           f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
           f'android:viewportWidth="{w}" android:viewportHeight="{h}">\n' + "\n".join(paths) + "\n</vector>\n")
    open(os.path.join(OUT, name + ".xml"), "w", encoding="utf-8").write(xml)


if __name__ == "__main__":
    args = sys.argv[1:]
    for i in range(0, len(args), 2):
        convert(args[i], args[i + 1])
