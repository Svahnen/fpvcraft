package dev.fpvcore.mc.demo;

import dev.fpvcore.mc.Drone;
import dev.fpvcore.mc.FpvNative;
import dev.fpvcore.mc.Mapping;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Autopilot that makes a real fpvcore drone (acro mode) follow a {@link Path}: position/velocity feedback + feed-forward on the path acceleration and quadratic drag
 * give a desired thrust vector -> desired attitude (body-up = thrust direction, nose along the path tangent) -> body rates with a P law -> stick deflections by inverting
 * the drone's own rates curve. Only produces stick inputs (throttle, yaw, pitch, roll), like a pilot; the physics stays in the library.
 */
public final class PathPilot {
    public static final double G = 9.81;
    public double kPos = 5.0, kVel = 4.2, kI = 1.6, kAtt = 13.0, kDrag = 0.0277, maxTilt = 1e9;
    private final FpvNative.Rates rates = new FpvNative.Rates();
    private final double[] thrStick = new double[41], thrT = new double[41]; // thrust (field units) at throttle stick -1..1
    private double hoverT;
    private final Vector3d integ = new Vector3d();
    private Quaterniond qDesPrev;
    private final Vector3d wFf = new Vector3d();
    private Vector3d lastX = new Vector3d(1, 0, 0);
    public double lastTilt, lastThrustG, lastErr;

    public PathPilot(String preset) {
        calibrate(preset);
    }

    private void calibrate(String preset) {
        try (Drone d = new Drone(preset)) {
            FpvNative.Rates r = d.getRates();
            rates.type = r.type; System.arraycopy(r.rc, 0, rates.rc, 0, 3); System.arraycopy(r.srate, 0, rates.srate, 0, 3); System.arraycopy(r.expo, 0, rates.expo, 0, 3);
            double dt = 1 / 60.0;
            for (int k = 0; k <= 40; k++) {
                double st = -1 + k * 0.05;
                d.reset(0, 1000, 0, 0);
                d.setInput(-1, 0, 0, 0, false, 1); for (int i = 0; i < 6; i++) d.step(dt);
                d.setInput(-1, 0, 0, 0, true, 1); for (int i = 0; i < 6; i++) d.step(dt);
                d.setInput(st, 0, 0, 0, true, 1); for (int i = 0; i < 40; i++) d.step(dt);
                thrStick[k] = st; thrT[k] = d.state().thrust;
            }
            // hover thrust from the early vertical acceleration at a known stick
            d.reset(0, 1000, 0, 0);
            d.setInput(-1, 0, 0, 0, false, 1); for (int i = 0; i < 6; i++) d.step(dt);
            d.setInput(-1, 0, 0, 0, true, 1); for (int i = 0; i < 6; i++) d.step(dt);
            d.setInput(-0.4, 0, 0, 0, true, 1); for (int i = 0; i < 30; i++) d.step(dt);
            double v0 = d.state().linvel[1]; double tt = d.state().thrust;
            for (int i = 0; i < 6; i++) d.step(dt);
            double acc = (d.state().linvel[1] - v0) / (6 * dt);
            hoverT = tt * G / (G + acc);
        }
    }

    /** Thrust expressed in g (1 = hover) at a throttle stick. */
    public double thrustG(double stick) {
        double f = (Math.max(-1, Math.min(1, stick)) + 1) / 0.05; int i = Math.min(39, (int) f);
        double T = thrT[i] + (thrT[i + 1] - thrT[i]) * (f - i);
        return T / hoverT;
    }
    public double maxThrustG() { return thrT[40] / hoverT; }

    private double stickForThrustG(double g) {
        double T = g * hoverT;
        if (T <= thrT[0]) return -1;
        for (int k = 0; k < 40; k++) if (T <= thrT[k + 1]) { double f = (T - thrT[k]) / Math.max(1e-9, thrT[k + 1] - thrT[k]); return thrStick[k] + 0.05 * f; }
        return 1;
    }

    private double invRate(int axis, double w) { // deg/s -> stick
        double a = Math.abs(w), lo = 0, hi = 1;
        FpvNative.Lib lib = FpvNative.get();
        if (a >= lib.fpv_rate(rates.type, 1.0, rates.rc[axis], rates.srate[axis], rates.expo[axis])) return Math.signum(w);
        for (int i = 0; i < 28; i++) { double m = 0.5 * (lo + hi); if (lib.fpv_rate(rates.type, m, rates.rc[axis], rates.srate[axis], rates.expo[axis]) < a) lo = m; else hi = m; }
        return Math.signum(w) * 0.5 * (lo + hi);
    }

