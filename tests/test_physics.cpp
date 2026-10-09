#include <doctest/doctest.h>
#include <cmath>
#include <cstring>
#include <vector>
#include <string>
#include "fpvcore.h"
#include "model.hpp"
#include "curve.hpp"

using namespace fpv;

// Direct Model construction (mirrors api.cpp preset parsing for the values the tests need).
#include <fstream>
#include <sstream>
#include <nlohmann/json.hpp>
static std::string slurp(const std::string& p) { std::ifstream f(p); std::stringstream s; s << f.rdbuf(); return s.str(); }
static void loadModel(Model& m, const char* id) {
  auto j = nlohmann::json::parse(slurp(std::string(FPV_SOURCE_DIR) + "/data/presets/drone_" + id + ".json"));
  auto d = j["airframe"];
  Airframe ds;
  ds.motor_kv = d["motor_kv"]; ds.battery_cells = d["battery_cells"]; ds.prop_pitch_in = d["prop_pitch_in"]; ds.mass_kg = d["mass_kg"];
  ds.gravity = d["gravity"]; ds.linear_damping = d["linear_damping"]; ds.lateral_grip = d["lateral_grip"]; ds.air_speed_scale = d["air_speed_scale"];
  ds.throttle_min = d["throttle_min"]; ds.throttle_low = d["throttle_low"]; ds.throttle_mid = d["throttle_mid"];
  ds.throttle_high = d["throttle_high"]; ds.prop_diameter_in = d["prop_diameter_in"]; ds.power_response = d["power_response"];
  ds.pid_wobble_mode = d["pid_wobble_mode"]; ds.pid_wobble_amount = d["pid_wobble_amount"]; ds.propwash_amount = d["propwash_amount"];
  BatterySpec bs; bs.cells = (int)ds.battery_cells;
  CurveSet cs; std::string err;
  REQUIRE(parseCurves(slurp(std::string(FPV_SOURCE_DIR) + "/data/presets/curves.json"), cs, err));
  REQUIRE(m.init(ds, bs, cs, err));
}

TEST_CASE("builtin presets load through the C API") {
  for (const char* id : {"3micro", "5race", "5freestyle", "7longrange"}) {
    fpv_drone* d = fpv_create(fpv_builtin_preset(id));
    CHECK_MESSAGE(d != nullptr, id);
    fpv_destroy(d);
  }
  CHECK(fpv_create("{not json") == nullptr);
  CHECK(std::strlen(fpv_last_error()) > 0);
}

TEST_CASE("thrust-to-weight is plausible and monotonic in throttle (5 inch race)") {
  Model m; loadModel(m, "5race");
  const double W = m.s.mass_kg * m.s.gravity * 100.0;
  auto tw = [&](double thr) {
    double rpm = m.calcRpm(thr, 4.0, 1.0), mx = m.calcRpm(1.0, 4.0, 1.0);
    return m.calcThrustLen(rpm, mx, V3(), 0) / W;
  };
  const double noLoad = m.s.motor_kv * m.s.battery_cells * 4.0;
  CHECK(m.calcRpm(1.0, 4.0, 1.0) < noLoad);          // loaded speed is below KV * V
  CHECK(m.calcRpm(1.0, 4.0, 1.0) > 0.6 * noLoad);
  CHECK(tw(1.0) > 8.0);                              // race quads: thrust-to-weight roughly 8..14
  CHECK(tw(1.0) < 15.0);
  CHECK(tw(-0.5) < 1.2);                             // hover lies between low and mid stick
  CHECK(tw(0.0) > 1.0);
  CHECK(tw(-1.0) < 0.05);
  double prev = -1;
  for (double t = -1.0; t <= 1.0; t += 0.1) { const double x = tw(t); CHECK(x >= prev); prev = x; }
}

static void freeFallSetup(Model& m) {
  m.p.freeze_until_armed = false;
  m.reset(V3(0, 0, 1e7), 0);  // very high up; start_z is re-pinned so the density stays constant
  m.st.start_z = m.st.pos.z;
  m.st.physics_active = true;
}

