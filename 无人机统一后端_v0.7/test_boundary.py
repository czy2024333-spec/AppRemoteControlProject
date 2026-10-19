"""边界迟滞回归：真实RotorPy与HTTP，不以模拟报文替代硬件验收。"""
import threading
import time
import unittest
import numpy as np
from app_server import SimulationService, make_server
from client import SourceClient, ClientError
from simulation_core import SimulationCore, BoundaryRecoveryPending, CoreLimits


class BoundaryTests(unittest.TestCase):
    def recover(self, position, reason):
        core = SimulationCore()
        # 测试注入初始位置，之后位置与速度都由真实动力学推进。
        core._state['x'] = np.array(position, dtype=float)
        core.protect(reason)
        start = core.get_state()['state']['x']
        with self.assertRaises(BoundaryRecoveryPending):
            core.resume(neutral_confirmed=True)
        self.assertEqual(core.get_state()['mode'], 'PROTECTED')
        self.assertEqual(core.get_state()['state']['x'], start)
        for _ in range(800):
            core.step()
            if core.boundary_recovery()['can_resume']:
                break
        else:
            self.fail('实际位置未进入恢复区或未停稳')
        before = core.get_state()['state']
        core.resume(neutral_confirmed=True)
        self.assertEqual(before, core.get_state()['state'])  # 解除不瞬移、不篡改速度。
        for _ in range(300):
            self.assertEqual(core.step()['mode'], 'HOLD')
        self.assertFalse(core.boundary_recovery()['required'])
        return core

    def test_lower_boundary_recovers_without_relatch(self):
        core = self.recover([0., 0., .299], 'ALTITUDE_STATE_LIMIT')
        self.assertGreaterEqual(core.get_state()['state']['x'][2], .35 - 1e-6)

    def test_upper_boundary_recovers_without_relatch(self):
        core = self.recover([0., 0., 10.001], 'ALTITUDE_STATE_LIMIT')
        self.assertLessEqual(core.get_state()['state']['x'][2], 9.95 + 1e-6)

    def test_horizontal_boundary_recovers_without_relatch(self):
        core = self.recover([10.001, 0., 1.], 'HORIZONTAL_STATE_LIMIT')
        self.assertLessEqual(core.get_state()['state']['x'][0], 9.95 + 1e-6)

    def test_hold_at_edge_and_actual_limit_are_distinct(self):
        core = SimulationCore()
        core._state['x'][2] = .3 - 1e-9
        core.stop()
        self.assertAlmostEqual(core.get_state()['target_position_m'][2], .35)
        for _ in range(300):
            self.assertEqual(core.step()['mode'], 'HOLD')
        # 数值容差不隐藏真实的高度越界。
        core = SimulationCore()
        core._state['x'][2] = .29999
        core.stop()
        self.assertEqual(core.step()['protection_reason'], 'ALTITUDE_STATE_LIMIT')

    def test_already_protected_inertia_still_reanchors_inward(self):
        core = SimulationCore()
        core._state['x'][2] = .3
        core._state['v'][2] = -.3
        core.protect('INPUT_TIMEOUT')
        for _ in range(50):
            core.step()
            if core.boundary_recovery()['required']:
                break
        self.assertTrue(core.boundary_recovery()['required'])
        self.assertAlmostEqual(core.get_state()['target_position_m'][2], .4)
        self.assertEqual(core.get_state()['protection_reason'], 'INPUT_TIMEOUT')

    def test_small_experiment_radius_retains_valid_recovery_zone(self):
        core = SimulationCore(limits=CoreLimits(horizontal_radius_m=.02))
        core._state['x'][0] = .021
        core.protect('HORIZONTAL_STATE_LIMIT')
        self.assertAlmostEqual(core.boundary_recovery()['horizontal_margin_m'], .005)
        self.assertAlmostEqual(core.get_state()['target_position_m'][0], .01)

    def test_recovery_requires_settled_actual_velocity(self):
        core = SimulationCore()
        core._state['x'][2] = .36
        core._state['v'][2] = -.2
        core.protect('ALTITUDE_STATE_LIMIT')
        with self.assertRaises(BoundaryRecoveryPending):
            core.resume(neutral_confirmed=True)
        self.assertEqual(core.get_state()['mode'], 'PROTECTED')

    def test_continuous_descent_protection_and_five_second_hold(self):
        core = SimulationCore()
        for _ in range(500):
            core.set_control(-.3, .5)
            if core.step()['mode'] == 'PROTECTED':
                break
        else:
            self.fail('持续下降未触发边界保护')
        self.assertTrue(core.boundary_recovery()['required'])
        for _ in range(800):
            core.step()
            if core.boundary_recovery()['can_resume']:
                break
        else:
            self.fail('持续下降后的边界保护未完成恢复')
        core.resume(neutral_confirmed=True)
        for _ in range(500):
            self.assertEqual(core.step()['mode'], 'HOLD')


class BoundaryHTTPTests(unittest.TestCase):
    def test_resume_pending_feedback_then_neutral_and_fresh_motion(self):
        core = SimulationCore()
        core._state['x'][2] = .299
        core.protect('ALTITUDE_STATE_LIMIT')
        service = SimulationService(core)
        server = make_server(service, '127.0.0.1', 0)
        service.start()
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        app = SourceClient('app', f'http://127.0.0.1:{server.server_port}')
        try:
            app.open_session()
            old_epoch = app.request('/api/state')['control_epoch']
            with self.assertRaises(ClientError) as caught:
                app.resume()
            self.assertEqual(caught.exception.status, 409)
            self.assertEqual(caught.exception.payload['error_code'], 'BOUNDARY_RECOVERY_PENDING')
            self.assertEqual(app.request('/api/state')['control_epoch'], old_epoch)
            until = time.monotonic() + 8.
            while time.monotonic() < until:
                app.heartbeat()
                state = app.request('/api/state')
                if state['boundary_recovery']['can_resume']:
                    break
                time.sleep(.05)
            else:
                self.fail('HTTP恢复条件未满足')
            state = app.resume()
            self.assertEqual(state['mode'], 'HOLD')
            self.assertIsNone(state['selected_source'])
            app.switch('app')
            app.neutral()
            app.begin_intent()
            z = app.request('/api/state')['position_m'][2]
            for _ in range(12):
                self.assertEqual(app.control(vz=.2)['mode'], 'ACTIVE')
                time.sleep(.05)
            self.assertGreater(app.request('/api/state')['position_m'][2], z)
            app.stop()
        finally:
            app.close()
            service.close()
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
