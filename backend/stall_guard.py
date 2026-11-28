"""控制线程卡顿判定。

`SimulationService.run()` 原先用一个条件同时承担"轻微抖动"和"真卡死"：

    if now-last>.1 or now-next_tick>.08: 保护

50Hz 调度下，操作系统一次正常的调度抖动或磁盘/GC 停顿就能超过 80ms，于是保护被
反复锁存，操作员必须不停重新申请控制权。这里拆成三档，把"打断操纵"留给真正的
卡死和持续劣化，单次中等滞后只留证据：

- hard   ：单次循环或 tick 滞后超过 hard_ms，立即保护（真卡死）；
- streak ：连续 3 次超过 soft_ms 且累计不低于 200ms，保护（持续劣化）；
- warn   ：单次超过 warn_ms 但未达 hard_ms，只写审计 SCHEDULER_SLOW，不锁存。

阈值随仿真步长自适应；50Hz（dt=0.02）下为 soft=80ms / warn=250ms / hard=500ms。
"""


class StallGuard:
    STREAK_COUNT = 3
    STREAK_TOTAL_MS = 200.0
    WARN_INTERVAL_S = 5.0

    def __init__(self, dt):
        if not isinstance(dt, (int, float)) or dt <= 0:
            raise ValueError('仿真步长必须为正数')
        self.dt = dt
        # 单次软超限：3 个仿真步长，且不低于 80ms。
        self.soft_ms = max(3 * dt * 1000.0, 80.0)
        # 需要留痕的单次滞后：不足以打断操纵，但要能复盘。
        self.warn_ms = max(6 * dt * 1000.0, 250.0)
        # 单次硬卡顿：10 个仿真步长，且不低于 500ms。
        self.hard_ms = max(10 * dt * 1000.0, 500.0)
        self.streak_count = 0
        self.streak_time_ms = 0.0
        self.last_warn_at = None

    def reset(self):
        self.streak_count = 0
        self.streak_time_ms = 0.0

    def check(self, now, last_loop_start, next_tick):
        """返回 (是否保护, 诊断)。

        诊断里的 reason 为 None / SINGLE_SLOW / STREAK_LIMIT / HARD_STALL，
        protected 表示这一轮是否应当锁存保护。
        """
        loop_time_ms = (now - last_loop_start) * 1000.0
        gap_ms = max(0.0, (now - next_tick) * 1000.0)
        diag = dict(gap_ms=round(gap_ms, 2), loop_time_ms=round(loop_time_ms, 2),
                    missed_cycles=int(gap_ms / (self.dt * 1000.0)) if gap_ms > 0 else 0,
                    reason=None, protected=False)

        if loop_time_ms > self.hard_ms or gap_ms > self.hard_ms:
            # 单次硬卡顿：不累计，直接保护并清空劣化计数。
            self.reset()
            diag.update(reason='HARD_STALL', protected=True)
            return True, diag

        worst = max(loop_time_ms, gap_ms)
        if worst > self.soft_ms:
            self.streak_count += 1
            self.streak_time_ms += worst
        else:
            self.reset()

        if self.streak_count >= self.STREAK_COUNT and self.streak_time_ms >= self.STREAK_TOTAL_MS:
            total = self.streak_time_ms
            self.reset()
            diag.update(reason='STREAK_LIMIT', protected=True, streak_ms=round(total, 2))
            return True, diag

        # 250~500ms 的单次滞后只留证据：限频 5 秒一次，避免审计被刷。
        if worst > self.warn_ms and (self.last_warn_at is None or now - self.last_warn_at >= self.WARN_INTERVAL_S):
            self.last_warn_at = now
            diag['reason'] = 'SINGLE_SLOW'
            return False, diag

        return False, diag
