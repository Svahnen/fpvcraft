package dev.fpvcore.mc.demo;

import dev.fpvcore.mc.*;
import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Demo-video director (enabled with -Dfpvcore.demo=<camera plan>): opens/creates the demo world, builds the race course, spawns the drone, flies the scripted
 * course with {@link PathPilot} at a fixed 60 Hz step, runs the camera plan, pipes the frames to ffmpeg ({@link DemoRecorder}) and quits.
 */
public final class DemoDirector {
    private DemoDirector() {}

    private enum Phase { TITLE, LOADING, BUILD, SETTLE, SPAWN, RUN, DONE }
    private static Phase phase = Phase.TITLE;
    private static int wait;
    private static Path path;
    private static PathPilot pilot, pilot2;
    private static final double T_ARM = 1.2, T_GO = 2.8;
    private static final double[] ORG = {CoursePath.OX, CoursePath.OY, CoursePath.OZ};
    private static boolean buildQueued, buildDone;
    private static int buildDoneTick;
    private static String world() { return System.getProperty("fpvcore.demo.world", "fpvdemo1"); }

    // run state
    private static int tick, readyFrames;
    private static boolean recording, finished;
    private static int restFrames;
    private static double disarmT = 1e9, finishT = 1e9;
    private static Drone wing;
    private static Collider wingCol;
    private static boolean wingActive;
    private static double wingEnd = 0, wingPropAngle;
    private static final Quaternionf prevQ = new Quaternionf();
    private static double angRate;
    private static String planName;
    private static final List<Cam> PLAN = new ArrayList<>();

    public static void init() {
        planName = DemoHooks.TAKE;
        path = CoursePath.build();
        for (Path.Mark m : path.marks) { if (m.tag.equals("disarm")) disarmT = m.t; if (m.tag.equals("finish")) finishT = m.t; }
        buildPlan(planName);
        ClientTickEvents.END_CLIENT_TICK.register(DemoDirector::tick);
        ServerTickEvents.END_SERVER_TICK.register(DemoDirector::serverTick);
        WorldRenderEvents.AFTER_ENTITIES.register(DemoDirector::renderExtras);
        FpvClient.LOG.info("DEMO director: plan={} path={}s world={} out={}", planName, path.duration(), world(), DemoRecorder.outDir());
    }

    // ------------------------------------------------------------------ camera plan
    static final class Cam {
        double t; String type; double fov = 80, scale = 1; boolean hud; double[] p;
        Cam(double t, String type, double fov, boolean hud, double scale, double... p) { this.t = t; this.type = type; this.fov = fov; this.hud = hud; this.scale = scale; this.p = p; }
    }
    private static void cam(double t, String type, double fov, boolean hud, double scale, double... p) { PLAN.add(new Cam(t, type, fov, hud, scale, p)); }

