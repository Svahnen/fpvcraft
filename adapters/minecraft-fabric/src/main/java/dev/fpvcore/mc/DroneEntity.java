package dev.fpvcore.mc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pilot's drone as seen by everyone. The server moves it (position via vanilla entity tracking, orientation / rpm / flags via synched data),
 * clients interpolate between the last two ticks. Never saved.
 */
public class DroneEntity extends LivingEntity {
    public static final EntityDataAccessor<Quaternionf> QUAT = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.QUATERNION);
    public static final EntityDataAccessor<Float> RPM = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Byte> FLAGS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE); // 1 armed, 2 pilot flying
    public static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** Client side: owner uuid -> drone entity (used to hide the pilot's own player model while it is "away"). */
    public static final Map<UUID, DroneEntity> CLIENT = new ConcurrentHashMap<>();

    // client interpolation state
    public final Quaternionf qPrev = new Quaternionf(), qCur = new Quaternionf();
    public float propAngle;
    public boolean clientInit;
    /** Set by the client entrypoint: starts the motor sound. */
    public static java.util.function.Consumer<DroneEntity> onClientAdded = e -> {};

    public DroneEntity(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    public static AttributeSupplier.Builder attributes() { return LivingEntity.createLivingAttributes(); }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(QUAT, new Quaternionf());
        b.define(RPM, 0f);
        b.define(FLAGS, (byte) 0);
        b.define(OWNER, Optional.empty());
    }

    public Optional<UUID> owner() { return entityData.get(OWNER); }
    public boolean armed() { return (entityData.get(FLAGS) & 1) != 0; }
    public boolean pilotFlying() { return (entityData.get(FLAGS) & 2) != 0; }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            Quaternionf q = entityData.get(QUAT);
            if (!clientInit) { qPrev.set(q); clientInit = true; onClientAdded.accept(this); } else qPrev.set(qCur);
            qCur.set(q);
            owner().ifPresent(u -> CLIENT.put(u, this));
        } else if (isAlive()) {
            checkInsideBlocks(); // pressure plates, tripwires, fire, cactus ... (the entity is only ever teleported with setPos, which never does this)
        }
    }

    /** Server: what destroys the drone (and so ends the flight): mobs, explosions, fire / lava. Players can not hit it; water, falls, suffocation do nothing. */
    static String destroyReason(DamageSource s) {
        if (s.is(DamageTypeTags.IS_EXPLOSION)) return "explosion";
        if (s.is(DamageTypeTags.IS_FIRE)) return s.is(net.minecraft.world.damagesource.DamageTypes.LAVA) ? "lava" : "fire";
        if (s.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return "removed";
        var src = s.getEntity();
        if (src instanceof Player || s.getDirectEntity() instanceof Player) return null;
        if (src instanceof LivingEntity) return "mob";
        if (s.getDirectEntity() != null && s.is(DamageTypeTags.IS_PROJECTILE)) return "projectile";
        return null;
    }

    @Override
    public boolean hurt(DamageSource s, float a) {
        if (level().isClientSide || isRemoved()) return false;
        String why = destroyReason(s);
        if (why == null) return false;
        Flights.onDroneDestroyed(this, why);
        return true;
    }

    /** The bounding box is centred on the drone's centre (the simulation position), so a drone resting on a pressure plate overlaps it. */
    @Override protected AABB makeBoundingBox() {
        double x = getX(), y = getY(), z = getZ();
        return new AABB(x - 0.15, y - 0.1, z - 0.15, x + 0.15, y + 0.1, z + 0.15);
    }

    /** Server tracking moves are applied immediately (the renderer interpolates between ticks); LivingEntity's own lerp steps would add latency. */
    @Override public void lerpTo(double x, double y, double z, float yr, float xr, int steps) { setPos(x, y, z); setRot(yr, xr); }
    @Override public void aiStep() {}
    @Override public boolean isPickable() { return true; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override public boolean isAffectedByPotions() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean canUsePortal(boolean b) { return false; }
    @Override public boolean shouldRenderAtSqrDistance(double d) { return d < 256.0 * 256.0; }
    @Override public Iterable<ItemStack> getArmorSlots() { return List.of(); }
    @Override public ItemStack getItemBySlot(EquipmentSlot s) { return ItemStack.EMPTY; }
    @Override public void setItemSlot(EquipmentSlot s, ItemStack st) {}
    @Override public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
    @Override public void readAdditionalSaveData(CompoundTag t) {}
    @Override public void addAdditionalSaveData(CompoundTag t) {}
}
