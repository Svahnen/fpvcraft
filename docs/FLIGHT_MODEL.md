# Flight model

`fpvcore` simulates a small FPV quadcopter as a single rigid body driven by a lumped motor/propeller model. This document describes
what the library computes each step. All numbers quoted are the defaults of the shipped presets; the exact constants live in
`src/fpvcore/model.cpp` and `data/presets/`.

Units inside the library: centimetres, kilograms, degrees, seconds, forces in kg*cm/s^2 (0.01 N). The frame is left-handed with Z up;
the body frame has +Y = nose, +X = left, +Z = up and the quaternion maps body to world. Helpers convert to a metric, Y-up,
right-handed frame (see `docs/API.md`).

## 1. Step overview

`fpv_step(dt)` (dt clamped to 1/30 s by default) does, in order:

1. shape the throttle stick (mid/expo) and convert the other sticks into rate set-points (section 6);
2. arming logic;
3. attitude update (Acro / Angle / Acro3D);
4. motor speed and propeller thrust (sections 2 and 3);
5. gravity, aerodynamic drag, ground effect, extra lift in fast rotations, turtle assist (sections 4 and 5);
6. battery state (section 7);
7. semi-implicit Euler integration of velocity, position, residual angular velocity;
8. a fixed 120 Hz sub-step for the cosmetic camera spring (section 9).

Collisions are reported by the host after the step (section 8).

## 2. Motor and propeller speed

Per preset: motor constant KV [rpm/V], cell count, propeller diameter D and pitch P (inches).

```
rpm_noload = KV * cells * V_cell * f(throttle)
rpm        = rpm_noload * droop(tip_mach) * pitch_factor(P / D)         (then limited near the tip speed of sound)
```

* `f` is an ESC/motor response curve, close to `throttle^1.25`, scaled to 0.9 .. 1.0 at full throttle depending on propeller size
  (`motor/rpm_vs_throttle/<size>` in `curves.json`). The throttle stick maps to an internal 0..1 command between an idle floor (the preset's `throttle_min`, 5.5 %, plus up to 4 % when the
  drone is inverted) and 1.
* `droop(M) = 1 - 0.30 * M^0.75` models the speed lost to aerodynamic load as the blade-tip Mach number M rises.
* `pitch_factor(r) = (0.72 / r)^0.33`: a coarser propeller loads the motor more and runs slower.
* Above tip Mach 0.75 the speed is blended toward a compressibility limit that depends on propeller size.
* The preset's low/medium/high throttle scalers shape the response around mid stick (all 1.0 in the shipped presets).
* With the default simplified battery model the motor sees the unsagged cell voltage (4.0 V/cell); the displayed voltage still sags.

## 3. Thrust

Propeller thrust follows actuator-disc momentum theory with an empirical efficiency factor:

```
v_exit = rpm/60 * P            (m/s, ideal pitch speed)
T = rho * (pi D^2 / 4) * ( v_exit^2 - v_exit * v_inflow * R ) * (D / (3.3 P))^1.5 * 4 motors * eta(D) * grip
```

* `rho` is 1.225 kg/m^3. `eta(D)` (`prop/thrust_factor_vs_diameter`) peaks around 4.5 to 5 inch props and falls for very large and very small ones.
* `v_inflow` is the axial airspeed through the disc, blended toward the exit speed in fast climbs; it reduces thrust in forward and
  climbing flight. `R` (preset `air_speed_scale`) scales that effect.
* `grip` (preset `lateral_grip`) changes thrust while descending, representing how well the props bite into disturbed air.
* Total thrust acts along the body up axis. Thrust is multiplied by a damage factor (down to 0.55) when propeller damage is set, and by a
  short transient boost proportional to the throttle derivative (preset `power_response`), representing fast motor response.
* **Ground effect**: when a downward ray hits within 70 cm, extra lift proportional to the motor power index and to the remaining distance is added.
* **Turtle assist**: when the drone is upside down, nearly stationary and a ray upward hits within 14.5 cm, stick deflection adds an upward force
  that flips it back over.
* **Rotation lift**: fast stick rotations add a small upward force (bounded), so flips and rolls do not lose altitude unrealistically.

## 4. Gravity

Gravity is the preset value (9.82 m/s^2) multiplied by an effective-weight factor between 1 and about 1.15 that depends on propeller size,
mass and downward speed. It is a tuning term which makes small light drones fall slightly faster than ballistic at low speed; it relaxes to 1
at high descent speed.

## 5. Aerodynamic drag

Two models exist.

**Directional tables (default for all presets).** `data/presets/drag_<id>.json` holds 42 sample directions (vertices of a subdivided
icosahedron in the body frame), the projected frontal area `A(d)` along each, and a force-direction correction `g(d)`. They are baked by
`tools/bake/bake_drag_generic.py` from a cuboid drone model (section 10). At run time the relative air velocity (body frame) is normalised,
the three nearest samples are blended with inverse-distance weights, and

```
F_drag = 0.5 * rho * A * c_x * |v|^2          c_x = coefficient interpolated between the preset's four CX values (nose-on, top, side, diagonal)
F_dir  = normalize( -v_hat + portance * g )    portance is 0.7 below 200 km/h and rises to 2.8 at 500 km/h, so tilted surfaces deflect the flow
```

