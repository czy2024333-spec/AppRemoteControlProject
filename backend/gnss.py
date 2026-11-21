"""米制局部坐标下的 GNSS 误差仿真，地理坐标转换留待后续接入。"""
from collections import deque
import copy
import math
import random


TIME_EPSILON_S = 1e-9
DEFAULT_GNSS_CONFIG = dict(
    enabled=False,
    rate_hz=20,
    noise_std_m=.8,
    drift_m_per_s=[.02,0.,0.],
    latency_ms=80,
    loss_probability=0.,
    stale_after_ms=500,
)


def validate_gnss_config(value):
    """配置只影响测量误差，字段与取值在启动仿真实例前校验。"""
    if not isinstance(value,dict) or set(value)-DEFAULT_GNSS_CONFIG.keys():
        raise ValueError('gnss配置包含未知字段或类型错误')
    result = copy.deepcopy(DEFAULT_GNSS_CONFIG)
    result.update(copy.deepcopy(value))
    if type(result['enabled']) is not bool:
        raise ValueError('gnss.enabled必须为bool')
    if type(result['rate_hz']) is not int or not 1<=result['rate_hz']<=50:
        raise ValueError('gnss.rate_hz必须为1至50的整数')
    for name,low,high in (
        ('noise_std_m',0.,20.),
        ('latency_ms',0.,5000.),
        ('loss_probability',0.,1.),
        ('stale_after_ms',1.,10000.),
    ):
        number = result[name]
        if type(number) not in (int,float) or not low<=number<=high:
            raise ValueError('gnss.'+name+'超出允许范围')
    drift = result['drift_m_per_s']
    if not isinstance(drift,list) or len(drift)!=3 or any(
            type(v) not in (int,float) or not -1<=v<=1 for v in drift):
        raise ValueError('gnss.drift_m_per_s需要三个有限分量，每轴范围-1至1m/s')
    return result


class LocalGnssSimulator:
    """按仿真时间采样，独立随机源产生误差，有界队列模拟数据到达延迟。"""

    def __init__(self, config=None, seed=0):
        self.config = validate_gnss_config({} if config is None else config)
        if type(seed) is not int or not 0<=seed<2**32:
            raise ValueError('GNSS随机种子范围0至2**32-1')
        self.seed = seed
        self.period_s = 1 / self.config['rate_hz']
        self.reset()

    def reset(self):
        # 位置误差和传输丢包各用一个随机源，调整其中一项时仍可逐个样本对照。
        self.loss_rng = random.Random(self.seed)
        self.noise_rng = random.Random(f'gnss-position:{self.seed}')
        capacity = math.ceil(self.config['latency_ms']/1000*self.config['rate_hz'])+2
        self.pending = deque(maxlen=capacity)
        self.drift_origin_s = None
        self.grid_origin_s = 0.
        self.next_slot = 0
        self.previous_tick = None
        self.generated = 0
        self.delivered_sequence = 0
        self.lost_packets = 0
        self.last_packet_lost = False
        self.sample = None

    def _enqueue_sample(self, position_m, sample_s, unix_ms):
        self.generated += 1
        # 即使本包稍后丢失，接收机仍已采样。这样提高丢包率不会改变后续样本的位置误差。
        sigma = self.config['noise_std_m']
        noise = [self.noise_rng.gauss(0., sigma) for _ in range(3)] if sigma else (0., 0., 0.)
        sample = None
        if self.loss_rng.random() >= self.config['loss_probability']:
            elapsed_s = sample_s - self.drift_origin_s
            drift = self.config['drift_m_per_s']
            sample = dict(
                sequence=self.generated, simulation_time_s=sample_s, measured_at_unix_ms=unix_ms,
                position_m=[p + v * elapsed_s + e for p, v, e in zip(position_m, drift, noise)],
            )
        available_s = sample_s + self.config['latency_ms'] / 1000
        self.pending.append((available_s, self.generated, sample))

    def update(self, position_m, simulation_time_s, measured_at_unix_ms):
        if not self.config['enabled']:
            return self.state()
        if not isinstance(position_m,(list,tuple)) or len(position_m)!=3 or any(
                type(v) not in (int,float) or not math.isfinite(v) for v in position_m):
            raise ValueError('GNSS采样位置必须为有限三维向量')
        if any(type(v) not in (int,float) or not math.isfinite(v) or v<0
                for v in (simulation_time_s,measured_at_unix_ms)):
            raise ValueError('GNSS采样时间必须为非负有限值')
        previous = self.previous_tick
        if previous is not None and simulation_time_s == previous[0]:
            # 同一步可能因新控制报文再次发布。保留首次真值和墙钟作为插值依据。
            return self.state()
        if previous is not None and simulation_time_s < previous[0]:
            self.reset()
            previous = None
        if previous is None:
            self.drift_origin_s = simulation_time_s
        if previous is None or simulation_time_s - previous[0] > self.period_s + TIME_EPSILON_S:
            # 真值中断超过一个采样周期时从当前点重新计时，保留漂移，不补造中间轨迹。
            self.grid_origin_s = simulation_time_s
            self.next_slot = 0

        scheduled_s = self.grid_origin_s + self.next_slot / self.config['rate_hz']
        if scheduled_s <= simulation_time_s + TIME_EPSILON_S:
            sample_s = min(scheduled_s, simulation_time_s)
            sample_position, sample_unix_ms = position_m, measured_at_unix_ms
            if previous is not None and simulation_time_s - sample_s > TIME_EPSILON_S:
                # 采样点落在两个动力学步之间时，位置和 Unix 时刻使用同一插值比例。
                prev_s, prev_position, prev_unix_ms = previous
                fraction = (sample_s - prev_s) / (simulation_time_s - prev_s)
                sample_position = [a + (b - a) * fraction for a, b in zip(prev_position, position_m)]
                sample_unix_ms = prev_unix_ms + (measured_at_unix_ms - prev_unix_ms) * fraction
            self._enqueue_sample(sample_position, sample_s, sample_unix_ms)
            self.next_slot += 1
        self.previous_tick = (simulation_time_s, tuple(position_m), measured_at_unix_ms)

        while self.pending and self.pending[0][0] <= simulation_time_s + TIME_EPSILON_S:
            _,self.delivered_sequence,sample = self.pending.popleft()
            self.last_packet_lost = sample is None
            if self.last_packet_lost:
                self.lost_packets += 1
            else:
                # 交付的是过去采到的坐标，测量时间保留采样时刻，另记实际交付仿真时刻。
                self.sample = dict(sample,delivered_at_simulation_time_s=simulation_time_s)
        return self.state()

    def state(self):
        return dict(source='SIM_GNSS',sample=copy.deepcopy(self.sample),last_packet_lost=self.last_packet_lost,
            packet_sequence=self.delivered_sequence,lost_packets=self.lost_packets,
            pending_samples=len(self.pending),rate_hz=self.config['rate_hz'],
            latency_ms=self.config['latency_ms'],stale_after_ms=self.config['stale_after_ms'])