TEST_CASE("free fall: initial acceleration is g * A (gravity scale at rest)") {
  Model m; loadModel(m, "5race"); freeFallSetup(m);
  m.in.arm = false;
  const double dt = 1.0 / 240.0;
  m.step(dt);
  const double a = -m.st.vel.z / dt;  // cm/s^2 ; drag negligible at v=0
  const double A = mapRangeClamped(5, 2, 6, 1.15, 1.072) + mapRangeClamped(m.s.mass_kg, 1.5, 6, 0, 0.1);
  CHECK(a == doctest::Approx(982.0 * A).epsilon(0.001));
}

TEST_CASE("free fall: drag terminal velocity (data-free branch A)") {
  Model m; loadModel(m, "5race"); freeFallSetup(m);
  const double dt = 1.0 / 120.0;
  for (int i = 0; i < 120 * 60; ++i) { m.st.start_z = m.st.pos.z; m.step(dt); }  // keep rho = 1.225
  const double v = -m.st.vel.z, v2 = v;
  for (int i = 0; i < 120; ++i) { m.st.start_z = m.st.pos.z; m.step(dt); }
  CHECK(-m.st.vel.z == doctest::Approx(v2).epsilon(0.002));  // settled
  // independent solve of  100 m g gs(-v) = 0.5 rho k v^2
  const double rho = 1.225, k = m.s.linear_damping * 1.0 * 0.00075, mass = m.s.mass_kg;
  const double Tz = -1390.0, A = 1.072 + 0.0 + mapRangeClamped(mass, 1.5, 6, 0, 0.1) + (1.15 - 1.072) * (1 - (5 - 2) / 4.0);
  double lo = 0, hi = 1e5;
  for (int i = 0; i < 200; ++i) {
    double vm = 0.5 * (lo + hi);
    double gs = mapRangeClamped(-vm, 0, Tz, A, 1);
    double fg = 100.0 * mass * m.s.gravity * gs, fd = 0.5 * rho * k * vm * vm;
    if (fd < fg) lo = vm; else hi = vm;
  }
  CHECK(-m.st.vel.z == doctest::Approx(0.5 * (lo + hi)).epsilon(0.01));
}

TEST_CASE("armed: stick response, hover band and kinematic rotation") {
  Model m; loadModel(m, "5race");
  m.reset(V3(0, 0, 100000), 0);
  m.in.arm = true; m.in.throttle = -1;
  m.step(1.0 / 120);
  CHECK(m.st.armed);
  m.in.throttle = 0.0;
  for (int i = 0; i < 60; ++i) m.step(1.0 / 120);
  CHECK(m.st.vel.z > 100);  // climbs at mid stick
  m.reset(V3(0, 0, 100000), 0);
  m.in.arm = true; m.in.throttle = -1;
  for (int i = 0; i < 120; ++i) m.step(1.0 / 120);
  CHECK(m.st.vel.z < -500);  // idle -> falls
  // roll: full right stick 0.25 s at the default 666 dps => ~166.7 deg, up vector z = cos(166.7)
  m.reset(V3(0, 0, 100000), 0);
  m.in.arm = true; m.in.throttle = -1.0; m.step(1.0 / 120); CHECK(m.st.armed);
  m.in.throttle = 0.0; m.in.roll = 1.0;
  for (int i = 0; i < 30; ++i) m.step(1.0 / 120);
  V3 up = m.st.q.rotate(V3(0, 0, 1));
  CHECK(up.z == doctest::Approx(std::cos(166.667 * kDeg2Rad)).epsilon(0.01));
}

