package dev.fpvcore.mc;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FpvClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("fpvcore");
    static final String CAT = "key.categories.fpvcore";
    static KeyMapping fly, spawn, ret, calib, preset, arm, mode;

    private static KeyMapping key(String id, int k) { return KeyBindingHelper.registerKeyBinding(new KeyMapping("key.fpvcore." + id, InputConstants.Type.KEYSYM, k, CAT)); }

    @Override
    public void onInitializeClient() {
        LOG.info("fpvcore-fabric loading, native fpvcore {}", FpvNative.get().fpv_version());
        fly = key("fly", GLFW.GLFW_KEY_G);
        spawn = key("spawn", GLFW.GLFW_KEY_H);
        ret = key("return", GLFW.GLFW_KEY_J);
        calib = key("calibrate", GLFW.GLFW_KEY_K);
        preset = key("preset", GLFW.GLFW_KEY_COMMA);
        arm = key("arm", GLFW.GLFW_KEY_Z);
        mode = key("mode", GLFW.GLFW_KEY_X);

        EntityRendererRegistry.register(ModEntities.DRONE, DroneEntityRenderer::new);
        EntityRendererRegistry.register(ModEntities.PILOT, PilotBodyRenderer::new);
        DroneEntity.onClientAdded = DroneAudio::startFor;
        ClientPlayNetworking.registerGlobalReceiver(LinkPayload.TYPE, (p, ctx) -> Session.I.serverLinked(p.active()));
        ClientPlayNetworking.registerGlobalReceiver(EndPayload.TYPE, (p, ctx) -> Session.I.serverEnded(ctx.client(), p.reason()));
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> Commands.register(d));
        HudRenderCallback.EVENT.register(Osd::render);
        WorldRenderEvents.AFTER_ENTITIES.register(DroneRenderer::render);
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> { Session.I.destroy(); DroneEntity.CLIENT.clear(); });
        ClientTickEvents.END_CLIENT_TICK.register(FpvClient::tick);
        if (dev.fpvcore.mc.demo.DemoHooks.ACTIVE) dev.fpvcore.mc.demo.DemoDirector.init();
        if (System.getProperty("fpvcore.autotest") != null) ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
    }

    private static void tick(Minecraft mc) {
        Session S = Session.I;
        DroneAudio.tick();
        S.ctl.scan(mc, S.cfg);
        if (mc.player == null) return;
        while (fly.consumeClick()) {
            if (S.flying) { S.land(mc); S.msg(mc, "FPV off: drone removed, back at the launch spot"); }
            else { S.setFlying(mc, true); S.msg(mc, "FPV on (G again = exit and land)"); }
        }
        while (spawn.consumeClick()) { S.spawn(mc, S.cfg.preset); S.msg(mc, "drone placed (" + S.cfg.preset + ")"); }
        while (ret.consumeClick()) {
            if (S.flying) { S.land(mc); S.msg(mc, "drone returned: FPV off"); }
            else { S.placeAtPlayer(mc); S.msg(mc, "drone returned"); }
        }
        while (calib.consumeClick()) mc.setScreen(new CalibrationScreen());
        while (preset.consumeClick()) {
            int i = java.util.Arrays.asList(Drone.PRESETS).indexOf(S.cfg.preset);
            String next = Drone.PRESETS[(i + 1) % Drone.PRESETS.length];
            boolean was = S.flying;
            S.spawn(mc, next); S.setFlying(mc, was); S.msg(mc, "preset " + next);
        }
        while (arm.consumeClick()) { S.ctl.kbArm = !S.ctl.kbArm; S.msg(mc, S.ctl.kbArm ? "keyboard arm ON" : "keyboard arm OFF"); }
        while (mode.consumeClick()) { S.ctl.kbMode = S.ctl.kbMode % 3 + 1; S.msg(mc, "mode " + Osd.MODES[S.ctl.kbMode]); }
        if (S.flying && (mc.player.isDeadOrDying() || mc.level == null)) S.land(mc);
    }
}
