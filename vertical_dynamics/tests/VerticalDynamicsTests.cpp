#include "vertical_dynamics/VerticalDynamics.h"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>

namespace {

using vertical_dynamics::Error;
using vertical_dynamics::State;
using vertical_dynamics::VerticalDynamics;

int failures = 0;

void check(bool condition, const char* expression, int line) {
    if (!condition) {
        std::cerr << "Line " << line << ": " << expression << '\n';
        ++failures;
    }
}

#define CHECK(expression) check(static_cast<bool>(expression), #expression, __LINE__)

bool near(double actual, double expected) {
    return std::abs(actual - expected) < 1e-9;
}

State state_of(const VerticalDynamics& model) {
    State state;
    CHECK(model.get_state(state));
    return state;
}

void test_uninitialized_model() {
    VerticalDynamics model;
    State state{123.0, 456.0};
    CHECK(model.set_thrust(0.0).error == Error::not_initialized);
    CHECK(model.step().error == Error::not_initialized);
    CHECK(model.get_state(state).error == Error::not_initialized);
    CHECK(near(state.height_m, 123.0));
    CHECK(near(state.vertical_velocity_mps, 456.0));
    CHECK(model.reset().error == Error::not_initialized);
}

void test_thrust_relationships() {
    VerticalDynamics model;
    CHECK(model.initialize(2.0, 10.0, 0.1));

    CHECK(model.set_thrust(20.0));
    CHECK(model.step());
    State state = state_of(model);
    CHECK(near(state.vertical_velocity_mps, 0.0));
    CHECK(near(state.height_m, 0.0));

    CHECK(model.set_thrust(220.0));
    CHECK(model.step());
    state = state_of(model);
    CHECK(near(state.vertical_velocity_mps, 10.0));
    CHECK(near(state.height_m, 1.0));

    CHECK(model.set_thrust(0.0));
    CHECK(model.step());
    state = state_of(model);
    CHECK(near(state.vertical_velocity_mps, 9.0));
    CHECK(near(state.height_m, 1.9));
}

void test_semi_implicit_integration_and_free_fall() {
    VerticalDynamics model;
    CHECK(model.initialize(2.0, 10.0, 0.1));
    CHECK(model.set_thrust(10.0));
    CHECK(model.step());
    State state = state_of(model);
    CHECK(near(state.vertical_velocity_mps, 0.0));
    CHECK(near(state.height_m, 0.0));

    CHECK(model.set_thrust(40.0));
    CHECK(model.step());
    state = state_of(model);
    CHECK(near(state.vertical_velocity_mps, 1.0));
    CHECK(near(state.height_m, 0.1));

    CHECK(model.set_thrust(0.0));
    CHECK(model.step());
    state = state_of(model);
    CHECK(near(state.vertical_velocity_mps, 0.0));
    CHECK(near(state.height_m, 0.1));
    CHECK(model.step());
    state = state_of(model);
    CHECK(near(state.vertical_velocity_mps, 0.0));
    CHECK(near(state.height_m, 0.0));
}

void test_ground_contact() {
    VerticalDynamics model;
    CHECK(model.initialize(1.0, 10.0, 1.0));
    CHECK(model.set_thrust(20.0));
    CHECK(model.step());
    CHECK(model.set_thrust(0.0));
    CHECK(model.step());
    CHECK(model.step());
    State state = state_of(model);
    CHECK(near(state.height_m, 0.0));
    CHECK(near(state.vertical_velocity_mps, 0.0));

    CHECK(model.step());
    state = state_of(model);
    CHECK(near(state.height_m, 0.0));
    CHECK(near(state.vertical_velocity_mps, 0.0));
}

void test_reset() {
    VerticalDynamics model;
    CHECK(model.initialize(1.0, 10.0, 1.0));
    CHECK(model.set_thrust(20.0));
    CHECK(model.step());
    CHECK(model.reset());
    State state = state_of(model);
    CHECK(near(state.height_m, 0.0));
    CHECK(near(state.vertical_velocity_mps, 0.0));

    CHECK(model.step());
    state = state_of(model);
    CHECK(near(state.height_m, 0.0));
    CHECK(near(state.vertical_velocity_mps, 0.0));
}

void test_invalid_inputs_are_reported_without_mutation() {
    VerticalDynamics model;
    const double infinity = std::numeric_limits<double>::infinity();
    const double nan = std::numeric_limits<double>::quiet_NaN();

    CHECK(model.initialize(0.0, 9.81, 0.1).error == Error::invalid_mass);
    CHECK(model.initialize(-1.0, 9.81, 0.1).error == Error::invalid_mass);
    CHECK(model.initialize(infinity, 9.81, 0.1).error == Error::invalid_mass);
    CHECK(model.initialize(1.0, 0.0, 0.1).error == Error::invalid_gravity);
    CHECK(model.initialize(1.0, -9.81, 0.1).error == Error::invalid_gravity);
    CHECK(model.initialize(1.0, nan, 0.1).error == Error::invalid_gravity);
    CHECK(model.initialize(1.0, 9.81, 0.0).error == Error::invalid_time_step);
    CHECK(model.initialize(1.0, 9.81, -0.1).error == Error::invalid_time_step);
    CHECK(model.initialize(1.0, 9.81, infinity).error == Error::invalid_time_step);
    CHECK(model.initialize(1.0, 9.81, nan).error == Error::invalid_time_step);
    CHECK(model.initialize(1.0, 10.0, 0.1));

    CHECK(model.set_thrust(-1.0).error == Error::invalid_thrust);
    CHECK(model.set_thrust(infinity).error == Error::invalid_thrust);
    CHECK(model.set_thrust(nan).error == Error::invalid_thrust);
    CHECK(model.set_thrust(20.0));
    State state = state_of(model);
    CHECK(near(state.height_m, 0.0));
    CHECK(near(state.vertical_velocity_mps, 0.0));
    CHECK(model.step());
    state = state_of(model);
    CHECK(near(state.vertical_velocity_mps, 1.0));
    CHECK(near(state.height_m, 0.1));
}

void test_numerical_overflow_is_reported_without_mutation() {
    VerticalDynamics model;
    CHECK(model.initialize(std::numeric_limits<double>::min(), 1.0, 1.0));
    CHECK(model.set_thrust(std::numeric_limits<double>::max()));
    CHECK(model.step().error == Error::numerical_overflow);
    State state = state_of(model);
    CHECK(near(state.height_m, 0.0));
    CHECK(near(state.vertical_velocity_mps, 0.0));
}

}  // namespace

int main() {
    test_uninitialized_model();
    test_thrust_relationships();
    test_semi_implicit_integration_and_free_fall();
    test_ground_contact();
    test_reset();
    test_invalid_inputs_are_reported_without_mutation();
    test_numerical_overflow_is_reported_without_mutation();

    if (failures != 0) {
        std::cerr << failures << " test assertion(s) failed\n";
        return EXIT_FAILURE;
    }

    std::cout << "All vertical dynamics tests passed\n";
    return EXIT_SUCCESS;
}
