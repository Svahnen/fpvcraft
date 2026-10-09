package dev.fpvcore.mc;

import com.sun.jna.Pointer;

/** One fpvcore drone instance. All values in the *_yup helpers are metres, Y-up right-handed = Minecraft world axes. */
public final class Drone implements AutoCloseable {
    public static final String[] PRESETS = {"5race", "5freestyle", "3micro", "7longrange"};

    /** Maps preset ids used by earlier versions (old configs, commands) to the current ids. */
    public static String canonicalId(String id) {
        if (id == null) return "5race";
        return switch (id) {
            case "KM" -> "3micro";
            case "7Msurfer" -> "7longrange";
            default -> id;
        };
    }

    private final FpvNative.Lib lib = FpvNative.get();
    private Pointer h;
    public final String presetId;
    private final FpvNative.Input in = new FpvNative.Input();
    private final FpvNative.State st = new FpvNative.State();
    private final FpvNative.Rates rates = new FpvNative.Rates();

    public Drone(String presetId) {
        this.presetId = presetId = canonicalId(presetId);
        String json = lib.fpv_builtin_preset(presetId);
        if (json == null) throw new IllegalArgumentException("unknown preset " + presetId);
        h = lib.fpv_create(json);
        if (h == null) throw new IllegalStateException("fpv_create failed: " + lib.fpv_last_error());
    }

    public void setInput(double thr, double yaw, double pitch, double roll, boolean arm, int mode) {
        in.throttle = thr; in.yaw = yaw; in.pitch = pitch; in.roll = roll; in.arm = arm ? 1 : 0; in.mode = mode;
        lib.fpv_set_input(h, in);
    }

    public void step(double dt) { lib.fpv_step(h, dt); }

    /** Refreshes and returns the (reused) Y-up state. */
    public FpvNative.State state() { lib.fpv_get_state_yup(h, st); return st; }

    /** Places the drone at a Minecraft position (metres, feet-of-drone centre) facing a Minecraft yaw (degrees). */
    public void reset(double x, double y, double z, double mcYawDeg) {
        double[] nat = new double[3];
        lib.fpv_pos_yup_to_native(new double[]{x, y, z}, nat);
        lib.fpv_reset(h, nat, mcYawDeg - 180.0); // native heading 0 = MC north (yaw 180)
    }

    /** Teleport pose only (keeps velocity). */
    public void setPosition(double x, double y, double z) {
        FpvNative.State s = new FpvNative.State();
        lib.fpv_get_state(h, s);
        lib.fpv_pos_yup_to_native(new double[]{x, y, z}, s.pos);
        lib.fpv_set_state(h, s);
    }

    public void setTrace(double downM, double upM) {
        lib.fpv_set_trace_results(h, downM < 0 ? -1 : downM * 100.0, upM < 0 ? -1 : upM * 100.0);
    }

    /** Contact with a Y-up world normal (pointing out of the surface) and depth in metres. Returns the impulse. */
    public double contact(double nx, double ny, double nz, double depthM) {
        double[] nat = new double[3];
        lib.fpv_dir_yup_to_native(new double[]{nx, ny, nz}, nat);
        return lib.fpv_add_contact(h, nat, depthM * 100.0);
    }

    /** Point contact: Y-up world point (m) and normal, depth (m), Coulomb friction coefficient (<0 = library default). Returns the impulse. */
    public double contactAt(double px, double py, double pz, double nx, double ny, double nz, double depthM, double friction) {
        double[] pt = new double[3], n = new double[3];
        lib.fpv_pos_yup_to_native(new double[]{px, py, pz}, pt);
        lib.fpv_dir_yup_to_native(new double[]{nx, ny, nz}, n);
        return lib.fpv_add_contact_ex(h, pt, n, depthM * 100.0, friction, -1.0);
    }

    private double[] hull;
    /** Body-local collision hull points (metres) in the Y-up body frame (+X nose, +Y up, +Z right), flat array x,y,z,... */
    public double[] hull() {
        if (hull == null) {
            double[] nat = new double[24];
            int n = lib.fpv_get_contact_points(h, nat, 8);
            hull = new double[n * 3];
            for (int i = 0; i < n; i++) { // native body (x nose, y right, z up) cm -> yup body (x nose, y up, z right) m
                hull[3 * i] = nat[3 * i] * 0.01; hull[3 * i + 1] = nat[3 * i + 2] * 0.01; hull[3 * i + 2] = nat[3 * i + 1] * 0.01;
            }
        }
        return hull;
    }

    public boolean resting() { return lib.fpv_is_resting(h) != 0; }

    public void param(int p, double v) { lib.fpv_set_param(h, p, v); }

    public FpvNative.Rates getRates() { lib.fpv_get_rates(h, rates); return rates; }
    public void applyRates() { lib.fpv_set_rates(h, rates); }

    @Override public void close() { if (h != null) { lib.fpv_destroy(h); h = null; } }
}
