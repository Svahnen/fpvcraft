package dev.fpvcore.mc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.fpvcore.mc.Session;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /** Step the drone once per rendered frame, before the camera is set up (variable dt). */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void fpv$frame(net.minecraft.client.DeltaTracker dt, CallbackInfo ci) {
        Session.I.frame(Minecraft.getInstance());
    }

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void fpv$fov(Camera camera, float pt, boolean useConfig, CallbackInfoReturnable<Double> cir) {
        if (Session.I.cameraActive()) cir.setReturnValue(Session.I.verticalFov(Minecraft.getInstance()));
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void fpv$hand(Camera c, float pt, Matrix4f m, CallbackInfo ci) {
        if (Session.I.cameraActive()) ci.cancel();
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void fpv$bob(PoseStack ps, float pt, CallbackInfo ci) {
        if (Session.I.cameraActive()) ci.cancel();
    }
}
