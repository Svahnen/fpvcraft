# Minecraft adapter (Fabric) - `adapters/minecraft-fabric/`

A Fabric mod (client + server, install it on both for multiplayer) that flies an FPV drone in Minecraft with the unmodified fpvcore physics and a real RC controller.
Chunks load around the drone (fly arbitrarily far), other players see the drone and the pilot.

* Minecraft **1.21.1**, Fabric Loader >= 0.16, Fabric API 0.116.x , Java 21, Loom 1.11, Mojang mappings.
* Mod id `fpvcore`, environment `*`: a common entrypoint (`FpvMod`: entities, payloads, server flight manager `Flights`) plus the client entrypoint. Singleplayer uses the integrated server, so it is the same code path. On a server **without** the mod the client falls back to the old client-only mode (see Multiplayer).

## Architecture

```
GLFW joystick / keyboard --> Controller --> Mapping (calibration) --> fpv_input
GameRenderer.renderLevel HEAD (mixin) --> Session.frame():   once per rendered frame, variable dt (variable frame dt)
    traces (ground 70 cm / turtle 14.5 cm) --> fpv_set_trace_results
    fpv_step(dt)
    Collider.resolve: swept centre ray + sphere-vs-block-shape overlap --> fpv_add_contact (normal, depth)
    fpv_get_state_yup  --> camera pose (cam_pos / cam_forward / cam_up incl. uptilt + spring) --> Camera mixin
```

* **Single source of truth**: all physics is `libfpvcore` (C++). Java only binds the C ABI through **JNA** (`FpvNative.java`; JNA 5.14 already ships
  with Minecraft, so there is no JNI glue and no extra dependency). Java 21 has no final FFM API, hence JNA; porting to FFM on Java 22+ is mechanical.
* **Units**: 1 block = 1 m = 100 native cm, Y-up right-handed = Minecraft world (`fpv_get_state_yup`, `fpv_pos_yup_to_native`, ...). The yup *body* frame is +X nose, +Y up, +Z right.
  `Drone.reset(x,y,z,mcYaw)` converts Minecraft yaw to the native heading (`yaw - 180`).
* **Collisions** (rigid body since the friction rework): the drone is a box hull (8 corner points, small spheres r = 1.2 cm, `Collider.POINT_R`) handled by fpvcore (`fpv_add_contact_ex`: friction, restitution, torque, rest/sleep, see docs/FLIGHT_MODEL.md, ground contact); the block's slipperiness scales the friction (ice slides). Per frame: a ray (swept centre, radius `Collider.SWEEP_R` = 4 cm) from the old to the new centre position (extended by r) against `COLLIDER` shapes catches
  tunnelling at speed; then up to 6 passes of every hull corner against the `level.getBlockCollisions` boxes (closest point, deepest per corner). Each contact goes to
  `fpv_add_contact_ex` (contact point, normal, depth, block friction; restitution 0.1, hit event/spin on hard impacts). Impulse > 50 plays the bundled crash / prop-strike sounds. Entities and unloaded chunks are ignored.
