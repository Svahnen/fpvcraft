// fpvcore math helpers. Native frame: left-handed, Z-up; quaternions use the Hamilton product (v' = q v q^-1), applied numerically.
#pragma once
#include <algorithm>
#include <cmath>

namespace fpv {

constexpr double kPi = 3.14159265358979323846;
constexpr double kDeg2Rad = kPi / 180.0;
constexpr double kRad2Deg = 180.0 / kPi;

struct V3 {
  double x = 0, y = 0, z = 0;
  V3() = default;
  V3(double X, double Y, double Z) : x(X), y(Y), z(Z) {}
  V3 operator+(const V3& o) const { return {x + o.x, y + o.y, z + o.z}; }
  V3 operator-(const V3& o) const { return {x - o.x, y - o.y, z - o.z}; }
  V3 operator*(double s) const { return {x * s, y * s, z * s}; }
  V3 operator-() const { return {-x, -y, -z}; }
  V3& operator+=(const V3& o) { x += o.x; y += o.y; z += o.z; return *this; }
};
inline double dot(const V3& a, const V3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline V3 cross(const V3& a, const V3& b) {
  return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
inline double len(const V3& a) { return std::sqrt(dot(a, a)); }
inline V3 normal(const V3& a) {  // safe normalise (tolerance 1e-8)
  double l = len(a);
  return l > 1e-8 ? a * (1.0 / l) : V3();
}
inline V3 vlerp(const V3& a, const V3& b, double t) { return a + (b - a) * t; }

inline double clampd(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline double lerpd(double a, double b, double t) { return a + (b - a) * t; }
inline double sgn(double v) { return v >= 0 ? 1.0 : -1.0; }
// Linear range remap (clamped / unclamped)
inline double mapRangeUnclamped(double v, double inA, double inB, double outA, double outB) {
  double d = inB - inA;
  if (d == 0) return outA;
  return outA + (v - inA) / d * (outB - outA);
}
inline double mapRangeClamped(double v, double inA, double inB, double outA, double outB) {
  double d = inB - inA;
  if (d == 0) return v >= inB ? outB : outA;
  double t = clampd((v - inA) / d, 0.0, 1.0);
  return outA + t * (outB - outA);
}
inline double safeDivide(double a, double b) { return b == 0 ? 0 : a / b; }

struct Quat {
  double x = 0, y = 0, z = 0, w = 1;
  Quat() = default;
  Quat(double X, double Y, double Z, double W) : x(X), y(Y), z(Z), w(W) {}
  // Hamilton product: (a*b).rotate(v) == a.rotate(b.rotate(v))
  Quat operator*(const Quat& b) const {
    return {w * b.x + x * b.w + y * b.z - z * b.y, w * b.y - x * b.z + y * b.w + z * b.x,
            w * b.z + x * b.y - y * b.x + z * b.w, w * b.w - x * b.x - y * b.y - z * b.z};
  }
  Quat inverse() const { return {-x, -y, -z, w}; }  // unit quats
  Quat normalized() const {
    double n = std::sqrt(x * x + y * y + z * z + w * w);
    if (n < 1e-12) return Quat();
    double i = 1.0 / n;
    return {x * i, y * i, z * i, w * i};
  }
  V3 rotate(const V3& v) const {  // rotate a vector by this quaternion
    V3 q(x, y, z);
    V3 t = cross(q, v) * 2.0;
    return v + t * w + cross(q, t);
  }
  V3 unrotate(const V3& v) const { return inverse().rotate(v); }
};
inline double dotq(const Quat& a, const Quat& b) { return a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w; }
inline Quat quatAxisAngleDeg(V3 axis, double angDeg) {  // axis need not be unit
  axis = normal(axis);
  double h = 0.5 * angDeg * kDeg2Rad, s = std::sin(h);
  return {axis.x * s, axis.y * s, axis.z * s, std::cos(h)};
}
inline Quat slerp(Quat a, Quat b, double t) {
  double c = dotq(a, b);
  if (c < 0) { b = {-b.x, -b.y, -b.z, -b.w}; c = -c; }
  double ka, kb;
  if (c > 0.9995) { ka = 1 - t; kb = t; }
  else {
    double th = std::acos(c), s = std::sin(th);
    ka = std::sin((1 - t) * th) / s; kb = std::sin(t * th) / s;
  }
  return Quat(a.x * ka + b.x * kb, a.y * ka + b.y * kb, a.z * ka + b.z * kb, a.w * ka + b.w * kb).normalized();
}
// Euler angles (pitch, yaw, roll in degrees) -> quaternion
inline Quat quatFromEuler(double pitch, double yaw, double roll) {
  double sp = std::sin(pitch * kDeg2Rad * 0.5), cp = std::cos(pitch * kDeg2Rad * 0.5);
  double sy = std::sin(yaw * kDeg2Rad * 0.5), cy = std::cos(yaw * kDeg2Rad * 0.5);
  double sr = std::sin(roll * kDeg2Rad * 0.5), cr = std::cos(roll * kDeg2Rad * 0.5);
  return {cr * sp * sy - sr * cp * cy, -cr * sp * cy - sr * cp * sy, cr * cp * sy - sr * sp * cy,
          cr * cp * cy + sr * sp * sy};
}
struct EulerDeg { double pitch = 0, yaw = 0, roll = 0; };  // degrees
inline double normalizeAxis(double a) {  // wrap an angle to (-180,180]
  a = std::fmod(a, 360.0);
  if (a > 180) a -= 360; else if (a < -180) a += 360;
  return a;
}
inline EulerDeg eulerFromQuat(const Quat& q) {  // quaternion -> Euler angles (degrees)
  const double st = q.z * q.x - q.w * q.y;
  const double yawY = 2.0 * (q.w * q.z + q.x * q.y), yawX = 1.0 - 2.0 * (q.y * q.y + q.z * q.z);
  const double thr = 0.4999995;
  EulerDeg r;
  if (st < -thr) {
    r.pitch = -90; r.yaw = std::atan2(yawY, yawX) * kRad2Deg;
    r.roll = normalizeAxis(-r.yaw - 2.0 * std::atan2(q.x, q.w) * kRad2Deg);
  } else if (st > thr) {
    r.pitch = 90; r.yaw = std::atan2(yawY, yawX) * kRad2Deg;
    r.roll = normalizeAxis(r.yaw - 2.0 * std::atan2(q.x, q.w) * kRad2Deg);
  } else {
    r.pitch = std::asin(clampd(2.0 * st, -1, 1)) * kRad2Deg;
    r.yaw = std::atan2(yawY, yawX) * kRad2Deg;
    r.roll = std::atan2(-2.0 * (q.w * q.x + q.y * q.z), 1.0 - 2.0 * (q.x * q.x + q.y * q.y)) * kRad2Deg;
  }
  return r;
}

// Deterministic PRNG (xorshift64*), part of the model state.
struct Rng {
  unsigned long long s = 0x9E3779B97F4A7C15ull;
  double next() {  // [0,1)
    s ^= s >> 12; s ^= s << 25; s ^= s >> 27;
    return ((s * 2685821657736338717ull) >> 11) * (1.0 / 9007199254740992.0);
  }
};

}  // namespace fpv