    private static void buildPlan(String name) {
        PLAN.clear();
        switch (name) {
            case "chase" -> {   // third person behind the drone, whole flight
                cam(0, "orbit", 62, false, 2, 9.0, 2.4, 0.2, 200, 1.0);
                cam(T_GO, "chase", 76, false, 3, 2.4, 0.8, 5, 0.08);
            }
            case "fpv" -> {     // drone camera, clean picture
                cam(0, "orbit", 62, false, 2, 9.0, 2.4, 0.2, 200, 1.0);
                cam(T_GO - 0.3, "fpv", -1, false, 1);
            }
            case "osd" -> {     // drone camera with the on-screen display
                cam(0, "orbit", 62, false, 2, 9.0, 2.4, 0.2, 200, 1.0);
                cam(T_GO - 0.3, "fpv", -1, true, 1);
            }
            case "multi" -> {   // tracking cameras placed along the course (course coordinates x, h, z)
                cam(0, "orbit", 62, false, 2, 9.0, 2.4, 0.2, 200, 1.0);
                cam(2.8, "static", 72, false, 3, 16, 1.6, 5, 0.1);
                cam(5.2, "static", 72, false, 3, 47, 1.6, -3, 0.1);
                cam(8.5, "static", 70, false, 3, 84, 1.8, 5, 0.1);
                cam(11.0, "static", 62, false, 3, 90, 2.2, 14, 0.1);
                cam(14.0, "static", 70, false, 3, 166, 2.0, 3, 0.1);
                cam(16.2, "static", 70, false, 3, 200, 1.5, -14, 0.1);
                cam(18.0, "static", 70, false, 3, 252, 3.5, 6, 0.1);
                cam(20.8, "static", 62, false, 3, 306, 1.5, 22, 0.1);
                cam(27.0, "static", 50, false, 3, 400, 17, -24, 0.12);
                cam(36.8, "static", 55, false, 3, 484, 20, -26, 0.12);
                cam(44.2, "static", 70, false, 3, 540, 4, 22, 0.1);
            }
            case "var" -> {     // other third-person rigs per course section
                cam(0, "orbit", 62, false, 2, 9.0, 2.4, 0.2, 200, 1.0);
                cam(T_GO, "side", 74, false, 3, 3.4, 0.6, 1.5);
                cam(8.0, "front", 76, false, 3, 3.4, 0.8, 0.2);
                cam(14.5, "front", 76, false, 3, 3.6, 1.0, 0.2);
                cam(21.0, "side", 74, false, 3, 3.6, 0.5, 1.0);
                cam(24.2, "high", 74, false, 3, 5.0, 4.5);
                cam(28.5, "high", 72, false, 3, 7.5, 7.0);
                cam(36.5, "side", 72, false, 3, 7.0, 2.5, 2.0);
                cam(44.0, "front", 76, false, 3, 4.5, 1.0, 0.2);
            }
            case "fw" -> {      // finish line with fireworks
                cam(0, "orbit", 62, false, 2, 9.0, 2.4, 0.2, 200, 1.0);
                cam(44.0, "static", 70, false, 3, 510, 3.0, -6, 0.1);
                cam(46.6, "lookat", 74, false, 3, 506, 3.4, -8, 534, 16, 9);
            }
            case "wing" -> {    // two pilots: body + drone of the second pilot are drawn like another player's
                cam(0, "orbit", 62, false, 2, 9.5, 2.5, 0.2, 200, 1.0);
                cam(T_GO, "high", 72, false, 3, 7.0, 4.5);
            }
            case "f5" -> {      // the mod's own F5 camera cycle: FPV -> chase -> front, with the OSD
                cam(0, "orbit", 62, false, 2, 9.0, 2.4, 0.2, 200, 1.0);
                cam(T_GO - 0.3, "fpv", -1, true, 1);
                cam(T_GO + 3.7, "f5chase", -1, true, 2);
                cam(T_GO + 7.7, "f5front", -1, true, 2);
                cam(T_GO + 11.7, "fpv", -1, true, 1);
            }
            case "cal" -> cam(0, "orbit", 70, false, 1, 9, 2.4, 0.1, 200, 0);
            case "survey" -> {
                cam(0, "orbit", 70, false, 1, 5.5, 1.7, 0.25, 200, 1.0);
                int i = 0;
                for (double tt = T_GO; tt < 60; tt += 3.5, i++) {
                    switch (i % 5) {
                        case 0 -> cam(tt, "chase", 78, false, 3, 2.8, 0.9, 5, 0.3);
                        case 1 -> cam(tt, "fpv", -1, true, 1);
                        case 2 -> cam(tt, "front", 75, false, 3, 3.2, 0.5, 0.25);
                        case 3 -> cam(tt, "side", 70, false, 3, 3.5, 0.5, 1.0);
                        default -> cam(tt, "high", 70, false, 3, 6, 5);
                    }
                }
            }
            default -> cam(0, "chase", 85, false, 3, 2.8, 0.9, 5, 0.3);
        }
    }

    // ------------------------------------------------------------------ phases
    private static void applyOptions(Minecraft mc) {
        var o = mc.options;
        o.renderDistance().set(Integer.getInteger("fpvcore.demo.rd", 16));
        o.simulationDistance().set(8);
        o.framerateLimit().set(260);
        o.enableVsync().set(false);
        o.guiScale().set(3);
        o.pauseOnLostFocus = false;
        o.showSubtitles().set(false);
        o.bobView().set(false);
        o.hideGui = true;
        o.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
    }

