#include "drone_core.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace drone {
namespace {
void Positive(double value, const char* message) {
    if (!std::isfinite(value) || value <= 0.0) throw std::invalid_argument(message);
}
}

void Config::Validate() const {
    Positive(mass_kg, "mass_kg must be positive and finite");
    Positive(gravity_m_s2, "gravity must be positive and finite");
    Positive(max_thrust_n, "max thrust must be positive and finite");
    Positive(thrust_time_constant_s, "thrust time constant must be positive and finite");
    Positive(step_s, "step must be positive and finite");
    Positive(initial_altitude_m, "initial altitude must be positive and finite");
    Positive(min_altitude_m, "min altitude must be positive and finite");
    Positive(max_altitude_m, "max altitude must be positive and finite");
    Positive(max_vertical_speed_m_s, "max speed must be positive and finite");
    Positive(reference_acceleration_m_s2, "reference acceleration must be positive and finite");
    Positive(max_input_ttl_s, "max TTL must be positive and finite");
    Positive(position_gain, "position gain must be positive and finite");
    Positive(velocity_gain, "velocity gain must be positive and finite");
    if (step_s < 0.002 || step_s > 0.02) throw std::invalid_argument("step must be 0.002 to 0.02 seconds");
    if (min_altitude_m >= max_altitude_m || initial_altitude_m < min_altitude_m || initial_altitude_m > max_altitude_m)
        throw std::invalid_argument("invalid altitude bounds");
    const double weight = mass_kg * gravity_m_s2;
    if (!std::isfinite(weight) || max_thrust_n <= weight)
        throw std::invalid_argument("thrust must exceed weight");
}

VerticalPlant::VerticalPlant(Config config) : config_(config) {
    config_.Validate();
    Reset();
}
void VerticalPlant::Reset() {
    // 【物理初态】初始速度为零，实际推力等于m*g，代表空中平衡悬停。
    state_ = {config_.initial_altitude_m, 0.0, config_.mass_kg * config_.gravity_m_s2};
}
void VerticalPlant::Step(double command, double dt) {
    if (!std::isfinite(command) || command < 0.0 || command > config_.max_thrust_n)
        throw std::invalid_argument("thrust command outside bounds");
    Positive(dt, "dt must be positive and finite");
    const double tau = config_.thrust_time_constant_s;
    // 【执行器响应】总推力按一阶模型趋近命令；当前步内命令保持不变，可解析积分。
    // expm1用于准确计算1-exp(-dt/tau)，减少小步长下的相减误差。
    const double decay = std::exp(-dt / tau);
    const double one_minus_decay = -std::expm1(-dt / tau);
    const double acceleration = command / config_.mass_kg - config_.gravity_m_s2;
    const double transient = (state_.thrust_n - command) / config_.mass_kg;
    PlantState candidate;
    // 【动力学】推力大于重力时向上加速；transient项保留推力响应滞后的影响。
    candidate.altitude_m = state_.altitude_m + state_.vertical_speed_m_s * dt
        + 0.5 * acceleration * dt * dt + transient * tau * (dt - tau * one_minus_decay);
    candidate.vertical_speed_m_s = state_.vertical_speed_m_s + acceleration * dt
        + transient * tau * one_minus_decay;
    candidate.thrust_n = command + (state_.thrust_n - command) * decay;
    if (!std::isfinite(candidate.altitude_m) || !std::isfinite(candidate.vertical_speed_m_s) || !std::isfinite(candidate.thrust_n))
        throw std::runtime_error("nonfinite plant state");
    // 候选状态全部有效后再提交，异常时保留上一有效状态供诊断。
    state_ = candidate;
}

