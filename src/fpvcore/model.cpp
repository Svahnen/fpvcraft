#include "model.hpp"
#include <cstdio>
#include "rates.hpp"

namespace fpv {

static const double kSpringTick = 1.0 / 120.0;
static const double kSpeedOfSound = 346.0;

// ---- helpers ------------------------------------------------------------------------------
static double perlin1d(double x) {  // Improved-Perlin style 1D gradient noise, range ~[-1,1]
  static unsigned char perm[512];
  static bool init = false;
  if (!init) {
    unsigned s = 12345u;
    unsigned char p[256];
    for (int i = 0; i < 256; ++i) p[i] = (unsigned char)i;
    for (int i = 255; i > 0; --i) { s = s * 1664525u + 1013904223u; int j = (s >> 8) % (i + 1); std::swap(p[i], p[j]); }
    for (int i = 0; i < 512; ++i) perm[i] = p[i & 255];
    init = true;
  }
  double fl = std::floor(x);
  int xi = (int)((long long)fl & 255);
  double xf = x - fl;
  double u = xf * xf * xf * (xf * (xf * 6 - 15) + 10);
  auto grad = [&](int h, double d) { return (h & 1) ? -d : d; };
  double a = grad(perm[perm[xi]], xf), b = grad(perm[perm[xi + 1]], xf - 1);
  return clampd(2.0 * lerpd(a, b, u), -1.0, 1.0);
}

static double finterpTo(double cur, double target, double dt, double speed) {
  if (speed <= 0) return target;
  double dist = target - cur;
  if (dist * dist < 1e-8) return target;
  return cur + dist * clampd(dt * speed, 0.0, 1.0);
}

// Critically-parameterised spring follower (semi-implicit Euler, cosmetic only)
static double springInterp(double cur, double target, State::Spring& s, double k, double zeta, double dt, double m) {
  if (dt < 1e-8) return cur;
  if (!s.valid) { s.prev_target = target; s.valid = true; }
  double tv = (target - s.prev_target) / dt;
  s.prev_target = target;
  double damping = 2.0 * std::sqrt(m * k) * zeta;
  double acc = ((target - cur) * k + (tv - s.vel) * damping) / m;
  s.vel += acc * dt;
  return cur + s.vel * dt;
}

static std::string sizeKey(double size) {
  double r = std::round(size * 10.0) / 10.0;
  static const double known[] = {2, 2.5, 3, 3.5, 4, 5, 6, 7, 10, 15};
  for (double k : known) if (std::fabs(r - k) < 1e-6) return k == 2.5 ? "2.5" : (k == 3.5 ? "3.5" : std::to_string((int)k));
  return "5";
}

bool Model::init(const Airframe& ds, const BatterySpec& b, const CurveSet& curves, std::string& err) {
  s = ds; bat_spec = b; curves_ = curves;
  auto get = [&](const std::string& n) -> const Curve* {
    auto it = curves_.find(n);
    if (it == curves_.end()) { err = "missing curve " + n; return nullptr; }
    return &it->second;
  };
  const std::string key = sizeKey(s.prop_diameter_in);
  c_thr_ = get("motor/power_vs_throttle/" + key);
  c_adv_ = get("motor/rpm_vs_throttle/" + key);
  c_kvsound_ = get("motor/load_droop_vs_tip_mach");
  c_kvpitch_ = get("motor/speed_vs_pitch_ratio");
  c_ratio_ = get("prop/thrust_factor_vs_diameter");
  c_batcons_ = get("battery/current_k_vs_diameter");
  c_lipo_ = get("battery/lipo_ocv");
  c_liion_ = get("battery/liion_ocv");
  c_airfric_ = get("drag/size_factor");
  c_pwresp_ = get("dynamics/propwash_vs_throttle");
  if (!(c_thr_ && c_adv_ && c_kvsound_ && c_kvpitch_ && c_ratio_ && c_batcons_ && c_lipo_ && c_liion_ && c_airfric_ && c_pwresp_)) return false;
  reset(V3(), 0);
  return true;
}

void Model::reset(const V3& pos, double heading_deg) {
  Rng keep = st.rng;
  st = State();
  st.rng = keep;
  st.pos = pos;
  st.start_z = pos.z;
  // body frame: +Y nose, +X left, +Z up. Nose along world heading psi (from +X toward +Y) => body yaw = psi - 90.
  st.q = quatFromEuler(0, heading_deg - 90.0, 0);
  st.pid_rel = Quat();
  st.ang_damp_cur = p.angular_damping;
  st.bat.cell_v = p.simulate_battery ? 4.2 : 4.0;
  st.bat.v_wosag = st.bat.cell_v;
  st.physics_active = !p.freeze_until_armed;
  st.max_rpm = calcRpm(1.0, 4.0, 1.0);
}

// ---- model functions ------------------------------------------------------------------------
double Model::throttleCurve(double x) const { return c_thr_->eval(x); }

double Model::airDensity(double /*altitude_cm*/) const {
  // Sea-level standard air density (kg/m^3), independent of altitude over the flyable range.
  return p.rho_scale * 1.2250000238418579;
}

double Model::throttleShape(double t) const {
  double u = mapRangeClamped(t, -1, 1, 0, 1);
  return mapRangeClamped(u, 0.5, 1, 0, 1) * s.throttle_high + mapRangeClamped(std::fabs(u - 0.5), 0, 0.5, 1, 0) * s.throttle_mid +
         mapRangeClamped(u, 0, 0.5, 1, 0) * s.throttle_low;
}

// Motor speed [rpm] from the throttle axis (-1..1), cell voltage and the body's up-vector z component. The throttle shaping is
// evaluated at the `thr` argument (not at the live stick), so calcRpm(1, ...) gives the full-throttle speed.
double Model::calcRpm(double thr, double cell_voltage, double up_dot) const {
  const double D = s.prop_diameter_in * 2.54 * 0.01;
  const double lo = s.throttle_min * 0.01;
  const double idle_floor = lerpd(lo + 0.04, lo, mapRangeClamped(up_dot, -0.7, 0.5, 0, 1));
  const double x = mapRangeClamped(thr, -1, 1, idle_floor, 1);
  const double theo = (s.motor_kv / 1000.0) * 1000.0 * s.battery_cells * cell_voltage * (c_adv_->eval(x) * throttleShape(thr));
  const double tip = theo / 60.0 * kPi * D;
  double r = theo * c_kvsound_->eval(clampd(tip / kSpeedOfSound, 0, 1));
  r *= c_kvpitch_->eval(s.prop_pitch_in / s.prop_diameter_in);
  const double lim = kSpeedOfSound * mapRangeClamped(s.prop_diameter_in, 2, 6, 0.62, 1);
  const double mach = (r / 60.0 * kPi * D) / lim;
  if (mach >= 0.75) {
    double cl = clampd(r / 60.0 * kPi * D, 0, lim * 0.85) / (kPi * D) * 60.0;
    r = lerpd(r, cl, mapRangeClamped(mach, 0.75, 1, 0, 1));
  }
  return r;
}

// Total propeller thrust. v = air-relative velocity in the drone frame [cm/s]; returns kg*cm/s^2.
double Model::calcThrustLen(double rpm, double maxRpm, V3 v, double extra) const {
  const double size = s.prop_diameter_in, pitch = s.prop_pitch_in;
  const double rho = airDensity(st.pos.z - st.start_z);
  const double vl = len(v);
  const double speed_mix = mapRangeClamped(vl, 0, 2000, 0.92, 1);
  const double ratio = c_ratio_->eval(size) * lerpd(speed_mix, 1, std::fabs(mapRangeClamped(size, 3, 7, -1, 1)));
  const double k = pitch * 2.54 * 0.01;
  const double exitv = rpm / 60.0 * k, maxAir = maxRpm / 60.0 * k;
  const double vzm = v.z * 0.01;
  const double blend = mapRangeClamped(vl * 0.01 / maxAir, 0.4, 0.7, 0, 1) * mapRangeClamped(safeDivide(exitv, vzm), 0.5, 2, 0, 0.3);
  V3 v_eff = normal(vlerp(v, V3(0, 0, exitv * 100.0), blend)) * vl;
  const double S = mapRangeClamped((vzm - exitv) / maxAir, -1, -0.4, 1, 0);
  const double Fz = mapRangeClamped(vzm, -20, 3, 1, 0);
  const double mix = S * mapRangeClamped(size, 3, 6.5, 0, 1) * Fz * 0.5;
  const double vi = lerpd(v_eff.z, exitv * 100.0, mix) * 0.01;
  const double g = s.lateral_grip;
  const double grip = g < 0 ? mapRangeClamped(g, 0, 1, 0.65, 0.95) : mapRangeClamped(g, 1, 2, 0.95, 1.7);
  const double grip_factor = v.z < 0 ? grip : 1.0;
  const double R = clampd(v.z < 0 ? 1.0 : s.air_speed_scale, 0, 2);
  const double Dm = size * 0.0254;
  const double T = rho * kPi * Dm * Dm * 0.25 * (sgn(exitv) * exitv * exitv - exitv * vi * R * p.inflow_scale) *
                   std::pow(size / (3.29546 * pitch), 1.5) * 100.0 * 4.0 * ratio * grip_factor;
  return std::max(T, 0.0) + extra;
}

bool Model::trace(const V3& o, const V3& d, double maxd, double injected, double* dist) const {
  if (raycast) {
    double oo[3] = {o.x, o.y, o.z}, dd[3] = {d.x, d.y, d.z};
    return raycast(raycast_user, oo, dd, maxd, dist) != 0;
  }
  if (injected >= 0 && injected <= maxd) { *dist = injected; return true; }
  return false;
}

// ---- drag ---------------------------------------------------------------------------------
// Inverse-distance interpolation (weights 1/d) over the 3 nearest table samples; an exact hit (d == 0) returns that sample.
template <class T, class Get>
static T nearestInterp(const std::vector<V3>& dirs, const Get& get, const V3& q, T zero) {
  int i0 = -1, i1 = -1, i2 = -1;
  float d0 = 3.402823466e38f, d1 = d0, d2 = d0;
  for (size_t i = 0; i < dirs.size(); ++i) {
    const float dx = (float)(q.x - dirs[i].x), dy = (float)(q.y - dirs[i].y), dz = (float)(q.z - dirs[i].z);
    const float d = dx * dx + dy * dy + dz * dz;
    if (d0 <= d) {
      if (d1 <= d) { if (d < d2) { i2 = (int)i; d2 = d; } }
      else { d2 = d1; i2 = i1; i1 = (int)i; d1 = d; }
    } else { d2 = d1; i2 = i1; i1 = i0; i0 = (int)i; d1 = d0; d0 = d; }
  }
  if (i0 < 0) return zero;
  const float r0 = std::sqrt(d0);
  if (r0 == 0.0f || i1 < 0 || i2 < 0) return get(i0);
  const float w0 = 1.0f / r0, w1 = 1.0f / std::sqrt(d1), w2 = 1.0f / std::sqrt(d2);
  const double inv = 1.0 / (w0 + w1 + w2);
  return (get(i0) * (double)w0 + get(i1) * (double)w1 + get(i2) * (double)w2) * inv;
}

V3 Model::airFriction(const V3& vw, const V3& vlocRel, double rpm) const {
  const double rho = airDensity(st.pos.z - st.start_z);
  if (!drag.valid()) {
    // Table-free fallback: isotropic quadratic drag
    double sp = len(vw);
    if (sp < 1e-9) return V3();
    double coef = s.linear_damping * c_airfric_->eval(s.prop_diameter_in) * 0.00075;
    return normal(vw) * (-0.5 * rho * coef * sp * sp);
  }
  // Directional drag from the baked area tables
  const V3 vl = vlocRel;
  const V3 vn = normal(vl);
  const V3 vabsN = normal(V3(std::fabs(vl.x), std::fabs(vl.y), std::fabs(vl.z)));
  const V3 dir_in = normal(V3(vn.x, vn.y, vn.z * 2.0 * mapRangeClamped(std::fabs(vn.z), 0, 0.7, 0.1, 1)));
  double area_raw = nearestInterp<double>(drag.dirs, [&](int i) { return drag.area[i]; }, dir_in, 0.0);
  V3 Fdir = nearestInterp<V3>(drag.dirs, [&](int i) { return drag.force_dir[i]; }, vn, V3());
  static const V3 cxd[4] = {V3(0, 1, 0), V3(0, 0, 1), V3(1, 0, 0), V3(0.577, 0.577, 0.577)};
  double cxv[4] = {s.drag_coeff_front, s.drag_coeff_top, s.drag_coeff_side, s.drag_coeff_diag};
  std::vector<V3> cxdv(cxd, cxd + 4);
  double CX = nearestInterp<double>(cxdv, [&](int i) { return cxv[i]; }, vabsN, 0.0);
  const double exit_speed = (rpm / 60.0) * s.prop_pitch_in * 2.54 * 0.01;
  const double dot_up = dot(vn, V3(0, 0, 1));
  const double sf = mapRangeClamped(vl.z * 0.01 - exit_speed, -3, 3, 1, 0);
  const double area_eff = lerpd(area_raw, lerpd(1, 0.66, std::fabs(dot_up)) * area_raw, sf * 2.0 * std::max(dot_up, 0.0) * p.drag_wash_scale);
  const double coef = (s.drag_mode == 0 ? 1.0 : s.linear_damping * 2.5) * 1e-5 * s.drag_coeff_side * CX;
  const V3 vw2 = st.q.rotate(vl);
  const double sp2 = len(vw2);
  V3 d = normal(vw2) * (-0.5 * rho * area_eff * coef * sp2 * sp2);
  const V3 up = st.q.rotate(V3(0, 0, 1));
  double sway = mapRangeClamped(std::min(1 - vabsN.z, 0.7) * std::min(std::fabs(up.z), 0.7) * mapRangeClamped(sp2, 250, 1600, 1, 0), 0, 1, 1, 1.7);
  d.x *= sway; d.y *= sway;
  const double portance = mapRangeClamped(len(vl) * 0.036, 200, 500, 0.7, 2.8);
  V3 out = normal(normal(d) + st.q.rotate(Fdir) * portance) * len(d);
  if (out.z > 0) out = V3(out.x, out.y, out.z * 0.93);
  return out;
}

// ---- battery -------------------------------------------------------------------------------
void Model::updateBattery(double dt, double yawAddMag, bool canFly) {
  BatteryState& b = st.bat;
  const double maxAmps = bat_spec.discharge_c * bat_spec.capacity_mah * 0.0018;
  double x = st.throttle_theory + yawAddMag +
             0.5 * (s.mass_kg * s.prop_diameter_in * 1000.0) * clampd(st.agitation - std::fabs(st.propwash_offset), 0, 1);
  x = x * 0.25 * 0.01 * (canFly ? 1.0 : 0.0);
  const int cells = bat_spec.cells;
  double amps = (4.0 * c_batcons_->eval(s.prop_diameter_in) * std::pow(std::max(x, 0.0), 1.5) / std::sqrt(s.prop_diameter_in * 2.54) + 1.6) / (4.0 * cells);
  amps = std::min(amps, maxAmps);
  b.amps = amps;
  const double rpm_frac = st.max_rpm > 0 ? st.rpm / st.max_rpm : 0;
  if (p.simulate_battery) {
    b.mah += amps * dt / 3.6;
    const double usage = b.mah / bat_spec.capacity_mah;
    b.v_wosag = bat_spec.is_liion ? c_liion_->eval(usage * 1.05) : c_lipo_->eval(usage * 1.05);
    const double reff = cells * bat_spec.r_mohm * (1 + 1.5 * usage) * 0.001;
    const double drop = reff * amps, stress = maxAmps > 0 ? amps / maxAmps : 0;
    const double maxSag = bat_spec.is_liion ? 0.9 : 0.5;
    const double load_sag = cells * stress * stress * maxSag;
    const double cap = cells * maxSag * (bat_spec.is_liion ? mapRangeClamped(std::pow(stress * 2, 4), 0, 1, 0.5, 1.3) : 1.0);
    const double target = std::min(drop + load_sag, cap);
    if (target > b.sag) b.sag = std::min(b.sag + (target - b.sag) * 2.3 * dt, target);
    else b.sag = std::max(target, b.sag - (b.sag - target) * (bat_spec.is_liion ? 0.79 : 1.3) * dt);
    b.sag = std::max(b.sag, 0.0);
    b.cell_v = std::max(b.v_wosag - b.sag / cells, 0.0);
  } else {
    const double sel = bat_spec.is_liion ? 2.7 : 3.5;
    b.cell_v = lerpd(4.0, lerpd(3.9, sel, std::pow(rpm_frac, 2.5)), b.sag);
    b.v_wosag = 4.0;
    const double m = mapRangeClamped(std::pow(rpm_frac, 1.8), 0, 1, -1, 1);
    b.sag = clampd(b.sag + m * (m < 0 ? 0.6 : 1.0) * dt, 0, 1);
  }
}

// ---- propwash / agitation state ------------------------------------------------------------------
void Model::updateAgitation(double dt) {
  const V3 w = st.angvel + st.theory;  // residual angular velocity plus rate setpoint (magnitude only)
  const double rot = len(w);
  const double d = (rot - st.last_rot_rate) / std::max(dt, 1e-6);
  st.last_rot_rate = rot;
  const double x = 2.0 * mapRangeClamped(std::fabs(d), 0, 12500, 0, 1) +
                   clampd(s.pid_wobble_amount, 0.85, 1.0) * s.propwash_amount * mapRangeClamped(std::fabs(st.propwash_offset), 0, 0.15, 0, 1);
  const double y = finterpTo(x, 0, dt, 25.0 / clampd(dt * 120.0, 0.8, 3.0));
  const double wgt = (y <= st.agitation) ? 0.2 : 1.0;
  st.agitation = wgt * y + (1 - wgt) * st.agitation;
}

// ---- cosmetic spring (fixed 120 Hz update) ----------------------------------------------------
void Model::updateSpring(double dt) {
  if (s.pid_wobble_mode == 0 || !st.armed) { st.pid_rel = Quat(); st.propwash_offset = 0; return; }
  static const double dampT[6] = {0.98, 0.98, 0.97, 0.9, 0.3, 0.8}, stiffT[6] = {50, 50, 50, 25, 25, 20},
                      massT[6] = {0.06, 0.045, 0.06, 0.07, 0.12, 0.04};
  const int pi = std::min(std::max(s.pid_wobble_mode, 0), 5);
  const double zeta = dampT[pi] * mapRangeClamped(s.prop_diameter_in, 6, 10, 1, 0.5);
  const double k = stiffT[pi] * mapRangeClamped(s.mass_kg, 2, 8, 1, 2);
  const double m = massT[pi];
  const double thr = mapRangeClamped(in.throttle, -1, 1, 0, 1);  // approximate pre-expo throttle
  const double u = c_pwresp_->eval(thr);
  const double k2 = mapRangeClamped(s.pid_wobble_amount, 0, 1, 2.5, 1.25) * s.propwash_amount;
  const V3 up = st.q.rotate(V3(0, 0, 1));
  const double upSpeed = dot(st.vel, up);
  const double pw = mapRangeClamped(last_thrust_deriv_, 2500, 10000, 0.3, 0.7) *
                    mapRangeClamped(upSpeed, 200, 1500, 0, 1) * upSpeed * u * k2 * 0.001;
  const double amp = mapRangeClamped(pw, 0, 0.3, 0, 2);
  const double t = st.time;
  V3 noise(amp * perlin1d(15 * t), amp * perlin1d(15 * t + 0.28791), amp * perlin1d(15 * t + 0.56812));
  EulerDeg rel = eulerFromQuat(st.pid_rel);
  double r_roll = springInterp(rel.roll, noise.x + st.theory.y / 120.0, st.sp[0], k * 0.5, zeta, dt, m);   // Roll comp = drone lateral axis
  double r_pitch = springInterp(rel.pitch, noise.y + st.theory.x / 120.0, st.sp[1], k, zeta, dt, m);
  double r_yaw = springInterp(rel.yaw, noise.z + st.theory.z / 120.0, st.sp[2], k, zeta, dt, m);
  Quat R = quatFromEuler(r_pitch, r_yaw, r_roll);
  Quat Rn = R * quatFromEuler(noise.y, noise.z, noise.x);
  Quat SO = slerp(R, Rn, mapRangeClamped(s.prop_diameter_in * s.propwash_amount, 5, 10, 0, 0.15));
  EulerDeg so = eulerFromQuat(SO);
  if (std::fabs(so.pitch) > 15 || std::fabs(so.yaw) > 15 || std::fabs(so.roll) > 15) {
    st.pid_rel = Quat();
  } else {
    Quat SO2 = quatFromEuler(so.pitch, so.yaw * 0.75, so.roll);
    double alpha = lerpd(s.pid_wobble_amount, mapRangeClamped(s.pid_wobble_amount, 0, 0.5, 0, 0.98), mapRangeClamped(s.prop_diameter_in, 5, 10, 0, 1));
    st.pid_rel = slerp(Quat(), SO2, alpha);
  }
  st.propwash_offset = pw * (std::sin(t * 3.0 * mapRangeClamped(k2, 0, 1, 130, 65)) - 1.0);
}

// ---- contacts --------------------------------------------------------------------------------
void Model::hitEvent(const V3& n, double I) {
  if (I > 800) {
    st.ang_damp_cur = 0;
    st.ang_damp_timer = 0.15;
    const V3 up = st.q.rotate(V3(0, 0, 1));
    const double spin = std::pow(mapRangeClamped(I, 0, 2000, 0, 1), 2.5) * 2000.0 * (st.rng.next() - 0.5) * (1.0 - std::fabs(dot(n, up)));
    st.angvel += V3(spin, spin, spin);
  }
}

V3 Model::invInertiaWorld(const V3& v) const {
  // Solid box of the contact hull, I_i = m/3 * (h_j^2 + h_k^2) (kg*cm^2)
  const double m = s.mass_kg, hx = p.body_half[0], hy = p.body_half[1], hz = p.body_half[2];
  const double ix = m / 3.0 * (hy * hy + hz * hz), iy = m / 3.0 * (hx * hx + hz * hz), iz = m / 3.0 * (hx * hx + hy * hy);
  const V3 b = st.q.unrotate(v);
  return st.q.rotate(V3(b.x / ix, b.y / iy, b.z / iz));
}


static const double kDegToRad = 3.14159265358979323846 / 180.0;

// Projected Gauss-Seidel over this frame's contacts with accumulated impulses: normal >= 0 driving the point's normal velocity to the
// restitution bias, Coulomb friction cone (static: hold the point, kinetic: mu_k * normal) on the tangential velocity.
void Model::solveContacts(int iterations) {
  const double m = s.mass_kg;
  for (int it = 0; it < iterations; ++it) {
    for (ContactRec& c : st.frame_contacts) {
      auto kEff = [&](const V3& d) { return 1.0 / m + dot(d, cross(invInertiaWorld(cross(c.r, d)), c.r)); };
      auto apply = [&](const V3& J) { st.vel += J * (1.0 / m); st.angvel += invInertiaWorld(cross(c.r, J)) * (1.0 / kDegToRad); };
      V3 vp = st.vel + cross(st.angvel * kDegToRad, c.r);
      const double newJn = std::max(c.jn + (c.bias - dot(vp, c.n)) / kEff(c.n), 0.0);
      apply(c.n * (newJn - c.jn));
      c.jn = newJn;
      if (c.mu <= 0) continue;
      vp = st.vel + cross(st.angvel * kDegToRad, c.r);
      const V3 vt = vp - c.n * dot(vp, c.n);
      const double lt = len(vt);
      if (lt < 1e-12) continue;
      const V3 t = vt * (1.0 / lt);
      V3 jtNew = c.jt - vt * (1.0 / kEff(t));   // impulse that would zero the tangential velocity (accumulated)
      const double lj = len(jtNew), stat = c.mu * p.friction_static_ratio * c.jn;
      if (lj > stat) jtNew = jtNew * (lj > 1e-12 ? c.mu * c.jn / lj : 0.0);  // slipping: kinetic limit
      apply(jtNew - c.jt);
      c.jt = jtNew;
    }
  }
}

double Model::addContact(const V3& nIn, double depth) { return addContactEx(st.pos, nIn, depth, -1, -1); }

double Model::addContactEx(const V3& point, const V3& nIn, double depth, double mu, double e) {
  const V3 n = normal(nIn);
  if (len(n) == 0) return 0;
  if (mu < 0) mu = p.friction;
  if (e < 0) e = p.restitution;
  if (depth > 0) st.pos += n * depth;
  st.contact_hold = 3;
  ContactRec c;
  c.r = (point + n * std::max(depth, 0.0)) - st.pos;   // lever arm from the (corrected) centre
  c.n = n;
  c.mu = mu;
  const double vn = dot(st.vel + cross(st.angvel * kDegToRad, c.r), n);
  if (vn >= 0) return 0;
  c.bias = (-vn > p.bounce_threshold) ? -e * vn : 0.0;
  st.frame_contacts.push_back(c);
  if (st.frame_contacts.size() > 64) st.frame_contacts.erase(st.frame_contacts.begin());
  solveContacts(8);
  const double impulse = st.frame_contacts.back().jn;
  hitEvent(n, impulse);
  return impulse;
}

// ---- main step -------------------------------------------------------------------------------
void Model::step(double dtIn) {
  const double dt = clampd(dtIn, 0.0, p.max_dt);
  if (dt <= 0) return;
  State& S = st;
  S.time += dt;
  S.frame_contacts.clear();
  if (S.ang_damp_timer > 0) {
    S.ang_damp_timer -= dt;
    if (S.ang_damp_timer <= 0) { S.ang_damp_timer = 0; S.ang_damp_cur = S.prop_destroyed ? 0.3 : p.angular_damping; }
  }

  // --- input pipeline (throttle shaping, rate setpoints)
  const bool mode3d = S.mode == MODE_ACRO3D;
  Inputs ip = in;
  if (!mode3d) ip.throttle = throttleMidExpo(in.throttle, rates.thr_mid, rates.thr_expo);
  else ip.throttle = sgn(in.throttle) * throttleMidExpoU(std::fabs(in.throttle), rates.thr_mid, rates.thr_expo);
  V3 theory(rateEval(rates.type, ip.roll, rates.rc[0], rates.srate[0], rates.expo[0]),
            rateEval(rates.type, ip.pitch, rates.rc[1], rates.srate[1], rates.expo[1]),
            rateEval(rates.type, ip.yaw, rates.rc[2], rates.srate[2], rates.expo[2]));
  if (s.prop_diameter_in >= 10) {  // big drones: low-passed setpoints
    theory.x = finterpTo(S.theory.x, theory.x, dt, s.pid_wobble_amount * 22);
    theory.y = finterpTo(S.theory.y, theory.y, dt, s.pid_wobble_amount * 22);
    theory.z = finterpTo(S.theory.z, theory.z, dt, s.pid_wobble_amount * 25);
  }
  S.theory = theory;
  S.throttle_theory = mode3d ? throttleCurve(std::fabs(ip.throttle)) * 4.0
                             : throttleCurve(mapRangeClamped(ip.throttle, -1, 1, s.throttle_min / 80.0, 1)) * 4.0;

  // --- arming
  if (!in.arm) S.armed = false;
  else if (!S.armed && ip.throttle < -0.5) { S.armed = true; S.physics_active = true; }
  S.arm_switch = in.arm;
  const bool canFly = S.armed && S.arm_switch && !S.prop_destroyed;
  if (!S.physics_active) { S.thrust_len = 0; return; }

  // --- rest detection: a disarmed drone that touches the ground and has (nearly) stopped is put to sleep (velocities held at 0)
  {
    const bool touching = S.contact_hold > 0;
    if (S.contact_hold > 0) --S.contact_hold;
    if (!canFly && touching && len(S.vel) < p.rest_lin && len(S.angvel) < p.rest_ang) S.sleep_t += dt;
    else { S.sleep_t = 0; S.sleeping = false; }
    if (S.sleep_t >= p.rest_time) S.sleeping = true;
    if (S.sleeping) { S.vel = V3(); S.angvel = V3(); }
  }

  const bool rotOk = S.arm_switch && !S.prop_destroyed;
  const double d_pitch = theory.y * dt, d_roll = theory.x * dt, d_yaw = theory.z * dt;

  // --- attitude
  if (S.mode == MODE_ANGLE) {
    const V3 up0 = S.q.rotate(V3(0, 0, 1));
    if (rotOk) S.q = (quatAxisAngleDeg(V3(up0.x * 0.1, up0.y * 0.1, up0.z), d_yaw) * S.q).normalized();
    const double maxAng = 50.0;
    const double maxRoll = mapRangeClamped(std::fabs(theory.z), 200, 750, maxAng, maxAng * 0.4);
    EulerDeg ar = eulerFromQuat(S.q);
    const double tgtRoll = ip.roll * maxRoll * -1, cur_roll = -ar.pitch;
    const double tgtPitch = ip.pitch * maxAng * -1, cur_pitch = -ar.roll;
    const double tg[2] = {tgtRoll, tgtPitch}, cu[2] = {cur_roll, cur_pitch};
    double out[2];
    for (int i = 0; i < 2; ++i) {  // PD controller on the attitude error (P=0.15, I=0, D=0.012, output clamped to +-100)
      const double e = tg[i] - cu[i];
      S.pid_int[i] = e * dt + S.pid_int[i] * std::exp(-0.0 * dt);   // integral decay rate 0 -> factor 1
      const double de = (e - S.pid_prev_e[i]) / dt;                  // previous error starts at 0: first-step derivative kick e/dt
      S.pid_prev_e[i] = e;
      out[i] = clampd(0.15 * e + 0.0 * S.pid_int[i] + 0.012 * de, -100, 100);
    }
    if (canFly) {
      S.angvel += S.q.rotate(V3(0, 1, 0)) * (out[0] * 360.0 * dt);  // body +Y axis; angular acceleration (deg/s^2)
      S.angvel += S.q.rotate(V3(1, 0, 0)) * (out[1] * 360.0 * dt);  // body +X axis
    }
  } else if (rotOk) {
    S.q = (S.q * quatFromEuler(d_roll, d_yaw, d_pitch)).normalized();  // body-frame Euler step: pitch=d_roll, yaw=d_yaw, roll=d_pitch
  }
  // MODE_HORIZON is accepted but treated as Acro.

  // --- forces: thrust, ground effect, gravity, drag
  const V3 up = S.q.rotate(V3(0, 0, 1));
  const double mass = s.mass_kg;
  const V3 wind(p.wind_cms[0], p.wind_cms[1], p.wind_cms[2]);
  const V3 vrel = S.vel - wind;
  V3 vloc = S.q.unrotate(vrel);
  const bool rev = mode3d && ip.throttle < 0;
  if (rev) vloc = -vloc;
  const double thrAxis = mode3d ? mapRangeClamped(std::fabs(ip.throttle), 0, 1, -1, 1) : ip.throttle;
  // In the default (non-simulated battery) mode the displayed voltage sags but the thrust does not: the RPM sees the unsagged
  // 4.0 V/cell; sag_scale (0..1) blends the sagged voltage back in.
  const double vRpm = p.simulate_battery ? S.bat.cell_v : lerpd(S.bat.v_wosag, S.bat.cell_v, p.sag_scale);
  S.rpm = calcRpm(thrAxis, vRpm, up.z);
  S.max_rpm = calcRpm(1.0, 4.0, up.z);

  const double kvScale = (s.motor_kv / 1000.0) * s.battery_cells * 1000.0 / 10500.0;
  S.throttle_in = kvScale * (mode3d ? sgn(ip.throttle) * S.throttle_theory : S.throttle_theory);
  const double xd = S.throttle_in * throttleShape(thrAxis);
  const double deriv = (xd - S.last_thrust) / dt;
  S.last_thrust = xd;
  last_thrust_deriv_ = deriv;

  double gfx = 0, hd = 0;
  if (trace(S.pos, -up, 70.0, inj_down_cm, &hd))
    gfx = p.ge_scale * std::max(0.0, S.throttle_in * 0.0085 * mapRangeClamped(s.prop_diameter_in, 2, 7, 0.3, 1.1) * mapRangeClamped(S.bat.cell_v, 1, 4.2, 0, 1) * (70.0 - hd));
  S.thrust_len = calcThrustLen(S.rpm, S.max_rpm, vloc, gfx);
  V3 thrustVec = up * (S.thrust_len * (rev ? -1.0 : 1.0));

  // turtle (flip-over) mode
  V3 flip_force;
  double hu = 0;
  if (len(S.vel) * 0.036 < 4.0 && up.z < -0.5 && trace(S.pos, up, 14.5, inj_up_cm, &hu))
    flip_force = V3(0, 0, clampd(std::fabs(ip.roll) + std::fabs(ip.pitch) + std::fabs(ip.yaw), 0, 1) * mass * 1975.0);

  updateAgitation(dt);

  // extra lift during fast rotations
  const double max_rot_rate = std::max(std::max(std::fabs(theory.y), std::fabs(theory.x)), std::fabs(theory.z * 25.0));
  const double C = clampd(max_rot_rate / 10.0, 0, 1500);
  const double M1 = mapRangeClamped(std::sqrt(std::max(S.agitation, 0.0)), 0, 1, 0.5, 3);
  const double G = throttleCurve(mapRangeClamped(S.bat.cell_v, 0, 4.2, 0.1, 1));
  const double not_flipping = std::fabs(flip_force.z) <= 0.1 ? 1.0 : 0.0;
  const double mag = clampd(std::pow(C * (mass / 0.7) / 1000.0, 0.7) * 800.0 * M1 * (G / 1450.0) * not_flipping, 0, 0.7 * G);
  const V3 yawAdd = up * (mapRangeClamped(clampd(ip.throttle, 0, 0.8), 0, 0.8, 1, 0) * mag);

  // gravity (with a size-dependent effective-weight factor)
  const double Tz = mapRangeClamped(s.prop_diameter_in, 2, 5, -800, -1390);
  const double A = mapRangeClamped(s.prop_diameter_in, 2, 6, 1.15, 1.072) + mapRangeClamped(mass, 1.5, 6, 0, 0.1);
  const double gs = mapRangeClamped(S.vel.z, 0, Tz, A, 1);
  const V3 grav(0, 0, -100.0 * mass * s.gravity * gs);

  // aerodynamic drag
  const V3 fric = airFriction(S.vel, vloc, S.rpm) * p.drag_scale;

  // net thrust incl. prop damage and throttle-derivative boost
  double maxDmg = 0;
  for (double d : S.prop_damage) maxDmg = std::max(maxDmg, d);
  const double damage_factor = mapRangeClamped(maxDmg, 0, 1, 1, 0.55);
  const double ipw = std::pow(s.power_response, 1.5) * 0.25;
  const double dn = mapRangeUnclamped(std::max(deriv, 0.0), 0, 5000, 0, 1);
  const double boost = ipw * dn * 0.08;
  const V3 Fthrust = thrustVec * ((1 + boost) * damage_factor * 1.05 * 1.05 * p.thrust_scale);
  const V3 F = canFly ? (yawAdd + flip_force + Fthrust) : V3();
  const V3 total = F + grav + fric;
  S.last_force = total;

  updateBattery(dt, len(yawAdd), canFly);

  // --- integration (semi-implicit Euler)
  S.vel += total * (dt / mass);
  S.pos += S.vel * dt;
  S.angvel = S.angvel * std::exp(-S.ang_damp_cur * dt);
  double wl = len(S.angvel);
  if (wl > p.max_ang_vel) { S.angvel = S.angvel * (p.max_ang_vel / wl); wl = p.max_ang_vel; }
  if (wl > 1e-9) S.q = (quatAxisAngleDeg(S.angvel, wl * dt) * S.q).normalized();

  // --- 120 Hz timer
  S.spring_acc += dt;
  int guard = 0;
  while (S.spring_acc >= kSpringTick && guard++ < 16) { S.spring_acc -= kSpringTick; updateSpring(kSpringTick); }
}

}  // namespace fpv
