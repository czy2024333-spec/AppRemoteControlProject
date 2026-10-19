#ifndef DRONE_CORE_C_H
#define DRONE_CORE_C_H
#include <stdint.h>
#if defined(_WIN32) && defined(DC_BUILD_DLL)
#define DC_API __declspec(dllexport)
#elif defined(_WIN32) && defined(DC_USE_DLL)
#define DC_API __declspec(dllimport)
#else
#define DC_API
#endif
#ifdef __cplusplus
extern "C" {
#endif

typedef struct dc_handle dc_handle;
enum dc_status { DC_OK = 0, DC_INVALID_ARGUMENT = 1, DC_PROTECTED = 2, DC_INTERNAL_ERROR = 3 };
enum dc_mode { DC_HOLD = 0, DC_ACTIVE = 1, DC_PROTECTION = 2 };
enum dc_reason { DC_NONE = 0, DC_STOP = 1, DC_INPUT_TIMEOUT = 2, DC_SERVICE_PROTECTION = 3,
                 DC_REFERENCE_LIMIT = 4, DC_STATE_LIMIT = 5, DC_NUMERICAL_FAULT = 6 };
typedef struct dc_config {
    double mass_kg, gravity_m_s2, max_thrust_n, thrust_time_constant_s, step_s;
    double initial_altitude_m, min_altitude_m, max_altitude_m;
    double max_vertical_speed_m_s, reference_acceleration_m_s2, max_input_ttl_s;
    double position_gain, velocity_gain;
} dc_config;
typedef struct dc_state {
    uint32_t interface_version; /* 1 = this first C ABI; compile with this exact header */
    int32_t mode, reason;
    uint64_t sequence;
    double simulation_time_s, monotonic_time_s;
    double position_m[3], velocity_m_s[3], quaternion_xyzw[4];
    double actual_thrust_n, commanded_thrust_n, target_altitude_m, reference_vertical_speed_m_s;
} dc_state;

DC_API int32_t dc_default_config(dc_config* output);
DC_API int32_t dc_create(const dc_config* config, dc_handle** output);
DC_API void dc_destroy(dc_handle* handle);
DC_API int32_t dc_reset(dc_handle* handle);
DC_API int32_t dc_set_control(dc_handle* handle, double speed_m_s, double remaining_ttl_s, double monotonic_now_s);
DC_API int32_t dc_step(dc_handle* handle, double monotonic_now_s);
DC_API int32_t dc_stop(dc_handle* handle);
DC_API int32_t dc_protect(dc_handle* handle, int32_t reason);
DC_API int32_t dc_resume(dc_handle* handle, int32_t neutral_confirmed);
DC_API int32_t dc_get_state(const dc_handle* handle, dc_state* output);

#ifdef __cplusplus
}
#endif
#endif
