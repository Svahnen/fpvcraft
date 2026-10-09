package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.demo.DemoHooks;
import dev.fpvcore.mc.demo.DemoRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Demo mode only (inert unless -Dfpvcore.demo is set): frame capture at the end of each rendered frame. */
@Mixin(Minecraft.class)
public abstract class DemoMixin {
    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void fpv$capture(boolean renderLevel, CallbackInfo ci) {
        if (DemoHooks.ACTIVE) DemoRecorder.onFrameRendered((Minecraft) (Object) this);
    }
}
