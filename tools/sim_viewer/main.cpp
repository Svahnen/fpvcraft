// sim_viewer: desktop hand-test harness for fpvcore. SDL2 + legacy OpenGL, no other dependencies.
// Host-side world: ground plane y=0 and some pillars/gates (the host owns geometry; fpvcore only gets contacts + traces).
#include <SDL.h>
#include <SDL_opengl.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include "fpvcore.h"
#include "fpvinput.h"

// ------------------------------------------------------------------------------------------------
// World (metres, Y-up, right-handed). Boxes are axis aligned.
struct Box { double min[3], max[3]; };
static std::vector<Box> g_boxes;
static const double kRadius = 0.10;  // collision sphere radius (m)

static void addBox(double cx, double cz, double sx, double sy, double sz, double y0 = 0) {
  g_boxes.push_back({{cx - sx / 2, y0, cz - sz / 2}, {cx + sx / 2, y0 + sy, cz + sz / 2}});
}
static void buildWorld() {
  for (int i = 0; i < 12; ++i) {  // ring of pillars
    double a = i * 6.2831853 / 12;
    addBox(12 * std::sin(a), -12 * std::cos(a), 0.6, 8, 0.6);
  }
  for (int i = 0; i < 4; ++i) {  // slalom line
    addBox(-6 + i * 0, -25 - i * 8, 0.5, 5, 0.5);
    addBox(6, -29 - i * 8, 0.5, 5, 0.5);
  }
  // race gate: two posts + top bar (fly through)
  addBox(-2.5, -45, 0.3, 3, 0.3); addBox(2.5, -45, 0.3, 3, 0.3); addBox(0, -45, 5.3, 0.3, 0.3, 3);
  // tall tower and a low wall
  addBox(30, -10, 4, 20, 4); addBox(-15, 5, 10, 1.2, 0.4);
}

// Ray vs scene (ground plane y=0 + boxes), host's raycast for fpvcore. Origin in cm (native frame), dir a native unit vector.
static int raycastCb(void*, const double o_ue[3], const double d_ue[3], double maxcm, double* out) {
  double o[3], d[3];
  fpv_pos_native_to_yup(o_ue, o);
  fpv_dir_native_to_yup(d_ue, d);
  double maxm = maxcm * 0.01, best = 1e30;
  if (d[1] < -1e-9) { double t = -o[1] / d[1]; if (t >= 0) best = t; }
  for (const Box& b : g_boxes) {
    double t0 = 0, t1 = 1e30;
    bool ok = true;
    for (int a = 0; a < 3 && ok; ++a) {
      if (std::fabs(d[a]) < 1e-12) { if (o[a] < b.min[a] || o[a] > b.max[a]) ok = false; }
      else {
        double ta = (b.min[a] - o[a]) / d[a], tb = (b.max[a] - o[a]) / d[a];
        if (ta > tb) std::swap(ta, tb);
        t0 = std::max(t0, ta); t1 = std::min(t1, tb);
        if (t0 > t1) ok = false;
      }
    }
    if (ok && t0 < best) best = t0;
  }
  if (best <= maxm) { *out = best * 100.0; return 1; }
  return 0;
}

// Sphere vs world: returns up to a few contacts (normal in yup, depth m)
struct Hit { double n[3], depth; };
static int sphereHits(const double p[3], Hit* hits, int maxh) {
  int n = 0;
  if (p[1] < kRadius && n < maxh) { hits[n++] = {{0, 1, 0}, kRadius - p[1]}; }
  for (const Box& b : g_boxes) {
    if (n >= maxh) break;
    double c[3], d2 = 0;
    for (int a = 0; a < 3; ++a) { c[a] = std::fmax(b.min[a], std::fmin(p[a], b.max[a])); d2 += (p[a] - c[a]) * (p[a] - c[a]); }
    if (d2 < kRadius * kRadius) {
      double d = std::sqrt(d2);
      if (d > 1e-9) { hits[n++] = {{(p[0] - c[0]) / d, (p[1] - c[1]) / d, (p[2] - c[2]) / d}, kRadius - d}; }
      else {  // centre inside box: push out the shortest way
        int ba = 0; double bd = 1e30, sgn = 1;
        for (int a = 0; a < 3; ++a) {
          double lo = p[a] - b.min[a], hi = b.max[a] - p[a];
          if (lo < bd) { bd = lo; ba = a; sgn = -1; }
          if (hi < bd) { bd = hi; ba = a; sgn = 1; }
        }
        Hit h{{0, 0, 0}, bd + kRadius}; h.n[ba] = sgn; hits[n++] = h;
      }
    }
  }
  return n;
}

