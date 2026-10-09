package dev.fpvcore.mc.demo;

import dev.fpvcore.mc.Session;
import net.minecraft.client.Minecraft;

/**
 * Switchboard for the demo-video mode (-Dfpvcore.demo=<take>, off by default): fixed simulation timestep, a virtual clock that advances exactly one
 * video frame per rendered frame, frame hold while chunks/sections are still loading, camera/FOV override. All other classes only call these
 * static hooks, so the mod behaves exactly as before when the property is absent.
 */
public final class DemoHooks {
    private DemoHooks() {}

    public static final String TAKE = System.getProperty("fpvcore.demo");
    public static final boolean ACTIVE = TAKE != null && !TAKE.isEmpty();
    public static final int FPS = Integer.getInteger("fpvcore.demo.fps", 60);
    public static final double DT = 1.0 / FPS;

    /** Camera override written by the director (valid when camOn). */
    public static boolean camOn;
    public static double camX, camY, camZ;
    public static final org.joml.Quaternionf camQ = new org.joml.Quaternionf();
    /** Vertical field of view in degrees for free cameras (<= 0: use the mod's FPV field of view). */
    public static double fovV = -1;
    /** Visual scale of the pilot's own drone model in free-camera shots. */
    public static float droneScale = 1f;

    /** True when this rendered frame repeats the previous state (waiting for chunk sections), nothing advances. */
    /** Do not draw the drone OSD (name tags still show). */
    public static boolean hideOsd;
    public static boolean frozen;
    /** Set by the sim hook when the frame advanced the world: only such frames are recorded. */
    public static boolean advanced;

    private static double clockMs;
    /** Virtual wall clock fed to Minecraft's DeltaTracker: one video frame (1000/FPS ms) per rendered frame. */
    public static long nextClockMs() { clockMs += 1000.0 / FPS; return (long) clockMs; }

    /** Called at the start of Session.frame. Returns true when the frame must not advance the simulation. */
    public static boolean preFrame(Minecraft mc) {
        if (frozen) return true;
        DemoDirector.preFrame(mc);
        return false;
    }

    /** Called at the end of Session.updateCamera: lets the director replace the camera. */
    public static void cameraOverride(Session s) {
        advanced = !s.waitingChunks;
        DemoDirector.camera(s);
        if (!camOn) return;
        s.camX = camX; s.camY = camY; s.camZ = camZ; s.camQ.set(camQ);
    }
}
