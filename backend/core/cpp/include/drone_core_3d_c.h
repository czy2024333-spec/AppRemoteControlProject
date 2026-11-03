#ifndef DRONE_CORE_3D_C_H
#define DRONE_CORE_3D_C_H
#include "drone_core_c.h"
#ifdef __cplusplus
extern "C" {
#endif
typedef struct dc3_handle dc3_handle;
// 与一维dc_* ABI隔离。调用方和DLL必须使用同一份头文件，version固定2。
typedef struct dc3_config {
    uint32_t interface_version;
    double step_s, initial_position_m[3], min_altitude_m, max_altitude_m, horizontal_radius_m;
    double max_horizontal_speed_m_s, max_vertical_speed_m_s, max_yaw_rate_rad_s;
    double reference_acceleration_m_s2, max_input_ttl_s;
} dc3_config;
typedef struct dc3_state {
    uint32_t interface_version;
    int32_t mode, reason;
    uint64_t sequence;
    double simulation_time_s, monotonic_time_s;
    double position_m[3], velocity_m_s[3], quaternion_xyzw[4], angular_velocity_rad_s[3];
    double rotor_speeds_rad_s[4], commanded_rotor_speeds_rad_s[4];
    double target_position_m[3], reference_velocity_m_s[3], target_yaw_rad, reference_yaw_rate_rad_s;
} dc3_state;
DC_API int32_t dc3_default_config(dc3_config* out);
DC_API int32_t dc3_create(const dc3_config* config, dc3_handle** out);
DC_API void dc3_destroy(dc3_handle* handle);
DC_API int32_t dc3_reset(dc3_handle* handle);
DC_API int32_t dc3_set_control(dc3_handle* handle, const double world_velocity_m_s[3], double yaw_rate_rad_s, double remaining_ttl_s, double monotonic_now_s);
DC_API int32_t dc3_step(dc3_handle* handle, double monotonic_now_s);
DC_API int32_t dc3_stop(dc3_handle* handle);
DC_API int32_t dc3_protect(dc3_handle* handle, int32_t reason);
DC_API int32_t dc3_resume(dc3_handle* handle, int32_t neutral_confirmed);
DC_API int32_t dc3_get_state(const dc3_handle* handle, dc3_state* out);
// v0.3仅增加导出，不改变已有config/state布局，原dc3 ABI版本仍为2。
typedef struct dc3_environment {
    uint32_t environment_version; // 1
    double world_wind_m_s[3], compensation_acceleration_m_s2[3];
    int32_t compensation_enabled;
} dc3_environment;
DC_API int32_t dc3_set_wind(dc3_handle* handle, const double world_wind_m_s[3]);
DC_API int32_t dc3_apply_impulse(dc3_handle* handle, const double world_linear_impulse_n_s[3], const double body_angular_impulse_nm_s[3]);
DC_API int32_t dc3_enable_compensation(dc3_handle* handle, int32_t enabled);
DC_API int32_t dc3_get_environment(const dc3_handle* handle, dc3_environment* out);
#ifdef __cplusplus
}
#endif
#endif
