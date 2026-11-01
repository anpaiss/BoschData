"""Local wall thickness of a binary STL: from sample points on every triangle, a ray cast inward (against the
normal) measures the distance to the opposite surface. Reports the thinnest spots and every region under a
threshold. usage: python thickness.py file.stl [threshold_mm]"""
import struct, sys
import numpy as np

def load(p):
    d = open(p, 'rb').read(); n = struct.unpack('<I', d[80:84])[0]
    return np.frombuffer(d, dtype=np.dtype([('n', '<f4', 3), ('v', '<f4', (3, 3)), ('a', '<u2')]), count=n, offset=84)['v'].astype(float)

def ray_hits(o, d, tris):
    """Möller–Trumbore for one ray against all triangles; returns the smallest positive t (or inf)."""
    v0, v1, v2 = tris[:, 0], tris[:, 1], tris[:, 2]
    e1, e2 = v1 - v0, v2 - v0
    p = np.cross(d, e2); det = np.einsum('ij,ij->i', e1, p)
    ok = np.abs(det) > 1e-12
    inv = np.where(ok, 1 / np.where(ok, det, 1), 0)
    s = o - v0
    u = np.einsum('ij,ij->i', s, p) * inv
    q = np.cross(s, e1)
    v = np.einsum('j,ij->i', d, q) * inv
    t = np.einsum('ij,ij->i', e2, q) * inv
    good = ok & (u >= -1e-9) & (v >= -1e-9) & (u + v <= 1 + 1e-9) & (t > 1e-6)
    return t[good].min() if good.any() else np.inf

tris = load(sys.argv[1]); thr = float(sys.argv[2]) if len(sys.argv) > 2 else 1.5
results = []
for i, t in enumerate(tris):
    a, b, c = t
    n = np.cross(b - a, c - a); area = np.linalg.norm(n) / 2
    if area < 0.05: continue
    n /= 2 * area
    # sample the centroid plus points towards each corner (thin strips are caught by the corner samples)
    samples = [(a + b + c) / 3] + [(a + b + c) / 3 * 0.4 + p * 0.6 for p in (a, b, c)]
    for s in samples:
        d = ray_hits(s, -n, tris)
        results.append((d, s, n, i))
results.sort(key=lambda r: r[0])
print('%s: %d samples, thinnest %.2f mm' % (sys.argv[1], len(results), results[0][0]))
thin = [r for r in results if r[0] < thr]
print('samples under %.2f mm: %d' % (thr, len(thin)))
# group thin samples by (normal axis, thickness) and show the extent of each group
groups = {}
for d, s, n, i in thin:
    key = (tuple(np.round(n).astype(int)), round(d, 2))
    groups.setdefault(key, []).append(s)
for (n, d), pts in sorted(groups.items(), key=lambda g: g[0][1]):
    pts = np.array(pts); lo, hi = pts.min(0), pts.max(0)
    print('  %.2f mm along normal %s: %d samples, x %.1f..%.1f  y %.1f..%.1f  z %.1f..%.1f' % (d, n, len(pts), lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]))
