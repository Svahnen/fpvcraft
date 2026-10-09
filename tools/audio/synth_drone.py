#!/usr/bin/env python3
"""Procedural drone sound generator for the fpvcore Minecraft mod (original work, CC0 / MIT: see CREDITS).

Everything is generated from numeric parameters (no samples are read or copied); the parameters below are frozen constants
chosen for the desired timbre (see docs/AUDIO.md for a description of the synthesis).

  motor_1 .. motor_6                   RPM ladder: snapshots of the whole drone sound at six anchor values of the sound
                                       parameter T (see motor_ladder.py). Each snapshot is the sum of the three component layers
                                       (4 slightly detuned motors with blade-pass fundamental + harmonics, phase jitter, slow
                                       amplitude wobble, commutation whine with sidebands, prop wash noise) synthesized at the
                                       frequency they have at that T, so MC plays every layer at pitch 0.7..1.4
  wind_low / wind_high                 shaped-noise wind loops with gusts
  flip                                 rotation whoosh loop
  crash_1..3, crash_big_1..2           impact one-shots (plastic/carbon clack + thud + frame ring + debris)
  prop_strike_1..2                     short prop-on-block buzz

All loops are periodic by construction (every partial has an integer number of cycles per loop, noise is generated and
shaped circularly), so they are seamless; mono 44.1 kHz Ogg Vorbis.

usage: synth_drone.py [--out DIR] [--quality 0.4]      (default DIR = adapters/minecraft-fabric/src/main/resources/assets/fpvcore/sounds)
"""
import argparse, json, sys
from pathlib import Path
import numpy as np
import soundfile as sf
sys.path.insert(0, str(Path(__file__).parent))
import motor_ladder as ML

SR = 44100
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / "adapters/minecraft-fabric/src/main/resources/assets/fpvcore"

# ---------------------------------------------------------------------------------------------------------------------
# Component layer specifications (idle / mid / high), tuned by ear. Frequencies are the nominal ones (scale = 1.0).
# detune: relative frequency offsets of the 4 motors; harm: harmonic amplitudes relative to the fundamental;
# whine_hz: commutation whine frequency, side: sideband spacing in Hz; noise: (Hz, dB) spectral envelope points
# of the prop-wash noise; *_db: component levels relative to the tonal power; am: (cycles per loop, depth).
# ---------------------------------------------------------------------------------------------------------------------
LAYERS = {
    "motor_idle": dict(
        f0=95.0, scale=1.32, loop_s=2.0,
        detune=[-0.040, -0.012, 0.020, 0.052], harm=[0.631, 0.473, 0.315, 0.262, 0.317, 0.12, 0.08],
        whine_hz=1317.9, whine_db=2.41, side=11.0, nside=3,
        noise=[(30, -45.6), (60, -37.6), (100, -23.9), (200, -21.3), (400, -20.1), (800, -17.7), (1000, -19.3), (1270, -7.9), (1600, -7.1), (2000, -9.7), (2500, -9.2), (3200, -14.0), (4000, -16.6), (5000, -12.0), (6400, -14.2), (8000, -17.6), (10000, -22.2), (12800, -25.8), (16000, -23.7), (19000, -33.7), (22000, -59.7)],
        noise_db=5.48, am=[(2, 0.10), (19, 0.04)], jitter=0.35),
    "motor_mid": dict(
        f0=277.0, scale=1.0, loop_s=2.0,
        detune=[-0.055, -0.010, 0.047, 0.083], harm=[1.0, 0.28, 0.089, 0.032, 0.028],
        whine_hz=1612.5, whine_db=-36.77, side=15.0, nside=2,
        noise=[(40, -38.2), (100, -22.1), (200, -30.7), (300, -24.7), (500, -22.8), (800, -28.4), (1000, -34.1), (1600, -25.1), (2500, -31.4), (4000, -29.7), (6400, -42.2), (10000, -54.9), (16000, -64.4), (22000, -84.4)],
        noise_db=-18.58, am=[(2, 0.06)], jitter=0.30),
    "motor_high": dict(
        f0=345.0, scale=1.30, loop_s=2.0,
        detune=[-0.100, -0.075, 0.0, 0.090], harm=[0.751, 0.166, 0.044, 0.013],
        whine_hz=2108.1, whine_db=-41.61, side=18.0, nside=2,
        noise=[(40, -29.8), (126, -31.3), (252, -33.9), (317, -4.4), (400, -21.8), (500, -35.2), (635, -13.6), (800, -31.3), (1008, -24.0), (1600, -36.3), (2500, -38.1), (4000, -35.0), (5080, -31.4), (6400, -36.8), (8000, -37.2), (10000, -33.9), (12800, -31.5), (16000, -31.4), (19000, -40.4), (22000, -70.4)],
        noise_db=-8.67, am=[(3, 0.04)], jitter=0.30),
}

