package dev.fpvcore.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Dev-only scripted flight (-Dfpvcore.autotest=1): creates a flat world, builds a wall, flies into it, takes screenshots, logs state, exits. */
final class AutoTest {
    private AutoTest() {}
    private static int t = -1;
    private static boolean created;
    private static double wallZ = 14.5, maxZ = -1e9;

    private static void shot(Minecraft mc, String tag) {
        Screenshot.grab(mc.gameDirectory, "fpv_" + tag + ".png", mc.getMainRenderTarget(), c -> {});
    }

    private static Mapping.Sticks sticks(double thr, double pitch, double yaw, double roll, boolean arm) {
        Mapping.Sticks s = new Mapping.Sticks();
        s.throttle = thr; s.pitch = pitch; s.yaw = yaw; s.roll = roll; s.arm = arm; s.mode = 1;
        return s;
    }

    private static String mode() { return System.getProperty("fpvcore.autotest"); }

    /** Altitude hold stick for a given target height (hover is about -0.5). */
    private static double alt(FpvNative.State s, double target) {
        return Math.max(-1, Math.min(0.2, -0.5 + 0.35 * (target - s.pos[1]) - 0.12 * s.linvel[1]));
    }

    private static Mapping.Sticks sticksA(double thr, double pitch, double yaw, double roll, boolean arm) {
        Mapping.Sticks s = sticks(thr, pitch, yaw, roll, arm); s.mode = 2; return s; // ANGLE mode: pitch stick = tilt
    }

    private static int mp = -1;
    private static boolean seen;
    private static int seenAt;