static fpv_state runSeq(unsigned seed) {
  fpv_drone* d = fpv_create(fpv_builtin_preset("5freestyle"));
  fpv_set_param(d, FPV_PARAM_SEED, seed);
  double p[3] = {0, 0, 5000};
  fpv_reset(d, p, 30);
  fpv_set_param(d, FPV_PARAM_FREEZE_UNTIL_ARMED, 0);
  for (int i = 0; i < 2000; ++i) {
    fpv_input in = {-0.3 + 0.2 * std::sin(i * 0.01), 0.3 * std::sin(i * 0.013), 0.5 * std::cos(i * 0.02), 0.8 * std::sin(i * 0.007), 1, FPV_MODE_ACRO};
    fpv_set_input(d, &in);
    fpv_step(d, (i % 7 == 0) ? 1.0 / 144 : 1.0 / 60);
    if (i % 250 == 249) { double n[3] = {0, 0, 1}; fpv_report_hit(d, n, 1500); }
  }
  fpv_state s; fpv_get_state(d, &s);
  fpv_destroy(d);
  return s;
}
TEST_CASE("determinism") {
  fpv_state a = runSeq(7), b = runSeq(7), c = runSeq(8);
  CHECK(std::memcmp(&a, &b, sizeof a) == 0);
  CHECK(std::memcmp(&a, &c, sizeof a) != 0);  // different seed -> different collision spin
}

TEST_CASE("contact injection: bounce with restitution 0.1, push-out") {
  fpv_drone* d = fpv_create(fpv_builtin_preset("5race"));
  fpv_set_param(d, FPV_PARAM_FREEZE_UNTIL_ARMED, 0);
  double p[3] = {0, 0, 1000};
  fpv_reset(d, p, 0);
  fpv_state s;
  double n[3] = {0, 0, 1};
  double imp = 0;
  for (int i = 0; i < 600 && imp == 0; ++i) {
    fpv_step(d, 1.0 / 120);
    fpv_get_state(d, &s);
    if (s.pos_cm[2] < 10) imp = fpv_add_contact(d, n, 10 - s.pos_cm[2]);
  }
  CHECK(imp > 0);
  fpv_get_state(d, &s);
  CHECK(s.linvel_cms[2] > 0);
  CHECK(s.pos_cm[2] >= 10 - 1e-9);
  fpv_destroy(d);
}

TEST_CASE("battery sag: full throttle sags the cell voltage, idle recovers") {
  Model m; loadModel(m, "5race");
  m.reset(V3(0, 0, 1e6), 0);
  m.in.arm = true; m.in.throttle = -1.0; m.step(1.0 / 120);
  m.in.throttle = 1.0;
  for (int i = 0; i < 240; ++i) m.step(1.0 / 120);
  CHECK(m.st.bat.cell_v < 3.8);
  CHECK(m.st.bat.cell_v > 3.4);
  m.in.throttle = -1.0;
  for (int i = 0; i < 1200; ++i) m.step(1.0 / 120);
  CHECK(m.st.bat.cell_v > 3.95);
}

TEST_CASE("stick directions: roll right banks right, pitch forward points the nose down, yaw right turns right") {
  Model m; loadModel(m, "5race");
  auto fresh = [&]() {
    m.reset(V3(0, 0, 100000), 0);  // nose along world +X, right = +Y
    m.in = Inputs(); m.in.arm = true; m.in.throttle = -1; m.step(1.0 / 120);
    m.in.throttle = -0.4;
  };
  fresh(); m.in.roll = 0.5;
  for (int i = 0; i < 20; ++i) m.step(1.0 / 120);
  CHECK(m.st.q.rotate(V3(0, 0, 1)).y > 0.05);       // up vector leans toward +Y (right)
  fresh(); m.in.pitch = 0.5;
  for (int i = 0; i < 20; ++i) m.step(1.0 / 120);
  CHECK(m.st.q.rotate(V3(0, 1, 0)).z < -0.05);      // nose (body +Y) points down
  fresh(); m.in.yaw = 0.5;
  for (int i = 0; i < 20; ++i) m.step(1.0 / 120);
  CHECK(m.st.q.rotate(V3(0, 1, 0)).y > 0.05);       // nose turns toward +Y (right)
}

TEST_CASE("angle mode self-levels") {
  Model m; loadModel(m, "5race");
  m.reset(V3(0, 0, 100000), 0);
  m.in = Inputs(); m.in.arm = true; m.in.throttle = -1; m.st.mode = MODE_ANGLE; m.step(1.0 / 120);
  m.st.q = (m.st.q * quatFromEuler(0, 0, 30)).normalized();  // 30 deg tilt about the lateral axis
  m.in.throttle = -0.4;
  for (int i = 0; i < 360; ++i) m.step(1.0 / 120);
  CHECK(m.st.q.rotate(V3(0, 0, 1)).z > 0.98);
}

