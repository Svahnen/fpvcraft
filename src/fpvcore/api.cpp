#define FPV_BUILDING 1
#include "fpvcore.h"
#include <cstring>
#include <memory>
#include <nlohmann/json.hpp>
#include "model.hpp"
#include "rates.hpp"

namespace fpv {
extern const char* const kEmbeddedCurves;
extern const char* const kEmbeddedPresetIds[];
extern const char* const kEmbeddedPresets[];
extern const char* const kEmbeddedDrag[];
extern const int kEmbeddedPresetCount;
}  // namespace fpv

using namespace fpv;
using json = nlohmann::json;

struct fpv_drone {
  Model m;
  double cam_uptilt = 25;
};

static thread_local std::string g_err;

static bool parsePreset(const json& j0, Airframe& ds, BatterySpec& bs, std::string& err) {
  const json& j = j0.contains("airframe") ? j0 : (j0.contains("presets") ? j0["presets"] : j0);
  if (!j.contains("airframe")) { err = "preset has no \"airframe\" object"; return false; }
  const json& d = j["airframe"];
  auto g = [&](const char* k, double& v) { if (d.contains(k) && d[k].is_number()) v = d[k].get<double>(); };
  g("motor_kv", ds.motor_kv); g("battery_cells", ds.battery_cells); g("prop_pitch_in", ds.prop_pitch_in);
  g("mass_kg", ds.mass_kg); g("gravity", ds.gravity); g("linear_damping", ds.linear_damping); g("camera_tilt_deg", ds.camera_tilt_deg);
  g("lateral_grip", ds.lateral_grip); g("air_speed_scale", ds.air_speed_scale); g("throttle_min", ds.throttle_min);
  g("throttle_low", ds.throttle_low); g("throttle_mid", ds.throttle_mid); g("throttle_high", ds.throttle_high);
  g("prop_diameter_in", ds.prop_diameter_in); g("power_response", ds.power_response); g("pid_wobble_amount", ds.pid_wobble_amount);
  g("propwash_amount", ds.propwash_amount); g("drag_coeff_front", ds.drag_coeff_front); g("drag_coeff_top", ds.drag_coeff_top);
  g("drag_coeff_side", ds.drag_coeff_side); g("drag_coeff_diag", ds.drag_coeff_diag);
  double wm = ds.pid_wobble_mode; g("pid_wobble_mode", wm); ds.pid_wobble_mode = (int)wm;
  double dm = ds.drag_mode; g("drag_mode", dm); ds.drag_mode = (int)dm;
  bs.cells = (int)ds.battery_cells;
  if (j.contains("battery")) {
    const json& b = j["battery"];
    bs.cells = b.value("cells", bs.cells);
    bs.capacity_mah = b.value("capacity_mah", bs.capacity_mah);
    bs.discharge_c = b.value("discharge_c", bs.discharge_c);
    bs.is_liion = b.value("is_liion", bs.is_liion);
    bs.r_mohm = b.value("internal_resistance_mohm_per_cell", bs.is_liion ? 9.0 : 5.0);
  }
  return true;
}

