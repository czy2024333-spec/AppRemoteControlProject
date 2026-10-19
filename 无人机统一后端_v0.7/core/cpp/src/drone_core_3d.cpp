#include "drone_core_3d.hpp"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace drone3 {
namespace {
constexpr double Pi = 3.14159265358979323846;
using M3 = std::array<V3, 3>;
using StateVector = std::array<double, 17>;
V3 Add(V3 a, const V3& b) { for (int i=0;i<3;++i) a[i]+=b[i]; return a; }
V3 Sub(V3 a, const V3& b) { for (int i=0;i<3;++i) a[i]-=b[i]; return a; }
V3 Scale(V3 a, double x) { for (double& v:a) v*=x; return a; }
double Dot(const V3& a,const V3& b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
double Norm(const V3& a) { return std::sqrt(Dot(a,a)); }
V3 Cross(const V3& a,const V3& b) { return {a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]}; }
V3 Unit(V3 v) { double n=Norm(v); if (!std::isfinite(n)||n<1e-12) throw std::runtime_error("singular attitude target"); return Scale(v,1/n); }
template<class A> bool Finite(const A& a) { for (double v:a) if (!std::isfinite(v)) return false; return true; }
void Positive(double v) { if (!std::isfinite(v)||v<=0) throw std::invalid_argument("config must be finite and positive"); }
Q4 Normalize(Q4 q) {
    double n=0; for(double v:q) n+=v*v;
    if (!std::isfinite(n)||n<1e-24) throw std::runtime_error("invalid quaternion");
    for(double& v:q) v/=std::sqrt(n);
    return q;
}
M3 Rotation(Q4 q) {
    q=Normalize(q); const double x=q[0],y=q[1],z=q[2],w=q[3];
    return {{{1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)},
             {2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)},
             {2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)}}};
}
V3 Mul(const M3& r,const V3& v) { return {Dot(r[0],v),Dot(r[1],v),Dot(r[2],v)}; }
V3 TMul(const M3& r,const V3& v) {
    V3 out{}; for(int i=0;i<3;++i) for(int j=0;j<3;++j) out[i]+=r[j][i]*v[j]; return out;
}
std::array<V3,4> RotorPositions(const Config& c) {
    const double a=c.arm*.70710678118;
    return {{{a,a,0},{a,-a,0},{-a,-a,0},{-a,a,0}}};
}
StateVector Pack(const PlantState& s) {
    StateVector y{};
    for(int i=0;i<3;++i) { y[i]=s.position[i]; y[i+3]=s.velocity[i]; y[i+10]=s.angular_velocity[i]; }
    for(int i=0;i<4;++i) { y[i+6]=s.quaternion[i]; y[i+13]=s.rotor_speeds[i]; }
    return y;
}
PlantState Unpack(const StateVector& y) {
    PlantState s;
    for(int i=0;i<3;++i) { s.position[i]=y[i]; s.velocity[i]=y[i+3]; s.angular_velocity[i]=y[i+10]; }
    for(int i=0;i<4;++i) { s.quaternion[i]=y[i+6]; s.rotor_speeds[i]=y[i+13]; }
    return s;
}
StateVector Derivative(const StateVector& y,const Motors& command,const Config& c,const V3& wind) {
    const auto s=Unpack(y); const auto r=Rotation(s.quaternion);
    const V3 air=TMul(r,Sub(s.velocity,wind)); // 世界系相对空气速度，随后转到机体系。
    const auto positions=RotorPositions(c);
    V3 force{}, moment{};
    for(int i=0;i<4;++i) {
        // 【桨毂气动】局部空气速度包含w×r，阻力同时影响力和力矩。
        const V3 local=Add(air,Cross(s.angular_velocity,positions[i]));
        const double speed=s.rotor_speeds[i], square=speed*speed;
        const V3 f{-speed*c.k_d*local[0],-speed*c.k_d*local[1],c.k_eta*square-speed*c.k_z*local[2]};
        force=Add(force,f); moment=Add(moment,Cross(positions[i],f));
        moment[2]+=(i%2==0 ? 1. : -1.)*c.k_m*square;
    }
    V3 acceleration=Scale(Mul(r,force),1/c.mass); acceleration[2]-=c.gravity;
    V3 jw{}; for(int i=0;i<3;++i) jw[i]=c.inertia[i]*s.angular_velocity[i];
    const V3 angular=Sub(moment,Cross(s.angular_velocity,jw));
    StateVector d{};
    for(int i=0;i<3;++i) { d[i]=s.velocity[i]; d[i+3]=acceleration[i]; d[i+10]=angular[i]/c.inertia[i]; }
    // 【姿态积分】q_dot=0.5*q⊗[w,0]，角速度在机体系中。
    const V3 qv{s.quaternion[0],s.quaternion[1],s.quaternion[2]};
    const V3 qdot=Scale(Add(Scale(s.angular_velocity,s.quaternion[3]),Cross(qv,s.angular_velocity)),.5);
    for(int i=0;i<3;++i) d[i+6]=qdot[i];
    d[9]=-.5*Dot(qv,s.angular_velocity);
    for(int i=0;i<4;++i) d[i+13]=(command[i]-s.rotor_speeds[i])/c.motor_tau;
    return d;
}
StateVector Shift(StateVector y,const StateVector& d,double h) { for(int i=0;i<17;++i) y[i]+=h*d[i]; return y; }
Motors Controller(const Config& c,const PlantState& s,V3 target,V3 velocity,double yaw,double yaw_rate,V3 compensation,bool& saturated) {
    V3 desired{};
    for(int i=0;i<3;++i) desired[i]=c.mass*(c.kp[i]*(target[i]-s.position[i])+c.kd[i]*(velocity[i]-s.velocity[i])+compensation[i]);
    desired[2]+=c.mass*c.gravity;
    const auto r=Rotation(s.quaternion);
    const V3 b3{r[0][2],r[1][2],r[2][2]};
    const double thrust=Dot(desired,b3);
    const V3 b3d=Unit(desired), heading{std::cos(yaw),std::sin(yaw),0.};
    const V3 b2d=Unit(Cross(b3d,heading)), b1d=Cross(b2d,b3d);
    M3 rd{}; for(int i=0;i<3;++i) { rd[i][0]=b1d[i]; rd[i][1]=b2d[i]; rd[i][2]=b3d[i]; }
    M3 skew{};
    for(int i=0;i<3;++i) for(int j=0;j<3;++j) for(int k=0;k<3;++k)
        skew[i][j]+=.5*(rd[k][i]*r[k][j]-r[k][i]*rd[k][j]);
    const V3 error{-skew[1][2],skew[0][2],-skew[0][1]};
    // 将世界z偏航角速度投影到机体系；不同于简化的直接wz赋值。
    const V3 desired_w=TMul(r,{0.,0.,yaw_rate});
    V3 torque{},jw{};
    for(int i=0;i<3;++i) {
        torque[i]=c.inertia[i]*(-c.kp_att*error[i]-c.kd_att*(s.angular_velocity[i]-desired_w[i]));
        jw[i]=c.inertia[i]*s.angular_velocity[i];
    }
    torque=Add(torque,Cross(s.angular_velocity,jw));
    // 【四电机分配】X型r1(+,+),r2(+,-),r3(-,-),r4(-,+)，转向+,-,+,-。
    const double a=c.arm*.70710678118, k=c.k_m/c.k_eta;
    const std::array<double,4> forces{
        .25*(thrust+torque[0]/a-torque[1]/a+torque[2]/k),
        .25*(thrust-torque[0]/a-torque[1]/a-torque[2]/k),
        .25*(thrust-torque[0]/a+torque[1]/a+torque[2]/k),
        .25*(thrust+torque[0]/a+torque[1]/a-torque[2]/k)};
    Motors out{};
    saturated=false;
    for(int i=0;i<4;++i) {
        if (!std::isfinite(forces[i])) throw std::runtime_error("nonfinite rotor force");
        const double max_force=c.k_eta*c.max_rotor_speed*c.max_rotor_speed;
        if(forces[i]<0||forces[i]>max_force) saturated=true;
        out[i]=std::clamp(std::sqrt(std::max(0.,forces[i])/c.k_eta),0.,c.max_rotor_speed);
    }
    if (!Finite(out)) throw std::runtime_error("nonfinite motor commands");
    return out;
}
}

