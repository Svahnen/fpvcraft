// Piecewise curve evaluation (linear / cubic Hermite / constant segments), constant extrapolation beyond the end keys.
#pragma once
#include <string>
#include <vector>
#include <map>

namespace fpv {

struct CurveKey {
  double t = 0, v = 0, arrive = 0, leave = 0;
  int interp = 1;  // 0 constant, 1 linear, 2 cubic
};
struct Curve {
  std::vector<CurveKey> keys;
  double eval(double x) const;
};
using CurveSet = std::map<std::string, Curve>;

// Parse the {"curves": {name: {"keys": [...]}}} document (data/presets/curves.json).
bool parseCurves(const std::string& json, CurveSet& out, std::string& err);

}  // namespace fpv
