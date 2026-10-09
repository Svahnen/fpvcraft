# fpvcore C API (`include/fpvcore.h`)

All functions are plain C; types are doubles/ints. One `fpv_drone*` must not be used from two threads at once. Errors:
creation functions return NULL and `fpv_last_error()` (thread-local) holds the message.

## Units and frames
Default getters/setters use the native units: cm, cm/s, deg, deg/s, kg; left-handed Z-up; body frame +Y nose / +X left / +Z up; quaternions
are (x, y, z, w) body -> world. `fpv_get_state_yup` returns the same struct in metres, Y-up right-handed (`x_yup = y_ue`,
`y_yup = z_ue`, `z_yup = -x_ue`); field names keep the `_cm`/`_cms` suffix but values are m and m/s. Converters:
`fpv_pos_native_to_yup` (also velocities), `fpv_pos_yup_to_native`, `fpv_dir_*` (no scaling), `fpv_quat_*` (handedness-aware),
`fpv_angvel_native_to_yup` (axial vector; deg/s unchanged).

## Lifecycle
| function | notes |
|---|---|
| `const char* fpv_version()` | |
| `fpv_drone* fpv_create(const char* preset_json)` | preset = `data/presets/drone_*.json` text. Needs an `airframe` object (keys: `motor_kv`, `battery_cells`, `prop_pitch_in`, `mass_kg`, `gravity`, `linear_damping`, `camera_tilt_deg`, `lateral_grip`, `air_speed_scale`, `throttle_min`/`throttle_low`/`throttle_mid`/`throttle_high`, `prop_diameter_in`, `power_response`, `pid_wobble_mode`/`pid_wobble_amount`/`propwash_amount`, `drag_coeff_front`/`_top`/`_side`/`_diag`; units in docs/FLIGHT_MODEL.md); optional `battery`. Curves are built in. |
| `fpv_create_ex(preset_json, curves_json)` | custom curves document (same schema as `data/presets/curves.json`); NULL = built-in |
| `const char* fpv_builtin_preset(id)` | `3micro`, `5race`, `5freestyle`, `7longrange`; static JSON text |
| `fpv_destroy`, `fpv_last_error` | |

## Control
* `fpv_set_input(d, const fpv_input*)`: `throttle` (-1 idle .. +1 full), `yaw`, `pitch`, `roll` (-1..1, post calibration), `arm` (switch),
  `mode` (`FPV_MODE_ACRO|ANGLE|ACRO3D|HORIZON`). Arming: switch on and throttle < -0.5; switch off disarms. Before the first arm the drone is
  frozen (physics starts on the first arm; `FPV_PARAM_FREEZE_UNTIL_ARMED=0` removes this).
  Positive pitch = nose down, positive roll = bank right, positive yaw = turn right.
* `fpv_set_rates(d, const fpv_rates*)` / `fpv_get_rates`: `type` (`FPV_RATES_*`), per-axis `rc/srate/expo` (order roll, pitch, yaw), `thr_mid`, `thr_expo`.
  Default profile: Betaflight, rc 1.0, srate 0.7, expo 0 (max 666.7 deg/s), thr_mid 0.5. Parameter meaning per type (guess, see `rates.cpp`):
  Betaflight/Kiss rc/srate/expo as fractions; Actual rc = centre deg/s, srate = max deg/s, expo 0..1; Raceflight rc in "RF rcRate" units, srate/expo as fractions;
  Quickrate rc fraction (centre = 200*rc), srate = max deg/s.
* `fpv_step(d, dt)`: one frame tick; flight model order = input pipeline, rates, arming, attitude (kinematic rotation from the rate setpoint in ACRO; PD attitude torque in ANGLE),
  RPM/thrust/drag/gravity forces, battery, semi-implicit Euler integration (rigid-body), residual angular velocity damping (6.5 /s), 120 Hz spring substeps. dt is clamped to `[0, max_dt]`.

## State
`fpv_get_state` / `fpv_get_state_yup` fill `fpv_state`: pose (`pos_cm`, `quat`), `linvel_cms`, `angvel_degs` (residual, from collisions/angle-mode torque; stick rotation is kinematic),
`rpm[4]` (one RPM for all motors), `battery_v` (pack), `cell_v`, `amps`, `mah_used`, `thrust`, `rate_setpoint_degs` (roll, pitch, yaw), `armed`, `can_fly`, `mode`, `time`,
`pid_quat` (cosmetic attitude spring, relative to the body), the **camera** (`cam_pos_cm`, `cam_quat`, `cam_forward`, `cam_up`, `cam_uptilt_deg`, `cam_fov_deg`) and prop damage.
The camera sits at a body-local offset (default 0, 6, 2.5 cm; guess), is tilted up by `camera_tilt_deg` about the lateral axis, and follows the spring attitude when `FPV_PARAM_PID_CAMERA=1`.

