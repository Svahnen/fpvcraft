package dev.fpvcore.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Per-drone state shared by the layer sounds. Maps the synched rpm to the bundled motor layers motor_1..motor_6
 * (tools/audio/synth_drone.py): rpm fraction n -> sound parameter T = tFull(preset) * soundCurve(n) -> pitch and volume
 * of every layer from the generated tables in {@link MotorCurves} (tools/audio/motor_ladder.py). Every layer is a snapshot of the whole
 * drone sound at its anchor T and is only ever played at pitch 0.7..1.4 (well inside Minecraft's 0.5..2.0 clamp); neighbouring layers
 * crossfade equal-power; the level follows a loudness curve (quiet idle).
 */
public final class MotorModel {
    /** Full-throttle motor speed per preset (rpm at 4.0 V/cell, as computed by the fpvcore motor model). */
    public static float rpmMax(String preset) {
        return switch (preset == null ? "" : preset) {
            case "5race" -> 31781f;
            case "5freestyle" -> 28879f;
            case "3micro" -> 36799f;
            case "7longrange" -> 23450f;
            default -> 32000f;
        };
    }
    /** Sound parameter T at full throttle per preset (higher-revving motors sound higher/louder at full throttle; chosen by ear). */
    public static float tFull(String preset) {
        return switch (preset == null ? "" : preset) {
            case "5race" -> 0.64f;
            case "5freestyle" -> 0.55f;
            case "3micro" -> 0.60f;
            case "7longrange" -> 0.46f;
            default -> 0.6f;
        };
    }
    public static final int LAYERS = MotorCurves.LAYERS;
    public static final float GAIN = 0.8f;         // layer files are normalised to rms 0.16; MC volume clamps to 1; 1.0 table volume = level of the 5in-race at full throttle
    public static final float OTHER_DRONE = 0.6f;  // volume factor for other pilots' drones

    public final DroneEntity e;
    public final boolean own;
    final float rpmMax, tFull;
    float n;          // smoothed rpm fraction
    long lastTick = -1;
    public final float[] pitch = new float[LAYERS], vol = new float[LAYERS];
    public boolean armed;

    public MotorModel(DroneEntity e) {
        this.e = e;
        var mc = Minecraft.getInstance();
        own = mc.player != null && e.owner().map(mc.player.getUUID()::equals).orElse(false);
        rpmMax = rpmMax(Session.I.cfg.preset);
        tFull = tFull(Session.I.cfg.preset);
    }

    /** Called once per client tick by whichever layer ticks first. */
    public void update(long gameTime) {
        if (gameTime == lastTick) return;
        float dt = lastTick < 0 ? 0.05f : 0.05f * Math.max(1, gameTime - lastTick);
        lastTick = gameTime;
        armed = e.armed();
        float target = armed ? Mth.clamp(e.getEntityData().get(DroneEntity.RPM) / rpmMax, 0f, 1.1f) : 0f;
        // instant rise, smooth fall
        float interp = n + (target - n) * Mth.clamp(dt * 10f, 0f, 1f);
        n = Math.max(interp, target);
        compute(n, tFull, pitch, vol);
    }

    /** Rpm fraction -> sound parameter scale: a power law (quiet, low-pitched at idle; rises quickly near full throttle). */
    static float soundCurve(float x) {
        return (float) Math.pow(Mth.clamp(x, 0f, 1f), 1.72);
    }

    /** Pure function of the rpm fraction and the preset's T at full throttle: pitch and volume (before GAIN / user volume) of every layer. */
    public static void compute(float n, float tFull, float[] pitch, float[] vol) {
        float t = Mth.clamp(tFull * soundCurve(Math.min(n, 1.1f)), 0f, (MotorCurves.PITCH[0].length - 1) * MotorCurves.T_STEP);
        float f = t / MotorCurves.T_STEP;
        int i = Math.min((int) f, MotorCurves.PITCH[0].length - 2);
        float a = f - i;
        for (int k = 0; k < LAYERS; k++) {
            pitch[k] = Mth.clamp(MotorCurves.PITCH[k][i] * (1f - a) + MotorCurves.PITCH[k][i + 1] * a, 0.5f, 2f);
            vol[k] = MotorCurves.VOL[k][i] * (1f - a) + MotorCurves.VOL[k][i + 1] * a;
        }
    }
}
