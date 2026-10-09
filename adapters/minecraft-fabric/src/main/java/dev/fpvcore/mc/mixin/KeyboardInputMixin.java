package dev.fpvcore.mc.mixin;

import dev.fpvcore.mc.Session;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While flying, the player does not walk. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void fpv$tick(boolean sneaking, float f, CallbackInfo ci) {
        if (!Session.I.flying) return;
        Input in = (Input) (Object) this;
        in.up = in.down = in.left = in.right = in.jumping = in.shiftKeyDown = false;
        in.forwardImpulse = 0; in.leftImpulse = 0;
    }
}