A small extra sideways factor (up to 1.7) models fuselage sway when the drone is flying fast and mostly level, and upward force components are
reduced by 7 %.

**Table-free fallback.** `F = 0.5 * rho * k * |v|^2` with `k = linear_damping * size_factor(D) * 0.00075`.

## 6. Control inputs, rates and modes

* **Arming.** The drone arms when the arm switch is on and the throttle is below -0.5; it disarms when the switch is turned off. Physics is
  frozen until the first arm (configurable). Disarmed, the motors give no force.
* **Throttle** stick -1..1 is shaped by a mid/expo curve; Acro3D uses a bidirectional thrust (reversed thrust for negative throttle).
* **Rates.** Sticks are converted to angular-rate set-points (deg/s) by one of five public formulas: Betaflight, Actual, Raceflight, KISS,
  Quickrates (`src/fpvcore/rates.cpp`). With the Betaflight defaults (rc 1.0, srate 0.7, expo 0) full stick gives 666.7 deg/s.
* **Acro.** The set-point rates rotate the body directly (kinematically, about the body axes) every step; there is no attitude lag.
* **Angle.** Yaw is kinematic. Roll and pitch targets are the stick times a maximum angle (50 deg, reduced at high yaw rate); a PD controller on the
  angle error (P 0.15, D 0.012) applies an angular acceleration.
* **Horizon** is accepted and behaves like Acro.
* **Residual angular velocity.** Collisions and the Angle-mode torque add a separate angular velocity that decays with a damping of 6.5 1/s
  (0 for 0.15 s after a hard impact) and is capped at 3000 deg/s.

## 7. Battery

The battery model is a pack of `cells` LiPo or Li-ion cells with capacity, discharge rating and internal resistance per cell.

* Current estimate from the motor power index: `I = (4 K(D) x^1.5 / sqrt(2.54 D) + 1.6) / (4 cells)` clipped to the discharge limit, where
  `x` is the normalised power demand.
* With `FPV_PARAM_SIMULATE_BATTERY = 1`: consumed capacity integrates the current; the open-circuit voltage follows a LiPo or Li-ion discharge
  curve (`battery/lipo_ocv`, `battery/liion_ocv`); the voltage sag is the sum of an ohmic drop and a stress term, approached with a first-order response
  (rise rate 2.3 1/s, recovery 1.3 1/s for LiPo and 0.79 1/s for Li-ion), and the sagged voltage feeds back into motor speed.
* Default (simplified) mode: cell voltage falls with throttle and recovers at idle, purely for the on-screen display.

## 8. Ground and obstacle contact

The host reports penetrations after each step. The library keeps the contact points of the frame, pushes the body out along the normal and solves
all contacts of the frame together with sequential impulses (8 iterations): a non-negative normal impulse that drives the point's normal velocity to
`-e * v_n` (restitution `e = 0.1`, applied only above a bounce threshold of 2 m/s), and a Coulomb friction cone (kinetic `mu = 0.7`, static 1.1x) on
the tangential velocity. The inertia is that of a solid box of the contact hull. Impacts above an impulse of 800 zero the angular damping for
0.15 s and add a random spin proportional to the impulse. A disarmed drone that is touching, slow, and stays so for 0.25 s is put to sleep
until armed or lifted.

## 9. Cosmetic camera spring

Some presets enable a spring-damper attitude lag (camera wobble) and prop-wash shake for the FPV camera. It runs at a fixed 120 Hz internal rate
and only affects the camera pose (`pid_quat`), never the flight dynamics. Noise comes from a seeded one-dimensional gradient-noise function, so a
run is deterministic for a given seed.

## 10. Presets and data files

* `drone_<id>.json`: airframe values (`airframe`) and battery. Field meanings: `motor_kv` (rpm/V), `battery_cells`,
  `prop_pitch_in` and `prop_diameter_in` (inches), `mass_kg`, `gravity`, `linear_damping` (table-free drag), `camera_tilt_deg` (camera uptilt),
  `lateral_grip`, `air_speed_scale`, `throttle_min`/`throttle_low`/`throttle_mid`/`throttle_high`, `power_response`, `pid_wobble_mode`/`pid_wobble_amount`/`propwash_amount` (camera spring),
  `drag_coeff_front`/`_top`/`_side`/`_diag` (drag coefficients).
* `curves.json`: all curves are closed-form relations sampled by `tools/bake/gen_curves.py`.
* `drag_<id>.json`: baked from a cuboid model (body, battery, camera, two crossing arm bars, four motor cans, three thin blades per propeller)
  scaled by propeller diameter. For each of 42 directions a ray caster renders the model orthographically; the area is the silhouette area and
  the force-direction correction is `d - F`, with `F` the normalised sum of `n (n . d)` over the visible leading surfaces (Newtonian impact theory).
* Preset values are estimates for real hardware classes (motor size/KV, cell count, propeller size, all-up weight) tuned for plausible hover
  throttle, top speed (about 80 km/h for the 3 inch, 130 to 185 km/h for the 5 inch and 150 km/h for the 7 inch), terminal velocity and acceleration.
