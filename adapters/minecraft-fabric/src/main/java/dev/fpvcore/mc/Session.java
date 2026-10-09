package dev.fpvcore.mc;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Client-side drone session: owns the fpvcore drone, steps it once per rendered frame, drives camera/collisions. */
public final class Session {
    public static final Session I = new Session();

    public Config cfg = Config.load();
    public final Controller ctl = new Controller();
    public final Collider collider = new Collider();
    public Drone drone;
    public boolean flying;      // camera attached to the drone, player input blocked
    /** Camera view while flying (vanilla "Toggle Perspective" key cycles it): 0 = FPV, 1 = third person behind the drone, 2 = third person in front, looking at it. */
    public int view;
    public static final String[] VIEWS = {"FPV camera", "chase camera (behind)", "front camera"};
    public boolean thirdPerson() { return view != 0; }
    public void cycleView(Minecraft mc) { view = (view + 1) % 3; msg(mc, VIEWS[view]); }
    public Mapping.Sticks sticks = new Mapping.Sticks();
    public long frames;
    public double lastDt;
    private long lastNs;

    // camera output, valid after frame()
    public final Quaternionf camQ = new Quaternionf();
    public double camX, camY, camZ;
    public boolean camValid;

    public double fpvStateSpeed;
    public float propAngle;

    private static final boolean NO_SERVER_MOD = System.getProperty("fpvcore.noservermod") != null; // dev: simulate a server without fpvcore
    // multiplayer / chunk following
    public boolean waitingChunks;          // sim paused: chunk ahead of the drone is not loaded (yet)
    public boolean linked;                 // the server acknowledged our flight start (LinkPayload)
    private long linkWaitNs;               // when the flight start was sent (ack timeout)
    private long lastSendNs;
    private boolean wasArmed;

    /** True when the server runs fpvcore: it then moves our player to the drone (chunks follow) and mirrors the drone to other players. */
    public boolean serverMod() { return !NO_SERVER_MOD && mc_play() && ClientPlayNetworking.canSend(PosePayload.TYPE); }
    /** Why serverMod() is false (or "ok"), for logs. */
    public String syncReason() {
        var mc = Minecraft.getInstance();
        if (NO_SERVER_MOD) return "disabled by -Dfpvcore.noservermod";
        if (mc.getConnection() == null || mc.level == null) return "not in a world";
        if (!ClientPlayNetworking.canSend(PosePayload.TYPE)) return "server does not accept fpvcore:pose (fpvcore not installed on the server)";
        return "ok";
    }

    /** OSD / log text for the current sync mode. */
    public String syncStatus() {
        if (!serverMod()) return "CLIENT-ONLY: range limited";
        if (linked) return "LINKED";
        if (flying && System.nanoTime() - linkWaitNs > 3_000_000_000L) return "CLIENT-ONLY: no answer from server";
        return flying ? "LINKING..." : "LINKED (idle)";
    }

    public void serverLinked(boolean on) {
        linked = on;
        FpvClient.LOG.info("server sync: LINKED (server acknowledged the flight start, {}ms after sending)", linkWaitNs == 0 ? -1 : (System.nanoTime() - linkWaitNs) / 1_000_000);
    }

    private static boolean mc_play() { var mc = Minecraft.getInstance(); return mc.getConnection() != null && mc.level != null; }

    private void sendPose(boolean flyingFlag) {
        if (drone == null || !serverMod()) return;
        FpvNative.State s = drone.state();
        double rpm = (s.rpm[0] + s.rpm[1] + s.rpm[2] + s.rpm[3]) / 4;
        ClientPlayNetworking.send(new PosePayload(flyingFlag, false, s.armed != 0, s.pos[0], s.pos[1], s.pos[2],
            (float) s.linvel[0], (float) s.linvel[1], (float) s.linvel[2],
            (float) s.quat[0], (float) s.quat[1], (float) s.quat[2], (float) s.quat[3], (float) rpm));
        lastSendNs = System.nanoTime();
    }

    /** The server ended our flight (body attacked, death, dimension change ...): the drone is despawned too. */
    public void serverEnded(Minecraft mc, String reason) {
        land(mc);
        msg(mc, "flight ended by the server: " + reason);
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[fpv] " + (reason.startsWith("Drone destroyed") || reason.startsWith("cannot") ? reason : "flight ended by the server: " + reason)), false);
    }

