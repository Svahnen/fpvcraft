# fpvcore library (C++17, C ABI)

`fpvcore` is a standalone, engine-agnostic C++17 library with a physically based FPV quadcopter flight model
(rigid body, motor/propeller thrust, battery, aerodynamic drag, Acro/Angle/3D modes and the usual rate profiles; see
`docs/FLIGHT_MODEL.md`), for embedding in other games. The first host is the Minecraft Fabric mod in
`adapters/minecraft-fabric` ("FPVcraft"). The library has a plain C ABI (`include/fpvcore.h`), no engine
dependency, and a single vendored header-only JSON library.

Licence: code is MIT (see `LICENSE`), the bundled sounds are CC0 (see `adapters/minecraft-fabric/src/main/resources/assets/fpvcore/CREDITS.txt`).

Parts:

| target | what | deps |
|---|---|---|
| `fpvcore` | flight model, rates, presets, C ABI | none (vendored `third_party/nlohmann/json.hpp`, MIT) |
| `fpvinput` (optional) | SDL2 joystick/RC-radio input, per-axis calibration (min/center/max), deadzone, inversion, axis mapping saved to JSON | SDL2 |
| `sim_viewer` (tool) | desktop harness: ground grid, pillars/gate, FPV camera with uptilt, OSD, keyboard or controller | SDL2, OpenGL |
| `fpvcore_tests`, `fpvinput_tests` | doctest unit tests (`third_party/doctest`, MIT) | |

Built-in presets (ids in the C API / `/fpv preset`):

| id | name | airframe |
|---|---|---|
| `5race` | 5in-race | 5" race quad, 2207 2000KV, 6S, 5.1x4.6 props, about 580 g |
| `5freestyle` | 5in-freestyle | 5" freestyle quad, 2207 1700KV, 6S, 5.1x4.3 props, about 680 g |
| `3micro` | 3in-micro | 3" micro quad, 1404 4200KV, 3S, 3x2.2 props, about 105 g |
| `7longrange` | 7in-longrange | 7" long-range quad, 2807 1400KV, 6S, 7x5 props, about 1.0 kg |

## Build

Requirements: a C++17 compiler, CMake >= 3.16, optionally SDL2 and OpenGL development packages (input module and viewer).
Full instructions (including the Linux manylinux and Windows mingw builds used for the mod): `docs/BUILDING.md`.

```
cmake -S . -B build && cmake --build build -j8 && ctest --test-dir build --output-on-failure
```

Options: `-DFPV_BUILD_INPUT=OFF` (no SDL2), `-DFPV_BUILD_VIEWER=OFF`, `-DFPV_BUILD_TESTS=OFF`,
`-DFPV_BUILD_SHARED=ON` (produces `libfpvcore.so` for JNI/FFI). The presets (`data/presets/curves.json`, `drone_*.json`,
`drag_*.json`) are embedded into the library at configure time.

Run the viewer (a desktop window opens):

```
./build/sim_viewer --preset 5race
```

Keys: `W/S` throttle (ramps like a stick), `A/D` yaw, arrows pitch/roll, `Space` arm (throttle must be low), `M` mode
(acro/angle/acro3d), `1-5` rate type, `R` reset, `Tab` chase cam, `+/-` camera uptilt, `C` controller calibration wizard
(ENTER confirms each step, instructions in the terminal), `F1-F4` invert throttle/roll/pitch/yaw, `F5` save mapping to
`~/.config/fpvcore/mapping.json`. With a transmitter in USB-joystick mode (EdgeTX/OpenTX) the first joystick is used
(default mapping AETR on axes 0-3, arm = axis 4, mode = axis 5; run the wizard once). `--demo 3 --shot out.ppm` runs a
scripted flight and writes a screenshot (for CI/smoke tests).

## Quick start

```c
#include "fpvcore.h"

fpv_drone* d = fpv_create(fpv_builtin_preset("5race"));      // or the text of any data/presets/drone_*.json
double home[3] = {0, 0, 0};                                    // cm
fpv_reset(d, home, 0.0);                                       // nose along world +X

for (;;) {                                                     // once per rendered frame, variable dt
    fpv_input in = { .throttle = -1, .yaw = 0, .pitch = 0, .roll = 0, .arm = 1, .mode = FPV_MODE_ACRO };
    fpv_set_input(d, &in);
    fpv_step(d, frame_dt);                                     // dt seconds

    fpv_state s; fpv_get_state_yup(d, &s);                     // metres, Y-up, right-handed
    // s.pos_cm[] is metres here (field names are kept); s.cam_pos_cm / s.cam_quat / s.cam_forward / s.cam_up: FPV camera

    // host world: report penetrations after stepping (normal points out of the surface, depth in cm)
    double n[3] = {0, 0, 1};
    if (hit) fpv_add_contact(d, n, depth_cm);
}
fpv_destroy(d);
```

