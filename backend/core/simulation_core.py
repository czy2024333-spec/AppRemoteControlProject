"""A→C 最小离线仿真接口。调用方负责调度及B层授权；单线程调用。"""
import copy
from dataclasses import dataclass
import math

import numpy as np
from scipy.spatial.transform import Rotation

from run_lab import ROOT, check_version, load_config, make_components, jsonable


@dataclass(frozen=True)
class CoreLimits:
    min_altitude_m: float = 0.3
    max_altitude_m: float = 10.0
    max_vertical_speed_m_s: float = 0.5
    max_reference_acceleration_m_s2: float = 0.5
    max_input_ttl_s: float = 0.5
    max_horizontal_speed_m_s: float = 0.5
    max_yaw_rate_rad_s: float = 0.5
    horizontal_radius_m: float = 10.0
    # 【边界迟滞】触发线保持不变，恢复必须回到内侧安全区并基本停稳。
    boundary_recovery_margin_m: float = 0.05
    boundary_recovery_max_speed_m_s: float = 0.05
    boundary_numerical_tolerance_m: float = 1e-6

    def __post_init__(self):
        for name, value in vars(self).items():
            if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
                raise ValueError(f"{name}应为有限正数")
        if self.min_altitude_m >= self.max_altitude_m:
            raise ValueError("高度下限必须小于上限")


