package dev.fpvcore.mc;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Wind noise for the pilot's own drone: the level grows with the square of the airspeed (full at about 120 km/h), split between a low and a
 * high loop; the loop pitch rises from 0.8 to 1.9 with speed.
 */
public class WindSound extends AbstractTickableSoundInstance {
    private final MotorModel m;
    private final float weight;
    private int logN;

    public WindSound(MotorModel m, boolean high) {
        super(DroneAudio.event(high ? "wind_high" : "wind_low"), SoundSource.PLAYERS, RandomSource.create());
        this.m = m; this.weight = high ? 0.10f : 0.23f;
        looping = true; delay = 0; volume = 0; pitch = 1f;
        relative = true; attenuation = SoundInstance.Attenuation.NONE;
    }

    @Override public boolean canStartSilent() { return true; }

    @Override
    public void tick() {
        if (m.e.isRemoved()) { stop(); return; }
        double kmh = Session.I.fpvStateSpeed * 3.6;
        float speed = (float) (kmh * kmh);
        float k = Mth.clamp(speed / 14400f, 0f, 1f);
        pitch = Mth.clamp((0.8f + 1.1f * k), 0.5f, 2f);
        float v = m.armed ? k * weight * DroneAudio.userVolume() : 0f;
        volume = Mth.clamp(v, 0f, 1f);
        if (MotorLayerSound.LOG && logN++ % 20 == 0) FpvClient.LOG.info("AUDIO wind kmh={} pitch={} vol={}", kmh, pitch, volume);
    }
}
