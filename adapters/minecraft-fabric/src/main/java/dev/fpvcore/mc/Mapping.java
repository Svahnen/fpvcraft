package dev.fpvcore.mc;

/** RC axis mapping + calibration; a port of fpvinput::Mapping (min/center/max, deadzone, invert, axis map). */
public final class Mapping {
    public static final class Axis {
        public int axis = -1;
        public boolean invert;
        public float min = -1, center = 0, max = 1, deadzone = 0;
        public Axis() {}
        public Axis(int a) { axis = a; }
    }

    public String deviceName = "", deviceGuid = "";
    public Axis roll = new Axis(0), pitch = new Axis(1), throttle = new Axis(2), yaw = new Axis(3); // EdgeTX AETR default
    public boolean gamepadMode;
    public int armAxis = 4, armButton = -1;
    public boolean armInvert;
    public float armThreshold = 0.5f;
    public int modeAxis = 5;
    public boolean modeInvert;
    public int[] modeButtons = {-1, -1, -1};
    /** True once the wizard saved this mapping for the device (false = default AETR guess). */
    public boolean calibrated;
    /** Transmitter stick mode 1-4 (default 2: left = throttle/yaw, right = pitch/roll). Only affects prompts and the two-stick test view. Old configs lack it and get 2. */
    public int stickMode = 2;

    /** Channel-order presets for the four sticks (invert off, range -1..1): "AETR" = EdgeTX default (roll 0, pitch 1, throttle 2, yaw 3), "TAER" (throttle 0, roll 1, pitch 2, yaw 3). */
    public void applyPreset(String name) {
        int[] o = "TAER".equalsIgnoreCase(name) ? new int[]{1, 2, 0, 3} : new int[]{0, 1, 2, 3}; // roll, pitch, throttle, yaw
        Axis[] a = {roll, pitch, throttle, yaw};
        for (int i = 0; i < 4; i++) { a[i].axis = o[i]; a[i].invert = false; a[i].min = -1; a[i].max = 1; a[i].center = 0; }
        throttle.deadzone = 0;
        if (armAxis >= 0 && armAxis < 4) armAxis = 4;
        if (modeAxis >= 0 && modeAxis < 4) modeAxis = 5;
    }

    public String summary() {
        return String.format("stickMode=%d roll=%d%s[%.2f,%.2f,%.2f] pitch=%d%s[%.2f,%.2f,%.2f] throttle=%d%s[%.2f,%.2f] yaw=%d%s[%.2f,%.2f,%.2f] arm=%d/btn%d mode=%d calibrated=%s",
            stickMode, roll.axis, roll.invert ? "(inv)" : "", roll.min, roll.center, roll.max, pitch.axis, pitch.invert ? "(inv)" : "", pitch.min, pitch.center, pitch.max,
            throttle.axis, throttle.invert ? "(inv)" : "", throttle.min, throttle.max, yaw.axis, yaw.invert ? "(inv)" : "", yaw.min, yaw.center, yaw.max, armAxis, armButton, modeAxis, calibrated);
    }

    public Mapping copy() { com.google.gson.Gson g = new com.google.gson.Gson(); return g.fromJson(g.toJson(this), Mapping.class); }
    public boolean hasArm() { return armAxis >= 0 || armButton >= 0; }

    /** Result of reading the device: sticks in [-1,1] (throttle -1 = idle). */
    public static final class Sticks { public double throttle = -1, yaw, pitch, roll; public boolean arm; public int mode = 1; public boolean armMapped, modeMapped; }

    static double mapClamped(double v, double a, double b, double c, double d) {
        if (b == a) return d;
        double t = (v - a) / (b - a);
        t = Math.max(0, Math.min(1, t));
        return c + (d - c) * t;
    }

    public static double normalizeCentered(float raw, Axis c) {
        float v = c.invert ? -raw : raw;
        float mn = c.invert ? -c.max : c.min, mx = c.invert ? -c.min : c.max, ce = c.invert ? -c.center : c.center;
        double a = v < ce ? mapClamped(v, mn, ce, -1, 0) : mapClamped(v, ce, mx, 0, 1);
        double s = a < 0 ? -1 : 1;
        return s * mapClamped(Math.abs(a), c.deadzone, 1, 0, 1);
    }

    public static double normalizeThrottle(float raw, Axis c, boolean gamepad) {
        float v = c.invert ? -raw : raw;
        float mn = c.invert ? -c.max : c.min, mx = c.invert ? -c.min : c.max;
        return mapClamped(v, gamepad ? 0.5f * (mn + mx) : mn, mx, -1, 1);
    }

    private static float at(float[] a, int i) { return i >= 0 && i < a.length ? a[i] : 0f; }

    public Sticks apply(float[] ax, boolean[] bt) {
        Sticks s = new Sticks();
        if (roll.axis >= 0) s.roll = normalizeCentered(at(ax, roll.axis), roll);
        if (pitch.axis >= 0) s.pitch = normalizeCentered(at(ax, pitch.axis), pitch);
        if (yaw.axis >= 0) s.yaw = normalizeCentered(at(ax, yaw.axis), yaw);
        if (throttle.axis >= 0) s.throttle = normalizeThrottle(at(ax, throttle.axis), throttle, gamepadMode);
        if (armAxis >= 0 && armAxis < ax.length) { float v = ax[armAxis]; s.arm = (armInvert ? -v : v) > armThreshold; s.armMapped = true; }
        else if (armButton >= 0 && armButton < bt.length) { s.arm = bt[armButton]; s.armMapped = true; }
        s.mode = 1;
        if (modeAxis >= 0 && modeAxis < ax.length) {
            float v = ax[modeAxis]; if (modeInvert) v = -v;
            int idx = Math.max(0, Math.min(2, Math.round(v + 1f)));
            s.mode = 1 + idx; // 1 acro, 2 angle, 3 acro3d
            s.modeMapped = true;
        } else {
            for (int i = 0; i < 3; i++) if (modeButtons[i] >= 0 && modeButtons[i] < bt.length) { s.modeMapped = true; if (bt[modeButtons[i]]) s.mode = 1 + i; }
        }
        return s;
    }
}