    /** True after the drone went down (the sim disarms on a crash) while the arm switch is still on: the pilot must flip it off and on, or press G. */
    public boolean crashed;

    /**
     * Ends the flight: camera back to the player, the drone is despawned (client + server entity) and the server puts the pilot back at the launch spot.
     * Used by G, /fpv land, /fpv return while flying, death, disconnects and server-initiated ends.
     */
    public void land(Minecraft mc) {
        boolean had = drone != null;
        flying = false; camValid = false; lastNs = 0; waitingChunks = false; crashed = false; linked = false;
        setWater(mc, false);
        if (mc != null && mc.player != null) { mc.player.noPhysics = false; mc.player.setNoGravity(false); }
        if (had && serverMod()) ClientPlayNetworking.send(PosePayload.goneNow());
        if (drone != null) { drone.close(); drone = null; }
        ctl.resetKeyboard();
        ctl.override = null;
    }

    public boolean exists() { return drone != null; }

    public void msg(Minecraft mc, String s) { if (mc.player != null) mc.player.displayClientMessage(Component.literal("[fpv] " + s), true); }

    /** (Re)create the drone from the preset and put it in front of the player. */
    public void spawn(Minecraft mc, String preset) {
        if (mc.player == null) return;
        if (drone != null) drone.close();
        cfg.preset = preset = Drone.canonicalId(preset);
        drone = new Drone(preset);
        inWater = false;
        applyConfig();
        placeAtPlayer(mc);
        ctl.resetKeyboard();
        cfg.save();
    }

    public void applyConfig() {
        if (drone == null) return;
        if (cfg.uptiltDeg >= 0) drone.param(FpvNative.PARAM_CAM_UPTILT_DEG, cfg.uptiltDeg);
        drone.param(FpvNative.PARAM_SIMULATE_BATTERY, cfg.simulateBattery ? 1 : 0);
        FpvNative.Rates r = drone.getRates();
        if (cfg.ratesType != null) r.type = cfg.ratesType;
        if (cfg.rc != null && cfg.rc.length == 3) System.arraycopy(cfg.rc, 0, r.rc, 0, 3);
        if (cfg.srate != null && cfg.srate.length == 3) System.arraycopy(cfg.srate, 0, r.srate, 0, 3);
        if (cfg.expo != null && cfg.expo.length == 3) System.arraycopy(cfg.expo, 0, r.expo, 0, 3);
        if (cfg.thrMid != null) r.thr_mid = cfg.thrMid;
        if (cfg.thrExpo != null) r.thr_expo = cfg.thrExpo;
        drone.applyRates();
    }

    /** Launch spot (the real player moves with the drone while linked, so "the player" is no longer the launch spot). */
    private double homeX, homeY, homeZ, homeYaw;
    private boolean haveHome;

    public void placeAtPlayer(Minecraft mc) {
        if (drone == null || mc.player == null) return;
        boolean h = flying && haveHome;
        double yaw = h ? homeYaw : mc.player.getYRot();
        double yr = Math.toRadians(yaw);
        double px = h ? homeX : mc.player.getX(), py = h ? homeY : mc.player.getY(), pz = h ? homeZ : mc.player.getZ();
        double x = px - Math.sin(yr) * 1.5, z = pz + Math.cos(yr) * 1.5;
        drone.reset(x, py + cfg.droneRadius + 0.02, z, yaw);
        ctl.kbArm = false;
        camValid = false;
    }

    public void setFlying(Minecraft mc, boolean f) {
        if (f && drone == null) spawn(mc, cfg.preset);
        boolean was = flying;
        if (f && !was && mc.player != null) { homeX = mc.player.getX(); homeY = mc.player.getY(); homeZ = mc.player.getZ(); homeYaw = mc.player.getYRot(); haveHome = true; }
        flying = f && drone != null;
        if (!flying) camValid = false;
        lastNs = 0;
        waitingChunks = false;
        wasArmed = false; crashed = false;
        if (flying != was) { linked = false; linkWaitNs = System.nanoTime(); sendPose(flying); }
        if (flying && flying != was) {
            if (serverMod()) FpvClient.LOG.info("server sync: available (flight start sent, waiting for the server acknowledgement)");
            else FpvClient.LOG.warn("server sync: unavailable, {}", syncReason());
        }
        if (flying && !serverMod()) mc.player.displayClientMessage(Component.literal("[fpv] server has no fpvcore: flight range is limited to the loaded chunks, other players will not see the drone"), false);
    }