class SimulationCore:
    """固定步长、每实例独立状态；世界系三轴速度与偏航输入，底层为六自由度动力学。"""

    def __init__(self, config=None, limits=None):
        check_version()
        self.config = copy.deepcopy(load_config(config or ROOT / "lab_config.json"))
        if self.config["motor_noise_rad_s"] != 0:
            raise ValueError("最小核心只支持无噪声；避免RotorPy全局随机源造成实例耦合")
        self.limits = limits or CoreLimits()
        z = self.config["hover_position_m"][2]
        if not self.limits.min_altitude_m <= z <= self.limits.max_altitude_m:
            raise ValueError("初始高度超出核心范围")
        self.dt = 1.0 / self.config["sim_rate_hz"]
        self.reset()

    def reset(self):
        # 【初始化】每实例创建独立模型和控制器；起点已在空中平衡，未模拟地面起飞。
        self.vehicle, self.controller, _, self.params, _ = make_components(self.config, "hover")
        self._state = copy.deepcopy(self.vehicle.initial_state)
        self._target = self._state["x"].copy()
        self._origin_xy = self._target[:2].copy()
        self._reference_v = np.zeros(3)
        self._command_v = np.zeros(3)
        self._yaw = 0.0
        self._yaw_rate = 0.0
        self._expires_at = None
        self._ticks = 0
        self._mode = "HOLD"
        self._reason = None
        self._boundary_recovery_required = False
        self._compensation = np.zeros(3)
        self._compensation_enabled = False
        return self.get_state()

    @property
    def time_s(self):
        return self._ticks * self.dt

    def set_control(self, vertical_speed_m_s, ttl_s):
        """兼容第一阶段升降入口，同时将水平与偏航输入归零。"""
        return self.set_motion(0., 0., vertical_speed_m_s, 0., ttl_s)

    @staticmethod
    def _vector(value, name):
        if not isinstance(value, (list, tuple, np.ndarray)):
            raise ValueError(f"{name}必须是三元素向量")
        array = np.asarray(value, dtype=object)
        if array.shape != (3,):
            raise ValueError(f"{name}必须是三元素向量")
        if any(isinstance(v, (bool, np.bool_)) or not isinstance(v, (int, float, np.integer, np.floating)) or not math.isfinite(v) for v in array):
            raise ValueError(f"{name}必须为有限数值，不能包含bool")
        return np.array(array, dtype=float)

    def set_wind(self, world_wind_m_s):
        """环境管理入口：世界系空气速度，不是直接修改飞机速度；合风速<=5m/s。"""
        wind = self._vector(world_wind_m_s, "wind")
        if np.linalg.norm(wind) > 5.:
            raise ValueError("风速向量范数不得超过5m/s")
        self._state["wind"] = wind

    def apply_impulse(self, linear_impulse_n_s, body_angular_impulse_nm_s=(0., 0., 0.)):
        """物理冲量：世界系J/m改变速度，机体系J惯量逆乘角冲量改变角速度。"""
        linear = self._vector(linear_impulse_n_s, "linear_impulse")
        angular = self._vector(body_angular_impulse_nm_s, "angular_impulse")
        dv = linear / self.params["mass"]
        dw = np.linalg.solve(self.vehicle.inertia, angular)
        if np.linalg.norm(dv) > 1. or np.linalg.norm(dw) > 1.:
            raise ValueError("每次冲量引起的速度/角速度变化范数不得超过1m/s或1rad/s")
        candidate_v = self._state["v"] + dv
        candidate_w = self._state["w"] + dw
        if not np.isfinite(candidate_v).all() or not np.isfinite(candidate_w).all():
            raise ValueError("冲量会导致非有限状态")
        # 不修改位置、姿态、目标、TTL或保护状态；之后由控制器真实制动。
        self._state["v"], self._state["w"] = candidate_v, candidate_w

    def enable_disturbance_compensation(self, enabled=True):
        """可选位置积分补偿，初始关闭；切换时清积分，模式/输入不变。"""
        if type(enabled) is not bool:
            raise ValueError("enabled必须是bool")
        self._compensation_enabled = enabled
        self._compensation = np.zeros(3)

    def set_motion(self, vx_m_s, vy_m_s, vz_m_s, yaw_rate_rad_s, ttl_s):
        """世界系x前/y左/z上；偏航绕+z，TTL是B扣除报文年龄后的剩余秒数。"""
        # 【输入入口】先处理旧输入是否过期，再检查新输入；过期保护不能靠新指令自动解除。
        self._expire_input()
        for name, value in (("vx", vx_m_s), ("vy", vy_m_s), ("vz", vz_m_s),
                            ("yaw_rate", yaw_rate_rad_s), ("ttl_s", ttl_s)):
            if type(value) not in (int, float) or not math.isfinite(value):
                raise ValueError(f"{name}应为有限数值")
        if math.hypot(vx_m_s, vy_m_s) > self.limits.max_horizontal_speed_m_s:
            raise ValueError("水平合速度超过范围")
        if abs(yaw_rate_rad_s) > self.limits.max_yaw_rate_rad_s:
            raise ValueError("偏航角速度超过范围")
        if abs(vz_m_s) > self.limits.max_vertical_speed_m_s:
            raise ValueError("竖直输入超过速度范围")
        if not 0 < ttl_s <= self.limits.max_input_ttl_s:
            raise ValueError("剩余TTL超出范围")
        if self._mode == "PROTECTED":
            raise RuntimeError("保护已锁存；B确认中立输入后调用resume，再提交新指令")
        # 正值上升、负值下降，单位m/s；这里保存意图，真正运动由step中的控制器计算。
        self._command_v = np.array([vx_m_s, vy_m_s, vz_m_s], dtype=float)
        self._yaw_rate = float(yaw_rate_rad_s)
        # Python原型采用仿真时间TTL；仿真暂停时不计时。C++接入版采用调用方单调墙钟。
        self._expires_at = self.time_s + ttl_s
        self._mode = "ACTIVE"
        self._reason = None

    def _boundary_margins(self):
        # 小范围自定义实验自动缩小余量，避免内侧恢复区被挤没。
        vertical = min(self.limits.boundary_recovery_margin_m,
                       (self.limits.max_altitude_m - self.limits.min_altitude_m) / 8.)
        horizontal = min(self.limits.boundary_recovery_margin_m,
                         self.limits.horizontal_radius_m / 4.)
        return vertical, horizontal

    def boundary_recovery(self):
        """可供APP显示的恢复条件；仿真状态与目标分别检查，不用目标冒充实际高度。"""
        vertical, horizontal = self._boundary_margins()
        tolerance = min(self.limits.boundary_numerical_tolerance_m,
                        vertical / 100., horizontal / 100.)
        low = self.limits.min_altitude_m + vertical
        high = self.limits.max_altitude_m - vertical
        radius = self.limits.horizontal_radius_m - horizontal
        z = float(self._state['x'][2])
        distance = float(np.linalg.norm(self._state['x'][:2] - self._origin_xy))
        speed = float(np.linalg.norm(self._state['v']))
        inside = low - tolerance <= z <= high + tolerance and distance <= radius + tolerance
        settled = speed <= self.limits.boundary_recovery_max_speed_m_s
        return dict(required=self._boundary_recovery_required,
                    can_resume=not self._boundary_recovery_required or (inside and settled),
                    actual_altitude_m=z, actual_horizontal_radius_m=distance, actual_speed_m_s=speed,
                    min_resume_altitude_m=low, max_resume_altitude_m=high,
                    max_resume_horizontal_radius_m=radius,
                    max_resume_speed_m_s=self.limits.boundary_recovery_max_speed_m_s,
                    vertical_margin_m=vertical, horizontal_margin_m=horizontal)

    def _hold(self, mode, reason):
        # 【制动目标】捕获实际位置作为保持点并清除旧输入，飞机随后通过动力学逐渐停稳。
        self._target = self._state["x"].copy()
        vertical, horizontal = self._boundary_margins()
        # 边界保护目标比解除阈值再深入一个余量，保证实际位置能跨过恢复线。
        factor = 2. if self._boundary_recovery_required else 1.
        self._target[2] = np.clip(self._target[2], self.limits.min_altitude_m + factor * vertical,
                                  self.limits.max_altitude_m - factor * vertical)
        offset = self._target[:2] - self._origin_xy
        distance = np.linalg.norm(offset)
        safe_radius = self.limits.horizontal_radius_m - factor * horizontal
        if distance > safe_radius:
            self._target[:2] = self._origin_xy + offset * safe_radius / distance
        self._reference_v = np.zeros(3)
        self._command_v = np.zeros(3)
        # 【偏航保持】停止捕获实际偏航，不继续旧转动、不跳回0。
        matrix = Rotation.from_quat(self._state["q"]).as_matrix()
        self._yaw = math.atan2(matrix[1, 0], matrix[0, 0])
        self._yaw_rate = 0.0
        self._expires_at = None
        self._mode, self._reason = mode, reason

    def stop(self):
        """停止=闭环制动并保持当前位置，不关闭电机、不瞬移、不解除保护。"""
        if self._mode != "PROTECTED":
            self._hold("HOLD", "STOP")
        return self.get_state()

    def protect(self, reason="SERVICE_PROTECTION"):
        if not isinstance(reason, str) or not reason:
            raise ValueError("保护原因应为非空字符串")
        boundary = reason in {'ALTITUDE_REFERENCE_LIMIT', 'ALTITUDE_STATE_LIMIT',
                              'HORIZONTAL_REFERENCE_LIMIT', 'HORIZONTAL_STATE_LIMIT'}
        new_boundary = boundary and not self._boundary_recovery_required
        if boundary:
            self._boundary_recovery_required = True
        if self._mode != "PROTECTED":
            self._hold("PROTECTED", reason)
        elif new_boundary:
            # 已因断网/超时保护后仍可能因惯性越界，首次越界时也要移入安全目标。
            self._hold("PROTECTED", self._reason)
        return self.get_state()

    def resume(self, *, neutral_confirmed=False):
        """只能由B确认控制权及新来源中立后调用；本接口不承担鉴权。"""
        if neutral_confirmed is not True:
            raise ValueError("需要B确认中立输入")
        if self._boundary_recovery_required and not self.boundary_recovery()['can_resume']:
            recovery = self.boundary_recovery()
            raise BoundaryRecoveryPending(
                '边界恢复中：实际高度需处于'
                f"[{recovery['min_resume_altitude_m']:.3f}, {recovery['max_resume_altitude_m']:.3f}]米，"
                f"水平半径不超过{recovery['max_resume_horizontal_radius_m']:.3f}米，"
                f"速度不超过{recovery['max_resume_speed_m_s']:.3f}米/秒；请等待后重新确认归中")
        self._boundary_recovery_required = False
        # 【恢复】只解除锁存并保持悬停；恢复运动必须另外提交新的合法输入。
        self._hold("HOLD", None)
        return self.get_state()

    def _expire_input(self):
        # 【超时断点】到期时锁存保护；1e-12用于消除浮点数在到期边界上的微小误差。
        if self._expires_at is not None and self.time_s >= self._expires_at - 1e-12:
            self.protect("INPUT_TIMEOUT")

    def step(self):
        # 【单步入口】每次只推进dt（默认0.01s），执行控制前先排除失效输入。
        self._expire_input()
        # 【参考限速】限制参考速度的变化率，避免目标突然跳变；并非实际机体加速度硬约束。
        delta = self.limits.max_reference_acceleration_m_s2 * self.dt
        # 【三轴参考】按向量范数限加速度，斜向不会得到额外加速度。
        difference = self._command_v - self._reference_v
        norm = np.linalg.norm(difference)
        self._reference_v += difference * min(1., delta / norm) if norm > 0 else difference
        candidate_target = self._target + self._reference_v * self.dt
        if not self.limits.min_altitude_m <= candidate_target[2] <= self.limits.max_altitude_m:
            self.protect("ALTITUDE_REFERENCE_LIMIT")
        elif np.linalg.norm(candidate_target[:2] - self._origin_xy) > self.limits.horizontal_radius_m:
            self.protect("HORIZONTAL_REFERENCE_LIMIT")
        else:
            self._target = candidate_target
        self._yaw = math.remainder(self._yaw + self._yaw_rate * self.dt, 2 * math.pi)
        # 【期望与真值】flat是目标轨迹，_state是实际状态；二者交给SE3控制器求电机指令。
        flat = {"x": self._target.copy(), "x_dot": self._reference_v.copy(),
                "x_ddot": np.zeros(3), "x_dddot": np.zeros(3), "x_ddddot": np.zeros(3),
                "yaw": self._yaw, "yaw_dot": self._yaw_rate, "yaw_ddot": 0.}
        # 【抗持续风】积分位置误差形成额外期望加速度；分量限幅1m/s²。
        proposed = self._compensation.copy()
        if self._compensation_enabled:
            proposed = np.clip(proposed + 2. * (self._target - self._state["x"]) * self.dt, -1., 1.)
        flat["x_ddot"] = proposed
        try:
            control = self.controller.update(self.time_s, self._state, flat)
            speeds = control["cmd_motor_speeds"]
            if np.any(speeds < self.params["rotor_speed_min"]) or np.any(speeds > self.params["rotor_speed_max"]):
                # 【防积分饱和】电机已饱和时冻结本步积分，重新计算控制。
                proposed = self._compensation.copy()
                flat["x_ddot"] = proposed
                control = self.controller.update(self.time_s, self._state, flat)
        except Exception:
            self.protect("NUMERICAL_FAULT")
            raise
        if not all(np.isfinite(v).all() for v in control.values()):
            self.protect("NONFINITE_CONTROL")
            raise RuntimeError("控制器输出非有限数值，停止推进")
        # vehicle.step可能修改传入state，使用副本以便失败时保留上一有效状态。
        try:
            candidate = self.vehicle.step(copy.deepcopy(self._state), control, self.dt)
        except Exception:
            self.protect("NUMERICAL_FAULT")
            raise
        if not all(np.isfinite(v).all() for v in candidate.values()):
            self.protect("NONFINITE_STATE")
            raise RuntimeError("动力学输出非有限数值，停止推进")
        # 【提交状态】候选状态通过有限数检查后才替换真值，随后检查实际高度和新的到期时刻。
        self._state = candidate
        self._compensation = proposed
        self._ticks += 1
        z = self._state["x"][2]
        vertical, horizontal = self._boundary_margins()
        tolerance = min(self.limits.boundary_numerical_tolerance_m, vertical / 100., horizontal / 100.)
        # 仅忽略微米级浮点误差，不把0.30米保护下限放宽到0.28米。
        if not self.limits.min_altitude_m - tolerance <= z <= self.limits.max_altitude_m + tolerance:
            self.protect("ALTITUDE_STATE_LIMIT")
        elif np.linalg.norm(self._state["x"][:2] - self._origin_xy) > self.limits.horizontal_radius_m + tolerance:
            self.protect("HORIZONTAL_STATE_LIMIT")
        self._expire_input()
        return self.get_state()

    def get_state(self):
        """返回JSON可序列化副本，调用方不能通过快照修改核心。"""
        return jsonable({"interface_version": "0.3", "data_source": "simulation_truth",
                         "time_s": self.time_s, "sequence": self._ticks, "mode": self._mode,
                         "protection_reason": self._reason, "state": copy.deepcopy(self._state),
                         "boundary_recovery": self.boundary_recovery(),
                         "target_position_m": self._target.copy(),
                         "reference_velocity_m_s": self._reference_v.copy(),
                         "target_yaw_rad": self._yaw, "yaw_rate_rad_s": self._yaw_rate,
                         "environment": {"wind_m_s": self._state["wind"].copy(),
                                         "compensation_enabled": self._compensation_enabled,
                                         "compensation_acceleration_m_s2": self._compensation.copy()}})


class BoundaryRecoveryPending(RuntimeError):
    """边界实际状态尚未进入恢复区，服务应返回可显示的409而非虚报解除成功。"""


def to_ue_pose(snapshot):
    """约定核心x前/y左/z上(m)→UE x前/y右/z上(cm)，q均为xyzw。"""
    reflection = np.diag([1., -1., 1.])
    state = snapshot["state"]
    position = np.asarray(state["x"], dtype=float)
    quaternion = np.asarray(state["q"], dtype=float)
    if position.shape != (3,) or quaternion.shape != (4,) or not np.isfinite(position).all() or not np.isfinite(quaternion).all():
        raise ValueError("位置/姿态维度或数值无效")
    if not np.isclose(np.linalg.norm(quaternion), 1., atol=1e-6, rtol=0):
        raise ValueError("姿态应为单位四元数")
    rotation = Rotation.from_quat(quaternion).as_matrix()
    # 【UE转换】位置乘100换成cm并翻转y；旋转须对机体系和世界系同时反射，即S*R*S。
    return {"position_cm": (100 * reflection @ position).tolist(),
            "quaternion_xyzw": Rotation.from_matrix(reflection @ rotation @ reflection).as_quat().tolist()}
