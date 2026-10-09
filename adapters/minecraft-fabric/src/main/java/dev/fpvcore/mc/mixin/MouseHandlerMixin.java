package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Session;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While flying, the mouse does not turn the player. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void fpv$turn(double dt, CallbackInfo ci) {
        if (Session.I.flying) { accumulatedDX = 0; accumulatedDY = 0; ci.cancel(); }
    }
}
