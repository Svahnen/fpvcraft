package dev.fpvcore.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Controller screen (K): pick the controller, watch every raw axis / button live, run the calibration wizard (stick mode, extremes, centre,
 * then identify throttle / yaw / pitch / roll / arm / mode by moving them, each with Confirm / Retry) or edit the mapping directly
 * (axis selector + invert per function, AETR / TAER presets, two-stick test view). Save writes config/fpvcore.json.
 */
public class CalibrationScreen extends Screen {
    private static final int OVERVIEW = -1, MODESEL = 0, EXT = 1, CTR = 2, THR = 3, YAW = 4, PIT = 5, ROL = 6, ARM = 7, MODE = 8, REVIEW = 9;
    private static final String[] NAMES = {"Stick mode", "Extremes", "Centre", "Throttle", "Yaw", "Pitch", "Roll", "Arm switch", "Mode switch", "Review"};
    private static final String[] FN = {"THR", "YAW", "PIT", "ROL", "ARM", "MOD"};            // function index 0..5
    private static final String[] FN_LONG = {"Throttle", "Yaw", "Pitch", "Roll", "Arm", "Mode"};
    /** Gimbal cell of throttle/yaw/pitch/roll per stick mode 1-4: side*2 + (vertical ? 1 : 0); side 0 = left, 1 = right. Cells: 0 L-horiz, 1 L-vert, 2 R-horiz, 3 R-vert. */
    private static final int[][] LAYOUT = {{3, 0, 1, 2}, {1, 0, 3, 2}, {3, 2, 1, 0}, {1, 2, 3, 0}};
    private static final float MIN_RANGE = 0.5f;

    private static final int TXT = 0xFFFFFFFF, DIM = 0xFFC8C8D0, YEL = 0xFFFFE060, RED = 0xFFFF7070, GRN = 0xFF80FF80;

    private final Controller ctl = Session.I.ctl;
    private final Config cfg = Session.I.cfg;
    private int step = OVERVIEW;
    private boolean editOnly;
    private Mapping work;
    private String wizGuid = "", recGuid = "";
    private float[] rmn = new float[0], rmx = new float[0], ctr = new float[0];   // recorded extremes (all axes, reset at the extremes step) and centre snapshot
    private float[] base = new float[0], wmn = new float[0], wmx = new float[0];  // per-step window: start value and min / max since the step began
    private final ArrayDeque<float[]> hist = new ArrayDeque<>();                  // axis samples of the current step window
    private boolean ctrValid;
    private boolean[] btnSeen = new boolean[0];
    private final Button[] axBtn = new Button[6], invBtn = new Button[6];
    private String status = "";
    private int devOffset;
    private int panelX, panelW, panelTop, panelBottom;
    private final List<int[]> rowRects = new ArrayList<>(); // x0,y0,x1,y1,deviceIndex(-1 keyboard)

