"""负责人A的第一天：官方SE3控制器 + Crazyflie模型 + 悬停/偏差恢复。

只做离线数值实验，不连接MQTT、UE或实机。100Hz是仿真逻辑频率，
不是实时性能保证。运行: python run_lab.py --case all
"""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import importlib.metadata as metadata
import inspect
import json
import math
from pathlib import Path
import platform
import subprocess
import sys
import time

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from scipy.spatial.transform import Rotation
from rotorpy.environments import Environment
from rotorpy.vehicles.multirotor import Multirotor
from rotorpy.vehicles.crazyflie_params import quad_params
from rotorpy.controllers.quadrotor_control import SE3Control
from rotorpy.trajectories.hover_traj import HoverTraj
from rotorpy.wind.default_winds import NoWind
from rotorpy.utils.postprocessing import unpack_sim_data

ROOT = Path(__file__).resolve().parent
EXPECTED_VERSION = "3.0.0"


def check_version():
    actual = metadata.version("rotorpy")
    if actual != EXPECTED_VERSION:
        raise RuntimeError(f"需要RotorPy {EXPECTED_VERSION}，实际{actual}；请按安装说明使用隔离环境")


def jsonable(value):
    if isinstance(value, dict):
        return {str(k): jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [jsonable(v) for v in value]
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, np.generic):
        return value.item()
    return value


def load_config(path):
    cfg = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(cfg, dict):
        raise ValueError("配置应为JSON对象")
    required = {"seed", "duration_s", "mass_scale", "position_gain_scale", "tail_window_s",
                "tail_rmse_limit_m", "sim_rate_hz", "hover_position_m", "recovery_offset_m", "motor_noise_rad_s"}
    if required - cfg.keys():
        raise ValueError(f"缺少配置字段: {sorted(required - cfg.keys())}")
    for key in ("duration_s", "mass_scale", "position_gain_scale", "tail_window_s", "tail_rmse_limit_m", "motor_noise_rad_s"):
        if type(cfg[key]) not in (int, float):
            raise ValueError(f"{key}应为数值，不接受字符串或布尔值")
    if type(cfg["seed"]) is not int or not 0 <= cfg["seed"] < 2**32:
        raise ValueError("seed应为0至2**32-1的整数")
    for key in ("duration_s", "mass_scale", "position_gain_scale", "tail_window_s", "tail_rmse_limit_m"):
        if not math.isfinite(float(cfg[key])) or float(cfg[key]) <= 0:
            raise ValueError(f"{key}需要为有限正数")
    rate = cfg["sim_rate_hz"]
    if type(rate) is not int or not 50 <= rate <= 500:
        raise ValueError("sim_rate_hz需要为50至500的整数")
    if cfg["duration_s"] < cfg["tail_window_s"]:
        raise ValueError("仿真时长不能短于尾段窗口")
    for key in ("hover_position_m", "recovery_offset_m"):
        if not isinstance(cfg[key], list) or any(type(v) not in (int, float) for v in cfg[key]):
            raise ValueError(f"{key}应为数值列表")
        array = np.asarray(cfg[key], dtype=float)
        if array.shape != (3,) or not np.isfinite(array).all():
            raise ValueError(f"{key}应为三个有限数值")
    if float(cfg["motor_noise_rad_s"]) < 0 or not math.isfinite(float(cfg["motor_noise_rad_s"])):
        raise ValueError("motor_noise_rad_s不能为负或非有限数值")
    if cfg["hover_position_m"][2] <= 0 or cfg["hover_position_m"][2] + cfg["recovery_offset_m"][2] <= 0:
        raise ValueError("首日实验初始高度和目标高度应高于地面")
    return cfg


def make_components(cfg, case):
    if case not in {"hover", "recovery"}:
        raise ValueError(f"未知工况: {case}")
    # 机体参数是物理模型，不是控制器增益。深复制防止修改官方全局对象。
    params = copy.deepcopy(quad_params)
    params["mass"] *= cfg["mass_scale"]
    params["motor_noise_std"] = cfg["motor_noise_rad_s"]
    # 对称四旋翼近似：4*k_eta*omega_hover^2 = m*g；omega单位rad/s。
    omega_hover = math.sqrt(params["mass"] * 9.81 / (params["num_rotors"] * params["k_eta"]))
    if omega_hover >= params["rotor_speed_max"]:
        raise ValueError("质量过大，悬停转速超过电机上限；请降低mass_scale")
    vehicle = Multirotor(params)
    # 控制器要用同一套机体参数。mass_scale同时更新模型与控制器，
    # 因而不是质量失配实验，也不是自动生成新机型。
    controller = SE3Control(params)
    controller.kp_pos *= cfg["position_gain_scale"]
    target = np.asarray(cfg["hover_position_m"], dtype=float)
    offset = np.asarray(cfg["recovery_offset_m"], dtype=float) if case == "recovery" else np.zeros(3)
    # state是真实仿真状态；flat_output是期望轨迹，两者不要混淆。
    vehicle.initial_state = {
        "x": target + offset,                  # 世界系位置，m
        "v": np.zeros(3),                      # 世界系速度，m/s
        "q": np.array([0., 0., 0., 1.]),        # 姿态四元数[x,y,z,w]，不是欧拉角
        "w": np.zeros(3),                      # 机体系角速度，rad/s
        "wind": np.zeros(3),                   # 世界系风速，m/s，由风场更新
        "rotor_speeds": np.full(params["num_rotors"], omega_hover),
    }
    # 故意从离地位置启动，今天测试悬停而非起飞/降落状态机。
    trajectory = HoverTraj(x0=target)
    return vehicle, controller, trajectory, params, omega_hover