// In the default (non-simulated battery) mode the displayed cell voltage sags under load but the motor speed is computed from the unsagged
// voltage, so a long full-throttle climb does not lose thrust. Regression guard.
TEST_CASE("default-mode voltage sag is displayed but does not reduce RPM") {
  fpv_drone* d = fpv_create(fpv_builtin_preset("5freestyle"));
  double p[3] = {0, 0, 5000};
  fpv_reset(d, p, 0);
  fpv_input in = {-1, 0, 0, 0, 1, FPV_MODE_ACRO};
  fpv_set_input(d, &in); fpv_step(d, 1.0 / 60);
  in.throttle = 1.0; fpv_set_input(d, &in);
  fpv_state s0, s1;
  fpv_step(d, 1.0 / 60); fpv_get_state(d, &s0);
  for (int i = 0; i < 180; ++i) fpv_step(d, 1.0 / 60);
  fpv_get_state(d, &s1);
  CHECK(s1.cell_v < 3.7);                                    // displayed voltage sags
  CHECK(s1.rpm[0] == doctest::Approx(s0.rpm[0]).epsilon(0.01));  // thrust does not
  fpv_destroy(d);
}

// ---- baked aero tables (tools/bake/bake_drag_generic.py -> data/presets/drag_<id>.json, merged into fpv_builtin_preset) ------
TEST_CASE("baked drag tables: loaded with the builtin preset, sane, and used by fpv_step") {
  for (const char* id : {"3micro", "5race", "5freestyle", "7longrange"}) {
    auto j = nlohmann::json::parse(fpv_builtin_preset(id));
    REQUIRE(j.contains("drag_tables"));
    const auto& g = j["drag_tables"];
    CHECK(g["dirs"].size() == 42);                // vertices of a once-subdivided icosahedron
    CHECK(g["area_cm2"].size() == 42);
    CHECK(g["force_dir"].size() == 42);
    for (size_t i = 0; i < 42; ++i) {
      const auto& d = g["dirs"][i];
      CHECK(std::sqrt(double(d[0]) * double(d[0]) + double(d[1]) * double(d[1]) + double(d[2]) * double(d[2])) == doctest::Approx(1.0).epsilon(1e-4));
      CHECK(g["area_cm2"][i].get<double>() > 5.0);
      CHECK(g["area_cm2"][i].get<double>() < 600.0);
    }
  }
  // 5" freestyle, 30 m/s along the nose (+Y local), idle: the small baked frontal area drags much less than the isotropic fallback.
  auto decel = [](bool baked) {
    fpv_drone* d = fpv_create(fpv_builtin_preset("5freestyle"));
    if (!baked) fpv_set_drag_tables(d, 0, nullptr, nullptr, nullptr);
    double p[3] = {0, 0, 50000};
    fpv_reset(d, p, 0);
    fpv_set_param(d, FPV_PARAM_FREEZE_UNTIL_ARMED, 0);
    fpv_input in = {-1, 0, 0, 0, 0, FPV_MODE_ACRO};
    fpv_set_input(d, &in);
    fpv_step(d, 1.0 / 60);
    fpv_state s; fpv_get_state(d, &s);
    s.linvel_cms[0] = 0; s.linvel_cms[1] = 0; s.linvel_cms[2] = 0;
    const double qx = s.quat[0], qy = s.quat[1], qz = s.quat[2], qw = s.quat[3];
    // nose (+Y) direction in world = q * (0,1,0)
    double fwd[3] = {2 * (qx * qy - qw * qz), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz + qw * qx)};
    for (int k = 0; k < 3; ++k) s.linvel_cms[k] = fwd[k] * 3000.0;
    fpv_set_state(d, &s);
    fpv_step(d, 1.0 / 60);
    fpv_state s2; fpv_get_state(d, &s2);
    double dv = 0;                                    // speed loss along the nose, horizontal part removes gravity
    for (int k = 0; k < 2; ++k) dv += (s2.linvel_cms[k] - s.linvel_cms[k]) * fwd[k];
    fpv_destroy(d);
    return -dv * 60.0;  // cm/s^2 deceleration
  };
  const double iso = decel(false), baked = decel(true);
  CHECK(iso > 1000.0);
  CHECK(baked > 100.0);
  CHECK(baked < 0.7 * iso);
}

