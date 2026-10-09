package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Flights;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pressure plates and tripwires skip entities that ignore block triggers: the hidden pilot (the drone entity is what triggers them). */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "isIgnoringBlockTriggers", at = @At("HEAD"), cancellable = true)
    private void fpv$triggers(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer p && Flights.isActive(p.getUUID())) cir.setReturnValue(true);
    }
}
