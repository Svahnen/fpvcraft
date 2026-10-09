package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Session;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No boss bars while flying (part of hiding the vanilla HUD). */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void fpv$boss(GuiGraphics g, CallbackInfo ci) { if (Session.I.cameraActive()) ci.cancel(); }
}
