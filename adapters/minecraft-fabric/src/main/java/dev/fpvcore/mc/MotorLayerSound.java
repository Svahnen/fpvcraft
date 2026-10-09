package dev.fpvcore.mc;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/** One RPM layer of the motor sound (0..5, low to high rpm), crossfaded by {@link MotorModel}. Non-positional for the pilot, positional at the drone for others. */
public class MotorLayerSound extends AbstractTickableSoundInstance {
    private static final String[] NAMES = {"motor_1", "motor_2", "motor_3", "motor_4", "motor_5", "motor_6"};
    private final MotorModel m;
    private final int layer;
    static final boolean LOG = System.getProperty("fpvcore.autotest") != null;
    private int logN;

    public MotorLayerSound(MotorModel m, int layer) {
        super(DroneAudio.event(NAMES[layer]), SoundSource.PLAYERS, RandomSource.create());
        this.m = m; this.layer = layer;
        looping = true; delay = 0; volume = 0; pitch = 1f;
        if (m.own) { relative = true; attenuation = SoundInstance.Attenuation.NONE; }
        else { attenuation = SoundInstance.Attenuation.LINEAR; x = m.e.getX(); y = m.e.getY(); z = m.e.getZ(); }
    }

    @Override public boolean canStartSilent() { return true; }

    @Override
    public void tick() {
        var e = m.e;
        if (e.isRemoved()) { stop(); return; }
        m.update(e.level().getGameTime());
        if (!m.own) { x = e.getX(); y = e.getY(); z = e.getZ(); }
        pitch = Mth.clamp(m.pitch[layer], 0.5f, 2f);
        float v = m.armed ? m.vol[layer] * MotorModel.GAIN * DroneAudio.userVolume() * (m.own ? 1f : MotorModel.OTHER_DRONE) : 0f;
        volume = Mth.clamp(v, 0f, 1f);
        if (LOG && m.own && logN++ % 20 == 0)
            FpvClient.LOG.info("AUDIO layer={} n={} pitch={} vol={}", layer, m.n, pitch, volume);
    }
}
