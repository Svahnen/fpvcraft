package dev.fpvcore.mc.demo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Builds the demo race course in a flat world, entirely from code (so anyone can recreate it): meadow, forest, mountains, a lake, a hill with a lit tunnel,
 * a waterfall arch, a dive tower, glowing gate rings along the {@link CoursePath} and a finish arch. Work is split into per-chunk jobs that run on the server thread.
 */
public final class CourseBuilder {
    private CourseBuilder() {}

    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** Ground plane: the top face of the flat world's grass is y = GROUND (blocks at y = GROUND-1 and below). */
    public static final int GROUND = -60;
    public static final int X0 = -48, X1 = 620, Z0 = -150, Z1 = 150;
    static final Block SENTINEL = Blocks.GOLD_BLOCK;
    static final BlockPos SENTINEL_POS = new BlockPos(0, 200, 0);

    public static boolean built(ServerLevel lvl) { return lvl.getBlockState(SENTINEL_POS).is(SENTINEL); }

    // ---------------- terrain -----------------
    private static double sm(double a, double b, double x) { double t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); }
    private static double n2(double x, double z) {
        return Math.sin(x * 0.071 + 1.3) * Math.cos(z * 0.083 + 0.4) * 0.5 + Math.sin(x * 0.153 + z * 0.121 + 2.0) * 0.3 + Math.sin(x * 0.31 - z * 0.27) * 0.2;
    }

    /** Terrain height in blocks above the ground plane (negative = lake bed). */
    public static double height(double x, double z) {
        double az = Math.abs(z);
        double flat = sm(10, 45, Math.hypot(x, z)) * (1 - (1 - sm(0, 14, Math.abs(z - 9))) * sm(465, 490, x));  // flat launch area and finish strip
        double roll = 1.2 * (0.5 + 0.5 * n2(x, z)) * flat;                          // gentle meadow
        double prof = sm(26, 80, az) * (1 - sm(105, 150, az));
        double peaks = 14 + 26 * (0.5 + 0.5 * n2(x * 1.7 + 40, z * 1.3)) + 8 * Math.abs(n2(x * 3.1, z * 2.9));
        double mount = prof * prof * peaks;
        double hill = 17 * Math.exp(-Math.pow((x - 127) / 24.0, 20)) * Math.exp(-Math.pow(z / 20.0, 2));
        double land = roll + mount + hill;
        double e = Math.pow((x - 205) / 64.0, 2) + Math.pow(z / 48.0, 2);
        double lake = 1 - sm(0.45, 1.0, e);
        return land * (1 - lake) + (-3.0) * lake;
    }

    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState(), DIRT = Blocks.DIRT.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState(),
        SAND = Blocks.SAND.defaultBlockState(), SNOW = Blocks.SNOW_BLOCK.defaultBlockState(), WATER = Blocks.WATER.defaultBlockState(), AIR = Blocks.AIR.defaultBlockState(),
        GRAVEL = Blocks.GRAVEL.defaultBlockState();

    private static long hash(int x, int z) { long h = x * 73856093L ^ z * 19349663L ^ 0x9E3779B97F4A7C15L; h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32; return h; }
    private static double rnd(int x, int z, int k) { return ((hash(x + k * 131, z - k * 17) >>> 11) & 0xFFFFF) / (double) 0x100000; }

    private static void set(ServerLevel l, int x, int y, int z, BlockState s) { l.setBlock(new BlockPos(x, y, z), s, FLAGS); }

    private static void column(ServerLevel l, int x, int z) {
        double hd = height(x + 0.5, z + 0.5);
        int h = (int) Math.round(hd);
        int top = GROUND - 1 + h;   // y of the top solid block
        double slope = Math.abs(height(x + 1.5, z + 0.5) - height(x - 0.5, z + 0.5)) + Math.abs(height(x + 0.5, z + 1.5) - height(x + 0.5, z - 0.5));
        if (h < 0) { // lake bed / beach
            int bed = Math.max(-64, top);
            for (int y = -64; y <= GROUND - 1; y++) {
                if (y < bed) { if (y == -64) set(l, x, y, z, STONE); }
                else if (y == bed) set(l, x, y, z, SAND);
                else if (y <= GROUND - 2) set(l, x, y, z, h <= -2 ? WATER : AIR);
                else set(l, x, y, z, AIR);
            }
            return;
        }
        BlockState surf = hd > 30 ? SNOW : (hd > 17 || slope > 3.2) ? STONE : (hd < 0.8 && Math.abs(z) < 80 && x > 130 && x < 270 && Math.pow((x - 205) / 64.0, 2) + Math.pow(z / 48.0, 2) < 1.15) ? SAND : GRASS;
        if (h > 0) {
            for (int y = GROUND; y <= top; y++) {
                BlockState s = y == top ? surf : (y >= top - 3 ? (surf == STONE || surf == SNOW ? STONE : DIRT) : STONE);
                set(l, x, y, z, s);
            }
        } else if (surf != GRASS) set(l, x, top, z, surf);
        // plants on flat-ish grass
        if (surf == GRASS && slope < 2.5) {
            double r = rnd(x, z, 1);
            BlockState p = null;
            if (r < 0.10) p = Blocks.SHORT_GRASS.defaultBlockState();
            else if (r < 0.112) p = Blocks.DANDELION.defaultBlockState();
            else if (r < 0.122) p = Blocks.POPPY.defaultBlockState();
            else if (r < 0.130) p = Blocks.CORNFLOWER.defaultBlockState();
            else if (r < 0.136) p = Blocks.AZURE_BLUET.defaultBlockState();
            boolean strip = x > 440 && Math.abs(z - 9) < 7;       // keep the slide strip bare
            boolean pad = Math.abs(x) < 8 && Math.abs(z) < 6;
            if (p != null && !strip && !pad) set(l, x, top + 1, z, p);
        }
    }

    private static BlockState leaves(Block b) { return b.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true); }

    private static void tree(ServerLevel l, int x, int z, boolean spruce, boolean birch, int top) {
        double r = rnd(x, z, 7);
        int th = spruce ? 6 + (int) (r * 4) : 4 + (int) (r * 3);
        Block log = spruce ? Blocks.SPRUCE_LOG : birch ? Blocks.BIRCH_LOG : Blocks.OAK_LOG;
        BlockState lv = leaves(spruce ? Blocks.SPRUCE_LEAVES : birch ? Blocks.BIRCH_LEAVES : Blocks.OAK_LEAVES);
        for (int i = 1; i <= th; i++) set(l, x, top + i, z, log.defaultBlockState());
        if (spruce) {
            for (int dy = 0; dy < th; dy++) {
                int rad = Math.max(0, Math.min(3, (th - dy) / 2 - (dy < 2 ? 1 : 0))); // wide at the bottom, narrow at the top
                if (dy < 2) continue;
                int yy = top + 1 + dy;
                for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++)
                    if (Math.abs(dx) + Math.abs(dz) <= rad + (rad > 1 ? 1 : 0) && !(dx == 0 && dz == 0 && dy < th)) set(l, x + dx, yy, z + dz, lv);
            }
            set(l, x, top + th + 1, z, lv);
        } else {
            for (int dy = th - 2; dy <= th + 1; dy++) {
                int rad = dy <= th - 1 ? 2 : 1;
                for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
                    if (Math.abs(dx) == rad && Math.abs(dz) == rad && (rnd(x + dx, z + dz, dy) < 0.5 || dy > th - 1)) continue;
                    if (dx == 0 && dz == 0 && dy < th) continue;
                    set(l, x + dx, top + 1 + dy, z + dz, lv);
                }
            }
        }
    }

    private static void decorate(ServerLevel l, int cx, int cz) {
        for (int x = cx * 16; x < cx * 16 + 16; x++) for (int z = cz * 16; z < cz * 16 + 16; z++) {
            if (Math.abs(z) < 24 && x > -30) continue;           // keep the corridor clear
            double hd = height(x + 0.5, z + 0.5);
            if (hd < 0.5 || hd > 24) continue;
            double slope = Math.abs(height(x + 1.5, z + 0.5) - height(x - 0.5, z + 0.5)) + Math.abs(height(x + 0.5, z + 1.5) - height(x + 0.5, z - 0.5));
            if (slope > 3.0) continue;
            double dens = Math.abs(z) < 70 ? 0.020 : 0.012;
            if (rnd(x, z, 3) < dens) tree(l, x, z, hd > 9 || rnd(x, z, 4) < 0.25, hd < 9 && rnd(x, z, 5) < 0.2, GROUND - 1 + (int) Math.round(hd));
        }
    }

    // ---------------- structures -----------------
    private static final Block[] COLORS = {Blocks.ORANGE_CONCRETE, Blocks.CYAN_CONCRETE, Blocks.LIME_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.YELLOW_CONCRETE};
    private static final Block[] GLOWS = {Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.SEA_LANTERN, Blocks.SEA_LANTERN, Blocks.GLOWSTONE};

    static Vector3d world(Vector3d local) { return new Vector3d(CoursePath.OX + local.x, CoursePath.OY + local.y, CoursePath.OZ + local.z); }

    /** Glowing ring of radius r around the point c, in the plane perpendicular to dir. */
    private static void ring(ServerLevel l, Vector3d c, Vector3d dir, double r, int colorIdx) {
        Vector3d d = new Vector3d(dir).normalize();
        Vector3d ref = Math.abs(d.y) > 0.9 ? new Vector3d(1, 0, 0) : new Vector3d(0, 1, 0);
        Vector3d u = new Vector3d(ref).sub(new Vector3d(d).mul(ref.dot(d))).normalize(), w = new Vector3d(d).cross(u).normalize();
        Map<BlockPos, Integer> cells = new HashMap<>();
        int segs = 24;
        double dth = 0.3 / r;
        for (double th = 0; th < 2 * Math.PI; th += dth) {
            Vector3d p = new Vector3d(c).fma(r * Math.cos(th), u).fma(r * Math.sin(th), w);
            int seg = (int) (th / (2 * Math.PI) * segs);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                int bx = (int) Math.floor(p.x) + dx, by = (int) Math.floor(p.y) + dy, bz = (int) Math.floor(p.z) + dz;
                double dd = Math.sqrt(Math.pow(bx + 0.5 - p.x, 2) + Math.pow(by + 0.5 - p.y, 2) + Math.pow(bz + 0.5 - p.z, 2));
                if (dd <= 0.95) cells.putIfAbsent(new BlockPos(bx, by, bz), seg);
            }
        }
        for (var e : cells.entrySet()) {
            boolean glow = e.getValue() % 3 == 0;
            l.setBlock(e.getKey(), (glow ? GLOWS[colorIdx % GLOWS.length] : COLORS[colorIdx % COLORS.length]).defaultBlockState(), FLAGS);
        }
    }

    private static void box(ServerLevel l, int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(l, x, y, z, s);
    }
    private static BlockState b(Block b) { return b.defaultBlockState(); }

    private static void tunnel(ServerLevel l, Path.Mark in, Path.Mark out) {
        int x1 = (int) Math.floor(CoursePath.OX + in.pos.x) - 1, x2 = (int) Math.floor(CoursePath.OX + out.pos.x) + 1, zc = (int) Math.floor(CoursePath.OZ + in.pos.z), g = GROUND;
        box(l, x1, g - 1, zc - 7, x2, g + 11, zc + 7, b(Blocks.STONE_BRICKS));       // solid shell (floor row included)
        box(l, x1, g, zc - 5, x2, g + 8, zc + 5, AIR);                                 // the bore: 11 wide, 9 high
        for (int x = x1; x <= x2; x++) {
            if ((x - x1) % 6 == 3) { set(l, x, g + 8, zc - 3, b(Blocks.SEA_LANTERN)); set(l, x, g + 8, zc + 3, b(Blocks.SEA_LANTERN)); }
            if ((x - x1) % 4 == 1) { set(l, x, g + 1, zc - 5, b(Blocks.SEA_LANTERN)); set(l, x, g + 1, zc + 5, b(Blocks.SEA_LANTERN)); }
            set(l, x, g - 1, zc, b(Blocks.POLISHED_ANDESITE));
        }
        // coloured portal frames at both mouths
        for (int xx : new int[]{x1, x2}) {
            for (int z = zc - 6; z <= zc + 6; z++) { set(l, xx, g + 9, z, b(Blocks.ORANGE_CONCRETE)); set(l, xx, g + 10, z, b(Blocks.ORANGE_CONCRETE)); }
            for (int y = g; y <= g + 8; y++) { set(l, xx, y, zc - 6, b(Blocks.ORANGE_CONCRETE)); set(l, xx, y, zc + 6, b(Blocks.ORANGE_CONCRETE)); set(l, xx, y, zc - 7, b(Blocks.WHITE_CONCRETE)); set(l, xx, y, zc + 7, b(Blocks.WHITE_CONCRETE)); }
            box(l, xx, g + 9, zc - 5, xx, g + 9, zc + 5, b(Blocks.SEA_LANTERN));
        }
        // clear the shell's open ends (the bore runs through)
        box(l, x1, g, zc - 5, x1, g + 8, zc + 5, AIR);
        box(l, x2, g, zc - 5, x2, g + 8, zc + 5, AIR);
    }

    private static void waterfallArch(ServerLevel l, Path.Mark m) {
        int bx = (int) Math.floor(CoursePath.OX + m.pos.x) - 1, zc = (int) Math.floor(CoursePath.OZ + m.pos.z), g = GROUND;
        BlockState brick = b(Blocks.STONE_BRICKS), moss = b(Blocks.MOSSY_STONE_BRICKS);
        // pillars (from the lake bed up)
        for (int sgn : new int[]{-1, 1}) {
            int z1 = zc + sgn * 6, z2 = zc + sgn * 8;
            box(l, bx, -64, Math.min(z1, z2), bx + 3, g + 13, Math.max(z1, z2), brick);
            box(l, bx + 1, -64, Math.min(z1, z2), bx + 2, g + 3, Math.max(z1, z2), moss);
            set(l, bx + 1, g + 14, zc + sgn * 7, b(Blocks.SEA_LANTERN)); set(l, bx + 2, g + 14, zc + sgn * 7, b(Blocks.SEA_LANTERN));
        }
        // lintel with a slot for the water sheet
        box(l, bx, g + 9, zc - 8, bx + 3, g + 13, zc + 8, brick);
        box(l, bx + 1, g + 9, zc - 5, bx + 2, g + 9, zc + 5, WATER);                  // sources (open below, walls on the sides, roof above)
        box(l, bx, g + 13, zc - 8, bx + 3, g + 13, zc + 8, b(Blocks.PRISMARINE_BRICKS));
        for (int z = zc - 8; z <= zc + 8; z += 2) { set(l, bx, g + 12, z, b(Blocks.SEA_LANTERN)); set(l, bx + 3, g + 12, z, b(Blocks.SEA_LANTERN)); }
        for (int cxx = (bx >> 4) - 1; cxx <= (bx >> 4) + 1; cxx++) for (int czz = (zc >> 4) - 1; czz <= (zc >> 4) + 1; czz++) l.setChunkForced(cxx, czz, true); // keep the water ticking
        // keep the passage clear below the lintel
        box(l, bx, g - 1, zc - 5, bx + 3, g + 8, zc + 5, AIR);
        box(l, bx, -62, zc - 5, bx + 3, g - 2, zc + 5, WATER);
        box(l, bx, -63, zc - 5, bx + 3, -63, zc + 5, WATER);
    }

    private static void tower(ServerLevel l, Path.Mark m) {
        int cx = (int) Math.floor(CoursePath.OX + m.pos.x) + 1, cz = (int) Math.floor(CoursePath.OZ + m.pos.z), g = GROUND, hgt = 24;
        for (int y = g - 1; y <= g + hgt; y++) {
            boolean band = ((y - g) / 4) % 2 == 0;
            box(l, cx - 3, y, cz - 3, cx + 3, y, cz + 3, band ? b(Blocks.WHITE_CONCRETE) : b(Blocks.RED_CONCRETE));
        }
        box(l, cx - 5, g + hgt + 1, cz - 5, cx + 5, g + hgt + 1, cz + 5, b(Blocks.POLISHED_ANDESITE));
        for (int dx : new int[]{-5, 5}) for (int dz : new int[]{-5, 5}) { box(l, cx + dx, g + hgt + 2, cz + dz, cx + dx, g + hgt + 4, cz + dz, b(Blocks.SEA_LANTERN)); }
        for (int i = -5; i <= 5; i++) { set(l, cx + i, g + hgt + 2, cz - 5, b(Blocks.IRON_BARS)); set(l, cx + i, g + hgt + 2, cz + 5, b(Blocks.IRON_BARS)); set(l, cx - 5, g + hgt + 2, cz + i, b(Blocks.IRON_BARS)); set(l, cx + 5, g + hgt + 2, cz + i, b(Blocks.IRON_BARS)); }
    }

    private static void finishArch(ServerLevel l, Path.Mark m) {
        int x = (int) Math.floor(CoursePath.OX + m.pos.x), zc = (int) Math.floor(CoursePath.OZ + m.pos.z), g = GROUND, hgt = 12, w = 9;
        for (int dx = 0; dx <= 1; dx++) {
            for (int y = g; y <= g + hgt; y++) for (int dz : new int[]{-w, w}) set(l, x + dx, y, zc + dz, ((x + dx + y + dz) & 1) == 0 ? b(Blocks.WHITE_CONCRETE) : b(Blocks.BLACK_CONCRETE));
            for (int z = zc - w; z <= zc + w; z++) for (int y = g + hgt - 1; y <= g + hgt; y++) set(l, x + dx, y, z, ((x + dx + y + z) & 1) == 0 ? b(Blocks.WHITE_CONCRETE) : b(Blocks.BLACK_CONCRETE));
        }
        for (int z = zc - w; z <= zc + w; z += 3) set(l, x, g + hgt - 2, z, b(Blocks.SEA_LANTERN));
        // checkered finish line on the ground
        for (int dx = 0; dx <= 1; dx++) for (int z = zc - w + 1; z <= zc + w - 1; z++) set(l, x + dx, g - 1, z, ((x + dx + z) & 1) == 0 ? b(Blocks.WHITE_CONCRETE) : b(Blocks.BLACK_CONCRETE));
    }

    private static void startPad(ServerLevel l) {
        int g = GROUND;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) set(l, x, g - 1, z, (Math.abs(x) == 4 || Math.abs(z) == 4) ? b(Blocks.LIGHT_GRAY_CONCRETE) : b(Blocks.POLISHED_ANDESITE));
        for (int dx : new int[]{-4, 4}) for (int dz : new int[]{-4, 4}) { set(l, dx, g, dz, b(Blocks.OAK_FENCE)); set(l, dx, g + 1, dz, b(Blocks.OAK_FENCE)); set(l, dx, g + 2, dz, b(Blocks.SEA_LANTERN)); }
        for (int z = -3; z <= 3; z++) set(l, 6, g - 1, z, ((6 + z) & 1) == 0 ? b(Blocks.WHITE_CONCRETE) : b(Blocks.BLACK_CONCRETE));
    }

    // ---------------- job queue -----------------
    public static final ArrayDeque<Runnable> JOBS = new ArrayDeque<>();
    public static int total, done;

    public static void queue(ServerLevel lvl, Path path) {
        total = 0; done = 0;
        int cx0 = Math.floorDiv(X0, 16), cx1 = Math.floorDiv(X1, 16), cz0 = Math.floorDiv(Z0, 16), cz1 = Math.floorDiv(Z1, 16);
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) {
            final int fx = cx, fz = cz;
            JOBS.add(() -> {
                lvl.getChunk(fx, fz);
                for (int x = fx * 16; x < fx * 16 + 16; x++) for (int z = fz * 16; z < fz * 16 + 16; z++) column(lvl, x, z);
                decorate(lvl, fx, fz);
                for (Mob m : lvl.getEntitiesOfClass(Mob.class, new AABB(fx * 16, -64, fz * 16, fx * 16 + 16, 320, fz * 16 + 16))) m.discard();
            });
        }
        JOBS.add(() -> startPad(lvl));
        Path.Mark tin = null;
        for (Path.Mark m : path.marks) {
            Path.Mark mk = m;
            if (m.tag.startsWith("ring:")) {
                double r = Double.parseDouble(m.tag.substring(5));
                int idx = (int) Math.abs(Math.round(m.t * 3)) ;
                JOBS.add(() -> ring(lvl, world(mk.pos), mk.dir, r, idx));
            } else if (m.tag.equals("tunnel")) tin = m;
            else if (m.tag.equals("tunnelOut")) { Path.Mark a = tin, bb = m; JOBS.add(() -> tunnel(lvl, a, bb)); }
            else if (m.tag.equals("arch")) JOBS.add(() -> waterfallArch(lvl, mk));
            else if (m.tag.equals("towerTop")) JOBS.add(() -> tower(lvl, mk));
            else if (m.tag.equals("finish")) JOBS.add(() -> finishArch(lvl, mk));
        }
        JOBS.add(() -> set(lvl, SENTINEL_POS.getX(), SENTINEL_POS.getY(), SENTINEL_POS.getZ(), SENTINEL.defaultBlockState()));
        total = JOBS.size();
    }

    /** Runs queued jobs for at most budgetMs; returns true when everything is done. */
    public static boolean run(long budgetMs) {
        long end = System.nanoTime() + budgetMs * 1_000_000L;
        while (!JOBS.isEmpty() && System.nanoTime() < end) { JOBS.poll().run(); done++; }
        return JOBS.isEmpty();
    }
}
