"""首日基线单元测试：只验证局部数值行为，不代表整机验收。"""
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import numpy as np
from run_lab import ROOT, check_version, load_config, make_components, summarize


class Day1Tests(unittest.TestCase):
    def setUp(self):
        self.cfg = load_config(ROOT / "lab_config.json")

    def test_pinned_version(self):
        check_version()

    def test_hover_force_balance(self):
        vehicle, controller, trajectory, params, omega = make_components(self.cfg, "hover")
        self.assertAlmostEqual(params["num_rotors"] * params["k_eta"] * omega**2, params["mass"] * 9.81, places=10)
        state = copy.deepcopy(vehicle.initial_state)
        control = controller.update(0.0, state, trajectory.update(0.0))
        np.testing.assert_allclose(control["cmd_motor_speeds"], omega, rtol=1e-6)
        advanced = vehicle.step(state, control, 1.0 / self.cfg["sim_rate_hz"])
        np.testing.assert_allclose(advanced["x"], vehicle.initial_state["x"], atol=1e-5)
        self.assertAlmostEqual(np.linalg.norm(advanced["q"]), 1.0, places=8)

    def test_initial_offset_only_in_recovery(self):
        vh, _, _, _, _ = make_components(self.cfg, "hover")
        vr, _, _, _, _ = make_components(self.cfg, "recovery")
        np.testing.assert_allclose(vr.initial_state["x"] - vh.initial_state["x"], self.cfg["recovery_offset_m"])

    def test_mass_change_does_not_mutate_official_parameters(self):
        from rotorpy.vehicles.crazyflie_params import quad_params
        original = quad_params["mass"]
        cfg = dict(self.cfg, mass_scale=1.1)
        _, controller, _, params, _ = make_components(cfg, "hover")
        self.assertAlmostEqual(quad_params["mass"], original)
        self.assertAlmostEqual(controller.mass, params["mass"])

    def test_invalid_config_rejected(self):
        for key, bad_value in (("sim_rate_hz", 0), ("mass_scale", -1),
                               ("motor_noise_rad_s", -1), ("hover_position_m", [0, 0]),
                               ("seed", True), ("duration_s", "8"), ("mass_scale", True)):
            cfg = dict(self.cfg, **{key: bad_value})
            with patch.object(Path, "read_text", return_value=json.dumps(cfg)):
                with self.assertRaises(ValueError):
                    load_config("invalid.json")

    def test_collision_exit_cannot_pass_as_timeout(self):
        from rotorpy.simulate import ExitStatus
        t = np.array([0., 0.01, 0.02])
        synthetic = {"time": t, "state": {"x": np.zeros((3, 3)), "v": np.zeros((3, 3)),
                     "q": np.tile([0., 0., 0., 1.], (3, 1)), "w": np.zeros((3, 3)),
                     "rotor_speeds": np.ones((3, 4))}, "flat": {"x": np.zeros((3, 3))},
                     "control": {"cmd_motor_speeds": np.ones((3, 4))}, "exit": ExitStatus.COLLISION}
        report = summarize(synthetic, 0.02)
        self.assertFalse(report["checks_passed"])
        self.assertFalse(report["checks"]["normal_time_exit"])

    @unittest.skipUnless(__import__("os").environ.get("ROTORPY_CHECK_SAVED_RESULTS") == "1",
                         "历史输出验收可选；设置ROTORPY_CHECK_SAVED_RESULTS=1开启")
    def test_finished_run_summaries(self):
        for name in ("hover", "recovery", "official"):
            path = ROOT / "results" / name / "summary.json"
            self.assertTrue(path.exists(), f"先运行实验：{path}")
            report = json.loads(path.read_text(encoding="utf-8"))
            self.assertTrue(report["checks_passed"], (name, report["checks"]))
        for name in ("hover", "recovery"):
            with np.load(ROOT / "results" / name / "states.npz") as data:
                self.assertEqual(data["x"].shape[1], 3)
                self.assertEqual(data["q"].shape[1], 4)
                self.assertTrue(np.isfinite(data["rotor_speeds"]).all())


if __name__ == "__main__":
    unittest.main(verbosity=2)
