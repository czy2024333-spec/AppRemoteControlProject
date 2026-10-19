"""实际加载C++ DLL，并与现有RotorPy竖直输入样例对照。"""
import argparse
import csv
import ctypes as ct
import hashlib
import json
from pathlib import Path
import numpy as np
from simulation_core import SimulationCore

ROOT = Path(__file__).resolve().parent


class CConfig(ct.Structure):
    _fields_ = [(name, ct.c_double) for name in (
        "mass_kg", "gravity_m_s2", "max_thrust_n", "thrust_time_constant_s", "step_s",
        "initial_altitude_m", "min_altitude_m", "max_altitude_m", "max_vertical_speed_m_s",
        "reference_acceleration_m_s2", "max_input_ttl_s", "position_gain", "velocity_gain")]


class CState(ct.Structure):
    _fields_ = [("interface_version", ct.c_uint32), ("mode", ct.c_int32), ("reason", ct.c_int32),
                ("sequence", ct.c_uint64), ("simulation_time_s", ct.c_double), ("monotonic_time_s", ct.c_double),
                ("position_m", ct.c_double * 3), ("velocity_m_s", ct.c_double * 3),
                ("quaternion_xyzw", ct.c_double * 4), ("actual_thrust_n", ct.c_double),
                ("commanded_thrust_n", ct.c_double), ("target_altitude_m", ct.c_double),
                ("reference_vertical_speed_m_s", ct.c_double)]


def load_library(path):
    dll = ct.CDLL(str(path.resolve()))
    signatures = {
        "dc_default_config": [ct.POINTER(CConfig)],
        "dc_create": [ct.POINTER(CConfig), ct.POINTER(ct.c_void_p)],
        "dc_destroy": [ct.c_void_p], "dc_reset": [ct.c_void_p],
        "dc_set_control": [ct.c_void_p, ct.c_double, ct.c_double, ct.c_double],
        "dc_step": [ct.c_void_p, ct.c_double], "dc_stop": [ct.c_void_p],
        "dc_protect": [ct.c_void_p, ct.c_int32], "dc_resume": [ct.c_void_p, ct.c_int32],
        "dc_get_state": [ct.c_void_p, ct.POINTER(CState)],
    }
    for name, args in signatures.items():
        function = getattr(dll, name)
        function.argtypes = args
        function.restype = None if name == "dc_destroy" else ct.c_int32
    return dll


def require(status):
    if status != 0:
        raise RuntimeError(f"DLL返回错误码 {status}")


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dll", type=Path, default=ROOT / "cpp/build/drone_core_c.dll")
    parser.add_argument("--out", type=Path, default=ROOT / "results/cpp")
    args = parser.parse_args()
    dll = load_library(args.dll)
    config = CConfig()
    require(dll.dc_default_config(ct.byref(config)))
    handle = ct.c_void_p()
    require(dll.dc_create(ct.byref(config), ct.byref(handle)))
    records = []
    try:
        # ABI、参数拒绝、墙钟停顿、保护锁存、恢复和复位。
        check(dll.dc_step(None, 0) == 1, "空句柄未拒绝")
        check(dll.dc_resume(handle, 0) == 1, "未确认中立的恢复未拒绝")
        require(dll.dc_set_control(handle, .3, .1, 100))
        require(dll.dc_step(handle, 105))
        state = CState()
        require(dll.dc_get_state(handle, ct.byref(state)))
        check(state.interface_version == 1 and state.mode == 2 and state.reason == 2, "DLL快照/墙钟超时状态错误")
        check(dll.dc_set_control(handle, .3, .1, 105) == 2, "保护后运动输入未拒绝")
        require(dll.dc_resume(handle, 1))
        require(dll.dc_reset(handle))
        python_core = SimulationCore()
        for tick in range(801):
            require(dll.dc_get_state(handle, ct.byref(state)))
            py_state = python_core.get_state()
            records.append({"time_s": state.simulation_time_s,
                            "cpp_height_m": state.position_m[2], "rotorpy_height_m": py_state["state"]["x"][2],
                            "cpp_vz_m_s": state.velocity_m_s[2], "rotorpy_vz_m_s": py_state["state"]["v"][2],
                            "cpp_mode": state.mode, "rotorpy_mode": py_state["mode"]})
            if tick == 800:
                break
            if tick < 200 and tick % 10 == 0:
                require(dll.dc_set_control(handle, .3, .5, tick * .01))
                python_core.set_control(.3, .5)
            elif tick == 200:
                require(dll.dc_stop(handle))
                python_core.stop()
            elif 400 <= tick < 500 and tick % 10 == 0:
                require(dll.dc_set_control(handle, -.3, .5, tick * .01))
                python_core.set_control(-.3, .5)
            require(dll.dc_step(handle, (tick + 1) * .01))
            python_core.step()
        errors = np.array([row["cpp_height_m"] - row["rotorpy_height_m"] for row in records])
        final = records[-1]
        checks = {"dll_abi_and_errors": True, "finite": bool(np.isfinite(errors).all()),
                  "cpp_ascended": records[200]["cpp_height_m"] > 1.3,
                  "cpp_stop_settled": abs(records[400]["cpp_vz_m_s"]) < .01,
                  "cpp_descended": records[500]["cpp_height_m"] < records[400]["cpp_height_m"],
                  "both_protected": final["cpp_mode"] == 2 and final["rotorpy_mode"] == "PROTECTED",
                  "cpp_protected_hover": abs(final["cpp_vz_m_s"]) < .01}
        report = {"checks": checks, "checks_passed": all(checks.values()), "samples": len(records),
                  "height_difference_rmse_m": float(np.sqrt(np.mean(errors**2))),
                  "max_absolute_height_difference_m": float(np.max(np.abs(errors))),
                  "final": final, "dll_sha256": hashlib.sha256(args.dll.read_bytes()).hexdigest(),
                  "model_note": "C++为一维总推力一阶模型，RotorPy为六自由度电机转速/气动模型；差值用于观察，未设置模型等价验收阈值。",
                  "cpp_config": {name: getattr(config, name) for name, _ in CConfig._fields_}}
        args.out.mkdir(parents=True, exist_ok=True)
        with (args.out / "comparison.csv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(records[0]))
            writer.writeheader()
            writer.writerows(records)
        (args.out / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        if not report["checks_passed"]:
            raise SystemExit("DLL联调验证失败")
    finally:
        dll.dc_destroy(handle)


if __name__ == "__main__":
    main()