extern "C" {

const char* fpv_version(void) { return FPV_VERSION; }
const char* fpv_last_error(void) { return g_err.c_str(); }

const char* fpv_builtin_preset(const char* id) {
  if (!id) return nullptr;
  // preset JSON + (if baked) "drag_tables": {dirs, area_cm2, force_dir} from data/presets/drag_<id>.json (tools/bake/bake_drag_generic.py)
  static std::vector<std::string> merged = [] {
    std::vector<std::string> v;
    for (int i = 0; i < kEmbeddedPresetCount; ++i) {
      json j = json::parse(kEmbeddedPresets[i]);
      if (kEmbeddedDrag[i]) {
        json g = json::parse(kEmbeddedDrag[i]);
        j["drag_tables"] = {{"dirs", g["dirs"]}, {"area_cm2", g["area_cm2"]}, {"force_dir", g["force_dir"]}};
      }
      v.push_back(j.dump());
    }
    return v;
  }();
  for (int i = 0; i < kEmbeddedPresetCount; ++i) if (!std::strcmp(id, kEmbeddedPresetIds[i])) return merged[i].c_str();
  return nullptr;
}

fpv_drone* fpv_create_ex(const char* preset_json, const char* curves_json) {
  g_err.clear();
  if (!preset_json) { g_err = "null preset"; return nullptr; }
  try {
    json j = json::parse(preset_json);
    Airframe ds; BatterySpec bs;
    if (!parsePreset(j, ds, bs, g_err)) return nullptr;
    CurveSet cs;
    if (!parseCurves(curves_json ? curves_json : kEmbeddedCurves, cs, g_err)) return nullptr;
    std::unique_ptr<fpv_drone> d(new fpv_drone());
    if (!d->m.init(ds, bs, cs, g_err)) return nullptr;
    d->cam_uptilt = ds.camera_tilt_deg;
    const json& jj = j.contains("presets") ? j["presets"] : j;
    if (jj.contains("drag_tables")) {   // baked aero tables; absent/empty -> data-free isotropic drag
      const json& g = jj["drag_tables"];
      DragTables& t = d->m.drag;
      for (size_t i = 0; i < g["dirs"].size(); ++i) {
        const json& a = g["dirs"][i]; const json& f = g["force_dir"][i];
        t.dirs.push_back(normal(V3(a[0], a[1], a[2])));
        t.area.push_back(g["area_cm2"][i].get<double>());
        t.force_dir.push_back(V3(f[0], f[1], f[2]));
      }
      if (!t.valid()) t = DragTables();
    }
    return d.release();
  } catch (const std::exception& e) {
    g_err = e.what();
    return nullptr;
  }
}
fpv_drone* fpv_create(const char* preset_json) { return fpv_create_ex(preset_json, nullptr); }
void fpv_destroy(fpv_drone* d) { delete d; }

void fpv_set_input(fpv_drone* d, const fpv_input* i) {
  d->m.in.throttle = clampd(i->throttle, -1, 1); d->m.in.yaw = clampd(i->yaw, -1, 1);
  d->m.in.pitch = clampd(i->pitch, -1, 1); d->m.in.roll = clampd(i->roll, -1, 1);
  d->m.in.arm = i->arm != 0;
  int mo = i->mode; d->m.st.mode = (mo >= 1 && mo <= 4) ? mo : 1;
}
void fpv_set_rates(fpv_drone* d, const fpv_rates* r) {
  RateProfile& p = d->m.rates;
  p.type = r->type;
  for (int i = 0; i < 3; ++i) { p.rc[i] = r->rc[i]; p.srate[i] = r->srate[i]; p.expo[i] = r->expo[i]; }
  p.thr_mid = r->thr_mid; p.thr_expo = r->thr_expo;
}
void fpv_get_rates(fpv_drone* d, fpv_rates* r) {
  const RateProfile& p = d->m.rates;
  r->type = p.type;
  for (int i = 0; i < 3; ++i) { r->rc[i] = p.rc[i]; r->srate[i] = p.srate[i]; r->expo[i] = p.expo[i]; }
  r->thr_mid = p.thr_mid; r->thr_expo = p.thr_expo;
}
void fpv_step(fpv_drone* d, double dt) { d->m.step(dt); }

static void put3(double* o, const V3& v) { o[0] = v.x; o[1] = v.y; o[2] = v.z; }
static void putq(double* o, const Quat& q) { o[0] = q.x; o[1] = q.y; o[2] = q.z; o[3] = q.w; }

void fpv_get_state(const fpv_drone* d, fpv_state* o) {
  const Model& m = d->m;
  const State& s = m.st;
  std::memset(o, 0, sizeof(*o));
  put3(o->pos_cm, s.pos); putq(o->quat, s.q); put3(o->linvel_cms, s.vel); put3(o->angvel_degs, s.angvel);
  for (int i = 0; i < 4; ++i) { o->rpm[i] = s.rpm; o->prop_damage[i] = s.prop_damage[i]; }
  o->cell_v = s.bat.cell_v; o->battery_v = s.bat.cell_v * m.bat_spec.cells;
  o->amps = s.bat.amps; o->mah_used = s.bat.mah; o->thrust = s.thrust_len;
  o->rate_setpoint_degs[0] = s.theory.x; o->rate_setpoint_degs[1] = s.theory.y; o->rate_setpoint_degs[2] = s.theory.z;
  o->armed = s.armed; o->can_fly = s.armed && s.arm_switch && !s.prop_destroyed; o->mode = s.mode; o->time = s.time;
  o->prop_destroyed = s.prop_destroyed;
  putq(o->pid_quat, s.pid_rel);
  Quat base = m.p.pid_camera ? s.q * s.pid_rel : s.q;
  Quat cq = (base * quatAxisAngleDeg(V3(1, 0, 0), d->cam_uptilt)).normalized();
  put3(o->cam_pos_cm, s.pos + s.q.rotate(V3(m.p.cam_offset[0], m.p.cam_offset[1], m.p.cam_offset[2])));
  putq(o->cam_quat, cq);
  put3(o->cam_forward, cq.rotate(V3(0, 1, 0))); put3(o->cam_up, cq.rotate(V3(0, 0, 1)));
  o->cam_uptilt_deg = d->cam_uptilt; o->cam_fov_deg = m.p.cam_fov;
}

void fpv_pos_native_to_yup(const double c[3], double m[3]) { double x = c[0], y = c[1], z = c[2]; m[0] = y * 0.01; m[1] = z * 0.01; m[2] = -x * 0.01; }
void fpv_pos_yup_to_native(const double m[3], double c[3]) { double x = m[0], y = m[1], z = m[2]; c[0] = -z * 100; c[1] = x * 100; c[2] = y * 100; }
void fpv_dir_native_to_yup(const double c[3], double m[3]) { double x = c[0], y = c[1], z = c[2]; m[0] = y; m[1] = z; m[2] = -x; }
void fpv_dir_yup_to_native(const double m[3], double c[3]) { double x = m[0], y = m[1], z = m[2]; c[0] = -z; c[1] = x; c[2] = y; }
void fpv_quat_native_to_yup(const double q[4], double o[4]) {  // q' = (-M q.xyz, w)  (reflection, det M = -1)
  double x = q[0], y = q[1], z = q[2], w = q[3];
  o[0] = -y; o[1] = -z; o[2] = x; o[3] = w;
}
void fpv_quat_yup_to_native(const double q[4], double o[4]) {
  double x = q[0], y = q[1], z = q[2], w = q[3];
  o[0] = z; o[1] = -x; o[2] = -y; o[3] = w;
}
void fpv_angvel_native_to_yup(const double w[3], double o[3]) {  // axial vector: w' = det(M) * M w
  double x = w[0], y = w[1], z = w[2];
  o[0] = -y; o[1] = -z; o[2] = x;
}

void fpv_get_state_yup(const fpv_drone* d, fpv_state* o) {
  fpv_state s;
  fpv_get_state(d, &s);
  *o = s;
  fpv_pos_native_to_yup(s.pos_cm, o->pos_cm); fpv_pos_native_to_yup(s.linvel_cms, o->linvel_cms);
  fpv_pos_native_to_yup(s.cam_pos_cm, o->cam_pos_cm);
  fpv_quat_native_to_yup(s.quat, o->quat); fpv_quat_native_to_yup(s.cam_quat, o->cam_quat); fpv_quat_native_to_yup(s.pid_quat, o->pid_quat);
  fpv_angvel_native_to_yup(s.angvel_degs, o->angvel_degs);
  fpv_dir_native_to_yup(s.cam_forward, o->cam_forward); fpv_dir_native_to_yup(s.cam_up, o->cam_up);
}

void fpv_set_state(fpv_drone* d, const fpv_state* s) {
  State& st = d->m.st;
  st.pos = V3(s->pos_cm[0], s->pos_cm[1], s->pos_cm[2]);
  st.q = Quat(s->quat[0], s->quat[1], s->quat[2], s->quat[3]).normalized();
  st.vel = V3(s->linvel_cms[0], s->linvel_cms[1], s->linvel_cms[2]);
  st.angvel = V3(s->angvel_degs[0], s->angvel_degs[1], s->angvel_degs[2]);
}

void fpv_reset(fpv_drone* d, const double pos[3], double heading) {
  d->m.reset(V3(pos[0], pos[1], pos[2]), heading);
  d->m.in.arm = false;
}

void fpv_set_raycast(fpv_drone* d, fpv_raycast_fn fn, void* user) { d->m.raycast = fn; d->m.raycast_user = user; }
void fpv_set_trace_results(fpv_drone* d, double down, double up) { d->m.inj_down_cm = down; d->m.inj_up_cm = up; }
void fpv_set_wind(fpv_drone* d, const double w[3]) { for (int i = 0; i < 3; ++i) d->m.p.wind_cms[i] = w[i]; }
double fpv_add_contact(fpv_drone* d, const double n[3], double depth) { return d->m.addContact(V3(n[0], n[1], n[2]), depth); }
double fpv_add_contact_ex(fpv_drone* d, const double pt[3], const double n[3], double depth, double mu, double e) {
  return d->m.addContactEx(V3(pt[0], pt[1], pt[2]), V3(n[0], n[1], n[2]), depth, mu, e);
}
int fpv_get_contact_points(const fpv_drone* d, double* out, int max) {
  const double* h = d->m.p.body_half;
  int n = 0;
  for (int i = 0; i < 8 && n < max; ++i, ++n) {
    out[3 * n] = (i & 1 ? 1 : -1) * h[0];
    out[3 * n + 1] = (i & 2 ? 1 : -1) * h[1];
    out[3 * n + 2] = (i & 4 ? 1 : -1) * h[2];
  }
  return n;
}
int fpv_is_resting(const fpv_drone* d) { return d->m.st.sleeping ? 1 : 0; }
void fpv_report_hit(fpv_drone* d, const double n[3], double impulse) { d->m.hitEvent(normal(V3(n[0], n[1], n[2])), impulse); }

void fpv_set_drag_tables(fpv_drone* d, int n, const double* dirs, const double* area, const double* fd) {
  DragTables& t = d->m.drag;
  t = DragTables();
  for (int i = 0; i < n; ++i) {
    t.dirs.push_back(normal(V3(dirs[3 * i], dirs[3 * i + 1], dirs[3 * i + 2])));
    t.area.push_back(area[i]);
    t.force_dir.push_back(V3(fd[3 * i], fd[3 * i + 1], fd[3 * i + 2]));
  }
}

void fpv_set_param(fpv_drone* d, int p, double v) {
  Params& pr = d->m.p;
  switch (p) {
    case FPV_PARAM_MAX_DT: pr.max_dt = v; break;
    case FPV_PARAM_RESTITUTION: pr.restitution = v; break;
    case FPV_PARAM_FRICTION: pr.friction = v; break;
    case FPV_PARAM_CAM_UPTILT_DEG: d->cam_uptilt = v; break;
    case FPV_PARAM_CAM_FOV_DEG: pr.cam_fov = v; break;
    case FPV_PARAM_SIMULATE_BATTERY: pr.simulate_battery = v != 0; break;
    case FPV_PARAM_FREEZE_UNTIL_ARMED: pr.freeze_until_armed = v != 0; if (!d->m.st.armed) d->m.st.physics_active = !pr.freeze_until_armed; break;
    case FPV_PARAM_GRAVITY: d->m.s.gravity = v; break;
    case FPV_PARAM_PID_CAMERA: pr.pid_camera = v != 0; break;
    case FPV_PARAM_PHYSICS_ACTIVE: d->m.st.physics_active = v != 0; break;
    case FPV_PARAM_SEED: d->m.st.rng.s = 0x9E3779B97F4A7C15ull ^ ((unsigned long long)(long long)v * 0xD1B54A32D192ED03ull); if (!d->m.st.rng.s) d->m.st.rng.s = 1; break;
    case FPV_PARAM_RHO_SCALE: pr.rho_scale = v; break;
    case FPV_PARAM_THRUST_SCALE: pr.thrust_scale = v; break;
    case FPV_PARAM_DRAG_SCALE: pr.drag_scale = v; break;
    case FPV_PARAM_INFLOW_SCALE: pr.inflow_scale = v; break;
    case FPV_PARAM_SAG_SCALE: pr.sag_scale = v; break;
    case FPV_PARAM_GE_SCALE: pr.ge_scale = v; break;
    case FPV_PARAM_BOUNCE_THRESHOLD: pr.bounce_threshold = v; break;
    case FPV_PARAM_REST_TIME: pr.rest_time = v; break;
    case FPV_PARAM_DRAG_WASH_SCALE: pr.drag_wash_scale = v; break;
    default: break;
  }
}

double fpv_rate(int type, double stick, double rc, double srate, double expo) { return rateEval(type, stick, rc, srate, expo); }

}  // extern "C"
