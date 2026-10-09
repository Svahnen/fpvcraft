package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Flights;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The hidden, drone-following pilot is never a mob target (not "seen by anyone", like a spectator) and can not be hit by projectiles / clicks. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "canBeSeenByAnyone", at = @At("HEAD"), cancellable = true)
    private void fpv$unseen(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer p && Flights.isActive(p.getUUID())) cir.setReturnValue(false);
    }

    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
    private void fpv$pickable(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer p && Flights.isActive(p.getUUID())) cir.setReturnValue(false);
    }
}
