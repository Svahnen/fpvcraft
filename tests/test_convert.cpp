#include <doctest/doctest.h>
#include "fpvcore.h"
#include "fpv_math.hpp"

using namespace fpv;

TEST_CASE("native <-> Y-up conversion is consistent for rotations") {
  Quat q = quatFromEuler(20, 70, -35);
  V3 v(0.3, -1.2, 2.0);
  V3 vr = q.rotate(v);
  double qa[4] = {q.x, q.y, q.z, q.w}, qy[4];
  fpv_quat_native_to_yup(qa, qy);
  double va[3] = {v.x, v.y, v.z}, vy[3], vry[3], expect[3];
  fpv_dir_native_to_yup(va, vy);
  double ra[3] = {vr.x, vr.y, vr.z};
  fpv_dir_native_to_yup(ra, expect);
  Quat Q(qy[0], qy[1], qy[2], qy[3]);
  V3 got = Q.rotate(V3(vy[0], vy[1], vy[2]));
  CHECK(got.x == doctest::Approx(expect[0]));
  CHECK(got.y == doctest::Approx(expect[1]));
  CHECK(got.z == doctest::Approx(expect[2]));
  double back[4];
  fpv_quat_yup_to_native(qy, back);
  for (int i = 0; i < 4; ++i) CHECK(back[i] == doctest::Approx(qa[i]));
  double p[3] = {100, 200, 300}, m[3], p2[3];
  fpv_pos_native_to_yup(p, m);
  CHECK(m[0] == doctest::Approx(2.0)); CHECK(m[1] == doctest::Approx(3.0)); CHECK(m[2] == doctest::Approx(-1.0));
  fpv_pos_yup_to_native(m, p2);
  CHECK(p2[0] == doctest::Approx(100)); CHECK(p2[1] == doctest::Approx(200)); CHECK(p2[2] == doctest::Approx(300));
}

TEST_CASE("Euler angles <-> quaternion round trip") {
  Quat q = quatFromEuler(15, -40, 25);
  EulerDeg r = eulerFromQuat(q);
  CHECK(r.pitch == doctest::Approx(15)); CHECK(r.yaw == doctest::Approx(-40)); CHECK(r.roll == doctest::Approx(25));
}
