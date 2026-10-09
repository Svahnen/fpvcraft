package dev.fpvcore.mc;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C: the server ended the flight (pilot body hurt, death, dimension change, ...). The client goes back to its body. */
public record EndPayload(String reason) implements CustomPacketPayload {
    public static final Type<EndPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fpvcore", "end"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EndPayload> CODEC = CustomPacketPayload.codec((p, b) -> b.writeUtf(p.reason, 128), b -> new EndPayload(b.readUtf(128)));
    @Override public Type<EndPayload> type() { return TYPE; }
}
