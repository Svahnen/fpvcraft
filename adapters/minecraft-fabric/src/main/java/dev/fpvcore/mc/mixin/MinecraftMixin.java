package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Session;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No block breaking / placing / attacking from the pilot's (away) body while flying. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    /** While flying, the vanilla Toggle Perspective key cycles the drone camera (FPV / behind / in front) instead of the player's camera. */
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void fpv$perspective(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (Session.I.flying && mc.player != null) while (mc.options.keyTogglePerspective.consumeClick()) Session.I.cycleView(mc);
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void fpv$attack(CallbackInfoReturnable<Boolean> cir) { if (Session.I.flying) cir.setReturnValue(false); }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void fpv$use(CallbackInfo ci) { if (Session.I.flying) ci.cancel(); }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void fpv$cont(boolean b, CallbackInfo ci) { if (Session.I.flying) ci.cancel(); }
}