def summarize(result, expected_duration, hover_target=None, tail_window_s=1.0, tail_limit=0.05):
    t = np.asarray(result["time"])
    if t.ndim != 1 or not len(t):
        raise ValueError("仿真时间应为非空一维数组")
    s = result["state"]
    position, velocity, q, rates, rotors = [np.asarray(s[key]) for key in ("x", "v", "q", "w", "rotor_speeds")]
    expected_shape = all(arr.shape == (len(t), width) for arr, width in
                         ((position, 3), (velocity, 3), (q, 4), (rates, 3)))
    expected_shape = expected_shape and rotors.ndim == 2 and rotors.shape == (len(t), 4)
    if not expected_shape or np.asarray(result["flat"]["x"]).shape != (len(t), 3):
        raise ValueError("状态或目标数组维度不符合四旋翼接口")
    finite = all(np.isfinite(arr).all() for arr in (t, position, velocity, q, rates, rotors))
    controls_finite = all(np.isfinite(np.asarray(v)).all() for v in result["control"].values())
    q_error = float(np.max(np.abs(np.linalg.norm(q, axis=1) - 1)))
    errors = np.linalg.norm(position - np.asarray(result["flat"]["x"]), axis=1)
    report = {
        "samples": len(t), "simulation_end_s": float(t[-1]),
        "exit_status": str(result.get("exit", "unknown")),
        "position_rmse_m": float(np.sqrt(np.mean(errors ** 2))),
        "max_position_error_m": float(errors.max()),
        "max_quaternion_norm_error": q_error,
        "max_speed_m_s": float(np.linalg.norm(velocity, axis=1).max()),
        "final_position_m": position[-1].tolist(),
        "motor_speed_min_rad_s": float(rotors.min()),
        "motor_speed_max_rad_s": float(rotors.max()),
        "checks": {"normal_time_exit": getattr(result.get("exit"), "name", None) == "TIMEOUT",
                   "state_shapes": expected_shape, "state_finite": finite,
                   "control_finite": bool(controls_finite), "time_monotonic": bool(np.all(np.diff(t) > 0)),
                   "duration_reached": bool(abs(float(t[-1]) - expected_duration) <= 0.02),
                   "quaternion_normalized": q_error < 1e-6},
    }
    if hover_target is not None:
        tail = t >= t[-1] - tail_window_s
        tail_errors = np.linalg.norm(position[tail] - hover_target, axis=1)
        tail_rmse = float(np.sqrt(np.mean(tail_errors ** 2)))
        report["tail_position_rmse_m"] = tail_rmse
        report["tail_window_s"] = tail_window_s
        report["tail_rmse_limit_m"] = tail_limit
        report["checks"]["hover_tail_rmse"] = tail_rmse <= tail_limit
    report["checks_passed"] = all(report["checks"].values())
    return report


def plot_result(result, path, title):
    t, s, flat, control = result["time"], result["state"], result["flat"], result["control"]
    euler = Rotation.from_quat(s["q"]).as_euler("xyz", degrees=True)
    plt.style.use("dark_background")
    fig, axes = plt.subplots(2, 2, figsize=(12, 7), constrained_layout=True)
    fig.set_facecolor("#111820")
    for ax in axes.flat:
        ax.set_facecolor("#1a2531")
    colors = ["#47adce", "#e7a14b", "#83b95c"]
    for i, (axis, color) in enumerate(zip("xyz", colors)):
        axes[0, 0].plot(t, s["x"][:, i], color=color, label=axis)
        axes[0, 0].plot(t, flat["x"][:, i], "--", color=color, alpha=.65, label=f"{axis} target")
        axes[0, 1].plot(t, euler[:, i], color=color, label=["roll", "pitch", "yaw"][i])
    axes[0, 0].set_ylabel("World position (m)")
    axes[0, 1].set_ylabel("Attitude (deg)")
    error = np.linalg.norm(s["x"] - flat["x"], axis=1)
    axes[1, 0].plot(t, error, color="#47adce", label="position error norm")
    axes[1, 0].set_ylabel("Position error (m)")
    for i in range(s["rotor_speeds"].shape[1]):
        axes[1, 1].plot(t, s["rotor_speeds"][:, i], label=f"motor {i+1}")
    axes[1, 1].set_ylabel("Actual motor speed (rad/s)")
    for ax in axes.flat:
        ax.set_xlabel("Simulation time (s)")
        ax.grid(alpha=.2)
        ax.legend(fontsize=8)
    fig.suptitle(title)
    fig.savefig(path, dpi=150)
    plt.close(fig)


