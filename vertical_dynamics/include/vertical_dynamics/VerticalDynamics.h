#pragma once

namespace vertical_dynamics {

enum class Error {
    none,
    not_initialized,
    invalid_mass,
    invalid_gravity,
    invalid_thrust,
    invalid_time_step,
    numerical_overflow
};

struct Result {
    Error error = Error::none;

    constexpr bool ok() const noexcept { return error == Error::none; }
    constexpr explicit operator bool() const noexcept { return ok(); }
};

struct State {
    double height_m = 0.0;
    double vertical_velocity_mps = 0.0;
};

class VerticalDynamics {
public:
    Result initialize(double mass_kg, double gravity_mps2, double time_step_s) noexcept;
    Result set_thrust(double thrust_n) noexcept;
    Result step() noexcept;
    Result get_state(State& state) const noexcept;
    Result reset() noexcept;

private:
    double mass_kg_ = 0.0;
    double gravity_mps2_ = 0.0;
    double time_step_s_ = 0.0;
    double thrust_n_ = 0.0;
    State state_{};
    bool initialized_ = false;
};

}  // namespace vertical_dynamics