void Config::Validate() const {
    for(double v:{mass,gravity,arm,k_eta,k_m,motor_tau,max_rotor_speed,step,min_z,max_z,radius,
                  max_horizontal_speed,max_vertical_speed,max_yaw_rate,reference_acceleration,max_ttl,kp_att,kd_att}) Positive(v);
    for(double v:inertia) Positive(v);
    for(double v:kp) Positive(v);
    for(double v:kd) Positive(v);
    if (!std::isfinite(k_d)||!std::isfinite(k_z)||k_d<0||k_z<0||!Finite(initial_position))
        throw std::invalid_argument("invalid drag/initial position");
    if (step<.002||step>.02||min_z>=max_z||initial_position[2]<min_z||initial_position[2]>max_z)
        throw std::invalid_argument("invalid step/altitude bounds");
    if (!std::isfinite(4*k_eta*max_rotor_speed*max_rotor_speed)||4*k_eta*max_rotor_speed*max_rotor_speed<=mass*gravity)
        throw std::invalid_argument("insufficient maximum thrust");
}
RigidBodyPlant::RigidBodyPlant(Config c):config_(c) { c.Validate(); Reset(); }
void RigidBodyPlant::Reset() {
    state_={}; state_.position=config_.initial_position;
    state_.quaternion={0,0,0,1};
    state_.rotor_speeds.fill(std::sqrt(config_.mass*config_.gravity/(4*config_.k_eta)));
    wind_={};
}
void RigidBodyPlant::SetWind(V3 wind) {
    if(!Finite(wind)||Norm(wind)>5.) throw std::invalid_argument("wind norm must be <=5 m/s");
    wind_=wind;
}
void RigidBodyPlant::ApplyImpulse(V3 linear,V3 angular) {
    if(!Finite(linear)||!Finite(angular)) throw std::invalid_argument("nonfinite impulse");
    const V3 dv=Scale(linear,1/config_.mass);
    V3 dw{}; for(int i=0;i<3;++i) dw[i]=angular[i]/config_.inertia[i];
    if(Norm(dv)>1.||Norm(dw)>1.) throw std::invalid_argument("impulse delta must be <=1 m/s or 1 rad/s");
    const V3 v=Add(state_.velocity,dv),w=Add(state_.angular_velocity,dw);
    if(!Finite(v)||!Finite(w)) throw std::invalid_argument("nonfinite impulse state");
    state_.velocity=v; state_.angular_velocity=w; // 只改变动量，不改变位置/姿态。
}
void RigidBodyPlant::Step(const Motors& commands) {
    for(double v:commands) if (!std::isfinite(v)||v<0||v>config_.max_rotor_speed) throw std::invalid_argument("invalid rotor speed");
    StateVector y=Pack(state_);
    // 【RK4】每100Hz控制步细分为<=1ms，四个电机一阶响应与姿态耦合积分。
    const int n=static_cast<int>(std::ceil(config_.step/.001)); const double h=config_.step/n;
    for(int j=0;j<n;++j) {
        const auto a=Derivative(y,commands,config_,wind_);
        const auto b=Derivative(Shift(y,a,h/2),commands,config_,wind_);
        const auto c=Derivative(Shift(y,b,h/2),commands,config_,wind_);
        const auto d=Derivative(Shift(y,c,h),commands,config_,wind_);
        for(int i=0;i<17;++i) y[i]+=h*(a[i]+2*b[i]+2*c[i]+d[i])/6;
        if (!Finite(y)) throw std::runtime_error("nonfinite rigid body state");
        const Q4 q=Normalize({y[6],y[7],y[8],y[9]});
        for(int i=0;i<4;++i) y[i+6]=q[i];
    }
    state_=Unpack(y); // 全部子步成功后提交，异常时保留旧状态。
}
Core::Core(Config c):config_(c),plant_(c) { Reset(); }
void Core::Reset() {
    plant_.Reset(); target_=config_.initial_position; reference_={}; command_={};
    motor_commands_=plant_.State().rotor_speeds;
    yaw_=yaw_rate_=clock_=deadline_=0.; has_deadline_=false; ticks_=0;
    mode_=drone::Mode::Hold; reason_=drone::Reason::None;
    compensation_={}; compensation_enabled_=false;
}
bool Core::Inside(const V3& p) const {
    return p[2]>=config_.min_z&&p[2]<=config_.max_z&&
        std::hypot(p[0]-config_.initial_position[0],p[1]-config_.initial_position[1])<=config_.radius;
}
void Core::ObserveClock(double now) {
    if (!std::isfinite(now)||now<clock_) throw std::invalid_argument("monotonic clock moved backwards");
    clock_=now;
    if (has_deadline_&&now>=deadline_-1e-12) Protect(drone::Reason::InputTimeout);
}
void Core::SetControl(V3 v,double yaw_rate,double ttl,double now) {
    if (!Finite(v)||std::hypot(v[0],v[1])>config_.max_horizontal_speed||std::abs(v[2])>config_.max_vertical_speed||
        !std::isfinite(yaw_rate)||std::abs(yaw_rate)>config_.max_yaw_rate||!std::isfinite(ttl)||ttl<=0||ttl>config_.max_ttl||!std::isfinite(now+ttl))
        throw std::invalid_argument("motion input outside limits");
    ObserveClock(now);
    if (mode_==drone::Mode::Protected) throw std::logic_error("protection latched");
    command_=v; yaw_rate_=yaw_rate; deadline_=now+ttl; has_deadline_=true;
    mode_=drone::Mode::Active; reason_=drone::Reason::None;
}
void Core::Hold(drone::Mode mode,drone::Reason reason) {
    target_=plant_.State().position;
    target_[2]=std::clamp(target_[2],config_.min_z,config_.max_z);
    const double x=target_[0]-config_.initial_position[0],y=target_[1]-config_.initial_position[1],n=std::hypot(x,y);
    if(n>config_.radius) { target_[0]=config_.initial_position[0]+x*config_.radius/n; target_[1]=config_.initial_position[1]+y*config_.radius/n; }
    const auto r=Rotation(plant_.State().quaternion); yaw_=std::atan2(r[1][0],r[0][0]);
    reference_={}; command_={}; yaw_rate_=0; has_deadline_=false; mode_=mode; reason_=reason;
}
void Core::Stop() { if(mode_!=drone::Mode::Protected) Hold(drone::Mode::Hold,drone::Reason::Stop); }
void Core::Protect(drone::Reason r) {
    if(r<drone::Reason::InputTimeout||r>drone::Reason::NumericalFault) throw std::invalid_argument("invalid protection reason");
    if(mode_!=drone::Mode::Protected) Hold(drone::Mode::Protected,r);
}
void Core::Resume(bool neutral) { if(!neutral) throw std::invalid_argument("neutral required"); Hold(drone::Mode::Hold,drone::Reason::None); }
void Core::Step(double now) {
    ObserveClock(now);
    const V3 diff=Sub(command_,reference_); const double n=Norm(diff);
    reference_=Add(reference_,Scale(diff,n>0 ? std::min(1.,config_.reference_acceleration*config_.step/n) : 0.));
    const V3 next=Add(target_,Scale(reference_,config_.step));
    if (!Inside(next)) Protect(drone::Reason::ReferenceLimit); else target_=next;
    yaw_=std::remainder(yaw_+yaw_rate_*config_.step,2*Pi);
    V3 proposed=compensation_;
    if(compensation_enabled_) for(int i=0;i<3;++i)
        proposed[i]=std::clamp(proposed[i]+2.*(target_[i]-plant_.State().position[i])*config_.step,-1.,1.);
    try {
        bool saturated=false;
        motor_commands_=Controller(config_,plant_.State(),target_,reference_,yaw_,yaw_rate_,proposed,saturated);
        if(saturated) {
            proposed=compensation_;
            motor_commands_=Controller(config_,plant_.State(),target_,reference_,yaw_,yaw_rate_,proposed,saturated);
        }
        plant_.Step(motor_commands_);
        compensation_=proposed; // 积分只在整个物理步成功后提交。
    }
    catch (...) { Protect(drone::Reason::NumericalFault); throw; }
    ++ticks_;
    if (!Inside(plant_.State().position)) Protect(drone::Reason::StateLimit);
}
Snapshot Core::State() const {
    return {plant_.State(),target_,reference_,motor_commands_,yaw_,yaw_rate_,static_cast<double>(ticks_)*config_.step,clock_,ticks_,mode_,reason_,plant_.Wind(),compensation_,compensation_enabled_};
}
} // namespace drone3
