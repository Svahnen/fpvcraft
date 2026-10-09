package dev.fpvcore.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;

/** Renders other pilots' drones (and our own when the local sim is not drawing it): pose interpolated between the last two server ticks. */
public class DroneEntityRenderer extends EntityRenderer<DroneEntity> {
    private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    private long lastNs;

    public DroneEntityRenderer(EntityRendererProvider.Context ctx) { super(ctx); }

    @Override public ResourceLocation getTextureLocation(DroneEntity e) { return WHITE; }

    @Override
    public void render(DroneEntity e, float yaw, float pt, PoseStack ps, MultiBufferSource mb, int light) {
        Minecraft mc = Minecraft.getInstance();
        var owner = e.owner().orElse(null);
        // the local pilot's own drone is drawn from the local (higher rate) simulation
        if (owner != null && mc.player != null && owner.equals(mc.player.getUUID()) && Session.I.drone != null) return;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0 : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        float rpm = e.getEntityData().get(DroneEntity.RPM);
        e.propAngle += (float) (dt * DroneRenderer.spinRevPerSec(rpm, e.armed()) * 2 * Math.PI);
        Quaternionf q = new Quaternionf(e.qPrev).slerp(e.qCur, pt);
        float sc = (float) Math.max(0.1, Session.I.cfg.remoteDroneScale);
        ps.pushPose();
        ps.scale(sc, sc, sc);
        DroneRenderer.drawModel(ps, mb, q, e.armed(), e.propAngle, rpm);
        ps.popPose();
        if (mb instanceof MultiBufferSource.BufferSource bs) bs.endBatch(RenderType.debugFilledBox());
        if (owner != null && mc.getConnection() != null) {
            PlayerInfo pi = mc.getConnection().getPlayerInfo(owner);
            if (pi != null) renderNameTag(e, Component.literal(pi.getProfile().getName()), ps, mb, light, pt);
        }
    }
}
