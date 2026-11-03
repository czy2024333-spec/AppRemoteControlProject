#include "drone_core.hpp"
#include "drone_core_c.h"
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>

namespace {
void Check(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
void Near(double actual, double expected, double tolerance, const char* message) {
    Check(std::isfinite(actual) && std::abs(actual - expected) <= tolerance, message);
}
template<class Exception, class Function> void Throws(Function function) {
    bool rejected = false;
    try { function(); } catch (const Exception&) { rejected = true; }
    Check(rejected, "expected exception was not raised");
}
void Advance(drone::Core& core, int count, double dt, double speed, bool renew) {
    for (int i = 0; i < count; ++i) {
        const double now = core.State().monotonic_time_s;
        if (renew && i % 10 == 0) core.SetControl(speed, 0.5, now);
        core.Step(now + dt);
    }
}

void ForceBalance() {
    drone::Config c;
    const double weight = c.mass_kg * c.gravity_m_s2;
    for (const double scale : {0.8, 1.0, 1.2}) {
        drone::VerticalPlant plant(c);
        for (int i = 0; i < 100; ++i) plant.Step(weight * scale, c.step_s);
        const auto s = plant.State();
        if (scale < 1.0) Check(s.altitude_m < 1.0 && s.vertical_speed_m_s < 0.0, "T<mg must descend");
        else if (scale > 1.0) Check(s.altitude_m > 1.0 && s.vertical_speed_m_s > 0.0, "T>mg must ascend");
        else { Near(s.altitude_m, 1.0, 1e-12, "T=mg height drift"); Near(s.vertical_speed_m_s, 0.0, 1e-12, "T=mg speed drift"); }
        const double a = (scale - 1.0) * c.gravity_m_s2;
        const double analytic_v = a * (1.0 - c.thrust_time_constant_s * (1.0 - std::exp(-1.0 / c.thrust_time_constant_s)));
        Near(s.vertical_speed_m_s, analytic_v, 1e-11, "constant thrust analytical velocity mismatch");
    }
}
void PlantStepPartition() {
    drone::VerticalPlant one, many;
    one.Step(0.35, 1.0);
    for (int i = 0; i < 100; ++i) many.Step(0.35, 0.01);
    Near(one.State().altitude_m, many.State().altitude_m, 1e-11, "plant integration depends on partition");
    Near(one.State().vertical_speed_m_s, many.State().vertical_speed_m_s, 1e-11, "plant speed partition mismatch");
}
void HoverIsolation() {
    drone::Core first, second;
    Advance(first, 100, 0.01, 0.0, false);
    Near(first.State().plant.altitude_m, 1.0, 1e-12, "hover drift");
    Check(second.State().sequence == 0, "instances share ticks");
    auto copied = first.State();
    copied.plant.altitude_m = 99.0;
    Near(first.State().plant.altitude_m, 1.0, 1e-12, "snapshot mutated core");
}
void UpDownStop() {
    for (const double speed : {-0.3, 0.3}) {
        drone::Core core;
        Advance(core, 200, 0.01, speed, true);
        const double anchor = core.State().plant.altitude_m;
        Check((anchor - 1.0) * (speed > 0.0 ? 1.0 : -1.0) > 0.3, "motion direction/magnitude wrong");
        core.Stop();
        Advance(core, 400, 0.01, 0.0, false);
        Near(core.State().plant.altitude_m, anchor, 0.01, "stop failed to settle to captured position");
        Near(core.State().plant.vertical_speed_m_s, 0.0, 0.01, "stop failed to brake");
        Check(core.State().mode == drone::Mode::Hold, "stop mode wrong");
    }
}
void TimeoutBoundary() {
    drone::Core core;
    core.SetControl(0.3, 0.1, 0.0);
    for (int i = 1; i <= 9; ++i) core.Step(i * 0.01);
    Check(core.State().mode == drone::Mode::Active, "expired too early");
    core.Step(0.1);
    Check(core.State().reason == drone::Reason::InputTimeout, "deadline boundary not protected");
    Throws<std::logic_error>([&] { core.SetControl(0.3, 0.1, 0.1); });
    core.Stop();
    Check(core.State().mode == drone::Mode::Protected, "stop cleared latch");
    Throws<std::invalid_argument>([&] { core.Resume(false); });
    core.Resume(true);
    Check(core.State().mode == drone::Mode::Hold, "resume retained motion");
    Near(core.State().reference_vertical_speed_m_s, 0.0, 0.0, "resume retained reference speed");
}
void SchedulerStall() {
    drone::Core core;
    core.SetControl(0.3, 0.1, 100.0);
    core.Step(105.0);
    Check(core.State().reason == drone::Reason::InputTimeout, "wall clock stall missed expiration");
    Near(core.State().simulation_time_s, 0.01, 1e-12, "stall advanced model by wall gap");
    Near(core.State().plant.altitude_m, 1.0, 1e-12, "expired motion executed after stall");
    Throws<std::invalid_argument>([&] { core.Step(104.0); });
    Check(core.State().sequence == 1, "invalid clock advanced model");
}
void ReferenceLimit() {
    drone::Config c;
    c.max_altitude_m = 1.1;
    drone::Core core(c);
    for (int i = 0; i < 200 && core.State().mode != drone::Mode::Protected; ++i) {
        core.SetControl(0.5, 0.5, i * 0.01);
        core.Step((i + 1) * 0.01);
    }
    Check(core.State().mode == drone::Mode::Protected, "altitude limit not protected");
    Check(core.State().target_altitude_m <= c.max_altitude_m, "reference escaped limit");
    core.Reset();
    Check(core.State().sequence == 0 && core.State().mode == drone::Mode::Hold, "reset failed");
}
void InvalidInputs() {
    drone::Core core;
    const double nan = std::numeric_limits<double>::quiet_NaN();
    Throws<std::invalid_argument>([&] { core.SetControl(nan, 0.1, 0.0); });
    Throws<std::invalid_argument>([&] { core.SetControl(0.6, 0.1, 0.0); });
    Throws<std::invalid_argument>([&] { core.SetControl(0.1, 0.0, 0.0); });
    Throws<std::invalid_argument>([&] { core.SetControl(0.1, 1.0, 0.0); });
    Throws<std::invalid_argument>([&] { core.Step(nan); });
    Throws<std::invalid_argument>([&] { core.Protect(drone::Reason::None); });
    drone::Config bad;
    bad.mass_kg = -1.0;
    Throws<std::invalid_argument>([&] { drone::Core invalid(bad); });
    bad = {};
    bad.max_thrust_n = 0.1;
    Throws<std::invalid_argument>([&] { drone::Core invalid(bad); });
    Check(core.State().sequence == 0, "invalid input changed model time");
}
void ControllerStepConvergence() {
    double final_heights[3] = {};
    const double steps[3] = {0.02, 0.01, 0.005};
    for (int j = 0; j < 3; ++j) {
        drone::Config c;
        c.step_s = steps[j];
        drone::Core core(c);
        const int count = static_cast<int>(2.0 / c.step_s);
        Advance(core, count, c.step_s, 0.3, true);
        final_heights[j] = core.State().plant.altitude_m;
    }
    Check(std::abs(final_heights[1] - final_heights[2]) < std::abs(final_heights[0] - final_heights[2]), "halving dt failed to converge");
    Near(final_heights[1], final_heights[2], 0.01, "100Hz vs 200Hz mismatch exceeds 1cm");
}
void CApiContract() {
    dc_handle* h = nullptr;
    Check(dc_create(nullptr, &h) == DC_OK && h, "C create failed");
    struct Cleanup { dc_handle* h; ~Cleanup() { dc_destroy(h); } } cleanup{h};
    Check(dc_step(nullptr, 0.0) == DC_INVALID_ARGUMENT, "null handle accepted");
    Check(dc_get_state(h, nullptr) == DC_INVALID_ARGUMENT, "null output accepted");
    Check(dc_set_control(h, 0.3, 0.1, 0.0) == DC_OK, "C command failed");
    Check(dc_step(h, 1.0) == DC_OK, "C stalled step failed");
    Check(dc_set_control(h, 0.3, 0.1, 1.0) == DC_PROTECTED, "C exception did not map to protected");
    dc_state s{};
    Check(dc_get_state(h, &s) == DC_OK, "C snapshot failed");
    Check(s.interface_version == 1 && s.mode == DC_PROTECTION && s.reason == DC_INPUT_TIMEOUT, "C snapshot contract mismatch");
    Near(s.quaternion_xyzw[3], 1.0, 0.0, "identity quaternion wrong");
    Check(dc_resume(h, 2) == DC_INVALID_ARGUMENT && dc_resume(h, 0) == DC_INVALID_ARGUMENT, "non-neutral resume accepted");
    Check(dc_resume(h, 1) == DC_OK, "neutral resume rejected");
    Check(dc_reset(h) == DC_OK, "C reset failed");
    dc_config bad{};
    Check(dc_default_config(&bad) == DC_OK, "default config failed");
    bad.mass_kg = 0.0;
    dc_handle* invalid = h;
    Check(dc_create(&bad, &invalid) == DC_INVALID_ARGUMENT && invalid == nullptr, "C invalid create output wrong");
}
}

int main() {
    const struct { const char* name; void (*run)(); } cases[] = {
        {"force_balance", ForceBalance}, {"plant_step_partition", PlantStepPartition},
        {"hover_and_isolation", HoverIsolation}, {"up_down_stop", UpDownStop},
        {"timeout_boundary", TimeoutBoundary}, {"scheduler_stall", SchedulerStall},
        {"reference_limit_and_reset", ReferenceLimit}, {"invalid_inputs", InvalidInputs},
        {"controller_step_convergence", ControllerStepConvergence}, {"c_api_contract", CApiContract}
    };
    int failures = 0;
    for (const auto& test : cases) {
        try { test.run(); std::cout << "PASS " << test.name << '\n'; }
        catch (const std::exception& error) { ++failures; std::cerr << "FAIL " << test.name << ": " << error.what() << '\n'; }
    }
    std::cout << "tests=" << sizeof(cases) / sizeof(cases[0]) << " failures=" << failures << '\n';
    return failures ? 1 : 0;
}
