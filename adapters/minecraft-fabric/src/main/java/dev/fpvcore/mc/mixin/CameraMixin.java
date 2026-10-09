package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Session;
import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean detached;
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow @org.spongepowered.asm.mixin.Final private Quaternionf rotation;
    @Shadow @org.spongepowered.asm.mixin.Final private Vector3f forwards;
    @Shadow @org.spongepowered.asm.mixin.Final private Vector3f up;
    @Shadow @org.spongepowered.asm.mixin.Final private Vector3f left;
    @Shadow protected abstract void setPosition(Vec3 v);

    @Inject(method = "setup", at = @At("TAIL"))
    private void fpv$setup(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float pt, CallbackInfo ci) {
        Session S = Session.I;
        if (!S.cameraActive()) return;
        Quaternionf q = S.camQ;
        setPosition(new Vec3(S.camX, S.camY, S.camZ));
        rotation.set(q);
        forwards.set(0, 0, -1).rotate(q);
        up.set(0, 1, 0).rotate(q);
        left.set(-1, 0, 0).rotate(q);
        xRot = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, forwards.y))));
        yRot = (float) Math.toDegrees(Math.atan2(-forwards.x, forwards.z));
        this.detached = true; // makes the player model visible (we are away from it)
    }
}