WIND = {
    # 1/3-octave envelopes of the wind loops (dB rel. total); gust = (cycles per loop, depth)
    "wind_low": dict(loop_s=6.0, gust=[(4, 0.45), (7, 0.2), (11, 0.1)], scale=1.0,
        env=[(8, -14), (20, -9), (40, -7.4), (63, -7.9), (101, -10), (160, -15), (254, -22), (403, -26), (640, -27.5), (1016, -28.5), (1613, -32),
             (2560, -38), (4064, -43.5), (6451, -47.5), (10240, -50), (16000, -56)]),
    "wind_high": dict(loop_s=6.0, gust=[(11, 0.30), (17, 0.18), (29, 0.08)], scale=1.0,
        env=[(50, -42), (80, -17.5), (101, -11), (127, -6.5), (200, -10), (320, -11), (508, -12), (806, -12.5), (1016, -14.6),
             (1280, -21), (2032, -27), (3225, -33), (5120, -42), (8127, -47), (12000, -52)]),
    "flip": dict(loop_s=3.0, gust=[(5, 0.35), (9, 0.2)], scale=1.0,
        env=[(60, -30), (120, -18), (250, -9), (500, -4), (900, -3), (1500, -6), (2500, -12), (4000, -20), (7000, -30), (12000, -42)]),
}


def tgrid(seconds):
    n = int(round(seconds * SR))
    return n, np.arange(n) / SR


def shaped_noise(rng, n, pts, scale=1.0, rolloff=False):
    """Circular (hence seamless) noise whose amplitude spectrum follows the (Hz, dB) points (log-frequency interpolation)."""
    X = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    fp = np.array([p[0] for p in pts]) * scale
    dbp = np.array([p[1] for p in pts], float)
    db = np.interp(np.log(np.maximum(f, 1.0)), np.log(fp), dbp)
    if rolloff:                                # motor layers: beyond the last table point -18 dB/oct instead of a flat floor up to Nyquist
        hi = f > fp[-1]
        db[hi] -= 18 * np.log2(f[hi] / fp[-1])
    X *= 10 ** (db / 20) / np.sqrt(np.maximum(f, 1.0))   # table = band power per 1/3 octave -> PSD ~ 1/f
    X[0] = 0
    return np.fft.irfft(X, n)


def rms(x): return float(np.sqrt(np.mean(x ** 2)))
def unit(x): return x / (rms(x) + 1e-12)
def db(x): return 10 ** (x / 20)


def periodic_phase_jitter(rng, n, loop_s, amount, nterms=4):
    """Periodic (integer cycles per loop) slow phase wander in radians."""
    t = np.arange(n) / SR
    j = np.zeros(n)
    for k in rng.integers(1, 7, nterms):
        j += rng.uniform(0.3, 1.0) * np.sin(2 * np.pi * k * t / loop_s + rng.uniform(0, 2 * np.pi))
    return amount * j / np.sqrt(nterms)


