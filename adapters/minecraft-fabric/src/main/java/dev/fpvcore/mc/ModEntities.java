package dev.fpvcore.mc;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    private ModEntities() {}

    public static final EntityType<DroneEntity> DRONE = Registry.register(BuiltInRegistries.ENTITY_TYPE,
        ResourceLocation.fromNamespaceAndPath("fpvcore", "drone"),
        EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC).sized(0.3f, 0.2f).clientTrackingRange(16).updateInterval(1)
            .noSave().noSummon().build("fpvcore:drone"));

    public static final EntityType<PilotBodyEntity> PILOT = Registry.register(BuiltInRegistries.ENTITY_TYPE,
        ResourceLocation.fromNamespaceAndPath("fpvcore", "pilot_body"),
        EntityType.Builder.<PilotBodyEntity>of(PilotBodyEntity::new, MobCategory.MISC).sized(0.6f, 1.8f).clientTrackingRange(10).updateInterval(20)
            .noSave().noSummon().build("fpvcore:pilot_body"));

    public static void init() {}
}
