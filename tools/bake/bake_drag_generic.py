#!/usr/bin/env python3
"""Bake per-direction aerodynamic tables (drag_<preset>.json) from a simple built-in box model of a quadcopter.

The geometry is the same cuboid drone the Minecraft adapter draws (DroneRenderer.drawModel: flat body, battery pack on top, camera
and nose block in front, two crossing arm bars, four motor cans, three thin blades per propeller), scaled per preset by propeller
diameter. No external mesh or data file is read.

For each of 42 directions d (the vertices of a once-subdivided icosahedron, expressed in the body frame x = left, y = nose, z = up) the
drone is rendered orthographically along d with a ray caster (first hit per pixel) and

    area_cm2[i]  = silhouette pixel count * pixel area               projected frontal area when the drone moves along +d
    force_dir[i] = d - F,   F = normalise( sum over visible pixels of n * (n . d) )     (n = outward normal of the first surface hit)

so that -(F) is the Newtonian (impact-theory) pressure-force direction and force_dir is the correction of the pure-drag direction -d
that the flight model adds as "portance" (a tilted plate deflects the flow, so the force is not exactly anti-parallel to the velocity).

Usage:  python3 -I tools/bake/bake_drag_generic.py [--res 320] [preset ...]     (needs numpy)
Writes  data/presets/drag_<preset>.json
"""
import argparse, json, math, os
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'data', 'presets')

# per preset: propeller diameter [in], body scale factors (electronics grow slower than the airframe), battery pack (l, w, h) [cm] on top.
PRESETS = {
    '3micro':     dict(prop_in=3.0, body=0.80, battery=(4.5, 2.2, 1.5), cam=0.8),
    '5race':      dict(prop_in=5.1, body=1.00, battery=(7.0, 3.6, 2.4), cam=1.0),
    '5freestyle': dict(prop_in=5.1, body=1.05, battery=(10.0, 4.2, 2.6), cam=1.1),
    '7longrange': dict(prop_in=7.0, body=1.30, battery=(14.0, 6.0, 4.5), cam=1.2),
}
BASE_PROP_R = 6.5   # cm, radius of the renderer's 5.1 in propeller


class Box:
    """Oriented box: centre c, half extents h (local axes), rotation about body z by yaw [rad]."""
    def __init__(self, c, h, yaw=0.0):
        self.c = np.array(c, float); self.h = np.array(h, float)
        cy, sy = math.cos(yaw), math.sin(yaw)
        self.R = np.array([[cy, -sy, 0], [sy, cy, 0], [0, 0, 1]])   # local -> body


def build(prop_in, body, battery, cam):
    s = (prop_in * 2.54) / (2 * BASE_PROP_R)   # scale relative to the renderer's 13 cm propeller diameter
    boxes = []
    b = body
    boxes.append(Box((0, 0, 0), (3.2 * b, 5.5 * b, 1.8 * b)))                                    # flight stack / body
    bl, bw, bh = battery
    boxes.append(Box((0, -0.5 * b, 1.8 * b + bh / 2), (bw / 2, bl / 2, bh / 2)))                 # battery pack on top
    boxes.append(Box((0, 6.5 * b, 0.2 * cam), (1.2 * cam, 1.0 * cam, 1.0 * cam)))                # camera
    boxes.append(Box((0, 8.2 * b, 0), (0.4, 0.8, 0.4)))                                          # nose marker
    m = 7.0 * s * 1.0                                                                            # motor offset along each axis
    arm_half = 9.5 * s
    arm_t = 0.6 * max(s, 0.8)
    for yaw in (math.radians(45), math.radians(-45)):
        boxes.append(Box((0, 0, 0), (arm_half, arm_t, arm_t), yaw))                              # crossing arm bars
    prop_r = BASE_PROP_R * s
    blade_w = 0.55 * (0.6 + 0.4 * s)
    for sx in (1, -1):
        for sy in (1, -1):
            mx, my = m * sx, m * sy
            boxes.append(Box((mx, my, 1.6), (1.4 * max(s, 0.7), 1.4 * max(s, 0.7), 1.0)))        # motor can
            for k in range(3):                                                                   # three blades, 120 deg apart
                th = math.radians(20 + 120 * k + (0 if sx * sy > 0 else 60))
                boxes.append(Box((mx + prop_r / 2 * math.cos(th), my + prop_r / 2 * math.sin(th), 2.85),
                                 (prop_r / 2, blade_w, 0.12), th))
    return boxes


