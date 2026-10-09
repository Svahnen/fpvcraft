package dev.fpvcore.mc;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: drone pose of the pilot (client-authoritative physics). {@code flying} = pilot camera is on the drone (server moves the player body there). */
public record PosePayload(boolean flying, boolean gone, boolean armed, double x, double y, double z, float vx, float vy, float vz,
                          float qx, float qy, float qz, float qw, float rpm) implements CustomPacketPayload {
    public static final Type<PosePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fpvcore", "pose"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PosePayload> CODEC = CustomPacketPayload.codec(PosePayload::write, PosePayload::new);

    PosePayload(RegistryFriendlyByteBuf b) {
        this(read(b));
    }
    private PosePayload(PosePayload o) { this(o.flying, o.gone, o.armed, o.x, o.y, o.z, o.vx, o.vy, o.vz, o.qx, o.qy, o.qz, o.qw, o.rpm); }
    private static PosePayload read(RegistryFriendlyByteBuf b) {
        int fl = b.readByte();
        boolean armed = (b.readByte() & 1) != 0;
        return new PosePayload((fl & 1) != 0, (fl & 2) != 0, armed, b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat(), b.readFloat(),
            b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat());
    }
    /** "My drone is gone" (pilot landed / despawned it): the server ends the flight, restores the pilot and removes the drone entity. */
    static PosePayload goneNow() { return new PosePayload(false, true, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0); }

    void write(RegistryFriendlyByteBuf b) {
        b.writeByte((flying ? 1 : 0) | (gone ? 2 : 0)); b.writeByte(armed ? 1 : 0);
        b.writeDouble(x); b.writeDouble(y); b.writeDouble(z);
        b.writeFloat(vx); b.writeFloat(vy); b.writeFloat(vz);
        b.writeFloat(qx); b.writeFloat(qy); b.writeFloat(qz); b.writeFloat(qw); b.writeFloat(rpm);
    }

    @Override public Type<PosePayload> type() { return TYPE; }

    public boolean valid() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Math.abs(x) < 2.9e7 && Math.abs(z) < 2.9e7 && Math.abs(y) < 1e5
            && Float.isFinite(vx) && Float.isFinite(vy) && Float.isFinite(vz) && Float.isFinite(qx + qy + qz + qw) && Float.isFinite(rpm);
    }
}
