package dev.fpvcore.mc.demo;

import java.util.ArrayList;
import java.util.List;

/**
 * The scripted race course flown in the demo video, in course-local coordinates (x = along the course, h = height above the ground, z = sideways).
 * Tags on waypoints say what gets built there (see {@link CourseBuilder}): "ring:<radius>" = glowing gate ring around the path, "tunnel", "arch" (waterfall arch),
 * "tower", "finish", "disarm".
 */
public final class CoursePath {
    private CoursePath() {}

    /** Course origin in the world: local (0,0,0) = the pilot's launch spot at ground level. */
    public static final double OX = 0.5, OY = -60.0, OZ = 0.5;

    private static final List<Path.Wp> W = new ArrayList<>();
    private static void w(double x, double h, double z, double v, String tag) { W.add(new Path.Wp(x, h, z, v, tag)); }
    private static void w(double x, double h, double z, double v) { w(x, h, z, v, null); }

    public static Path build() {
        W.clear();
        // takeoff and meadow slalom
        w(1.5, 0.12, 0, 1.5);
        w(4.5, 0.9, 0, 4);
        w(9, 2.4, 0, 8);
        w(22, 3.3, 0, 13, "ring:5.0");
        w(38, 3.6, 5.5, 15);
        w(52, 4.2, 10, 16, "ring:5.0");
        w(68, 4.0, 4, 16);
        w(82, 3.4, -6, 17, "ring:5.0");
        w(90, 3.4, -3, 17);
        // tunnel through the hill
        w(100, 3.5, 0, 17, "tunnel");
        w(127, 3.5, 0, 17);
        w(154, 3.5, 0, 18, "tunnelOut");
        // low and fast over the lake
        w(170, 2.3, 6, 21);
        w(192, 1.6, -6, 23, "ring:5.0");
        w(214, 2.3, 3, 22);
        w(232, 4.5, 0, 21, "arch");
        w(250, 6.5, 0, 20);
        // climb to the tower and dive off it
        w(268, 12, -6, 19);
        w(284, 24, 0, 19);
        w(296, 36, 0, 18, "towerTop");
        w(305, 37, 0, 18);
        w(313, 32, 0, 18);
        w(319.5, 22, 0, 18, "ring:5.2");
        w(323, 11, 0, 19, "ring:5.2");
        w(328, 4.5, 0, 19);
        w(341, 3.0, 0, 20);
        // corkscrew: helix of radius R around an axis along the course, rings every quarter turn
        w(355, 4.0, 0, 20);
        w(366, 5.5, 2, 18);
        double R = 11, axisH = 17, pitch = 36, x0 = 374, v = 17;
        for (int i = 0; i <= 16 * 2; i++) {
            double phi = Math.PI * 2 * i / 16.0;
            boolean ring = i % 4 == 0 && i >= 4 && i <= 28; // every quarter turn
            w(x0 + pitch * phi / (2 * Math.PI), axisH - R * Math.cos(phi), R * Math.sin(phi), v, ring ? "ring:5.0" : null);
        }
        // vertical power loop (radius 16, drifts 6 m sideways so entry and exit do not meet)
        double lx = x0 + 2 * pitch + 36, lz = 0, LR = 16, lv = 20;
        w(x0 + 2 * pitch + 14, 5.5, 3.0, 19);
        w(lx - 6, 5.0, lz + 3, 20);
        for (int i = 0; i <= 24; i++) {
            double th = 2 * Math.PI * i / 24.0;
            boolean ring = i == 6 || i == 12 || i == 18;
            w(lx + LR * Math.sin(th), 5.0 + LR * (1 - Math.cos(th)), 3 + 6 * th / (2 * Math.PI), lv, ring ? "ring:5.2" : null);
        }
        // finish run, arch with fireworks, then a low pass and disarm for the slide
        w(lx + 22, 4.5, 9, 20);
        w(lx + 40, 3.6, 9, 20, "finish");
        w(lx + 56, 2.6, 9, 17);
        w(lx + 72, 1.5, 9, 14);
        w(lx + 84, 0.75, 9, 12, "disarm");
        w(lx + 92, 0.4, 9, 10);
        return new Path(W, 0.12);
    }
}
