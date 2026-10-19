#include "drone_core_c.h"
#include "drone_core.hpp"
#include <stdexcept>

struct dc_handle { explicit dc_handle(drone::Config c) : core(c) {} drone::Core core; };
namespace {
// 【DLL边界】把C++异常转换为C错误码，避免异常穿过DLL进入UE或Python。
template<class Function> int32_t Guard(Function function) noexcept {
    try { function(); return DC_OK; }
    catch (const std::invalid_argument&) { return DC_INVALID_ARGUMENT; }
    catch (const std::logic_error&) { return DC_PROTECTED; }
    catch (...) { return DC_INTERNAL_ERROR; }
}
drone::Config Convert(const dc_config& c) {
    return {c.mass_kg, c.gravity_m_s2, c.max_thrust_n, c.thrust_time_constant_s, c.step_s,
            c.initial_altitude_m, c.min_altitude_m, c.max_altitude_m, c.max_vertical_speed_m_s,
            c.reference_acceleration_m_s2, c.max_input_ttl_s, c.position_gain, c.velocity_gain};
}
}
extern "C" {
int32_t dc_default_config(dc_config* out) {
    if (!out) return DC_INVALID_ARGUMENT;
    const drone::Config c;
    *out = {c.mass_kg, c.gravity_m_s2, c.max_thrust_n, c.thrust_time_constant_s, c.step_s,
            c.initial_altitude_m, c.min_altitude_m, c.max_altitude_m, c.max_vertical_speed_m_s,
            c.reference_acceleration_m_s2, c.max_input_ttl_s, c.position_gain, c.velocity_gain};
    return DC_OK;
}
int32_t dc_create(const dc_config* config, dc_handle** out) {
    // 【所有权】每次创建独立实例，失败时输出空句柄；由同一DLL的dc_destroy释放。
    if (!out) return DC_INVALID_ARGUMENT;
    *out = nullptr;
    return Guard([&] { *out = new dc_handle(config ? Convert(*config) : drone::Config{}); });
}
void dc_destroy(dc_handle* handle) { delete handle; }
int32_t dc_reset(dc_handle* h) {
    if (!h) return DC_INVALID_ARGUMENT;
    return Guard([&] { h->core.Reset(); });
}
int32_t dc_set_control(dc_handle* h, double speed, double ttl, double now) {
    if (!h) return DC_INVALID_ARGUMENT;
    return Guard([&] { h->core.SetControl(speed, ttl, now); });
}
int32_t dc_step(dc_handle* h, double now) {
    if (!h) return DC_INVALID_ARGUMENT;
    return Guard([&] { h->core.Step(now); });
}
int32_t dc_stop(dc_handle* h) {
    if (!h) return DC_INVALID_ARGUMENT;
    return Guard([&] { h->core.Stop(); });
}
int32_t dc_protect(dc_handle* h, int32_t reason) {
    if (!h || reason < DC_INPUT_TIMEOUT || reason > DC_NUMERICAL_FAULT) return DC_INVALID_ARGUMENT;
    return Guard([&] { h->core.Protect(static_cast<drone::Reason>(reason)); });
}
int32_t dc_resume(dc_handle* h, int32_t neutral) {
    if (!h || neutral != 1) return DC_INVALID_ARGUMENT;
    return Guard([&] { h->core.Resume(true); });
}
int32_t dc_get_state(const dc_handle* h, dc_state* out) {
    // 【快照】复制状态供外部读取；首版只运动z轴，横向为零、姿态为单位四元数。
    if (!h || !out) return DC_INVALID_ARGUMENT;
    return Guard([&] {
        const auto s = h->core.State();
        *out = {};
        out->interface_version = 1;
        out->mode = static_cast<int32_t>(s.mode);
        out->reason = static_cast<int32_t>(s.reason);
        out->sequence = s.sequence;
        out->simulation_time_s = s.simulation_time_s;
        out->monotonic_time_s = s.monotonic_time_s;
        out->position_m[2] = s.plant.altitude_m;
        out->velocity_m_s[2] = s.plant.vertical_speed_m_s;
        out->quaternion_xyzw[3] = 1.0;
        out->actual_thrust_n = s.plant.thrust_n;
        out->commanded_thrust_n = s.commanded_thrust_n;
        out->target_altitude_m = s.target_altitude_m;
        out->reference_vertical_speed_m_s = s.reference_vertical_speed_m_s;
    });
}
}