    /** Multiplayer scenarios: "pilot" flies a pattern near spawn on a server, "watch" looks at the drone entity and takes screenshots. */
    private static void mpTick(Minecraft mc, boolean pilot) {
        Session S = Session.I;
        if (mc.level == null || mc.player == null) return;
        mp++;
        if (pilot) {
            int start = 400; // let the watcher join first
            if (mp == start) {
                S.spawn(mc, "5race"); S.setFlying(mc, true);
                FpvClient.LOG.info("MP-PILOT serverMod={} start", S.serverMod());
            }
            if (mp >= start && S.drone != null) {
                int k = mp - start;
                FpvNative.State s = S.drone.state();
                double y0 = -60;
                if (k < 40) S.ctl.override = sticksA(-1, 0, 0, 0, k > 10);
                else if (k < 200) S.ctl.override = sticksA(alt(s, y0 + 5), 0, 0, 0, true);
                else if (k < 250) S.ctl.override = sticksA(alt(s, y0 + 5), 0.3, 0, 0, true);   // fly forward (+z)
                else if (k < 330) S.ctl.override = sticksA(alt(s, y0 + 5), -0.3, 0, 0, true);  // and back
                else if (k < 560) S.ctl.override = sticksA(alt(s, y0 + 5), 0, 0, 0, true);
                else if (k < 760) S.ctl.override = sticksA(alt(s, y0 + 5), 0.45, 0, 0, true);  // then far away (chunks follow on the server)
                else S.ctl.override = sticksA(alt(s, y0 + 5), -0.45, 0, 0, true);
                if (k % 20 == 0) FpvClient.LOG.info("MP-PILOT k={} pos=({}, {}, {}) armed={} waiting={}", k, String.format("%.1f", s.pos[0]), String.format("%.1f", s.pos[1]), String.format("%.1f", s.pos[2]), s.armed, S.waitingChunks);
                if (k == 900) { S.land(mc); FpvClient.LOG.info("MP-PILOT landed"); }
                if (k == 960) { FpvClient.LOG.info("MP-PILOT player at {} {} {}", mc.player.getX(), mc.player.getY(), mc.player.getZ()); }
                if (k == 1000) { FpvClient.LOG.info("MP-PILOT DONE"); mc.stop(); }
            }
        } else {
            DroneEntity d = null; PilotBodyEntity body = null;
            for (var e : mc.level.entitiesForRendering()) { if (e instanceof DroneEntity de) d = de; if (e instanceof PilotBodyEntity b) body = b; }
            if (d != null && !seen) { seen = true; seenAt = mp; FpvClient.LOG.info("MP-WATCH drone entity appeared at tick {}", mp); }
            if (d != null) {
                int k = mp - seenAt;
                mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, d.position());
                if (k % 20 == 0) FpvClient.LOG.info("MP-WATCH k={} drone=({}, {}, {}) dist={} armed={} pilotFlying={} rpm={} body={}", k,
                    String.format("%.1f", d.getX()), String.format("%.1f", d.getY()), String.format("%.1f", d.getZ()), String.format("%.1f", d.distanceTo(mc.player)),
                    d.armed(), d.pilotFlying(), d.getEntityData().get(DroneEntity.RPM), body == null ? "none" : String.format("(%.1f, %.1f, %.1f)", body.getX(), body.getY(), body.getZ()));
                if (k == 100 || k == 200 || k == 250 || k == 330 || k == 420) shot(mc, "watch_" + k);
            }
            if (seen && mp - seenAt == 1100) { FpvClient.LOG.info("MP-WATCH DONE"); mc.stop(); }
        }
    }

    private static int far = -1;
    private static double maxDist;
    private static void farTick(Minecraft mc) {
        Session S = Session.I;
        far++;
        var p = mc.player;
        if (far == 60) p.connection.sendCommand("tp @s 0.5 -60 0.5 0 0");
        if (far == 100) { S.spawn(mc, "5race"); S.setFlying(mc, true); }
        if (far >= 100 && S.drone != null) {
            int k = far - 100;
            FpvNative.State s = S.drone.state();
            double y0 = -60;
            if (k < 40) S.ctl.override = sticksA(-1, 0, 0, 0, k > 10);
            else if (k < 100) S.ctl.override = sticksA(alt(s, y0 + 20), 0, 0, 0, true);
            else if (maxDist < 560) S.ctl.override = sticksA(alt(s, y0 + 20), 0.45, 0, 0, true);
            else S.ctl.override = sticksA(alt(s, y0 + 20), -0.45, 0, 0, true); // brake
            double dist = Math.hypot(s.pos[0], s.pos[2]);
            maxDist = Math.max(maxDist, dist);
            var srv = mc.getSingleplayerServer();
            var sp = srv == null ? null : srv.getPlayerList().getPlayer(p.getUUID());
            boolean chunkHere = mc.level.getChunkSource().hasChunk(Mth.floor(s.pos[0]) >> 4, Mth.floor(s.pos[2]) >> 4);
            if (k % 20 == 0) FpvClient.LOG.info("AUTOFAR k={} drone=({}, {}, {}) dist={} v={} serverPlayer={} clientChunkAtDrone={} waiting={}", k,
                String.format("%.1f", s.pos[0]), String.format("%.1f", s.pos[1]), String.format("%.1f", s.pos[2]), String.format("%.0f", dist), String.format("%.1f", S.fpvStateSpeed),
                sp == null ? "?" : String.format("(%.1f, %.1f, %.1f)", sp.getX(), sp.getY(), sp.getZ()), chunkHere, S.waitingChunks);
            if (maxDist >= 560 && k % 20 == 0 && !farShot) { farShot = true; shot(mc, "far_view"); FpvClient.LOG.info("AUTOFAR reached {} blocks", (int) dist); farAt = k; }
            if (farShot && k == farAt + 120) { S.land(mc); FpvClient.LOG.info("AUTOFAR landed (flight ended)"); }
            if (farShot && k == farAt + 200) {
                FpvClient.LOG.info("AUTOFAR player now client=({}, {}, {}) server={}", p.getX(), p.getY(), p.getZ(), sp == null ? "?" : String.format("(%.1f, %.1f, %.1f)", sp.getX(), sp.getY(), sp.getZ()));
                FpvClient.LOG.info("AUTOFAR DONE"); mc.stop();
            }
            if (k > 5000) { FpvClient.LOG.info("AUTOFAR TIMEOUT maxDist={}", maxDist); mc.stop(); }
        }
    }
    private static boolean farShot; private static int farAt;

    // ---- "userfar" (-Dfpvcore.autotest=userfar): like the real player: existing world, H (spawn) and G (fly) as key presses, fly far, G back. Speed via -Dfpvcore.pitch ----
    private static int uf = -1, menuTicks;
    private static double ufMax;
    private static int ufEnd = -1;
    private static void click(net.minecraft.client.KeyMapping k) { net.minecraft.client.KeyMapping.click(k.getDefaultKey()); }

    // ---- client-side chunk statistics (what the CLIENT really has, not the server player position) ----
    private static int chunksAround(Minecraft mc, double x, double z, int r) {
        int cx = Mth.floor(x) >> 4, cz = Mth.floor(z) >> 4, n = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) n++;
        return n;
    }
    /** Client view centre (ClientChunkCache.storage.viewCenterX/Z) and radius via reflection (dev env = Mojang names). */
    private static String clientCentre(Minecraft mc) {
        try {
            var cc = mc.level.getChunkSource();
            var f = cc.getClass().getDeclaredField("storage"); f.setAccessible(true);
            Object st = f.get(cc);
            var fx = st.getClass().getDeclaredField("viewCenterX"); fx.setAccessible(true);
            var fz = st.getClass().getDeclaredField("viewCenterZ"); fz.setAccessible(true);
            var fr = st.getClass().getDeclaredField("chunkRadius"); fr.setAccessible(true);
            return "centre=(" + fx.getInt(st) + "," + fz.getInt(st) + ") r=" + fr.getInt(st);
        } catch (ReflectiveOperationException e) { return "centre=?" + e; }
    }
    /** A chunk "renders" when its sections are compiled; count the render-dispatched sections around a point as a proxy. */
    private static String chunkStats(Minecraft mc, FpvNative.State s) {
        int r = mc.options.renderDistance().get();
        int side = 2 * r + 1;
        double sp = Math.hypot(s.linvel[0], s.linvel[2]);
        double dx = sp > 0.5 ? s.linvel[0] / sp : 0, dz = sp > 0.5 ? s.linvel[2] / sp : 0;
        boolean ahead = mc.level.getChunkSource().hasChunk(Mth.floor(s.pos[0] + dx * 16 * (r - 2)) >> 4, Mth.floor(s.pos[2] + dz * 16 * (r - 2)) >> 4);
        boolean under = mc.level.getChunkSource().hasChunk(Mth.floor(s.pos[0]) >> 4, Mth.floor(s.pos[2]) >> 4);
        return String.format("clientChunks total=%d ringDrone(r=%d)=%d/%d ring4=%d/81 aheadR-2=%s under=%s %s", mc.level.getChunkSource().getLoadedChunksCount(), r, chunksAround(mc, s.pos[0], s.pos[2], r), side * side, chunksAround(mc, s.pos[0], s.pos[2], 4), ahead, under, clientCentre(mc));
    }
    /** Chunks the SERVER has fully loaded in the ring around the drone (to tell worldgen limits from chunk-sending limits). */
    private static String serverStats(Minecraft mc, FpvNative.State s) {
        var srv = mc.getSingleplayerServer();
        if (srv == null) return "";
        var sp = srv.getPlayerList().getPlayer(mc.player.getUUID());
        if (sp == null) return "";
        var lvl = sp.serverLevel();
        int r = mc.options.renderDistance().get(), cx = Mth.floor(s.pos[0]) >> 4, cz = Mth.floor(s.pos[2]) >> 4, n = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (lvl.getChunkSource().hasChunk(cx + dx, cz + dz)) n++;
        return String.format("serverChunks ring=%d loaded=%d sectionPos=%s", n, lvl.getChunkSource().getLoadedChunksCount(), sp.getLastSectionPos());
    }
    private static Object fld(Object o, String n) throws ReflectiveOperationException { var f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); }
    /** Chunk sender pacing: server-side pending queue / quota, client-requested chunks per tick, fps. */
    private static String pacing(Minecraft mc) {
        try {
            var srv = mc.getSingleplayerServer();
            var sp = srv.getPlayerList().getPlayer(mc.player.getUUID());
            Object snd = sp.connection.chunkSender;
            Object calc = fld(mc.getConnection(), "chunkBatchSizeCalculator");
            return String.format("pacing pending=%d desired=%.2f quota=%.2f unacked=%d clientDesired=%.2f fps=%d", ((java.util.Collection<?>) fld(snd, "pendingChunks")).size(), (Float) fld(snd, "desiredChunksPerTick"), (Float) fld(snd, "batchQuota"),
                (Integer) fld(snd, "unacknowledgedBatches"), ((ChunkBatchCalc) () -> 0).x(calc), mc.getFps());
        } catch (Throwable e) { return "pacing=? " + e; }
    }
    private interface ChunkBatchCalc { int dummy(); default float x(Object c) { return ((net.minecraft.client.multiplayer.ChunkBatchSizeCalculator) c).getDesiredChunksPerTick(); } }
    private static int serverRing(Minecraft mc, FpvNative.State s) {
        var srv = mc.getSingleplayerServer(); var sp = srv == null ? null : srv.getPlayerList().getPlayer(mc.player.getUUID());
        if (sp == null) return 0;
        int r = mc.options.renderDistance().get(), cx = Mth.floor(s.pos[0]) >> 4, cz = Mth.floor(s.pos[2]) >> 4, n = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (sp.serverLevel().getChunkSource().hasChunk(cx + dx, cz + dz)) n++;
        return n;
    }
    private static void press(Minecraft mc, int key) {
        long w = mc.getWindow().getWindow();
        mc.keyboardHandler.keyPress(w, key, 0, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0);
        mc.keyboardHandler.keyPress(w, key, 0, org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0);
    }
    private static void userFarTick(Minecraft mc) {
        Session S = Session.I;
        uf++;
        var p = mc.player;
        var srv = mc.getSingleplayerServer();
        var sp = srv == null ? null : srv.getPlayerList().getPlayer(p.getUUID());
        if (uf == 50 && System.getProperty("fpvcore.survival") != null) p.connection.sendCommand("gamemode survival");
        if (uf == 60 && srv != null) { var cs = srv.createCommandSourceStack().withSuppressedOutput(); srv.getCommands().performPrefixedCommand(cs, "time set noon"); srv.getCommands().performPrefixedCommand(cs, "weather clear"); }
        if (uf == 75) { shot(mc, "uf_pre_hud"); FpvClient.LOG.info("USERFAR gamemode={}", mc.gameMode.getPlayerMode()); }
        if (uf == 80) { press(mc, org.lwjgl.glfw.GLFW.GLFW_KEY_H); FpvClient.LOG.info("USERFAR pressed H"); }
        if (uf == 99) { ufHx = p.getX(); ufHy = p.getY(); ufHz = p.getZ(); }
        if (uf == 100) { press(mc, org.lwjgl.glfw.GLFW.GLFW_KEY_G); FpvClient.LOG.info("USERFAR pressed G"); }
        if (uf >= 100 && S.drone != null && S.flying) {
            int k = uf - 100;
            FpvNative.State s = S.drone.state();
            double y0 = ufHy + 20;
            double pitch = Double.parseDouble(System.getProperty("fpvcore.pitch", "0.45"));
            double target = Double.parseDouble(System.getProperty("fpvcore.dist", "700"));
            double dist = Math.hypot(s.pos[0] - ufHx, s.pos[2] - ufHz);
            ufMax = Math.max(ufMax, dist);
            if (k < 40) S.ctl.override = sticksA(-1, 0, 0, 0, k > 10);
            else if (k < 100) S.ctl.override = sticksA(alt(s, y0), 0, 0, 0, true);
            else if (ufMax < target) S.ctl.override = sticksA(alt(s, y0), pitch, 0, 0, true);
            else S.ctl.override = sticksA(alt(s, y0), -pitch, 0, 0, true);
            if (System.getProperty("fpvcore.views") != null) {
                if (k == 150) shot(mc, "uf_view0_fpv");
                if (k == 160 || k == 190 || k == 220) click(mc.options.keyTogglePerspective);
                if (k == 175) shot(mc, "uf_view1_behind");
                if (k == 205) shot(mc, "uf_view2_front");
                if (k == 235) { shot(mc, "uf_view0_again"); FpvClient.LOG.info("USERFAR view now {} playerCamera={}", S.view, mc.options.getCameraType()); }
            }
            if (k % 20 == 0) {
                int bodies = 0; String bp = "none";
                if (srv != null) for (var lvl : srv.getAllLevels()) for (var e : lvl.getAllEntities()) if (e instanceof PilotBodyEntity b) { bodies++; bp = String.format("(%.1f, %.1f, %.1f)", b.getX(), b.getY(), b.getZ()); }
                FpvClient.LOG.info("USERFAR k={} dist={} v={} drone=({}, {}, {}) serverPlayer={} clientPlayer=({}, {}, {}) clientChunkAtDrone={} waiting={} bodies={} {} sync={} {}", k, String.format("%.0f", dist), String.format("%.1f", S.fpvStateSpeed),
                    String.format("%.1f", s.pos[0]), String.format("%.1f", s.pos[1]), String.format("%.1f", s.pos[2]),
                    sp == null ? "?" : String.format("(%.1f, %.1f, %.1f)", sp.getX(), sp.getY(), sp.getZ()), String.format("%.1f", p.getX()), String.format("%.1f", p.getY()), String.format("%.1f", p.getZ()),
                    mc.level.getChunkSource().hasChunk(Mth.floor(s.pos[0]) >> 4, Mth.floor(s.pos[2]) >> 4), S.waitingChunks, bodies, bp, S.syncStatus(), chunkStats(mc, s) + " " + serverStats(mc, s) + " " + pacing(mc));
            }
            if (k == 20) S.view = 2;
            if (k == 32) { shot(mc, "uf_body_front0"); S.view = 1; }
            if (k == 44) { shot(mc, "uf_body_chase0"); S.view = 0; }
            if (chaseShot > 0 && --chaseShot == 0) { shot(mc, chaseName); S.view = 0; }
            if (dist >= ufNextShot && ufEnd < 0) { // every 100 blocks: client-side assertion + a low-res FPV screenshot
                int r = mc.options.renderDistance().get();
                int ring = chunksAround(mc, s.pos[0], s.pos[2], r), ring4 = chunksAround(mc, s.pos[0], s.pos[2], 4);
                int srvRing = serverRing(mc, s);
                int rendered = mc.levelRenderer.countRenderedSections();
                boolean ok = ring4 >= 60 && ring >= 0.6 * srvRing && rendered > 30;
                FpvClient.LOG.info("USERFAR ASSERT detail: clientRing={} serverRing={} renderedSections={} (client must hold >=60% of what the server has loaded (worldgen/CPU bound, so not 100%) and render >30 sections)", ring, srvRing, rendered);
                if (!ok) ufFail++;
                FpvClient.LOG.info("USERFAR ASSERT dist={} {} ring={} ring4={} -> {}", (int) dist, (int) dist, ring, ring4, ok ? "PASS" : "FAIL");
                shot(mc, "uf_fpv_" + (int) ufNextShot);
                if (ufEnd < 0) { S.view = 1; chaseShot = 3; chaseName = "uf_chase_" + (int) ufNextShot; }
                ufNextShot += 100;
            }
            if (ufMax >= target && ufEnd < 0) { ufEnd = k; shot(mc, "userfar_far"); S.view = 1; FpvClient.LOG.info("USERFAR reached {} blocks", (int) dist); }
            if (ufEnd >= 0 && k == ufEnd + 10) shot(mc, "userfar_chase");
            if (ufEnd >= 0 && ufExit < 0 && dist < 60 && k > ufEnd + 100) { if (ufBody < 0) { ufBody = k; S.view = 2; } if (k == ufBody + 15) shot(mc, "uf_body_front"); if (k == ufBody + 40) { S.view = 1; } if (k == ufBody + 55) shot(mc, "uf_body_behind"); }
            if (ufEnd >= 0 && ufExit < 0 && ((ufBody >= 0 && k > ufBody + 60) || k > ufEnd + 200)) { click(FpvClient.fly); ufExit = uf; FpvClient.LOG.info("USERFAR pressed G (exit) k={} dist={}", k, (int) dist); }
        }
        if (ufExit >= 0 && uf == ufExit + 60) {
            FpvClient.LOG.info("USERFAR after G: flying={} client=({}, {}, {}) server={}", S.flying, p.getX(), p.getY(), p.getZ(), sp == null ? "?" : String.format("(%.1f, %.1f, %.1f)", sp.getX(), sp.getY(), sp.getZ()));
            FpvClient.LOG.info("USERFAR RESULT {} (chunk assertion failures: {})", ufFail == 0 ? "PASS" : "FAIL", ufFail); FpvClient.LOG.info("USERFAR DONE"); mc.stop();
        }
    }
    private static double ufHx, ufHy, ufHz;
    private static int ufExit = -1, ufBody = -1, ufFail;
    private static double ufNextShot = 100;
    private static int chaseShot; private static String chaseName;

    // ---- file-driven mode (-Dfpvcore.autotest=cmd -Dfpvcore.cmdfile=<path>): an external script writes one command per file, the game executes and logs "CMD ..." ----
    private static int ct;
    private static void cmdTick(Minecraft mc) {
        Session S = Session.I;
        ct++;
        if (ct == 30) { mc.player.connection.sendCommand("tp @s 0.5 -60 0.5 0 0"); mc.player.connection.sendCommand("fill -6 -60 14 6 -52 14 minecraft:stone"); FpvClient.LOG.info("CMD ready"); }
        if (ct < 60) return;
        java.nio.file.Path f = java.nio.file.Path.of(System.getProperty("fpvcore.cmdfile", "fpv_cmd.txt"));
        String line;
        try { if (!java.nio.file.Files.isRegularFile(f)) return; line = java.nio.file.Files.readString(f).trim(); java.nio.file.Files.delete(f); } catch (java.io.IOException e) { return; }
        String[] a = line.split(" ", 2);
        String arg = a.length > 1 ? a[1] : "";
        var p = mc.player;
        switch (a[0]) {
            case "shot" -> shot(mc, arg);
            case "screen" -> mc.setScreen(new CalibrationScreen());
            case "close" -> mc.setScreen(null);
            case "wizard" -> { if (mc.screen instanceof CalibrationScreen cs) cs.startWizard(); }
            case "next" -> { if (mc.screen instanceof CalibrationScreen cs) cs.next(); }
            case "retry" -> { if (mc.screen instanceof CalibrationScreen cs) cs.retry(); }
            case "edit" -> { if (mc.screen instanceof CalibrationScreen cs) cs.startEdit(); }
            case "mode" -> { if (mc.screen instanceof CalibrationScreen cs) cs.setStickMode(Integer.parseInt(arg)); }
            case "preset" -> { if (mc.screen instanceof CalibrationScreen cs) cs.applyPreset(arg); }
            case "assign" -> { if (mc.screen instanceof CalibrationScreen cs) { String[] v = arg.split(" "); cs.assignAxis(Integer.parseInt(v[0]), Integer.parseInt(v[1])); } }
            case "map" -> FpvClient.LOG.info("CMD map {} work[{}] saved[{}]", arg, mc.screen instanceof CalibrationScreen cs ? cs.summary() : "-", S.cfg.mapping.summary());
            case "back" -> { if (mc.screen instanceof CalibrationScreen cs) cs.back(); }
            case "key" -> net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(Integer.parseInt(arg)));
            case "chat" -> p.connection.sendCommand(arg);
            case "sticks" -> { String[] v = arg.split(" "); S.ctl.override = sticks(Double.parseDouble(v[0]), Double.parseDouble(v[1]), 0, 0, Boolean.parseBoolean(v[2])); }
            case "nooverride" -> S.ctl.override = null;
            case "log" -> {
                var srv = mc.getSingleplayerServer(); var sp = srv == null ? null : srv.getPlayerList().getPlayer(p.getUUID());
                int drones = 0; for (var e : mc.level.entitiesForRendering()) if (e instanceof DroneEntity) drones++;
                FpvNative.State s = S.drone == null ? null : S.drone.state();
                FpvClient.LOG.info("CMD state {} flying={} drone={} droneEntities={} crashed={} client=({}, {}, {}) server={} dronePos={} armed={} sticks=({}, {}, {}, {}) arm={} mode={} ctl={} [{}]", arg, S.flying, S.drone != null, drones, S.crashed,
                    String.format("%.1f", p.getX()), String.format("%.1f", p.getY()), String.format("%.1f", p.getZ()),
                    sp == null ? "?" : String.format("(%.1f, %.1f, %.1f)", sp.getX(), sp.getY(), sp.getZ()),
                    s == null ? "-" : String.format("(%.1f, %.1f, %.1f)", s.pos[0], s.pos[1], s.pos[2]), s == null ? "-" : s.armed,
                    String.format("%.2f", S.sticks.throttle), String.format("%.2f", S.sticks.yaw), String.format("%.2f", S.sticks.pitch), String.format("%.2f", S.sticks.roll), S.sticks.arm, S.sticks.mode,
                    S.ctl.connected() ? S.ctl.name : "keyboard", S.cfg.mapping.calibrated);
            }
            case "quit" -> mc.stop();
            default -> FpvClient.LOG.info("CMD unknown {}", line);
        }
    }

    // ---- -Dfpvcore.autotest=disarm: fly forward, disarm mid-air; the drone must fall, slide a bit (Coulomb friction), tumble to rest and sleep ----
    private static double[] dsStart = new double[3];
    private static double dsSpeed;
    private static void disarmTick(Minecraft mc, int k) {
        Session S = Session.I;
        FpvNative.State s = S.drone.state();
        double speed = Math.sqrt(s.linvel[0] * s.linvel[0] + s.linvel[1] * s.linvel[1] + s.linvel[2] * s.linvel[2]);
        if (k < 30) S.ctl.override = sticks(-1, 0, 0, 0, k > 10);
        else if (k < 42) S.ctl.override = sticks(-0.2, 0, 0, 0, true);
        else if (k < 90) S.ctl.override = sticksA(alt(s, -56), 0.4, 0, 0, true);      // forward flight (south, +z) at low altitude, the wall is 12 m away
        if (k == 90) {
            dsStart = new double[]{s.pos[0], s.pos[1], s.pos[2]}; dsSpeed = Math.hypot(s.linvel[0], s.linvel[2]);
            FpvClient.LOG.info("AUTOTEST DISARM at ({}, {}, {}) horizontal speed {} m/s", String.format("%.2f", s.pos[0]), String.format("%.2f", s.pos[1]), String.format("%.2f", s.pos[2]), String.format("%.1f", dsSpeed));
        }
        if (k >= 90) S.ctl.override = sticks(-1, 0, 0, 0, false);
        if (k >= 90 && k % 10 == 0) FpvClient.LOG.info("AUTOTEST DISARM k={} pos=({}, {}, {}) speed={} resting={}", k, String.format("%.2f", s.pos[0]), String.format("%.2f", s.pos[1]), String.format("%.2f", s.pos[2]), String.format("%.2f", speed), S.drone.resting());
        if (k == 400) {
            double dist = Math.hypot(s.pos[0] - dsStart[0], s.pos[2] - dsStart[2]);
            boolean ok = speed < 0.05 && S.drone.resting() && dsSpeed > 3 && dist < 25 && s.pos[1] < -59.5;
            FpvClient.LOG.info("AUTOTEST DISARM RESULT {} speed={} resting={} startSpeed={} slid={} m y={}", ok ? "PASS" : "FAIL", String.format("%.3f", speed), S.drone.resting(), String.format("%.1f", dsSpeed), String.format("%.1f", dist), String.format("%.2f", s.pos[1]));
            FpvClient.LOG.info("AUTOTEST DONE"); mc.stop();
        }
    }

    static void tick(Minecraft mc) {
        Session S = Session.I;
        String m = mode();
        if ("mp".equals(m)) { MpQa.tick(mc); return; }
        if ("rejoin".equals(m)) {
            if (mc.player != null && ++mp == 100) { FpvClient.LOG.info("REJOIN player at {} {} {}", mc.player.getX(), mc.player.getY(), mc.player.getZ()); mc.stop(); }
            return;
        }
        if ("pilot".equals(m) || "watch".equals(m)) { mpTick(mc, m.equals("pilot")); return; }
        if (mc.level != null && mc.player != null && "far".equals(m)) { farTick(mc); return; }
        if (mc.level != null && mc.player != null && "userfar".equals(m)) { userFarTick(mc); return; }
        if ("userfar".equals(m)) {
            if (++menuTicks == 100) { // quickPlay is ignored behind the accessibility onboarding: open the world like the world list does
                String w = System.getProperty("fpvcore.world", "fpv test");
                FpvClient.LOG.info("USERFAR opening world '{}' from {}", w, mc.screen);
                mc.setScreen(new TitleScreen());
                try (var acc = mc.getLevelSource().createAccess(w)) { var dyn = acc.getDataTag(); FpvClient.LOG.info("USERFAR level data ok: {}", acc.getSummary(dyn)); } catch (Throwable e) { FpvClient.LOG.error("USERFAR level data failed", e); }
                mc.createWorldOpenFlows().openWorld(w, () -> mc.setScreen(new TitleScreen()));
            }
            if (menuTicks % 200 == 0) { FpvClient.LOG.info("USERFAR waiting for the world, screen={}", mc.screen); shot(mc, "menu"); }
            return;
        }
        if (mc.level == null || mc.player == null) {
            if (!created && mc.screen instanceof TitleScreen) {
                created = true;
                LevelSettings ls = new LevelSettings("fpvtest" + System.currentTimeMillis() % 100000, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(ls.levelName(), ls, new WorldOptions(1234L, false, false),
                    ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            }
            return;
        }
        if ("cmd".equals(m)) { cmdTick(mc); return; }
        t++;
        var p = mc.player;
        if (t == 60) {
            p.connection.sendCommand("tp @s 0.5 -60 0.5 0 0");
            p.connection.sendCommand("fill -6 -60 14 6 -52 14 minecraft:stone");
            p.connection.sendCommand("fill 3 -60 4 3 -56 4 minecraft:glass");
        }
        if (t == 100) {
            S.spawn(mc, System.getProperty("fpvcore.autotest").equals("1") || "disarm".equals(m) ? "5race" : System.getProperty("fpvcore.autotest"));
            S.setFlying(mc, true);
            S.ctl.override = sticks(-1, 0, 0, 0, false);
            FpvClient.LOG.info("AUTOTEST native {} controller={}", FpvNative.get().fpv_version(), S.ctl.connected());
        }
        if (t >= 100 && S.drone != null && "disarm".equals(m)) { disarmTick(mc, t - 100); return; }
        if (t >= 100 && S.drone != null) {
            int k = t - 100;
            FpvNative.State s = S.drone.state();
            if (k == 5) shot(mc, "0_landed_fpv");
            if (k < 30) S.ctl.override = sticks(-1, 0, 0, 0, k > 10);                      // arm with throttle low
            else if (k < 70) S.ctl.override = sticks(-0.2, 0, 0, 0, true);                // climb
            else if (k < 80) S.ctl.override = sticks(-0.5, 0, 0, 0, true);                // hover
            else if (k < 210) S.ctl.override = sticksA(alt(s, -52), 0.35, 0, 0, true);   // angle mode, altitude hold: pitch forward -> fly south into the wall
            else S.ctl.override = sticksA(alt(s, -52), -0.2, 0, 0, true);
            if (k == 75) shot(mc, "1_climb");
            if (k == 130) shot(mc, "2_cruise");
            if (k == 175) { S.view = 1; }
            if (k == 180) shot(mc, "3_chase");
            if (k % 10 == 0) FpvClient.LOG.info("AUTOTEST k={} pos=({}, {}, {}) v=({}, {}, {}) armed={} thr={} batt={}V impulse={}", k,
                String.format("%.2f", s.pos[0]), String.format("%.2f", s.pos[1]), String.format("%.2f", s.pos[2]),
                String.format("%.1f", s.linvel[0]), String.format("%.1f", s.linvel[1]), String.format("%.1f", s.linvel[2]),
                s.armed, String.format("%.0f", s.thrust), String.format("%.1f", s.battery_v), String.format("%.0f", S.collider.lastImpulse));
            maxZ = Math.max(maxZ, s.pos[2]);
            if (k == 260) {
                S.view = 0; shot(mc, "4_after");
                FpvClient.LOG.info("AUTOTEST RESULT maxZ={} wallZ={} (wall blocks at z=14..15 so the hull stops at z<={})", maxZ, wallZ, 14 - Collider.SWEEP_R);
            }
            if (k == 280) { FpvClient.LOG.info("AUTOTEST DONE"); mc.stop(); }
        }
    }
}
