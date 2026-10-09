package dev.fpvcore.mc;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C: the server acknowledges that it started a flight for this pilot (chunks now follow the drone). Drives the LINKED / CLIENT-ONLY OSD indicator. */
public record LinkPayload(boolean active) implements CustomPacketPayload {
    public static final Type<LinkPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fpvcore", "link"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LinkPayload> CODEC = CustomPacketPayload.codec((p, b) -> b.writeBoolean(p.active), b -> new LinkPayload(b.readBoolean()));
    @Override public Type<LinkPayload> type() { return TYPE; }
}
