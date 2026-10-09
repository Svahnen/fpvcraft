package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Session;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the camera is on the drone the whole vanilla HUD is hidden (crosshair, hotbar, health / hunger / armor / air, XP, mount health, item name, effect icons,
 * vignette, pumpkin / powder snow / portal / spyglass overlays); only our OSD, chat, the debug screen and the tab list stay.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = {"renderCrosshair", "renderHotbarAndDecorations", "renderExperienceLevel", "renderEffects", "renderCameraOverlays", "renderSleepOverlay", "renderVehicleHealth"},
        at = @At("HEAD"), cancellable = true)
    private void fpv$hud(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) { if (Session.I.cameraActive()) ci.cancel(); }

    @Inject(method = "renderSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void fpv$item(GuiGraphics g, CallbackInfo ci) { if (Session.I.cameraActive()) ci.cancel(); }

    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void fpv$vignette(GuiGraphics g, Entity e, CallbackInfo ci) { if (Session.I.cameraActive()) ci.cancel(); }

    @Inject(method = {"renderPortalOverlay", "renderSpyglassOverlay"}, at = @At("HEAD"), cancellable = true)
    private void fpv$overlay(GuiGraphics g, float f, CallbackInfo ci) { if (Session.I.cameraActive()) ci.cancel(); }

    @Inject(method = "renderTextureOverlay", at = @At("HEAD"), cancellable = true)
    private void fpv$tex(GuiGraphics g, ResourceLocation r, float f, CallbackInfo ci) { if (Session.I.cameraActive()) ci.cancel(); }
}
