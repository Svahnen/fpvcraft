package dev.fpvcore.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.Entity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only multiplayer QA client (-Dfpvcore.autotest=mp -Dfpvcore.cmdfile=<f> -Dfpvcore.user=<name>): executes the lines of the command file (an external driver
 * appends/replaces it), runs scripted flight patterns and logs "MPQA ..." lines (player health / position, drone and body entities seen, disconnect reasons).
 * Commands: shot N, chat C, h, g (key presses), land, pattern NAME [H], pattern off, view N, look drone|body [owner], track drone|body|off, log TAG, qalog on|off,
 * connect HOST:PORT, disconnect, quit, nolog.
 */
final class MpQa {
    private MpQa() {}

    private static int k, pk, phase, phaseT;
    private static String pat = "off", track = "off", trackOwner = "";
    private static double y0;
    private static boolean qalog = true, prevFlying;
    private static float lastHp = -1;
    private static Object lastScreen;
    private static double maxSpeed;

    private static void shot(Minecraft mc, String tag) { Screenshot.grab(mc.gameDirectory, "mp_" + tag + ".png", mc.getMainRenderTarget(), c -> {}); }

    private static Mapping.Sticks st(double thr, double pitch, double yaw, double roll, boolean arm) {
        Mapping.Sticks s = new Mapping.Sticks();
        s.throttle = thr; s.pitch = pitch; s.yaw = yaw; s.roll = roll; s.arm = arm; s.mode = 2; // angle mode
        return s;
    }

    private static double alt(FpvNative.State s, double target) {
        return Math.max(-1, Math.min(0.2, -0.5 + 0.35 * (target - s.pos[1]) - 0.12 * s.linvel[1]));
    }

    private static String name(Entity e) {
        return e instanceof DroneEntity d ? d.owner().map(u -> { var c = Minecraft.getInstance().getConnection(); var pi = c == null ? null : c.getPlayerInfo(u); return pi == null ? u.toString() : pi.getProfile().getName(); }).orElse("?")
            : e instanceof PilotBodyEntity b ? b.owner().map(u -> { var c = Minecraft.getInstance().getConnection(); var pi = c == null ? null : c.getPlayerInfo(u); return pi == null ? u.toString() : pi.getProfile().getName(); }).orElse("?") : "?";
    }

