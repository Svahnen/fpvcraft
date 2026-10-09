package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Flights;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a pilot flies the server owns the player's position: client movement packets are ignored (no "moved too quickly", no fly kick). */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean clientIsFloating;
    @Shadow private int aboveGroundTickCount;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void fpv$move(ServerboundMovePlayerPacket p, CallbackInfo ci) {
        if (Flights.isActive(player.getUUID())) ci.cancel();
    }

    /** Someone else (/tp by an op ...) moves the flying player: the flight ends where the player is sent (our own return teleport runs after the flight is inactive). */
    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"))
    private void fpv$teleport(double x, double y, double z, float yr, float xr, java.util.Set<?> rel, CallbackInfo ci) {
        if (Flights.isActive(player.getUUID())) Flights.onExternalTeleport(player);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void fpv$tick(CallbackInfo ci) {
        if (Flights.isActive(player.getUUID())) { clientIsFloating = false; aboveGroundTickCount = 0; }
    }
}