def render_motor(name, spec, rng, scale=None, loop_s=None):
    scale = spec["scale"] if scale is None else scale
    L = spec["loop_s"] if loop_s is None else loop_s
    n, t = tgrid(L)
    q = lambda f: np.round(f * L) / L                       # integer cycles per loop
    tone = np.zeros(n)
    amps = [1.0, 0.9, 0.8, 0.95]
    for m, d in enumerate(spec["detune"]):
        f1 = q(spec["f0"] * scale * (1 + d))
        ph = 2 * np.pi * f1 * t + rng.uniform(0, 2 * np.pi) + periodic_phase_jitter(rng, n, L, spec["jitter"])
        am = np.ones(n)
        for cyc, depth in spec["am"]:
            am += depth * rng.uniform(0.6, 1.0) * np.sin(2 * np.pi * cyc * t / L + rng.uniform(0, 2 * np.pi))
        v = np.zeros(n)
        for h, a in enumerate(spec["harm"], start=1):
            if f1 * h < SR * 0.45:
                v += a * np.sin(h * ph + rng.uniform(0, 2 * np.pi))
        tone += amps[m] * am * v
    tone = unit(tone)
    # commutation whine: carrier plus sidebands, one slightly different carrier per motor
    whine = np.zeros(n)
    for m, d in enumerate(spec["detune"]):
        fw = spec["whine_hz"] * scale * (1 + 0.35 * d)
        for j in range(-spec["nside"], spec["nside"] + 1):
            fj = q(fw + j * spec["side"] * scale)
            whine += (1.0 / (1 + abs(j))) * np.sin(2 * np.pi * fj * t + rng.uniform(0, 2 * np.pi)
                                                   + periodic_phase_jitter(rng, n, L, 0.5, 2))
    whine = unit(whine)
    noise = unit(shaped_noise(rng, n, spec["noise"], scale, rolloff=True))
    # slow irregular turbulence on the noise (prop wash)
    turb = 1 + 0.25 * np.sin(2 * np.pi * 2 * t / L + rng.uniform(0, 6.28)) + 0.15 * np.sin(2 * np.pi * 5 * t / L + rng.uniform(0, 6.28))
    x = tone + db(spec["whine_db"]) * whine + db(spec["noise_db"]) * noise * turb
    return x, n


def render_snapshot(T, rng, loop_s):
    """Whole drone sound at sound parameter T: the three component layers at their pitch (synthesis scale) and relative level."""
    pit, vol = ML.cue(T)
    x = np.zeros(int(round(loop_s * SR)))
    for name, p, v, r in zip(("motor_idle", "motor_mid", "motor_high"), pit, vol, ML.RMS):
        if v * r < 1e-4: continue
        c, _ = render_motor(name, LAYERS[name], rng, scale=p, loop_s=loop_s)
        x += v * r * unit(c)
    return x


def render_wind(spec, rng):
    n, t = tgrid(spec["loop_s"])
    x = unit(shaped_noise(rng, n, spec["env"], spec["scale"]))
    g = np.ones(n)
    for cyc, depth in spec["gust"]:
        g += depth * np.sin(2 * np.pi * cyc * t / spec["loop_s"] + rng.uniform(0, 2 * np.pi))
    return x * np.maximum(g, 0.1), n


# ------------------------------------------------------------------ one-shots
def env_exp(n, tau, attack=0.0005):
    t = np.arange(n) / SR
    e = np.exp(-t / tau)
    a = int(attack * SR)
    if a > 1: e[:a] *= np.linspace(0, 1, a)
    return e


def modal(rng, n, freqs, taus, amps):
    t = np.arange(n) / SR
    y = np.zeros(n)
    for f, tau, a in zip(freqs, taus, amps):
        y += a * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / tau)
    return y


def lowpass_noise(rng, n, fc):
    return shaped_noise(rng, n, [(20, 0), (fc, 0), (fc * 2, -30), (SR / 2, -60)])


def clack(rng, n, kind):
    """Plastic / carbon frame hitting something: a handful of inharmonic damped modes + a short noise click."""
    if kind == "plastic":
        k = rng.integers(4, 7); fr = rng.uniform(900, 4800, k); tau = rng.uniform(0.004, 0.022, k)
    else:  # carbon: stiffer, brighter, shorter, a bit glassy
        k = rng.integers(5, 8); fr = rng.uniform(2200, 9500, k); tau = rng.uniform(0.002, 0.014, k)
    y = modal(rng, n, fr, tau, rng.uniform(0.3, 1.0, k))
    click = shaped_noise(rng, n, [(500, -20), (3000, 0), (9000, 0), (16000, -10), (21000, -30)]) * env_exp(n, 0.0015, 0.0001)
    return unit(y) * 0.8 + unit(click) * 0.5 * (rms(y) > 0)


