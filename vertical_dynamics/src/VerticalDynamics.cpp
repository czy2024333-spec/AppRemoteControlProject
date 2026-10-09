#include "vertical_dynamics/VerticalDynamics.h"

#include <cmath>

namespace vertical_dynamics {

Result VerticalDynamics::initialize(
    double mass_kg,
    double gravity_mps2,
    double time_step_s) noexcept {
    if (!std::isfinite(mass_kg) || mass_kg <= 0.0) {
        return {Error::invalid_mass};
    }
    if (!std::isfinite(gravity_mps2) || gravity_mps2 <= 0.0) {
        return {Error::invalid_gravity};
    }
    if (!std::isfinite(time_step_s) || time_step_s <= 0.0) {
        return {Error::invalid_time_step};
    }

    mass_kg_ = mass_kg;
    gravity_mps2_ = gravity_mps2;
    time_step_s_ = time_step_s;
    thrust_n_ = 0.0;
    state_ = {};
    initialized_ = true;
    return {};
}

Result VerticalDynamics::set_thrust(double thrust_n) noexcept {
    if (!initialized_) {
        return {Error::not_initialized};
    }
    if (!std::isfinite(thrust_n) || thrust_n < 0.0) {
        return {Error::invalid_thrust};
    }

    thrust_n_ = thrust_n;
    return {};
}

Result VerticalDynamics::step() noexcept {
    if (!initialized_) {
        return {Error::not_initialized};
    }

    const double acceleration_mps2 = thrust_n_ / mass_kg_ - gravity_mps2_;
    const double next_velocity_mps =
        state_.vertical_velocity_mps + acceleration_mps2 * time_step_s_;
    if (!std::isfinite(acceleration_mps2) || !std::isfinite(next_velocity_mps)) {
        return {Error::numerical_overflow};
    }

    const double next_height_m =
        state_.height_m + next_velocity_mps * time_step_s_;
    if (!std::isfinite(next_height_m)) {
        return {Error::numerical_overflow};
    }

    if (next_height_m < 0.0 ||
        (next_height_m == 0.0 && next_velocity_mps < 0.0)) {
        state_ = {0.0, 0.0};
    } else {
        state_ = {next_height_m, next_velocity_mps};
    }
    return {};
}

Result VerticalDynamics::get_state(State& state) const noexcept {
    if (!initialized_) {
        return {Error::not_initialized};
    }

    state = state_;
    return {};
}

Result VerticalDynamics::reset() noexcept {
    if (!initialized_) {
        return {Error::not_initialized};
    }

    thrust_n_ = 0.0;
    state_ = {};
    return {};
}

}  // namespace vertical_dynamics
