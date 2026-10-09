#include <doctest/doctest.h>
#include "fpvinput.h"

using namespace fpvinput;

TEST_CASE("axis calibration: min/center/max and deadzone") {
  AxisCal c; c.min = -0.8f; c.center = 0.1f; c.max = 0.9f;
  CHECK(normalizeCentered(0.1f, c) == doctest::Approx(0));
  CHECK(normalizeCentered(0.9f, c) == doctest::Approx(1));
  CHECK(normalizeCentered(-0.8f, c) == doctest::Approx(-1));
  CHECK(normalizeCentered(0.5f, c) == doctest::Approx(0.5));
  CHECK(normalizeCentered(2.0f, c) == doctest::Approx(1));  // clamped
  c.deadzone = 0.1f;
  CHECK(normalizeCentered(0.1f + 0.05f * 0.8f, c) == doctest::Approx(0));   // inside deadzone
  CHECK(normalizeCentered(0.9f, c) == doctest::Approx(1));                   // rescaled, no jump
  c.invert = true;
  CHECK(normalizeCentered(-0.9f, c) > 0.9);
}
TEST_CASE("throttle mapping") {
  AxisCal c; c.min = -1; c.max = 1;
  CHECK(normalizeThrottle(-1, c, false) == doctest::Approx(-1));
  CHECK(normalizeThrottle(0, c, false) == doctest::Approx(0));
  CHECK(normalizeThrottle(0, c, true) == doctest::Approx(-1));  // gamepad mode: centre = idle
  CHECK(normalizeThrottle(1, c, true) == doctest::Approx(1));
}
TEST_CASE("mapping: axes, arm and mode switches, json round trip") {
  Mapping m;
  m.roll.invert = true; m.roll.center = 0.02f; m.arm_threshold = 0.0f;
  std::vector<float> ax = {0.5f, -0.5f, 1.0f, 0.0f, 1.0f, 0.0f};
  Sticks s = applyMapping(m, ax, {});
  CHECK(s.throttle == doctest::Approx(1));
  CHECK(s.arm == 1);
  CHECK(s.mode == 2);  // mode axis 0.0 -> round(1) -> index 1 -> angle
  CHECK(s.roll < 0);
  ax[4] = -1; ax[5] = 1;
  s = applyMapping(m, ax, {});
  CHECK(s.arm == 0); CHECK(s.mode == 3);
  Mapping r;
  std::string err;
  CHECK(mappingFromJson(mappingToJson(m), r, &err));
  CHECK(r.roll.invert); CHECK(r.roll.center == doctest::Approx(0.02)); CHECK(r.mode_axis == 5);
}