// ---- rigid-body ground contact (flat ground at z = 0, 8 corner points of the contact hull) -------------------------------------
static void groundPass(Model& m, double maxImpulse[1] = nullptr) {
  const double* h = m.p.body_half;
  for (int pass = 0; pass < 4; ++pass) {
    bool any = false;
    for (int i = 0; i < 8; ++i) {
      const V3 loc((i & 1 ? 1 : -1) * h[0], (i & 2 ? 1 : -1) * h[1], (i & 4 ? 1 : -1) * h[2]);
      const V3 w = m.st.pos + m.st.q.rotate(loc);
      if (w.z < -1e-4) {
        const double I = m.addContactEx(V3(w.x, w.y, 0), V3(0, 0, 1), -w.z, -1, -1);
        if (maxImpulse && I > maxImpulse[0]) maxImpulse[0] = I;
        any = true;
      }
    }
    if (!any) break;
  }
}
static void dropFlat(Model& m, double z, V3 vel) {
  m.p.freeze_until_armed = false;
  m.reset(V3(0, 0, z), 0);
  m.st.physics_active = true;
  m.st.vel = vel;
}

TEST_CASE("ground contact: disarmed drone sliding on the ground stops (Coulomb friction) and comes to rest") {
  Model m; loadModel(m, "5race");
  dropFlat(m, 40, V3(1000, 0, 0));  // 10 m/s forward, dropped from 40 cm
  double stopT = -1;
  for (int i = 0; i < 60 * 30; ++i) {
    m.step(1.0 / 60);
    groundPass(m);
    if (stopT < 0 && m.st.sleeping) stopT = m.st.time;
  }
  CHECK(stopT > 0);
  CHECK(m.st.sleeping);
  CHECK(len(m.st.vel) < 1e-2);
  CHECK(m.st.pos.x > 100);            // it did slide
  CHECK(m.st.pos.x < 1200);           // v^2/(2 mu g) ~ 7 m with mu = 0.7 (+ the bounces); no gliding forever
  CHECK(std::fabs(m.st.pos.z - m.p.body_half[2]) < 1.0);  // lies flat on the ground
  const double upz = m.st.q.rotate(V3(0, 0, 1)).z;
  CHECK(std::fabs(upz) > 0.95);       // flat (either side up after tumbling is not expected for a gentle drop)
  // stays asleep: nothing moves any more
  const V3 p0 = m.st.pos;
  for (int i = 0; i < 600; ++i) { m.step(1.0 / 60); groundPass(m); }
  CHECK(len(m.st.pos - p0) < 0.05);
}

TEST_CASE("ground contact: friction coefficient sets the stopping distance, zero friction glides") {
  auto slide = [](double mu) {
    Model m; loadModel(m, "5race");
    m.p.friction = mu;
    dropFlat(m, 2.5, V3(500, 0, 0));
    for (int i = 0; i < 60 * 20; ++i) { m.step(1.0 / 60); groundPass(m); }
    return m.st.pos.x;
  };
  const double d7 = slide(0.7), d3 = slide(0.3), d0 = slide(0.0);
  CHECK(d7 < d3);
  CHECK(d7 == doctest::Approx(500.0 * 500.0 / (2 * 0.7 * 982.0)).epsilon(0.25));  // v^2 / (2 mu g)
  CHECK(d0 > 3000);   // frictionless: still gliding after 20 s
}

