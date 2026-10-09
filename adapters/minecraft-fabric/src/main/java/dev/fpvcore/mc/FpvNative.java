package dev.fpvcore.mc;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** JNA binding of include/fpvcore.h (C ABI). The physics lives only in the native library. */
public final class FpvNative {
    private FpvNative() {}

    @Structure.FieldOrder({"throttle", "yaw", "pitch", "roll", "arm", "mode"})
    public static class Input extends Structure {
        public double throttle, yaw, pitch, roll;
        public int arm, mode;
    }

    @Structure.FieldOrder({"type", "rc", "srate", "expo", "thr_mid", "thr_expo"})
    public static class Rates extends Structure {
        public int type;
        public double[] rc = new double[3], srate = new double[3], expo = new double[3];
        public double thr_mid, thr_expo;
    }

    @Structure.FieldOrder({"pos", "quat", "linvel", "angvel", "rpm", "battery_v", "cell_v", "amps", "mah_used", "thrust",
            "rate_setpoint", "armed", "can_fly", "mode", "time", "pid_quat", "cam_pos", "cam_quat", "cam_forward", "cam_up",
            "cam_uptilt_deg", "cam_fov_deg", "prop_damage", "prop_destroyed"})
    public static class State extends Structure {
        public double[] pos = new double[3], quat = new double[4], linvel = new double[3], angvel = new double[3];
        public double[] rpm = new double[4];
        public double battery_v, cell_v, amps, mah_used, thrust;
        public double[] rate_setpoint = new double[3];
        public int armed, can_fly, mode;
        public double time;
        public double[] pid_quat = new double[4];
        public double[] cam_pos = new double[3], cam_quat = new double[4], cam_forward = new double[3], cam_up = new double[3];
        public double cam_uptilt_deg, cam_fov_deg;
        public double[] prop_damage = new double[4];
        public int prop_destroyed;
    }

    public interface Lib extends Library {
        String fpv_version();
        String fpv_builtin_preset(String id);
        Pointer fpv_create(String presetJson);
        void fpv_destroy(Pointer d);
        String fpv_last_error();
        void fpv_set_input(Pointer d, Input in);
        void fpv_set_rates(Pointer d, Rates r);
        void fpv_get_rates(Pointer d, Rates r);
        void fpv_step(Pointer d, double dt);
        void fpv_get_state(Pointer d, State s);
        void fpv_get_state_yup(Pointer d, State s);
        void fpv_set_state(Pointer d, State s);
        void fpv_reset(Pointer d, double[] posCm, double headingDeg);
        void fpv_set_trace_results(Pointer d, double downCm, double upCm);
        void fpv_set_wind(Pointer d, double[] windCms);
        double fpv_add_contact(Pointer d, double[] normal, double depthCm);
        double fpv_add_contact_ex(Pointer d, double[] pointCm, double[] normal, double depthCm, double friction, double restitution);
        int fpv_get_contact_points(Pointer d, double[] outCm, int max);
        int fpv_is_resting(Pointer d);
        void fpv_report_hit(Pointer d, double[] normal, double impulse);
        void fpv_set_param(Pointer d, int param, double value);
        double fpv_rate(int type, double stick, double rc, double srate, double expo);
        void fpv_pos_native_to_yup(double[] cm, double[] m);
        void fpv_pos_yup_to_native(double[] m, double[] cm);
        void fpv_dir_native_to_yup(double[] d, double[] out);
        void fpv_dir_yup_to_native(double[] d, double[] out);
    }

    public static final int PARAM_MAX_DT = 1, PARAM_RESTITUTION = 2, PARAM_FRICTION = 3, PARAM_CAM_UPTILT_DEG = 4,
            PARAM_CAM_FOV_DEG = 5, PARAM_SIMULATE_BATTERY = 6, PARAM_FREEZE_UNTIL_ARMED = 7, PARAM_GRAVITY = 8,
            PARAM_PID_CAMERA = 9, PARAM_PHYSICS_ACTIVE = 10, PARAM_SEED = 11, PARAM_THRUST_SCALE = 13, PARAM_DRAG_SCALE = 14;

    private static Lib lib;

    public static synchronized Lib get() {
        if (lib == null) lib = load();
        return lib;
    }

    private static Lib load() {
        String override = System.getProperty("fpvcore.native");
        if (override != null && Files.isRegularFile(Path.of(override))) {
            return com.sun.jna.Native.load(override, Lib.class);
        }
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String a = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x86_64";
        String platform, file;
        if (os.contains("win")) { platform = "windows-" + a; file = "fpvcore.dll"; }
        else if (os.contains("mac")) { platform = "macos-" + a; file = "libfpvcore.dylib"; }
        else { platform = "linux-" + a; file = "libfpvcore.so"; }
        String res = "/natives/" + platform + "/" + file;
        try (InputStream in = FpvNative.class.getResourceAsStream(res)) {
            if (in == null) throw new IllegalStateException("fpvcore native library not bundled for " + platform + " (" + res + ")");
            Path dir = Files.createTempDirectory("fpvcore-native");
            Path out = dir.resolve(file);
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            out.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return com.sun.jna.Native.load(out.toAbsolutePath().toString(), Lib.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot extract fpvcore native library", e);
        }
    }
}
