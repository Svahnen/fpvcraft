// fpvcore flight model: rigid-body quadcopter with motor/propeller thrust, battery, aerodynamic drag and contact response (docs/FLIGHT_MODEL.md).
// Units: cm, kg, deg, s; force kg*cm/s^2.
#pragma once
#include <string>
#include <vector>
#include "curve.hpp"
#include "fpv_math.hpp"

namespace fpv {

enum Mode { MODE_ACRO = 1, MODE_ANGLE = 2, MODE_ACRO3D = 3, MODE_HORIZON = 4 };

struct Airframe {  // per-airframe tuning values (see data/presets)
  double motor_kv = 1750, battery_cells = 6, prop_pitch_in = 4.3, mass_kg = 0.715, gravity = 9.82;
  double linear_damping = 0.4, camera_tilt_deg = 25, lateral_grip = 0.8, air_speed_scale = 1;
  double throttle_min = 5.5, throttle_low = 1, throttle_mid = 1, throttle_high = 1;
  double prop_diameter_in = 5, power_response = 1;
  int pid_wobble_mode = 0;  // 0 = off, 1..5 = camera spring tuning set
  double pid_wobble_amount = 0, propwash_amount = 0;
  int drag_mode = 0;        // 0 = simple, 1 = scaled by linear_damping (table drag only)
  double drag_coeff_front = 0.4, drag_coeff_top = 0.4, drag_coeff_side = 0.4, drag_coeff_diag = 0.4;
};
struct BatterySpec {
  int cells = 6;
  double capacity_mah = 1400, discharge_c = 150, r_mohm = 5;
  bool is_liion = false;
};
struct RateProfile {
  int type = 0;
  double rc[3] = {1, 1, 1}, srate[3] = {0.7, 0.7, 0.7}, expo[3] = {0, 0, 0};  // roll, pitch, yaw
  double thr_mid = 0.5, thr_expo = 0;
};
struct Inputs { double throttle = -1, yaw = 0, pitch = 0, roll = 0; bool arm = false; };

// Optional area-based drag tables (directional drag model). All arrays same length N.
struct DragTables {
  std::vector<V3> dirs;       // sample directions (local-frame unit vectors)
  std::vector<double> area;   // projected frontal area [cm^2] along each direction
  std::vector<V3> force_dir;  // local-frame correction of the force direction (deflection of the flow by tilted surfaces)
  bool valid() const { return !dirs.empty() && dirs.size() == area.size() && dirs.size() == force_dir.size(); }
};

struct BatteryState {
  double cell_v = 4.0, v_wosag = 4.0, mah = 0, amps = 0, sag = 0;
};

struct Contact { V3 normal; double depth_cm; };
// One body-point contact of the current frame (solved together with the others by sequential impulses, accumulated + clamped).
struct ContactRec { V3 r, n; double mu = 0, bias = 0, jn = 0; V3 jt; };

// Trace callback: cast a ray from origin along unit dir for max_dist cm; return true and *dist if something is hit.
typedef int (*RaycastFn)(void* user, const double origin[3], const double dir[3], double max_dist, double* dist);

struct Params {
  double max_dt = 1.0 / 30.0;
  double angular_damping = 6.5;
  double max_ang_vel = 3000;
  // Rigid-body contact. friction = kinetic Coulomb coefficient (drone x surface, already combined);
  // the static coefficient is friction * friction_static_ratio. Restitution is only applied above bounce_threshold (cm/s, default 200).
  double restitution = 0.1, friction = 0.7, friction_static_ratio = 1.1, bounce_threshold = 200.0;
  double body_half[3] = {9.0, 9.0, 2.5};    // contact box half extents, body-local cm (x nose, y right, z up) - frame corners incl. motors
  // rest detection: disarmed + touching + slow for rest_time seconds -> velocities are held at zero until armed/lifted
  double rest_lin = 8.0, rest_ang = 30.0, rest_time = 0.25;
  double cam_offset[3] = {0, 6, 2.5};  // body-local cm (estimate)
  double cam_fov = 120;                // estimate
  bool simulate_battery = false;
  bool freeze_until_armed = true;      // physics stays off until the first arm
  bool pid_camera = true;              // camera follows the cosmetic spring attitude
  double wind_cms[3] = {0, 0, 0};
  // calibration/diagnostic multipliers; 1 = nominal
  double rho_scale = 1.0, thrust_scale = 1.0, drag_scale = 1.0, inflow_scale = 1.0, sag_scale = 0.0, ge_scale = 1.0, drag_wash_scale = 0.0;  // sag_scale: 0 = RPM ignores the simplified (non-simulated) voltage sag, 1 = full sag
};

struct State {
  V3 pos;           // cm
  Quat q;           // body -> world
  V3 vel;           // cm/s
  V3 angvel;        // deg/s, world (residual angular velocity from collisions and torques)
  double rpm = 0, max_rpm = 0;
  BatteryState bat;
  bool armed = false, arm_switch = false, physics_active = false, prop_destroyed = false;
  int mode = MODE_ACRO;
  double time = 0, start_z = 0;
  // persistent controller/propwash state
  double last_thrust = 0, agitation = 0, last_rot_rate = 0;
  double propwash_offset = 0;
  double prop_damage[4] = {0, 0, 0, 0};
  double ang_damp_cur = 6.5, ang_damp_timer = 0;
  // cosmetic spring-attitude model
  double spring_acc = 0;
  struct Spring { double vel = 0, prev_target = 0; bool valid = false; } sp[3];
  std::vector<ContactRec> frame_contacts;  // cleared every step
  int contact_hold = 0;      // >0: a host contact was reported within the last few steps
  double sleep_t = 0;        // seconds the drone has been touching + slow + disarmed
  bool sleeping = false;
  Quat pid_rel;  // spring attitude relative to the drone
  // angle-mode PD controller state
  double pid_prev_e[2] = {0, 0}, pid_int[2] = {0, 0};
  // derived/diagnostic
  double thrust_len = 0, throttle_theory = 0, throttle_in = 0;
  V3 theory;  // (roll, pitch, yaw) setpoint deg/s
  V3 last_force;
  Rng rng;
};

class Model {
 public:
  Airframe s;
  BatterySpec bat_spec;
  RateProfile rates;
  Params p;
  State st;
  Inputs in;
  double last_thrust_deriv_ = 0;
  DragTables drag;
  RaycastFn raycast = nullptr;
  void* raycast_user = nullptr;
  double inj_down_cm = -1, inj_up_cm = -1;  // injected trace results (<0 = no hit)

