/*
 * fpvcore - engine-agnostic FPV quadcopter flight model (C ABI).
 *
 * INTERNAL CONVENTIONS ("native" frame): centimetres, kilograms, degrees, seconds, left-handed, Z up.
 * World axes: +X forward, +Y right, +Z up (arbitrary but fixed; the model only cares about Z = up).
 * Drone body frame: +Y = nose, +X = LEFT, +Z = up. A quaternion maps body -> world (v_world = q * v_body).
 * Positions/velocities in the state are cm and cm/s; angular velocity deg/s. Helpers at the bottom convert to
 * metres, Y-up, right-handed (Minecraft / OpenGL) - or use fpv_get_state_yup().
 * See docs/API.md for details. All functions are thread-compatible (one fpv_drone per thread at a time).
 */
#ifndef FPVCORE_H
#define FPVCORE_H

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32) && defined(FPV_SHARED)
#  ifdef FPV_BUILDING
#    define FPV_API __declspec(dllexport)
#  else
#    define FPV_API __declspec(dllimport)
#  endif
#else
#  define FPV_API __attribute__((visibility("default")))
#endif

#define FPV_VERSION "0.1.0"

typedef struct fpv_drone fpv_drone;

enum { FPV_MODE_ACRO = 1, FPV_MODE_ANGLE = 2, FPV_MODE_ACRO3D = 3, FPV_MODE_HORIZON = 4 };
enum { FPV_RATES_BETAFLIGHT = 0, FPV_RATES_ACTUAL = 1, FPV_RATES_RACEFLIGHT = 2, FPV_RATES_KISS = 3, FPV_RATES_QUICKRATE = 4 };

/* Sticks are post-calibration values in [-1, 1] (use the optional fpvinput module for raw devices).
 * throttle: -1 = idle, +1 = full (0..1 mapped to -1..1). arm: arm switch (1 = armed position); the drone arms when
 * the switch is on and throttle < -0.5. mode: FPV_MODE_*. */
typedef struct {
    double throttle, yaw, pitch, roll;
    int arm;
    int mode;
} fpv_input;

/* Rate profile, order roll, pitch, yaw. Values are fractions in the Betaflight convention (default: rc 1.0, srate 0.7, expo 0); other types use their own units, see docs/API.md. */
typedef struct {
    int type;               /* FPV_RATES_* */
    double rc[3], srate[3], expo[3];
    double thr_mid, thr_expo;
} fpv_rates;

typedef struct {
    double pos_cm[3];       /* centre of mass, cm, world */
    double quat[4];         /* x,y,z,w body->world */
    double linvel_cms[3];   /* cm/s world */
    double angvel_degs[3];  /* residual (collision/attitude-controller torque) angular velocity, deg/s world; stick rotation is kinematic */
    double rpm[4];          /* motor RPM (model has one RPM for all four motors) */
    double battery_v;       /* pack voltage (cells * cell_v) */
    double cell_v;
    double amps, mah_used;
    double thrust;          /* thrust magnitude, force units kg*cm/s^2 */
    double rate_setpoint_degs[3]; /* roll, pitch, yaw setpoint in deg/s */
    int armed, can_fly, mode;
    double time;            /* simulated seconds since reset */
    /* cosmetic spring attitude (camera wobble): quaternion relative to the body, identity if disabled */
    double pid_quat[4];
    /* FPV camera */
    double cam_pos_cm[3];
    double cam_quat[4];     /* camera frame: +Y forward, +Z up, +X left (same convention as the body) */
    double cam_forward[3], cam_up[3];
    double cam_uptilt_deg, cam_fov_deg;
    double prop_damage[4];
    int prop_destroyed;
} fpv_state;

/* ---- lifecycle ------------------------------------------------------------------------------ */
FPV_API const char* fpv_version(void);
/* preset_json: contents of a data/presets/drone_*.json (or base preset object). Curves are built in. NULL on error. */
FPV_API fpv_drone* fpv_create(const char* preset_json);
/* curves_json may be NULL (use built-in curves, = data/presets/curves.json). */
FPV_API fpv_drone* fpv_create_ex(const char* preset_json, const char* curves_json);
FPV_API void fpv_destroy(fpv_drone*);
FPV_API const char* fpv_last_error(void);
/* Built-in presets (ids): "3micro" (3in-micro), "5race" (5in-race), "5freestyle" (5in-freestyle), "7longrange" (7in-longrange). Returns JSON text (static) or NULL. */
FPV_API const char* fpv_builtin_preset(const char* id);