`fpv_set_state` overwrites pose and velocities (battery etc. unchanged). `fpv_reset(d, pos_cm, heading_deg)` re-initialises everything (heading 0 = world +X, positive toward +Y), disarmed.

## Environment and collisions
* `fpv_set_raycast(d, fn, user)`: `int fn(void* user, const double origin_cm[3], const double dir[3], double max_dist_cm, double* dist_cm)`, return 1 on hit.
  Called for the ground-effect trace (70 cm along -up) and the turtle trace (14.5 cm along +up). Native-frame coordinates (cm).
* `fpv_set_trace_results(d, down_hit_cm, up_hit_cm)`: injected alternative (negative = no hit), persists until changed; ignored while a raycast callback is set.
* `fpv_add_contact(d, normal[3], depth_cm)`: world-space normal out of the surface; position correction, velocity response (restitution `FPV_PARAM_RESTITUTION`, friction `FPV_PARAM_FRICTION`),
  hit event. Returns the normal impulse in kg*cm/s (> 800 triggers spin/zero angular damping for 0.15 s; > 50 is treated as audible by the Minecraft adapter).
* `fpv_add_contact_ex(d, point[3], normal[3], depth_cm, friction, restitution)`: rigid-body point contact (world point; friction/restitution < 0 = params). Coulomb friction against the point's tangential velocity, restitution above the bounce threshold, contact torque; contacts of one frame are solved together. Call it for every hull point that penetrates, after `fpv_step`.
  `fpv_get_contact_points(d, out, max)`: the 8 body-local hull corners (native body frame, cm). `fpv_is_resting(d)`: 1 when the disarmed drone lies still on a surface (velocities are then held at 0). Params `FPV_PARAM_BOUNCE_THRESHOLD`, `FPV_PARAM_REST_TIME`; `FPV_PARAM_FRICTION` now defaults to 0.7. See docs/FLIGHT_MODEL.md (ground contact).
* `fpv_report_hit(d, normal[3], impulse)`: only the hit event (host resolved physics itself).
* `fpv_set_wind(d, wind_cms[3])`.
* `fpv_set_drag_tables(d, n, dirs, area_cm2, force_dirs)`: area-based drag (directional drag model). `fpv_builtin_preset(id)` already carries the baked tables (`"drag_tables"` in the preset JSON, loaded by `fpv_create`); `n = 0` switches back to the table-free isotropic drag. Tables are interpolated with inverse-distance weights over the three nearest samples (`nearestInterp` in `model.cpp`).

## Parameters (`fpv_set_param`)
`FPV_PARAM_MAX_DT` (1/30), `RESTITUTION` (0.1), `FRICTION` (0), `CAM_UPTILT_DEG`, `CAM_FOV_DEG`, `SIMULATE_BATTERY` (0), `FREEZE_UNTIL_ARMED` (1), `GRAVITY`,
`PID_CAMERA` (1), `PHYSICS_ACTIVE`, `SEED` (PRNG seed for crash spin; the model is otherwise deterministic: identical inputs and dt sequence give bit-identical state).

## Rates
`double fpv_rate(type, stick, rc, srate, expo)`: stick -> deg/s, usable without a drone. Implementations in `src/fpvcore/rates.cpp`:
`rateBetaflight_bf`, `rateActual_bf`, `rateRaceflight_bf`, `rateKiss_bf`, `rateQuick_bf` take Betaflight configurator units; `rateEval` maps the library's (rc, srate, expo) triple.

## fpvinput (C++, optional, `include/fpvinput.h`)
`Device::list()` / `open(i)` / `read(mapping)` returns `Sticks {throttle, yaw, pitch, roll, arm, mode}`; `Mapping` holds axis index, invert, min/center/max, deadzone per stick,
arm and mode switch assignment; `saveMapping`/`loadMapping` use JSON. Calibration: `calibBegin()`, call `calibTrack()` while the user moves the sticks,
`calibMostMovedAxis()` / `calibRange()` to auto-detect the axis, then `calibCapture()` with sticks released. Pure functions (`normalizeCentered`, `normalizeThrottle`, `applyMapping`)
work without SDL, (piecewise linear around the centre, rescaled deadzone; throttle linear min..max, no deadzone; gamepad mode uses the
mid-point as idle).

## Calibration / diagnostic parameters
`FPV_PARAM_SAG_SCALE` (default 0: RPM ignores the simplified voltage sag; 1 = full sag), `FPV_PARAM_RHO_SCALE`, `FPV_PARAM_THRUST_SCALE`, `FPV_PARAM_DRAG_SCALE`, `FPV_PARAM_INFLOW_SCALE`, `FPV_PARAM_GE_SCALE` (all default 1 = nominal; multipliers for calibration), `FPV_PARAM_DRAG_WASH_SCALE` (prop-wash area reduction of the baked-table drag; default 0 = off, 1 = full reduction).
For reproducible runs step at a fixed dt (e.g. 1/60 s) and feed `fpv_set_trace_results(height_above_ground_cm, -1)` every step (ground effect).
