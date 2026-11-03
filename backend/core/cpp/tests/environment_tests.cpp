#include "drone_core_3d.hpp"
#include "drone_core_3d_c.h"
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
namespace {
void Check(bool ok,const char* message) { if(!ok) throw std::runtime_error(message); }
double Norm(const drone3::V3& v) { return std::sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]); }
double Error(const drone3::Core& c) { auto p=c.State().plant.position; p[2]-=1.; return Norm(p); }
void Advance(drone3::Core& c,int n) { const double start=c.State().monotonic_time; for(int i=0;i<n;++i) c.Step(start+(i+1)*.01); }
void WindDirection() {
    for(int axis=0;axis<3;++axis) for(double sign:{-1.,1.}) {
        drone3::Core c; drone3::V3 wind{}; wind[axis]=sign;
        c.SetWind(wind); c.Step(.01); Check(c.State().plant.velocity[axis]*sign>0.,"world wind force direction");
    }
}
void ImpulsePhysics() {
    drone3::Core c; const auto before=c.State();
    c.ApplyImpulse({.012,-.009,.003},{4.29e-6,-2.86e-6,5.78e-6}); const auto after=c.State();
    Check(after.plant.position==before.plant.position&&after.plant.quaternion==before.plant.quaternion,"no impulse teleport");
    Check(std::abs(after.plant.velocity[0]-.4)<1e-12&&std::abs(after.plant.velocity[1]+.3)<1e-12,"linear impulse J/m");
    Check(std::abs(after.plant.angular_velocity[0]-.3)<1e-12&&std::abs(after.plant.angular_velocity[1]+.2)<1e-12,"angular impulse J/inertia");
    Check(after.sequence==0&&after.mode==drone::Mode::Hold,"impulse leaves clock/mode unchanged");
    Advance(c,800); Check(Error(c)<.01&&Norm(c.State().plant.velocity)<.01&&Norm(c.State().plant.angular_velocity)<.01,"impulse recovery");
}
void ValidationAndIsolation() {
    drone3::Core a,b; a.SetWind({2,-1,.2});
    Check(Norm(b.State().wind)==0,"wind isolation");
    bool rejected=false; try{a.SetWind({6,0,0});}catch(const std::invalid_argument&){rejected=true;}
    Check(rejected&&a.State().wind[0]==2,"wind bound atomicity");
    rejected=false; try{a.ApplyImpulse({.04,0,0},{});}catch(const std::invalid_argument&){rejected=true;}
    Check(rejected&&Norm(a.State().plant.velocity)==0,"impulse limit atomicity");
    rejected=false; try{a.ApplyImpulse({}, {std::numeric_limits<double>::quiet_NaN(),0,0});}catch(const std::invalid_argument&){rejected=true;}
    Check(rejected,"nonfinite angular impulse");
    a.EnableCompensation(true); Advance(a,10); a.Reset();
    Check(Norm(a.State().wind)==0&&Norm(a.State().compensation_acceleration)==0&&!a.State().compensation_enabled,"reset clears environment");
}
void SteadyCompensation() {
    drone3::Core baseline,c; c.EnableCompensation(true);
    baseline.SetWind({2,-1,.25}); c.SetWind({2,-1,.25}); Advance(baseline,1200); Advance(c,1200);
    Check(Error(baseline)>.04&&Error(c)<.01&&Error(c)<Error(baseline)*.2,"steady wind offset reduction");
    c.SetWind({}); Advance(c,1200);
    Check(Error(c)<.01&&Norm(c.State().plant.velocity)<.01,"recovery after wind removed");
}
void SaturationFreeze() {
    drone3::Config config; config.max_rotor_speed=1790.;
    drone3::Core c(config); c.EnableCompensation(true); c.SetControl({0,0,.5},0.,.5,0.);
    for(int i=0;i<10;++i) c.Step((i+1)*.01);
    Check(Norm(c.State().compensation_acceleration)==0.,"integral frozen under real rotor saturation");
    drone3::Core wind; wind.SetWind({5,0,0}); wind.EnableCompensation(true);
    for(int i=0;i<500;++i) {
        wind.Step((i+1)*.01);
        for(double value:wind.State().compensation_acceleration) Check(std::abs(value)<=1.,"compensation bound");
    }
}
void ProtectedEnvironment() {
    drone3::Core c; c.Protect(); c.SetWind({1,0,0}); c.ApplyImpulse({.003,0,0},{}); c.EnableCompensation(true); Advance(c,10);
    Check(c.State().mode==drone::Mode::Protected&&Norm(c.State().reference_velocity)==0.,"environment cannot resume motion");
    drone3::Config config; config.radius=.02; drone3::Core bounded(config); bounded.SetWind({5,0,0}); Advance(bounded,100);
    Check(bounded.State().mode==drone::Mode::Protected,"wind can trigger fence protection");
}
void EnvironmentABI() {
    static_assert(sizeof(dc3_state)==272,"Existing ABI v2 snapshot layout must remain unchanged");
    dc3_handle* handle=nullptr; Check(dc3_create(nullptr,&handle)==DC_OK,"environment C create");
    dc3_environment environment{}; dc3_state state{};
    const double wind[3]={2,-1,.25},linear[3]={.003,0,0},angular[3]={0,0,0};
    Check(dc3_set_wind(handle,wind)==DC_OK&&dc3_apply_impulse(handle,linear,angular)==DC_OK,"environment C mutation");
    Check(dc3_set_wind(handle,nullptr)==DC_INVALID_ARGUMENT&&dc3_apply_impulse(handle,nullptr,angular)==DC_INVALID_ARGUMENT,"null vectors");
    Check(dc3_enable_compensation(handle,2)==DC_INVALID_ARGUMENT&&dc3_enable_compensation(handle,1)==DC_OK,"compensation flag");
    Check(dc3_get_environment(handle,&environment)==DC_OK&&environment.environment_version==1&&environment.world_wind_m_s[0]==2&&environment.compensation_enabled==1,"C environment state");
    Check(dc3_get_state(handle,&state)==DC_OK&&state.interface_version==2,"C snapshot version retained");
    Check(dc3_reset(handle)==DC_OK&&dc3_get_environment(handle,&environment)==DC_OK&&environment.world_wind_m_s[0]==0&&environment.compensation_enabled==0,"C reset environment");
    dc3_destroy(handle); Check(dc3_get_environment(nullptr,&environment)==DC_INVALID_ARGUMENT,"null environment handle");
}
}
int main() {
    try {
        WindDirection(); std::cout<<"PASS six signed wind directions\n";
        ImpulsePhysics(); std::cout<<"PASS physical impulse and hold recovery\n";
        ValidationAndIsolation(); std::cout<<"PASS validation, isolation and environment reset\n";
        SteadyCompensation(); std::cout<<"PASS steady wind rejection and wind-off recovery\n";
        SaturationFreeze(); std::cout<<"PASS integral bound and real actuator saturation freeze\n";
        ProtectedEnvironment(); std::cout<<"PASS protection latch and wind-driven fence\n";
        EnvironmentABI(); std::cout<<"PASS additive environment C ABI and original layout\n7 environment test groups passed\n";
    } catch(const std::exception& e) { std::cerr<<"FAIL "<<e.what()<<'\n'; return 1; }
}
