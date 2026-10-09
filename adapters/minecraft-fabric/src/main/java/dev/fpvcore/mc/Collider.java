package dev.fpvcore.mc;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Collides the drone (a sphere) with Minecraft block collision shapes and reports contacts to fpvcore. */
public final class Collider {
    public static final double GROUND_TRACE_M = 0.70, TURTLE_TRACE_M = 0.145;
    public double lastImpulse;

    /** Distance along dir to the first block (collider shapes) or -1. */
    public static double trace(ClientLevel lvl, Entity ctx, double[] o, double[] d, double max) {
        Vec3 from = new Vec3(o[0], o[1], o[2]);
        BlockHitResult r = lvl.clip(new ClipContext(from, from.add(d[0] * max, d[1] * max, d[2] * max), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx));
        return r.getType() == HitResult.Type.MISS ? -1 : r.getLocation().distanceTo(from);
    }

    /** Ground-effect (-up) and turtle (+up) traces for the next step. */
    public void traces(ClientLevel lvl, Entity ctx, Drone drone, FpvNative.State s) {
        double[] up = bodyUp(s);
        double[] o = {s.pos[0], s.pos[1], s.pos[2]};
        double down = trace(lvl, ctx, o, new double[]{-up[0], -up[1], -up[2]}, GROUND_TRACE_M);
        double upd = trace(lvl, ctx, o, up, TURTLE_TRACE_M);
        drone.setTrace(down, upd);
    }

    /** Body "up" in Y-up world: the yup body frame has +X nose, +Y up, +Z right. */
    public static double[] bodyUp(FpvNative.State s) { return rot(s.quat, 0, 1, 0); }
    public static double[] bodyNose(FpvNative.State s) { return rot(s.quat, 1, 0, 0); }

    public static double[] rot(double[] q, double x, double y, double z) {
        double qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        double tx = 2 * (qy * z - qz * y), ty = 2 * (qz * x - qx * z), tz = 2 * (qx * y - qy * x);
        return new double[]{x + qw * tx + (qy * tz - qz * ty), y + qw * ty + (qz * tx - qx * tz), z + qw * tz + (qx * ty - qy * tx)};
    }

    /** Radius of the swept centre ray (m); the frame corners (Drone.hull) do the detailed contact. */
    public static final double SWEEP_R = 0.04;
    /** Collision radius of each hull point (frame thickness / prop guard), m. */
    public static final double POINT_R = 0.012;
    /** Drone-vs-ground Coulomb coefficient on a normal block (slipperiness 0.6); other blocks scale it. */
    public static final double MU = 0.7;

    /** Block-specific friction coefficient from Minecraft's slipperiness (0.6 normal, 0.8 slime, 0.98 ice, 0.989 blue ice). */
    public static double frictionFor(ClientLevel lvl, double x, double y, double z) {
        float slip = lvl.getBlockState(BlockPos.containing(x, y, z)).getBlock().getFriction();
        return MU * Math.max(0.04, Math.min(1.5, (1.0 - slip) / 0.4));
    }

    /** Resolve collisions after fpv_step. prev = position before the step. */
    public void resolve(ClientLevel lvl, Entity ctx, Drone drone, double[] prev, double unusedRadius) {
        lastImpulse = 0;
        FpvNative.State s = drone.state();
        final double r = SWEEP_R;
        // 1. swept test of the centre to avoid tunnelling through thin blocks at speed
        Vec3 a = new Vec3(prev[0], prev[1], prev[2]), b = new Vec3(s.pos[0], s.pos[1], s.pos[2]);
        Vec3 mv = b.subtract(a);
        if (mv.lengthSqr() > 1e-8) {
            Vec3 dir = mv.normalize();
            BlockHitResult hit = lvl.clip(new ClipContext(a, b.add(dir.scale(r)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx));
            if (hit.getType() != HitResult.Type.MISS && !hit.isInside()) {
                double nx = hit.getDirection().getStepX(), ny = hit.getDirection().getStepY(), nz = hit.getDirection().getStepZ();
                Vec3 h = hit.getLocation();
                drone.setPosition(h.x + nx * r, h.y + ny * r, h.z + nz * r);
                double mu = frictionFor(lvl, h.x - nx * 0.02, h.y - ny * 0.02, h.z - nz * 0.02);
                lastImpulse = Math.max(lastImpulse, drone.contactAt(h.x, h.y, h.z, nx, ny, nz, 0.0, mu));
                s = drone.state();
            }
        }
        // 2. hull corners (small spheres) against the block collision boxes, relaxation passes
        double[] hull = drone.hull();
        final double reach = 0.16 + POINT_R;
        s = drone.state();
        List<double[]> boxes = new ArrayList<>();
        AABB area = new AABB(s.pos[0] - reach, s.pos[1] - reach, s.pos[2] - reach, s.pos[0] + reach, s.pos[1] + reach, s.pos[2] + reach);
        for (VoxelShape shape : lvl.getBlockCollisions(null, area))
            shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> boxes.add(new double[]{x1, y1, z1, x2, y2, z2}));
        if (boxes.isEmpty()) return;
        for (int pass = 0; pass < 6; pass++) {
            boolean any = false;
            double passImpulse = 0;
            for (int i = 0; i + 2 < hull.length; i += 3) {
                s = drone.state();
                double[] o = rot(s.quat, hull[i], hull[i + 1], hull[i + 2]);
                double px = s.pos[0] + o[0], py = s.pos[1] + o[1], pz = s.pos[2] + o[2];
                // deepest penetration of this point: nx, ny, nz, depth, contact point xyz
                double[] best = new double[7];
                best[3] = 1e-4;
                for (double[] bx : boxes) {
                    double x1 = bx[0], y1 = bx[1], z1 = bx[2], x2 = bx[3], y2 = bx[4], z2 = bx[5];
                    double cx = Math.max(x1, Math.min(px, x2)), cy = Math.max(y1, Math.min(py, y2)), cz = Math.max(z1, Math.min(pz, z2));
                    double dx = px - cx, dy = py - cy, dz = pz - cz, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double nx, ny, nz, depth;
                    if (len > 1e-9) {
                        if (len >= POINT_R) continue;
                        nx = dx / len; ny = dy / len; nz = dz / len; depth = POINT_R - len;
                    } else { // point inside the box: leave through the nearest face
                        double[] dist = {px - x1, x2 - px, py - y1, y2 - py, pz - z1, z2 - pz};
                        int k = 0; for (int j = 1; j < 6; j++) if (dist[j] < dist[k]) k = j;
                        nx = k == 0 ? -1 : k == 1 ? 1 : 0; ny = k == 2 ? -1 : k == 3 ? 1 : 0; nz = k == 4 ? -1 : k == 5 ? 1 : 0;
                        depth = dist[k] + POINT_R;
                        cx = px - nx * dist[k]; cy = py - ny * dist[k]; cz = pz - nz * dist[k];
                    }
                    if (depth > best[3]) { best[0] = nx; best[1] = ny; best[2] = nz; best[3] = depth; best[4] = cx; best[5] = cy; best[6] = cz; }
                }
                if (best[3] <= 1e-4) continue;
                double mu = frictionFor(lvl, best[4] - best[0] * 0.02, best[5] - best[1] * 0.02, best[6] - best[2] * 0.02);
                passImpulse += drone.contactAt(best[4], best[5], best[6], best[0], best[1], best[2], best[3], mu);
                any = true;
            }
            lastImpulse = Math.max(lastImpulse, passImpulse);
            if (!any) break;
        }
    }
}
