package dev.fpvcore.mc;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import dev.fpvcore.mc.demo.DemoHooks;

/** On-screen display: throttle, speed, battery voltage, flight mode, arm state (+ altitude, mAh, controller). */
public final class Osd {
    private Osd() {}
    static final String[] MODES = {"?", "ACRO", "ANGLE", "ACRO3D", "HORIZON"};

    private static final int PANEL = 0x99000000;

    /** Text on a dark translucent plate so it stays readable over bright sky / snow. */
    private static void plate(GuiGraphics g, Minecraft mc, String t, int cx, int y, int color) {
        int tw = mc.font.width(t);
        g.fill(cx - tw / 2 - 4, y - 2, cx + tw / 2 + 4, y + 10, PANEL);
        g.drawString(mc.font, t, cx - tw / 2, y, color, true);
    }

    public static void render(GuiGraphics g, DeltaTracker dt) {
        Session S = Session.I;
        Minecraft mc = Minecraft.getInstance();
        if (S.drone == null || !S.flying || mc.options.hideGui || (DemoHooks.ACTIVE && DemoHooks.hideOsd)) return;
        FpvNative.State s = S.drone.state();
        int w = g.guiWidth(), h = g.guiHeight();
        int white = 0xFFFFFFFF, shade = 0xFFE8FF00;
        boolean armed = s.armed != 0;
        double kmh = S.fpvStateSpeed * 3.6;
        double thr = (S.sticks.throttle + 1) * 50.0;
        int m = Math.max(0, Math.min(4, s.mode));
        // bottom-left block
        int x = 12, y = h - 62;
        g.fill(x - 4, y - 4, x + 92, y + 46, PANEL);
        g.drawString(mc.font, String.format("THR %3.0f%%", thr), x, y, white, true);
        g.fill(x, y + 11, x + 80, y + 17, 0x80000000);
        g.fill(x, y + 11, x + (int) (0.8 * thr), y + 17, 0xFF30D0FF);
        g.drawString(mc.font, String.format("%.1f V", s.battery_v), x, y + 22, s.cell_v < 3.4 ? 0xFFFF5050 : white, true);
        g.drawString(mc.font, String.format("%.0f mAh", s.mah_used), x + 44, y + 22, white, true);
        g.drawString(mc.font, String.format("%.0f km/h", kmh), x, y + 33, white, true);
        g.drawString(mc.font, String.format("Y %.1f", s.pos[1]), x + 56, y + 33, white, true);
        // bottom-centre: arm + mode
        String arm = armed ? "ARMED" : (S.crashed ? "CRASHED" : "DISARMED");
        plate(g, mc, arm + "   " + MODES[m], w / 2, h - 42, armed ? 0xFF60FF60 : 0xFFFF6060);
        String hint = null;
        if (S.crashed) hint = "flip ARM off and on to re-arm (throttle low)   J = reset drone   G = exit FPV";
        else if (!armed) hint = (S.ctl.connected() ? "flip the ARM switch with throttle low" : "Z = arm (throttle low)   W/S throttle   A/D yaw   arrows pitch/roll   X mode") + "   G = exit FPV";
        if (DemoHooks.ACTIVE) hint = null;
        if (hint != null) plate(g, mc, hint, w / 2, h - 28, 0xFFE0E0E0);
        else if (!DemoHooks.ACTIVE) plate(g, mc, "G = exit FPV", w / 2, h - 28, 0xFFB0B0B0);
        if (S.waitingChunks) plate(g, mc, "WAITING FOR CHUNKS", w / 2, h / 3, 0xFFFFA030);
        String sync = S.syncStatus();
        boolean linkedOk = S.linked;
        plate(g, mc, sync, 12 + mc.font.width(sync) / 2 + 4, 8, linkedOk ? 0xFF60FF60 : 0xFFFFA030);
        if (!S.serverMod()) plate(g, mc, "no fpvcore on server: drone not visible to others", 12 + mc.font.width("no fpvcore on server: drone not visible to others") / 2 + 4, 24, 0xFFFFA030);
        // stick display (bottom-right): left = throttle/yaw, right = pitch/roll
        stick(g, w - 140, h - 66, S.sticks.yaw, S.sticks.throttle);
        stick(g, w - 74, h - 66, S.sticks.roll, -S.sticks.pitch);
        String dev;
        int dc = 0xFFFFFFFF;
        if (DemoHooks.ACTIVE) { dev = "Demo autopilot"; }
        else         if (S.ctl.connected()) { dev = S.ctl.name + (S.cfg.mapping.calibrated ? "" : " (not calibrated: press K)"); if (!S.cfg.mapping.calibrated) dc = 0xFFFFC040; }
        else { dev = "Keyboard (press K to set up a controller)"; dc = 0xFFFFC040; }
        if (DemoHooks.ACTIVE) return;   // demo videos: no device label
        int dw = mc.font.width(dev);
        g.fill(w - 12 - dw - 4, S.serverMod() ? 6 : 22, w - 8, (S.serverMod() ? 6 : 22) + 12, PANEL);
        g.drawString(mc.font, dev, w - 12 - dw, S.serverMod() ? 8 : 24, dc, true);
    }

    private static void stick(GuiGraphics g, int x, int y, double sx, double sy) {
        g.fill(x, y, x + 56, y + 56, 0x99000000);
        g.fill(x + 27, y, x + 29, y + 56, 0x30FFFFFF);
        g.fill(x, y + 27, x + 56, y + 29, 0x30FFFFFF);
        int px = x + 28 + (int) (sx * 26), py = y + 28 - (int) (sy * 26);
        g.fill(px - 3, py - 3, px + 3, py + 3, 0xFFFFFFFF);
    }
}
