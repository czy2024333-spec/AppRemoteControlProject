"""11/26 状态回放：10Hz 抽稀落盘，并提供 UE 快照查询与 JSONL 导出。"""
from contextlib import contextmanager
from collections import Counter
import math
from pathlib import Path
import re
import threading
import time

from async_audit import AsyncAudit
from logs_query import LogError, LogIndex

REPLAY_SCHEMA = 'arc.ue-replay/v1'
REPLAY_EVENT = 'STATE_FRAME'
REPLAY_RATE_HZ = 10
STATE_VALID = 1 << 0
POSITION_VALID = 1 << 1
ATTITUDE_VALID = 1 << 2
SIMULATED_TRUTH = 1 << 3


def _vector(raw, key, size):
    value = raw.get(key)
    if not isinstance(value, (list, tuple)) or len(value) != size:
        raise ValueError(key + ' 维度无效')
    if any(type(item) not in (int, float) or not math.isfinite(item) for item in value):
        raise ValueError(key + ' 含非有限数值')
    return [float(item) for item in value]


def _ue_device_id(device_id):
    match = re.search(r'(\d+)$', device_id)
    if not match:
        return None
    value = int(match.group(1))
    return value if value <= 2147483647 else None


def replay_sample(raw, *, cluster_id, boot_id, frame_sequence, recorded_at_unix_ms):
    """把后端 FLU/米状态转换为 CsaStateSnapshot 所需的 UE/厘米数据。

    WallClockSec 是快照进入 UE 游戏线程的时间，必须由 C 侧注入时填写，不能伪造为录制时间。
    """
    position = _vector(raw, 'position_m', 3)
    velocity = _vector(raw, 'velocity_m_s', 3)
    quaternion = _vector(raw, 'quaternion_xyzw', 4)
    angular = _vector(raw, 'angular_velocity_body_rad_s', 3)
    norm = math.sqrt(sum(value * value for value in quaternion))
    if not math.isclose(norm, 1., abs_tol=1e-6, rel_tol=0):
        raise ValueError('quaternion_xyzw 应为单位四元数')
    online = raw.get('online') is True
    x, y, z, w = quaternion
    flags = SIMULATED_TRUTH | ((STATE_VALID | POSITION_VALID | ATTITUDE_VALID) if online else 0)
    return dict(
        schema=REPLAY_SCHEMA, event=REPLAY_EVENT,
        cluster_id=cluster_id, boot_id=boot_id,
        device_id=raw['device_id'], ue_device_id=_ue_device_id(raw['device_id']),
        frame_sequence=frame_sequence, sequence=raw['sequence'],
        server_time_ms=recorded_at_unix_ms, recorded_at_unix_ms=recorded_at_unix_ms,
        simulation_time_s=raw['simulation_time_s'], online=online,
        mode=raw['mode'], protection_reason=raw.get('protection_reason'),
        source=dict(
            coordinate_frame='SIM_WORLD_X_FORWARD_Y_LEFT_Z_UP',
            position_m=position, velocity_m_s=velocity,
            quaternion_xyzw=quaternion, angular_velocity_body_rad_s=angular),
        ue=dict(
            coordinate_frame='UE_LH_ZUP_CM',
            position_cm=[100 * position[0], -100 * position[1], 100 * position[2]],
            quaternion_xyzw=[-x, y, -z, w],
            linear_velocity_cm_s=[100 * velocity[0], -100 * velocity[1], 100 * velocity[2]],
            # 角速度是轴向量：镜像 Y 时按 det(M)·M 变换，与 CsaCoordinateConverter 一致。
            # 若按普通向量处理，实时流与回放流的 roll/yaw 速率方向会相反。
            angular_velocity_body_rad_s=[-angular[0], angular[1], -angular[2]],
            quaternion_convention='XYZW_BODY_TO_WORLD',
            angular_velocity_reference_frame='BODY', flags=flags))