Core::Core(Config config) : config_(config), plant_(config) { Reset(); }
void Core::Reset() {
    plant_.Reset();
    clock_s_ = 0.0;
    target_m_ = config_.initial_altitude_m;
    reference_vz_ = commanded_vz_ = deadline_s_ = 0.0;
    commanded_thrust_n_ = config_.mass_kg * config_.gravity_m_s2;
    has_deadline_ = false;
    ticks_ = 0;
    mode_ = Mode::Hold;
    reason_ = Reason::None;
}
void Core::ObserveClock(double now) {
    // 【墙钟门禁】使用调用方单调时钟；禁止倒退，在执行运动前检查TTL。
    // 即使调度停顿数秒，恢复调用时仍先锁存保护，不补执行过期输入。
    if (!std::isfinite(now) || now < 0.0 || now < clock_s_)
        throw std::invalid_argument("monotonic clock must be nonnegative and never decrease");
    clock_s_ = now;
    if (has_deadline_ && now >= deadline_s_ - 1e-12) Protect(Reason::InputTimeout);
}
void Core::SetControl(double speed, double ttl, double now) {
    // 【合法运动入口】这里只检查数值和模型范围；B仍负责权限、控制权、序列和报文年龄。
    if (!std::isfinite(speed) || std::abs(speed) > config_.max_vertical_speed_m_s)
        throw std::invalid_argument("vertical speed outside bounds");
    if (!std::isfinite(ttl) || ttl <= 0.0 || ttl > config_.max_input_ttl_s || !std::isfinite(now + ttl))
        throw std::invalid_argument("remaining TTL outside bounds");
    ObserveClock(now);
    if (mode_ == Mode::Protected) throw std::logic_error("protection latched");
    commanded_vz_ = speed;
    // ttl必须是B扣除报文年龄后的剩余有效期，不能给重复旧报文重发完整TTL。
    deadline_s_ = now + ttl;
    has_deadline_ = true;
    mode_ = Mode::Active;
    reason_ = Reason::None;
}
void Core::Hold(Mode mode, Reason reason) {
    // 【停止/保护公共路径】保持点取实际高度，清除旧运动输入；不会瞬移或断电。
    target_m_ = std::clamp(plant_.State().altitude_m, config_.min_altitude_m, config_.max_altitude_m);
    reference_vz_ = commanded_vz_ = 0.0;
    has_deadline_ = false;
    mode_ = mode;
    reason_ = reason;
}
void Core::Stop() { if (mode_ != Mode::Protected) Hold(Mode::Hold, Reason::Stop); }
void Core::Protect(Reason reason) {
    if (reason < Reason::InputTimeout || reason > Reason::NumericalFault)
        throw std::invalid_argument("invalid protection reason");
    if (mode_ != Mode::Protected) Hold(Mode::Protected, reason);
}
void Core::Resume(bool neutral_confirmed) {
    // 【显式恢复】B确认新来源中立后才能解除保护，解除后先保持而不是继续旧动作。
    if (!neutral_confirmed) throw std::invalid_argument("B must confirm neutral input");
    Hold(Mode::Hold, Reason::None);
}
void Core::Step(double now) {
    // 【控制循环1】先判断输入有效性，再按固定dt更新受限参考速度与参考高度。
    ObserveClock(now);
    const double delta = config_.reference_acceleration_m_s2 * config_.step_s;
    reference_vz_ += std::clamp(commanded_vz_ - reference_vz_, -delta, delta);
    const double next_z = target_m_ + reference_vz_ * config_.step_s;
    if (next_z < config_.min_altitude_m || next_z > config_.max_altitude_m) Protect(Reason::ReferenceLimit);
    else target_m_ = next_z;
    const auto& state = plant_.State();
    // 【控制循环2】位置误差负责回到目标，速度误差负责阻尼，m*g补偿重力。
    const double desired_acceleration = config_.position_gain * (target_m_ - state.altitude_m)
        + config_.velocity_gain * (reference_vz_ - state.vertical_speed_m_s);
    const double raw_thrust = config_.mass_kg * (config_.gravity_m_s2 + desired_acceleration);
    if (!std::isfinite(raw_thrust)) {
        Protect(Reason::NumericalFault);
        throw std::runtime_error("nonfinite controller output");
    }
    // 【控制循环3】推力限制在执行器能力范围后，模型推进一个固定步长。
    commanded_thrust_n_ = std::clamp(raw_thrust, 0.0, config_.max_thrust_n);
    try { plant_.Step(commanded_thrust_n_, config_.step_s); }
    catch (...) { Protect(Reason::NumericalFault); throw; }
    // 【控制循环4】成功才增加仿真序列；实际高度检查与参考高度检查分开。
    ++ticks_;
    if (plant_.State().altitude_m < config_.min_altitude_m || plant_.State().altitude_m > config_.max_altitude_m)
        Protect(Reason::StateLimit);
}
Snapshot Core::State() const {
    return {plant_.State(), static_cast<double>(ticks_) * config_.step_s, clock_s_, target_m_,
            reference_vz_, commanded_thrust_n_, ticks_, mode_, reason_};
}
} // namespace drone
