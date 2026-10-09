package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.demo.DemoHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Demo mode only: feeds the game's DeltaTracker a virtual clock (one video frame per rendered frame) so ticks/partial ticks are deterministic. */
@Mixin(targets = "net.minecraft.client.DeltaTracker$Timer")
public abstract class DemoTimerMixin {
    @ModifyVariable(method = "advanceTime", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private long fpv$clock(long millis) { return DemoHooks.ACTIVE ? DemoHooks.nextClockMs() : millis; }
}