* **Camera**: `CameraMixin` overwrites position/rotation (full quaternion, so roll works), `GameRendererMixin` sets the FOV (configured *horizontal* FOV, default 120 deg,
  converted to MC's vertical FOV by window aspect), hides the hand and view-bobbing. `KeyboardInputMixin` / `MouseHandlerMixin` freeze the player while flying.
* **Render**: the drone is a set of coloured cuboids drawn with `DebugRenderer.renderFilledBox` after entities (visible when landed, in chase cam, or in FPV when the
  camera is elsewhere). The drone model is original: hand-coded cuboids, no mesh or texture files.
* **OSD** (HUD callback): throttle % + bar, battery V (cell voltage turns red below 3.4 V), mAh, speed km/h, Y, ARMED/DISARMED, flight mode, stick display, input device.
  Battery sag is off by default (the library default); enable with `/fpv battery on`.

## Multiplayer / chunk following (design)

Physics stays **client-authoritative** (fpvcore runs on the pilot's client once per rendered frame). The client sends a C2S `PosePayload` (`fpvcore:pose`: flying flag, armed,
position, velocity, quaternion, rpm) at about 30 Hz while flying (4 Hz while the drone is parked). The server (`Flights`) does the rest:

* **Chunks follow the drone**: while `flying`, the pilot's `ServerPlayer` is moved to the (velocity-extrapolated) drone position every server tick
  (`setPos` + `ServerChunkCache.move`), so vanilla chunk loading/sending/tracking, `ClientboundSetChunkCacheCenter` and the client chunk window follow it. No custom chunk code.
  The player is invisible, has no gravity/`noPhysics`, takes no damage (`ALLOW_DAMAGE`, except /kill and void), air/fire are reset, and client movement packets are ignored
  (`ServerGamePacketListenerImplMixin`: no "moved too quickly/wrongly", no fly-kick counters). Alternative evaluated and rejected: server chunk tickets + custom chunk sending
  (needs a parallel chunk-sender and client-side centre handling; the player-move approach is ~10 lines and keeps simulation/entity tracking right too).
* **Pilot body**: a `PilotBodyEntity` (player model with the pilot's skin and name tag, never saved) stands at the launch spot. Hitting it ends the flight, returns the pilot
  and passes the damage on to the pilot. The launch spot is stored in the player data (`fpvcore_home`, `ServerPlayerMixin`) so a crash / kill / disconnect mid-flight returns the
  player to it at the next login. Flights also end (and the pilot is restored or released) on land, death, dimension change, disconnect and server stop.
* **Other players**: a `DroneEntity` (vanilla entity tracking, never saved) mirrors the drone. Position goes through normal tracking (update interval 1), orientation / rpm / armed
  go through synched data; clients interpolate between the last two ticks (slerp for the quaternion) and the server extrapolates with the reported velocity to hide the 20 Hz tick
  sampling. Props spin with rpm (2 blades per prop + a blur disc), the motor sound is a layered, rpm-driven set of loops that are generated by `tools/audio/synth_drone.py` and bundled in the jar (original CC0 sounds; an optional resource pack can override them, see docs/AUDIO.md), the pilot's name floats above the drone.
  Other pilots' drones are drawn `remoteDroneScale` (default 3x, config) larger since a real 5" quad is 0.25 m. The (invisible) player entity of a flying pilot is not rendered
  on other clients (`EntityRenderDispatcherMixin`, avoids floating armor / name tags).
* **Client**: while linked, the real client player travels with the drone (`Session.followPlayer`, every frame), exactly like the server-side player (invisible, no gravity, server owns the position); the launch spot is remembered in `Session` (`H`/reset use it)
  and the `PilotBodyEntity` stand-in (visible to everybody including the pilot) stays there; ending the flight teleports the player back to the body. Reason: vanilla builds the renderable section grid around the PLAYER
  (`LevelRenderer.setupRender` -> `ViewArea.repositionCamera(player)`), not around the camera, so with the local player frozen at home the chunks the server streamed around the drone were received (`ClientChunkCache` centre did follow) but never drawn
  (sky only, see `docs/img/minecraft_chunks_before_no_terrain.png` vs `minecraft_chunks_after_terrain.png`). Existing mods were checked for alternatives: freecam-style mods are client-only and cannot get chunks the server does not send; vanilla
  spectator `setCamera` works because the server moves the player to the camera entity, which is what we do on both sides. Without the server mod (client-only fallback) the local player is NOT moved (no server consent) and range stays limited.
  Block interaction/attacks are disabled while flying (`MinecraftMixin`). Collision uses the client world; if the chunk at or ahead of the drone (0.25 s of velocity) is not loaded the
  simulation is paused and the OSD shows "WAITING FOR CHUNKS". The pilot's own `DroneEntity` is not drawn (the local sim draws it).
* **S2C `EndPayload`**: server-initiated end of flight (body hit, death, dimension change).
* **S2C `LinkPayload`**: the server acknowledges the flight start. The OSD (top left) shows `LINKED` (green) or `CLIENT-ONLY: range limited` / `LINKING...` (orange); the log has `server sync: available/unavailable, <reason>` (client) and `flight start/end: <player>` (server). While flying the whole vanilla HUD (hearts, hunger, hotbar, XP, effects, boss bars, vignette, crosshair) is hidden; only the OSD, chat, F3 and the tab list stay.
* **Fallback**: `ClientPlayNetworking.canSend(pose)` is false on a server without the mod: the client stays in the old client-only mode (range limited to loaded chunks, nobody else
  sees it), chats a note once and shows an on-screen warning while flying.
* Trust model: the server accepts the pilot's pose as is (position sanity-checked only for finite values / world size), like a creative-mode client. Do not use on servers with untrusted players.

## Multiplayer hardening (2026-10-09)

* **No kicks**: while flying, `handleMovePlayer` is ignored (no "moved too quickly/wrongly", no fly kick: `clientIsFloating`/`aboveGroundTickCount` are reset every tick, so `allow-flight=false` works) and every pose resets the idle timer (`player-idle-timeout`). Poses arriving during server shutdown, or after the server ended a flight (refused / destroyed / body hit: `Flight.ended`), cannot restart the flight. A refused start (dead, spectator, sleeping, riding) tells the client (`EndPayload`). An external teleport (`/tp`, plugin) ends the flight where the player was sent (mixin on `ServerGamePacketListenerImpl.teleport`); a dimension change ends it too. The disconnect handler runs on the server thread (`executeBlocking`).
* **The hidden player cannot be hurt** (`ALLOW_DAMAGE` blocks everything except `/kill`, including void/suffocation/drowning/fire/explosions/mobs; y is clamped above the void kill height, food/saturation are restored every tick, air/fire reset, fall distance 0). It is never "seen" by mobs (`LivingEntity.canBeSeenByAnyone`), not pickable, picks up no items/XP (`Player.touch`, `ExperienceOrb`), and ignores plates/tripwires (`Entity.isIgnoringBlockTriggers`). It is not persisted as flying: the launch spot, gravity flag are in the player data (`fpvcore_home`), a crash mid-flight returns the player on the next login.
* **Safe return**: the player goes back to the launch spot unless it became unsafe (blocks, lava, fire, cactus, magma, cobweb, berries, powder snow, outside the border); then the nearest safe spot (radius 8, +-6 y), else the world spawn column.
* **The drone is a world object** (`DroneEntity` is a `LivingEntity` with a 0.3x0.2 m box centred on the simulated position): hostile mobs that can see it target it when it is nearer than their current target (`Flights.lureMobs`); mob attacks, explosions, fire and lava (`checkInsideBlocks` every tick) destroy it, which ends the flight like G (pilot back at the body, unharmed, chat + action bar "Drone destroyed: mob/explosion/lava/fire"); players can not hit it; it triggers pressure plates and tripwires; water does not hurt it (client: 4x drag while the drone is in water, splash particles/sound). The body ignores non-attack damage (drowning, in-wall, fall, cramming) and does not get pushed by fluids.
* **Dev QA harness** (`-Dfpvcore.autotest=mp`, `MpQa.java`, `scripts/mpqa/`): dedicated server `run-qa` (offline, `allow-flight=false`, idle timeout 2 min, rcon) plus PilotA / PilotB / Watcher headless clients (`up.sh`, each with a private copy of the native lib), `matrix.py [soak_seconds] [section ...]` drives them through rcon and per-client command files and asserts on the server log (`-Dfpvcore.qalog` adds `QA flight ...` samples with hp, food, flags, max speed, blocked damage) and the clients' `MPQA` lines.

### Multiplayer test matrix

Last run: `matrix.py 90` on the dedicated server, three clients, MC 1.21.1: **36/36 PASS, twice in a row** (a 150 s soak passed earlier). Screenshots from the Watcher: `docs/img/minecraft_mp_qa_A_body.png` (PilotA's body, name tag), `minecraft_mp_qa_A_drone.png`, `minecraft_mp_qa_B_drone.png`.

| Area | Check | Result |
|---|---|---|
| 1 No kicks | 90-150 s of race/dive flight by two pilots at once, `allow-flight=false`, idle timeout 2 min, top speed 45-49 m/s: nobody kicked, no "moved too quickly/wrongly", no "flying", no "Invalid move" | PASS |
| 1 No kicks | landing / ending a flight: no "moved too quickly", no client disconnect | PASS |
| 1 No kicks | server `stop` mid-flight: pilots restored at the body, no restart of the flight by late poses; rejoin at the body | PASS (manual) |
| 1 No kicks | server killed mid-flight (`-Dfpvcore.crashtest`): next login "recovered pilot to launch spot" | PASS (manual) |
| 2 Immunity | hp stays 20, food unchanged for the whole flight; flags invisible / ignoresTriggers / unseen / unpickable | PASS |
| 2 Immunity | flight ended mid-air by the pilot: no fall damage, back at the body; suffocation, explosion and other damage events are blocked (counted in the log) | PASS |
| 2 Immunity | no item / XP pickup while hovering on top of them | PASS |
| 2 Mobs/world | zombie nearer to the drone than other players targets it and kills it: flight ends, pilot unharmed at the body | PASS |
| 2 Mobs/world | TNT / lava / fire destroy the drone: flight ends, chat reason on the client | PASS |
| 2 Mobs/world | another player hitting the drone does nothing | PASS |
| 2 Water | drone in water: not destroyed, pilot unharmed, air full, client reports water (4x drag; sim top speed ratio 0.49) | PASS |
| 2 Plates | pressure plate pressed by the drone resting on it, released when it leaves; tripwire the same | PASS |
| 3 Body | Watcher sees body + drone of both pilots, drones and bodies vanish on landing and on disconnect, no duplicate body on rejoin | PASS |
| 3 Body | hitting the body ends the flight once and passes the damage; pilot back at the body | PASS |
| 3 Return | home became unsafe (stone + lava placed): pilot put on the nearest safe spot, unharmed | PASS |
| 4 Drones | Watcher sees both drones moving (up to 171 m away at view-distance 10), spinning props, positional motor audio started (`drone audio` log) | PASS |
| 5 Edge | `/tp` of a flying player; dimension change (Nether) mid-flight; start while riding / sleeping / spectator is refused with a message; two pilots from the same spot (creative + adventure + survival); two pilots 1.4 km apart at once | PASS |
| 5 Edge | disconnect mid-flight: flight ended, body and drone removed for everybody, rejoin at the body | PASS |

Known gaps: the drone audio positional quality and smoothness were checked by log/screenshots only; vehicle riders are refused instead of handled; mobs only chase the drone, they do not path to unreachable heights; QA clients can SIGBUS if `build-native/` is rebuilt while they run (use the private lib copy, done by `client.sh`).

## Controls

### Controller setup

The mod reads raw GLFW joystick axes/buttons (never the gamepad mapping). Every joystick GLFW reports is listed (`K` screen or `/fpv controller list`); only touchpad/mouse/keyboard-like
names and Steam virtual pads are flagged and never auto-selected. If none is configured, the first real device (>= 4 axes preferred) is selected automatically and a toast + chat line
"Controller connected: <name>" appears (also on hot-plug and unplug; polled every client tick). The selection and one mapping per device (by GUID) are saved in `config/fpvcore.json`.

* Radio: EdgeTX/OpenTX USB mode **Joystick (HID)**. The OSD (top right) shows the active controller, or "Keyboard (press K to set up a controller)".
* `K` / `/fpv calibrate`: device list (click or Up/Down), live raw bars for ALL axes (labelled with the function assigned to each, e.g. `A2 THR`) + button squares, then **Start wizard** or **Edit mapping / test**.
  Wizard: 1. **stick mode** (Mode 1-4, default Mode 2 = left stick throttle + yaw, right stick pitch + roll; saved as `stickMode`, only used for prompts and the test view), 2. extremes (move every stick and switch everywhere:
  min/max recorded for ALL axes, and kept growing in later steps), 3. centre (sticks released, throttle down), 4-7. throttle / yaw / pitch / roll with prompts naming the physical stick for the chosen mode
  ("Move the LEFT stick fully LEFT and RIGHT a few times (Yaw), ending fully RIGHT"), 8. arm switch (axis or button), 9. mode switch (optional), 10. review.
  Axis detection per step: the unassigned axis with the largest range (max - min) over the step window wins and must exceed 0.5 (a second axis that also moved is flagged); the screen shows `Detected: axis N (range, direction)`
  live and nothing advances until **Confirm** (or **Retry**). Direction comes from where the stick ended (sticks), from its rest end (throttle) or from the way the switch left its rest position.
  Throttle uses min/max only (no centre/deadzone).
* **Edit / review screen**: per function (Throttle/Yaw/Pitch/Roll/Arm/Mode) an axis selector (click cycles none, 0..N-1; Shift+click backwards; picking an axis another function uses swaps them) and an Invert toggle,
  presets **AETR (EdgeTX default)** (roll 0, pitch 1, throttle 2, yaw 3) and **TAER** (throttle 0, roll 1, pitch 2, yaw 3), a stick-mode cycle, and a **two-stick test view**: the left and right gimbal boxes with a dot showing the MAPPED
  result for the chosen mode (Mode 2: left dot = throttle up/down + yaw, right dot = pitch up/down + roll). Keys 1-5 / G (invert, gamepad throttle) and `-`/`=` (deadzone) still work. Old `fpvcore.json` mappings load unchanged (`stickMode` defaults to 2).
* `/fpv controller list | use <n> | keyboard`.
* Keyboard fallback and extras: `W/S` throttle, `A/D` yaw, arrows pitch/roll, `Z` arm, `X` mode. With a radio, Z/X still work for an arm/mode that is not mapped (Z also arms when the RC switch is off).
* Arming requires the throttle to be low. Maths is a port of `fpvinput` (`normalizeCentered`/`normalizeThrottle`).
* Steam Input: if Steam is running it can turn the radio into mouse/keyboard on the desktop and create a "Steam Virtual Gamepad". Disable it for the radio (Steam > Settings > Controller,
  or the application's Properties > Controller > Disable Steam Input); the calibration screen shows this hint when a Steam virtual pad is listed or no real controller is found.
* Dev testing without a radio: the autotest mode
  `-Dfpvcore.autotest=cmd -Dfpvcore.cmdfile=<f>` executes one command per file (screen, wizard, edit, next, back, retry, mode N, preset AETR|TAER, assign <fn> <axis>, map, key, shot, log ...), inside a headless compositor.

### Flight lifecycle

`G` enters FPV; `G` again (or `/fpv land`, `J` / `/fpv return` while flying) ends the flight: the drone is despawned (client sim + server entity, via a `gone` pose) and the server restores
the pilot and removes the pilot body. A crash/disarm keeps you in FPV (OSD "CRASHED": flip ARM off/on, `H` resets the drone to the launch spot, `G` exits). The vanilla crosshair/hotbar are hidden while flying.

Default keybinds (category "FPV Drone", rebindable): `G` toggle FPV flight (spawns the drone if none; leaving despawns it), `H` place/reset drone in front of the player, `J` return drone to the player (ends the flight if flying),
`K` calibration, `,` next preset; while flying the vanilla Toggle Perspective key (F5 by default) cycles the camera FPV -> chase (behind) -> front (looking at the drone) -> FPV; the third-person cameras are pulled in so they stay out of blocks.

Commands: `/fpv spawn | launch | fly | land | return | chase (cycle the camera) | calibrate | info`, `/fpv preset <5race|5freestyle|3micro|7longrange>` (= 5in-race, 5in-freestyle, 3in-micro, 7in-longrange),
`/fpv rates type <0-4>` (Betaflight/Actual/Raceflight/Kiss/Quickrate), `/fpv rates set <rc> <srate> <expo>` (all axes), `/fpv rates throttle <mid> <expo>`, `/fpv rates default`,
`/fpv fov <deg>`, `/fpv uptilt <deg>`, `/fpv battery on|off`. Everything persists in `config/fpvcore.json`.

Typical session: `/fpv launch` (or `G`), flip the arm switch with throttle at the bottom, raise throttle (hover is about stick -0.5, i.e. just below centre).

## Build and run

Needs: podman (the Linux library is built in a manylinux_2_28 container, see docs/BUILDING.md) and a JDK 21 (a Gradle toolchain or `JAVA_HOME`).

```
cd adapters/minecraft-fabric
./scripts/build-native.sh                 # builds libfpvcore.so (static libstdc++) in a manylinux container and stages build-native/natives/linux-x86_64/
JAVA_HOME=/path/to/jdk-21 ./gradlew build      # -> build/libs/fpvcraft-0.1.0.jar (native lib bundled)
JAVA_HOME=... ./gradlew runClient         # dev client (uses -Dfpvcore.native=<staged .so>)
```

Install: copy `build/libs/fpvcraft-0.1.0.jar` (not the `-sources` jar) to the `mods/` folder of a Fabric 1.21.1 profile together with Fabric API. At runtime the lib is
extracted to a temp dir and loaded; `-Dfpvcore.native=/path/to/lib` overrides.

### Windows `.dll` (cross-build with mingw, verified)

```
# needs mingw-w64 g++ and cmake (e.g. `dnf install mingw64-gcc-c++ cmake`); set FPV_BUILD_BOX=<distrobox name> to run this step inside a container
./scripts/build-native.sh windows         # cross-compiles with x86_64-w64-mingw32-g++ (static runtime), stages natives/windows-x86_64/fpvcore.dll
```
`fpvcore.h` exports with `__declspec(dllexport)` (`FPV_SHARED` is set by CMake for the shared target, `FPV_BUILDING` privately), so no extra flags are needed. Verified:
29 `fpv_*` exports (`x86_64-w64-mingw32-objdump -p`), only KERNEL32/msvcrt imports, and a tiny test exe run under wine (`LoadLibrary`, `fpv_builtin_preset("5race")` contains `drag_tables`, `fpv_create` returns a drone).
`FpvNative` picks `/natives/<os>-<arch>/` from `os.name`/`os.arch` (`windows-x86_64/fpvcore.dll`, `linux-x86_64/libfpvcore.so`); the release jar contains both. macOS lookups are wired but untested.

## Installing (players)

Release jar: `dist/fpvcraft-0.1.0.jar` (one jar for Windows and Linux, x86_64). Requirements: Minecraft **1.21.1**, Fabric Loader >= 0.16, **Fabric API 0.116.x**, Java 21 (the launcher supplies it).

* **Official launcher (Windows/Linux)**: Installations > New, version `fabric-loader-<ver>-1.21.1` (install Fabric first with the Fabric installer, https://fabricmc.net/use/installer/). Put `fpvcraft-0.1.0.jar` and `fabric-api-0.116.x+1.21.1.jar` in `.minecraft/mods/` (Windows `%APPDATA%\.minecraft\mods`, Linux `~/.minecraft/mods`).
* **Prism Launcher**: Add Instance > Minecraft 1.21.1 > Loader: Fabric. Edit instance > Mods > Download mods (Fabric API) / Add file (the FPVcraft jar).
* **X Minecraft Launcher (XMCL)**: Add instance > Minecraft 1.21.1 + Fabric. In the instance Mods tab drop in Fabric API and the FPVcraft jar (or copy them to `<root>/instances/<name>/mods/`; the data root is shown in Settings, e.g. `~/.minecraftx`; the Flatpak keeps its config in `~/.var/app/app.xmcl.voxelum`). Launch.
* Troubleshooting (Flatpak XMCL hangs at "Backend library: LWJGL version ..."): the Flatpak only has `sockets=fallback-x11`, so inside the sandbox `DISPLAY` is unset when Wayland is available; Minecraft's bundled GLFW is X11-only, `glfwCreateWindow` fails, and the error dialog (`tinyfd_messageBox`) blocks forever (the Main thread sits in `class_1041.<init>`). Not caused by the mod. Fix: `flatpak override --user --socket=x11 app.xmcl.voxelum`, then restart XMCL (the game runs through XWayland). Check with `flatpak run --command=sh app.xmcl.voxelum -c 'echo $DISPLAY'` (should print `:0`). Kill a hung game with `kill` on the java process.
* Linux note: the lib is extracted to a temp dir and loaded by JNA; no system packages are needed (libstdc++ is static). Plug in the RC transmitter (USB joystick mode) before starting, then `K` to calibrate.
* In game: create/open a world, `/fpv launch`, arm, fly (see Controls).

## Known limitations / TODO

* Multiplayer needs the mod on the server and on every client that should see drones (new entity types are registry-synced; vanilla clients are not supported on a modded server). Without the mod on the server: client-only fallback with limited range.
* The pose is trusted (see Trust model). Mobs spawn around the drone (the chunks follow it), not the body. The hidden player is invisible, invulnerable, ignored by mobs / plates / pickups (see Multiplayer hardening); a flying pilot can still be killed with `/kill`.
* Other players see a drone only inside the server's view distance (entity tracking is clamped to it by vanilla: type range 16 chunks, effective `view-distance`; the QA run saw drones up to 171 m with view-distance 10). Longer would need a custom packet.
* No entity collisions; water only slows the drone (4x drag, about half the top speed; measured 0.49 in the sim) and fluid buoyancy is not simulated. Remote drones are interpolated over one server tick (50 ms) plus network jitter; fast manoeuvres of other pilots look slightly stepped.
* Slim-arm skins are supported for the pilot body; capes/elytra/armor are not rendered on it.
* Model is a simple cuboid drone (props: two blades + blur disc).
* Rates mapping/atmosphere guesses are inherited from fpvcore (see docs/LIBRARY.md, "Status").
* Joystick axis indices follow GLFW (Linux: evdev order), so a mapping saved from the sim_viewer (SDL) is not guaranteed to transfer; recalibrate in game.

## Verification (2026-10-09)

* `./gradlew build` produces the jar with the native lib; `./gradlew runClient` loads the mod (log: `fpvcore-fabric loading, native fpvcore 0.1.0`).
* Dev autotest (`./gradlew runClient -Dfpvcore.autotest=1`; `AutoTest.java`, dev only) creates a flat world, builds a stone wall at z=14, launches the drone, arms with scripted sticks,
  climbs, pitches forward, crashes into the ground and the wall, takes screenshots to `run/screenshots/` and logs states to `run/logs/latest.log`: armed at throttle low, climb to ~25 m,
  ground contacts (impulse 10-25), stop at the wall (z = 13.90 = 14 - radius 0.10).
* Joystick list shows every device; auto-select skips touchpads/Steam pads and prefers >= 4 axes. No real RC transmitter was attached during the test, so GLFW axis reading is untested on hardware
  (the calibration maths are the ported, unit-tested fpvinput functions).

## Verification (2026-10-09, rebuilt with drag tables + sag fix)

* Builtin presets in the bundled `libfpvcore.so` (and `fpvcore.dll`) contain `drag_tables` (all 4 presets).
* Dev autotest (`runClient -Dfpvcore.autotest=1`) and the same autotest in a production-style launch (remapped release jar + Fabric API in an XMCL-installed 1.21.1 Fabric instance `fpvcore-test`, started with the launcher's version libraries): armed, climbed, ground contacts, stopped at the wall (z = 13.90).
* Windows dll: loaded and exercised under wine; not yet tested in a real Windows Minecraft.

## Verification (2026-10-09, multiplayer + chunk following)

Dev tooling: `./gradlew runServer` (`run-server/`, offline mode, flat world), `runClient` / `runClient2` (`run2/`) with `-Dfpvcore.user=<name> -Dfpvcore.connect=localhost:25565 -Dfpvcore.autotest=<mode> -Dfpvcore.size=640x360`;
autotest modes: `far` (singleplayer), `pilot`, `watch`, `rejoin` (multiplayer). The clients were run headless (no window on the desktop).

* Singleplayer `far`: the drone flew in a straight line to 595 blocks (angle mode, altitude hold); the integrated server's player position equalled the drone position every second, the client chunk
  at the drone was always loaded (`waiting=false`), after landing the server put the player back at the launch spot (0.5, -60, 0.5) and the far chunks were forgotten.
* Multiplayer (dedicated server + `Watcher` + `Pilot`): the watcher's client got the `DroneEntity` (position, armed, rpm, `pilotFlying`) and the `PilotBodyEntity` at the launch spot;
  screenshots show the pilot body (skin, name tag) and the drone with name tag and spinning props. The pilot flew over 340 blocks away from the body on the server (chunks followed), then landed and was restored.
* Screenshots from the watcher client: `docs/img/minecraft_mp_watcher_200.png` (pilot body with skin + name tag, drone with name tag above it), `docs/img/minecraft_mp_watcher_330.png`.
* No-server-mod fallback (`-Dfpvcore.noservermod=1`, `far` test): the drone stops at 155 blocks (end of the loaded chunks), `WAITING FOR CHUNKS` is shown and the server player never moves.
* Landing/ending a flight no longer triggers vanilla's "moved too quickly" (`resetPosition()` after the restore teleport).
* Crash recovery: the server was halted (`Runtime.halt`) mid-flight right after `saveEverything`; on the next login the pilot was returned to the launch spot (`recovered pilot Pilot to launch spot`; saved at (0,-55,-2) = drone, rejoined at (0.5,-60,-4.5) = launch spot).