// ------------------------------------------------------------------------------------------------
// Rendering helpers
static void drawBox(const Box& b) {
  float x0 = b.min[0], y0 = b.min[1], z0 = b.min[2], x1 = b.max[0], y1 = b.max[1], z1 = b.max[2];
  glBegin(GL_QUADS);
  glColor3f(0.75f, 0.35f, 0.25f);
  glVertex3f(x0, y0, z1); glVertex3f(x1, y0, z1); glVertex3f(x1, y1, z1); glVertex3f(x0, y1, z1);
  glVertex3f(x1, y0, z0); glVertex3f(x0, y0, z0); glVertex3f(x0, y1, z0); glVertex3f(x1, y1, z0);
  glColor3f(0.55f, 0.25f, 0.18f);
  glVertex3f(x0, y0, z0); glVertex3f(x0, y0, z1); glVertex3f(x0, y1, z1); glVertex3f(x0, y1, z0);
  glVertex3f(x1, y0, z1); glVertex3f(x1, y0, z0); glVertex3f(x1, y1, z0); glVertex3f(x1, y1, z1);
  glColor3f(0.9f, 0.5f, 0.3f);
  glVertex3f(x0, y1, z1); glVertex3f(x1, y1, z1); glVertex3f(x1, y1, z0); glVertex3f(x0, y1, z0);
  glEnd();
}
static void drawGround() {
  glColor3f(0.16f, 0.2f, 0.16f);
  glBegin(GL_QUADS);
  glVertex3f(-300, 0, -300); glVertex3f(-300, 0, 300); glVertex3f(300, 0, 300); glVertex3f(300, 0, -300);
  glEnd();
  glBegin(GL_LINES);
  for (int i = -60; i <= 60; ++i) {
    bool major = (i % 10) == 0;
    glColor3f(major ? 0.45f : 0.26f, major ? 0.55f : 0.34f, major ? 0.45f : 0.26f);
    glVertex3f(i * 2.f, 0.003f, -120); glVertex3f(i * 2.f, 0.003f, 120);
    glVertex3f(-120, 0.003f, i * 2.f); glVertex3f(120, 0.003f, i * 2.f);
  }
  glEnd();
}
static void quatToMat(const double q[4], float m[16]) {  // column-major rotation matrix (RH math)
  double x = q[0], y = q[1], z = q[2], w = q[3];
  float r[9] = {(float)(1 - 2 * (y * y + z * z)), (float)(2 * (x * y + z * w)), (float)(2 * (x * z - y * w)),
                (float)(2 * (x * y - z * w)), (float)(1 - 2 * (x * x + z * z)), (float)(2 * (y * z + x * w)),
                (float)(2 * (x * z + y * w)), (float)(2 * (y * z - x * w)), (float)(1 - 2 * (x * x + y * y))};
  for (int i = 0; i < 16; ++i) m[i] = 0;
  for (int c = 0; c < 3; ++c) for (int rr = 0; rr < 3; ++rr) m[c * 4 + rr] = r[c * 3 + rr];
  m[15] = 1;
}
static void drawDroneModel(const fpv_state& s) {
  // Axes of the body in yup world = rotate the native body basis (via cam-independent conversion): use the camera-free approach:
  // forward (nose), left, up vectors computed from the quaternion in yup space with the converted rest basis.
  double bx[3] = {1, 0, 0}, by[3] = {0, 1, 0}, bz[3] = {0, 0, 1};  // native body axes expressed in the native body frame
  double cx[3], cy[3], cz[3];
  fpv_dir_native_to_yup(bx, cx); fpv_dir_native_to_yup(by, cy); fpv_dir_native_to_yup(bz, cz);  // rest basis in yup space
  glPushMatrix();
  glTranslated(s.pos_cm[0], s.pos_cm[1], s.pos_cm[2]);
  float m[16]; quatToMat(s.quat, m); glMultMatrixf(m);
  auto V = [&](const double* c, double a, double b2, double c2, const double* d, const double* e) {
    glVertex3d(c[0] * a + d[0] * b2 + e[0] * c2, c[1] * a + d[1] * b2 + e[1] * c2, c[2] * a + d[2] * b2 + e[2] * c2);
  };
  glLineWidth(3);
  glBegin(GL_LINES);
  glColor3f(0.9f, 0.9f, 0.9f);
  const double a = 0.09;  // arm half-span
  V(cx, a, a, 0, cy, cz); V(cx, -a, -a, 0, cy, cz);
  V(cx, a, -a, 0, cy, cz); V(cx, -a, a, 0, cy, cz);
  glColor3f(1.f, 0.2f, 0.2f);  // nose marker
  V(cx, 0, 0, 0, cy, cz); V(cx, 0, 0.14, 0, cy, cz);
  glColor3f(0.3f, 1.f, 0.3f);  // up marker
  V(cx, 0, 0, 0, cy, cz); V(cx, 0, 0, 0.06, cy, cz);
  glEnd();
  glPopMatrix();
}

