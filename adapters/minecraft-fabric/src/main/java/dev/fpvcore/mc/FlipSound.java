package dev.fpvcore.mc;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Quaternionf;

/** Rotation whoosh for the pilot's own drone: volume and pitch follow the drone's angular rate (flips and rolls). */
public class FlipSound extends AbstractTickableSoundInstance {
    private final MotorModel m;
    private final Quaternionf last = new Quaternionf();
    private boolean have;
    private float rate;   // smoothed deg/s

    public FlipSound(MotorModel m) {
        super(DroneAudio.event("flip"), SoundSource.PLAYERS, RandomSource.create());
        this.m = m;
        looping = true; delay = 0; volume = 0; pitch = 1f;
        relative = true; attenuation = SoundInstance.Attenuation.NONE;
    }

    @Override public boolean canStartSilent() { return true; }

    @Override
    public void tick() {
        if (m.e.isRemoved()) { stop(); return; }
        Quaternionf q = m.e.qCur;
        if (have) {
            float dot = Math.min(1f, Math.abs(last.dot(q)));
            float degPerS = (float) Math.toDegrees(2.0 * Math.acos(dot)) / 0.05f;
            rate += (degPerS - rate) * 0.3f;
        }
        last.set(q); have = true;
        float k = Mth.clamp((rate - 250f) / 900f, 0f, 1f);
        pitch = Mth.clamp(0.8f + 0.7f * k, 0.5f, 2f);
        volume = m.armed ? Mth.clamp(k * 0.55f * DroneAudio.userVolume(), 0f, 1f) : 0f;
    }
}
