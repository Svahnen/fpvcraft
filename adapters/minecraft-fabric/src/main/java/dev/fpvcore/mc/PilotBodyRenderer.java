package dev.fpvcore.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

/** Pilot stand-in: a player model with the pilot's skin. Drawn for everybody including the pilot (the pilot's own player entity travels with the drone, so this is the only body at the launch spot). */
public class PilotBodyRenderer extends LivingEntityRenderer<PilotBodyEntity, PlayerModel<PilotBodyEntity>> {
    private final PlayerModel<PilotBodyEntity> wide, slim;

    public PilotBodyRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        wide = model;
        slim = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
    }

    private static PlayerSkin skin(PilotBodyEntity e) {
        var c = Minecraft.getInstance().getConnection();
        PlayerInfo pi = c == null ? null : e.owner().map(c::getPlayerInfo).orElse(null);
        return pi != null ? pi.getSkin() : DefaultPlayerSkin.get(e.getUUID());
    }

    @Override public ResourceLocation getTextureLocation(PilotBodyEntity e) { return skin(e).texture(); }

    @Override
    public void render(PilotBodyEntity e, float yaw, float pt, PoseStack ps, MultiBufferSource mb, int light) {
        model = skin(e).model() == PlayerSkin.Model.SLIM ? slim : wide;
        super.render(e, yaw, pt, ps, mb, light);
    }
}