def thud(rng, n, f_lo, tau, drop=1.5):
    t = np.arange(n) / SR
    f = f_lo * (1 + (drop - 1) * np.exp(-t / 0.03))
    ph = 2 * np.pi * np.cumsum(f) / SR
    body = np.sin(ph) * env_exp(n, tau, 0.001)
    noise = lowpass_noise(rng, n, 220) * env_exp(n, tau * 0.6, 0.0005)
    return unit(body) * 0.8 + unit(noise) * 0.5


def place(buf, sig, t0, gain):
    i = int(t0 * SR)
    m = min(len(sig), len(buf) - i)
    if m > 0: buf[i:i + m] += gain * sig[:m]


def render_crash(rng, variant, big=False):
    dur = 2.4 if big else 0.55
    n = int(dur * SR)
    out = np.zeros(n)
    kind = "carbon" if variant % 2 else "plastic"
    thud_f = rng.uniform(48, 62) if big else rng.uniform(62, 88)
    place(out, thud(rng, int(0.5 * SR) if not big else int(1.2 * SR), thud_f, 0.25 if big else 0.07), 0.0, 1.0 if big else 0.9)
    place(out, clack(rng, int(0.25 * SR), kind), 0.0, 0.9)
    # frame ring (low modes near 133 / 267 Hz)
    place(out, unit(modal(rng, int(0.5 * SR), [267 * rng.uniform(0.95, 1.05), 133 * rng.uniform(0.95, 1.05)], [0.15, 0.2], [1, 0.7])), 0.0, 0.35)
    # secondary bounces
    nb = 6 if big else 2
    tt = 0.0
    gap = 0.07
    for b in range(nb):
        tt += gap * rng.uniform(0.8, 1.3); gap *= 0.75
        g = 0.55 * 0.75 ** b
        place(out, clack(rng, int(0.2 * SR), kind if rng.random() < 0.6 else ("plastic" if kind == "carbon" else "carbon")), tt, g)
        place(out, thud(rng, int(0.2 * SR), thud_f * rng.uniform(1.0, 1.4), 0.04), tt, g * 0.6)
    if big:
        # debris scattering and rumble: Poisson clacks with decaying rate, broadband tail
        t = 0.1
        while t < 2.2:
            place(out, clack(rng, int(0.12 * SR), "plastic" if rng.random() < 0.7 else "carbon"), t, 0.35 * np.exp(-t / 0.7) * rng.uniform(0.3, 1))
            t += rng.exponential(0.018 + 0.05 * t)
        tail = shaped_noise(rng, n, [(30, -10), (100, -4), (400, -4), (1500, -6), (3000, -9), (6000, -16), (12000, -30)])
        out += 0.5 * unit(tail) * env_exp(n, 0.55, 0.01) * (1 - np.exp(-np.arange(n) / SR / 0.02))
    return out * np.minimum(1, (n - np.arange(n)) / (0.05 * SR))


def render_prop_strike(rng, variant):
    n = int(0.45 * SR)
    t = np.arange(n) / SR
    f = rng.uniform(700, 1000) * (1 - 0.35 * t / t[-1])
    ph = 2 * np.pi * np.cumsum(f) / SR
    saw = sum(np.sin(h * ph) / h for h in range(1, 12))
    chop = 0.6 + 0.4 * np.sin(2 * np.pi * rng.uniform(70, 110) * t + rng.uniform(0, 6.28))
    scrape = shaped_noise(rng, n, [(300, -20), (1500, 0), (5000, -3), (10000, -12), (18000, -30)])
    y = (unit(saw) * 0.6 * chop + unit(scrape) * 0.6 * chop) * env_exp(n, 0.11, 0.002)
    y += 0.8 * np.concatenate([clack(rng, int(0.2 * SR), "plastic"), np.zeros(n - int(0.2 * SR))])
    y += 0.4 * np.concatenate([thud(rng, int(0.15 * SR), 120 * rng.uniform(0.9, 1.2), 0.03), np.zeros(n - int(0.15 * SR))])
    return y * np.minimum(1, (n - np.arange(n)) / (0.04 * SR))


