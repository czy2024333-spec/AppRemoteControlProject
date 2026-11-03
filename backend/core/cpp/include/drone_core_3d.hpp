#pragma once
#include "drone_core.hpp"
#include <array>

namespace drone3 {
using V3 = std::array<double, 3>;
using Q4 = std::array<double, 4>; // xyzw，机体到世界；世界/机体均x前、y左、z上。
using Motors = std::array<double, 4>;
struct Config {
    // Crazyflie默认值来自仓库内固定版本的RotorPy crazyflie_params.py。
    double mass = 0.03, gravity = 9.81;
    V3 inertia{1.43e-5, 1.43e-5, 2.89e-5}; // 仅支持主惯量对角阵。
    double arm = .043, k_eta = 2.3e-8, k_m = 7.8e-10;
    double k_d = 10.2506e-7, k_z = 7.553e-7;
    double motor_tau = .072, max_rotor_speed = 2500., step = .01;
    V3 initial_position{0., 0., 1.};
    double min_z = .3, max_z = 10., radius = 10.;
    double max_horizontal_speed = .5, max_vertical_speed = .5, max_yaw_rate = .5;
    double reference_acceleration = .5, max_ttl = .5;
    V3 kp{6.5, 6.5, 15.}, kd{4., 4., 9.};
    double kp_att = 310., kd_att = 57.;
    void Validate() const;
};
struct PlantState {
    V3 position{}, velocity{}, angular_velocity{};
    Q4 quaternion{0., 0., 0., 1.};
    Motors rotor_speeds{};
};
struct Snapshot {
    PlantState plant;
    V3 target_position{}, reference_velocity{};
    Motors commanded_rotor_speeds{};
    double target_yaw = 0., reference_yaw_rate = 0.;
    double simulation_time = 0., monotonic_time = 0.;
    std::uint64_t sequence = 0;
    drone::Mode mode = drone::Mode::Hold;
    drone::Reason reason = drone::Reason::None;
    V3 wind{}, compensation_acceleration{};
    bool compensation_enabled = false;
};

// 六自由度刚体 + 四独立电机 + 桨毂阻力；不是三个独立一维高度模型。
class RigidBodyPlant {
public:
    explicit RigidBodyPlant(Config config = {});
    void Reset();
    void Step(const Motors& commands);
    void SetWind(V3 wind);
    void ApplyImpulse(V3 world_linear_impulse, V3 body_angular_impulse);
    V3 Wind() const { return wind_; }
    const PlantState& State() const { return state_; }
private:
    Config config_;
    PlantState state_;
    V3 wind_{};
};
class Core {
public:
    explicit Core(Config config = {});
    void Reset();
    void SetControl(V3 world_velocity, double yaw_rate, double remaining_ttl, double monotonic_now);
    void Step(double monotonic_now);
    void Stop();
    void Protect(drone::Reason reason = drone::Reason::Service);
    void Resume(bool neutral_confirmed);
    void SetWind(V3 wind) { plant_.SetWind(wind); }
    void ApplyImpulse(V3 linear, V3 angular) { plant_.ApplyImpulse(linear, angular); }
    void EnableCompensation(bool enabled) { compensation_enabled_ = enabled; compensation_ = {}; }
    Snapshot State() const;
private:
    void ObserveClock(double now);
    void Hold(drone::Mode mode, drone::Reason reason);
    bool Inside(const V3& p) const;
    Config config_;
    RigidBodyPlant plant_;
    V3 target_{}, reference_{}, command_{};
    Motors motor_commands_{};
    double yaw_ = 0., yaw_rate_ = 0., clock_ = 0., deadline_ = 0.;
    bool has_deadline_ = false;
    std::uint64_t ticks_ = 0;
    drone::Mode mode_ = drone::Mode::Hold;
    drone::Reason reason_ = drone::Reason::None;
    V3 compensation_{};
    bool compensation_enabled_ = false;
};
} // namespace drone3
