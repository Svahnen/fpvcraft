package dev.fpvcore.mc;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the multiplayer / chunk-following design.
 * <p>While a pilot is flying, the pilot's ServerPlayer is moved to the drone every tick (vanilla chunk loading, sending and entity tracking follow the player),
 * client movement packets are ignored (mixin), the player is invisible / invulnerable / without gravity, and a {@link PilotBodyEntity} stands at the launch spot.
 * A {@link DroneEntity} mirrors the pilot's pose for everybody. Everything is restored on land, death, dimension change, disconnect, server stop, and
 * (via the saved home tag in the player data) after a crash.
 */
public final class Flights {
    private Flights() {}

    static final class Home {
        ResourceKey<Level> dim; double x, y, z; float yaw, pitch; boolean noGravity;
    }

    static final class Flight {
        final UUID id;
        volatile boolean active;
        Home home;
        ServerLevel level;          // level of the drone (the pilot's level)
        PosePayload pose;
        long recvNs;
        DroneEntity drone;
        PilotBodyEntity body;
        boolean hadNoGravity;
        int ticks, lureTicks;
        /** The server ended this flight (refused / destroyed / body hit ...): ignore "flying" poses until the client has landed (a non-flying or gone pose). */
        boolean ended;
        int food; float saturation, exhaustion;
        double maxSpeed; long blockedDamage;
        Flight(UUID id) { this.id = id; }
    }

    static final Map<UUID, Flight> FLIGHTS = new ConcurrentHashMap<>();
    /** Home tags read from player data at login (the previous session ended mid-flight): restored on JOIN. */
    static final Map<UUID, Home> RECOVERED = new ConcurrentHashMap<>();
    private static MinecraftServer server;
    private static volatile boolean stopping;