    public void destroy() {
        if (drone != null) drone.close();
        inWater = false;
        drone = null; flying = false; camValid = false; crashed = false; linked = false;
    }

    /** Called at the start of every rendered frame (GameRenderer.renderLevel). */
    public void frame(Minecraft mc) {
        long now = System.nanoTime();
        double dt = lastNs == 0 ? 0 : (now - lastNs) / 1e9;
        lastNs = now;
        ClientLevel lvl = mc.level;
        if (drone == null || lvl == null || mc.player == null) { camValid = false; return; }
        if (dev.fpvcore.mc.demo.DemoHooks.ACTIVE) { // demo video mode: fixed timestep, director drives the sticks (see demo/)
            if (dev.fpvcore.mc.demo.DemoHooks.preFrame(mc)) { updateCamera(mc, drone.state()); return; }
            dt = dev.fpvcore.mc.demo.DemoHooks.DT;
        }
        if (mc.isPaused()) { updateCamera(mc, drone.state()); return; }
        dt = Math.min(dt, 0.1);
        lastDt = dt;
        if (flying) sticks = ctl.read(mc, cfg, dt);
        else { sticks = new Mapping.Sticks(); ctl.poll(cfg); }
        // an idle (not flown) drone keeps its last input: disarmed, lies on the ground
        drone.setInput(sticks.throttle, sticks.yaw, sticks.pitch, sticks.roll, flying && sticks.arm, sticks.mode);

        FpvNative.State s = drone.state();
        if (flying) {
            if (s.armed != 0) wasArmed = true;
            else if (wasArmed) { wasArmed = false; if (sticks.arm) { crashed = true; msg(mc, "crashed / disarmed: flip ARM off and on to re-arm, G to exit"); } }
            if (!sticks.arm) crashed = false;
        }
        // chunk ahead of the drone not loaded (yet): pause the simulation instead of flying through holes
        double look = 0.25;
        boolean loaded = chunkLoaded(lvl, s.pos[0], s.pos[2]) && chunkLoaded(lvl, s.pos[0] + s.linvel[0] * look, s.pos[2] + s.linvel[2] * look);
        waitingChunks = !loaded;
        if (!loaded) {
            fpvStateSpeed = 0;
            maybeSend();
            updateCamera(mc, s);
            return;
        }
        setWater(mc, wetAt(lvl, s.pos[0], s.pos[1], s.pos[2]));
        collider.traces(lvl, mc.player, drone, s);
        double[] prev = {s.pos[0], s.pos[1], s.pos[2]};
        drone.step(dt);
        collider.resolve(lvl, mc.player, drone, prev, cfg.droneRadius);
        s = drone.state();
        if (collider.lastImpulse > 50) {
            DroneAudio.crash(lvl, s.pos[0], s.pos[1], s.pos[2], collider.lastImpulse);
        }
        if (s.pos[1] < lvl.getMinBuildHeight() - 16) { placeAtPlayer(mc); msg(mc, "drone fell out of the world, returned"); }
        fpvStateSpeed = Math.sqrt(s.linvel[0] * s.linvel[0] + s.linvel[1] * s.linvel[1] + s.linvel[2] * s.linvel[2]);
        frames++;
        maybeSend();
        updateCamera(mc, s);
    }

