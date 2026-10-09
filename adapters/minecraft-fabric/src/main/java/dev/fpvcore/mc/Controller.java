package dev.fpvcore.mc;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/** RC controller via GLFW's joystick API (already in Minecraft), with a keyboard fallback. */
public final class Controller {
    public float[] axes = new float[0];
    public boolean[] buttons = new boolean[0];
    public int jid = -1;
    public String name = "";


    // keyboard state
    private double kThr = -1, kYaw, kPitch, kRoll;
    public boolean kbArm;
    public int kbMode = 1;

    /** Scripted sticks (dev autotest); when non-null it replaces all other input. */
    public volatile Mapping.Sticks override;

    /** Demo videos only: a simulated transmitter (8 axes, 24 buttons); no system input device is created. null = off. */
    public static float[] demoAxes;
    public static final String DEMO_NAME = "RC Transmitter (EdgeTX USB Joystick)";

    public boolean connected() { return jid >= 0; }

    /** One joystick slot as GLFW sees it. */
    public static final class Dev {
        public int jid; public String name = "", guid = ""; public int axes, buttons, hats; public boolean junk, steam;
        public String label() { return name + " (" + axes + " axes, " + buttons + " buttons" + (steam ? ", Steam virtual pad: not your radio" : junk ? ", touchpad: not auto-selected" : "") + ")"; }
    }

    private static boolean junkName(String n) {
        n = n.toLowerCase();
        return n.contains("touchpad") || n.contains("trackpad") || n.contains("synaptics") || n.contains("mouse") || n.contains("keyboard");
    }

    public static Dev dev(int i) {
        if (!GLFW.glfwJoystickPresent(i)) return null;
        Dev d = new Dev();
        d.jid = i;
        String n = GLFW.glfwGetJoystickName(i); d.name = n == null ? "joystick " + i : n;
        String g = GLFW.glfwGetJoystickGUID(i); d.guid = g == null ? "" : g;
        FloatBuffer a = GLFW.glfwGetJoystickAxes(i); d.axes = a == null ? 0 : a.remaining();
        ByteBuffer b = GLFW.glfwGetJoystickButtons(i); d.buttons = b == null ? 0 : b.remaining();
        ByteBuffer h = GLFW.glfwGetJoystickHats(i); d.hats = h == null ? 0 : h.remaining();
        String gl = d.guid.toLowerCase();
        d.steam = d.name.toLowerCase().contains("steam") || (gl.length() >= 12 && gl.substring(8, 12).equals("28de"));
        d.junk = d.steam || junkName(d.name);
        return d;
    }

    /** Every joystick GLFW reports (nothing is hidden; only touchpad-like names are flagged and never auto-selected). */
    public java.util.List<Dev> devices() {
        java.util.List<Dev> l = new java.util.ArrayList<>();
        if (demoAxes != null) { Dev d = new Dev(); d.jid = 0; d.name = DEMO_NAME; d.guid = "demo"; d.axes = demoAxes.length; d.buttons = 24; l.add(d); return l; }
        for (int i = GLFW.GLFW_JOYSTICK_1; i <= GLFW.GLFW_JOYSTICK_LAST; i++) { Dev d = dev(i); if (d != null) l.add(d); }
        return l;
    }

    /** Select a device (null = keyboard only) and activate its saved mapping. */
    public void select(Config cfg, Dev d) {
        if (d == null) { cfg.forceKeyboard = true; }
        else {
            cfg.forceKeyboard = false; cfg.controllerGuid = d.guid; cfg.controllerName = d.name; cfg.joystick = d.jid;
            cfg.mapping = cfg.mappings.computeIfAbsent(d.guid, k -> { Mapping m = new Mapping(); m.deviceGuid = d.guid; m.deviceName = d.name; return m; });
            cfg.mapping.deviceGuid = d.guid; cfg.mapping.deviceName = d.name;
        }
        kbArm = false; kbMode = 1;
        cfg.save();
        poll(cfg);
    }

    /** Best auto-select candidate: a real RC/gamepad (>= 4 axes, not a touchpad), else a non-touchpad device with >= 2 axes. */
    private Dev autoPick() {
        Dev best = null;
        for (Dev d : devices()) {
            if (d.junk || d.axes < 2) continue;
            boolean better = best == null || ((d.axes >= 4) != (best.axes >= 4) ? d.axes >= 4 : d.axes > best.axes);
            if (better) best = d;
        }
        return best;
    }

    /** Resolves which GLFW slot is the selected controller and refreshes raw state. Safe to call every frame. */
    public void poll(Config cfg) {
        if (demoAxes != null) {
            jid = 0; name = DEMO_NAME;
            if (axes.length != demoAxes.length) axes = new float[demoAxes.length];
            System.arraycopy(demoAxes, 0, axes, 0, axes.length);
            if (buttons.length != 24) buttons = new boolean[24];
            return;
        }
        int use = -1;
        if (!cfg.forceKeyboard) {
            String g = cfg.controllerGuid;
            if (g != null && !g.isEmpty()) {
                if (cfg.joystick >= 0 && GLFW.glfwJoystickPresent(cfg.joystick) && g.equals(GLFW.glfwGetJoystickGUID(cfg.joystick))) use = cfg.joystick;
                else for (int i = GLFW.GLFW_JOYSTICK_1; i <= GLFW.GLFW_JOYSTICK_LAST && use < 0; i++)
                    if (GLFW.glfwJoystickPresent(i) && g.equals(GLFW.glfwGetJoystickGUID(i))) { use = i; cfg.joystick = i; }
            } else {
                Dev d = autoPick();
                if (d != null) { select(cfg, d); return; }
            }
        }
        jid = use;
        if (use < 0) { axes = new float[0]; buttons = new boolean[0]; name = ""; return; }
        FloatBuffer a = GLFW.glfwGetJoystickAxes(use);
        ByteBuffer b = GLFW.glfwGetJoystickButtons(use);
        if (a == null) { axes = new float[0]; } else { if (axes.length != a.remaining()) axes = new float[a.remaining()]; a.get(0, axes); }
        if (b == null) { buttons = new boolean[0]; } else { if (buttons.length != b.remaining()) buttons = new boolean[b.remaining()]; for (int i = 0; i < buttons.length; i++) buttons[i] = b.get(i) != 0; }
        String n = GLFW.glfwGetJoystickName(use);
        name = n == null ? "joystick" : n;
    }

