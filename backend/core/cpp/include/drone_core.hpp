#pragma once
#include <cstdint>

namespace drone {
enum class Mode : std::int32_t { Hold = 0, Active = 1, Protected = 2 };
enum class Reason : std::int32_t {
    None = 0, Stop = 1, InputTimeout = 2, Service = 3,
    ReferenceLimit = 4, StateLimit = 5, NumericalFault = 6
};

struct Config {
    double mass_kg = 0.03;
    double gravity_m_s2 = 9.81;
    double max_thrust_n = 0.575;
    double thrust_time_constant_s = 0.072;
    double step_s = 0.01;
    double initial_altitude_m = 1.0;
    double min_altitude_m = 0.3;
    double max_altitude_m = 10.0;
    double max_vertical_speed_m_s = 0.5;
    double reference_acceleration_m_s2 = 0.5;
    double max_input_ttl_s = 0.5;
    double position_gain = 15.0;
    double velocity_gain = 9.0;
    void Validate() const;
};

struct PlantState {
    double altitude_m = 1.0;
    double vertical_speed_m_s = 0.0;
    double thrust_n = 0.2943;
};

// Independent 1D plant: z'=v, v'=T/m-g, T'=(Tcmd-T)/tau.
// Closed-form integration for a constant thrust command over each step.
class VerticalPlant {
public:
    explicit VerticalPlant(Config config = {});
    void Reset();
    void Step(double thrust_command_n, double dt_s);
    const PlantState& State() const { return state_; }
private:
    Config config_;
    PlantState state_;
};

struct Snapshot {
    PlantState plant;
    double simulation_time_s = 0.0;
    double monotonic_time_s = 0.0;
    double target_altitude_m = 1.0;
    double reference_vertical_speed_m_s = 0.0;
    double commanded_thrust_n = 0.2943;
    std::uint64_t sequence = 0;
    Mode mode = Mode::Hold;
    Reason reason = Reason::None;
};

// Single-threaded. B performs authorization/arbitration before calling this.
class Core {
public:
    explicit Core(Config config = {});
    void Reset();
    void SetControl(double vertical_speed_m_s, double remaining_ttl_s, double monotonic_now_s);
    void Step(double monotonic_now_s);
    void Stop();
    void Protect(Reason reason = Reason::Service);
    void Resume(bool neutral_confirmed);
    Snapshot State() const;
private:
    void ObserveClock(double monotonic_now_s);
    void Hold(Mode mode, Reason reason);
    Config config_;
    VerticalPlant plant_;
    double clock_s_ = 0.0;
    double target_m_ = 1.0;
    double reference_vz_ = 0.0;
    double commanded_vz_ = 0.0;
    double commanded_thrust_n_ = 0.2943;
    double deadline_s_ = 0.0;
    bool has_deadline_ = false;
    std::uint64_t ticks_ = 0;
    Mode mode_ = Mode::Hold;
    Reason reason_ = Reason::None;
};
} // namespace drone