TEST_CASE("ground contact: restitution bounce above the bounce threshold, none below") {
  Model m; loadModel(m, "5race");
  dropFlat(m, 30, V3(0, 0, -800));
  double vin = 0, vout = 0;
  for (int i = 0; i < 240 && vout == 0; ++i) {
    vin = std::min(vin, m.st.vel.z);
    m.step(1.0 / 240);
    groundPass(m);
    if (m.st.vel.z > 0) vout = m.st.vel.z;
  }
  CHECK(vout > 0.05 * -vin);
  CHECK(vout < 0.3 * -vin);   // ~ restitution 0.1 (corner contacts exchange some energy with rotation)
  // below the threshold (150 cm/s) it just lands
  Model m2; loadModel(m2, "5race");
  dropFlat(m2, 10, V3(0, 0, -100));
  double vmax = 0;
  for (int i = 0; i < 240; ++i) { m2.step(1.0 / 240); groundPass(m2); vmax = std::max(vmax, m2.st.vel.z); }
  CHECK(vmax < 25);
}

TEST_CASE("ground contact: an off-centre hit makes the drone tumble, then it settles") {
  Model m; loadModel(m, "5race");
  dropFlat(m, 30, V3(600, 0, -300));
  m.st.q = quatAxisAngleDeg(V3(0, 1, 0), 50) * m.st.q;   // tilted nose-down/up on touchdown
  double maxW = 0;
  for (int i = 0; i < 60 * 20; ++i) { m.step(1.0 / 120); groundPass(m); maxW = std::max(maxW, len(m.st.angvel)); }
  CHECK(maxW > 30);          // contact torque spun it
  CHECK(m.st.sleeping);
  CHECK(len(m.st.vel) < 1e-2);
  CHECK(len(m.st.angvel) < 1e-2);
}

TEST_CASE("ground contact: contact simulation is deterministic") {
  auto run = []() {
    Model m; loadModel(m, "5race");
    dropFlat(m, 100, V3(700, 200, -100));
    m.st.q = quatAxisAngleDeg(V3(1, 1, 0), 120) * m.st.q;
    for (int i = 0; i < 1200; ++i) { m.step(1.0 / 120); groundPass(m); }
    return std::vector<double>{m.st.pos.x, m.st.pos.y, m.st.pos.z, m.st.q.x, m.st.q.y, m.st.q.z, m.st.q.w, m.st.vel.x};
  };
  CHECK(run() == run());
}

TEST_CASE("ground contact: armed takeoff from the ground still works and wakes a sleeping drone") {
  Model m; loadModel(m, "5race");
  dropFlat(m, 5, V3());
  for (int i = 0; i < 120; ++i) { m.step(1.0 / 120); groundPass(m); }
  CHECK(m.st.sleeping);
  m.in.arm = true; m.in.throttle = -1.0;
  for (int i = 0; i < 60; ++i) { m.step(1.0 / 120); groundPass(m); }
  CHECK(!m.st.sleeping);
  m.in.throttle = 0.5;
  for (int i = 0; i < 240; ++i) { m.step(1.0 / 120); groundPass(m); }
  CHECK(m.st.pos.z > 200);
  CHECK(m.st.vel.z > 100);
}

TEST_CASE("C API: fpv_add_contact_ex / contact points / resting flag") {
  fpv_drone* d = fpv_create(fpv_builtin_preset("5race"));
  fpv_set_param(d, FPV_PARAM_FREEZE_UNTIL_ARMED, 0);
  double pts[24];
  CHECK(fpv_get_contact_points(d, pts, 24) == 8);
  double p[3] = {0, 0, 2.5};
  fpv_reset(d, p, 0);
  fpv_state s;
  const double n[3] = {0, 0, 1};
  for (int i = 0; i < 300; ++i) {
    fpv_step(d, 1.0 / 60);
    for (int k = 0; k < 8; ++k) {
      fpv_get_state(d, &s);
      const double lz = pts[3 * k + 2];
      const double z = s.pos_cm[2] + lz;   // flat attitude: body up = world up
      if (z < 0) { const double c[3] = {s.pos_cm[0], s.pos_cm[1], 0}; fpv_add_contact_ex(d, c, n, -z, -1, -1); }
    }
  }
  CHECK(fpv_is_resting(d) == 1);
  fpv_destroy(d);
}
