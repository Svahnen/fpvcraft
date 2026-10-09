package dev.fpvcore.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Drone audio: RPM-layered motor loops, wind, flip whoosh and crash one-shots. All sounds are original, procedurally
 * generated (tools/audio/synth_drone.py, CC0) and bundled in the jar (assets/fpvcore/sounds.json). A resource pack that
 * defines the same `fpvcore:*` events overrides them. See docs/AUDIO.md.
 */
public final class DroneAudio {
    private DroneAudio() {}

    public static final String[] EVENTS = {"motor_1", "motor_2", "motor_3", "motor_4", "motor_5", "motor_6", "wind_low", "wind_high", "flip", "crash", "crash_big", "prop_strike"};

    static ResourceLocation id(String n) { return ResourceLocation.fromNamespaceAndPath("fpvcore", n); }
    static SoundEvent event(String n) { return SoundEvent.createVariableRangeEvent(id(n)); }

    /** User volume (config `droneVolume`, /fpv volume); the vanilla "Players" and master sliders apply on top. */
    public static float userVolume() { return (float) Mth.clamp(Session.I.cfg.droneVolume, 0.0, 1.0); }

    private static final java.util.List<DroneEntity> PENDING = new java.util.ArrayList<>();
    private static final java.util.Map<DroneEntity, Integer> WAIT = new java.util.HashMap<>();

    /** Entity added on the client: wait (max 2 s) until the synched OWNER arrives so we know whether it is ours (non-positional) or not. */
    public static void startFor(DroneEntity e) { PENDING.add(e); }

    /** Called every client tick. */
    public static void tick() {
        if (PENDING.isEmpty()) return;
        for (var it = PENDING.iterator(); it.hasNext(); ) {
            DroneEntity e = it.next();
            int w = WAIT.merge(e, 1, Integer::sum);
            if (e.isRemoved()) { it.remove(); WAIT.remove(e); continue; }
            if (e.owner().isPresent() || w > 40) { it.remove(); WAIT.remove(e); start(e); }
        }
    }

    private static void start(DroneEntity e) {
        var sm = Minecraft.getInstance().getSoundManager();
        FpvClient.LOG.info("drone audio: layered motor sounds for drone of {}", e.owner().orElse(null));
        MotorModel m = new MotorModel(e);
        for (int k = 0; k < MotorModel.LAYERS; k++) sm.play(new MotorLayerSound(m, k));
        if (m.own) { sm.play(new WindSound(m, false)); sm.play(new WindSound(m, true)); sm.play(new FlipSound(m)); }
    }

    /** Collision sound: impulses above 50 are audible; volume scales linearly from impulse 100 (silent) to 2000 (full), with random pitch 0.9..1.1 and volume 0.9..1.4. */
    public static void crash(ClientLevel lvl, double x, double y, double z, double impulse) {
        float v = (float) Mth.clamp((impulse - 100.0) / 1900.0, 0.0, 1.0);
        if (MotorLayerSound.LOG) FpvClient.LOG.info("AUDIO crash impulse={} vol={}", impulse, v);
        RandomSource r = lvl.random;
        if (v <= 0.01f) {
            // light touch (impulse 50..100): prop scrape / tap, not a crash
            float tv = (float) Mth.clamp((impulse - 50.0) / 50.0, 0.0, 1.0) * 0.6f * userVolume();
            Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                id("prop_strike"), SoundSource.PLAYERS, tv, 0.9f + 0.2f * r.nextFloat(), RandomSource.create(), false, 0,
                net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE, 0, 0, 0, true));
            return;
        }
        float pitch = 0.9f + 0.2f * r.nextFloat();
        float vol = v * (0.9f + 0.5f * r.nextFloat()) * userVolume();
        // non-positional: the pilot is the drone
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
            id(impulse > 1500 ? "crash_big" : "crash"), SoundSource.PLAYERS, Math.min(1f, vol), pitch, RandomSource.create(), false, 0,
            net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }

    /** Linear remap with clamping: lerp(minOut, maxOut, clamp01((x - minIn) / (maxIn - minIn))). */
    static float cm(float x, float minIn, float maxIn, float minOut, float maxOut) {
        float a = Mth.clamp((x - minIn) / (maxIn - minIn), 0f, 1f);
        return minOut + (maxOut - minOut) * a;
    }
}
