"""Minimal snap-fit case for an Adafruit Feather nRF52840 Express stacked over a 500 mAh LiPo (40x24x6).
Geometry is a union/difference of axis-aligned boxes, turned into a watertight STL on a non-uniform grid
(every cell face between solid and empty is emitted, so the mesh has no T-junctions).

Closure: the lid has an inner skirt (1.5 mm) along the two long sides and the antenna end that drops into the
pocket; a 0.4 mm ridge on the skirt snaps into a groove cut in the pocket wall. Nothing is thinner than the
skirt itself: walls 3.0 (2.6 at the groove), floor and lid plate 1.5, lip above the groove 1.5 tall x 3 thick.
usage: python slimcase.py outdir   -> outdir/slim-case-bottom.stl, outdir/slim-case-lid.stl"""
import struct, sys, os
import numpy as np

# ---- dimensions (mm) --------------------------------------------------------------------
PCB_L, PCB_W, PCB_T = 50.8, 22.86, 1.6     # Feather
BAT_L, BAT_W, BAT_T = 40.0, 24.0, 6.0      # LiPo 562438 (6.5 allowed for swelling)
FOAM = 1.0                                 # double-sided foam tape, two layers (floor-battery, battery-board)
TOP_PARTS = 6.5                            # JST PH header + plug is the tallest part on the board
GAP_USB = 2.5                              # room between the USB-end board edge and the wall for the LiPo wires
CLEAR = 0.6                                # board length clearance
WALL, FLOOR, LID = 3.0, 1.5, 1.5
SKIRT, SKIRT_H, FIT = 1.5, 3.0, 0.15       # lid skirt inside the pocket, along the long sides and the antenna end
RIDGE, RIDGE_H = 0.4, 1.5                  # snap ridge at the skirt tip (outer face) / groove in the pocket wall
LIP = 1.5                                  # material above the groove (wall top to groove top)
IN_L = PCB_L + CLEAR + GAP_USB             # 53.9
IN_W = PCB_W + 2 * (SKIRT + FIT) + 0.8     # 27.0: board centred with 0.4 mm beside each skirt
IN_H = FOAM + BAT_T + 0.5 + FOAM + PCB_T + TOP_PARTS + 0.4   # 17.0
WIN_W, WIN_Z0 = 20.0, 8.5                  # USB-end opening: width, bottom height above the floor (cable overmold)

OUT_L, OUT_W = IN_L + 2 * WALL, IN_W + 2 * WALL
BOX_H = FLOOR + IN_H

def box(x0, x1, y0, y1, z0, z1): return (x0, x1, y0, y1, z0, z1)

def mesh(ops):
    """ops: list of (sign, box). Later ops override earlier ones. Returns Nx3x3 triangles."""
    xs = sorted({v for _, b in ops for v in b[0:2]}); ys = sorted({v for _, b in ops for v in b[2:4]}); zs = sorted({v for _, b in ops for v in b[4:6]})
    xs, ys, zs = map(np.array, (xs, ys, zs))
    cx, cy, cz = (xs[:-1] + xs[1:]) / 2, (ys[:-1] + ys[1:]) / 2, (zs[:-1] + zs[1:]) / 2
    solid = np.zeros((len(cx), len(cy), len(cz)), bool)
    for sign, (x0, x1, y0, y1, z0, z1) in ops:
        m = np.ix_((cx > x0) & (cx < x1), (cy > y0) & (cy < y1), (cz > z0) & (cz < z1))
        solid[m] = sign > 0
    pad = np.zeros((len(cx) + 2, len(cy) + 2, len(cz) + 2), bool); pad[1:-1, 1:-1, 1:-1] = solid
    tris = []
    def quad(p0, p1, p2, p3): tris.append((p0, p1, p2)); tris.append((p0, p2, p3))
    for i in range(len(cx)):
        for j in range(len(cy)):
            for k in range(len(cz)):
                if not solid[i, j, k]: continue
                x0, x1, y0, y1, z0, z1 = xs[i], xs[i + 1], ys[j], ys[j + 1], zs[k], zs[k + 1]
                if not pad[i, j + 1, k + 1]:     quad((x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0))   # -x
                if not pad[i + 2, j + 1, k + 1]: quad((x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1))   # +x
                if not pad[i + 1, j, k + 1]:     quad((x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1))   # -y
                if not pad[i + 1, j + 2, k + 1]: quad((x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0))   # +y
                if not pad[i + 1, j + 1, k]:     quad((x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0))   # -z
                if not pad[i + 1, j + 1, k + 2]: quad((x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1))   # +z
    return np.array(tris, float)

