#include "frame_scheduler.hpp"
#include "drone_core.hpp"
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>

namespace {
void Check(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
void FrameRates() {
    double baseline_height = 0.0;
    for (const int rate : {30, 60, 144}) {
        drone::FrameScheduler scheduler;
        drone::Core core;
        Check(scheduler.Initialize(0.01, 0.0), "initialize failed");
        int count = 0;
        for (int i = 0; i < rate * 3; ++i) {
            core.SetControl(0.3, 0.5, static_cast<double>(i) / rate);
            const auto stats = scheduler.Advance(1.0 / rate, static_cast<double>(i + 1) / rate,
                [&](double now) { core.Step(now); ++count; return 0; }, [&] { core.Protect(); return 0; });
            Check(stats.result == drone::FrameResult::Ok, "normal frame unexpectedly protected");
            Check(stats.steps <= 8, "step budget exceeded");
        }
        Check(count == 300, "frame rate changed simulation duration");
        if (rate == 30) baseline_height = core.State().plant.altitude_m;
        else Check(std::abs(core.State().plant.altitude_m - baseline_height) < 1e-12,
                   "frame rate changed plant trajectory under constant input");
    }
}
void WallGapProtection() {
    drone::FrameScheduler scheduler;
    drone::Core core;
    scheduler.Initialize(0.01, 100.0);
    core.SetControl(0.3, 0.5, 100.0);
    const auto stats = scheduler.Advance(0.01, 105.0,
        [&](double now) { core.Step(now); return 0; }, [&] { core.Protect(); return 0; });
    Check(stats.result == drone::FrameResult::ProtectedGap && stats.steps == 0, "wall gap replayed motion");
    Check(core.State().mode == drone::Mode::Protected && core.State().sequence == 0, "stall did not protect before stepping");
}
void OverloadProtection() {
    drone::FrameScheduler scheduler;
    scheduler.Initialize(0.01, 0.0);
    int count = 0, protections = 0;
    const auto stats = scheduler.Advance(0.09, 0.09,
        [&](double) { ++count; return 0; }, [&] { ++protections; return 0; });
    Check(stats.result == drone::FrameResult::ProtectedGap && protections == 1 && count == 0,
        "over-budget work executed motion");
    Check(scheduler.RemainderSeconds() == 0.0, "dropped backlog retained");
}
void DisabledMode() {
    drone::FrameScheduler scheduler;
    scheduler.Initialize(0.01, 0.0);
    scheduler.Disable();
    int calls = 0;
    scheduler.Advance(1.0, 1.0, [&](double) { ++calls; return 0; }, [&] { ++calls; return 0; });
    Check(calls == 0 && !scheduler.Enabled(), "disabled/replay owner still advanced core");
    Check(scheduler.Initialize(0.01, 2.0), "explicit re-enable failed");
    scheduler.Advance(0.01, 2.01, [&](double) { ++calls; return 0; }, [] { return 0; });
    Check(calls == 1, "re-enable replayed old backlog");
}
void CurrentTimeCatchup() {
    drone::FrameScheduler scheduler;
    scheduler.Initialize(0.01, 100.0);
    int count = 0;
    scheduler.Advance(0.03, 100.03, [&](double now) {
        Check(now == 100.03, "catch-up used historical time"); ++count; return 0;
    }, [] { return 0; });
    Check(count == 3, "catch-up count wrong");
}
void InvalidAndFailure() {
    drone::FrameScheduler scheduler;
    Check(!scheduler.Initialize(0.0, 0.0), "invalid dt accepted");
    scheduler.Initialize(0.01, 1.0);
    const auto invalid = scheduler.Advance(0.01, 0.5, [](double) { return 0; }, [] { return 0; });
    Check(invalid.result == drone::FrameResult::InvalidInput, "clock rollback accepted");
    const auto nan = scheduler.Advance(std::numeric_limits<double>::quiet_NaN(), 1.1,
        [](double) { return 0; }, [] { return 0; });
    Check(nan.result == drone::FrameResult::InvalidInput, "NaN accepted");
    int protections = 0;
    const auto failure = scheduler.Advance(0.02, 1.02, [](double) { return 3; }, [&] { ++protections; return 0; });
    Check(failure.result == drone::FrameResult::CoreError && protections == 1 && !scheduler.Enabled(),
        "core failure did not stop scheduling");
}
}
int main() {
    const struct { const char* name; void (*run)(); } cases[] = {
        {"30_60_144hz", FrameRates}, {"wall_gap_protection", WallGapProtection},
        {"overload_protection", OverloadProtection}, {"disabled_mode", DisabledMode},
        {"current_time_catchup", CurrentTimeCatchup}, {"invalid_and_failure", InvalidAndFailure}
    };
    int failures = 0;
    for (const auto& test : cases) {
        try { test.run(); std::cout << "PASS " << test.name << '\n'; }
        catch (const std::exception& error) { ++failures; std::cerr << "FAIL " << test.name << ": " << error.what() << '\n'; }
    }
    std::cout << "scheduler_tests=6 failures=" << failures << '\n';
    return failures ? 1 : 0;
}
