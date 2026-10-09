#include "rates.hpp"
#include "fpv_math.hpp"

namespace fpv {

static double p3(double v) { return v * v * v; }
static double p5(double v) { return v * v * v * v * v; }

// Betaflight applyBetaflightRates(); RC_RATE_INCREMENTAL = 14.54
double rateBetaflight_bf(double c, double rcRatePct, double ratePct, double expoPct) {
  const double a = std::fabs(c);
  if (expoPct != 0) {
    const double e = expoPct / 100.0;
    c = c * p3(a) * e + c * (1 - e);
  }
  double rc = rcRatePct / 100.0;
  if (rc > 2.0) rc += 14.54 * (rc - 2.0);
  double angle = 200.0 * rc * c;
  if (ratePct != 0) angle *= 1.0 / clampd(1.0 - a * (ratePct / 100.0), 0.01, 1.0);
  return angle;
}

// Betaflight applyActualRates()
double rateActual_bf(double c, double center, double maxDps, double expoPct) {
  const double a = std::fabs(c);
  double e = expoPct / 100.0;
  e = a * (p5(c) * e + c * (1 - e));
  const double stickMovement = std::max(0.0, maxDps - center);
  return c * center + stickMovement * e;
}

// Betaflight applyRaceFlightRates()
double rateRaceflight_bf(double c, double rcRate, double ratePct, double expoPct) {
  c = (1.0 + 0.01 * expoPct * (c * c - 1.0)) * c;
  double angle = 10.0 * rcRate * c;
  return angle * (1 + std::fabs(c) * ratePct * 0.01);
}

// Betaflight applyKissRates()
double rateKiss_bf(double c, double rcRatePct, double ratePct, double expoPct) {
  const double a = std::fabs(c);
  const double curve = expoPct / 100.0;
  const double use = 1.0 / clampd(1.0 - a * (ratePct / 100.0), 0.01, 1.0);
  const double cmd = (p3(c) * curve + c * (1 - curve)) * (rcRatePct / 1000.0);
  return clampd(2000.0 * use * cmd, -kSetpointRateLimit, kSetpointRateLimit);
}

// Betaflight applyQuickRates()
double rateQuick_bf(double c, double rcRatePct, double rate10, double expoPct) {
  const double a = std::fabs(c);
  const double rcRate = rcRatePct * 2.0;
  if (rcRate <= 0) return 0;
  const double maxDps = std::max(rate10 * 10.0, rcRate);
  const double e = expoPct / 100.0;
  const double cfg = (maxDps / rcRate - 1.0) / (maxDps / rcRate);
  const double curve = p3(a) * e + a * (1 - e);
  const double sf = 1.0 / clampd(1.0 - curve * cfg, 0.01, 1.0);
  return clampd(c * rcRate * sf, -kSetpointRateLimit, kSetpointRateLimit);
}

// Units of the (rc, srate, expo) triple per rate type:
//  BF        : rc fraction (1.0 -> 200 deg/s), srate fraction, expo fraction; no final clamp
//  Actual    : rc = centre sensitivity [deg/s], srate = max rate [deg/s], expo fraction; sign(c) with sign(0)=+1
//  Raceflight: rc [deg/s], srate PERCENT, expo PERCENT (0.01 scale inside), no clamp
//  Kiss      : rc fraction (/10 inside), srate fraction, expo fraction; clamp +-1998
//  Quick     : rc fraction (x200 inside), srate = max rate [deg/s], expo fraction; clamp +-1998
double rateEval(int type, double c, double rc, double srate, double expo) {
  switch (type) {
    case RATE_ACTUAL: {
      const double a = std::fabs(c);
      const double s = (c < 0) ? -1.0 : 1.0;
      return (rc * a + (srate - rc) * a * (p5(a) * expo + a * (1.0 - expo))) * s;
    }
    case RATE_RACEFLIGHT: {
      const double a = std::fabs(c);
      return rc * (1.0 + 0.01 * expo * (c * c - 1.0)) * c * (1.0 + a * srate * 0.01);
    }
    case RATE_KISS: return rateKiss_bf(c, rc * 100.0, srate * 100.0, expo * 100.0);
    case RATE_QUICK: {
      if (!(std::fabs(rc) > 1e-8)) return 0.0;
      const double a = std::fabs(c);
      const double rcr = rc * 200.0;
      const double m = std::max(rcr, srate);
      const double r = m / rcr;
      const double curve = p3(a) * expo + a * (1.0 - expo);
      const double x = clampd(1.0 - curve * ((r - 1.0) / r), 0.01, 1.0);
      return clampd(c * rcr / x, -kSetpointRateLimit, kSetpointRateLimit);
    }
    case RATE_BETAFLIGHT:
    default: return rateBetaflight_bf(c, rc * 100.0, srate * 100.0, expo * 100.0);
  }
}

double throttleMidExpoU(double u, double m, double e) {
  if (e == 0) return clampd(u, 0.0, 1.0);
  const double x = u - m;
  double S = (x > 0) ? (1.0 - m) : m;
  if (x == 0 || S == 0) S = 1.0;
  const double r = x / S;
  return clampd(m + x * ((1.0 - e) + e * r * r), 0.0, 1.0);
}
double throttleMidExpo(double t, double m, double e) {
  if (e == 0) return t;
  return 2.0 * throttleMidExpoU((t + 1.0) * 0.5, m, e) - 1.0;
}

}  // namespace fpv