    static final boolean QA = System.getProperty("fpvcore.qalog") != null;
    static final boolean CRASHTEST = System.getProperty("fpvcore.crashtest") != null;
    public static boolean hasRecord(UUID id) { return FLIGHTS.containsKey(id); }
    public static boolean isActive(UUID id) { Flight f = FLIGHTS.get(id); return f != null && f.active; }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(s -> { server = s; FLIGHTS.clear(); stopping = false; });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            stopping = true;
            for (Flight f : new ArrayList<>(FLIGHTS.values())) {
                ServerPlayer p = s.getPlayerList().getPlayer(f.id);
                if (p != null) end(p, f, true, null);
                discard(f);
            }
            FLIGHTS.clear();
            server = null;
        });
        ServerTickEvents.START_SERVER_TICK.register(Flights::tick);
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> recover(h.player));
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> s.executeBlocking(() -> { // the event can arrive on a network thread; the world must only be touched from the server thread
            Flight f = FLIGHTS.remove(h.player.getUUID());
            if (f == null) return;
            if (f.active) end(h.player, f, true, null);
            discard(f);
        }));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, src, amt) -> {
            if (!(e instanceof ServerPlayer p) || !isActive(p.getUUID())) return true;
            if (src.is(DamageTypes.GENERIC_KILL)) return true; // /kill
            Flight f = FLIGHTS.get(p.getUUID());
            if (f != null) f.blockedDamage++;
            if (QA) FpvMod.LOG.info("QA damage blocked for {}: {} {}", p.getGameProfile().getName(), src.getMsgId(), amt);
            return false; // everything else, including the void (the position is clamped above it anyway)
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> {
            if (e instanceof ServerPlayer p) { Flight f = FLIGHTS.get(p.getUUID()); if (f != null && f.active) end(p, f, false, "died"); }
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((p, from, to) -> {
            Flight f = FLIGHTS.get(p.getUUID());
            if (f != null && f.active) end(p, f, false, "dimension change");
        });
    }

    // ---- packets -----------------------------------------------------------------------------------------------------------------------------

    public static void onPose(ServerPlayer p, PosePayload m) {
        if (!m.valid() || stopping) return; // (a pose that arrives while the server shuts down must not restart the flight)
        server = p.server;
        if (m.gone()) { // the pilot despawned the drone (G / land): end the flight, restore the pilot, remove the drone entity
            Flight g = FLIGHTS.remove(p.getUUID());
            if (g != null) { if (g.active) end(p, g, true, null); discard(g); }
            return;
        }
        Flight f = FLIGHTS.computeIfAbsent(p.getUUID(), Flight::new);
        if (f.active) p.resetLastActionTime(); // the movement packets (which normally reset the idle timer) are ignored while flying
        if (!m.flying()) f.ended = false;
        if (m.flying() && !f.active && !f.ended) {
            String refuse = p.isDeadOrDying() ? "you are dead" : p.isSpectator() ? "not in spectator mode" : p.isSleeping() ? "you are sleeping" : p.isPassenger() ? "dismount first (you are riding)" : null;
            if (refuse != null) { // tell the client, otherwise it would fly "client-only" next to a server that never moved the player
                f.ended = true;
                FpvMod.LOG.info("flight refused for {}: {}", p.getGameProfile().getName(), refuse);
                if (p.connection != null && ServerPlayNetworking.canSend(p, EndPayload.TYPE)) ServerPlayNetworking.send(p, new EndPayload("cannot fly: " + refuse));
                return;
            }
            start(p, f, m);
        } else if (!m.flying() && f.active) end(p, f, true, null);
        if (f.ended) { f.pose = m; f.recvNs = System.nanoTime(); return; }
        f.pose = m;
        f.recvNs = System.nanoTime();
        if (!f.active) f.level = p.serverLevel();
        update(p, f, false);
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------------------------------------

    private static void start(ServerPlayer p, Flight f, PosePayload m) {
        Home h = new Home();
        h.dim = p.serverLevel().dimension(); h.x = p.getX(); h.y = p.getY(); h.z = p.getZ(); h.yaw = p.getYRot(); h.pitch = p.getXRot();
        f.home = h;
        f.level = p.serverLevel();
        f.hadNoGravity = p.isNoGravity();
        f.active = true;
        var fd = p.getFoodData();
        f.food = fd.getFoodLevel(); f.saturation = fd.getSaturationLevel(); f.exhaustion = fd.getExhaustionLevel();
        p.resetFallDistance();
        p.setNoGravity(true);
        p.noPhysics = true;
        p.setInvisible(true);
        FpvMod.LOG.info("flight start: {} home {} {} {} (chunks follow the drone)", p.getGameProfile().getName(), (int) h.x, (int) h.y, (int) h.z);
        if (p.connection != null && ServerPlayNetworking.canSend(p, LinkPayload.TYPE)) ServerPlayNetworking.send(p, new LinkPayload(true));
    }

    /** Ends the flight. restore = move the player back to the launch spot; reason != null tells the client. */
    static void end(ServerPlayer p, Flight f, boolean restore, String reason) {
        if (!f.active) return;
        f.active = false;
        if (reason != null) f.ended = true;
        resetFlags(p, f.hadNoGravity);
        if (f.body != null) { f.body.discard(); f.body = null; }
        Home h = f.home;
        if (restore && h != null) {
            try {
                ServerLevel hl = p.server.getLevel(h.dim);
                if (hl != null) {
                    Vec3 spot = safeSpot(p, hl, h);
                    p.resetFallDistance();
                    p.teleportTo(hl, spot.x, spot.y, spot.z, h.yaw, h.pitch);
                    p.connection.resetPosition();
                }
            } catch (RuntimeException e) { FpvMod.LOG.warn("restoring pilot failed", e); }
        }
        p.setDeltaMovement(Vec3.ZERO);
        p.resetFallDistance();
        if (reason != null && p.connection != null) ServerPlayNetworking.send(p, new EndPayload(reason));
        if (f.drone != null && !f.drone.isRemoved()) f.drone.getEntityData().set(DroneEntity.FLAGS, flags(f.pose != null && f.pose.armed(), false));
        FpvMod.LOG.info("flight end: {} ({}) now at {} {} {} (blocked damage events: {}, max speed {} m/s)", p.getGameProfile().getName(), reason == null ? "landed" : reason,
            (int) p.getX(), (int) p.getY(), (int) p.getZ(), f.blockedDamage, (int) f.maxSpeed);
    }

    // ---- safe return spot ------------------------------------------------------------------------------------------------------------------

    private static boolean dangerous(BlockState b) {
        return b.getFluidState().is(FluidTags.LAVA) || b.is(BlockTags.FIRE) || b.is(Blocks.CACTUS) || b.is(Blocks.MAGMA_BLOCK) || b.is(BlockTags.CAMPFIRES)
            || b.is(Blocks.SWEET_BERRY_BUSH) || b.is(Blocks.WITHER_ROSE) || b.is(Blocks.POWDER_SNOW) || b.is(Blocks.COBWEB);
    }

    /** Free of collisions, lava / fire / cactus ..., inside the world border (and, with needFloor, with ground or water within 3 blocks below). */
    static boolean spotOk(ServerLevel l, EntityDimensions dims, double x, double y, double z, boolean needFloor) {
        AABB bb = dims.makeBoundingBox(x, y, z);
        if (y < l.getMinBuildHeight() + 1 || y > l.getMaxBuildHeight() - 2 || !l.getWorldBorder().isWithinBounds(bb) || !l.noCollision(bb)) return false;
        BlockPos lo = BlockPos.containing(bb.minX, bb.minY - 1, bb.minZ), hi = BlockPos.containing(bb.maxX, bb.maxY, bb.maxZ);
        for (BlockPos q : BlockPos.betweenClosed(lo, hi)) if (dangerous(l.getBlockState(q))) return false;
        if (needFloor) {
            if (!l.noCollision(bb.move(0, -3, 0).minmax(bb))) return true; // ground below (the swept box between is not empty)
            return l.getFluidState(BlockPos.containing(x, y - 0.2, z)).is(FluidTags.WATER);
        }
        return true;
    }

    /** The launch spot, or - when it became unsafe (blocks / lava / fire placed there) - the nearest safe spot around it, or the world spawn column. */
    static Vec3 safeSpot(ServerPlayer p, ServerLevel l, Home h) {
        EntityDimensions dims = p.getDimensions(net.minecraft.world.entity.Pose.STANDING);
        if (spotOk(l, dims, h.x, h.y, h.z, false)) return new Vec3(h.x, h.y, h.z);
        int bx = Mth.floor(h.x), bz = Mth.floor(h.z);
        for (int r = 1; r <= 8; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int k = 0; k <= 12; k++) {
                        int dy = (k + 1) / 2 * ((k & 1) == 1 ? 1 : -1);
                        double x = bx + dx + 0.5, y = Math.floor(h.y) + dy, z = bz + dz + 0.5;
                        if (spotOk(l, dims, x, y, z, true)) { FpvMod.LOG.info("home {} {} {} is unsafe: returning {} to {} {} {}", (int) h.x, (int) h.y, (int) h.z, p.getGameProfile().getName(), x, y, z); return new Vec3(x, y, z); }
                    }
                }
        BlockPos sp = l.getSharedSpawnPos();
        int y = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, sp.getX(), sp.getZ());
        FpvMod.LOG.warn("no safe spot near {} {} {}: returning {} to the world spawn", (int) h.x, (int) h.y, (int) h.z, p.getGameProfile().getName());
        return new Vec3(sp.getX() + 0.5, y, sp.getZ() + 0.5);
    }

    private static void resetFlags(ServerPlayer p, boolean noGravity) {
        p.noPhysics = false;
        p.setNoGravity(noGravity);
        p.setInvisible(p.hasEffect(MobEffects.INVISIBILITY));
    }

    private static void discard(Flight f) {
        if (f.drone != null) { f.drone.discard(); f.drone = null; }
        if (f.body != null) { f.body.discard(); f.body = null; }
    }

    static void onBodyHurt(PilotBodyEntity body, DamageSource src, float amt) {
        if (src.is(DamageTypes.DROWN) || src.is(DamageTypes.IN_WALL) || src.is(DamageTypes.CRAMMING) || src.is(DamageTypes.FALL) || src.is(DamageTypes.STARVE)) return; // not an attack
        UUID owner = body.owner().orElse(null);
        Flight f = owner == null ? null : FLIGHTS.get(owner);
        if (f == null || !f.active) return;
        ServerPlayer p = body.getServer().getPlayerList().getPlayer(owner);
        if (p == null) return;
        end(p, f, true, "your body was attacked");
        p.hurt(src, amt);
    }

    /** The drone entity was destroyed (mob, explosion, fire / lava): the flight ends like G, the pilot returns to the body unharmed. */
    static void onDroneDestroyed(DroneEntity d, String why) {
        UUID owner = d.owner().orElse(null);
        Flight f = owner == null ? null : FLIGHTS.get(owner);
        if (f == null || f.drone != d) return;
        ServerLevel lvl = (ServerLevel) d.level();
        lvl.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION, d.getX(), d.getY(), d.getZ(), 1, 0, 0, 0, 0);
        lvl.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, d.getX(), d.getY(), d.getZ(), 20, 0.15, 0.1, 0.15, 0.02);
        lvl.playSound(null, d.getX(), d.getY(), d.getZ(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.NEUTRAL, 0.6f, 1.6f);
        f.drone = null;
        d.discard();
        ServerPlayer p = lvl.getServer().getPlayerList().getPlayer(owner);
        FpvMod.LOG.info("drone of {} destroyed: {}", p == null ? owner : p.getGameProfile().getName(), why);
        if (p != null) {
            if (f.active) end(p, f, true, "Drone destroyed: " + why);
            else { f.ended = true; if (p.connection != null) ServerPlayNetworking.send(p, new EndPayload("Drone destroyed: " + why)); }
        }
    }

    /** Something else teleported the flying player (/tp by an op, a plugin ...): the flight ends where the player was sent; the body goes away. */
    public static void onExternalTeleport(ServerPlayer p) {
        Flight f = FLIGHTS.get(p.getUUID());
        if (f == null || !f.active) return;
        FpvMod.LOG.info("{} was teleported by someone else during a flight: ending the flight there", p.getGameProfile().getName());
        end(p, f, false, "you were teleported");
    }

    // ---- per-tick ----------------------------------------------------------------------------------------------------------------------------

    private static void tick(MinecraftServer s) {
        server = s;
        for (Flight f : FLIGHTS.values()) {
            ServerPlayer p = s.getPlayerList().getPlayer(f.id);
            if (p == null) { if (!f.active) { discard(f); FLIGHTS.remove(f.id); } continue; }
            if (f.pose == null) continue;
            if (CRASHTEST && f.active && ++f.ticks == 150) { // dev only: simulate a server crash mid-flight right after a save
                FpvMod.LOG.info("CRASHTEST: saving and halting the server mid-flight at {} {} {}", (int) p.getX(), (int) p.getY(), (int) p.getZ());
                s.saveEverything(false, true, true);
                Runtime.getRuntime().halt(1);
            }
            if (f.active && (p.isDeadOrDying() || p.serverLevel() != f.level)) { end(p, f, false, "interrupted"); continue; }
            update(p, f, true);
            if (QA && f.active && s.getTickCount() % 40 == 0)
                FpvMod.LOG.info("QA flight {} pos=({}, {}, {}) hp={} food={} fall={} invisible={} noPhys={} drone={} body={} maxSpeed={} blockedDamage={} gm={} ignoresTriggers={} unseen={} unpickable={}", p.getGameProfile().getName(),
                    (int) p.getX(), (int) p.getY(), (int) p.getZ(), p.getHealth(), p.getFoodData().getFoodLevel(), p.fallDistance, p.isInvisible(), p.noPhysics,
                    f.drone != null && !f.drone.isRemoved(), f.body != null && !f.body.isRemoved(), (int) f.maxSpeed, f.blockedDamage, p.gameMode.getGameModeForPlayer(), p.isIgnoringBlockTriggers(), !p.canBeSeenByAnyone(), !p.isPickable());
        }
    }

    private static byte flags(boolean armed, boolean flying) { return (byte) ((armed ? 1 : 0) | (flying ? 2 : 0)); }

    /** Moves the player (while flying) and the drone entity to the (extrapolated) pose; keeps the pilot body alive. */
    private static void update(ServerPlayer p, Flight f, boolean extrapolate) {
        PosePayload m = f.pose;
        if (m == null || f.level == null || f.ended) return;
        double ext = f.active && extrapolate ? Math.min(0.1, (System.nanoTime() - f.recvNs) / 1e9) : 0;
        double x = m.x() + m.vx() * ext, y = m.y() + m.vy() * ext, z = m.z() + m.vz() * ext;
        ServerLevel lvl = f.level;
        double sp = Math.sqrt(m.vx() * m.vx() + m.vy() * m.vy() + m.vz() * m.vz());
        f.maxSpeed = Math.max(f.maxSpeed, sp);
        if (f.active) {
            y = Math.max(y, lvl.getMinBuildHeight() - 32.0); // never below the void kill height (the drone is reset at -16 by the client)
            p.setPos(x, y, z);
            var fd = p.getFoodData();
            fd.setFoodLevel(f.food); fd.setSaturation(f.saturation); fd.setExhaustion(f.exhaustion);
            p.resetLastActionTime();
            p.setDeltaMovement(Vec3.ZERO);
            p.fallDistance = 0;
            p.noPhysics = true;
            p.setInvisible(true);
            p.clearFire();
            p.setAirSupply(p.getMaxAirSupply());
            lvl.getChunkSource().move(p);
            ensureBody(p, f, lvl);
        }
        BlockPos bp = BlockPos.containing(x, y, z);
        if (f.drone != null && (f.drone.isRemoved() || f.drone.level() != lvl)) f.drone = null;
        if (f.drone == null && lvl.hasChunkAt(bp)) {
            DroneEntity d = ModEntities.DRONE.create(lvl);
            if (d != null) {
                d.getEntityData().set(DroneEntity.OWNER, Optional.of(p.getUUID()));
                d.setPos(x, y, z);
                lvl.addFreshEntity(d);
                f.drone = d;
            }
        }
        if (f.drone != null) {
            DroneEntity d = f.drone;
            d.setPos(x, y, z);
            if (extrapolate && ++f.lureTicks % 10 == 0) lureMobs(lvl, d);
            var ed = d.getEntityData();
            ed.set(DroneEntity.QUAT, new Quaternionf(m.qx(), m.qy(), m.qz(), m.qw()).normalize());
            ed.set(DroneEntity.RPM, m.rpm());
            ed.set(DroneEntity.FLAGS, flags(m.armed(), f.active));
        }
    }

    /** Hostile mobs that can see the drone target it (when it is nearer than their current target) (the hidden pilot is never a target). A mob that reaches it destroys it (creepers, zombies ...). */
    private static void lureMobs(ServerLevel lvl, DroneEntity d) {
        for (Mob m : lvl.getEntitiesOfClass(Mob.class, d.getBoundingBox().inflate(20, 10, 20), mm -> mm instanceof Enemy && mm.isAlive() && mm.getTarget() != d)) {
            double range = m.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
            var cur = m.getTarget(); // a mob goes for the drone when it has no target or the drone is nearer than what it is chasing
            if (m.distanceToSqr(d) < range * range && (cur == null || m.distanceToSqr(d) < m.distanceToSqr(cur)) && m.getSensing().hasLineOfSight(d)) m.setTarget(d);
        }
    }

    private static void ensureBody(ServerPlayer p, Flight f, ServerLevel lvl) {
        Home h = f.home;
        if (h == null) return;
        if (f.body != null && f.body.isRemoved()) f.body = null;
        if (f.body != null) {
            if (f.body.distanceToSqr(h.x, h.y, h.z) > 0.0025) f.body.moveTo(h.x, h.y, h.z, h.yaw, h.pitch); // pistons, water ...: it always stands at the launch spot
            return;
        }
        ServerLevel hl = p.server.getLevel(h.dim);
        if (hl == null || !hl.hasChunkAt(BlockPos.containing(h.x, h.y, h.z))) return;
        PilotBodyEntity b = ModEntities.PILOT.create(hl);
        if (b == null) return;
        b.getEntityData().set(PilotBodyEntity.OWNER, Optional.of(p.getUUID()));
        b.moveTo(h.x, h.y, h.z, h.yaw, h.pitch);
        b.yBodyRot = h.yaw; b.yBodyRotO = h.yaw; b.yHeadRot = h.yaw; b.yHeadRotO = h.yaw;
        b.setCustomName(p.getName());
        b.setCustomNameVisible(true);
        hl.addFreshEntity(b);
        f.body = b;
    }

    // ---- persistence of the launch spot in the player data ---------------------------------------------------------------------------------

    public static void save(ServerPlayer p, CompoundTag t) {
        Flight f = FLIGHTS.get(p.getUUID());
        if (f == null || !f.active || f.home == null) return;
        CompoundTag c = new CompoundTag();
        c.putString("dim", f.home.dim.location().toString());
        c.putDouble("x", f.home.x); c.putDouble("y", f.home.y); c.putDouble("z", f.home.z);
        c.putFloat("yaw", f.home.yaw); c.putFloat("pitch", f.home.pitch); c.putBoolean("nograv", f.hadNoGravity);
        t.put("fpvcore_home", c);
    }

    public static void load(ServerPlayer p, CompoundTag t) {
        if (!t.contains("fpvcore_home")) return;
        CompoundTag c = t.getCompound("fpvcore_home");
        Home h = new Home();
        ResourceLocation rl = ResourceLocation.tryParse(c.getString("dim"));
        if (rl == null) return;
        h.dim = ResourceKey.create(Registries.DIMENSION, rl);
        h.x = c.getDouble("x"); h.y = c.getDouble("y"); h.z = c.getDouble("z"); h.yaw = c.getFloat("yaw"); h.pitch = c.getFloat("pitch"); h.noGravity = c.getBoolean("nograv");
        RECOVERED.put(p.getUUID(), h);
    }

    private static void recover(ServerPlayer p) {
        Home h = RECOVERED.remove(p.getUUID());
        if (h == null) return;
        resetFlags(p, h.noGravity);
        p.resetFallDistance();
        ServerLevel hl = p.server.getLevel(h.dim);
        if (hl != null) { Vec3 spot = safeSpot(p, hl, h); p.teleportTo(hl, spot.x, spot.y, spot.z, h.yaw, h.pitch); }
        p.sendSystemMessage(Component.literal("[fpv] flight interrupted by a disconnect/crash: you were returned to your launch spot"));
        FpvMod.LOG.info("recovered pilot {} to launch spot", p.getGameProfile().getName());
    }
}
