package dev.fpvcore.mc.demo;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * A time-parameterised 3D flight path (position, velocity, acceleration) built from waypoints with speeds: centripetal Catmull-Rom through the
 * waypoints, speed interpolated along arc length, resampled at 120 Hz and low-pass filtered so the acceleration feed-forward is smooth.
 * Pure maths (no Minecraft classes) so it can be used from offline test harnesses.
 */
public final class Path implements PathRef {
    public static final double DT = 1.0 / 120.0;

    public static final class Wp {
        public final double x, y, z, speed; public final String tag;
        public Wp(double x, double y, double z, double speed, String tag) { this.x = x; this.y = y; this.z = z; this.speed = speed; this.tag = tag; }
    }
    /** A tagged point on the final path. */
    public static final class Mark { public final String tag; public final double t; public final Vector3d pos = new Vector3d(), dir = new Vector3d(); Mark(String tag, double t) { this.tag = tag; this.t = t; } }

    public final int n;
    public final Vector3d[] p, v, a;
    public final List<Mark> marks = new ArrayList<>();
    public double duration() { return (n - 1) * DT; }

    private static Vector3d cr(Vector3d p0, Vector3d p1, Vector3d p2, Vector3d p3, double u) {
        // centripetal Catmull-Rom (Barry-Goldman), u in [0,1] between p1 and p2
        double t0 = 0, t1 = t0 + Math.sqrt(p0.distance(p1)) + 1e-9, t2 = t1 + Math.sqrt(p1.distance(p2)) + 1e-9, t3 = t2 + Math.sqrt(p2.distance(p3)) + 1e-9;
        double t = t1 + (t2 - t1) * u;
        Vector3d a1 = lerp(p0, p1, (t - t0) / (t1 - t0)), a2 = lerp(p1, p2, (t - t1) / (t2 - t1)), a3 = lerp(p2, p3, (t - t2) / (t3 - t2));
        Vector3d b1 = lerp(a1, a2, (t - t0) / (t2 - t0)), b2 = lerp(a2, a3, (t - t1) / (t3 - t1));
        return lerp(b1, b2, (t - t1) / (t2 - t1));
    }
    private static Vector3d lerp(Vector3d a, Vector3d b, double f) { return new Vector3d(a).lerp(b, f); }