    private static void tick(Minecraft mc) {
        Session S = Session.I;
        switch (phase) {
            case TITLE -> {
                if (mc.screen instanceof TitleScreen && ++wait > 40) {
                    String w = world();
                    FpvClient.LOG.info("DEMO opening world {} (exists={})", w, mc.getLevelSource().levelExists(w));
                    if (mc.getLevelSource().levelExists(w)) mc.createWorldOpenFlows().openWorld(w, () -> mc.setScreen(new TitleScreen()));
                    else {
                        LevelSettings ls = new LevelSettings(w, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                        mc.createWorldOpenFlows().createFreshLevel(ls.levelName(), ls, new WorldOptions(2024L, false, false),
                            ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
                    }
                    phase = Phase.LOADING; wait = 0;
                }
            }
            case LOADING -> {
                if (mc.level != null && mc.player != null && mc.getSingleplayerServer() != null && mc.player.tickCount > 30) {
                    applyOptions(mc);
                    S.cfg.fovHorizontal = Double.parseDouble(System.getProperty("fpvcore.demo.fov", "110"));
                    S.cfg.droneVolume = 0;
                    phase = Phase.BUILD; wait = 0;
                    MinecraftServer srv = mc.getSingleplayerServer();
                    srv.execute(() -> {
                        var lvl = srv.overworld();
                        if (!CourseBuilder.built(lvl)) { FpvClient.LOG.info("DEMO building the course ..."); CourseBuilder.queue(lvl, path); buildQueued = true; }
                        else { FpvClient.LOG.info("DEMO course already built"); buildQueued = true; buildDone = true; buildDoneTick = srv.getTickCount() - 1000; }
                    });
                }
            }
            case BUILD -> {
                var srv = mc.getSingleplayerServer();
                if (srv != null && buildDone && srv.getTickCount() - buildDoneTick > 400) { phase = Phase.SETTLE; wait = 0; }
                if (++wait % 100 == 0) FpvClient.LOG.info("DEMO build {}/{} done={}", CourseBuilder.done, CourseBuilder.total, buildDone);
            }
            case SETTLE -> {
                var srv = mc.getSingleplayerServer();
                if (wait == 0) {
                    srv.execute(() -> {
                        for (String c : new String[]{"gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false", "gamerule doFireTick false",
                            "gamerule randomTickSpeed 0", "gamerule announceAdvancements false", "gamerule sendCommandFeedback false", "gamerule doEntityDrops false",
                            "time set " + System.getProperty("fpvcore.demo.time", "11700"), "weather clear 1000000", "difficulty peaceful", "kill @e[type=!player,type=!fpvcore:pilot_body]"})
                            srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack().withSuppressedOutput(), c);
                    });
                }
                if (wait % 40 == 0) srv.execute(() -> srv.getPlayerList().getPlayers().get(0).teleportTo(srv.overworld(), CoursePath.OX, CoursePath.OY, CoursePath.OZ, -90f, 0f));
                wait++;
                mc.player.setYRot(-90f); mc.player.setXRot(0f);
                boolean at = Math.abs(mc.player.getX() - CoursePath.OX) < 0.05 && Math.abs(mc.player.getZ() - CoursePath.OZ) < 0.05 && Math.abs(mc.player.getY() - CoursePath.OY) < 0.5;
                if (wait > 200 && at && mc.levelRenderer.hasRenderedAllSections()) { phase = Phase.SPAWN; wait = 0; }
                if (wait % 100 == 0) FpvClient.LOG.info("DEMO settle player=({}, {}, {}) at={}", mc.player.getX(), mc.player.getY(), mc.player.getZ(), at);
            }
            case SPAWN -> {
                wait++;
                if (wait == 1) {
                    mc.player.setYRot(-90f); mc.player.yRotO = -90f;
                    S.spawn(mc, "5race");
                    S.setFlying(mc, true);
                    pilot = new PathPilot("5race");
                    if (planName.equals("wing")) { pilot2 = new PathPilot("5race"); setupWingman(mc); }
                }
                if (wait > 20) { phase = Phase.RUN; wait = 0; tick = 0; }
            }
            case RUN -> {
                if (finished) { phase = Phase.DONE; wait = 0; }
            }
            case DONE -> {
                if (++wait > 20) { FpvClient.LOG.info("DEMO done"); mc.stop(); }
            }
        }
    }

    private static void serverTick(MinecraftServer srv) {
        if (phase == Phase.RUN && srv.getTickCount() % 100 == 0) srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack().withSuppressedOutput(), "weather clear 1000000");
        if (phase == Phase.RUN && srv.getTickCount() % 5 == 0)
            srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack().withSuppressedOutput(), "kill @e[type=!player,type=!fpvcore:pilot_body,type=!fpvcore:drone]");
        if (!buildQueued) return;
        if (!buildDone) {
            if (CourseBuilder.run(30)) { buildDone = true; buildDoneTick = srv.getTickCount(); srv.saveEverything(false, false, false); FpvClient.LOG.info("DEMO course built, saved"); }
        }
    }

    // ------------------------------------------------------------------ wingman (second scripted pilot: drone + body, drawn like another player's)
    private static void setupWingman(Minecraft mc) {
        wing = new Drone("5race");
        wingCol = new Collider();
        wing.param(FpvNative.PARAM_CAM_UPTILT_DEG, 25);
        wing.reset(CoursePath.OX + 1.5, CoursePath.OY + 0.12, CoursePath.OZ + WZ, -90);
        PilotBodyEntity b = new PilotBodyEntity(ModEntities.PILOT, mc.level);
        b.setPos(CoursePath.OX, CoursePath.OY, CoursePath.OZ + WZ);
        b.setYRot(-90f); b.yRotO = -90f; b.setYHeadRot(-90f); b.yHeadRotO = -90f; b.yBodyRot = -90f; b.yBodyRotO = -90f;
        b.getEntityData().set(PilotBodyEntity.OWNER, Optional.of(new UUID(0x1234abcdL, 0x7777L)));
        b.setCustomName(Component.literal("Wingman")); b.setCustomNameVisible(true);
        mc.level.addEntity(b);
        wingActive = true;
    }
    private static final double WZ = 3.0, W_DELAY = -0.3;

    private static final PathRef WING_REF = new PathRef() {
        final Vector3d off = new Vector3d(0, 0, WZ);
        @Override public Vector3d pos(double t) { return path.pos(t - W_DELAY).add(off); }
        @Override public Vector3d vel(double t) { return path.vel(t - W_DELAY); }
        @Override public Vector3d acc(double t) { return path.acc(t - W_DELAY); }
    };

    // ------------------------------------------------------------------ per frame
    private static Cam curCam;
    private static boolean snap;

    /** Called at the start of every rendered frame (from Session.frame) while the drone exists. */
    public static void preFrame(Minecraft mc) {
        Session S = Session.I;
        if (phase != Phase.RUN || !S.flying || S.drone == null) return;
        mc.gui.setOverlayMessage(Component.empty(), false);
        mc.getToasts().clear();
        mc.level.setDayTime(Long.getLong("fpvcore.demo.time", 11700L));   // the client advances its sky time on its own: pin it
        mc.level.setRainLevel(0f); mc.level.setThunderLevel(0f);
        if (!recording) {
            S.ctl.override = idle(false);
            boolean bodyHere = false;
            for (var e : mc.level.entitiesForRendering()) if (e instanceof PilotBodyEntity pb && pb.getCustomName() != null && pb.getCustomName().getString().equals(mc.player.getName().getString())) bodyHere = true;
            if (S.linked && bodyHere && mc.levelRenderer.hasRenderedAllSections()) readyFrames++; else readyFrames = 0;
            if (readyFrames > 90) { DemoRecorder.start(mc, planName); writeMarks(); recording = true; DemoRecorder.csvRow = DemoDirector::csv; tick = 0; }
            else return;
        }
        if (S.waitingChunks) return;
        if (planName.equals("cal")) { calFrame(mc, S); return; }
        double tv = tick * DemoHooks.DT, tp = tv - T_GO;
        Mapping.Sticks st;
        FpvNative.State s = S.drone.state();
        if (tv < T_ARM) st = idle(false);
        else if (tv < T_GO) st = idle(true);
        else if (tp <= disarmT) { if (tp < 1e-6) pilot.reset(); st = pilot.control(s, path, tp, DemoHooks.DT, ORG, false); }
        else st = idle(false);
        S.ctl.override = st;
        // wingman
        if (wingActive && tv >= T_ARM + 0.4) {
            double wt = tv - T_GO;
            Mapping.Sticks ws;
            FpvNative.State w = wing.state();
            if (wt < 0) ws = idle(true); else { if (wt < 1e-6) pilot2.reset(); ws = pilot2.control(w, WING_REF, wt, DemoHooks.DT, ORG, false); }
            if (tv > wingEndT()) { wingActive = false; }
            else {
                wing.setInput(ws.throttle, ws.yaw, ws.pitch, ws.roll, ws.arm, 1);
                wingCol.traces(mc.level, mc.player, wing, w);
                double[] prev = {w.pos[0], w.pos[1], w.pos[2]};
                wing.step(DemoHooks.DT);
                wingCol.resolve(mc.level, mc.player, wing, prev, 0.1);
            }
        }
        selectCam(mc, S, tv);
        // fireworks at the finish
        if (tp >= finishT && !fw) { fw = true; fireworks(mc, s); }
        if (fw) fireworkTick(mc);
        // end of the run
        if (tp > path.duration() + 1 && S.fpvStateSpeed < 0.05 && S.drone.resting()) restFrames++; else restFrames = 0;
        if (restFrames > 100 || tv > T_GO + path.duration() + 25 || (maxT > 0 && tv > maxT)) { FpvClient.LOG.info("DEMO camera-near-block frames (non-orbit): {}", nearBlockFrames); DemoRecorder.stop(); recording = false; finished = true; S.land(mc); }
        tick++;
    }
    private static void selectCam(Minecraft mc, Session S, double tv) {
        Cam c = PLAN.get(0);
        for (Cam k : PLAN) if (k.t <= tv) c = k;
        if (c != curCam) { curCam = c; snap = true; }
        S.view = c.type.equals("fpv") ? 0 : c.type.equals("f5front") ? 2 : 1;
        if (mc.screen == null) { mc.options.hideGui = !c.hud && !planName.equals("wing"); DemoHooks.hideOsd = !c.hud; }   // wing: keep name tags (Minecraft hides them with the GUI)
        DemoHooks.fovV = c.type.equals("fpv") || c.type.startsWith("f5") ? -1 : c.fov;
        DemoHooks.droneScale = (float) c.scale;
    }

    /** "cal" plan: the controller screen with a simulated transmitter (no system device) instead of a flight. */
    private static void calFrame(Minecraft mc, Session S) {
        double t = tick * DemoHooks.DT;
        selectCam(mc, S, t);
        if (tick == 0) { Controller.demoAxes = new float[]{0, 0, -1, 0, -1, -1, 0, 0}; S.ctl.poll(S.cfg); }
        if (tick == 24) mc.setScreen(new CalibrationScreen());
        if (tick == 200 && mc.screen instanceof CalibrationScreen cs) cs.demoEdit();
        float[] a = Controller.demoAxes;
        double ramp = Math.min(1, t / 1.5);
        a[0] = (float) (0.85 * ramp * Math.sin(1.7 * t));
        a[1] = (float) (0.85 * ramp * Math.cos(1.3 * t));
        a[3] = (float) (0.8 * ramp * Math.sin(0.9 * t + 1));
        a[2] = (float) (-1 + 1.7 * ramp * (0.5 + 0.5 * Math.sin(0.8 * t - 1.2)));
        a[4] = t > 5.0 ? 1f : -1f;
        a[5] = t > 7.5 ? 0f : -1f;
        if (t > 11.5) { mc.setScreen(null); Controller.demoAxes = null; DemoRecorder.stop(); recording = false; finished = true; S.land(mc); }
        tick++;
    }

    private static final double maxT = Double.parseDouble(System.getProperty("fpvcore.demo.maxt", "0"));
    private static double wingEndT() { return Double.parseDouble(System.getProperty("fpvcore.demo.wingend", "9.5")); }

    private static void writeMarks() {
        try {
            StringBuilder sb = new StringBuilder("{\"T_GO\":" + T_GO + ",\"duration\":" + path.duration() + ",\"marks\":[");
            for (int i = 0; i < path.marks.size(); i++) { Path.Mark m = path.marks.get(i); sb.append(i > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "{\"tag\":\"%s\",\"tv\":%.3f,\"x\":%.1f,\"h\":%.1f,\"z\":%.1f}", m.tag, m.t + T_GO, m.pos.x, m.pos.y, m.pos.z)); }
            java.nio.file.Files.writeString(DemoRecorder.outDir().resolve(planName + ".marks.json"), sb.append("]}").toString());
        } catch (java.io.IOException e) { FpvClient.LOG.warn("marks: {}", e.toString()); }
    }

    private static Mapping.Sticks idle(boolean arm) { Mapping.Sticks s = new Mapping.Sticks(); s.throttle = -1; s.arm = arm; s.mode = 1; return s; }

    // ------------------------------------------------------------------ fireworks
    private static boolean fw; private static int fwT;
    private static Vector3d fwPos = new Vector3d();
    private static void fireworks(Minecraft mc, FpvNative.State s) {
        Path.Mark m = null; for (Path.Mark k : path.marks) if (k.tag.equals("finish")) m = k;
        fwPos.set(CoursePath.OX + m.pos.x, CoursePath.OY + m.pos.y, CoursePath.OZ + m.pos.z); fwT = 0;
    }
    private static final int[][] FW = {{0xFF3030, 0xFFD040}, {0x30C0FF, 0xFFFFFF}, {0x60FF60, 0xFFFF80}, {0xFF40C0, 0xFFA0E0}, {0xFFA020, 0xFF4020}};
    private static void fireworkTick(Minecraft mc) {
        fwT++;
        if (fwT % 6 == 1 && fwT < 120) {
            int i = (fwT / 6) % FW.length;
            double ox = 6 + 22 * ((fwT * 37 % 100) / 100.0), oz = (((fwT * 53) % 100) / 100.0 - 0.5) * 36, oy = 12 + 14 * (((fwT * 71) % 100) / 100.0);
            var ex = new FireworkExplosion(i % 2 == 0 ? FireworkExplosion.Shape.LARGE_BALL : FireworkExplosion.Shape.STAR, IntList.of(FW[i][0]), IntList.of(FW[i][1]), true, i % 2 == 1);
            mc.level.createFireworks(fwPos.x + ox, fwPos.y + oy, fwPos.z + oz, 0, 0.0, 0, List.of(ex));
        }
    }

    // ------------------------------------------------------------------ camera
    private static int nearBlockFrames;
    private static final Vector3d cp = new Vector3d(), look = new Vector3d(), headH = new Vector3d(1, 0, 0);
    private static double k(double tau) { return 1 - Math.exp(-DemoHooks.DT / tau); }

    /** Called from Session.updateCamera (after the physics step): free camera rigs. */
    public static void camera(Session S) {
        if (curCam == null || !recording) { DemoHooks.camOn = false; return; }
        FpvNative.State s = S.drone.state();
        Vector3d dp = new Vector3d(s.pos[0], s.pos[1], s.pos[2]), v = new Vector3d(s.linvel[0], s.linvel[1], s.linvel[2]);
        double spd = v.length();
        Vector3d vdir = spd > 2 ? new Vector3d(v).div(spd) : new Vector3d(Collider.bodyNose(s)[0], Collider.bodyNose(s)[1], Collider.bodyNose(s)[2]);
        double tv = tick * DemoHooks.DT;
        Cam c = curCam;
        String t = c.type;
        if (t.equals("fpv") || t.startsWith("f5")) { DemoHooks.camOn = false; snap = false; return; }
        Vector3d want = new Vector3d(), tgt = new Vector3d();
        double sm = 1;
        switch (t) {
            case "chase" -> { // behind and above, heading smoothed: p = back, up, lead, tauPos
                Vector3d hd = new Vector3d(vdir.x, 0, vdir.z); if (hd.length() < 0.2) hd.set(headH); hd.normalize();
                if (snap) headH.set(hd); else { headH.lerp(hd, k(0.45)); headH.normalize(); }
                want.set(dp).fma(-c.p[0], headH); want.y += c.p[1];
                tgt.set(dp).fma(c.p[2] * 0.15, v.length() > 1 ? vdir : headH);
                sm = c.p.length > 3 ? c.p[3] : 0.3;
                if (snap) look.set(tgt);
                cp.lerp(want, snap ? 1 : k(sm)); look.lerp(tgt, snap ? 1 : k(0.12));
            }
            case "orbit" -> { // p = radius, height, rad/s, startDeg, lookMix (0 = pilot, 1 = drone)
                double ang = Math.toRadians(c.p[3]) + c.p[2] * (tv - c.t);
                Vector3d centre = new Vector3d(CoursePath.OX, CoursePath.OY + 1.0, CoursePath.OZ);
                want.set(centre.x + Math.cos(ang) * c.p[0], CoursePath.OY + c.p[1], centre.z + Math.sin(ang) * c.p[0]);
                tgt.set(centre).lerp(dp, Math.min(1, c.p[4] * Math.max(0, (dp.y - CoursePath.OY - 0.2) / 3)));
                tgt.y = Math.max(tgt.y, CoursePath.OY + 0.8);
                cp.set(want); look.set(tgt);
            }
            case "front" -> { // in front of the drone looking back at it: dist, height, tau
                want.set(dp).fma(c.p[0], vdir); want.y += c.p[1];
                if (snap) look.set(dp);
                cp.lerp(want, snap ? 1 : k(c.p.length > 2 ? c.p[2] : 0.25)); look.lerp(dp, snap ? 1 : k(0.1));
            }
            case "static" -> { // fixed camera in course coordinates: x, h, z, tauLook
                want.set(CoursePath.OX + c.p[0], CoursePath.OY + c.p[1], CoursePath.OZ + c.p[2]);
                cp.set(want);
                if (snap) look.set(dp);
                look.lerp(dp, snap ? 1 : k(c.p.length > 3 ? c.p[3] : 0.08));
            }
            case "lookat" -> { // fixed camera looking at a fixed point (course coordinates): x, h, z, tx, th, tz
                cp.set(CoursePath.OX + c.p[0], CoursePath.OY + c.p[1], CoursePath.OZ + c.p[2]);
                look.set(CoursePath.OX + c.p[3], CoursePath.OY + c.p[4], CoursePath.OZ + c.p[5]);
            }
            case "side" -> { // beside the drone, relative to its heading: right offset, up, behind
                Vector3d hd = new Vector3d(vdir.x, 0, vdir.z); if (hd.length() < 0.2) hd.set(headH); hd.normalize();
                if (snap) headH.set(hd); else { headH.lerp(hd, k(0.5)); headH.normalize(); }
                Vector3d right = new Vector3d(headH).cross(new Vector3d(0, 1, 0)).negate();
                want.set(dp).fma(c.p[0], right).fma(-c.p[2], headH); want.y += c.p[1];
                if (snap) look.set(dp);
                cp.lerp(want, snap ? 1 : k(0.3)); look.lerp(dp, snap ? 1 : k(0.1));
            }
            case "high" -> { // high above looking down at the drone: dist behind, height
                Vector3d hd = new Vector3d(vdir.x, 0, vdir.z); if (hd.length() < 0.2) hd.set(headH); hd.normalize();
                if (snap) headH.set(hd); else { headH.lerp(hd, k(0.6)); headH.normalize(); }
                want.set(dp).fma(-c.p[0], headH); want.y += c.p[1];
                if (snap) look.set(dp);
                cp.lerp(want, snap ? 1 : k(0.5)); look.lerp(dp, snap ? 1 : k(0.1));
            }
            default -> { }
        }
        if (snap && (t.equals("chase") || t.equals("front") || t.equals("side") || t.equals("high"))) cp.set(want);
        snap = false;
        // keep free cameras out of blocks: pull in to the first hit between the drone and the camera
        var mc = Minecraft.getInstance();
        if (!t.equals("static") && !t.equals("orbit") && !t.equals("lookat") && mc.level != null) {
            Vec3 from = new Vec3(dp.x, dp.y + 0.05, dp.z), to = new Vec3(cp.x, cp.y, cp.z);
            var hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) { Vec3 d = to.subtract(from).normalize(); Vec3 l = hit.getLocation().subtract(d.scale(0.5)); cp.set(l.x, l.y, l.z); }
        }
        if (mc.level != null && mc.level.getBlockCollisions(null, new AABB(cp.x - 0.45, cp.y - 0.45, cp.z - 0.45, cp.x + 0.45, cp.y + 0.45, cp.z + 0.45)).iterator().hasNext()) {
            if (!t.equals("orbit")) nearBlockFrames++;
            if (!t.equals("orbit") && nearBlockFrames < 80) FpvClient.LOG.info("DEMO camera within 0.45 m of a block at t={} rig={} pos=({}, {}, {})", String.format("%.2f", tv), t, (int) cp.x, (int) cp.y, (int) cp.z);
        }
        Vector3f fwd = new Vector3f((float) (look.x - cp.x), (float) (look.y - cp.y), (float) (look.z - cp.z));
        if (fwd.lengthSquared() < 1e-6) fwd.set(1, 0, 0);
        fwd.normalize();
        Vector3f up = new Vector3f(0, 1, 0);
        Vector3f right = new Vector3f(fwd).cross(up).normalize();
        Vector3f upo = new Vector3f(right).cross(fwd);
        DemoHooks.camQ.setFromNormalized(new Matrix3f(right, upo, new Vector3f(fwd).negate())).normalize();
        DemoHooks.camX = cp.x; DemoHooks.camY = cp.y; DemoHooks.camZ = cp.z;
        DemoHooks.camOn = true;
    }

    // ------------------------------------------------------------------ extras drawn in the world
    private static void renderExtras(WorldRenderContext ctx) {
        if (!wingActive || wing == null) return;
        var ps = ctx.matrixStack(); var mb = ctx.consumers();
        if (ps == null || mb == null) return;
        FpvNative.State w = wing.state();
        var cam = ctx.camera().getPosition();
        double rpm = (w.rpm[0] + w.rpm[1] + w.rpm[2] + w.rpm[3]) / 4;
        wingPropAngle += (float) (DemoHooks.DT * DroneRenderer.spinRevPerSec(rpm, w.armed != 0) * 2 * Math.PI) * (DemoHooks.frozen ? 0 : 1);
        ps.pushPose();
        ps.translate(w.pos[0] - cam.x, w.pos[1] - cam.y, w.pos[2] - cam.z);
        float sc = (float) Session.I.cfg.remoteDroneScale;
        ps.scale(sc, sc, sc);
        DroneRenderer.drawModel(ps, mb, new Quaternionf((float) w.quat[0], (float) w.quat[1], (float) w.quat[2], (float) w.quat[3]), w.armed != 0, (float) wingPropAngle, rpm);
        ps.popPose();
        if (mb instanceof net.minecraft.client.renderer.MultiBufferSource.BufferSource bs) bs.endBatch(net.minecraft.client.renderer.RenderType.debugFilledBox());
        // name tag
        var mc = Minecraft.getInstance();
        ps.pushPose();
        ps.translate(w.pos[0] - cam.x, w.pos[1] + 0.55 - cam.y, w.pos[2] - cam.z);
        ps.mulPose(ctx.camera().rotation());
        ps.scale(-0.025f, -0.025f, 0.025f);
        String name = "Wingman";
        float x = -mc.font.width(name) / 2f;
        mc.font.drawInBatch(name, x, 0, 0xFFFFFFFF, false, ps.last().pose(), mb, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, 0x40000000, 0xF000F0);
        ps.popPose();
    }

    // ------------------------------------------------------------------ telemetry row for the audio render
    private static String csv() {
        Session S = Session.I;
        FpvNative.State s = S.drone.state();
        double rpm = (s.rpm[0] + s.rpm[1] + s.rpm[2] + s.rpm[3]) / 4;
        Quaternionf q = new Quaternionf((float) s.quat[0], (float) s.quat[1], (float) s.quat[2], (float) s.quat[3]);
        float dot = Math.min(1f, Math.abs(prevQ.dot(q)));
        angRate = Math.toDegrees(2.0 * Math.acos(dot)) * DemoHooks.FPS;
        prevQ.set(q);
        double wr = 0, wd = 0;
        if (wingActive && wing != null) { FpvNative.State w = wing.state(); wr = (w.rpm[0] + w.rpm[1] + w.rpm[2] + w.rpm[3]) / 4; wd = Math.sqrt(Math.pow(w.pos[0] - DemoHooks.camX, 2) + Math.pow(w.pos[1] - DemoHooks.camY, 2) + Math.pow(w.pos[2] - DemoHooks.camZ, 2)); }
        return String.format(java.util.Locale.ROOT, "%.4f,%d,%.1f,%.3f,%.2f,%.1f,%.1f,%.1f,%.2f,%.2f,%.2f,%.2f", tick * DemoHooks.DT, s.armed, rpm, S.sticks.throttle, S.fpvStateSpeed, S.collider.lastImpulse, angRate, wr, wd, s.pos[0], s.pos[1], s.pos[2]);
    }
}