static void seg(float x, float y, float w, float h, int s) {  // seven-segment bits: a b c d e f g
  static const float r[7][4] = {{0, 0, 1, 0.12f}, {0.88f, 0, 0.12f, 0.5f}, {0.88f, 0.5f, 0.12f, 0.5f}, {0, 0.88f, 1, 0.12f},
                                {0, 0.5f, 0.12f, 0.5f}, {0, 0, 0.12f, 0.5f}, {0, 0.44f, 1, 0.12f}};
  (void)s;
  for (int i = 0; i < 7; ++i) if (s & (1 << i)) {
    glVertex2f(x + r[i][0] * w, y + r[i][1] * h); glVertex2f(x + (r[i][0] + r[i][2]) * w, y + r[i][1] * h);
    glVertex2f(x + (r[i][0] + r[i][2]) * w, y + (r[i][1] + r[i][3]) * h); glVertex2f(x + r[i][0] * w, y + (r[i][1] + r[i][3]) * h);
  }
}
static void drawNumber(float x, float y, float h, double v, int dec) {
  // segments (top = y big?) We draw with y increasing downward in ortho: bit0 = top? Use mapping: a=top(0.88) -> handled by flipping rows.
  static const int digits[10] = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};
  char buf[32]; std::snprintf(buf, sizeof buf, "%.*f", dec, std::fabs(v));
  float w = h * 0.55f;
  glBegin(GL_QUADS);
  if (v < 0) seg(x - w * 1.2f, y, w, h, 0x40);
  for (char* c = buf; *c; ++c) {
    if (*c == '.') { glVertex2f(x + 0.1f * w, y + h * 0.85f); glVertex2f(x + 0.3f * w, y + h * 0.85f); glVertex2f(x + 0.3f * w, y + h); glVertex2f(x + 0.1f * w, y + h); x += w * 0.5f; continue; }
    seg(x, y, w, h, digits[*c - '0']);
    x += w * 1.3f;
  }
  glEnd();
}
static void rect(float x, float y, float w, float h) { glBegin(GL_QUADS); glVertex2f(x, y); glVertex2f(x + w, y); glVertex2f(x + w, y + h); glVertex2f(x, y + h); glEnd(); }
static void frameRect(float x, float y, float w, float h) { glBegin(GL_LINE_LOOP); glVertex2f(x, y); glVertex2f(x + w, y); glVertex2f(x + w, y + h); glVertex2f(x, y + h); glEnd(); }

