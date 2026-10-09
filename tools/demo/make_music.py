#!/usr/bin/env python3
"""Synthesise a short original music bed (CC0, numpy only, no samples): driving A-minor synthwave-style loop with a build-up.

usage: make_music.py <out.wav> [seconds=100] [bpm=120]
"""
import subprocess, sys
import numpy as np

SR = 44100


def midi(n): return 440.0 * 2 ** ((n - 69) / 12.0)


def lowpass_fft(x, cutoff):
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    X *= 1.0 / np.sqrt(1.0 + (f / cutoff) ** 4)
    return np.fft.irfft(X, len(x))


def saw(freq, n, detune=0.0, ph=0.0):
    t = np.arange(n) / SR
    return 2 * ((t * freq * (1 + detune) + ph) % 1.0) - 1


def adsr(n, a, d, s, r_start, r):
    e = np.ones(n) * s
    na = int(a * SR); nd = int(d * SR)
    e[:na] = np.linspace(0, 1, max(na, 1))
    e[na:na + nd] = np.linspace(1, s, max(nd, 1))[:max(0, min(nd, n - na))]
    nr = int(r * SR); rs = int(r_start * SR)
    if rs < n:
        k = min(nr, n - rs)
        e[rs:rs + k] *= np.linspace(1, 0, k)
        e[rs + k:] = 0
    return e


def main():
    out = sys.argv[1]
    secs = float(sys.argv[2]) if len(sys.argv) > 2 else 100
    bpm = float(sys.argv[3]) if len(sys.argv) > 3 else 120
    beat = 60.0 / bpm
    n = int((secs + 3) * SR)
    prog = [(45, [57, 60, 64]), (41, [53, 57, 60]), (36, [55, 60, 64]), (43, [55, 59, 62])]   # Am F C G (bass root, chord)
    pad = np.zeros(n); bass = np.zeros(n); arp = np.zeros(n); drums = np.zeros(n)
    bars = int(secs / (4 * beat)) + 2
    rng = np.random.default_rng(5)
    for bar in range(bars):
        root, chord = prog[(bar // 2) % 4]
        t0 = bar * 4 * beat
        a = int(t0 * SR)
        L = int(4 * beat * SR)
        if a >= n: break
        L = min(L, n - a)
        # pad: detuned saws, slow attack
        p = np.zeros(L)
        for note in chord:
            for dt in (-0.004, 0.0, 0.004):
                p += saw(midi(note), L, dt) * 0.25
        pad[a:a + L] += p * adsr(L, 0.8, 0.5, 0.8, 3.4 * beat, 0.6)
        # bass: eighths
        for i in range(8):
            s = int(i * 0.5 * beat * SR)
            m = min(int(0.42 * beat * SR), L - s)
            if m <= 0: continue
            bass[a + s:a + s + m] += saw(midi(root), m) * adsr(m, 0.005, 0.1, 0.6, 0.3 * beat, 0.08) * 0.8
        # arp: sixteenths over the chord (up-up-down pattern)
        pat = [0, 1, 2, 1, 0, 2, 1, 2, 0, 1, 2, 1, 2, 1, 0, 1]
        for i in range(16):
            s = int(i * 0.25 * beat * SR)
            m = min(int(0.2 * beat * SR), L - s)
            if m <= 0: continue
            note = chord[pat[i]] + 12 + (12 if i % 8 == 7 else 0)
            arp[a + s:a + s + m] += (saw(midi(note), m, 0.003) + saw(midi(note), m, -0.003)) * adsr(m, 0.002, 0.07, 0.15, 0.1 * beat, 0.05) * 0.5
        # drums
        for beatn in range(4):
            s = a + int(beatn * beat * SR)
            if s >= n: continue
            m = min(int(0.30 * SR), n - s)
            t = np.arange(m) / SR
            kick = np.sin(2 * np.pi * (50 * t + 90 * (1 - np.exp(-t * 28)) / 28)) * np.exp(-t * 11) * 1.0
            drums[s:s + m] += kick
            if beatn in (1, 3):
                noise = rng.standard_normal(m) * np.exp(-t * 18) * 0.5
                drums[s:s + m] += noise
            for h in (0, 1):   # offbeat hats
                s2 = s + int((0.5 * h + 0.25) * beat * SR)
                m2 = min(int(0.05 * SR), n - s2)
                if m2 > 0 and s2 < n:
                    hat = rng.standard_normal(m2) * np.exp(-np.arange(m2) / SR * 90) * 0.18
                    drums[s2:s2 + m2] += np.diff(np.concatenate([[0], hat]))
    pad = lowpass_fft(pad, 1800); bass = lowpass_fft(bass, 500); arp = lowpass_fft(arp, 4500)
    t = np.arange(n) / SR
    def ramp(a, b): return np.clip((t - a) / (b - a), 0, 1)
    # arrangement: pad from the start, bass+arp at 8 s, drums at 16 s, everything lifts at 40 s
    mix = pad * (0.5 + 0.2 * ramp(35, 40)) + bass * 0.55 * ramp(8, 10) + arp * 0.35 * ramp(7, 14) + drums * 0.5 * ramp(15, 16)
    # sidechain-ish pump on the kick
    env = np.ones(n)
    for bn in range(int(secs / beat) + 2):
        s = int(bn * beat * SR)
        m = min(int(0.28 * beat * SR), n - s)
        if m > 0 and t[s] > 15: env[s:s + m] *= np.linspace(0.45, 1, m)
    mix = mix * env
    mix *= np.clip((secs - t) / 3.0, 0, 1)    # fade out
    mix /= np.abs(mix).max() * 1.15
    st = np.stack([mix, np.roll(mix, int(0.011 * SR))], axis=1)   # slight stereo width
    raw = (np.clip(st, -1, 1) * 32767).astype('<i2').tobytes()
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-f', 's16le', '-ar', str(SR), '-ac', '2', '-i', '-', out], input=raw, check=True)
    print('wrote', out)

if __name__ == '__main__':
    main()
