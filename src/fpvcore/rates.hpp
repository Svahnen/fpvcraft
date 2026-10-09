#pragma once
namespace fpv {

// ---------------------------------------------------------------------------------------------
// Rate functions, stick (-1..1) -> setpoint deg/s.
//
// Formulas follow the public open-source flight-controller firmwares (Betaflight rc.c applyXxxRates, KISS, Raceflight,
// Actual, Quickrates). Each formula lives in exactly one function below.
// The *_bf functions take configurator units (rcRate in %, rate in %, expo in %; Actual: deg/s; Quick: rcRate/2 convention).
// `rateEval()` takes the compact (rc, srate, expo) triple used by the library API (fractions, see rates.cpp) and maps it onto them.
// ---------------------------------------------------------------------------------------------
constexpr double kSetpointRateLimit = 1998.0;  // Betaflight SETPOINT_RATE_LIMIT

double rateBetaflight_bf(double stick, double rcRatePct, double ratePct, double expoPct);
double rateActual_bf(double stick, double centerDps, double maxDps, double expoPct);
double rateRaceflight_bf(double stick, double rcRate, double ratePct, double expoPct);
double rateKiss_bf(double stick, double rcRatePct, double ratePct, double expoPct);
double rateQuick_bf(double stick, double rcRatePct, double rate10Dps, double expoPct);

enum RateType { RATE_BETAFLIGHT = 0, RATE_ACTUAL = 1, RATE_RACEFLIGHT = 2, RATE_KISS = 3, RATE_QUICK = 4 };

// API triple: Betaflight-type values e.g. rc=1.0, srate=0.7, expo=0.0 (fractions). See rates.cpp for the per-type units.
double rateEval(int type, double stick, double rc, double srate, double expo);

// Throttle mid/expo curve. t in [-1,1]; returns [-1,1].
double throttleMidExpo(double t, double mid, double expo);
double throttleMidExpoU(double u01, double mid, double expo);  // 3D / unipolar variant, u in [0,1]

}  // namespace fpv
