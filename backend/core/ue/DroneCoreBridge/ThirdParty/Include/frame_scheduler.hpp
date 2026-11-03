#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace drone {
enum class FrameResult { Ok, InvalidInput, ProtectedGap, CoreError };
struct FrameStats {
    FrameResult result = FrameResult::Ok;
    std::uint32_t steps = 0;
    double dropped_time_s = 0.0;
    int core_status = 0;
};

// Header-only, no exceptions, no UE dependency. Call on the owning game thread.
class FrameScheduler {
public:
    bool Initialize(double step_s, double now_s, std::uint32_t max_steps = 8, double max_gap_s = 0.1) {
        // 【调度起点】清空旧积压，绑定当前墙钟；重新启用时不重放停用期间的时间。
        if (!std::isfinite(step_s) || step_s < 0.002 || step_s > 0.02 ||
            !std::isfinite(now_s) || now_s < 0.0 || max_steps == 0 ||
            !std::isfinite(max_gap_s) || max_gap_s <= 0.0) return false;
        step_s_ = step_s;
        last_now_s_ = now_s;
        max_steps_ = max_steps;
        max_gap_s_ = max_gap_s;
        accumulator_s_ = 0.0;
        initialized_ = true;
        enabled_ = true;
        return true;
    }
    void Disable() { enabled_ = false; accumulator_s_ = 0.0; }
    bool Enabled() const { return enabled_; }
    double RemainderSeconds() const { return accumulator_s_; }

    template<class StepFunction, class ProtectFunction>
    FrameStats Advance(double frame_dt_s, double now_s, StepFunction step, ProtectFunction protect) {
        FrameStats stats;
        if (!initialized_ || !std::isfinite(frame_dt_s) || frame_dt_s < 0.0 ||
            !std::isfinite(now_s) || now_s < last_now_s_) {
            stats.result = FrameResult::InvalidInput;
            return stats;
        }
        if (!enabled_) {
            last_now_s_ = now_s;
            return stats;
        }
        // 【两种时间】帧时间用于累计仿真工作量，墙钟间隔用于发现暂停/断点/调度卡顿。
        const double wall_gap = now_s - last_now_s_;
        last_now_s_ = now_s;
        const double pending = accumulator_s_ + frame_dt_s;
        const auto capacity = static_cast<double>(max_steps_) * step_s_;
        // 【卡顿保护】在任何动力学步之前保护，并丢弃积压，避免恢复后突发执行旧运动。
        if (frame_dt_s > max_gap_s_ + 1e-12 || wall_gap > max_gap_s_ + 1e-12 ||
            pending >= capacity + step_s_ - 1e-12) {
            stats.core_status = protect();
            stats.result = stats.core_status == 0 ? FrameResult::ProtectedGap : FrameResult::CoreError;
            stats.dropped_time_s = pending;
            accumulator_s_ = 0.0;
            if (stats.core_status != 0) enabled_ = false;
            return stats;
        }
        // 【固定步长】帧率只影响一帧算几步；模型每步仍是step_s，剩余不足一步留给下一帧。
        accumulator_s_ = pending;
        while (accumulator_s_ >= step_s_ - 1e-12 && stats.steps < max_steps_) {
            // 【TTL关键点】所有补步使用当前墙钟，不能传历史时刻让过期输入重新有效。
            stats.core_status = step(now_s);
            if (stats.core_status != 0) {
                // 【故障路径】核心出错后停止调度，保留错误码交给宿主报告。
                protect();
                enabled_ = false;
                accumulator_s_ = 0.0;
                stats.result = FrameResult::CoreError;
                return stats;
            }
            accumulator_s_ = std::max(0.0, accumulator_s_ - step_s_);
            ++stats.steps;
        }
        return stats;
    }
private:
    double step_s_ = 0.01;
    double last_now_s_ = 0.0;
    double accumulator_s_ = 0.0;
    double max_gap_s_ = 0.1;
    std::uint32_t max_steps_ = 8;
    bool initialized_ = false;
    bool enabled_ = false;
};
} // namespace drone
