package dev.fpvcore.mc;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Stand-in for the pilot's body at the launch spot while the real player entity follows the drone. Not saved; damage is passed to the pilot. */
public class PilotBodyEntity extends LivingEntity {
    public static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(PilotBodyEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    public PilotBodyEntity(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    public static AttributeSupplier.Builder attributes() { return LivingEntity.createLivingAttributes(); }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(OWNER, Optional.empty());
    }

    public Optional<UUID> owner() { return entityData.get(OWNER); }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && !isRemoved()) Flights.onBodyHurt(this, source, amount);
        return false;
    }

    @Override public Iterable<ItemStack> getArmorSlots() { return List.of(); }
    @Override public ItemStack getItemBySlot(EquipmentSlot s) { return ItemStack.EMPTY; }
    @Override public void setItemSlot(EquipmentSlot s, ItemStack st) {}
    @Override public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override public void push(double x, double y, double z) {}
    @Override public void push(net.minecraft.world.entity.Entity e) {}
    @Override public boolean isAffectedByPotions() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean isPickable() { return true; }
    @Override public boolean attackable() { return true; }
}
