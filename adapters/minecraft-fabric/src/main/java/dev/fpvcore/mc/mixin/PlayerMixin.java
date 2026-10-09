package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Flights;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The hidden, drone-following pilot picks up nothing (items, XP, arrows ...). */
@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "touch", at = @At("HEAD"), cancellable = true)
    private void fpv$touch(Entity e, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer p && Flights.isActive(p.getUUID())) ci.cancel();
    }
}
