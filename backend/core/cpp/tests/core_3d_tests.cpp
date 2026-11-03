#include "drone_core_3d.hpp"
#include "drone_core_3d_c.h"
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
namespace {
void Check(bool ok,const char* message) { if(!ok) throw std::runtime_error(message); }
double Norm(const drone3::V3& v) { return std::sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]); }
double Distance(drone3::V3 a,const drone3::V3& b) { for(int i=0;i<3;++i) a[i]-=b[i]; return Norm(a); }
double Yaw(const drone3::Q4& q) { return std::atan2(2*(q[3]*q[2]+q[0]*q[1]),1-2*(q[1]*q[1]+q[2]*q[2])); }
void Advance(drone3::Core& c,int ticks,drone3::V3 velocity={},double yaw=0.,bool active=false) {
    const double start=c.State().monotonic_time;
    for(int i=0;i<ticks;++i) {
        const double now=start+(i+1)*.01;
        if(active&&i%10==0) c.SetControl(velocity,yaw,.5,now);
        c.Step(now);
    }
}
void Hover() {
    drone3::Core c; Advance(c,1000);
    Check(Distance(c.State().plant.position,{0,0,1})<1e-10,"hover position");
    Check(Norm(c.State().plant.angular_velocity)<1e-10,"hover rates");
}
void SixAxes() {
    for(int axis=0;axis<3;++axis) for(double sign:{-1.,1.}) {
        drone3::Core c; drone3::V3 v{}; v[axis]=sign*.3; Advance(c,250,v,0.,true);
        const auto moving=c.State();
        Check((moving.plant.position[axis]-(axis==2 ? 1. : 0.))*sign>.4,"signed axis direction");
        if(axis<2) Check(std::hypot(moving.plant.quaternion[0],moving.plant.quaternion[1])>.0001,"horizontal movement must tilt");
        c.Stop(); Advance(c,600);
        Check(Distance(c.State().plant.position,moving.plant.position)<.02,"stop anchor");
        Check(Norm(c.State().plant.velocity)<.01,"stop convergence");
    }
}
void Diagonal() {
    drone3::Core c; Advance(c,250,{.3,.3,.2},0.,true);
    const auto s=c.State();
    Check(s.plant.position[0]>.4&&s.plant.position[1]>.4&&s.plant.position[2]>1.2,"diagonal displacement");
    drone3::Core ramp; ramp.SetControl({.3,.3,.2},0.,.5,0.);
    drone3::V3 previous{};
    for(int i=0;i<20;++i) { ramp.Step(i*.01); auto ref=ramp.State().reference_velocity; Check(Distance(ref,previous)<=.005+1e-12,"vector acceleration"); previous=ref; }
}
void YawHold() {
    for(double sign:{-1.,1.}) {
        drone3::Core c; Advance(c,200,{},sign*.3,true);
        const double heading=Yaw(c.State().plant.quaternion); Check(heading*sign>.4,"yaw sign");
        c.Stop(); Advance(c,600);
        Check(std::abs(Yaw(c.State().plant.quaternion)-heading)<.02,"hold actual yaw");
        Check(Norm(c.State().plant.angular_velocity)<.01,"yaw stop rates");
    }
}
void Timeout() {
    drone3::Core c; c.SetControl({.3,.2,.2},.3,.1,0.); c.Step(.1);
    Check(c.State().mode==drone::Mode::Protected,"TTL boundary");
    c.Stop(); Check(c.State().mode==drone::Mode::Protected,"stop cannot resume");
    bool rejected=false; try{c.SetControl({},0.,.5,.1);}catch(const std::logic_error&){rejected=true;}
    Check(rejected,"protected control rejection");
    rejected=false; try{c.Resume(false);}catch(const std::invalid_argument&){rejected=true;}
    Check(rejected,"neutral required"); c.Resume(true); Advance(c,300);
    Check(c.State().mode==drone::Mode::Hold&&Norm(c.State().reference_velocity)==0,"no old input after resume");
    c.SetControl({.3,0,0},0.,.5,4.); c.Step(10.);
    Check(c.State().reason==drone::Reason::InputTimeout,"wall clock gap");
}
void Fence() {
    drone3::Config config; config.radius=.15; drone3::Core c(config);
    for(int i=0;i<200&&c.State().mode!=drone::Mode::Protected;++i) { c.SetControl({.5,0,0},0.,.5,i*.01); c.Step(i*.01); }
    Check(c.State().mode==drone::Mode::Protected,"horizontal fence");
    Check(c.State().reason==drone::Reason::ReferenceLimit||c.State().reason==drone::Reason::StateLimit,"fence reason");
    config.radius=10; config.max_z=1.1; drone3::Core z(config);
    for(int i=0;i<200&&z.State().mode!=drone::Mode::Protected;++i) { z.SetControl({0,0,.5},0.,.5,i*.01); z.Step(i*.01); }
    Check(z.State().mode==drone::Mode::Protected,"vertical fence");
}
void ValidationIsolation() {
    drone3::Core a,b; Advance(a,100,{.3,0,0},0,true);
    Check(b.State().sequence==0&&b.State().plant.position[0]==0,"instance isolation");
    bool rejected=false; try{a.SetControl({.5,.5,0},0,.5,1.);}catch(const std::invalid_argument&){rejected=true;}
    Check(rejected,"horizontal resultant limit");
    rejected=false; try{a.SetControl({std::numeric_limits<double>::quiet_NaN(),0,0},0,.5,1.);}catch(const std::invalid_argument&){rejected=true;}
    Check(rejected,"NaN input");
    rejected=false; try{a.Step(.5);}catch(const std::invalid_argument&){rejected=true;}
    Check(rejected,"backward clock");
    a.Reset(); Check(a.State().sequence==0&&Distance(a.State().plant.position,{0,0,1})==0,"reset");
}
void PlantCoupling() {
    drone3::RigidBodyPlant p; auto commands=p.State().rotor_speeds;
    commands[0]+=100;
    for(int i=0;i<20;++i) p.Step(commands);
    Check(std::abs(p.State().angular_velocity[0])>.01&&std::abs(p.State().angular_velocity[1])>.01,"rotor torque coupling");
    Check(std::abs(p.State().position[0])>.00001,"tilt causes translation");
    double norm=0; for(double v:p.State().quaternion) norm+=v*v;
    Check(std::abs(norm-1)<1e-12,"quaternion normalization");
}
void AnalyticMotorDecay() {
    drone3::Config config;
    // 关闭桨毂阻力后，四电机同速衰减有独立解析解，可检查RK4和推力平方律。
    config.k_d=0; config.k_z=0;
    drone3::RigidBodyPlant plant(config);
    const double initial=plant.State().rotor_speeds[0],t=.2,tau=config.motor_tau/2;
    for(int i=0;i<20;++i) plant.Step({0,0,0,0});
    const double decay=std::exp(-t/tau);
    const double velocity=config.gravity*(tau*(1-decay)-t);
    const double altitude=1+config.gravity*(tau*t-tau*tau*(1-decay)-t*t/2);
    Check(std::abs(plant.State().velocity[2]-velocity)<1e-8,"analytic freefall velocity");
    Check(std::abs(plant.State().position[2]-altitude)<1e-8,"analytic freefall altitude");
    Check(std::abs(plant.State().rotor_speeds[0]-initial*std::exp(-t/config.motor_tau))<1e-5,"analytic motor decay");
}
void ABI() {
    dc3_handle* h=nullptr; dc3_config config{}; dc3_state s{};
    Check(dc3_default_config(&config)==DC_OK,"default C config");
    config.interface_version=1; Check(dc3_create(&config,&h)==DC_INVALID_ARGUMENT&&h==nullptr,"version guard");
    config.interface_version=2; Check(dc3_create(&config,&h)==DC_OK,"create C handle");
    const double v[3]={.3,.2,0};
    Check(dc3_set_control(h,v,.2,.5,0)==DC_OK,"C control");
    Check(dc3_set_control(h,nullptr,0,.5,0)==DC_INVALID_ARGUMENT,"null velocity");
    Check(dc3_step(h,.01)==DC_OK&&dc3_get_state(h,&s)==DC_OK&&s.interface_version==2&&s.sequence==1,"C state");
    Check(dc3_protect(h,DC_SERVICE_PROTECTION)==DC_OK,"C protect");
    Check(dc3_set_control(h,v,0,.5,.01)==DC_PROTECTED,"C protection code");
    Check(dc3_resume(h,0)==DC_INVALID_ARGUMENT&&dc3_resume(h,1)==DC_OK,"C resume");
    Check(dc3_reset(h)==DC_OK,"C reset"); dc3_destroy(h);
    Check(dc3_get_state(nullptr,&s)==DC_INVALID_ARGUMENT,"null handle");
}
}
int main() {
    try {
        Hover(); std::cout<<"PASS hover\n";
        SixAxes(); std::cout<<"PASS six signed axes and braking\n";
        Diagonal(); std::cout<<"PASS diagonal and vector acceleration\n";
        YawHold(); std::cout<<"PASS yaw and heading hold\n";
        Timeout(); std::cout<<"PASS TTL, wall clock and neutral resume\n";
        Fence(); std::cout<<"PASS horizontal and vertical fence\n";
        ValidationIsolation(); std::cout<<"PASS validation, clock, isolation, reset\n";
        PlantCoupling(); std::cout<<"PASS four rotor rigid body coupling\n";
        AnalyticMotorDecay(); std::cout<<"PASS independent analytic motor decay/freefall\n";
        ABI(); std::cout<<"PASS versioned C ABI\n10 3D test groups passed\n";
    } catch(const std::exception& e) { std::cerr<<"FAIL "<<e.what()<<'\n'; return 1; }
}
