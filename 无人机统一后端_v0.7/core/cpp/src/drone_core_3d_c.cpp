#include "drone_core_3d_c.h"
#include "drone_core_3d.hpp"
#include <algorithm>
#include <stdexcept>
struct dc3_handle { explicit dc3_handle(drone3::Config c):core(c) {} drone3::Core core; };
namespace {
template<class F> int32_t Guard3(F f) noexcept {
    try { f(); return DC_OK; }
    catch(const std::invalid_argument&) { return DC_INVALID_ARGUMENT; }
    catch(const std::logic_error&) { return DC_PROTECTED; }
    catch(...) { return DC_INTERNAL_ERROR; }
}
drone3::Config Convert3(const dc3_config& c) {
    if(c.interface_version!=2) throw std::invalid_argument("3D interface version must be 2");
    drone3::Config o;
    o.step=c.step_s; std::copy_n(c.initial_position_m,3,o.initial_position.begin());
    o.min_z=c.min_altitude_m; o.max_z=c.max_altitude_m; o.radius=c.horizontal_radius_m;
    o.max_horizontal_speed=c.max_horizontal_speed_m_s; o.max_vertical_speed=c.max_vertical_speed_m_s;
    o.max_yaw_rate=c.max_yaw_rate_rad_s; o.reference_acceleration=c.reference_acceleration_m_s2; o.max_ttl=c.max_input_ttl_s;
    return o;
}
}
extern "C" {
int32_t dc3_default_config(dc3_config* out) {
    if(!out) return DC_INVALID_ARGUMENT;
    const drone3::Config c;
    *out={2,c.step,{c.initial_position[0],c.initial_position[1],c.initial_position[2]},c.min_z,c.max_z,c.radius,
          c.max_horizontal_speed,c.max_vertical_speed,c.max_yaw_rate,c.reference_acceleration,c.max_ttl};
    return DC_OK;
}
int32_t dc3_create(const dc3_config* c,dc3_handle** out) {
    if(!out) return DC_INVALID_ARGUMENT;
    *out=nullptr;
    return Guard3([&]{*out=new dc3_handle(c ? Convert3(*c) : drone3::Config{});});
}
void dc3_destroy(dc3_handle* h) { delete h; }
int32_t dc3_reset(dc3_handle* h) { if(!h) return DC_INVALID_ARGUMENT; return Guard3([&]{h->core.Reset();}); }
int32_t dc3_set_control(dc3_handle* h,const double v[3],double yaw,double ttl,double now) {
    if(!h||!v) return DC_INVALID_ARGUMENT;
    return Guard3([&]{h->core.SetControl({v[0],v[1],v[2]},yaw,ttl,now);});
}
int32_t dc3_step(dc3_handle* h,double now) { if(!h) return DC_INVALID_ARGUMENT; return Guard3([&]{h->core.Step(now);}); }
int32_t dc3_stop(dc3_handle* h) { if(!h) return DC_INVALID_ARGUMENT; return Guard3([&]{h->core.Stop();}); }
int32_t dc3_protect(dc3_handle* h,int32_t reason) {
    if(!h||reason<DC_INPUT_TIMEOUT||reason>DC_NUMERICAL_FAULT) return DC_INVALID_ARGUMENT;
    return Guard3([&]{h->core.Protect(static_cast<drone::Reason>(reason));});
}
int32_t dc3_resume(dc3_handle* h,int32_t neutral) {
    if(!h||neutral!=1) return DC_INVALID_ARGUMENT;
    return Guard3([&]{h->core.Resume(true);});
}
int32_t dc3_get_state(const dc3_handle* h,dc3_state* out) {
    if(!h||!out) return DC_INVALID_ARGUMENT;
    return Guard3([&]{
        const auto s=h->core.State(); *out={};
        out->interface_version=2; out->mode=static_cast<int32_t>(s.mode); out->reason=static_cast<int32_t>(s.reason);
        out->sequence=s.sequence; out->simulation_time_s=s.simulation_time; out->monotonic_time_s=s.monotonic_time;
        std::copy_n(s.plant.position.begin(),3,out->position_m);
        std::copy_n(s.plant.velocity.begin(),3,out->velocity_m_s);
        std::copy_n(s.plant.quaternion.begin(),4,out->quaternion_xyzw);
        std::copy_n(s.plant.angular_velocity.begin(),3,out->angular_velocity_rad_s);
        std::copy_n(s.plant.rotor_speeds.begin(),4,out->rotor_speeds_rad_s);
        std::copy_n(s.commanded_rotor_speeds.begin(),4,out->commanded_rotor_speeds_rad_s);
        std::copy_n(s.target_position.begin(),3,out->target_position_m);
        std::copy_n(s.reference_velocity.begin(),3,out->reference_velocity_m_s);
        out->target_yaw_rad=s.target_yaw; out->reference_yaw_rate_rad_s=s.reference_yaw_rate;
    });
}
int32_t dc3_set_wind(dc3_handle* h,const double v[3]) {
    if(!h||!v) return DC_INVALID_ARGUMENT;
    return Guard3([&]{h->core.SetWind({v[0],v[1],v[2]});});
}
int32_t dc3_apply_impulse(dc3_handle* h,const double linear[3],const double angular[3]) {
    if(!h||!linear||!angular) return DC_INVALID_ARGUMENT;
    return Guard3([&]{h->core.ApplyImpulse({linear[0],linear[1],linear[2]},{angular[0],angular[1],angular[2]});});
}
int32_t dc3_enable_compensation(dc3_handle* h,int32_t enabled) {
    if(!h||(enabled!=0&&enabled!=1)) return DC_INVALID_ARGUMENT;
    return Guard3([&]{h->core.EnableCompensation(enabled==1);});
}
int32_t dc3_get_environment(const dc3_handle* h,dc3_environment* out) {
    if(!h||!out) return DC_INVALID_ARGUMENT;
    return Guard3([&]{
        const auto s=h->core.State(); *out={}; out->environment_version=1;
        std::copy_n(s.wind.begin(),3,out->world_wind_m_s);
        std::copy_n(s.compensation_acceleration.begin(),3,out->compensation_acceleration_m_s2);
        out->compensation_enabled=s.compensation_enabled ? 1 : 0;
    });
}
}
