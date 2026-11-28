import unittest
from stall_guard import StallGuard

PROD_DT = 0.02  # cluster_config.json 里 sim_rate_hz=50，生产步长


class Loop:
    """复现 run() 的调度：每轮判定一次，命中 tick 才推进 next_tick。"""

    def __init__(self, dt, start=1000.0):
        self.dt = dt
        self.guard = StallGuard(dt)
        self.now = start
        self.last = start
        self.next_tick = start + dt
        self.events = []
        self.protections = 0

    def step(self, elapsed):
        self.now += elapsed
        stalled, diag = self.guard.check(self.now, self.last, self.next_tick)
        self.last = self.now
        if stalled:
            self.protections += 1
        if diag['reason'] is not None:
            self.events.append(diag)
        if self.now >= self.next_tick:
            # 与 run() 一致：落后超过一个步长就重排，不追赶累积。
            if self.now - self.next_tick >= self.dt:
                self.next_tick = self.now + self.dt
            else:
                self.next_tick += self.dt
        return stalled, diag

    def run(self, pattern, count):
        for index in range(count):
            self.step(pattern(index))
        return self


class StallGuardIdleTests(unittest.TestCase):
    def test_idle_loop_never_protects(self):
        loop = Loop(PROD_DT).run(lambda i: 0.002, 2000)
        self.assertEqual(loop.protections, 0)
        self.assertEqual(loop.events, [])

    def test_normal_jitter_never_protects(self):
        # 上一版 WINDOW_MISSED 就是在这里持续误判：2ms 空转 + 每 20 轮一次 25ms 抖动。
        loop = Loop(PROD_DT).run(lambda i: 0.025 if i % 20 == 0 else 0.002, 2000)
        self.assertEqual(loop.protections, 0)
        self.assertEqual(loop.events, [])

    def test_slow_loop_without_catch_up_never_protects(self):
        # 每轮 30ms（超过步长但低于软阈值）不应被判为卡顿。
        loop = Loop(PROD_DT).run(lambda i: 0.030, 600)
        self.assertEqual(loop.protections, 0)
        self.assertEqual(loop.events, [])


class StallGuardSingleEventTests(unittest.TestCase):
    def test_soft_spike_is_tolerated(self):
        loop = Loop(PROD_DT)
        loop.run(lambda i: 0.002, 100)
        stalled, diag = loop.step(0.12)
        loop.run(lambda i: 0.002, 20)
        self.assertFalse(stalled)
        self.assertIsNone(diag['reason'])
        self.assertEqual(loop.protections, 0)

    def test_single_warn_band_is_audited_but_not_protected(self):
        loop = Loop(PROD_DT)
        loop.run(lambda i: 0.002, 100)
        stalled, diag = loop.step(0.30)
        self.assertFalse(stalled)
        self.assertEqual(diag['reason'], 'SINGLE_SLOW')
        self.assertFalse(diag['protected'])
        self.assertEqual(loop.protections, 0)

    def test_hard_stall_protects(self):
        loop = Loop(PROD_DT)
        loop.run(lambda i: 0.002, 100)
        stalled, diag = loop.step(0.60)
        self.assertTrue(stalled)
        self.assertEqual(diag['reason'], 'HARD_STALL')
        self.assertTrue(diag['protected'])

    def test_warn_is_rate_limited(self):
        loop = Loop(PROD_DT)
        loop.run(lambda i: 0.002, 100)
        loop.step(0.30)
        loop.run(lambda i: 0.002, 500)   # 约 1 秒
        loop.step(0.30)
        warns = [e for e in loop.events if e['reason'] == 'SINGLE_SLOW']
        self.assertEqual(len(warns), 1, warns)
        loop.run(lambda i: 0.002, 2600)  # 再等约 5.2 秒
        loop.step(0.30)
        warns = [e for e in loop.events if e['reason'] == 'SINGLE_SLOW']
        self.assertEqual(len(warns), 2, warns)


class StallGuardStreakTests(unittest.TestCase):
    def _spike_loop(self, gaps):
        loop = Loop(PROD_DT)
        loop.run(lambda i: 0.002, 100)
        for gap in gaps:
            loop.step(gap)
        return loop

    def test_two_consecutive_slow_loops_are_tolerated(self):
        loop = self._spike_loop([0.12, 0.12])
        self.assertEqual(loop.protections, 0)

    def test_three_consecutive_slow_loops_protect(self):
        loop = self._spike_loop([0.12, 0.12, 0.12])
        self.assertEqual(loop.protections, 1)
        self.assertEqual(loop.events[-1]['reason'], 'STREAK_LIMIT')
        self.assertGreaterEqual(loop.events[-1]['streak_ms'], 200.0)

    def test_healthy_loop_resets_streak(self):
        loop = self._spike_loop([0.12, 0.12, 0.002, 0.12, 0.12])
        self.assertEqual(loop.protections, 0)

    def test_below_soft_threshold_never_accumulates(self):
        # 每轮 70ms 都低于 80ms 软阈值，即使累计超过 200ms 也不应触发劣化判定。
        loop = self._spike_loop([0.07, 0.07, 0.07, 0.07, 0.07])
        self.assertEqual(loop.protections, 0)

    def test_streak_requires_both_count_and_total(self):
        guard = StallGuard(PROD_DT)
        # 三次 70ms 软阈值以下 + 一次 300ms：计入劣化的只有 300ms，count=1，不该保护。
        stalled, diag = guard.check(1000.3, 1000.0, 1000.02)
        self.assertFalse(stalled)
        self.assertEqual(guard.streak_count, 1)
        self.assertLess(guard.streak_time_ms, 400.0)


class StallGuardThresholdTests(unittest.TestCase):
    def test_thresholds_follow_simulation_step(self):
        guard = StallGuard(PROD_DT)
        self.assertAlmostEqual(guard.soft_ms, 80.0)
        self.assertAlmostEqual(guard.warn_ms, 250.0)
        self.assertAlmostEqual(guard.hard_ms, 500.0)

        slow = StallGuard(0.05)  # 20Hz 仿真：软阈值按 3 步长放宽到 150ms
        self.assertAlmostEqual(slow.soft_ms, 150.0)
        self.assertAlmostEqual(slow.warn_ms, 300.0)
        self.assertAlmostEqual(slow.hard_ms, 500.0)

    def test_tick_lag_alone_can_protect(self):
        guard = StallGuard(PROD_DT)
        stalled, diag = guard.check(now=1001.0, last_loop_start=1000.998, next_tick=1000.4)
        self.assertTrue(stalled)
        self.assertEqual(diag['reason'], 'HARD_STALL')
        self.assertGreaterEqual(diag['missed_cycles'], 29)

    def test_tick_lag_in_warn_band_is_only_audited(self):
        guard = StallGuard(PROD_DT)
        stalled, diag = guard.check(now=1001.0, last_loop_start=1000.998, next_tick=1000.7)
        self.assertFalse(stalled)
        self.assertEqual(diag['reason'], 'SINGLE_SLOW')

    def test_invalid_step_is_rejected(self):
        for bad in (0, -0.02, None, '0.02'):
            with self.assertRaises(ValueError):
                StallGuard(bad)

    def test_missed_cycles_reported(self):
        guard = StallGuard(PROD_DT)
        _, diag = guard.check(now=1000.2, last_loop_start=1000.198, next_tick=1000.02)
        self.assertEqual(diag['missed_cycles'], 9)
        self.assertIsNone(diag['reason'])


if __name__ == '__main__':
    unittest.main()