    // hot-plug: polled from the client tick
    private int lastMask = -1;
    private final String[] lastNames = new String[GLFW.GLFW_JOYSTICK_LAST + 1];

    /** Detects connect / disconnect and tells the user (toast + chat). Cheap: one presence check per slot per tick. */
    public void scan(Minecraft mc, Config cfg) {
        int mask = 0;
        for (int i = GLFW.GLFW_JOYSTICK_1; i <= GLFW.GLFW_JOYSTICK_LAST; i++) if (GLFW.glfwJoystickPresent(i)) mask |= 1 << i;
        if (mask == lastMask) return;
        int prev = lastMask < 0 ? 0 : lastMask;
        lastMask = mask;
        poll(cfg);
        for (int i = GLFW.GLFW_JOYSTICK_1; i <= GLFW.GLFW_JOYSTICK_LAST; i++) {
            boolean now = (mask >> i & 1) != 0, was = (prev >> i & 1) != 0;
            if (now && !was) {
                Dev d = dev(i);
                if (d == null) continue;
                lastNames[i] = d.name;
                if (d.junk && jid != i) continue;
                String sub = jid == i ? (cfg.mapping.calibrated ? "active" : "active, not calibrated: press K") : "not selected: press K or /fpv controller use";
                notify(mc, "Controller connected: " + d.name, d.axes + " axes, " + d.buttons + " buttons, " + sub);
            } else if (!now && was && lastNames[i] != null) {
                if (!junkName(lastNames[i])) notify(mc, "Controller disconnected: " + lastNames[i], "keyboard fallback active");
                lastNames[i] = null;
            }
        }
    }

    private static void notify(Minecraft mc, String title, String sub) {
        FpvClient.LOG.info("{} ({})", title, sub);
        try { net.minecraft.client.gui.components.toasts.SystemToast.add(mc.getToasts(), net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            net.minecraft.network.chat.Component.literal(title), net.minecraft.network.chat.Component.literal(sub)); } catch (RuntimeException ignored) {}
        if (mc.player != null) mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("[fpv] " + title + " (" + sub + ")"), false);
    }

    public String guid() {
        if (jid < 0) return "";
        String g = GLFW.glfwGetJoystickGUID(jid);
        return g == null ? "" : g;
    }

    private static boolean down(Minecraft mc, int key) {
        return InputConstants.isKeyDown(mc.getWindow().getWindow(), key);
    }

    private static double slew(double cur, double target, double rate, double dt) {
        double d = target - cur, m = rate * dt;
        return Math.abs(d) <= m ? target : cur + Math.signum(d) * m;
    }

    /** Sticks from the controller if present, else keyboard. */
    public Mapping.Sticks read(Minecraft mc, Config cfg, double dt) {
        poll(cfg);
        if (override != null) return override;
        if (connected()) {
            Mapping.Sticks r = cfg.mapping.apply(axes, buttons);
            // keyboard Z / X as a fallback for switches that are not mapped (or to arm without touching the radio)
            if (!r.armMapped) r.arm = kbArm; else if (kbArm) r.arm = true;
            if (!r.modeMapped) r.mode = kbMode;
            return r;
        }
        Mapping.Sticks s = new Mapping.Sticks();
        boolean active = cfg.useKeyboard && mc.screen == null && mc.isWindowActive();
        double tThr = 0, tYaw = 0, tPitch = 0, tRoll = 0;
        boolean thrUp = false, thrDn = false;
        if (active) {
            thrUp = down(mc, GLFW.GLFW_KEY_W); thrDn = down(mc, GLFW.GLFW_KEY_S);
            tYaw = (down(mc, GLFW.GLFW_KEY_D) ? 1 : 0) - (down(mc, GLFW.GLFW_KEY_A) ? 1 : 0);
            tPitch = (down(mc, GLFW.GLFW_KEY_UP) ? 1 : 0) - (down(mc, GLFW.GLFW_KEY_DOWN) ? 1 : 0);
            tRoll = (down(mc, GLFW.GLFW_KEY_RIGHT) ? 1 : 0) - (down(mc, GLFW.GLFW_KEY_LEFT) ? 1 : 0);
        }
        if (thrUp) kThr = Math.min(1, kThr + 1.2 * dt);
        if (thrDn) kThr = Math.max(-1, kThr - 1.8 * dt);
        kYaw = slew(kYaw, tYaw, 6, dt); kPitch = slew(kPitch, tPitch, 6, dt); kRoll = slew(kRoll, tRoll, 6, dt);
        s.throttle = kThr; s.yaw = kYaw; s.pitch = kPitch; s.roll = kRoll; s.arm = kbArm; s.mode = kbMode;
        return s;
    }

    public void resetKeyboard() { kThr = -1; kYaw = kPitch = kRoll = 0; kbArm = false; }
    public void kbThrottleSet(double v) { kThr = v; }
}
