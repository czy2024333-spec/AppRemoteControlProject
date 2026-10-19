"""直接运行新核心，验证行为，不读取历史结果。"""
import unittest
import numpy as np
from scipy.spatial.transform import Rotation
from simulation_core import SimulationCore, CoreLimits, to_ue_pose


class CoreTests(unittest.TestCase):
    def advance(self, core, count, speed=None):
        for i in range(count):
            if speed is not None and i % 10 == 0:
                core.set_control(speed, 0.5)
            core.step()
        return core.get_state()

    def test_hover_and_instance_isolation(self):
        first, second = SimulationCore(), SimulationCore()
        snapshot = first.get_state()
        snapshot["state"]["x"][2] = 99
        self.advance(first, 100)
        np.testing.assert_allclose(first.get_state()["state"]["x"], [0, 0, 1], atol=1e-6)
        self.assertEqual(second.time_s, 0)
        self.assertEqual(first.time_s, 1)

    def test_up_down_stop(self):
        for speed in (0.3, -0.3):
            core = SimulationCore()
            moving = self.advance(core, 200, speed)
            self.assertGreater((moving["state"]["x"][2] - 1) * np.sign(speed), 0.3)
            anchor = moving["state"]["x"][2]
            core.stop()
            stopped = self.advance(core, 400)
            self.assertLess(abs(stopped["state"]["x"][2] - anchor), 0.01)
            self.assertLess(abs(stopped["state"]["v"][2]), 0.01)
            self.assertEqual(stopped["mode"], "HOLD")

    def test_timeout_latches_and_neutral_resume(self):
        core = SimulationCore()
        core.set_control(0.3, 0.1)
        self.advance(core, 10)
        self.assertEqual(core.get_state()["protection_reason"], "INPUT_TIMEOUT")
        with self.assertRaises(RuntimeError):
            core.set_control(0.3, 0.1)
        core.stop()
        self.assertEqual(core.get_state()["mode"], "PROTECTED")
        with self.assertRaises(ValueError):
            core.resume()
        core.resume(neutral_confirmed=True)
        self.advance(core, 100)
        self.assertEqual(core.get_state()["mode"], "HOLD")
        core.set_control(-0.1, 0.2)
        self.assertEqual(core.get_state()["mode"], "ACTIVE")

    def test_limits_and_reset(self):
        core = SimulationCore(limits=CoreLimits(max_altitude_m=1.1))
        for _ in range(200):
            if core.get_state()["mode"] == "PROTECTED":
                break
            core.set_control(0.5, 0.5)
            core.step()
        self.assertEqual(core.get_state()["mode"], "PROTECTED")
        self.assertIn(core.get_state()["protection_reason"], {"ALTITUDE_REFERENCE_LIMIT", "ALTITUDE_STATE_LIMIT"})
        core.reset()
        self.assertEqual(core.time_s, 0)
        self.assertEqual(core.get_state()["mode"], "HOLD")
        for speed, ttl in ((float("nan"), .1), (.6, .1), (.1, 0), (.1, 1), (True, .1)):
            with self.assertRaises(ValueError):
                core.set_control(speed, ttl)

    def test_ue_reflection_for_all_axes(self):
        reflection = np.diag([1., -1., 1.])
        for axis in "xyz":
            rotation = Rotation.from_euler(axis, 30, degrees=True)
            pose = to_ue_pose({"state": {"x": [1, 2, 3], "q": rotation.as_quat()}})
            np.testing.assert_allclose(pose["position_cm"], [100, -200, 300])
            np.testing.assert_allclose(Rotation.from_quat(pose["quaternion_xyzw"]).as_matrix(),
                                       reflection @ rotation.as_matrix() @ reflection, atol=1e-12)


if __name__ == "__main__":
    unittest.main(verbosity=2)