def write_stl(path, tris):
    with open(path, 'wb') as f:
        f.write(b'slim feather case'.ljust(80, b'\0')); f.write(struct.pack('<I', len(tris)))
        for a, b, c in tris:
            n = np.cross(b - a, c - a); n = n / (np.linalg.norm(n) or 1)
            f.write(struct.pack('<3f', *n) + struct.pack('<3f', *a) + struct.pack('<3f', *b) + struct.pack('<3f', *c) + b'\0\0')

def volume(tris): return abs(sum(np.dot(a, np.cross(b, c)) for a, b, c in tris) / 6)

def bottom():
    ops = [(+1, box(0, OUT_L, 0, OUT_W, 0, BOX_H))]
    ops.append((-1, box(WALL, WALL + IN_L, WALL, WALL + IN_W, FLOOR, BOX_H + 1)))                       # pocket
    gz1 = BOX_H - LIP; gz0 = gz1 - RIDGE_H                                                             # groove in the pocket wall: long sides + antenna end (x = 0 side)
    ops += [(-1, box(WALL - RIDGE, WALL + IN_L, WALL - RIDGE, WALL + RIDGE, gz0, gz1)),
            (-1, box(WALL - RIDGE, WALL + IN_L, WALL + IN_W - RIDGE, WALL + IN_W + RIDGE, gz0, gz1)),
            (-1, box(WALL - RIDGE, WALL + RIDGE, WALL - RIDGE, WALL + IN_W + RIDGE, gz0, gz1))]
    wy0 = OUT_W / 2 - WIN_W / 2                                                                        # USB-end opening (x = OUT_L end)
    ops.append((-1, box(WALL + IN_L - 0.01, OUT_L + 1, wy0, wy0 + WIN_W, FLOOR + WIN_Z0, BOX_H + 1)))
    return mesh(ops)

def lid():
    """Built upside down for printing: plate on z=0, skirt going up. Same footprint as the bottom."""
    ops = [(+1, box(0, OUT_L, 0, OUT_W, 0, LID))]
    x0, x1 = WALL + FIT, WALL + IN_L - FIT                                                             # skirt inside the pocket
    y0, y1 = WALL + FIT, WALL + IN_W - FIT
    ops += [(+1, box(x0, x1, y0, y0 + SKIRT, LID, LID + SKIRT_H)), (+1, box(x0, x1, y1 - SKIRT, y1, LID, LID + SKIRT_H)),
            (+1, box(x0, x0 + SKIRT, y0, y1, LID, LID + SKIRT_H))]
    rz0 = LID + LIP; rz1 = rz0 + RIDGE_H                                                               # ridge on the skirt's outer face, matching the groove
    ops += [(+1, box(x0, x1, y0 - RIDGE, y0, rz0, rz1)), (+1, box(x0, x1, y1, y1 + RIDGE, rz0, rz1)),
            (+1, box(x0 - RIDGE, x0, y0 - RIDGE, y1 + RIDGE, rz0, rz1))]
    return mesh(ops)

if __name__ == '__main__':
    out = sys.argv[1]; os.makedirs(out, exist_ok=True)
    for name, fn in (('slim-case-bottom', bottom), ('slim-case-lid', lid)):
        t = fn(); write_stl(os.path.join(out, name + '.stl'), t)
        lo, hi = t.reshape(-1, 3).min(0), t.reshape(-1, 3).max(0)
        print('%-18s %4d triangles  %.1f x %.1f x %.1f mm  volume %.1f cm3' % (name, len(t), *(hi - lo), volume(t) / 1000))
    print('outer %.1f x %.1f x %.1f mm (box %.1f + lid plate %.1f), inner %.1f x %.1f x %.1f' % (OUT_L, OUT_W, BOX_H + LID, BOX_H, LID, IN_L, IN_W, IN_H))