    public void reset() { integ.zero(); qDesPrev = null; wFf.zero(); }

    /** One control step. s = current Y-up state (metres, world), path time tp. Writes the sticks (arm = true, mode 1 = acro). */
    public Mapping.Sticks control(FpvNative.State s, PathRef path, double tp, double dt, double[] origin, boolean hold) {
        Vector3d p = new Vector3d(s.pos[0] - origin[0], s.pos[1] - origin[1], s.pos[2] - origin[2]);
        Vector3d v = new Vector3d(s.linvel[0], s.linvel[1], s.linvel[2]);
        Vector3d pr = path.pos(tp), vr = path.vel(tp), ar = path.acc(tp);
        Vector3d ep = new Vector3d(pr).sub(p), ev = new Vector3d(vr).sub(v);
        lastErr = ep.length();
        integ.fma(kI * dt, ep);
        if (integ.length() > 8) integ.normalize(8);
        Vector3d aDes = new Vector3d(ar).fma(kDrag * vr.length(), vr).fma(kPos, ep).fma(kVel, ev).add(integ);
        Vector3d F = new Vector3d(aDes.x, aDes.y + G, aDes.z);
        double maxF = G * maxThrustG() * 0.97;
        if (F.length() > maxF) F.mul(maxF / F.length());
        double fl = Math.max(F.length(), 0.3 * G);
        Vector3d yb = fl > 1e-6 && F.length() > 1e-6 ? new Vector3d(F).normalize() : new Vector3d(0, 1, 0);
        // nose along the path tangent (perpendicular to the thrust direction)
        Vector3d f = new Vector3d(vr); if (f.length() < 0.5) f.set(lastX); f.normalize();
        Vector3d xb = new Vector3d(f).fma(-f.dot(yb), yb);
        if (xb.length() < 0.25) xb.set(lastX).fma(-lastX.dot(yb), yb);
        xb.normalize(); lastX = new Vector3d(xb);
        Vector3d zb = new Vector3d(xb).cross(yb).normalize();
        Matrix3d Rd = new Matrix3d(xb, yb, zb);
        Quaterniond qd = new Quaterniond().setFromNormalized(Rd).normalize();
        Quaterniond qc = new Quaterniond(s.quat[0], s.quat[1], s.quat[2], s.quat[3]).normalize();
        Quaterniond qe = new Quaterniond(qc).invert().mul(qd);
        if (qe.w < 0) { qe.x = -qe.x; qe.y = -qe.y; qe.z = -qe.z; qe.w = -qe.w; }
        double ang = 2 * Math.acos(Math.min(1, qe.w)), sn = Math.sqrt(Math.max(1e-12, 1 - qe.w * qe.w));
        Vector3d e = new Vector3d(qe.x, qe.y, qe.z).mul(ang / sn);
        if (ang < 1e-6) e.zero();
        // feed-forward rate of the desired attitude (in the desired body frame ~ current body frame)
        if (qDesPrev != null) {
            Quaterniond dq = new Quaterniond(qDesPrev).invert().mul(qd);
            if (dq.w < 0) { dq.x = -dq.x; dq.y = -dq.y; dq.z = -dq.z; dq.w = -dq.w; }
            double a2 = 2 * Math.acos(Math.min(1, dq.w)), s2 = Math.sqrt(Math.max(1e-12, 1 - dq.w * dq.w));
            Vector3d w = new Vector3d(dq.x, dq.y, dq.z).mul(a2 / s2 / dt);
            if (a2 < 1e-7) w.zero();
            wFf.lerp(w, 0.5);
        }
        qDesPrev = qd;
        Vector3d wc = new Vector3d(e).mul(kAtt).add(wFf);
        Mapping.Sticks st = new Mapping.Sticks();
        st.roll = invRate(2, Math.toDegrees(wc.x));
        st.pitch = invRate(1, -Math.toDegrees(wc.z));
        st.yaw = invRate(0, -Math.toDegrees(wc.y));
        st.throttle = stickForThrustG(fl / G);
        st.arm = true; st.mode = 1;
        lastThrustG = fl / G;
        double[] up = {0, 1, 0};
        Vector3d ybc = new Vector3d(0, 1, 0).rotate(qc);
        lastTilt = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, ybc.y))));
        return st;
    }
}
