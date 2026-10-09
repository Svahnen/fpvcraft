#include "fpvinput.h"
#include <SDL.h>
#include <algorithm>
#include <cmath>
#include <fstream>
#include <sstream>
#include <nlohmann/json.hpp>

namespace fpvinput {

using json = nlohmann::json;
static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
static float mapClamped(float v, float a, float b, float c, float d) {
  if (b == a) return v >= b ? d : c;
  return c + clampf((v - a) / (b - a), 0, 1) * (d - c);
}

Mapping::Mapping() {
  roll.axis = 0; pitch.axis = 1; throttle.axis = 2; yaw.axis = 3;
  arm_axis = 4; mode_axis = 5;
}

double normalizeCentered(float raw, const AxisCal& c) {
  float v = c.invert ? -raw : raw;
  float mn = c.invert ? -c.max : c.min, mx = c.invert ? -c.min : c.max, ce = c.invert ? -c.center : c.center;
  float a = v < ce ? mapClamped(v, mn, ce, -1, 0) : mapClamped(v, ce, mx, 0, 1);
  float s = a < 0 ? -1.f : 1.f;
  return s * mapClamped(std::fabs(a), c.deadzone, 1, 0, 1);
}

double normalizeThrottle(float raw, const AxisCal& c, bool gamepad) {
  float v = c.invert ? -raw : raw;
  float mn = c.invert ? -c.max : c.min, mx = c.invert ? -c.min : c.max;
  return mapClamped(v, gamepad ? 0.5f * (mn + mx) : mn, mx, -1, 1);
}

static float axisAt(const std::vector<float>& a, int i) { return (i >= 0 && i < (int)a.size()) ? a[i] : 0.f; }

Sticks applyMapping(const Mapping& m, const std::vector<float>& ax, const std::vector<bool>& bt) {
  Sticks s;
  if (m.roll.axis >= 0) s.roll = normalizeCentered(axisAt(ax, m.roll.axis), m.roll);
  if (m.pitch.axis >= 0) s.pitch = normalizeCentered(axisAt(ax, m.pitch.axis), m.pitch);
  if (m.yaw.axis >= 0) s.yaw = normalizeCentered(axisAt(ax, m.yaw.axis), m.yaw);
  if (m.throttle.axis >= 0) s.throttle = normalizeThrottle(axisAt(ax, m.throttle.axis), m.throttle, m.gamepad_mode);
  if (m.arm_axis >= 0) { float v = axisAt(ax, m.arm_axis); s.arm = (m.arm_invert ? -v : v) > m.arm_threshold; }
  else if (m.arm_button >= 0 && m.arm_button < (int)bt.size()) s.arm = bt[m.arm_button];
  s.mode = 1;
  if (m.mode_axis >= 0) {  // mode axis: round(axis + 1) -> 0,1,2
    float v = axisAt(ax, m.mode_axis); if (m.mode_invert) v = -v;
    int idx = (int)std::lround(v + 1.f); idx = std::min(std::max(idx, 0), 2);
    s.mode = 1 + idx;  // 1 acro, 2 angle, 3 acro3d
  } else {
    for (int i = 0; i < 3; ++i) if (m.mode_buttons[i] >= 0 && m.mode_buttons[i] < (int)bt.size() && bt[m.mode_buttons[i]]) s.mode = 1 + i;
  }
  return s;
}

static json calToJson(const AxisCal& c) {
  return {{"axis", c.axis}, {"invert", c.invert}, {"min", c.min}, {"center", c.center}, {"max", c.max}, {"deadzone", c.deadzone}};
}
static void calFromJson(const json& j, AxisCal& c) {
  c.axis = j.value("axis", c.axis); c.invert = j.value("invert", c.invert); c.min = j.value("min", c.min);
  c.center = j.value("center", c.center); c.max = j.value("max", c.max); c.deadzone = j.value("deadzone", c.deadzone);
}

std::string mappingToJson(const Mapping& m) {
  json j;
  j["device_guid"] = m.device_guid; j["device_name"] = m.device_name;
  j["roll"] = calToJson(m.roll); j["pitch"] = calToJson(m.pitch); j["yaw"] = calToJson(m.yaw); j["throttle"] = calToJson(m.throttle);
  j["gamepad_mode"] = m.gamepad_mode;
  j["arm"] = {{"axis", m.arm_axis}, {"invert", m.arm_invert}, {"threshold", m.arm_threshold}, {"button", m.arm_button}};
  j["mode"] = {{"axis", m.mode_axis}, {"invert", m.mode_invert}, {"buttons", {m.mode_buttons[0], m.mode_buttons[1], m.mode_buttons[2]}}};
  return j.dump(2);
}

bool mappingFromJson(const std::string& s, Mapping& m, std::string* err) {
  try {
    json j = json::parse(s);
    m.device_guid = j.value("device_guid", m.device_guid); m.device_name = j.value("device_name", m.device_name);
    if (j.contains("roll")) calFromJson(j["roll"], m.roll);
    if (j.contains("pitch")) calFromJson(j["pitch"], m.pitch);
    if (j.contains("yaw")) calFromJson(j["yaw"], m.yaw);
    if (j.contains("throttle")) calFromJson(j["throttle"], m.throttle);
    m.gamepad_mode = j.value("gamepad_mode", m.gamepad_mode);
    if (j.contains("arm")) {
      auto& a = j["arm"]; m.arm_axis = a.value("axis", m.arm_axis); m.arm_invert = a.value("invert", m.arm_invert);
      m.arm_threshold = a.value("threshold", m.arm_threshold); m.arm_button = a.value("button", m.arm_button);
    }
    if (j.contains("mode")) {
      auto& a = j["mode"]; m.mode_axis = a.value("axis", m.mode_axis); m.mode_invert = a.value("invert", m.mode_invert);
      if (a.contains("buttons")) for (int i = 0; i < 3 && i < (int)a["buttons"].size(); ++i) m.mode_buttons[i] = a["buttons"][i];
    }
    return true;
  } catch (const std::exception& e) {
    if (err) *err = e.what();
    return false;
  }
}

bool saveMapping(const Mapping& m, const std::string& path) {
  std::ofstream f(path);
  if (!f) return false;
  f << mappingToJson(m);
  return (bool)f;
}
bool loadMapping(const std::string& path, Mapping& out) {
  std::ifstream f(path);
  if (!f) return false;
  std::stringstream ss; ss << f.rdbuf();
  return mappingFromJson(ss.str(), out);
}

// ---- SDL device ------------------------------------------------------------------------------
static bool ensureSdl() {
  if (SDL_WasInit(SDL_INIT_JOYSTICK)) return true;
  SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
  return SDL_InitSubSystem(SDL_INIT_JOYSTICK) == 0;
}

Device::Device() {}
Device::~Device() { close(); }

std::vector<DeviceInfo> Device::list() {
  std::vector<DeviceInfo> out;
  if (!ensureSdl()) return out;
  int n = SDL_NumJoysticks();
  for (int i = 0; i < n; ++i) {
    DeviceInfo d;
    d.index = i;
    const char* nm = SDL_JoystickNameForIndex(i);
    d.name = nm ? nm : "";
    char g[64]; SDL_JoystickGetGUIDString(SDL_JoystickGetDeviceGUID(i), g, sizeof g);
    d.guid = g;
    d.axes = d.buttons = 0;
    if (SDL_Joystick* j = SDL_JoystickOpen(i)) { d.axes = SDL_JoystickNumAxes(j); d.buttons = SDL_JoystickNumButtons(j); SDL_JoystickClose(j); }
    out.push_back(d);
  }
  return out;
}

bool Device::open(int index) {
  close();
  if (!ensureSdl()) return false;
  SDL_Joystick* j = SDL_JoystickOpen(index);
  if (!j) return false;
  joy_ = j;
  const char* nm = SDL_JoystickName(j);
  name_ = nm ? nm : "";
  char g[64]; SDL_JoystickGetGUIDString(SDL_JoystickGetGUID(j), g, sizeof g);
  guid_ = g;
  axes_.assign(SDL_JoystickNumAxes(j), 0.f);
  buttons_.assign(SDL_JoystickNumButtons(j), false);
  calibBegin();
  poll();
  return true;
}
void Device::close() {
  if (joy_) SDL_JoystickClose((SDL_Joystick*)joy_);
  joy_ = nullptr;
}
bool Device::isOpen() const { return joy_ != nullptr; }

void Device::poll() {
  if (!joy_) return;
  SDL_JoystickUpdate();
  SDL_Joystick* j = (SDL_Joystick*)joy_;
  for (size_t i = 0; i < axes_.size(); ++i) {
    int v = SDL_JoystickGetAxis(j, (int)i);
    axes_[i] = v < 0 ? v / 32768.f : v / 32767.f;
  }
  for (size_t i = 0; i < buttons_.size(); ++i) buttons_[i] = SDL_JoystickGetButton(j, (int)i) != 0;
}

Sticks Device::read(const Mapping& m) {
  poll();
  return applyMapping(m, axes_, buttons_);
}

void Device::calibBegin() {
  mins_.assign(axes_.size(), 2.f);
  maxs_.assign(axes_.size(), -2.f);
}
void Device::calibTrack() {
  for (size_t i = 0; i < axes_.size() && i < mins_.size(); ++i) {
    mins_[i] = std::min(mins_[i], axes_[i]);
    maxs_[i] = std::max(maxs_[i], axes_[i]);
  }
}
int Device::calibMostMovedAxis(float min_travel) const {
  int best = -1; float bt = min_travel;
  for (size_t i = 0; i < axes_.size() && i < mins_.size(); ++i) {
    float t = maxs_[i] - mins_[i];
    if (maxs_[i] >= mins_[i] && t > bt) { bt = t; best = (int)i; }
  }
  return best;
}
bool Device::calibRange(int a, float* mn, float* mx) const {
  if (a < 0 || a >= (int)mins_.size() || maxs_[a] < mins_[a]) return false;
  *mn = mins_[a]; *mx = maxs_[a];
  return true;
}
void Device::calibCapture(Mapping& m) {
  m.device_guid = guid_; m.device_name = name_;
  AxisCal* cals[4] = {&m.roll, &m.pitch, &m.yaw, &m.throttle};
  for (AxisCal* c : cals) {
    int a = c->axis;
    if (a < 0 || a >= (int)axes_.size() || a >= (int)mins_.size()) continue;
    c->center = axes_[a];  // sticks released
    if (maxs_[a] > mins_[a]) { c->min = std::min(mins_[a], c->center); c->max = std::max(maxs_[a], c->center); }
  }
  if (m.throttle.axis >= 0 && m.throttle.axis < (int)axes_.size()) {  // throttle: centre is meaningless unless gamepad mode
    m.throttle.center = 0.5f * (m.throttle.min + m.throttle.max);
  }
}

}  // namespace fpvinput
