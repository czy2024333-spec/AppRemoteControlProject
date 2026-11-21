# -*- coding: utf-8 -*-
"""动力学与参数工作包（UAV-001 / SIM-001~002）：质量、惯量、动力、负载与限幅
参数的编辑、保存、恢复与版本闭环。

- 参数对象可整体查询、按字段编辑、持久化到 JSON、启动时恢复；
- 每次成功编辑使版本号 +1，服务层在审计中记录变更；
- 质量/惯量/动力/负载在悬停时提交生效，仿真时钟连续；
- 限幅参数可即时生效（由服务层同步到动力学核心的 CoreLimits）。
"""
import copy
import json
from pathlib import Path
import tempfile

# 参数版本文件名。
PARAMS_FILENAME = 'dynamics_params.json'

# 通用参数样例。服务初始化时使用每个成员的baseline_physics，反映其实际仿真基准。
DEFAULT_DYNAMICS_PARAMS = {
    'mass_kg': 1.0,
    'inertia_kg_m2': {'Ixx': 0.012, 'Iyy': 0.012, 'Izz': 0.022},
    'power': {'max_thrust_n': 20.0, 'motor_time_constant_s': 0.05},
    'payload': {'mass_kg': 0.0, 'enabled': False},
    'limits': {
        'min_altitude_m': 0.3,
        'max_altitude_m': 10.0,
        'max_vertical_speed_m_s': 0.5,
        'max_horizontal_speed_m_s': 0.5,
        'max_yaw_rate_rad_s': 0.5,
        'max_reference_acceleration_m_s2': 0.5,
    },
}


def _finite(value, name, low, high):
    # 直接比较上下界也能拒绝NaN、无穷和超大JSON整数，避免浮点转换溢出。
    if type(value) not in (int, float) or not low <= value <= high:
        raise ValueError('%s 必须在 %.4g 到 %.4g 之间' % (name, low, high))
    return float(value)


def validate(candidate):
    if not isinstance(candidate, dict) or set(candidate) != set(DEFAULT_DYNAMICS_PARAMS):
        raise ValueError('参数字段不完整或含未知字段')

    mass = _finite(candidate['mass_kg'], 'mass_kg', 0.005, 50.0)

    inertia = candidate['inertia_kg_m2']
    if not isinstance(inertia, dict) or set(inertia) != {'Ixx', 'Iyy', 'Izz'}:
        raise ValueError('inertia_kg_m2 需完整声明 Ixx/Iyy/Izz')
    for k in ('Ixx', 'Iyy', 'Izz'):
        _finite(inertia[k], 'inertia.' + k, 1e-7, 1.0)

    power = candidate['power']
    if not isinstance(power, dict) or set(power) != {'max_thrust_n', 'motor_time_constant_s'}:
        raise ValueError('power 需完整声明 max_thrust_n/motor_time_constant_s')
    _finite(power['max_thrust_n'], 'power.max_thrust_n', 0.05, 500.0)
    _finite(power['motor_time_constant_s'], 'power.motor_time_constant_s', 0.001, 1.0)

    payload = candidate['payload']
    if not isinstance(payload, dict) or set(payload) != {'mass_kg', 'enabled'}:
        raise ValueError('payload 需完整声明 mass_kg/enabled')
    _finite(payload['mass_kg'], 'payload.mass_kg', 0.0, 10.0)
    if type(payload['enabled']) is not bool:
        raise ValueError('payload.enabled 必须是布尔值')

    limits = candidate['limits']
    if not isinstance(limits, dict) or set(limits) != set(DEFAULT_DYNAMICS_PARAMS['limits']):
        raise ValueError('limits 字段不完整或含未知字段')
    lo = _finite(limits['min_altitude_m'], 'limits.min_altitude_m', 0.001, 5.0)
    hi = _finite(limits['max_altitude_m'], 'limits.max_altitude_m', 0.5, 50.0)
    if lo >= hi:
        raise ValueError('高度下限必须小于上限')
    _finite(limits['max_vertical_speed_m_s'], 'limits.max_vertical_speed_m_s', 0.05, 5.0)
    _finite(limits['max_horizontal_speed_m_s'], 'limits.max_horizontal_speed_m_s', 0.05, 10.0)
    _finite(limits['max_yaw_rate_rad_s'], 'limits.max_yaw_rate_rad_s', 0.05, 6.0)
    _finite(limits['max_reference_acceleration_m_s2'], 'limits.max_reference_acceleration_m_s2', 0.05, 20.0)

    return copy.deepcopy(candidate)


def update(current, patch):
    """按字段合并编辑补丁，返回通过校验的新参数。"""
    if not isinstance(patch, dict) or not patch:
        raise ValueError('参数补丁不能为空')
    if set(patch) - set(DEFAULT_DYNAMICS_PARAMS):
        raise ValueError('参数补丁含不可修改字段')
    candidate = copy.deepcopy(current)
    for key, value in patch.items():
        if isinstance(value, dict) and isinstance(candidate.get(key), dict):
            merged = copy.deepcopy(candidate[key])
            merged.update(value)
            candidate[key] = merged
        else:
            candidate[key] = value
    return validate(candidate)

def view(raw):
    """从已发布快照读取参数，HTTP查询不接触正在更新的核心。"""
    return dict(device_id=raw['device_id'],boot_id=raw['boot_id'],
        params_version=raw['dynamics_params_version'],
        dynamics_params=copy.deepcopy(raw['dynamics_params']),
        dynamics_params_origin=raw['dynamics_params_origin'],
        effective_control_limits=copy.deepcopy(raw['effective_control_limits']))


def save(params, path, version):
    """在同目录写好临时文件后原子替换，失败时保留原参数文件。"""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {'version': int(version), 'params': params}
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent,
                prefix=path.name+'.', suffix='.tmp', delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(payload, stream, ensure_ascii=False, indent=2, allow_nan=False)
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def load(path, default=None):
    """缺少文件时使用该成员的基准机型，损坏或非法文件显式报错。"""
    if not Path(path).exists():
        return validate(DEFAULT_DYNAMICS_PARAMS if default is None else default), 1
    with Path(path).open('r', encoding='utf-8-sig') as stream:
        data = json.load(stream)
    if isinstance(data, dict) and 'params' in data:
        if set(data)-{'version','params'}:
            raise ValueError('参数文件包含未知字段')
        version = data.get('version', 1)
        if type(version) is not int or version < 1:
            raise ValueError('参数版本必须为正整数')
        return validate(data['params']), version
    return validate(data), 1
