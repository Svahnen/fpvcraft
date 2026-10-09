#!/usr/bin/env python3
"""Generate data/presets/curves.json from simple closed-form / physical relations.

Every curve is a sampled piecewise-linear function {"keys": [{"t": x, "v": y}, ...]}; the library evaluates them with
linear interpolation and holds the end values outside the sampled range.

Usage: python3 tools/bake/gen_curves.py   (writes data/presets/curves.json)
"""
import json, math, os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'data', 'presets', 'curves.json')
SIZES = ['2', '2.5', '3', '3.5', '4', '5', '6', '7', '10', '15']   # propeller diameter classes [in]


def sample(f, xs):
    return {'keys': [{'t': round(x, 6), 'v': round(f(x), 6)} for x in xs]}


def linspace(a, b, n):
    return [a + (b - a) * i / (n - 1) for i in range(n)]


def rpm_fraction(size):
    """Fraction of the no-load speed (KV * volts) reached at full throttle: smaller motors lose more to electrical/aero losses."""
    s = float(size)
    g = 0.90 + 0.10 * min(max((s - 3.0) / 2.0, 0.0), 1.0)
    return g - 0.05 * min(max((s - 5.0) / 2.0, 0.0), 1.0)


def power_ref(size):
    """Relative electrical power index at full throttle (arbitrary units, 5 in class = 1450). Broken power law in prop diameter:
    ~D^2.9 below 5 in (current rises quickly with motor size), ~D^1.3 above (large props are run at lower KV and lower disc loading)."""
    s = float(size)
    return 1450.0 * (s / 5.0) ** (2.9 if s < 5 else 1.3)


def main():
    curves = {}
    # motor speed command (0..1) -> fraction of full-throttle speed. Mild convexity from the ESC/motor response.
    for s in SIZES:
        g = rpm_fraction(s)
        curves['motor/rpm_vs_throttle/' + s] = sample(lambda x, g=g: g * x ** 1.25, linspace(0, 1, 41))
        P = power_ref(s)
        curves['motor/power_vs_throttle/' + s] = sample(lambda x, P=P: P * x ** 1.8, linspace(0, 1, 41))
    # loaded speed / no-load speed vs prop tip Mach number: speed droop under aerodynamic load grows with tip speed
    curves['motor/load_droop_vs_tip_mach'] = sample(lambda m: 1.0 - 0.30 * m ** 0.75, linspace(0, 1, 41))
    # loaded speed vs prop pitch/diameter ratio: a coarser prop loads the motor more, so it runs slower (reference ratio 0.72)
    curves['motor/speed_vs_pitch_ratio'] = sample(lambda r: (0.72 / max(r, 0.3)) ** 0.33, linspace(0, 4, 41))
    # empirical static-thrust efficiency factor vs diameter (peaks for the 4-6 in class, falls for very large props)
    curves['prop/thrust_factor_vs_diameter'] = sample(lambda s: 1.45 + 0.52 * math.exp(-((s - 4.7) / 2.6) ** 2), [0, 2, 2.5, 3, 4, 5, 6, 7, 10, 15])
    # frame/prop drag coefficient scaling vs diameter (used by the table-free drag fallback)
    curves['drag/size_factor'] = sample(lambda s: 1.65 * (1.0 - math.exp(-(s / 5.2) ** 2.2)), [0, 2, 3, 4, 5, 6, 7, 10, 15])
    # current-draw constant vs diameter: K ~ 330 * D^-1.2
    curves['battery/current_k_vs_diameter'] = sample(lambda s: 330.0 * max(s, 1.5) ** -1.2, [0, 2, 3, 5, 7, 10, 13.2])
    # open-circuit cell voltage vs fraction of capacity used (typical LiPo / Li-ion discharge shapes)
    lipo = [(0, 4.20), (0.05, 4.12), (0.1, 4.07), (0.2, 3.98), (0.3, 3.92), (0.4, 3.88), (0.5, 3.84), (0.6, 3.80), (0.7, 3.77), (0.8, 3.73),
            (0.9, 3.65), (0.95, 3.50), (1.0, 3.20), (1.05, 2.80)]
    liion = [(0, 4.20), (0.1, 3.98), (0.25, 3.85), (0.5, 3.68), (0.7, 3.55), (0.85, 3.35), (0.95, 3.00), (1.05, 2.50)]
    curves['battery/lipo_ocv'] = {'keys': [{'t': a, 'v': b} for a, b in lipo]}
    curves['battery/liion_ocv'] = {'keys': [{'t': a, 'v': b} for a, b in liion]}
    # cosmetic prop-wash response vs throttle: zero at low throttle, smooth ramp to a plateau
    def ss(x): x = min(max(x, 0.0), 1.0); return x * x * (3 - 2 * x)
    curves['dynamics/propwash_vs_throttle'] = sample(lambda t: 0.6 * ss((t - 0.1) / 0.2), linspace(0, 1, 41))
    with open(OUT, 'w') as f:
        json.dump({'curves': curves}, f, separators=(',', ':'))
        f.write('\n')
    print('wrote', OUT, len(curves), 'curves')


if __name__ == '__main__':
    main()