    public Path(List<Wp> wps, double smoothSigmaS) {
        int m = wps.size();
        Vector3d[] c = new Vector3d[m];
        for (int i = 0; i < m; i++) c[i] = new Vector3d(wps.get(i).x, wps.get(i).y, wps.get(i).z);
        // dense polyline
        List<Vector3d> dense = new ArrayList<>(); List<Integer> wpIdx = new ArrayList<>();
        for (int i = 0; i < m - 1; i++) {
            Vector3d p0 = i == 0 ? new Vector3d(c[0]).mul(2).sub(c[1]) : c[i - 1], p3 = i + 2 >= m ? new Vector3d(c[m - 1]).mul(2).sub(c[m - 2]) : c[i + 2];
            int k = Math.max(4, (int) Math.ceil(c[i].distance(c[i + 1]) / 0.25));
            for (int j = 0; j < k; j++) { wpIdx.add(j == 0 ? i : -1); dense.add(cr(p0, c[i], c[i + 1], p3, j / (double) k)); }
        }
        dense.add(new Vector3d(c[m - 1])); wpIdx.add(m - 1);
        int dn = dense.size();
        double[] s = new double[dn];
        for (int i = 1; i < dn; i++) s[i] = s[i - 1] + dense.get(i).distance(dense.get(i - 1));
        // speed along arc length: smoothstep between waypoint speeds
        double[] sw = new double[m]; int wi = 0;
        for (int i = 0; i < dn; i++) if (wpIdx.get(i) >= 0) sw[wpIdx.get(i)] = s[i];
        double[] sp = new double[dn];
        for (int i = 0; i < dn; i++) {
            int seg = 0; while (seg < m - 2 && s[i] > sw[seg + 1]) seg++;
            double f = sw[seg + 1] > sw[seg] ? (s[i] - sw[seg]) / (sw[seg + 1] - sw[seg]) : 0; f = Math.max(0, Math.min(1, f));
            f = f * f * (3 - 2 * f);
            sp[i] = wps.get(seg).speed + (wps.get(seg + 1).speed - wps.get(seg).speed) * f;
        }
        double[] tt = new double[dn];
        for (int i = 1; i < dn; i++) tt[i] = tt[i - 1] + (s[i] - s[i - 1]) / (0.5 * (sp[i] + sp[i - 1]));
        double total = tt[dn - 1];
        n = (int) Math.floor(total / DT) + 1;
        Vector3d[] raw = new Vector3d[n];
        int j = 0;
        for (int i = 0; i < n; i++) {
            double t = i * DT; while (j < dn - 2 && tt[j + 1] < t) j++;
            double f = (t - tt[j]) / Math.max(1e-9, tt[j + 1] - tt[j]);
            raw[i] = lerp(dense.get(j), dense.get(j + 1), Math.max(0, Math.min(1, f)));
        }
        // gaussian smoothing in time (endpoints fixed by mirrored padding)
        int rad = (int) Math.ceil(3 * smoothSigmaS / DT);
        p = new Vector3d[n];
        double[] w = new double[2 * rad + 1]; double ws = 0;
        for (int k = -rad; k <= rad; k++) { w[k + rad] = Math.exp(-0.5 * Math.pow(k * DT / Math.max(1e-6, smoothSigmaS), 2)); ws += w[k + rad]; }
        for (int i = 0; i < n; i++) {
            Vector3d acc = new Vector3d();
            for (int k = -rad; k <= rad; k++) {
                int q = i + k; Vector3d pt;
                if (q < 0) pt = new Vector3d(raw[0]).mul(2).sub(raw[Math.min(n - 1, -q)]); else if (q >= n) pt = new Vector3d(raw[n - 1]).mul(2).sub(raw[Math.max(0, 2 * (n - 1) - q)]); else pt = raw[q];
                acc.fma(w[k + rad] / ws, pt);
            }
            p[i] = acc;
        }
        v = new Vector3d[n]; a = new Vector3d[n];
        for (int i = 0; i < n; i++) { Vector3d pa = p[Math.max(0, i - 1)], pb = p[Math.min(n - 1, i + 1)]; v[i] = new Vector3d(pb).sub(pa).div((Math.min(n - 1, i + 1) - Math.max(0, i - 1)) * DT); }
        for (int i = 0; i < n; i++) { Vector3d va = v[Math.max(0, i - 2)], vb = v[Math.min(n - 1, i + 2)]; a[i] = new Vector3d(vb).sub(va).div((Math.min(n - 1, i + 2) - Math.max(0, i - 2)) * DT); }
        for (int i = 0; i < m; i++) {
            String tag = wps.get(i).tag; if (tag == null || tag.isEmpty()) continue;
            int di = wpIdx.indexOf(i);
            Mark mk = new Mark(tag, tt[di]);
            int idx = Math.min(n - 1, (int) Math.round(tt[di] / DT));
            mk.pos.set(p[idx]); mk.dir.set(v[idx]).normalize();
            marks.add(mk);
        }
    }

    private int idx(double t) { return Math.max(0, Math.min(n - 1, (int) Math.round(t / DT))); }
    @Override public Vector3d pos(double t) { return interp(p, t); }
    @Override public Vector3d vel(double t) { return interp(v, t); }
    @Override public Vector3d acc(double t) { return interp(a, t); }
    private Vector3d interp(Vector3d[] arr, double t) {
        double f = Math.max(0, Math.min(n - 1.0001, t / DT)); int i = (int) f; double u = f - i;
        return new Vector3d(arr[i]).lerp(arr[Math.min(n - 1, i + 1)], u);
    }
}