    public CalibrationScreen() { super(Component.literal("FPV controller")); }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        panelW = Math.min(width - 12, 460);
        panelX = (width - panelW) / 2;
        panelTop = 4;
        panelBottom = height - 4;
        rebuild();
    }

    // ---- mapping helpers ----------------------------------------------------------------------------------------------------------------------

    private static Mapping.Axis axisObj(Mapping m, int fn) { return switch (fn) { case 0 -> m.throttle; case 1 -> m.yaw; case 2 -> m.pitch; default -> m.roll; }; }

    private static int axisOf(Mapping m, int fn) {
        return fn < 4 ? axisObj(m, fn).axis : fn == 4 ? m.armAxis : m.modeAxis;
    }

    private void putAxis(int fn, int a) {
        if (fn < 4) axisObj(work, fn).axis = a;
        else if (fn == 4) { work.armAxis = a; work.armButton = -1; }
        else work.modeAxis = a;
    }

    private boolean invOf(int fn) { return fn < 4 ? axisObj(work, fn).invert : fn == 4 ? work.armInvert : work.modeInvert; }

    private void flipInv(int fn) {
        if (fn < 4) axisObj(work, fn).invert = !axisObj(work, fn).invert;
        else if (fn == 4) work.armInvert = !work.armInvert;
        else work.modeInvert = !work.modeInvert;
    }

    private boolean usedByOther(int axis, int fn) {
        for (int k = 0; k < 6; k++) if (k != fn && axisOf(work, k) == axis) return true;
        return false;
    }

    /** Fill min / centre / max of a stick function from what was recorded for its axis (fallback -1..1; throttle has no centre: min/max only). */
    private void calib(int fn) {
        if (fn >= 4) return;
        Mapping.Axis c = axisObj(work, fn);
        int a = c.axis;
        if (a < 0) return;
        float lo = -1, hi = 1;
        if (a < rmn.length && rmx[a] - rmn[a] >= MIN_RANGE) { lo = rmn[a]; hi = rmx[a]; }
        c.min = lo; c.max = hi;
        if (fn == 0) { c.center = 0.5f * (lo + hi); c.deadzone = 0; }
        else c.center = ctrValid && a < ctr.length && ctr[a] > lo && ctr[a] < hi ? ctr[a] : (lo < 0 && hi > 0 ? 0 : 0.5f * (lo + hi));
    }

    /** Manual assignment; an axis that another function already uses is swapped over to it. */
    private void assign(int fn, int a) {
        int old = axisOf(work, fn);
        if (a >= 0) for (int k = 0; k < 6; k++) if (k != fn && axisOf(work, k) == a) { putAxis(k, old); calib(k); }
        putAxis(fn, a);
        calib(fn);
    }

    private void cycle(int fn, boolean back) {
        int n = ctl.axes.length, cur = axisOf(work, fn), nx = back ? cur - 1 : cur + 1;
        if (nx >= n) nx = -1;
        if (nx < -1) nx = n - 1;
        assign(fn, nx);
        refreshRows();
    }

    private String axLabel(int fn) {
        int a = axisOf(work, fn);
        if (a >= 0) return "axis " + a;
        if (fn == 4 && work.armButton >= 0) return "button " + work.armButton;
        return "none";
    }

    private void refreshRows() {
        for (int fn = 0; fn < 6; fn++) {
            if (axBtn[fn] != null) axBtn[fn].setMessage(Component.literal(axLabel(fn)));
            if (invBtn[fn] != null) invBtn[fn].setMessage(Component.literal("Invert: " + (invOf(fn) ? "ON" : "off")));
        }
    }

    private int stickMode() { return work == null ? Math.max(1, Math.min(4, cfg.mapping.stickMode)) : Math.max(1, Math.min(4, work.stickMode)); }
    private int cellOf(int fn) { return LAYOUT[stickMode() - 1][fn]; }
    private String sideOf(int fn) { return (cellOf(fn) >> 1) == 0 ? "LEFT" : "RIGHT"; }

    void applyPreset(String name) {
        if (work == null) return;
        work.applyPreset(name);
        for (int fn = 0; fn < 4; fn++) calib(fn);
        if (work.armAxis < 0 && work.armButton < 0 && ctl.axes.length > 4 && !usedByOther(4, 4)) work.armAxis = 4;
        if (work.modeAxis < 0 && ctl.axes.length > 5 && !usedByOther(5, 5)) work.modeAxis = 5;
        refreshRows();
        status = "";
    }

    // ---- widgets ------------------------------------------------------------------------------------------------------------------------------

    private int lines(String s) { return font.split(Component.literal(s), panelW - 20).size(); }
    /** y below the title, device line and prompt: where the step's own widgets start. */
    private int contentTop() { return panelTop + 6 + 13 + (step == OVERVIEW ? 0 : 13 + lines(prompt()) * 10 + 1); }

    private void bar(String[] label, Runnable[] act) {
        int n = label.length, w = Math.min(n == 3 ? 140 : 110, (panelW - 16 - (n - 1) * 6) / n), tot = n * w + (n - 1) * 6, x = width / 2 - tot / 2, by = panelBottom - 26;
        for (int i = 0; i < n; i++) {
            final Runnable r = act[i];
            addRenderableWidget(Button.builder(Component.literal(label[i]), b -> r.run()).bounds(x + i * (w + 6), by, w, 20).build());
        }
    }

    private void rebuild() {
        clearWidgets();
        java.util.Arrays.fill(axBtn, null); java.util.Arrays.fill(invBtn, null);
        if (step == OVERVIEW) {
            bar(new String[]{"Start wizard", "Edit mapping / test", "Close"}, new Runnable[]{this::startWizard, this::startEdit, this::onClose});
            return;
        }
        if (step >= THR && step <= MODE)
            bar(new String[]{"< Back", step == MODE ? "Confirm / skip" : "Confirm", "Retry", "Cancel"}, new Runnable[]{this::back, this::next, this::retry, this::onClose});
        else
            bar(new String[]{"< Back", step == REVIEW ? "Save" : "Next >", "Cancel"}, new Runnable[]{this::back, this::next, this::onClose});
        int top = contentTop();
        if (step == MODESEL) {
            int w = 76, x = width / 2 - (4 * w + 3 * 6) / 2;
            for (int m = 1; m <= 4; m++) {
                final int mm = m;
                addRenderableWidget(Button.builder(Component.literal((work != null && work.stickMode == m ? "> " : "") + "Mode " + m), b -> { setStickMode(mm); }).bounds(x + (m - 1) * (w + 6), top + 2, w, 16).build());
            }
        } else if (step == REVIEW) {
            int lx = panelX + 8, rx = panelX + 240;
            for (int fn = 0; fn < 6; fn++) {
                final int f = fn;
                int y = top + fn * 16;
                axBtn[fn] = Button.builder(Component.literal(axLabel(fn)), b -> cycle(f, hasShiftDown())).bounds(lx + 46, y, 76, 14).build();
                invBtn[fn] = Button.builder(Component.literal("Invert"), b -> { flipInv(f); refreshRows(); }).bounds(lx + 126, y, 64, 14).build();
                addRenderableWidget(axBtn[fn]); addRenderableWidget(invBtn[fn]);
            }
            int py = top + 6 * 16 + 2;
            addRenderableWidget(Button.builder(Component.literal("AETR (EdgeTX default)"), b -> applyPreset("AETR")).bounds(lx, py, 134, 14).build());
            addRenderableWidget(Button.builder(Component.literal("TAER"), b -> applyPreset("TAER")).bounds(lx + 138, py, 52, 14).build());
            addRenderableWidget(Button.builder(Component.literal("Stick mode " + stickMode() + " (change)"), b -> setStickMode(stickMode() % 4 + 1)).bounds(rx, top, 150, 14).build());
            refreshRows();
        }
    }

    void setStickMode(int m) {
        if (work == null) return;
        work.stickMode = Math.max(1, Math.min(4, m));
        rebuild();
    }

    void toggle(int k) {
        if (k < 5) flipInv(k); else work.gamepadMode = !work.gamepadMode;
        refreshRows();
    }

    // ---- wizard flow --------------------------------------------------------------------------------------------------------------------------

    void startWizard() {
        ctl.poll(cfg);
        if (!ctl.connected()) { status = "Select a controller first (the list above)."; return; }
        if (ctl.axes.length == 0) { status = "This device reports no axes; pick another one."; return; }
        work = new Mapping();
        work.deviceGuid = ctl.guid(); work.deviceName = ctl.name; wizGuid = work.deviceGuid;
        work.stickMode = Math.max(1, Math.min(4, cfg.mapping.stickMode));
        for (Mapping.Axis a : new Mapping.Axis[]{work.throttle, work.yaw, work.pitch, work.roll}) a.axis = -1;
        work.armAxis = -1; work.armButton = -1; work.modeAxis = -1;
        work.yaw.deadzone = work.pitch.deadzone = work.roll.deadzone = 0.02f;
        work.throttle.deadzone = 0;
        editOnly = false; ctrValid = false; ctr = new float[0];
        begin(MODESEL);
    }

    /** Edit the current mapping directly (axis selectors, presets, test view) without running the wizard. */
    /** Demo videos: open the mapping editor / two-stick test view. */
    public void demoEdit() { startEdit(); }

    void startEdit() {
        ctl.poll(cfg);
        if (!ctl.connected()) { status = "Select a controller first (the list above)."; return; }
        if (ctl.axes.length == 0) { status = "This device reports no axes; pick another one."; return; }
        work = cfg.mapping.copy();
        work.deviceGuid = ctl.guid(); work.deviceName = ctl.name; wizGuid = work.deviceGuid;
        editOnly = true; ctrValid = false;
        begin(REVIEW);
    }

    private void begin(int s) {
        step = s; status = "";
        ctl.poll(cfg);
        base = ctl.axes.clone(); wmn = base.clone(); wmx = base.clone(); hist.clear();
        btnSeen = new boolean[ctl.buttons.length];
        if (s == EXT) { rmn = base.clone(); rmx = base.clone(); }
        if (s == REVIEW && !editOnly) { for (int fn = 0; fn < 4; fn++) calib(fn); work.calibrated = true; }
        rebuild();
    }

    void retry() {
        if (step >= THR && step <= MODE) begin(step);
    }

    void back() {
        if (step == MODESEL || (step == REVIEW && editOnly)) { step = OVERVIEW; status = ""; rebuild(); return; }
        if (step - 1 >= THR && step - 1 <= MODE) putAxis(step - 1 - THR, -1);
        begin(step - 1);
    }

    /** Unassigned axis whose range (max - min) over this step window is largest, if it exceeds MIN_RANGE; -1 otherwise. */
    private int pick(int fn) {
        int best = -1; float br = MIN_RANGE;
        for (int i = 0; i < wmn.length; i++) {
            if (usedByOther(i, fn)) continue;
            float r = wmx[i] - wmn[i];
            if (r > br) { br = r; best = i; }
        }
        return best;
    }

    /** A second unassigned axis that also moved a lot (cross-talk or the wrong stick), or -1. */
    private int second(int fn, int best) {
        int o = -1; float br = MIN_RANGE;
        for (int i = 0; i < wmn.length; i++) {
            if (i == best || usedByOther(i, fn)) continue;
            float r = wmx[i] - wmn[i];
            if (r > br) { br = r; o = i; }
        }
        return o;
    }

    /** True if positive (up / right / forward / armed) is the LOW end of this axis. */
    private boolean negative(int fn, int a) {
        float lo = wmn[a], hi = wmx[a], r = hi - lo, mid = 0.5f * (lo + hi);
        if (fn == 0) { // throttle rests at an end (step "Centre" asked for it down)
            if (Math.abs(base[a] - lo) < 0.15f * r) return false;
            if (Math.abs(base[a] - hi) < 0.15f * r) return true;
        }
        if (fn >= 4) { // switch: whichever way it went away from rest
            float far = 0;
            for (float[] h : hist) { float d = h[a] - base[a]; if (Math.abs(d) > Math.abs(far)) far = d; }
            return far < 0;
        }
        java.util.Iterator<float[]> it = hist.descendingIterator(); // stick: the end it was last pushed to
        while (it.hasNext()) { float v = it.next()[a]; if (Math.abs(v - mid) > 0.3f * r) return v < mid; }
        return false;
    }

    private void applyDetected(int fn, int a) {
        boolean neg = negative(fn, a);
        putAxis(fn, a);
        if (fn < 4) { axisObj(work, fn).invert = neg; calib(fn); }
        else if (fn == 4) { work.armThreshold = 0.5f; work.armInvert = neg; }
        else work.modeInvert = neg;
    }

    void next() {
        track();
        if (step == OVERVIEW) return;
        switch (step) {
            case MODESEL -> begin(EXT);
            case EXT -> {
                boolean moved = false;
                for (int i = 0; i < rmn.length; i++) if (rmx[i] - rmn[i] > 0.3f) moved = true;
                if (!moved) { status = "No axis moved. Move every stick to all four corners, flip all switches, then Next."; return; }
                begin(CTR);
            }
            case CTR -> { ctr = ctl.axes.clone(); ctrValid = true; begin(THR); }
            case THR, YAW, PIT, ROL -> {
                int fn = step - THR, a = pick(fn);
                if (a < 0) { status = "No unused axis moved more than " + MIN_RANGE + ". Move the " + sideOf(fn) + " stick through its full travel, or press Retry."; return; }
                applyDetected(fn, a);
                begin(step + 1);
            }
            case ARM -> {
                int a = pick(4);
                if (a >= 0) applyDetected(4, a);
                else {
                    int b = -1;
                    for (int i = 0; i < btnSeen.length; i++) if (btnSeen[i]) { b = i; break; }
                    if (b < 0) { status = "Nothing moved. Flip the ARM switch to ARMED and back (or press the arm button), then Confirm."; return; }
                    work.armAxis = -1; work.armButton = b;
                }
                begin(MODE);
            }
            case MODE -> {
                int a = pick(5);
                if (a >= 0) applyDetected(5, a); else work.modeAxis = -1; // skipped: keyboard X
                begin(REVIEW);
            }
            default -> save();
        }
    }

    private void save() {
        work.deviceGuid = ctl.guid(); work.deviceName = ctl.name; work.calibrated = true;
        cfg.mappings.put(work.deviceGuid, work);
        cfg.mapping = work;
        cfg.controllerGuid = work.deviceGuid; cfg.controllerName = work.deviceName; cfg.forceKeyboard = false; cfg.joystick = ctl.jid;
        cfg.save();
        Session.I.msg(Minecraft.getInstance(), "controller mapping saved for " + work.deviceName);
        onClose();
    }

    private void abort(String why) { step = OVERVIEW; status = why; rebuild(); }

    private void track() {
        ctl.poll(cfg);
        if (!ctl.connected()) { if (step != OVERVIEW) abort("The controller disconnected: wizard aborted (nothing saved)."); return; }
        int n = ctl.axes.length;
        String g = ctl.guid();
        if (!g.equals(recGuid) || rmn.length != n) { rmn = ctl.axes.clone(); rmx = ctl.axes.clone(); recGuid = g; }
        if (step != OVERVIEW && (!g.equals(wizGuid) || n != base.length)) { abort("The controller changed: wizard aborted (nothing saved)."); return; }
        for (int i = 0; i < n; i++) {
            float v = ctl.axes[i];
            if (v < rmn[i]) rmn[i] = v;
            if (v > rmx[i]) rmx[i] = v;
        }
        if (step >= THR && step <= MODE) {
            for (int i = 0; i < n; i++) { float v = ctl.axes[i]; if (v < wmn[i]) wmn[i] = v; if (v > wmx[i]) wmx[i] = v; }
            hist.addLast(ctl.axes.clone());
            if (hist.size() > 6000) hist.removeFirst();
        }
        for (int i = 0; i < ctl.buttons.length && i < btnSeen.length; i++) if (ctl.buttons[i]) btnSeen[i] = true;
    }

    // ---- scripted access (dev autotest) -------------------------------------------------------------------------------------------------------

    void assignAxis(int fn, int a) { if (work != null) { assign(fn, a); refreshRows(); } }
    String summary() { return work == null ? "no work" : work.summary(); }

    // ---- input --------------------------------------------------------------------------------------------------------------------------------

    private void selectIndex(int idx) { // idx -1 = keyboard
        List<Controller.Dev> devs = ctl.devices();
        if (idx < 0) ctl.select(cfg, null);
        else if (idx < devs.size()) ctl.select(cfg, devs.get(idx));
        status = "";
    }

    private int selectedIndex() {
        List<Controller.Dev> devs = ctl.devices();
        for (int i = 0; i < devs.size(); i++) if (devs.get(i).jid == ctl.jid) return i;
        return -1;
    }

    @Override public boolean keyPressed(int key, int sc, int mod) {
        if (step == OVERVIEW) {
            if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
                int n = ctl.devices().size(), cur = selectedIndex();
                int nx = key == GLFW.GLFW_KEY_UP ? Math.max(-1, cur - 1) : Math.min(n - 1, cur + 1);
                selectIndex(nx);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { startWizard(); return true; }
        } else {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { next(); return true; }
            if (key == GLFW.GLFW_KEY_BACKSPACE) { back(); return true; }
            if (key == GLFW.GLFW_KEY_R && step >= THR && step <= MODE) { retry(); return true; }
            if (step == REVIEW) {
                switch (key) {
                    case GLFW.GLFW_KEY_1 -> toggle(0);
                    case GLFW.GLFW_KEY_2 -> toggle(1);
                    case GLFW.GLFW_KEY_3 -> toggle(2);
                    case GLFW.GLFW_KEY_4 -> toggle(3);
                    case GLFW.GLFW_KEY_5 -> toggle(4);
                    case GLFW.GLFW_KEY_G -> toggle(5);
                    case GLFW.GLFW_KEY_MINUS -> setDz(-0.01f);
                    case GLFW.GLFW_KEY_EQUAL -> setDz(0.01f);
                    default -> { return super.keyPressed(key, sc, mod); }
                }
                return true;
            }
        }
        return super.keyPressed(key, sc, mod);
    }

    private void setDz(float d) {
        for (Mapping.Axis a : new Mapping.Axis[]{work.yaw, work.pitch, work.roll}) a.deadzone = Math.max(0, Math.min(0.5f, a.deadzone + d));
    }

    @Override public boolean mouseClicked(double mx_, double my_, int b) {
        if (step == OVERVIEW && b == 0) {
            for (int[] r : rowRects) if (mx_ >= r[0] && mx_ < r[2] && my_ >= r[1] && my_ < r[3]) { selectIndex(r[4]); return true; }
        }
        return super.mouseClicked(mx_, my_, b);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (step == OVERVIEW) { devOffset = Math.max(0, devOffset - (int) Math.signum(dy)); return true; }
        return super.mouseScrolled(x, y, dx, dy);
    }

    // ---- rendering ----------------------------------------------------------------------------------------------------------------------------

    /** No vanilla blur (Screen.render calls this itself, so blur here would also cover everything we draw): a flat dim + a solid panel. */
    @Override public void renderBackground(GuiGraphics g, int mx_, int my_, float pt) {
        g.fill(0, 0, width, height, 0x80000000);
        g.fill(panelX, panelTop, panelX + panelW, panelBottom, 0xF0101018);
        g.renderOutline(panelX, panelTop, panelW, panelBottom - panelTop, 0xFF5060A0);
    }

    private void text(GuiGraphics g, String s, int x, int y, int c) { g.drawString(font, s, x, y, c, true); }
    private void ctext(GuiGraphics g, String s, int y, int c) { g.drawCenteredString(font, s, width / 2, y, c); }

    private int wrap(GuiGraphics g, String s, int y, int c) {
        for (FormattedCharSequence l : font.split(Component.literal(s), panelW - 20)) { g.drawString(font, l, panelX + 10, y, c, true); y += 10; }
        return y;
    }

    private String fnLabel(int axis) {
        Mapping m = step == OVERVIEW || work == null ? cfg.mapping : work;
        StringBuilder sb = new StringBuilder();
        if (m.throttle.axis == axis) sb.append("THR ");
        if (m.yaw.axis == axis) sb.append("YAW ");
        if (m.pitch.axis == axis) sb.append("PIT ");
        if (m.roll.axis == axis) sb.append("ROL ");
        if (m.armAxis == axis) sb.append("ARM ");
        if (m.modeAxis == axis) sb.append("MOD ");
        return sb.toString().trim();
    }

    private String cellName(int mode, int cell) {
        for (int fn = 0; fn < 4; fn++) if (LAYOUT[mode - 1][fn] == cell) return FN[fn];
        return "?";
    }

    /** Two gimbal boxes (left, right) with a dot each; hv = {leftH, leftV, rightH, rightV} in [-1,1]. Captions are vertical/horizontal function. */
    private void drawSticks(GuiGraphics g, int x, int y, int box, int gap, double[] hv, int mode) {
        for (int side = 0; side < 2; side++) {
            int bx = x + side * (box + gap), cx = bx + box / 2, cy = y + box / 2;
            g.fill(bx, y, bx + box, y + box, 0xFF000000);
            g.fill(bx + 1, y + 1, bx + box - 1, y + box - 1, 0xFF262636);
            g.fill(bx + 1, cy, bx + box - 1, cy + 1, 0xFF50506A);
            g.fill(cx, y + 1, cx + 1, y + box - 1, 0xFF50506A);
            double h = Math.max(-1, Math.min(1, hv[side * 2])), v = Math.max(-1, Math.min(1, hv[side * 2 + 1]));
            int r = box / 2 - 4, dx = cx + (int) Math.round(h * r), dy = cy - (int) Math.round(v * r);
            g.fill(dx - 2, dy - 2, dx + 3, dy + 3, YEL);
            text(g, (side == 0 ? "L " : "R ") + cellName(mode, side * 2 + 1) + "/" + cellName(mode, side * 2), bx, y + box + 2, DIM);
        }
    }

    @Override public void render(GuiGraphics g, int mx_, int my_, float pt) {
        track();
        super.render(g, mx_, my_, pt); // background (panel) + buttons
        int y = panelTop + 6;
        ctext(g, step == OVERVIEW ? "FPV controller" : editOnly ? "Edit mapping / test" : "Calibration wizard: step " + (step + 1) + "/" + NAMES.length + " - " + NAMES[step], y, step == OVERVIEW ? TXT : YEL);
        y += 13;
        rowRects.clear();
        int cand = -1, barTop;
        int gridY = panelBottom - 26 - 14;
        if (step == OVERVIEW) { y = renderList(g, y); barTop = y + 2; }
        else {
            ctext(g, ctl.connected() ? ctl.name + "  (" + ctl.axes.length + " axes, " + ctl.buttons.length + " buttons)" : "NO CONTROLLER", y, ctl.connected() ? GRN : RED);
            y += 13;
            y = wrap(g, prompt(), y, TXT) + 1;
            barTop = y + 2;
            if (step == MODESEL) {
                int m = stickMode();
                int ty = contentTop() + 22;
                text(g, "Mode " + m + ": " + modeDesc(m), panelX + 10, ty, GRN);
                double[] z = new double[4];
                drawSticks(g, width / 2 - 54, ty + 12, 48, 12, z, m);
                barTop = ty + 12 + 48 + 14;
            } else if (step >= THR && step <= MODE) {
                int fn = step - THR;
                cand = pick(fn);
                if (cand >= 0) {
                    String dir = fn < 4 ? (negative(fn, cand) ? "  direction: inverted" : "  direction: normal") : "";
                    text(g, String.format("Detected: axis %d  (range %.2f)%s", cand, wmx[cand] - wmn[cand], dir), panelX + 10, y, GRN);
                    int o = second(fn, cand);
                    if (o >= 0) text(g, "also moved: axis " + o, panelX + 270, y, YEL);
                } else {
                    int b = -1;
                    for (int i = 0; i < btnSeen.length; i++) if (btnSeen[i]) { b = i; break; }
                    text(g, fn == 4 && b >= 0 ? "Detected: button " + b : "Detected: nothing yet (needs a range above " + MIN_RANGE + ")", panelX + 10, y, fn == 4 && b >= 0 ? GRN : DIM);
                }
                y += 11;
                barTop = y + 2;
            }
        }
        if (!status.isEmpty() && step != REVIEW) { y = wrap(g, status, y + (step >= THR && step <= MODE ? 0 : 0), RED); barTop = Math.max(barTop, y + 2); }
        if (step == REVIEW) barTop = renderReview(g);
        renderBars(g, barTop, gridY - 3, cand);
        renderButtonsGrid(g, gridY);
    }

    private static String modeDesc(int m) {
        return switch (m) {
            case 1 -> "LEFT stick: pitch (up/down) + yaw (left/right). RIGHT stick: throttle + roll.";
            case 2 -> "LEFT stick: throttle (up/down) + yaw (left/right). RIGHT stick: pitch + roll.";
            case 3 -> "LEFT stick: pitch + roll. RIGHT stick: throttle + yaw.";
            default -> "LEFT stick: throttle + roll. RIGHT stick: pitch + yaw.";
        };
    }

    /** Function rows (labels), two-stick test view of the MAPPED result and numbers. Returns the y where the axis bars may start. */
    private int renderReview(GuiGraphics g) {
        int top = contentTop(), lx = panelX + 8, rx = panelX + 240, m = stickMode();
        for (int fn = 0; fn < 6; fn++) {
            boolean set = axisOf(work, fn) >= 0 || (fn == 4 && work.armButton >= 0);
            text(g, FN_LONG[fn], lx, top + fn * 16 + 3, set ? GRN : (fn < 4 ? RED : DIM));
        }
        Mapping.Sticks s = work.apply(ctl.axes, ctl.buttons);
        double[] hv = new double[4];
        double[] val = {s.throttle, s.yaw, s.pitch, s.roll};
        for (int fn = 0; fn < 4; fn++) hv[LAYOUT[m - 1][fn]] = val[fn];
        drawSticks(g, rx, top + 18, 62, 14, hv, m);
        int ty = top + 18 + 62 + 14;
        text(g, String.format("thr %+.2f  yaw %+.2f", s.throttle, s.yaw), rx, ty, TXT);
        text(g, s.arm ? "ARMED" : "disarmed", rx + 122, ty, s.arm ? YEL : DIM);
        text(g, String.format("pit %+.2f  rol %+.2f", s.pitch, s.roll), rx, ty + 10, TXT);
        text(g, Osd.MODES[s.mode], rx + 122, ty + 10, DIM);
        return top + 6 * 16 + 2 + 14 + 4;
    }

    private int renderList(GuiGraphics g, int y) {
        List<Controller.Dev> devs = ctl.devices();
        int sel = selectedIndex();
        if (devs.isEmpty()) {
            ctext(g, "No controller detected", y, RED); y += 10;
            y = wrap(g, "Connect the transmitter in USB Joystick/HID mode (EdgeTX: Model/System > USB Mode > Joystick), then it appears here automatically. Keyboard flight works meanwhile: W/S throttle, A/D yaw, arrows pitch/roll, Z arm, X mode.", y, DIM);
            return y + 4;
        }
        text(g, "Controllers (click or Up/Down to choose, Enter = wizard):", panelX + 10, y, TXT); y += 11;
        int rows = Math.min(devs.size() + 1, 5);
        devOffset = Math.max(0, Math.min(devOffset, devs.size() + 1 - rows));
        for (int r = 0; r < rows; r++) {
            int idx = devOffset + r - 1; // -1 = keyboard
            boolean isSel = idx == sel && !(idx < 0 && ctl.connected());
            if (idx < 0 && !ctl.connected()) isSel = true;
            int x0 = panelX + 8, x1 = panelX + panelW - 8;
            g.fill(x0, y - 1, x1, y + 10, isSel ? 0xFF2A4A80 : 0x40FFFFFF);
            String label = idx < 0 ? "Keyboard only (Z arm, X mode)" : (idx + 1) + ": " + devs.get(idx).label();
            String shown = font.plainSubstrByWidth(label, x1 - x0 - 8);
            text(g, shown, x0 + 4, y, isSel ? TXT : (idx >= 0 && devs.get(idx).junk ? 0xFF909098 : DIM));
            rowRects.add(new int[]{x0, y - 1, x1, y + 10, idx});
            y += 12;
        }
        if (devs.size() + 1 > rows) { text(g, "(scroll for more)", panelX + 10, y, DIM); y += 10; }
        boolean real = false;
        for (Controller.Dev d : devs) if (!d.junk) real = true;
        boolean steam = false;
        for (Controller.Dev d : devs) if (d.steam) steam = true;
        if (steam || !real) y = wrap(g, "Steam running? Steam Input can swallow the radio (it turns the sticks into mouse/keyboard on the desktop and shows a 'Steam Virtual Gamepad'). Disable it: Steam > Settings > Controller > uncheck 'Enable Steam Input for ...' / game Properties > Controller > Disable Steam Input.", y, YEL);
        if (!real) { y = wrap(g, "No RC controller detected: connect the transmitter in USB Joystick/HID mode, it appears here by itself.", y, RED); }
        if (ctl.connected()) {
            Mapping mp = cfg.mapping;
            text(g, mp.calibrated ? "Mapping: calibrated (Mode " + mp.stickMode + ")" : "Mapping: NOT calibrated (default AETR guess): run the wizard", panelX + 10, y, mp.calibrated ? GRN : YEL);
            y += 11;
            text(g, "THR a" + mp.throttle.axis + "  YAW a" + mp.yaw.axis + "  PIT a" + mp.pitch.axis + "  ROL a" + mp.roll.axis + "  ARM a" + mp.armAxis + "  MOD a" + mp.modeAxis, panelX + 10, y, DIM);
            y += 11;
        } else if (!cfg.controllerGuid.isEmpty() && !cfg.forceKeyboard) {
            text(g, "Selected controller not connected: " + cfg.controllerName, panelX + 10, y, YEL); y += 11;
        }
        return y + 2;
    }

    private String prompt() {
        return switch (step) {
            case MODESEL -> "Which transmitter stick mode do you fly? Mode 2 (default): left stick = throttle + yaw, right stick = pitch + roll. Then Next.";
            case EXT -> "Move BOTH sticks to every corner and around the edge, and flip every switch / turn every knob to all positions. The bars show the recorded range. Then Next.";
            case CTR -> "Release the sticks so they self-centre and put the THROTTLE stick all the way DOWN. Leave the switches. Then Next.";
            case THR -> "Move the " + sideOf(0) + " stick fully UP and DOWN a few times (Throttle), ending fully UP. Check the detected axis, then Confirm (or Retry).";
            case YAW -> "Move the " + sideOf(1) + " stick fully LEFT and RIGHT a few times (Yaw), ending fully RIGHT. Check the detected axis, then Confirm (or Retry).";
            case PIT -> "Move the " + sideOf(2) + " stick fully UP (forward) and DOWN (back) a few times (Pitch), ending fully UP. Check the detected axis, then Confirm (or Retry).";
            case ROL -> "Move the " + sideOf(3) + " stick fully LEFT and RIGHT a few times (Roll), ending fully RIGHT. Check the detected axis, then Confirm (or Retry).";
            case ARM -> "Flip the ARM switch from DISARMED to ARMED and leave it there (or press the arm button), then Confirm.";
            case MODE -> "Optional: put the MODE switch in its first position (acro), flip it through the others (angle, acro3d) to the last one and hold, then Confirm. Confirm without moving it to skip (keyboard X then selects the mode).";
            default -> "Check the dots and bars; fix with the selectors (Shift = back). Save.";
        };
    }

    private void renderBars(GuiGraphics g, int y0, int yEnd, int cand) {
        int n = ctl.axes.length;
        if (n == 0) {
            if (ctl.connected()) text(g, "This device reports no axes.", panelX + 10, y0, YEL);
            return;
        }
        int rowsAvail = Math.max(1, (yEnd - y0) / 10);
        int cols = n <= rowsAvail ? 1 : 2;
        int shown = Math.min(n, rowsAvail * cols);
        int colW = (panelW - 16) / cols;
        int labelW = 58, valW = 34;
        int barW = colW - labelW - valW - 6;
        for (int i = 0; i < shown; i++) {
            int col = i / rowsAvail, row = i % rowsAvail;
            int x = panelX + 8 + col * colW, y = y0 + row * 10;
            float v = ctl.axes[i];
            String fl = fnLabel(i);
            boolean isC = i == cand;
            text(g, "A" + i + (fl.isEmpty() ? "" : " " + fl), x, y, isC ? YEL : (fl.isEmpty() ? DIM : GRN));
            int bx = x + labelW, mid = bx + barW / 2;
            g.fill(bx, y, bx + barW, y + 8, 0xFF000000);
            g.fill(bx + 1, y + 1, bx + barW - 1, y + 7, 0xFF303040);
            int px = mid + (int) (Math.max(-1, Math.min(1, v)) * (barW / 2 - 1));
            g.fill(Math.min(mid, px), y + 1, Math.max(mid, px) + 1, y + 7, isC ? YEL : (fl.isEmpty() ? 0xFF60C0FF : 0xFF60E070));
            g.fill(mid, y, mid + 1, y + 8, 0x90FFFFFF);
            if (step == EXT && i < rmn.length) {
                int a = mid + (int) (Math.max(-1, Math.min(1, rmn[i])) * (barW / 2 - 1)), b = mid + (int) (Math.max(-1, Math.min(1, rmx[i])) * (barW / 2 - 1));
                g.fill(a, y, a + 1, y + 8, 0xFFFFFFFF); g.fill(b, y, b + 1, y + 8, 0xFFFFFFFF);
            }
            text(g, String.format("%+.2f", v), bx + barW + 4, y, TXT);
        }
        if (shown < n) text(g, "+" + (n - shown) + " more axes", panelX + 10, yEnd - 8, DIM);
    }

    private void renderButtonsGrid(GuiGraphics g, int y) {
        int n = ctl.buttons.length;
        if (!ctl.connected()) return;
        text(g, n == 0 ? "No buttons" : "Buttons:", panelX + 10, y, DIM);
        int x = panelX + 10 + font.width("Buttons:") + 6;
        for (int i = 0; i < n && x + 8 < panelX + panelW - 6; i++) {
            boolean on = ctl.buttons[i];
            boolean seen = i < btnSeen.length && btnSeen[i] && step >= ARM;
            g.fill(x, y, x + 7, y + 8, on ? YEL : (seen ? 0xFF807040 : 0xFF404050));
            x += 9;
        }
    }
}