    private static Entity find(Minecraft mc, boolean drone, String owner) {
        Entity best = null; double bd = 1e18;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (drone ? !(e instanceof DroneEntity) : !(e instanceof PilotBodyEntity)) continue;
            if (!owner.isEmpty() && !name(e).equalsIgnoreCase(owner)) continue;
            double d = e.distanceToSqr(mc.player);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    private static String entities(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        int nd = 0, nb = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof DroneEntity d) { nd++; sb.append(String.format(" D[%s (%.0f,%.0f,%.0f) fl=%s]", name(d), d.getX(), d.getY(), d.getZ(), d.pilotFlying())); }
            else if (e instanceof PilotBodyEntity b) { nb++; sb.append(String.format(" B[%s (%.1f,%.1f,%.1f)]", name(b), b.getX(), b.getY(), b.getZ())); }
        }
        return "drones=" + nd + " bodies=" + nb + sb;
    }

    private static void state(Minecraft mc, String tag) {
        Session S = Session.I;
        var p = mc.player;
        FpvNative.State s = S.drone == null ? null : S.drone.state();
        FpvClient.LOG.info("MPQA {} user={} flying={} linked={} waiting={} water={} crashed={} client=({}, {}, {}) hp={} food={} noPhys={} inv={} drone={} v={} maxv={} frames={} armed={} thr={} {}",
            tag, mc.getUser().getName(), S.flying, S.linked, S.waitingChunks, S.inWater, S.crashed,
            String.format("%.1f", p.getX()), String.format("%.1f", p.getY()), String.format("%.1f", p.getZ()), p.getHealth(), p.getFoodData().getFoodLevel(), p.noPhysics, p.isInvisible(),
            s == null ? "-" : String.format("(%.1f, %.1f, %.1f)", s.pos[0], s.pos[1], s.pos[2]), String.format("%.1f", S.fpvStateSpeed), String.format("%.1f", maxSpeed), S.frames, s == null ? "-" : s.armed, String.format("%.2f", S.sticks.throttle), entities(mc));
    }

    private static void runPattern(Minecraft mc) {
        Session S = Session.I;
        if (!S.flying || S.drone == null) { prevFlying = false; return; }
        if (pat.equals("off")) return;
        FpvNative.State s = S.drone.state();
        if (!prevFlying) { prevFlying = true; pk = 0; maxSpeed = 0; y0 = s.pos[1]; } // a new flight: ground altitude and arming warm-up
        pk++;
        double rel = s.pos[1] - y0;
        double h = 20;
        String[] a = pat.split(" ");
        if (a.length > 1) h = Double.parseDouble(a[1]);
        boolean arm = pk > 10;
        if (pk < 20) { S.ctl.override = st(-1, 0, 0, 0, arm); return; }
        switch (a[0]) {
            case "hover" -> S.ctl.override = st(alt(s, y0 + h), 0, 0, 0, true);
            case "fwd" -> S.ctl.override = st(alt(s, y0 + h), 0.45, 0, 0, true); // cruise
            case "race" -> { // full throttle sprints, climbs and dives, slowly turning (stays within a few hundred blocks)
                phaseT++;
                switch (phase) {
                    case 0 -> { S.ctl.override = st(1.0, 0.25, 0.05, 0, true); if (rel > 40 || phaseT > 200) { phase = 1; phaseT = 0; } }              // climb
                    case 1 -> { S.ctl.override = st(-0.4, 1.0, 0.05, 0, true); if (rel < 6 || phaseT > 160) { phase = 2; phaseT = 0; } }                // dive
                    default -> { S.ctl.override = st(Math.max(-0.4, alt(s, y0 + 8) + 0.5), 1.0, 0.05, 0, true); if (phaseT > 100) { phase = 0; phaseT = 0; } } // sprint low, full forward
                }
            }
            case "sprint" -> S.ctl.override = st(1.0, 1.0, 0.04, 0, true); // flat out
            case "brake" -> S.ctl.override = st(alt(s, y0 + h), -0.6, 0, 0, true); // tilt backwards to stop
            case "disarm" -> { S.ctl.override = st(-1, 0, 0, 0, false); pk = 0; } // (the next pattern starts with the arming warm-up again)
            default -> S.ctl.override = st(alt(s, y0 + h), 0, 0, 0, true);
        }
    }

    private static void command(Minecraft mc, String line) {
        Session S = Session.I;
        String[] a = line.trim().split(" ", 2);
        String arg = a.length > 1 ? a[1] : "";
        var p = mc.player;
        switch (a[0]) {
            case "shot" -> shot(mc, mc.getUser().getName() + "_" + arg);
            case "chat" -> { if (p != null) p.connection.sendCommand(arg); }
            case "say" -> { if (p != null) p.connection.sendChat(arg); }
            case "h" -> net.minecraft.client.KeyMapping.click(FpvClient.spawn.getDefaultKey());
            case "g" -> net.minecraft.client.KeyMapping.click(FpvClient.fly.getDefaultKey());
            case "land" -> S.land(mc);
            case "pattern" -> { pat = arg.isEmpty() ? "off" : arg; phase = 0; phaseT = 0; if (pat.equals("off")) S.ctl.override = null; } // (y0 = launch altitude and the arming warm-up belong to the flight, see runPattern)
            case "respawn" -> { if (p != null) p.respawn(); }
            case "hit" -> { String[] v = arg.split(" "); var e = find(mc, v[0].equals("drone"), v.length > 1 ? v[1] : ""); if (e != null && mc.gameMode != null) { mc.gameMode.attack(p, e); p.swing(net.minecraft.world.InteractionHand.MAIN_HAND); } else FpvClient.LOG.info("MPQA hit: no target"); }
            case "use" -> { String[] v = arg.split(" "); var pos = new net.minecraft.core.BlockPos(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]));
                var r = mc.gameMode.useItemOn(p, net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false)); FpvClient.LOG.info("MPQA use {} -> {}", pos, r); }
            case "view" -> S.view = Integer.parseInt(arg);
            case "look" -> { var e = find(mc, !arg.startsWith("body"), arg.contains(" ") ? arg.substring(arg.indexOf(' ') + 1) : ""); if (e != null) p.lookAt(EntityAnchorArgument.Anchor.EYES, e.position()); }
            case "track" -> { String[] v = arg.split(" "); track = v[0]; trackOwner = v.length > 1 ? v[1] : ""; }
            case "log" -> state(mc, "STATE " + arg);
            case "qalog" -> qalog = arg.equals("on");
            case "connect" -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(arg), new ServerData("qa", arg, ServerData.Type.OTHER), false, null);
            case "disconnect" -> { S.destroy(); if (mc.level != null) { mc.level.disconnect(); mc.disconnect(); } mc.setScreen(new TitleScreen()); }
            case "quit" -> mc.stop();
            default -> FpvClient.LOG.info("MPQA unknown command {}", line);
        }
        FpvClient.LOG.info("MPQA cmd done: {}", line);
    }

    static void tick(Minecraft mc) {
        Session S = Session.I;
        k++;
        if (mc.level == null || mc.player == null) {
            if (mc.screen != lastScreen) { lastScreen = mc.screen; FpvClient.LOG.info("MPQA NO WORLD screen={} {}", mc.screen, mc.screen instanceof DisconnectedScreen ds ? "DISCONNECTED: " + describe(ds) : ""); }
        } else {
            lastScreen = null;
            if (S.drone != null) maxSpeed = Math.max(maxSpeed, S.fpvStateSpeed);
            float hp = mc.player.getHealth();
            if (hp != lastHp) { FpvClient.LOG.info("MPQA HEALTH {} -> {}", lastHp, hp); lastHp = hp; }
            if (mc.player.isDeadOrDying()) FpvClient.LOG.info("MPQA PLAYER DEAD");
            runPattern(mc);
            if (!track.equals("off")) { var e = find(mc, track.equals("drone"), trackOwner); if (e != null) mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, e.position()); }
            if (qalog && k % 20 == 0) state(mc, "tick");
        }
        Path f = Path.of(System.getProperty("fpvcore.cmdfile", "fpv_cmd.txt"));
        if (!Files.isRegularFile(f)) return;
        List<String> lines = new ArrayList<>();
        try { lines = Files.readAllLines(f); Files.delete(f); } catch (java.io.IOException e) { return; }
        for (String l : lines) if (!l.isBlank()) { try { command(mc, l); } catch (RuntimeException e) { FpvClient.LOG.error("MPQA command failed: " + l, e); } }
    }

    private static String describe(DisconnectedScreen ds) {
        try {
            for (var fld : DisconnectedScreen.class.getDeclaredFields()) {
                fld.setAccessible(true);
                Object o = fld.get(ds);
                if (o instanceof net.minecraft.network.DisconnectionDetails dd) return dd.reason().getString();
                if (o instanceof net.minecraft.network.chat.Component c && fld.getName().toLowerCase().contains("reason")) return c.getString();
            }
        } catch (ReflectiveOperationException e) { return e.toString(); }
        return "?";
    }
}
