package dev.fpvcore.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** v1 drone model: simple coloured cuboids (frame, arms, motors, props, camera, nose marker). Original, redistributable. */
public final class DroneRenderer {
    private DroneRenderer() {}

    public static void render(WorldRenderContext ctx) {
        Session S = Session.I;
        if (S.drone == null) return;
        // the FPV camera sits on the drone: do not draw the model around it
        if (S.cameraActive() && !S.thirdPerson()) return;
        FpvNative.State s = S.drone.state();
        Vec3 cam = ctx.camera().getPosition();
        PoseStack ps = ctx.matrixStack();
        MultiBufferSource mb = ctx.consumers();
        if (ps == null || mb == null) return;
        ps.pushPose();
        ps.translate(s.pos[0] - cam.x, s.pos[1] - cam.y, s.pos[2] - cam.z);
        if (dev.fpvcore.mc.demo.DemoHooks.ACTIVE && dev.fpvcore.mc.demo.DemoHooks.droneScale != 1f) { float k = dev.fpvcore.mc.demo.DemoHooks.droneScale; ps.scale(k, k, k); }
        // yup body frame: +X nose, +Y up, +Z right. The body quaternion is already handedness-corrected.
        double rpm = (s.rpm[0] + s.rpm[1] + s.rpm[2] + s.rpm[3]) / 4;
        S.propAngle += (float) (S.lastDt * spinRevPerSec(rpm, s.armed != 0) * 2 * Math.PI);
        drawModel(ps, mb, new Quaternionf((float) s.quat[0], (float) s.quat[1], (float) s.quat[2], (float) s.quat[3]), s.armed != 0, S.propAngle, rpm);
        ps.popPose();
        if (mb instanceof MultiBufferSource.BufferSource bs) bs.endBatch(RenderType.debugFilledBox());
    }

    /** Visual propeller revolutions per second (kept low enough not to alias badly at 60 fps). */
    public static double spinRevPerSec(double rpm, boolean armed) { return armed ? 2 + 7 * Math.min(1, rpm / 25000.0) : 0; }

    /** Draws the drone model at the current origin of the pose stack (centre of the drone), orientation q (body frame -> world). */
    public static void drawModel(PoseStack ps, MultiBufferSource mb, Quaternionf q, boolean armedFlag, float propAngle, double rpm) {
        ps.pushPose();
        ps.mulPose(q);
        float armed = armedFlag ? 1f : 0.35f;
        float norm = (float) Math.min(1, rpm / 25000.0);
        box(ps, mb, -0.055, -0.018, -0.032, 0.055, 0.018, 0.032, 0.12f, 0.12f, 0.14f, 1f);          // body
        box(ps, mb, -0.035, 0.018, -0.022, 0.035, 0.030, 0.022, 0.9f, 0.55f * armed + 0.1f, 0.05f, 1f); // battery strap/top
        box(ps, mb, 0.055, -0.008, -0.012, 0.075, 0.016, 0.012, 0.05f, 0.05f, 0.06f, 1f);          // camera (front)
        box(ps, mb, 0.075, -0.004, -0.004, 0.090, 0.004, 0.004, 1f, 0.1f, 0.1f, 1f);                 // red nose marker
        for (int i = 0; i < 4; i++) {
            float sx = (i & 1) == 0 ? 1 : -1, sz = (i & 2) == 0 ? 1 : -1;
            double mx = 0.07 * sx, mz = 0.07 * sz;
            ps.pushPose();
            ps.mulPose(new Quaternionf().rotateY((float) Math.toRadians(sx * sz > 0 ? -45 : 45)));
            box(ps, mb, -0.095, -0.006, -0.006, 0.095, 0.006, 0.006, 0.20f, 0.20f, 0.22f, 1f);      // arm (diagonals)
            ps.popPose();
            box(ps, mb, mx - 0.014, 0.006, mz - 0.014, mx + 0.014, 0.026, mz + 0.014, 0.35f, 0.35f, 0.4f, 1f); // motor
            if (armedFlag) box(ps, mb, mx - 0.065, 0.026, mz - 0.065, mx + 0.065, 0.029, mz + 0.065, 0.7f, 0.85f, 1f, 0.10f + 0.25f * norm); // prop disc (blur)
            ps.pushPose();                                                                              // two blades, spinning (counter-rotating pairs)
            ps.translate(mx, 0.0285, mz);
            ps.mulPose(new Quaternionf().rotateY(propAngle * (sx * sz > 0 ? 1 : -1) + i));
            float ba = armedFlag ? 0.95f - 0.6f * norm : 0.9f;
            box(ps, mb, -0.065, -0.002, -0.006, 0.065, 0.002, 0.006, 0.75f, 0.9f, 1f, ba);
            ps.popPose();
        }
        ps.popPose();
    }

    static void box(PoseStack ps, MultiBufferSource mb, double x1, double y1, double z1, double x2, double y2, double z2,
                            float r, float g, float b, float a) {
        DebugRenderer.renderFilledBox(ps, mb, x1, y1, z1, x2, y2, z2, r, g, b, a);
    }
}