def icosphere42():
    t = (1 + 5 ** 0.5) / 2
    v = [(-1, t, 0), (1, t, 0), (-1, -t, 0), (1, -t, 0), (0, -1, t), (0, 1, t), (0, -1, -t), (0, 1, -t), (t, 0, -1), (t, 0, 1), (-t, 0, -1), (-t, 0, 1)]
    v = [np.array(x, float) / np.linalg.norm(x) for x in v]
    f = [(0, 11, 5), (0, 5, 1), (0, 1, 7), (0, 7, 10), (0, 10, 11), (1, 5, 9), (5, 11, 4), (11, 10, 2), (10, 7, 6), (7, 1, 8), (3, 9, 4), (3, 4, 2),
         (3, 2, 6), (3, 6, 8), (3, 8, 9), (4, 9, 5), (2, 4, 11), (6, 2, 10), (8, 6, 7), (9, 8, 1)]
    seen = {}
    out = list(v)
    for a, b, c in f:
        for i, j in ((a, b), (b, c), (c, a)):
            k = (min(i, j), max(i, j))
            if k not in seen:
                m = v[i] + v[j]; seen[k] = len(out); out.append(m / np.linalg.norm(m))
    return np.array(out)


def render(boxes, d, res, half_width):
    d = d / np.linalg.norm(d)
    a = np.array([0.0, 0.0, 1.0]) if abs(d[2]) < 0.9 else np.array([1.0, 0.0, 0.0])
    e1 = np.cross(d, a); e1 /= np.linalg.norm(e1); e2 = np.cross(d, e1)
    g = (np.arange(res) + 0.5) / res * 2 - 1
    U, V = np.meshgrid(g * half_width, g * half_width)
    O = (-100.0 * d)[None, None, :] + U[..., None] * e1 + V[..., None] * e2   # ray origins (res,res,3), rays travel along +d
    O = O.reshape(-1, 3)
    best_t = np.full(len(O), np.inf); best_n = np.zeros((len(O), 3))
    for bx in boxes:
        lo = (O - bx.c) @ bx.R            # origin in the box frame
        ld = d @ bx.R                     # direction in the box frame
        with np.errstate(divide='ignore', invalid='ignore'):
            t1 = (-bx.h - lo) / ld; t2 = (bx.h - lo) / ld
        t1 = np.where(np.abs(ld) < 1e-12, np.where(np.abs(lo) <= bx.h, -np.inf, np.inf), t1)
        t2 = np.where(np.abs(ld) < 1e-12, np.inf, t2)
        tn = np.minimum(t1, t2); tf = np.maximum(t1, t2)
        tnear = tn.max(axis=1); tfar = tf.min(axis=1)
        hit = (tnear <= tfar) & (tfar > 0) & (tnear < best_t)
        if not hit.any():
            continue
        ax = tn.argmax(axis=1)
        nl = np.zeros((len(O), 3)); nl[np.arange(len(O)), ax] = -np.sign(ld[ax])   # entry face normal (local)
        nw = nl @ bx.R.T
        best_t = np.where(hit, tnear, best_t)
        best_n[hit] = nw[hit]
    vis = np.isfinite(best_t)
    pix = (2 * half_width / res) ** 2
    area = vis.sum() * pix
    nd = best_n[vis] @ d
    front = nd < 0
    S = (best_n[vis][front] * nd[front][:, None]).sum(axis=0)
    F = S / np.linalg.norm(S) if np.linalg.norm(S) > 1e-12 else d
    return area, d - F


def bake(name, res):
    cfg = PRESETS[name]
    boxes = build(cfg['prop_in'], cfg['body'], cfg['battery'], cfg['cam'])
    ext = max(np.abs(bx.c).max() + bx.h.max() * 1.5 for bx in boxes)
    half_width = 1.05 * max(ext, np.sqrt(sum(np.abs(bx.c[:2]).max() ** 2 for bx in boxes[-1:])))
    dirs = icosphere42()
    areas, fdirs = [], []
    for d in dirs:
        a, f = render(boxes, d, res, half_width)
        areas.append(round(float(a), 3)); fdirs.append([round(float(x), 6) for x in f])
    out = {'preset': name, 'source': 'tools/bake/bake_drag_generic.py', 'geometry': 'box model, propeller %.1f in' % cfg['prop_in'],
           'resolution': res, 'ortho_width_cm': round(2 * half_width, 3), 'n': len(dirs),
           'dirs': [[round(float(x), 6) for x in d] for d in dirs], 'area_cm2': areas, 'force_dir': fdirs}
    path = os.path.join(OUT, 'drag_%s.json' % name)
    with open(path, 'w') as fh:
        json.dump(out, fh, separators=(',', ':')); fh.write('\n')
    print(name, 'min/max area %.1f / %.1f cm2' % (min(areas), max(areas)), '->', path)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--res', type=int, default=320)
    ap.add_argument('presets', nargs='*', default=list(PRESETS))
    a = ap.parse_args()
    for n in a.presets:
        bake(n, a.res)
