package dev.fpvcore.mc;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common (client + dedicated server) entrypoint: entities, network payloads and the server-side flight manager. */
public class FpvMod implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("fpvcore");

    @Override
    public void onInitialize() {
        ModEntities.init();
        FabricDefaultAttributeRegistry.register(ModEntities.PILOT, PilotBodyEntity.attributes());
        FabricDefaultAttributeRegistry.register(ModEntities.DRONE, DroneEntity.attributes());
        PayloadTypeRegistry.playC2S().register(PosePayload.TYPE, PosePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(EndPayload.TYPE, EndPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(LinkPayload.TYPE, LinkPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PosePayload.TYPE, (payload, ctx) -> Flights.onPose(ctx.player(), payload));
        Flights.init();
        LOG.info("fpvcore common init (server-side drone sync + chunk following enabled)");
    }
}
