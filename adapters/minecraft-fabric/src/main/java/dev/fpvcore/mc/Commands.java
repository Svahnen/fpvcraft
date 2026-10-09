package dev.fpvcore.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /fpv ... client commands. */
public final class Commands {
    private Commands() {}

    private static void say(FabricClientCommandSource src, String s) { src.sendFeedback(Component.literal("[fpv] " + s)); }

    private static void listControllers(FabricClientCommandSource src) {
        Session S = Session.I;
        S.ctl.poll(S.cfg);
        var devs = S.ctl.devices();
        if (devs.isEmpty()) say(src, "no controller detected; connect the transmitter in USB Joystick/HID mode (using the keyboard)");
        for (int i = 0; i < devs.size(); i++) {
            Controller.Dev d = devs.get(i);
            say(src, (d.jid == S.ctl.jid ? "* " : "  ") + (i + 1) + ": " + d.label());
        }
        say(src, S.ctl.connected() ? "active: " + S.ctl.name : "active: keyboard   (/fpv controller use <n> | keyboard, or press K)");
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        var mc = Minecraft.getInstance();
        Session S = Session.I;
        d.register(literal("fpv")
            .then(literal("spawn").executes(c -> { S.spawn(mc, S.cfg.preset); say(c.getSource(), "spawned " + S.cfg.preset); return 1; }))
            .then(literal("launch").executes(c -> { S.spawn(mc, S.cfg.preset); S.setFlying(mc, true); say(c.getSource(), "flying (arm to start: throttle low + ARM)"); return 1; }))
            .then(literal("fly").executes(c -> { S.setFlying(mc, true); return 1; }))
            .then(literal("land").executes(c -> { S.land(mc); say(c.getSource(), "landed: drone removed, back at the launch spot"); return 1; }))
            .then(literal("return").executes(c -> {
                if (S.flying) { S.land(mc); say(c.getSource(), "drone returned: FPV off, back at the launch spot"); }
                else { S.placeAtPlayer(mc); say(c.getSource(), "drone returned to you"); }
                return 1; }))
            .then(literal("controller")
                .executes(c -> { listControllers(c.getSource()); return 1; })
                .then(literal("list").executes(c -> { listControllers(c.getSource()); return 1; }))
                .then(literal("keyboard").executes(c -> { S.ctl.select(S.cfg, null); say(c.getSource(), "keyboard only (Z arm, X mode)"); return 1; }))
                .then(literal("use").then(argument("n", integer(1, 16)).executes(c -> {
                    var devs = S.ctl.devices();
                    int n = integer0(c, "n");
                    if (n > devs.size()) { say(c.getSource(), "no controller #" + n + " (see /fpv controller list)"); return 0; }
                    Controller.Dev dv = devs.get(n - 1);
                    S.ctl.select(S.cfg, dv);
                    say(c.getSource(), "using " + dv.name + (S.cfg.mapping.calibrated ? "" : " (not calibrated yet: press K)"));
                    return 1; }))))
            .then(literal("chase").executes(c -> { S.cycleView(mc); say(c.getSource(), Session.VIEWS[S.view]); return 1; }))
            .then(literal("calibrate").executes(c -> { mc.execute(() -> mc.setScreen(new CalibrationScreen())); return 1; }))
            .then(literal("preset").then(argument("id", StringArgumentType.word())
                .suggests((ctx, b) -> { for (String p : Drone.PRESETS) b.suggest(p); return b.buildFuture(); })
                .executes(c -> {
                    String id = StringArgumentType.getString(c, "id");
                    try { boolean was = S.flying; S.spawn(mc, id); S.setFlying(mc, was); say(c.getSource(), "preset " + id); }
                    catch (RuntimeException e) { say(c.getSource(), "unknown preset " + id + " (" + String.join(", ", Drone.PRESETS) + ")"); }
                    return 1; })))
            .then(literal("rates")
                .then(literal("type").then(argument("t", integer(0, 4)).executes(c -> {
                    S.cfg.ratesType = integer0(c, "t"); S.applyConfig(); S.cfg.save();
                    say(c.getSource(), "rate type " + S.cfg.ratesType + " (0 Betaflight, 1 Actual, 2 Raceflight, 3 Kiss, 4 Quickrate)"); return 1; })))
                .then(literal("set").then(argument("rc", DoubleArgumentType.doubleArg(0, 5000)).then(argument("srate", DoubleArgumentType.doubleArg(0, 5000))
                    .then(argument("expo", DoubleArgumentType.doubleArg(0, 1)).executes(c -> {
                        double rc = DoubleArgumentType.getDouble(c, "rc"), sr = DoubleArgumentType.getDouble(c, "srate"), ex = DoubleArgumentType.getDouble(c, "expo");
                        S.cfg.rc = new double[]{rc, rc, rc}; S.cfg.srate = new double[]{sr, sr, sr}; S.cfg.expo = new double[]{ex, ex, ex};
                        S.applyConfig(); S.cfg.save(); say(c.getSource(), String.format("rates rc=%.2f srate=%.2f expo=%.2f (all axes)", rc, sr, ex)); return 1; })))))
                .then(literal("throttle").then(argument("mid", DoubleArgumentType.doubleArg(0, 1)).then(argument("expo", DoubleArgumentType.doubleArg(0, 1)).executes(c -> {
                    S.cfg.thrMid = DoubleArgumentType.getDouble(c, "mid"); S.cfg.thrExpo = DoubleArgumentType.getDouble(c, "expo");
                    S.applyConfig(); S.cfg.save(); say(c.getSource(), "throttle mid/expo set"); return 1; }))))
                .then(literal("default").executes(c -> { S.cfg.ratesType = null; S.cfg.rc = S.cfg.srate = S.cfg.expo = null; S.cfg.thrMid = S.cfg.thrExpo = null;
                    S.spawn(mc, S.cfg.preset); say(c.getSource(), "rates reset (drone respawned)"); return 1; })))
            .then(literal("fov").then(argument("deg", DoubleArgumentType.doubleArg(60, 170)).executes(c -> {
                S.cfg.fovHorizontal = DoubleArgumentType.getDouble(c, "deg"); S.cfg.save(); say(c.getSource(), "horizontal FOV " + S.cfg.fovHorizontal); return 1; })))
            .then(literal("uptilt").then(argument("deg", DoubleArgumentType.doubleArg(0, 80)).executes(c -> {
                S.cfg.uptiltDeg = DoubleArgumentType.getDouble(c, "deg"); S.applyConfig(); S.cfg.save(); say(c.getSource(), "camera uptilt " + S.cfg.uptiltDeg); return 1; })))
            .then(literal("battery").then(argument("on", StringArgumentType.word()).executes(c -> {
                S.cfg.simulateBattery = StringArgumentType.getString(c, "on").equals("on"); S.applyConfig(); S.cfg.save(); say(c.getSource(), "battery sim " + S.cfg.simulateBattery); return 1; })))
            .then(literal("volume").then(argument("v", DoubleArgumentType.doubleArg(0, 1)).executes(c -> {
                S.cfg.droneVolume = DoubleArgumentType.getDouble(c, "v"); S.cfg.save(); say(c.getSource(), "drone volume " + S.cfg.droneVolume); return 1; })))
            .then(literal("info").executes(c -> {
                FpvNative.State s = S.drone != null ? S.drone.state() : null;
                say(c.getSource(), "fpvcore " + FpvNative.get().fpv_version() + ", preset " + S.cfg.preset + ", controller: " + (S.ctl.connected() ? S.ctl.name + " (" + S.ctl.axes.length + " axes)" : "none (keyboard)"));
                if (s != null) say(c.getSource(), String.format("pos %.1f %.1f %.1f  speed %.1f m/s  armed %d  mode %d  frames %d", s.pos[0], s.pos[1], s.pos[2], S.fpvStateSpeed, s.armed, s.mode, S.frames));
                return 1; })));
    }

    private static int integer0(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> c, String n) {
        return com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, n);
    }
}