Hover is at about stick -0.5 (throttle -1 = idle, +1 = full; the 5" race preset has a thrust-to-weight ratio of roughly 12 at full throttle).

## Coordinate conventions

* Internal ("native" frame): centimetres, kilograms, degrees, seconds; left-handed, Z up; forces in kg*cm/s^2 (= 0.01 N).
  World +X forward, +Y right, +Z up. Drone body frame: **+Y = nose, +X = left, +Z = up**; the quaternion maps body -> world.
* Y-up helpers (`fpv_*_native_to_yup`, `fpv_get_state_yup`): metres, right-handed, `x_yup = y_ue`, `y_yup = z_ue`, `z_yup = -x_ue`.
  native forward therefore becomes -Z (OpenGL "forward"; Minecraft north). Minecraft: 1 block = 1 m, +X east, +Y up, +Z south, so
  the yup frame is directly Minecraft's. Quaternions need the handedness flip (`q' = (-M q.xyz, w)`), which the helpers do.
* Time: `fpv_step` is called once per rendered frame with the variable frame `dt` (clamped to 1/30 s by default,
  `FPV_PARAM_MAX_DT`). The cosmetic attitude spring (camera lag and prop-wash shake) uses the exact 1/120 s substep
  via an internal accumulator. If you need frame-rate independence, call `fpv_step` at a fixed rate yourself.

## Integration guide for host games

1. Create one `fpv_drone` per player drone; call `fpv_set_input` with calibrated sticks (-1..1) each frame, `fpv_step(dt)`, then
   read `fpv_get_state_yup` to place your entity/camera. Use the camera pose (`cam_*`): it already includes the FPV uptilt
   (`camera_tilt_deg`, default 25 deg, `FPV_PARAM_CAM_UPTILT_DEG`) and the cosmetic spring when enabled.
2. The host owns the world. The model applies gravity, thrust, drag, ground effect itself, but moves freely. After `fpv_step`
   test the drone (a sphere of about 10 cm radius, or a box sized to the prop size) against your blocks and call
   `fpv_add_contact(normal, depth_cm)` for each penetration; the library pushes the drone out, applies restitution (0.1) and
   friction, and triggers the hit event (random spin on impacts above 800 impulse). If you resolve collisions yourself,
   write back with `fpv_set_state` and call `fpv_report_hit(normal, impulse)`.
3. Ground effect (70 cm trace below the drone) and turtle-mode recovery (14.5 cm trace above) need ray queries: register
   `fpv_set_raycast(cb, user)` or (simpler for JNI) call `fpv_set_trace_results(down_cm, up_cm)` before each step (negative = no hit).
4. Wind: `fpv_set_wind` (cm/s world). Gravity: preset value, override with `FPV_PARAM_GRAVITY`.
5. Minecraft: JNI wrapper over the C ABI (build with `-DFPV_BUILD_SHARED=ON`; everything uses plain doubles/ints/structs of doubles),
   or port `src/fpvcore/` to Java (the model is ~600 lines, no dynamic dispatch, deterministic xorshift PRNG for crash spin).
6. Inputs: use `fpvinput` (C++ API `include/fpvinput.h`) for raw SDL joysticks, or feed `fpv_input` from your own device layer.

Full function reference: `docs/API.md`. Model description: `docs/FLIGHT_MODEL.md`. Audio synthesis: `docs/AUDIO.md`.

## Status

* Implemented: rigid-body quadcopter physics (rpm/thrust/drag/gravity/ground effect/turtle/rotation lift/battery sag), ACRO, ANGLE, ACRO_3D, cosmetic spring camera,
  all five rate types (Betaflight, Actual, Raceflight, Kiss, Quickrate), throttle mid/expo, arming, collision hit events, rigid-body ground contact, baked per-direction aero tables for the four presets (`tools/bake/bake_drag_generic.py`).
* Not implemented: GPS-stabilised mode, prop damage accumulation (damage state can be set, thrust loss applies).
* Preset values are independent estimates of real hardware classes and were tuned for plausible hover throttle, top speed, terminal velocity and acceleration.
