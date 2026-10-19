"""打印模型结构和一次控制计算，配合断点学习，不推进完整仿真。"""
import inspect
import json
import numpy as np
from run_lab import ROOT, check_version, load_config, make_components, jsonable


def main():
    check_version()
    cfg = load_config(ROOT / "lab_config.json")
    vehicle, controller, trajectory, params, omega = make_components(cfg, "recovery")
    actual = vehicle.initial_state
    desired = trajectory.update(0.0)
    control = controller.update(0.0, actual, desired)
    labels = {"x": "世界系位置 m", "v": "世界系速度 m/s", "q": "姿态四元数 [x,y,z,w]",
              "w": "机体系角速度 rad/s", "wind": "世界系风速 m/s", "rotor_speeds": "实际电机转速 rad/s"}
    print("=== 1. 初始实际状态（有位置偏差）===")
    for key, values in actual.items():
        print(key, "shape=", np.asarray(values).shape, labels[key], "value=", values)
    print("\n=== 2. 期望轨迹 ===")
    print(json.dumps(jsonable(desired), indent=2, ensure_ascii=False))
    print("\n=== 3. 控制器输出，不是直接改变位置 ===")
    for key in ("cmd_thrust", "cmd_moment", "cmd_motor_speeds"):
        print(key, control[key])
    print("\n=== 4. 对称四旋翼平衡 ===")
    print("mass kg:", params["mass"], "weight N:", params["mass"] * 9.81)
    print("hover omega rad/s:", omega)
    print("kp_pos:", controller.kp_pos, "kd_pos:", controller.kd_pos)
    print("\n=== 5. 当前实际加载的源码位置 ===")
    for obj in (type(vehicle), type(controller), type(trajectory)):
        print(obj.__name__, inspect.getfile(obj))
    print("\n请在run_lab.py的make_components/run_case和上述update/step内设置断点。")


if __name__ == "__main__":
    main()
