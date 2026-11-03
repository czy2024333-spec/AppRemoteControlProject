"""每个成员拥有独立核心、输入会话、随机测量和发布配置，不打开单独网络控制口。"""
import copy
import json
import math
import time
import threading
from pathlib import Path
import numpy as np
from app_server import SimulationService
from runtime_info import IMPLEMENTATION_VERSION, identifier
from simulation_core import SimulationCore
from control_config import DEFAULT, update as update_control, validate as validate_control


def validate_member(spec):
    if not isinstance(spec, dict) or set(spec) != {'device_id','dynamics','control','sensors','state'}:
        raise ValueError('成员需要device_id/dynamics/control/sensors/state，禁止未知字段')
    identifier(spec['device_id'])
    if not isinstance(spec['dynamics'], dict) or set(spec['dynamics'])-{'hover_position_m','mass_scale','position_gain_scale','sim_rate_hz'}:
        raise ValueError('仅允许独立设置初始位置、质量比例及位置增益')
    rate=spec['dynamics'].get('sim_rate_hz',100)
    if type(rate) is not int or not 50<=rate<=500: raise ValueError('成员sim_rate_hz必须为50至500的整数')
    for key in ('mass_scale','position_gain_scale'):
        value = spec['dynamics'].get(key, 1.)
        if type(value) not in (int,float) or not math.isfinite(value) or not .5 <= value <= 1.5:
            raise ValueError(key+'范围为0.5至1.5')
    pos = spec['dynamics'].get('hover_position_m', [0,0,1])
    if not isinstance(pos,list) or len(pos)!=3 or any(type(v) not in (float,int) or not math.isfinite(v) or abs(v)>1000 for v in pos) or not .3<=pos[2]<=10:
        raise ValueError('初始位置必须有效，高度0.3至10米')
    if not isinstance(spec['control'],dict): raise ValueError('control必须为配置对象')
    control = update_control(DEFAULT, spec['control']) if spec['control'] else copy.deepcopy(DEFAULT)
    sensors = spec['sensors']
    if not isinstance(sensors,dict) or set(sensors)!={'enabled','rate_hz','seed','position_std_m','velocity_std_m_s'}:
        raise ValueError('传感器字段不完整或包含未知字段')
    if type(sensors['enabled']) is not bool or type(sensors['seed']) is not int or not 0<=sensors['seed']<2**32:
        raise ValueError('传感器enabled/seed无效')
    for name,low,high in [('rate_hz',1,50),('position_std_m',0,1),('velocity_std_m_s',0,1)]:
        v = sensors[name]
        if type(v) not in (int,float) or not math.isfinite(v) or not low<=v<=high:
            raise ValueError('传感器'+name+'超出范围')
    state = spec['state']
    if not isinstance(state,dict) or set(state)!={'stream_hz'} or type(state['stream_hz']) is not int or not 10<=state['stream_hz']<=50:
        raise ValueError('状态stream_hz必须为10至50的整数')
    return copy.deepcopy(spec), control


class ManagedMember(SimulationService):
    def __init__(self, spec, cluster_id, tokens, directory, audit_sink=None):
        self.publication_lock = threading.RLock()
        self.spec, control = validate_member(spec)
        self.cluster_id = cluster_id
        self.rng = np.random.default_rng(spec['sensors']['seed'])
        self.measurement = None
        self.next_measurement_s = 0.
        self.sensor_sequence = 0
        self.last_step_at = time.monotonic()
        self.last_step_sequence = -1
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        cfg = json.loads((Path(__file__).parent/'core/lab_config.json').read_text(encoding='utf-8'))
        cfg.update(spec['dynamics'])
        cfg_path = directory/'dynamics.json'
        cfg_path.write_text(json.dumps(cfg), encoding='utf-8')
        core = SimulationCore(cfg_path)
        control_path = directory/'control.json'
        # 各成员受限控制策略独立持久化；没有保存值时使用注册配置。
        if control_path.exists():
            control = validate_control(json.loads(control_path.read_text(encoding='utf-8')))
        super().__init__(core, tokens=tokens, config=control, config_path=control_path, audit_sink=audit_sink)
        self.device_id = spec['device_id']
        self.audit_path = directory/'control_events.jsonl'
        self.publish()

    def publish(self):
        # 连同传感器及集群字段整体发布，读取者不能看见只更新一半的成员状态。
        with self.publication_lock:
            self._publish_complete()

    def state(self):
        with self.publication_lock:
            return super().state()

    def _publish_complete(self):
        # 独立随机源只作用于测量，不污染动力学真值、控制反馈或其他实例。
        sequence = self.core.get_state()['sequence']
        if sequence != self.last_step_sequence:
            self.last_step_at = time.monotonic()
            self.last_step_sequence = sequence
        sensor = self.spec['sensors']
        if sensor['enabled'] and self.core.time_s + 1e-9 >= self.next_measurement_s:
            raw = self.core.get_state()['state']
            self.sensor_sequence += 1
            self.measurement = dict(kind='SIMULATED_LOCAL_POSITION_VELOCITY', quality='SIMULATED',
                sequence=self.sensor_sequence, simulation_time_s=self.core.time_s,
                position_m=(np.array(raw['x'])+self.rng.normal(0,sensor['position_std_m'],3)).tolist(),
                velocity_m_s=(np.array(raw['v'])+self.rng.normal(0,sensor['velocity_std_m_s'],3)).tolist(),
                position_std_m=sensor['position_std_m'], velocity_std_m_s=sensor['velocity_std_m_s'])
            # 绝对仿真时间节拍避免30Hz等不能整除100Hz的采样逐步降成25Hz。
            periods = math.floor(self.core.time_s * sensor['rate_hz'] + 1e-8) + 1
            self.next_measurement_s = periods / sensor['rate_hz']
        super().publish()
        with self.lock:
            config = copy.deepcopy(self.spec)
            config['control'] = copy.deepcopy(self.config)
            self.latest.update(implementation_version=IMPLEMENTATION_VERSION, cluster_id=self.cluster_id,
                member_boot_id=self.boot_id,
                device_id=self.spec['device_id'], sensor_measurement=copy.deepcopy(self.measurement),
                instance_config=config, state_stream_target_hz=self.spec['state']['stream_hz'])

    def execute(self, item):
        # 仅管理器内部调用；HTTP路由与批分发白名单均不暴露此路径。
        if item['path']=='/__cluster_barrier':
            if item['body']['protect']:
                self.core.protect(item['body']['reason'])
            else:
                self.core.stop()
            self.clear_selection(item['body']['reason'])
            self.new_epoch(item['body']['reason'])
            return {}
        return super().execute(item)

    def barrier(self, reason, protect=False):
        return self.submit('/__cluster_barrier', dict(reason=reason,protect=protect), 'app')