  bool init(const Airframe& ds, const BatterySpec& b, const CurveSet& curves, std::string& err);
  void reset(const V3& pos_cm, double heading_deg);
  void step(double dt);
  // Contact injection (world-space normal pointing out of the surface). Returns normal impulse (kg*cm/s).
  double addContact(const V3& n, double depth_cm);
  // Point contact (world cm). friction/restitution < 0 = use Params. Applies position push-out, a normal impulse with restitution,
  // Coulomb friction against the tangential point velocity and the resulting torque. Returns the normal impulse (kg*cm/s).
  double addContactEx(const V3& point, const V3& n, double depth_cm, double friction, double restitution);
  void solveContacts(int iterations);
  V3 invInertiaWorld(const V3& v) const;  // I^-1 * v in world frame (v in kg*cm^2 units)
  void hitEvent(const V3& n, double impulse);

  // --- exposed pure functions of the model (used by tests) ---
  double calcRpm(double throttleAxis, double cell_voltage, double up_dot) const;
  double calcThrustLen(double rpm, double maxRpm, V3 vLocal, double extra) const;
  double throttleShape(double t) const;
  double throttleCurve(double x) const;
  double airDensity(double altitude_cm) const;

 private:
  const Curve *c_thr_ = nullptr, *c_adv_ = nullptr, *c_kvsound_ = nullptr, *c_kvpitch_ = nullptr,
              *c_ratio_ = nullptr, *c_batcons_ = nullptr, *c_lipo_ = nullptr, *c_liion_ = nullptr,
              *c_airfric_ = nullptr, *c_pwresp_ = nullptr;
  CurveSet curves_;
  bool trace(const V3& origin, const V3& dir, double maxd, double injected, double* dist) const;
  V3 airFriction(const V3& vworld, const V3& vlocalRel, double rpm) const;
  void updateBattery(double dt, double yawAddMag, bool canFly);
  void updateSpring(double dt);
  void updateAgitation(double dt);
};

}  // namespace fpv
