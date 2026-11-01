"""Manifold check + isometric preview of binary STL files. usage: python render.py out.png a.stl [b.stl ...]"""
import struct, sys
import numpy as np
from PIL import Image, ImageDraw

def load(p):
    d = open(p, 'rb').read(); n = struct.unpack('<I', d[80:84])[0]
    return np.frombuffer(d, dtype=np.dtype([('n', '<f4', 3), ('v', '<f4', (3, 3)), ('a', '<u2')]), count=n, offset=84)['v'].astype(float)

def check(tris):
    edges = {}
    for t in tris:
        for a, b in ((0, 1), (1, 2), (2, 0)):
            k = (tuple(np.round(t[a], 4)), tuple(np.round(t[b], 4)))
            edges[k] = edges.get(k, 0) + 1
    bad = sum(1 for (a, b), n in edges.items() if n != 1 or edges.get((b, a), 0) != 1)
    return bad

out = sys.argv[1]; files = sys.argv[2:]
W, H = 1400, 700
img = Image.new('RGB', (W, H), (245, 245, 245)); dr = ImageDraw.Draw(img)
light = np.array([-0.4, -0.6, 0.7]); light /= np.linalg.norm(light)
# view: rotate about z by 35 deg, then tilt 30 deg
az, el = np.radians(35), np.radians(30)
Rz = np.array([[np.cos(az), -np.sin(az), 0], [np.sin(az), np.cos(az), 0], [0, 0, 1]])
Rx = np.array([[1, 0, 0], [0, np.cos(el), -np.sin(el)], [0, np.sin(el), np.cos(el)]])
R = Rx @ Rz
slot = W / len(files)
for i, f in enumerate(files):
    t = load(f); bad = check(t)
    print('%s: %d triangles, bad edges %d' % (f, len(t), bad))
    c = t.reshape(-1, 3).mean(0)
    p = (t - c) @ R.T
    depth = p[:, :, 1].mean(1)                     # y after rotation = towards the viewer? use z-depth: here view along +y
    order = np.argsort(depth)[::-1]
    scale = 9
    for j in order:
        tri = p[j]; a, b, cc = t[j]
        n = np.cross(b - a, cc - a); nn = np.linalg.norm(n)
        if nn == 0: continue
        n /= nn
        shade = 0.45 + 0.55 * max(0, np.dot(n, light))
        col = tuple(int(v * shade) for v in (120, 160, 220))
        pts = [(slot * i + slot / 2 + q[0] * scale, H / 2 - q[2] * scale) for q in tri]
        dr.polygon(pts, fill=col, outline=col)
    dr.text((slot * i + 20, 20), f.split('/')[-1], fill=(0, 0, 0))
img.save(out); print('saved', out)