def save_inventory(output):
    freeze = subprocess.check_output([sys.executable, "-m", "pip", "freeze"], text=True)
    (output / "requirements-observed.txt").write_text(freeze, encoding="utf-8")
    packages = {d.metadata["Name"]: d.version for d in metadata.distributions()}
    inventory = {
        "created_at": datetime.now(timezone.utc).isoformat(),
        "python": platform.python_version(), "executable": sys.executable,
        "platform": platform.platform(), "rotorpy": metadata.version("rotorpy"),
        "packages": packages, "logical_rate_note": "离线仿真，不是实时性能验收",
        "modules": {},
    }
    for obj in (Environment, Multirotor, SE3Control, HoverTraj):
        path = Path(inspect.getfile(obj))
        inventory["modules"][obj.__name__] = {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    manifest = json.loads((ROOT / "official_sources.json").read_text(encoding="utf-8"))
    installed_root = Path(inspect.getfile(Environment)).parent
    inventory["official_commit"] = manifest["commit"]
    inventory["source_comparisons"] = []
    for record in manifest["files"]:
        if record["source"].startswith("rotorpy/") and record["source"].endswith(".py"):
            relative = Path(record["source"]).relative_to("rotorpy")
            installed = installed_root / relative
            actual_hash = hashlib.sha256(installed.read_bytes()).hexdigest()
            inventory["source_comparisons"].append({"source": record["source"], "wheel_sha256": actual_hash,
                                                   "github_sha256": record["sha256"], "identical": actual_hash == record["sha256"]})
    (output / "versions.json").write_text(json.dumps(inventory, indent=2, ensure_ascii=False), encoding="utf-8")


def run_case(cfg, case, output):
    np.random.seed(cfg["seed"])
    vehicle, controller, trajectory, params, omega = make_components(cfg, case)
    sim = Environment(vehicle=vehicle, controller=controller, trajectory=trajectory,
                      wind_profile=NoWind(), sim_rate=cfg["sim_rate_hz"], safety_margin=0.1)
    start = time.perf_counter()
    # Environment在循环中调用trajectory.update -> controller.update -> vehicle.step。
    # 这里不直接拼装私有循环，先建立官方Environment运行基线。
    result = sim.run(t_final=cfg["duration_s"], use_mocap=False, terminate=False,
                     plot=False, plot_mocap=False, plot_estimator=False, plot_imu=False,
                     animate_bool=False, animate_wind=False, verbose=False)
    folder = output / case
    folder.mkdir(parents=True, exist_ok=True)
    unpack_sim_data(result).to_csv(folder / "flight.csv", index=False)
    np.savez_compressed(folder / "states.npz", time=result["time"], **result["state"])
    plot_result(result, folder / "curves.png", f"Day 1 - {case}")
    report = summarize(result, cfg["duration_s"], np.asarray(cfg["hover_position_m"]), cfg["tail_window_s"], cfg["tail_rmse_limit_m"])
    report.update({"case": case, "seed": cfg["seed"], "wall_time_s": time.perf_counter() - start,
                   "hover_speed_estimate_rad_s": omega, "physical_params": params,
                   "controller_gains": {"kp_pos": controller.kp_pos, "kd_pos": controller.kd_pos,
                                        "kp_att": controller.kp_att, "kd_att": controller.kd_att},
                   "config": cfg})
    (folder / "summary.json").write_text(json.dumps(jsonable(report), indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"{case}: PASS={report['checks_passed']}, tail RMSE={report['tail_position_rmse_m']:.8f} m, samples={report['samples']}")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", choices=["hover", "recovery", "all"], default="all")
    parser.add_argument("--config", type=Path, default=ROOT / "lab_config.json")
    parser.add_argument("--out", type=Path, default=ROOT / "results")
    args = parser.parse_args()
    plt.switch_backend("Agg")
    check_version()
    cfg = load_config(args.config)
    args.out.mkdir(parents=True, exist_ok=True)
    save_inventory(args.out)
    cases = ["hover", "recovery"] if args.case == "all" else [args.case]
    reports = [run_case(cfg, case, args.out) for case in cases]
    if not all(r["checks_passed"] for r in reports):
        raise SystemExit("有实验未通过；查看各summary.json，不要当作交付基线")


if __name__ == "__main__":
    main()
