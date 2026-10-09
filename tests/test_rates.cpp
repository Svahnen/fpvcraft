#include <doctest/doctest.h>
#include "fpvcore.h"
#include "rates.hpp"

using namespace fpv;
// Reference values computed by hand from the Betaflight firmware formulas (src/main/fc/rc.c, v4.4).

TEST_CASE("betaflight rates") {
  // default profile rc 100 / super 70 / expo 0: max = 200*1/(1-0.7)
  CHECK(rateBetaflight_bf(1.0, 100, 70, 0) == doctest::Approx(666.6667).epsilon(1e-6));
  CHECK(rateBetaflight_bf(-1.0, 100, 70, 0) == doctest::Approx(-666.6667).epsilon(1e-6));
  CHECK(rateBetaflight_bf(0.5, 100, 70, 0) == doctest::Approx(100.0 / 0.65).epsilon(1e-9));
  CHECK(rateBetaflight_bf(0.0, 100, 70, 20) == 0.0);
  // rc 0.8, super 0.7, expo 0.2, stick 0.5: c' = 0.5*(0.125*0.2)+0.5*0.8 = 0.4125; 200*0.8*0.4125 = 66; /0.65
  CHECK(rateBetaflight_bf(0.5, 80, 70, 20) == doctest::Approx(66.0 / 0.65).epsilon(1e-9));
  // rc > 2 increment: rc 2.5 -> 2.5 + 14.54*0.5 = 9.77; stick 1, no super: 200*9.77
  CHECK(rateBetaflight_bf(1.0, 250, 0, 0) == doctest::Approx(1954.0).epsilon(1e-9));
}
TEST_CASE("actual rates") {
  CHECK(rateActual_bf(1.0, 100, 667, 0) == doctest::Approx(667.0));
  CHECK(rateActual_bf(0.5, 100, 667, 0) == doctest::Approx(50 + 567 * 0.25));
  CHECK(rateActual_bf(0.0, 100, 667, 50) == 0.0);
  CHECK(rateActual_bf(1.0, 100, 667, 100) == doctest::Approx(667.0));  // expo keeps endpoints
}
TEST_CASE("raceflight rates") {
  CHECK(rateRaceflight_bf(1.0, 20, 70, 0) == doctest::Approx(340.0));
  CHECK(rateRaceflight_bf(0.5, 20, 70, 0) == doctest::Approx(10 * 20 * 0.5 * (1 + 0.5 * 0.7)));
}
TEST_CASE("kiss rates") {
  CHECK(rateKiss_bf(1.0, 100, 70, 0) == doctest::Approx(666.6667).epsilon(1e-6));
  CHECK(rateKiss_bf(0.5, 100, 70, 0) == doctest::Approx(2000.0 / 0.65 * 0.05).epsilon(1e-9));
  CHECK(rateKiss_bf(1.0, 100, 99, 0) <= kSetpointRateLimit);  // clamped to 1998
}
TEST_CASE("quick rates") {
  // rcRate 100 -> 200 dps centre, max 667: stick 1, expo 0 reproduces max (to rounding)
  CHECK(rateQuick_bf(1.0, 100, 66.7, 0) == doctest::Approx(667.0).epsilon(1e-3));
  CHECK(rateQuick_bf(0.0, 100, 66.7, 0) == 0.0);
}
TEST_CASE("API convention dispatcher") {
  for (int t = 0; t < 5; ++t) {
    double hi = fpv_rate(t, 1.0, 1.0, 0.7, 0.0), lo = fpv_rate(t, -1.0, 1.0, 0.7, 0.0);
    CHECK(hi == doctest::Approx(-lo));
  }
  CHECK(fpv_rate(FPV_RATES_BETAFLIGHT, 1.0, 1.0, 0.7, 0.0) == doctest::Approx(666.667).epsilon(1e-5));
  CHECK(fpv_rate(FPV_RATES_KISS, 1.0, 1.0, 0.7, 0.0) == doctest::Approx(666.667).epsilon(1e-5));
}
TEST_CASE("throttle mid/expo") {
  CHECK(throttleMidExpo(0.3, 0.5, 0.0) == 0.3);
  CHECK(throttleMidExpo(-1, 0.5, 0.5) == doctest::Approx(-1));
  CHECK(throttleMidExpo(1, 0.5, 0.5) == doctest::Approx(1));
  CHECK(throttleMidExpo(0, 0.5, 0.7) == doctest::Approx(0));  // stick centre -> mid
}

// Reference values evaluated by hand/python from the public firmware formulas, in the library's (rc, srate, expo) argument units.
TEST_CASE("rate dispatcher, reference values") {
  const double eps = 1e-9;
  CHECK(rateEval(RATE_BETAFLIGHT, 0.5, 0.65, 0.7, 0.3) == doctest::Approx(73.75).epsilon(eps));
  CHECK(rateEval(RATE_BETAFLIGHT, -1.0, 3.0, 0.8, 0.0) == doctest::Approx(-17540.0).epsilon(eps));  // rc>2 increment, no final clamp
  CHECK(rateEval(RATE_BETAFLIGHT, 0.25, 1.0, 0.0, 0.5) == doctest::Approx(25.390625).epsilon(eps));
  CHECK(rateEval(RATE_ACTUAL, 0.5, 200, 670, 0.2) == doctest::Approx(195.46875).epsilon(eps));
  CHECK(rateEval(RATE_ACTUAL, -1.0, 100, 800, 0.0) == doctest::Approx(-800.0).epsilon(eps));
  CHECK(rateEval(RATE_ACTUAL, 0.0, 100, 800, 0.3) == 0.0);
  CHECK(rateEval(RATE_RACEFLIGHT, 0.5, 200, 70, 40) == doctest::Approx(94.5).epsilon(eps));   // srate, expo in PERCENT
  CHECK(rateEval(RATE_RACEFLIGHT, -1.0, 300, 50, 0) == doctest::Approx(-450.0).epsilon(eps));
  CHECK(rateEval(RATE_KISS, 0.5, 1.0, 0.7, 0.3) == doctest::Approx(119.230769230769).epsilon(1e-9));
  CHECK(rateEval(RATE_KISS, 1.0, 2.0, 0.9, 0) == doctest::Approx(1998.0));                    // clamp
  CHECK(rateEval(RATE_QUICK, 0.5, 0.65, 800, 0.3) == doctest::Approx(96.2294702752718).epsilon(1e-9));
  CHECK(rateEval(RATE_QUICK, -1.0, 1.0, 1500, 0.5) == doctest::Approx(-1500.0).epsilon(1e-9));
  CHECK(rateEval(RATE_QUICK, 1.0, 1.0, 50, 0) == doctest::Approx(200.0));                      // max < centre: centre wins
  CHECK(rateEval(RATE_QUICK, 1.0, 0.0, 800, 0) == 0.0);                                         // |rc| <= 1e-8 -> 0
}