// ------------------------------------------------------------------------------------------------
struct RateDefaults { const char* name; double rc, srate, expo; };
static const RateDefaults kRates[5] = {{"Betaflight", 1.0, 0.7, 0.0}, {"Actual", 100.0, 667.0, 0.0}, {"Raceflight", 200.0, 0.7, 0.0},
                                       {"Kiss", 1.0, 0.7, 0.0}, {"Quickrate", 1.0, 667.0, 0.0}};
static const char* kModes[5] = {"", "ACRO", "ANGLE", "ACRO3D", "HORIZON"};

enum CalibStep { CAL_NONE, CAL_ROLL, CAL_PITCH, CAL_YAW, CAL_THR, CAL_CENTER, CAL_ARM, CAL_DONE };

static std::string defaultMappingPath() {
  const char* home = std::getenv("HOME");
  std::string d = home ? std::string(home) + "/.config/fpvcore" : ".";
  std::string cmd = "mkdir -p '" + d + "'";
  if (std::system(cmd.c_str())) {}
  return d + "/mapping.json";
}

int main(int argc, char** argv) {
  std::string preset = "5race", mappingPath = defaultMappingPath(), presetFile, shotPath;
  double demoSecs = 0;
  for (int i = 1; i < argc; ++i) {
    if (!std::strcmp(argv[i], "--preset") && i + 1 < argc) preset = argv[++i];
    else if (!std::strcmp(argv[i], "--preset-file") && i + 1 < argc) presetFile = argv[++i];
    else if (!std::strcmp(argv[i], "--mapping") && i + 1 < argc) mappingPath = argv[++i];
    else if (!std::strcmp(argv[i], "--demo") && i + 1 < argc) demoSecs = std::atof(argv[++i]);   // scripted flight, fixed dt
    else if (!std::strcmp(argv[i], "--shot") && i + 1 < argc) shotPath = argv[++i];            // write PPM of the last frame
    else if (!std::strcmp(argv[i], "--help")) {
      std::printf("sim_viewer [--preset 3micro|5race|5freestyle|7longrange] [--preset-file f.json] [--mapping mapping.json]\n"
                  "Keys: W/S throttle (ramp), A/D yaw, arrows pitch/roll, Space arm, M mode, 1-5 rate type, R reset,\n"
                  "      Tab chase cam, C calibrate controller (ENTER confirms each step), F1-F4 invert thr/roll/pitch/yaw,\n"
                  "      F5 save mapping, +/- camera uptilt, Esc quit\n");
      return 0;
    }
  }
  std::string presetJson;
  if (!presetFile.empty()) {
    FILE* f = std::fopen(presetFile.c_str(), "rb");
    if (!f) { std::fprintf(stderr, "cannot open %s\n", presetFile.c_str()); return 1; }
    char buf[4096]; size_t n;
    while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) presetJson.append(buf, n);
    std::fclose(f);
  } else {
    const char* js = fpv_builtin_preset(preset.c_str());
    if (!js) { std::fprintf(stderr, "unknown preset %s\n", preset.c_str()); return 1; }
    presetJson = js;
  }
  fpv_drone* drone = fpv_create(presetJson.c_str());
  if (!drone) { std::fprintf(stderr, "fpv_create: %s\n", fpv_last_error()); return 1; }
  buildWorld();
  fpv_set_raycast(drone, raycastCb, nullptr);

  if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_JOYSTICK) != 0) { std::fprintf(stderr, "SDL: %s\n", SDL_GetError()); return 1; }
  SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 24);
  SDL_Window* win = SDL_CreateWindow("fpvcore sim_viewer", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED, 1280, 720, SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE);
  if (!win) { std::fprintf(stderr, "window: %s\n", SDL_GetError()); return 1; }
  SDL_GLContext gl = SDL_GL_CreateContext(win);
  SDL_GL_SetSwapInterval(1);

  fpvinput::Device dev;
  fpvinput::Mapping map;
  bool haveDev = false;
  auto devs = fpvinput::Device::list();
  for (auto& d : devs) std::printf("joystick %d: %s (%d axes, %d buttons) %s\n", d.index, d.name.c_str(), d.axes, d.buttons, d.guid.c_str());
  if (!devs.empty() && dev.open(0)) {
    haveDev = true;
    if (fpvinput::loadMapping(mappingPath, map)) std::printf("loaded mapping %s\n", mappingPath.c_str());
    else std::printf("no mapping file; using EdgeTX defaults (AETR). Press C to calibrate.\n");
  } else std::printf("no joystick found, keyboard only\n");

  double home[3] = {0, 0, 0};
  fpv_reset(drone, home, 0);
  int rateType = 0;
  auto applyRates = [&](int t) {
    fpv_rates r{};
    r.type = t;
    for (int i = 0; i < 3; ++i) { r.rc[i] = kRates[t].rc; r.srate[i] = kRates[t].srate; r.expo[i] = kRates[t].expo; }
    r.thr_mid = 0.5; r.thr_expo = 0;
    fpv_set_rates(drone, &r);
  };
  applyRates(rateType);
  int mode = FPV_MODE_ACRO;
  bool armKey = false, chase = false, usePad = haveDev;
  double kThr = -1, kYaw = 0, kPitch = 0, kRoll = 0;
  double uptilt = 25;
  { fpv_state s; fpv_get_state(drone, &s); uptilt = s.cam_uptilt_deg; }
  CalibStep cal = CAL_NONE;
  Uint64 last = SDL_GetPerformanceCounter();
  double fpsAcc = 0; int fpsN = 0; double titleT = 0;
  bool running = true;
  int hitFlash = 0;
  double demoT = 0;

  while (running) {
    SDL_Event e;
    while (SDL_PollEvent(&e)) {
      if (e.type == SDL_QUIT) running = false;
      if (e.type == SDL_KEYDOWN && !e.key.repeat) {
        SDL_Keycode k = e.key.keysym.sym;
        if (k == SDLK_ESCAPE) running = false;
        else if (k == SDLK_SPACE) armKey = !armKey;
        else if (k == SDLK_m) mode = mode % 3 + 1;
        else if (k >= SDLK_1 && k <= SDLK_5) { rateType = k - SDLK_1; applyRates(rateType); }
        else if (k == SDLK_r) { fpv_reset(drone, home, 0); armKey = false; kThr = -1; }
        else if (k == SDLK_TAB) chase = !chase;
        else if (k == SDLK_EQUALS || k == SDLK_PLUS) { uptilt += 2.5; fpv_set_param(drone, FPV_PARAM_CAM_UPTILT_DEG, uptilt); }
        else if (k == SDLK_MINUS) { uptilt -= 2.5; fpv_set_param(drone, FPV_PARAM_CAM_UPTILT_DEG, uptilt); }
        else if (k == SDLK_F1) map.throttle.invert = !map.throttle.invert;
        else if (k == SDLK_F2) map.roll.invert = !map.roll.invert;
        else if (k == SDLK_F3) map.pitch.invert = !map.pitch.invert;
        else if (k == SDLK_F4) map.yaw.invert = !map.yaw.invert;
        else if (k == SDLK_F5) { std::printf("%s mapping %s\n", fpvinput::saveMapping(map, mappingPath) ? "saved" : "FAILED to save", mappingPath.c_str()); }
        else if (k == SDLK_c && haveDev) { cal = CAL_ROLL; dev.calibBegin(); usePad = true; std::printf("CALIBRATION: move ROLL stick fully left/right, then press ENTER\n"); }
        else if ((k == SDLK_RETURN || k == SDLK_KP_ENTER) && cal != CAL_NONE) {
          auto assign = [&](fpvinput::AxisCal& c, const char* nm, const char* next) {
            int a = dev.calibMostMovedAxis();
            if (a < 0) { std::printf("no movement detected for %s, try again\n", nm); dev.calibBegin(); return false; }
            c.axis = a; dev.calibRange(a, &c.min, &c.max); std::printf("%s -> axis %d (travel %.2f..%.2f)\n", nm, a, c.min, c.max);
            if (next) std::printf("%s\n", next);
            return true;
          };
          if (cal == CAL_ROLL && assign(map.roll, "roll", "move PITCH stick fully, ENTER")) { cal = CAL_PITCH; dev.calibBegin(); }
          else if (cal == CAL_PITCH && assign(map.pitch, "pitch", "move YAW stick fully, ENTER")) { cal = CAL_YAW; dev.calibBegin(); }
          else if (cal == CAL_YAW && assign(map.yaw, "yaw", "move THROTTLE stick fully (min to max), ENTER")) { cal = CAL_THR; dev.calibBegin(); }
          else if (cal == CAL_THR && assign(map.throttle, "throttle", "release all sticks to centre (throttle at min), ENTER")) { cal = CAL_CENTER; }
          else if (cal == CAL_CENTER) {
            dev.poll();
            for (fpvinput::AxisCal* c : {&map.roll, &map.pitch, &map.yaw}) if (c->axis >= 0 && c->axis < (int)dev.axes().size()) c->center = dev.axes()[c->axis];
            map.device_guid = dev.guid(); map.device_name = dev.name();
            std::printf("centres stored. Flip the ARM switch to ON, ENTER (ENTER with no change keeps axis %d)\n", map.arm_axis);
            cal = CAL_ARM; dev.calibBegin();
          } else if (cal == CAL_ARM) {
            int a = dev.calibMostMovedAxis(0.8f);
            if (a >= 0) { map.arm_axis = a; map.arm_threshold = 0; map.arm_invert = dev.axes()[a] < 0; }
            std::printf("arm -> axis %d%s. Calibration done. Press F1-F4 to invert if a stick is backwards, F5 to save.\n", map.arm_axis, map.arm_invert ? " (inverted)" : "");
            cal = CAL_NONE;
          }
        }
      }
    }
    const Uint8* ks = SDL_GetKeyboardState(nullptr);
    Uint64 now = SDL_GetPerformanceCounter();
    double dt = (double)(now - last) / SDL_GetPerformanceFrequency();
    last = now;
    if (dt > 0.1) dt = 0.1;
    if (demoSecs > 0) { dt = 1.0 / 60; demoT += dt; }

    // ---- input
    fpv_input in{};
    bool padActive = false;
    if (haveDev && usePad) {
      fpvinput::Sticks s = dev.read(map);
      if (cal != CAL_NONE) dev.calibTrack();
      in.throttle = s.throttle; in.yaw = s.yaw; in.pitch = s.pitch; in.roll = s.roll; in.arm = s.arm; in.mode = s.mode;
      padActive = true;
    }
    if (!padActive) {
      auto approach = [&](double& v, double target, double rate) { double d = target - v; double m = rate * dt; v += d > m ? m : (d < -m ? -m : d); };
      if (ks[SDL_SCANCODE_W]) kThr = std::fmin(1.0, kThr + 1.0 * dt);
      if (ks[SDL_SCANCODE_S]) kThr = std::fmax(-1.0, kThr - 1.0 * dt);
      approach(kYaw, (ks[SDL_SCANCODE_D] ? 1 : 0) - (ks[SDL_SCANCODE_A] ? 1 : 0), 6);
      approach(kPitch, (ks[SDL_SCANCODE_UP] ? 1 : 0) - (ks[SDL_SCANCODE_DOWN] ? 1 : 0), 6);
      approach(kRoll, (ks[SDL_SCANCODE_RIGHT] ? 1 : 0) - (ks[SDL_SCANCODE_LEFT] ? 1 : 0), 6);
      in.throttle = kThr; in.yaw = kYaw; in.pitch = kPitch; in.roll = kRoll; in.arm = armKey; in.mode = mode;
    }
    if (padActive) { armKey = in.arm; mode = in.mode; }
    if (demoSecs > 0) {  // scripted: arm, climb, pitch forward, bank
      in = fpv_input{}; in.arm = 1; in.mode = FPV_MODE_ACRO;
      in.throttle = demoT < 0.1 ? -1 : (demoT < 1.5 ? -0.2 : -0.42);
      if (demoT > 1.5 && demoT < 2.5) in.pitch = 0.1;
      if (demoT > 3.0) in.roll = 0.15;
    }
    fpv_set_input(drone, &in);

    // ---- simulate (one frame tick), then host collision resolution
    fpv_step(drone, dt);
    for (int it = 0; it < 4; ++it) {
      fpv_state su; fpv_get_state_yup(drone, &su);
      Hit hits[8];
      int n = sphereHits(su.pos_cm, hits, 8);
      if (!n) break;
      for (int i = 0; i < n; ++i) {
        double nue[3]; fpv_dir_yup_to_native(hits[i].n, nue);
        double imp = fpv_add_contact(drone, nue, hits[i].depth * 100.0);
        if (imp > 50) hitFlash = 20;
      }
    }

    // ---- render
    fpv_state s; fpv_get_state_yup(drone, &s);
    int W, H; SDL_GetWindowSize(win, &W, &H);
    glViewport(0, 0, W, H);
    glClearColor(0.45f, 0.62f, 0.85f, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glEnable(GL_DEPTH_TEST);
    double aspect = (double)W / H;
    double eye[3], f[3], u[3];
    if (!chase) {
      std::memcpy(eye, s.cam_pos_cm, sizeof eye); std::memcpy(f, s.cam_forward, sizeof f); std::memcpy(u, s.cam_up, sizeof u);
    } else {
      double fx = s.cam_forward[0], fz = s.cam_forward[2], l = std::sqrt(fx * fx + fz * fz); if (l < 1e-6) { fx = 0; fz = -1; l = 1; }
      eye[0] = s.pos_cm[0] - fx / l * 1.6; eye[1] = s.pos_cm[1] + 0.7; eye[2] = s.pos_cm[2] - fz / l * 1.6;
      f[0] = s.pos_cm[0] - eye[0]; f[1] = s.pos_cm[1] - eye[1]; f[2] = s.pos_cm[2] - eye[2];
      double fl = std::sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]); for (double& c : f) c /= fl;
      u[0] = 0; u[1] = 1; u[2] = 0;
    }
    double fovH = chase ? 80.0 : s.cam_fov_deg;
    double tanV = std::tan(fovH * 0.5 * 3.14159265 / 180.0) / aspect, nearZ = 0.03, farZ = 800;
    glMatrixMode(GL_PROJECTION); glLoadIdentity();
    glFrustum(-nearZ * tanV * aspect, nearZ * tanV * aspect, -nearZ * tanV, nearZ * tanV, nearZ, farZ);
    glMatrixMode(GL_MODELVIEW);
    double r[3] = {f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0]};
    double rl = std::sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]); for (double& c : r) c /= rl;
    double uu[3] = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
    float vm[16] = {(float)r[0], (float)uu[0], (float)-f[0], 0, (float)r[1], (float)uu[1], (float)-f[1], 0, (float)r[2], (float)uu[2], (float)-f[2], 0, 0, 0, 0, 1};
    glLoadMatrixf(vm);
    glTranslated(-eye[0], -eye[1], -eye[2]);
    drawGround();
    for (const Box& b : g_boxes) drawBox(b);
    if (chase) drawDroneModel(s);

    // OSD
    glDisable(GL_DEPTH_TEST);
    glMatrixMode(GL_PROJECTION); glLoadIdentity(); glOrtho(0, W, H, 0, -1, 1);
    glMatrixMode(GL_MODELVIEW); glLoadIdentity();
    if (hitFlash > 0) { glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glColor4f(1, 0, 0, hitFlash / 60.f); rect(0, 0, (float)W, (float)H); glDisable(GL_BLEND); --hitFlash; }
    float sc = H / 720.f;
    double speedKmh = std::sqrt(s.linvel_cms[0] * s.linvel_cms[0] + s.linvel_cms[1] * s.linvel_cms[1] + s.linvel_cms[2] * s.linvel_cms[2]) * 3.6;
    // throttle bar (right: effective throttle after expo: use stick value)
    glColor4f(0, 0, 0, 0.5f); rect(W - 60 * sc, H - 320 * sc, 30 * sc, 260 * sc);
    float tf = (float)((in.throttle + 1) * 0.5);
    glColor3f(0.2f, 0.9f, 0.3f); rect(W - 60 * sc, H - 60 * sc - 260 * sc * tf, 30 * sc, 260 * sc * tf);
    glColor3f(1, 1, 1); frameRect(W - 60 * sc, H - 320 * sc, 30 * sc, 260 * sc);
    // stick gimbals
    auto gimbal = [&](float cx, float cy, double x, double y) {
      glColor3f(1, 1, 1); frameRect(cx - 40 * sc, cy - 40 * sc, 80 * sc, 80 * sc);
      glColor3f(1, 0.8f, 0.2f); rect(cx + (float)x * 36 * sc - 3 * sc, cy - (float)y * 36 * sc - 3 * sc, 6 * sc, 6 * sc);
    };
    gimbal(70 * sc, H - 60 * sc, in.yaw, in.throttle); gimbal(190 * sc, H - 60 * sc, in.roll, in.pitch);
    // numbers: speed (km/h), battery V, altitude m
    glColor3f(1, 1, 1);
    drawNumber(W / 2 - 220 * sc, H - 60 * sc, 34 * sc, speedKmh, 0);
    glColor3f(s.cell_v < 3.5 ? 1.f : 0.4f, s.cell_v < 3.5 ? 0.3f : 1.f, 0.4f);
    drawNumber(W / 2 - 30 * sc, H - 60 * sc, 34 * sc, s.battery_v, 1);
    glColor3f(0.6f, 0.8f, 1);
    drawNumber(W / 2 + 140 * sc, H - 60 * sc, 34 * sc, s.pos_cm[1], 1);
    glColor3f(s.armed ? 0.2f : 0.9f, s.armed ? 1.f : 0.2f, 0.2f);
    rect(14 * sc, 14 * sc, 18 * sc, 18 * sc);

    if (demoSecs > 0 && demoT >= demoSecs) {
      if (!shotPath.empty()) {
        std::vector<unsigned char> px((size_t)W * H * 3);
        glReadPixels(0, 0, W, H, GL_RGB, GL_UNSIGNED_BYTE, px.data());
        if (FILE* f = std::fopen(shotPath.c_str(), "wb")) {
          std::fprintf(f, "P6\n%d %d\n255\n", W, H);
          for (int y = H - 1; y >= 0; --y) std::fwrite(&px[(size_t)y * W * 3], 1, (size_t)W * 3, f);
          std::fclose(f);
        }
      }
      std::printf("demo end: pos %.2f %.2f %.2f m, speed %.1f km/h, V %.2f\n", s.pos_cm[0], s.pos_cm[1], s.pos_cm[2], speedKmh, s.battery_v);
      running = false;
    }
    SDL_GL_SwapWindow(win);
    fpsAcc += dt; ++fpsN; titleT += dt;
    if (titleT > 0.25) {
      char t[320];
      std::snprintf(t, sizeof t, "fpvcore | %s | %s | rates %s | thr %+.2f | %.0f km/h | alt %.1f m | %.2f V | %.0f A | %s%s | %.0f fps",
                    preset.c_str(), kModes[in.mode < 1 || in.mode > 4 ? 1 : in.mode], kRates[rateType].name, in.throttle, speedKmh, s.pos_cm[1], s.battery_v, s.amps,
                    s.armed ? "ARMED" : "disarmed", cal != CAL_NONE ? " | CALIBRATING (see terminal)" : "", fpsN / fpsAcc);
      SDL_SetWindowTitle(win, t);
      titleT = 0; fpsAcc = 0; fpsN = 0;
    }
  }
  fpv_destroy(drone);
  SDL_GL_DeleteContext(gl);
  SDL_DestroyWindow(win);
  SDL_Quit();
  return 0;
}