# ------------------------------------------------------------------ output
def write_ogg(path, x, quality):
    path.parent.mkdir(parents=True, exist_ok=True)
    x = np.clip(x, -1, 1).astype(np.float32)
    with sf.SoundFile(str(path), "w", samplerate=SR, channels=1, format="OGG", subtype="VORBIS") as f:
        try: f.compression_level = quality
        except Exception: pass
        f.write(x)


def normalise(x, target_rms=None, peak=0.89):
    if target_rms is not None: x = x * (target_rms / (rms(x) + 1e-12))
    p = np.max(np.abs(x))
    return x * (peak / p) if p > peak else x


def wrap_step_ratio(x):
    """Discontinuity at the loop point relative to the typical sample-to-sample step (1.0 = seamless)."""
    d = np.abs(np.diff(x))
    return float(abs(x[0] - x[-1]) / (np.percentile(d, 99) + 1e-12))


def build(out_dir, quality=0.4, seed=7):
    rng = np.random.default_rng(seed)
    snd = out_dir / "sounds"
    report = {}
    files = {}
    for name, spec in LAYERS.items():          # legacy 3-layer render, discarded: only advances the rng so the other sounds stay identical
        render_motor(name, spec, rng)
    mrng = np.random.default_rng(seed + 100)
    Tk, rk = ML.anchors()
    for k, T in enumerate(Tk, start=1):
        files[f"motor_{k}"] = normalise(render_snapshot(T, mrng, 3.0 if k <= 2 else 2.0), 0.16)
    for name, spec in WIND.items():
        x, n = render_wind(spec, rng)
        files[name] = normalise(x, 0.16 if name != "flip" else 0.16)
    for i in range(1, 4): files[f"crash_{i}"] = normalise(render_crash(rng, i), None, 0.85)
    for i in range(1, 3): files[f"crash_big_{i}"] = normalise(render_crash(rng, i, big=True), None, 0.88)
    for i in range(1, 3): files[f"prop_strike_{i}"] = normalise(render_prop_strike(rng, i), None, 0.8)
    total = 0
    for k, x in files.items():
        p = snd / f"{k}.ogg"
        write_ogg(p, x, quality)
        b = p.stat().st_size; total += b
        loop = k.startswith(("motor", "wind", "flip"))
        chk, _ = sf.read(p)
        report[k] = (len(x) / SR, b, wrap_step_ratio(chk) if loop else None)
        print(f"{k:14s} {len(x)/SR:5.2f}s {b/1024:6.1f} KB rms={rms(x):.3f} peak={np.max(np.abs(x)):.2f}"
              + (f" loop-step-ratio={wrap_step_ratio(chk):.2f}" if loop else ""))
    print(f"total {total/1024:.0f} KB")
    sj = {
        "wind_low": {"sounds": [{"name": "fpvcore:wind_low", "stream": False}]},
        "wind_high": {"sounds": [{"name": "fpvcore:wind_high", "stream": False}]},
        "flip": {"sounds": [{"name": "fpvcore:flip", "stream": False}]},
        "crash": {"sounds": [f"fpvcore:crash_{i}" for i in range(1, 4)]},
        "crash_big": {"sounds": [f"fpvcore:crash_big_{i}" for i in range(1, 3)]},
        "prop_strike": {"sounds": [f"fpvcore:prop_strike_{i}" for i in range(1, 3)]},
    }
    for k in range(1, len(Tk) + 1): sj[f"motor_{k}"] = {"sounds": [{"name": f"fpvcore:motor_{k}", "stream": False}]}
    (out_dir / "sounds.json").write_text(json.dumps(sj, indent=2) + "\n")
    return files


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(DEFAULT_OUT), help="assets/fpvcore directory")
    ap.add_argument("--quality", type=float, default=0.4, help="Vorbis quality 0..1")
    a = ap.parse_args()
    build(Path(a.out), a.quality)