class StateReplay:
    """回放与实时审计分文件、分队列；回放故障只标记证据不完整，不锁存控制。"""

    def __init__(self, directory, members, cluster_id, boot_id, *, sample_interval_s=.1):
        if not 0.02 <= sample_interval_s <= 1:
            raise ValueError('回放采样周期必须在0.02至1秒之间')
        self.directory = Path(directory).resolve()/'replay'
        self.cluster_id = cluster_id
        self.boot_id = boot_id
        self.sample_interval_s = sample_interval_s
        self.ue_device_ids = {name:_ue_device_id(name) for name in members}
        # 后端支持完整字符串设备ID。只有唯一的 Int32 后缀才能作为UE数字编号。
        counts = Counter(self.ue_device_ids.values())
        self.ue_device_ids = {name: value if counts[value] == 1 else None
            for name, value in self.ue_device_ids.items()}
        self.writer = self.index = None
        self.failure = None
        self.capture_lock = threading.Lock()
        self.next_capture_at = 0.
        self.frame_sequence = 0
        try:
            self.writer = AsyncAudit(self.directory/'state_frames.jsonl', capacity=2048)
            self.index = LogIndex(self.directory, members, primary_filename='state_frames.jsonl',
                include_member_logs=False)
        except OSError as exc:
            # 回放是观察证据，启动时磁盘不可写也需要让实时仿真继续，并公开故障。
            self.failure = 'REPLAY_START_FAILED: '+str(exc)
            if self.writer is not None:
                self.writer.close()

    def capture(self, states, captured_at):
        with self.capture_lock:
            if captured_at < self.next_capture_at or not self.status()['healthy']:
                return False
            # 以绝对计划时刻推进；25Hz源在0/.04/.08/.12...到达时仍平均10Hz。
            # 调度落后时跳过过期计划，不在下一轮补发一串旧帧。
            planned = self.next_capture_at or captured_at
            periods = math.floor((captured_at-planned)/self.sample_interval_s)+1
            self.next_capture_at = planned + periods*self.sample_interval_s
            self.frame_sequence += 1
            sequence = self.frame_sequence
        recorded_at = time.time()*1000
        try:
            samples = [replay_sample(raw, cluster_id=self.cluster_id, boot_id=self.boot_id,
                frame_sequence=sequence, recorded_at_unix_ms=recorded_at) for raw in states]
            # 空白或有歧义的数字映射保持为空，消费端始终可以用完整device_id关联成员。
            for sample in samples:
                sample['ue_device_id'] = self.ue_device_ids[sample['device_id']]
        except (KeyError, TypeError, ValueError, OverflowError) as exc:
            self.writer._fail('REPLAY_INVALID_STATE: '+str(exc))
            return False
        for sample in samples:
            if not self.writer.emit(sample):
                return False
        return True

    def status(self):
        writer = self.writer.status() if self.writer is not None else dict(healthy=False,failure=self.failure)
        return dict(schema=REPLAY_SCHEMA, target_rate_hz=1/self.sample_interval_s,
            frame_sequence=self.frame_sequence, file='replay/state_frames.jsonl',
            healthy=self.failure is None and writer['healthy'], failure=self.failure or writer['failure'],
            writer=writer)

    def query(self, params, members):
        if params.get('event') not in (None, '', REPLAY_EVENT):
            raise ValueError('回放接口仅支持 STATE_FRAME')
        if self.index is None:
            raise LogError(503,'REPLAY_RECORDING_FAILED','回放存储启动失败，请检查 health/replay')
        query = dict(params, event=REPLAY_EVENT)
        result = self.index.query(query, members)
        result['samples'] = result.pop('events')
        result.update(replay_schema=REPLAY_SCHEMA, target_rate_hz=1/self.sample_interval_s,
            recording=self.status())
        result['integrity'] = dict(result['integrity'],
            complete=result['integrity']['complete'] and self.status()['healthy'])
        return result

    @contextmanager
    def export(self, params, members):
        if params.get('event') not in (None, '', REPLAY_EVENT):
            raise ValueError('回放接口仅支持 STATE_FRAME')
        if params.get('format') not in (None, '', 'jsonl'):
            raise ValueError('回放导出仅支持 jsonl')
        if self.status()['failure']:
            raise LogError(409, 'REPLAY_RECORDING_FAILED',
                '状态录制曾中断，导出会不完整；请检查 health/replay 后重新录制')
        query = dict(params, event=REPLAY_EVENT, format='jsonl')
        with self.index.export(query, members) as export:
            export.update(filename='ue-replay-'+time.strftime('%Y%m%d-%H%M%S')+'.jsonl',
                replay_schema=REPLAY_SCHEMA)
            yield export

    def close(self):
        writer_closed = self.writer.close() if self.writer is not None else self.failure is None
        if self.index is None:
            return writer_closed
        deadline = time.monotonic()+1
        while time.monotonic() < deadline:
            status = self.index.status()
            if status.get('caught_up') or status.get('failure'):
                break
            time.sleep(.02)
        self.index.close()
        return writer_closed and self.failure is None
