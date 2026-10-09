package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Flights;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** XP orbs do not home in on the hidden pilot. */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
    @Shadow private Player followingPlayer;

    @Inject(method = "tick", at = @At("TAIL"))
    private void fpv$ignore(CallbackInfo ci) {
        if (followingPlayer instanceof ServerPlayer p && Flights.isActive(p.getUUID())) followingPlayer = null;
    }
}
