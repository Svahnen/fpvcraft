// fpvinput - optional SDL2 joystick/game-controller input with per-axis calibration, for fpvcore.
// RC transmitters (EdgeTX/OpenTX "USB joystick" mode) show up as plain HID joysticks.
#pragma once
#include <string>
#include <vector>

namespace fpvinput {

// One calibrated axis (raw values are SDL axis values normalised to -1..1).
struct AxisCal {
  int axis = -1;          // SDL joystick axis index, -1 = unassigned
  bool invert = false;
  float min = -1, center = 0, max = 1;
  float deadzone = 0;     // 0..1, applied after centring, rescaled (no jump)
};

// Maps raw device state -> fpv_input-style sticks.
struct Mapping {
  std::string device_guid, device_name;
  AxisCal roll, pitch, yaw, throttle;
  bool gamepad_mode = false;   // throttle centre = stick centre (spring-loaded throttle)
  // Arm switch: axis above `arm_threshold` (after invert) or a button. mode switch: 3-position axis or 3 buttons.
  int arm_axis = -1; bool arm_invert = false; float arm_threshold = 0.0f; int arm_button = -1;
  int mode_axis = -1; bool mode_invert = false; int mode_buttons[3] = {-1, -1, -1};  // acro, angle, acro3d
  Mapping();               // EdgeTX default: A=0 E=1 T=2 R=3, arm=axis 4, mode=axis 5
};

struct Sticks { double throttle = -1, yaw = 0, pitch = 0, roll = 0; int arm = 0; int mode = 1; };

// ---- pure functions (no SDL needed) ------------------------------------------------------
// piecewise-linear around centre then rescaled deadzone. Returns -1..1.
double normalizeCentered(float raw, const AxisCal& c);
// Throttle: map [min(or centre in gamepad mode), max] -> [-1, 1], no deadzone.
double normalizeThrottle(float raw, const AxisCal& c, bool gamepad_mode);
Sticks applyMapping(const Mapping& m, const std::vector<float>& axes, const std::vector<bool>& buttons);

std::string mappingToJson(const Mapping& m);
bool mappingFromJson(const std::string& json, Mapping& out, std::string* err = nullptr);
bool saveMapping(const Mapping& m, const std::string& path);
bool loadMapping(const std::string& path, Mapping& out);

// ---- SDL-backed device ----------------------------------------------------------------------
struct DeviceInfo { int index; std::string name, guid; int axes, buttons; };

class Device {
 public:
  Device();
  ~Device();
  static std::vector<DeviceInfo> list();     // initialises SDL joystick subsystem on first use
  bool open(int index);
  void close();
  bool isOpen() const;
  const std::string& name() const { return name_; }
  const std::string& guid() const { return guid_; }
  void poll();                               // refresh raw state (SDL_JoystickUpdate)
  const std::vector<float>& axes() const { return axes_; }       // normalised -1..1
  const std::vector<bool>& buttons() const { return buttons_; }
  Sticks read(const Mapping& m);             // poll + applyMapping

  // ---- interactive calibration (call poll() each frame) ----
  void calibBegin();                         // reset min/max trackers
  void calibTrack();                         // update min/max from the current raw state; call every frame while the user moves sticks
  void calibCapture(Mapping& m);             // call with sticks released: stores centre, tracked min/max, auto-detects moved axes
  // Wizard helper: which axis moved most since calibBegin (for "move the throttle stick" assignment); -1 if none.
  int calibMostMovedAxis(float min_travel = 0.5f) const;
  bool calibRange(int axis, float* mn, float* mx) const;   // tracked travel of one axis since calibBegin

 private:
  void* joy_ = nullptr;
  std::string name_, guid_;
  std::vector<float> axes_, mins_, maxs_;
  std::vector<bool> buttons_;
};

}  // namespace fpvinput