/* ---- control ------------------------------------------------------------------------------- */
FPV_API void fpv_set_input(fpv_drone*, const fpv_input*);
FPV_API void fpv_set_rates(fpv_drone*, const fpv_rates*);
FPV_API void fpv_get_rates(fpv_drone*, fpv_rates*);
/* Advance by dt seconds (one frame tick; variable dt is fine, clamped to FPV_PARAM_MAX_DT). The 120 Hz cosmetic
 * spring runs on an internal accumulator. Contacts from the host go through fpv_add_contact AFTER stepping. */
FPV_API void fpv_step(fpv_drone*, double dt);

/* ---- state --------------------------------------------------------------------------------- */
FPV_API void fpv_get_state(const fpv_drone*, fpv_state*);             /* native units */
FPV_API void fpv_get_state_yup(const fpv_drone*, fpv_state*);          /* metres, m/s, Y-up right-handed (see converters) */
FPV_API void fpv_set_state(fpv_drone*, const fpv_state*);              /* pos, quat, linvel, angvel (native units) */
/* Reset: place at pos (cm), nose pointing at heading_deg (0 = world +X, positive toward +Y), zero velocity, fresh battery,
 * disarmed. */
FPV_API void fpv_reset(fpv_drone*, const double pos_cm[3], double heading_deg);

/* ---- environment: ground traces and collisions (host owns the world) ----------------------- */
/* Option A: callback. Return 1 and set *dist_cm if the ray hits within max_dist_cm. Used for the ground effect trace
 * (70 cm along the drone's -up) and the turtle-mode trace (14.5 cm along +up). */
typedef int (*fpv_raycast_fn)(void* user, const double origin_cm[3], const double dir[3], double max_dist_cm, double* dist_cm);
FPV_API void fpv_set_raycast(fpv_drone*, fpv_raycast_fn, void* user);
/* Option B (JNI-friendly): inject results before each fpv_step; negative = no hit. Persists until changed. */
FPV_API void fpv_set_trace_results(fpv_drone*, double down_hit_cm, double up_hit_cm);
FPV_API void fpv_set_wind(fpv_drone*, const double wind_cms[3]);
/* Report a penetrating contact: normal (world, pointing out of the surface into free space), penetration depth in cm.
 * The library pushes the drone out, removes the inward velocity (restitution/friction params) and fires the hit event
 * (spin on hard impacts). Returns the normal impulse (kg*cm/s). */
FPV_API double fpv_add_contact(fpv_drone*, const double normal[3], double depth_cm);
/* If the host resolved the collision itself (and used fpv_set_state): just fire the hit event with this impulse. */
FPV_API void fpv_report_hit(fpv_drone*, const double normal[3], double impulse);
/* Rigid-body point contact. point = world contact point (cm, native frame), normal out of the surface, depth in cm (push-out along the normal),
 * friction = Coulomb coefficient for this contact (already combined with the surface; < 0 = FPV_PARAM_FRICTION), restitution (< 0 = param).
 * Normal impulse + restitution (above the bounce threshold), Coulomb friction (static cone, then kinetic) against the tangential
 * velocity of the point, and the contact torque (the drone tumbles). Call once per touching body point, after fpv_step. Returns the normal impulse. */
FPV_API double fpv_add_contact_ex(fpv_drone*, const double point[3], const double normal[3], double depth_cm, double friction, double restitution);
/* Body-local collision hull (the 8 corners of the contact box, native body frame, cm: x nose, y right, z up). Writes up to max points (3 doubles each),
 * returns the number. Test each point (a small sphere) against the world and report penetrations with fpv_add_contact_ex. */
FPV_API int fpv_get_contact_points(const fpv_drone*, double* out_cm, int max_points);
/* 1 once the disarmed drone lies still on a surface (velocities are held at 0 until it is armed or the support disappears). */
FPV_API int fpv_is_resting(const fpv_drone*);

