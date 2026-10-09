package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Flights;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stores the pilot's launch spot in the player data, so a crash / kill mid-flight does not strand the player at the drone position. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void fpv$save(CompoundTag t, CallbackInfo ci) { Flights.save((ServerPlayer) (Object) this, t); }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void fpv$load(CompoundTag t, CallbackInfo ci) { Flights.load((ServerPlayer) (Object) this, t); }
}
