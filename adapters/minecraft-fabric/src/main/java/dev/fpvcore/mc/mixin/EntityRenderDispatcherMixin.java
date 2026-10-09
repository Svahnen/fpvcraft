package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.DroneEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Another player's (server-side invisible) entity follows their drone; hide it completely (armor, held item, name tag). */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void fpv$hide(E entity, Frustum f, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Player p && p != Minecraft.getInstance().player) {
            DroneEntity d = DroneEntity.CLIENT.get(p.getUUID());
            if (d != null && !d.isRemoved() && d.pilotFlying()) cir.setReturnValue(false);
        }
    }
}