/* Optional area-based drag . n samples of local-frame direction, projected area, and force direction (3 doubles each). */
FPV_API void fpv_set_drag_tables(fpv_drone*, int n, const double* dirs, const double* area, const double* force_dirs);

/* ---- parameters ---------------------------------------------------------------------------- */
enum {
    FPV_PARAM_MAX_DT = 1,           /* seconds, default 1/30 */
    FPV_PARAM_RESTITUTION = 2,      /* default 0.1 */
    FPV_PARAM_FRICTION = 3,         /* kinetic Coulomb coefficient, default 0.7 (static = 1.1x); low-friction airframes can set it to 0 */
    FPV_PARAM_CAM_UPTILT_DEG = 4,   /* default = preset camera_tilt_deg */
    FPV_PARAM_CAM_FOV_DEG = 5,      /* default 120 */
    FPV_PARAM_SIMULATE_BATTERY = 6, /* 0/1, default 0 */
    FPV_PARAM_FREEZE_UNTIL_ARMED = 7, /* 0/1, default 1 (physics starts on the first arm) */
    FPV_PARAM_GRAVITY = 8,          /* m/s^2 override */
    FPV_PARAM_PID_CAMERA = 9,       /* 0/1: camera follows cosmetic spring, default 1 */
    FPV_PARAM_PHYSICS_ACTIVE = 10,  /* 0/1 force physics on/off */
    FPV_PARAM_SEED = 11,            /* PRNG seed (integer) */
    /* calibration multipliers, 1 = nominal: air density, thrust force, drag force */
    FPV_PARAM_RHO_SCALE = 12,
    FPV_PARAM_THRUST_SCALE = 13,
    FPV_PARAM_DRAG_SCALE = 14,
    FPV_PARAM_INFLOW_SCALE = 15,    /* scales the inflow term of the thrust formula (exit speed * induced velocity * air_speed_scale) */
    FPV_PARAM_SAG_SCALE = 16,       /* how much of the default-mode (non-simulated) voltage sag reaches the RPM: 0 (default) .. 1 (full sag) */
    FPV_PARAM_GE_SCALE = 17,        /* scales the ground-effect force */
    FPV_PARAM_BOUNCE_THRESHOLD = 19, /* cm/s: impacts slower than this get restitution 0 (default 200) */
    FPV_PARAM_REST_TIME = 20,       /* s of touching + slow + disarmed before the drone sleeps (default 0.25) */
    FPV_PARAM_DRAG_WASH_SCALE = 18  /* scales the prop-wash area reduction of the baked-table drag (1 = full reduction; default 0 = none) */
};
FPV_API void fpv_set_param(fpv_drone*, int param, double value);

/* ---- rates (stateless, usable without a drone) ---------------------------------------------- */
FPV_API double fpv_rate(int type, double stick, double rc, double srate, double expo);

/* ---- coordinate conversion helpers -------------------------------------------------------------
 * native (cm, left-handed, X fwd / Y right / Z up)  <->  YUP (m, right-handed, +X right/east, +Y up, -Z forward/north).
 * Mapping: x_yup = y_ue, y_yup = z_ue, z_yup = -x_ue  (position scaled by 0.01). A Minecraft block = 1 m, with
 * +X east, +Y up, +Z south, so native forward = MC north. Rotate your world mapping if you want another heading. */
FPV_API void fpv_pos_native_to_yup(const double cm[3], double m[3]);   /* also velocities (cm/s -> m/s) */
FPV_API void fpv_pos_yup_to_native(const double m[3], double cm[3]);
FPV_API void fpv_dir_native_to_yup(const double d[3], double out[3]);  /* unit-less direction, no scaling */
FPV_API void fpv_dir_yup_to_native(const double d[3], double out[3]);
FPV_API void fpv_quat_native_to_yup(const double q[4], double out[4]); /* x,y,z,w (handedness flip handled) */
FPV_API void fpv_quat_yup_to_native(const double q[4], double out[4]);
FPV_API void fpv_angvel_native_to_yup(const double w[3], double out[3]); /* axial vector, deg/s or rad/s unchanged units */

#ifdef __cplusplus
}
#endif
#endif