    // ---- water: the drone is not damaged, but moves about half as fast (4x drag; terminal speed ~ 1/sqrt(drag)) ----
    public boolean inWater;
    public static final double WATER_DRAG = 4.0;
    private static final boolean FORCE_WATER = System.getProperty("fpvcore.forcewater") != null;
    private static boolean wetAt(ClientLevel lvl, double x, double y, double z) {
        return FORCE_WATER || lvl.getFluidState(net.minecraft.core.BlockPos.containing(x, y, z)).is(net.minecraft.tags.FluidTags.WATER);
    }
    private void setWater(Minecraft mc, boolean wet) {
        if (wet == inWater) return;
        inWater = wet;
        if (drone != null) drone.param(FpvNative.PARAM_DRAG_SCALE, wet ? WATER_DRAG : 1.0);
        if (wet && drone != null && mc != null && mc.level != null) {
            FpvNative.State s = drone.state();
            mc.level.playLocalSound(s.pos[0], s.pos[1], s.pos[2], SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 0.4f, 1.4f, false);
            for (int i = 0; i < 12; i++)
                mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SPLASH, s.pos[0] + (mc.level.random.nextDouble() - 0.5) * 0.4, s.pos[1], s.pos[2] + (mc.level.random.nextDouble() - 0.5) * 0.4, 0, 0.2, 0);
        }
        FpvClient.LOG.info("drone {} water (drag x{})", wet ? "entered" : "left", wet ? WATER_DRAG : 1.0);
    }

    private static boolean chunkLoaded(ClientLevel lvl, double x, double z) {
        return lvl.getChunkSource().hasChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    /** Pose to the server: ~30 Hz while flying, ~4 Hz when parked. */
    private void maybeSend() {
        long interval = flying ? 33_000_000L : 250_000_000L;
        if (System.nanoTime() - lastSendNs >= interval) sendPose(flying);
    }

    /**
     * The real (client) player travels with the drone while the server link is up, exactly like the server-side player: the vanilla level renderer builds its
     * section grid around the PLAYER (LevelRenderer.setupRender / ViewArea.repositionCamera), so without this the chunks the server streams around the drone
     * are received but never drawn. The player is invisible there (server flag); the stand-in body stays at the launch spot.
     */
    private void followPlayer(Minecraft mc, FpvNative.State s) {
        var p = mc.player;
        if (p == null || !linked || !serverMod()) return;
        p.noPhysics = true;
        p.setNoGravity(true);
        p.setPos(s.pos[0], s.pos[1], s.pos[2]);
        p.setOldPosAndRot();
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
    }

    private void updateCamera(Minecraft mc, FpvNative.State s) {
        if (!flying) { camValid = false; return; }
        followPlayer(mc, s);
        Vector3f fwd, up;
        if (view == 0) {
            camX = s.cam_pos[0]; camY = s.cam_pos[1]; camZ = s.cam_pos[2];
            fwd = new Vector3f((float) s.cam_forward[0], (float) s.cam_forward[1], (float) s.cam_forward[2]);
            up = new Vector3f((float) s.cam_up[0], (float) s.cam_up[1], (float) s.cam_up[2]);
        } else {
            double[] n = Collider.bodyNose(s);
            double h = Math.sqrt(n[0] * n[0] + n[2] * n[2]);
            double hx = h > 1e-3 ? n[0] / h : 0, hz = h > 1e-3 ? n[2] / h : -1;
            double d = cfg.chaseDistance;
            double sgn = view == 1 ? -1 : 1; // 1 = behind the drone, 2 = in front of it
            camX = s.pos[0] + sgn * hx * d; camY = s.pos[1] + 0.45; camZ = s.pos[2] + sgn * hz * d;
            // keep the third-person camera out of walls (pulled in to the first hit)
            Vec3 from = new Vec3(s.pos[0], s.pos[1] + 0.2, s.pos[2]), to = new Vec3(camX, camY, camZ);
            var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) { Vec3 l = hit.getLocation().subtract(to.subtract(from).normalize().scale(0.15)); camX = l.x; camY = l.y; camZ = l.z; }
            fwd = new Vector3f((float) (s.pos[0] - camX), (float) (s.pos[1] - camY), (float) (s.pos[2] - camZ));
            up = new Vector3f(0, 1, 0);
        }
        fwd.normalize();
        Vector3f right = new Vector3f(fwd).cross(up).normalize();
        Vector3f upo = new Vector3f(right).cross(fwd);
        // MC camera looks along local -Z: columns = right, up, -forward
        Matrix3f m = new Matrix3f(right, upo, new Vector3f(fwd).negate());
        camQ.setFromNormalized(m).normalize();
        if (dev.fpvcore.mc.demo.DemoHooks.ACTIVE) dev.fpvcore.mc.demo.DemoHooks.cameraOverride(this);
        camValid = true;
    }

    /** Vertical FOV for Minecraft from the configured horizontal FOV and window aspect. */
    public double verticalFov(Minecraft mc) {
        if (dev.fpvcore.mc.demo.DemoHooks.ACTIVE && dev.fpvcore.mc.demo.DemoHooks.fovV > 0) return dev.fpvcore.mc.demo.DemoHooks.fovV;
        double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(cfg.fovHorizontal) / 2) / aspect));
    }

    public boolean cameraActive() { return flying && camValid; }
}
