#include "curve.hpp"
#include <nlohmann/json.hpp>

namespace fpv {

double Curve::eval(double x) const {
  if (keys.empty()) return 0;
  if (x <= keys.front().t) return keys.front().v;
  if (x >= keys.back().t) return keys.back().v;
  size_t i = 0;
  while (i + 1 < keys.size() && x >= keys[i + 1].t) ++i;
  const CurveKey &a = keys[i], &b = keys[i + 1];
  double dt = b.t - a.t;
  if (dt <= 0) return b.v;
  double u = (x - a.t) / dt;
  if (a.interp == 0) return a.v;
  if (a.interp == 1) return a.v + (b.v - a.v) * u;
  double m0 = a.leave * dt, m1 = b.arrive * dt, u2 = u * u, u3 = u2 * u;
  return (2 * u3 - 3 * u2 + 1) * a.v + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * b.v + (u3 - u2) * m1;
}

bool parseCurves(const std::string& json, CurveSet& out, std::string& err) {
  try {
    auto j = nlohmann::json::parse(json);
    const auto& c = j.contains("curves") ? j["curves"] : j;
    for (auto it = c.begin(); it != c.end(); ++it) {
      if (!it.value().is_object() || !it.value().contains("keys")) continue;
      Curve cv;
      for (const auto& k : it.value()["keys"]) {
        CurveKey ck;
        ck.t = k.value("t", 0.0);
        ck.v = k.value("v", 0.0);
        ck.arrive = k.value("in_tangent", 0.0);
        ck.leave = k.value("out_tangent", 0.0);
        std::string in = k.value("interp", "Linear");
        ck.interp = in == "Cubic" ? 2 : (in == "Constant" ? 0 : 1);
        cv.keys.push_back(ck);
      }
      out[it.key()] = std::move(cv);
    }
    return true;
  } catch (const std::exception& e) {
    err = std::string("curves json: ") + e.what();
    return false;
  }
}

}  // namespace fpv
