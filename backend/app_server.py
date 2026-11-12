"""用户主动切换、中立握手，单线程仲裁与三维仿真；原生客户端局域网接口。"""
import argparse
import copy
import hashlib
import hmac
import itertools
import json
import math
import queue
import socket
import sys
import threading
import time
import uuid
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from http_transport import JsonHandler, TransportError, validate_origins
from runtime_info import IMPLEMENTATION_VERSION, is_continuous
from control_config import CAPABILITIES, DEFAULT, update as update_config, validate as validate_config
from input_protocol import SUPPORTED, voice_result, ring_event

CORE_DIR = Path(__file__).resolve().parent/'core'
sys.path.insert(0, str(CORE_DIR))
from simulation_core import BoundaryRecoveryPending
SOURCE_IDS = ('app','ring','voice')
DEMO_TOKENS = {'app':'demo-app-token-2026', 'ring':'demo-ring-token-2026', 'voice':'demo-voice-token-2026'}
VOICE_MOTIONS = {
    'forward': (.3, 0., 0., 0.), 'backward': (-.3, 0., 0., 0.),
    'left': (0., .3, 0., 0.), 'right': (0., -.3, 0., 0.),
    'up': (0., 0., .3, 0.), 'down': (0., 0., -.3, 0.),
    'turn_left': (0., 0., 0., .3), 'turn_right': (0., 0., 0., -.3),
}


class APIError(Exception):
    def __init__(self, status, message, code='REQUEST_REJECTED'):
        super().__init__(message)
        self.status, self.message, self.code = status, message, code


def number(value, name):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise APIError(400, name+'必须是有限数值', 'INVALID_ARGUMENT')
    return value


class SimulationService:
    def __init__(self, core, tokens=None, config=None, config_path=None, audit_sink=None):
        self.core = core
        # 独立多机模式注入异步审计；旧模式保留原有日志行为。
        self.audit_sink = audit_sink
        self.tokens = dict(tokens or DEMO_TOKENS)
        if set(self.tokens) != set(SOURCE_IDS) or len(set(self.tokens.values())) != 3:
            raise ValueError('三种来源必须分别配置不同Token')
        if any(not isinstance(t, str) or len(t)<16 or not t.isascii() or any(c.isspace() for c in t) for t in self.tokens.values()):
            raise ValueError('Token至少16个无空白ASCII字符')
        self.commands = queue.PriorityQueue(maxsize=128)
        self.queue_counter = itertools.count()
        self.device_id = 'sim-drone-1'
        self.config = validate_config(config or DEFAULT)
        self.config_version = 1
        self.control_config_origin = 'REGISTRY'
        self.config_path = config_path
        self.boot_id = uuid.uuid4().hex
        self.audit_path = Path(__file__).resolve().parent/'results/control_events.jsonl'
        self.selected_source = None
        self.pending_source = None
        self.pending_until = None
        self.pending_command = None
        self.active_command = None
        self.confirmations = {}
        self.records = {}
        self.command_view = {}
        self.events = []
        self.lock = threading.Lock()
        self.stopping = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.sessions = {}
        self.owner = None
        self.owner_generation = 0
        self.control_epoch = uuid.uuid4().hex
        self.last_applied = None
        self.motion = None
        self.deadline = None
        self.voice_end = None
        self.filtered_yaw = 0.
        self.transition = 'INITIAL_HOLD'
        self.failure = None
        self._published = None
        self.state_changed = None
        self.tick_intervals = deque(maxlen=512)
        self.tick_overrun_count = 0
        self.tick_diagnostics = dict(tick_jitter_p99_ms=None, tick_jitter_samples=0)
        self.publish()

    def authenticate(self, header):
        # 来源身份由不同Token确定，不能相信客户端请求体自报的source/role。
        for source, token in self.tokens.items():
            if hmac.compare_digest(header.encode('utf-8'), ('Bearer '+token).encode('ascii')):
                return source
        raise APIError(401, '来源Token无效', 'UNAUTHORIZED')

    def start(self):
        self.thread.start()

    def close(self):
        self.stopping.set()
        if self.thread.ident is not None:
            self.thread.join(3)

    def build_state(self, raw):
        return dict(api_version='0.5', implementation_version=IMPLEMENTATION_VERSION, boot_id=self.boot_id, data_source=raw['data_source'],
            boundary_recovery=raw['boundary_recovery'],
            server_time_ms=time.time()*1000, simulation_time_s=raw['time_s'], sequence=raw['sequence'],
            mode=raw['mode'], protection_reason=raw['protection_reason'],
            position_m=raw['state']['x'], velocity_m_s=raw['state']['v'], quaternion_xyzw=raw['state']['q'],
            angular_velocity_body_rad_s=raw['state']['w'], target_position_m=raw['target_position_m'],
            reference_velocity_m_s=raw['reference_velocity_m_s'], target_yaw_rad=raw['target_yaw_rad'],
            environment=raw['environment'], active_source=self.owner,
            device_id=self.device_id, selected_source=self.selected_source, pending_source=self.pending_source,
            input_task_capabilities={'takeoff':False,'land':False,'return_home':False,'hover':True},
            hardware_acceptance='PENDING_REAL_HARDWARE_AND_SDK',
            config_version=self.config_version, control_config=self.config, capabilities=CAPABILITIES,
            recent_events=self.events[-8:],
            recent_commands=[{k:v[k] for k in ('source','command_id','status','reason')} for v in list(self.records.values())[-8:]],
            control_epoch=self.control_epoch, arbitration_policy='USER_SWITCH_NEUTRAL',
            last_applied=self.last_applied,
            owner_generation=self.owner_generation,
            last_sequences={k:v['sequence'] for k,v in self.sessions.items()},
            transition_reason=self.transition, filtered_yaw_rate_rad_s=self.filtered_yaw,
            sim_rate_hz=1/self.core.dt, state_stream_target_hz=25,
            voice_remaining_ms=max(0., (self.voice_end-time.monotonic())*1000) if self.voice_end else 0.)

    def publish(self, raw=None):
        if raw is None:
            raw = self.core.get_state()
        previous = self._published
        stepped_at = time.monotonic() if previous is None or raw['sequence'] != previous[0]['sequence'] else previous[1]
        # 发布后不再修改快照。状态与步进时间一同替换，读者复制时无需持有发布锁。
        self._published = (self.build_state(raw), stepped_at)
        if self.state_changed is not None:
            self.state_changed.set()

    def published_snapshot(self):
        """内部只读引用，包含完整状态及对应的单调步进时刻。"""
        return self._published

    @property
    def last_step_at(self):
        return self._published[1]

    def refresh_record(self, key):
        # 命令历史只在变更时更新查询快照，避免每个100Hz动力学步复制全部历史。
        record = self.records[key]
        with self.lock:
            self.command_view[(record['source'],record['command_id'])] = {k:v for k,v in record.items() if k!='fingerprint'}

    def command_state(self, source, command_id, requester):
        # APP可查询各通路任务，其余来源只能查自己的命令，不能读其他来源的任务。
        if source!=requester and not CAPABILITIES[requester]['operator']:
            raise APIError(403,'没有跨来源查询权限','OPERATOR_REQUIRED')
        with self.lock:
            result = self.command_view.get((source,command_id))
            if result is None:
                raise APIError(404,'本会话中没有此命令','COMMAND_NOT_FOUND')
            return copy.deepcopy(result)

    def state(self):
        if self.failure:
            raise APIError(503, self.failure, 'SIMULATION_FAILURE')
        return copy.deepcopy(self.published_snapshot()[0])

    def health(self):
        state, stepped_at = self.published_snapshot()
        alive = self.thread.is_alive()
        failure = self.failure
        # 健康新鲜度使用动力学步进的单调时间，不能被校时或心跳刷新。
        age = max(0.,(time.monotonic()-stepped_at)*1000)
        return dict(healthy=alive and failure is None and age<500,implementation_version=IMPLEMENTATION_VERSION,
            mode='single',device_id=self.device_id,boot_id=self.boot_id,
            worker_alive=alive,state_age_ms=age,failure=failure, protection_mode=state['mode'],
            sim_rate_hz=state['sim_rate_hz'],tick_overrun_count=self.tick_overrun_count,**self.tick_diagnostics)

    def submit(self, path, body, source):
        item = dict(path=path, body=body, source=source, received=time.monotonic(),
                    epoch=time.time()*1000, done=threading.Event(),
                    claim_lock=threading.Lock(), queue_state='QUEUED')
        try:
            priority = 0 if path == '/api/protect' else 1 if path in ('/api/stop','/api/cancel','/api/disconnect') else 2
            # 规范化之前只决定排队优先级，真正执行仍必须经过全部参数/权限/时效校验。
            if source=='voice' and path in ('/api/voice','/api/voice-result'):
                try:
                    semantic = voice_result(body,self.state()['control_config']['input_policy']).get('action')
                    if semantic in ('stop','hover','protect'):
                        priority = 0 if semantic=='protect' else 1
                except ValueError:
                    pass
            if source=='ring' and path=='/api/ring-event':
                try:
                    mapped = ring_event(body,self.state()['control_config']['input_policy']).get('path')
                    if mapped in ('/api/stop','/api/protect','/api/disconnect'):
                        priority = 0 if mapped=='/api/protect' else 1
                except ValueError:
                    pass
            self.commands.put_nowait((priority,next(self.queue_counter),item))
        except queue.Full:
            raise APIError(503, '指令队列已满', 'QUEUE_FULL')
        if not item['done'].wait(2):
            # 未开始的请求可取消；已开始的操作不能谎称回滚，需查询命令状态。
            with item['claim_lock']:
                queued = item['queue_state']=='QUEUED'
                if queued: item['queue_state']='CANCELLED'
            if not item['done'].is_set():
                raise APIError(503, '确认超时；禁止重放运动请求',
                    'ACK_TIMEOUT' if queued else 'ACK_OUTCOME_UNKNOWN')
        if 'error' in item:
            raise item['error']
        return item['result']

    def new_epoch(self, reason, release=True):
        # 停止、切换和断连同时撤销尚未确认的危险动作。
        for key in list(self.confirmations):
            self.finish_command(key,'CANCELLED',reason)
        self.confirmations.clear()
        previous_source = self.owner or self.selected_source
        self.finish_command(self.active_command, 'CANCELLED', reason)
        self.active_command = None
        self.control_epoch = uuid.uuid4().hex
        self.last_applied = None
        self.transition = reason
        if release:
            if self.owner in self.sessions:
                session = self.sessions[self.owner]
                session['retired_intent'] = max(session['retired_intent'], session['intent'])
            self.owner = self.motion = self.deadline = self.voice_end = None
            self.filtered_yaw = 0.
        event = dict(reason=reason,server_time_ms=time.time()*1000,source=previous_source,
            device_id=self.device_id,boot_id=self.boot_id,simulation_time_s=self.core.time_s)
        self.events.append(event)
        self.events = self.events[-128:]
        # 失效原因持久化，便于联调复盘；日志不写Token和语音原始音频。
        if self.audit_sink is not None:
            self.audit_sink.emit(dict(event,event='CONTROL_TRANSITION'))
        else:
            self.audit_path.parent.mkdir(parents=True,exist_ok=True)
            with self.audit_path.open('a',encoding='utf-8') as log:
                log.write(json.dumps(event,ensure_ascii=False)+'\n')

    def finish_command(self, key, status, reason):
        if key in self.records and self.records[key]['status'] in ('EXECUTING','WAITING_NEUTRAL','WAITING_CONFIRMATION'):
            self.records[key]['status'] = status
            self.records[key]['reason'] = reason
            self.records[key]['completed_at_ms'] = time.time()*1000
            self.refresh_record(key)

    def clear_selection(self, reason):
        self.finish_command(self.pending_command,'CANCELLED',reason)
        self.pending_command = None
        self.selected_source = self.pending_source = self.pending_until = None

    def expire(self):
        # 墙钟期限在处理每条请求前检查，队列内新请求不能掩盖超时。
        now = time.monotonic()
        for key, pending in list(self.confirmations.items()):
            if now >= pending['until']:
                self.finish_command(key,'TIMED_OUT','CONFIRMATION_TIMEOUT')
                self.confirmations.pop(key,None)
        if self.pending_until is not None and now >= self.pending_until:
            self.core.protect('NEUTRAL_TIMEOUT')
            self.finish_command(self.pending_command,'TIMED_OUT','NEUTRAL_TIMEOUT')
            self.clear_selection('NEUTRAL_TIMEOUT')
            self.new_epoch('NEUTRAL_TIMEOUT')
        elif self.selected_source is not None and any(now-self.sessions[s]['last_seen']>self.config['input_ttl_ms']/1000 for s in {self.selected_source,'app'}):
            missing = next(s for s in (self.selected_source,'app') if now-self.sessions[s]['last_seen']>self.config['input_ttl_ms']/1000)
            reason = 'CONTROL_LOST' if missing=='app' else 'PERIPHERAL_DISCONNECTED' if missing=='ring' else 'NETWORK_INTERRUPTED'
            self.core.protect(reason)
            self.clear_selection(reason)
            self.new_epoch(reason)
        elif self.owner == 'voice' and self.voice_end is not None and now >= self.voice_end:
            self.finish_command(self.active_command,'COMPLETED','DURATION_COMPLETED')
            self.active_command = None
            self.core.stop()
            self.new_epoch('VOICE_COMPLETED')
        elif self.deadline is not None and now >= self.deadline:
            self.core.protect('INPUT_TIMEOUT')
            self.clear_selection('INPUT_TIMEOUT')
            self.new_epoch('INPUT_TIMEOUT')

    def envelope(self, item, require_epoch=True):
        # 会话防止重连重放，轮次防止切换重放，序号防止重复与乱序；期限扣除报文年龄。
        body, source = item['body'], item['source']
        session = self.sessions.get(source)
        if not session or body.get('session_id') != session['id']:
            raise APIError(409, '来源会话已失效，请重新建立会话', 'STALE_SESSION')
        if not session.get('connected',True):
            raise APIError(409,'来源已断连，重连需建立新会话','DISCONNECTED')
        if body.get('device_id') != self.device_id:
            raise APIError(400,'设备控制域不匹配','WRONG_DEVICE')
        seq = body.get('command_sequence')
        if type(seq) is not int or seq < 0:
            raise APIError(400, 'command_sequence必须是非负整数', 'INVALID_ARGUMENT')
        if seq <= session['sequence']:
            raise APIError(409, '重复或乱序指令', 'STALE_SEQUENCE')
        if require_epoch and body.get('control_epoch') != self.control_epoch:
            raise APIError(409, '控制轮次已变化，旧输入不得重试', 'STALE_EPOCH')
        ttl = number(body.get('ttl_ms'), 'ttl_ms')
        sent = number(body.get('sent_at_ms'), 'sent_at_ms')
        if not 0 < ttl <= self.config['input_ttl_ms']:
            raise APIError(400, 'ttl_ms超过已配置范围', 'INVALID_ARGUMENT')
        age = item['epoch']-sent
        if age < -50:
            raise APIError(400, '客户端时钟超前超过50ms', 'CLOCK_SKEW')
        expires = item['received']+(ttl-max(0., age))/1000
        if expires <= time.monotonic():
            raise APIError(409, '报文已过期', 'EXPIRED_COMMAND')
        return seq, expires

    def accept_owner(self, source):
        if self.core.get_state()['mode'] == 'PROTECTED':
            raise APIError(409, '保护锁存；APP确认中立后恢复', 'PROTECTED')
        if not self.config['sources_enabled'][source]:
            raise APIError(403,'此来源已禁用','SOURCE_DISABLED')
        if self.selected_source != source or self.pending_source is not None:
            raise APIError(409,'来源未选定或尚未完成中立握手','SOURCE_NOT_SELECTED')

    def world_motion(self, body, values):
        frame = body.get('frame')
        if frame not in ('world','heading'):
            raise APIError(400, 'frame必须是world或heading', 'INVALID_ARGUMENT')
        vx, vy, vz, yaw_rate = values
        limit = self.config
        if math.hypot(vx,vy)>limit['max_horizontal_speed_m_s'] or abs(vz)>limit['max_vertical_speed_m_s'] or abs(yaw_rate)>limit['max_yaw_rate_rad_s']:
            raise APIError(400, '运动参数超过配置速度范围', 'INVALID_ARGUMENT')
        if frame == 'heading':
            x,y,z,w = self.core.get_state()['state']['q']
            yaw = math.atan2(2*(w*z+x*y), 1-2*(y*y+z*z))
            vx,vy = math.cos(yaw)*vx-math.sin(yaw)*vy, math.sin(yaw)*vx+math.cos(yaw)*vy
            norm = math.hypot(vx,vy)
            if norm>limit['max_horizontal_speed_m_s']:
                vx,vy = vx*limit['max_horizontal_speed_m_s']/norm, vy*limit['max_horizontal_speed_m_s']/norm
        return vx,vy,vz,yaw_rate

    def operator(self, source):
        if not CAPABILITIES[source]['operator']:
            raise APIError(403,'只有APP操作员有此权限','OPERATOR_REQUIRED')

    def execute(self, item):
        # 全部来源都在同一仿真线程串行校验、仲裁、下发，避免两路同时更新核心。
        self.expire()
        body, source, path = item['body'], item['source'], item['path']
        if path == '/api/session':
            if self.owner == source or self.selected_source == source or self.pending_source == source or (source=='app' and (self.selected_source is not None or self.pending_source is not None)):
                self.core.stop()
                self.clear_selection('SESSION_REPLACED')
                self.new_epoch('SESSION_REPLACED')
            old = self.sessions.get(source)
            if old:
                self.records = {k:v for k,v in self.records.items() if k[0]!=old['id']}
                with self.lock:
                    self.command_view = {k:v for k,v in self.command_view.items() if k[0]!=source}
            session_id = uuid.uuid4().hex
            self.sessions[source] = dict(id=session_id,sequence=-1,intent=-1,retired_intent=-1,event_sequence=-1,connected=True,last_seen=time.monotonic())
            return dict(session_id=session_id,source=source,device_id=self.device_id)
        ring_continuous = is_continuous(path,body) and path=='/api/ring-event'
        continuous = path in ('/api/control','/api/input','/api/heartbeat') or ring_continuous
        discrete = path in ('/api/switch','/api/adaptive-switch','/api/neutral','/api/voice',
            '/api/stop','/api/protect','/api/resume','/api/cancel','/api/disconnect','/api/config',
            '/api/ring-event','/api/voice-result','/api/confirm')
        discrete = discrete and not ring_continuous
        if not continuous and not discrete:
            raise APIError(404,'接口不存在','NOT_FOUND')
        session = self.sessions.get(source)
        if not session or body.get('session_id')!=session['id']:
            raise APIError(409,'来源会话已失效','STALE_SESSION')
        key = None
        if discrete:
            command_id = body.get('command_id')
            if not isinstance(command_id,str) or not 1<=len(command_id)<=64 or not all(c.isascii() and (c.isalnum() or c in '-_') for c in command_id):
                raise APIError(400,'command_id需为1–64位ASCII字母数字或-_','INVALID_ARGUMENT')
            key = (session['id'], command_id)
            content = {k:v for k,v in body.items() if k not in ('sent_at_ms','ttl_ms')}
            try:
                # 停止和保护的请求体可以相同，幂等校验必须同时比较动作路径。
                action = dict(path=path, body=content)
                fingerprint = hashlib.sha256(json.dumps(action,sort_keys=True,allow_nan=False).encode()).hexdigest()
            except ValueError:
                raise APIError(400,'命令包含非有限数值','INVALID_ARGUMENT')
            if key in self.records:
                # 离散重试返回已有执行状态，不重新执行、不续时；内容变化则拒绝复用ID。
                record = self.records[key]
                if record['fingerprint']!=fingerprint:
                    raise APIError(409,'同一command_id被用于不同动作','COMMAND_ID_REUSED')
                return dict(command_id=command_id,command_status=record['status'],idempotent_replay=True)
            if sum(k[0]==session['id'] for k in self.records)>=1024:
                raise APIError(503,'本会话离散命令历史已满，停止后重新建会话','COMMAND_HISTORY_FULL')
        safety_protect = False
        try:
            if source=='voice' and path in ('/api/voice','/api/voice-result'):
                safety_protect = voice_result(body,self.config['input_policy']).get('action')=='protect'
            elif source=='ring' and path=='/api/ring-event':
                safety_protect = ring_event(body,self.config['input_policy']).get('path')=='/api/protect'
        except ValueError:
            pass
        seq, expires = self.envelope(item, require_epoch=path not in ('/api/protect','/api/heartbeat') and not safety_protect)
        if discrete:
            # 预建记录以便执行过程中完成/取消其他任务；失败时撤销此记录。
            self.records[key] = dict(source=source,command_id=body['command_id'],fingerprint=fingerprint,
                status='COMPLETED',reason='APPLIED',created_at_ms=time.time()*1000)
        try:
            self.apply(item,seq,expires,key)
        except Exception:
            if key is not None:
                self.records.pop(key,None)
            raise
        session['sequence'] = seq
        session['last_seen'] = item['received']
        if key is not None:
            self.refresh_record(key)
        return dict(accepted_command_sequence=seq, **({} if key is None else dict(
            command_id=body['command_id'],command_status=self.records[key]['status'],idempotent_replay=False)))

    def axes(self, value):
        if not isinstance(value,list) or len(value)!=4:
            raise APIError(400,'axes必须是四元素列表[vx,vy,vz,yaw]','INVALID_ARGUMENT')
        values = [number(v,'axis') for v in value]
        if any(abs(v)>1 for v in values):
            raise APIError(400,'归一化轴值范围[-1,1]','INVALID_ARGUMENT')
        return values

    def apply(self, item, seq, expires, key):
        body, source, path = item['body'], item['source'], item['path']
        if path in ('/api/ring-event','/api/voice-result','/api/voice','/api/confirm') and not item.get('_validated_input'):
            return self.apply_external(item,seq,expires,key)
        if path == '/api/heartbeat':
            # 心跳只证明连接有效，不改变运动期限、操纵值或控制权。
            return
        if path in ('/api/switch','/api/adaptive-switch'):
            self.operator(source)
            if body.get('user_initiated') is not True:
                raise APIError(400,'切换必须由用户主动发起','USER_ACTION_REQUIRED')
            target = body.get('target_source')
            if not isinstance(target,str) or target not in SOURCE_IDS:
                raise APIError(400,'未知来源','INVALID_ARGUMENT')
            if not self.config['sources_enabled'][target]:
                raise APIError(403,'目标来源已禁用','SOURCE_DISABLED')
            if target not in self.sessions or not self.sessions[target]['connected']:
                raise APIError(409,'目标来源尚未连接','TARGET_DISCONNECTED')
            if time.monotonic()-self.sessions[target]['last_seen']>self.config['input_ttl_ms']/1000:
                raise APIError(409,'目标来源连接已过期','TARGET_DISCONNECTED')
            if self.core.get_state()['mode']=='PROTECTED':
                raise APIError(409,'保护中不能切换','PROTECTED')
            # CTL-003：主动切换先撤销旧输入并捕获悬停点，不能立即执行新通路的旧值。
            self.core.stop()
            self.clear_selection('SWITCH_REPLACED')
            self.new_epoch('USER_SWITCH_TO_'+target)
            self.pending_source = target
            self.pending_until = time.monotonic()+5.
            self.pending_command = key
            self.records[key]['status'] = 'WAITING_NEUTRAL'
        elif path == '/api/neutral':
            # 必须收到目标来源新鲜的实际中立采样，不能只信APP传一个“已归中”标记。
            if self.core.get_state()['mode']=='PROTECTED':
                raise APIError(409,'保护中不能启用输入','PROTECTED')
            if self.pending_source!=source:
                raise APIError(409,'不是等待归中的目标来源','SOURCE_NOT_PENDING')
            if any(abs(v)>self.config['deadzone'] for v in self.axes(body.get('axes'))):
                raise APIError(409,'输入尚未进入中立死区','NOT_NEUTRAL')
            self.selected_source = source
            self.owner_generation += 1
            self.pending_source = self.pending_until = None
            self.finish_command(self.pending_command,'COMPLETED','NEUTRAL_CONFIRMED')
            self.pending_command = None
            self.transition = 'NEUTRAL_CONFIRMED_'+source
        elif path == '/api/protect':
            # 保护请求走优先队列，允许不同于当前控制轮次，但仍须合法身份、序号和期限。
            self.core.protect('EMERGENCY_'+source.upper())
            self.clear_selection('EMERGENCY_'+source.upper())
            self.new_epoch('EMERGENCY_'+source.upper())
        elif path == '/api/stop':
            self.core.stop()
            if self.pending_source is not None:
                self.clear_selection('STOP_'+source.upper())
            self.new_epoch('STOP_'+source.upper())
        elif path == '/api/resume':
            self.operator(source)
            if body.get('neutral_confirmed') is not True:
                raise APIError(400,'必须确认全部来源已归中','NEUTRAL_REQUIRED')
            try:
                self.core.resume(neutral_confirmed=True)
            except BoundaryRecoveryPending as exc:
                # 【恢复应答】安全区/停稳条件未满足时保留保护与控制轮次，APP显示具体原因。
                raise APIError(409, str(exc), 'BOUNDARY_RECOVERY_PENDING') from exc
            self.clear_selection('OPERATOR_RESUME')
            self.new_epoch('OPERATOR_RESUME')
        elif path == '/api/cancel':
            target_source = body.get('target_source',source)
            if target_source!=source:
                self.operator(source)
            target_session = self.sessions.get(target_source) if isinstance(target_source,str) else None
            target_id = body.get('target_command_id')
            target_key = (target_session['id'],target_id) if target_session and isinstance(target_id,str) else None
            if target_key not in self.records:
                raise APIError(404,'目标命令不存在','COMMAND_NOT_FOUND')
            if target_key in self.confirmations:
                self.confirmations.pop(target_key,None)
                self.finish_command(target_key,'CANCELLED','COMMAND_CANCELLED')
            elif target_key==self.active_command:
                self.core.stop()
                self.new_epoch('COMMAND_CANCELLED')
            elif target_key==self.pending_command:
                self.core.stop()
                self.clear_selection('COMMAND_CANCELLED')
                self.new_epoch('COMMAND_CANCELLED')
        elif path == '/api/disconnect':
            # APP是操作端监督者；即使指环正在操纵，APP退出也必须停止并记录原因。
            reason = body.get('reason')
            if reason not in ('APP_EXIT','PERIPHERAL_DISCONNECTED','NETWORK_INTERRUPTED','INPUT_INVALID','CONTROL_LOST'):
                raise APIError(400,'未知断连/失效原因','INVALID_ARGUMENT')
            self.sessions[source]['connected'] = False
            if source=='app' or source in (self.owner,self.selected_source,self.pending_source):
                self.core.protect(reason)
                self.clear_selection(reason)
                self.new_epoch(reason)
        elif path == '/api/config':
            # 配置按版本更新，字段白名单限制能力；更新后需重新切换和归中才能操纵。
            self.operator(source)
            if self.core.get_state()['mode']!='HOLD' or self.pending_source is not None:
                raise APIError(409,'配置只能在悬停且无待切换输入时更新','MODE_NOT_ALLOWED')
            if type(body.get('config_version')) is not int or body['config_version']!=self.config_version:
                raise APIError(409,'配置版本已变化','CONFIG_VERSION_CONFLICT')
            try:
                candidate = update_config(self.config,body.get('patch'))
            except ValueError as exc:
                raise APIError(400,str(exc),'INVALID_CONFIG')
            if self.config_path is not None:
                # 先保存候选配置再提交版本；保存失败不部分更新内存配置。
                temporary = self.config_path.with_suffix('.'+self.boot_id+'.tmp')
                try:
                    temporary.write_text(json.dumps(candidate,ensure_ascii=False,indent=2),encoding='utf-8')
                    temporary.replace(self.config_path)
                except OSError:
                    raise APIError(503,'配置保存失败，原配置仍有效','CONFIG_SAVE_FAILED')
            self.config = candidate
            if self.config_path is not None:
                self.control_config_origin = 'FILE'
            self.config_version += 1
            self.clear_selection('CONFIG_UPDATED')
            self.new_epoch('CONFIG_UPDATED')
        else:
            intent = body.get('intent_sequence')
            session = self.sessions[source]
            if type(intent) is not int or intent<0:
                raise APIError(400,'intent_sequence必须是非负整数','INVALID_ARGUMENT')
            if intent<=session['retired_intent'] or intent<session['intent']:
                raise APIError(409,'此轮操纵已撤销，等待新的用户操作','RETIRED_INPUT')
            if path == '/api/voice':
                if source!='voice':
                    raise APIError(403,'仅语音来源支持此离散动作','CAPABILITY_MISMATCH')
                action = body.get('action')
                if not isinstance(action,str) or action not in CAPABILITIES['voice']['actions']:
                    raise APIError(400,'未知语音动作','INVALID_ARGUMENT')
                duration = number(body.get('duration_ms'),'duration_ms')
                if not 100<=duration<=5000:
                    raise APIError(400,'语音执行时长100–5000ms','INVALID_ARGUMENT')
                if item['received']+duration/1000<=time.monotonic():
                    raise APIError(409,'语音在队列中过期','EXPIRED_COMMAND')
                # 映射在识别适配层只应用一次，避免二次映射绕过危险动作确认。
                template = VOICE_MOTIONS[action]
                limits = [self.config['max_horizontal_speed_m_s']]*2+[self.config['max_vertical_speed_m_s'],self.config['max_yaw_rate_rad_s']]
                values = tuple(v/.5*limit for v,limit in zip(template,limits))
            else:
                if not CAPABILITIES[source]['continuous']:
                    raise APIError(403,'设备不支持连续输入','CAPABILITY_MISMATCH')
                if path == '/api/input':
                    raw = self.axes(body.get('axes'))
                    order = self.config['input_mapping'][source]
                    mapped = [raw[abs(index)-1]*(1 if index>0 else -1) for index in order]
                    dz = self.config['deadzone']
                    mapped = [0. if abs(v)<=dz else math.copysign((abs(v)-dz)/(1-dz),v)*self.config['sensitivity'] for v in mapped]
                    norm = max(1.,math.hypot(mapped[0],mapped[1]))
                    values = (mapped[0]/norm*self.config['max_horizontal_speed_m_s'],mapped[1]/norm*self.config['max_horizontal_speed_m_s'],
                        mapped[2]*self.config['max_vertical_speed_m_s'],mapped[3]*self.config['max_yaw_rate_rad_s'])
                else:
                    values = tuple(number(body.get(k),k) for k in ('vx_m_s','vy_m_s','vz_m_s','yaw_rate_rad_s'))
            for axis,value in zip(('vx','vy','vz','yaw'),values):
                if source!='voice' and value!=0 and axis not in CAPABILITIES[source]['axes']:
                    raise APIError(403,'设备不支持此运动轴','CAPABILITY_MISMATCH')
            motion = self.world_motion(body,values)
            # 完成参数/能力/来源/模式检查后，才向动力学发送；非法输入不能改变控制权。
            self.accept_owner(source)
            self.core.set_motion(*motion[:3],self.filtered_yaw,.5)
            # 心跳和等待确认不推进运动回执。语音确认关联原始语音报文的序号。
            self.last_applied = dict(control_epoch=self.control_epoch, source=source.upper(),
                intent_sequence=intent, command_sequence=body['command_sequence'])
            if self.active_command is not None:
                self.finish_command(self.active_command,'CANCELLED','REPLACED_BY_NEW_INPUT')
            self.active_command = key if path=='/api/voice' else None
            self.motion, self.owner = motion, source
            if intent>session['intent']:
                session['retired_intent'] = max(session['retired_intent'],session['intent'])
            session['intent'] = intent
            self.voice_end = item['received']+duration/1000 if path=='/api/voice' else None
            self.deadline = None if path=='/api/voice' else expires
            self.transition = 'INPUT_'+source
            if key is not None:
                self.records[key]['status'] = 'EXECUTING'

    def apply_external(self,item,seq,expires,key):
        """硬件/识别组件规范化后复用统一控制校验，禁止绕过CTL流程。"""
        body,source,path = item['body'],item['source'],item['path']
        policy = self.config['input_policy']
        if path=='/api/confirm':
            self.operator(source)
            if body.get('confirmed') is not True:
                raise APIError(400,'危险动作必须由用户明确确认','CONFIRMATION_REQUIRED')
            target_id = body.get('target_command_id')
            target_session = self.sessions.get('voice')
            target_key = (target_session['id'],target_id) if target_session and isinstance(target_id,str) else None
            pending = self.confirmations.get(target_key)
            if pending is None:
                raise APIError(409,'没有等待确认的有效语音动作','NO_PENDING_CONFIRMATION')
            if pending['epoch']!=self.control_epoch or self.selected_source!='voice':
                raise APIError(409,'确认上下文已变化','STALE_EPOCH')
            # 确认是新的明确用户意图，使用本次确认的墙钟时刻执行，仍保留原语音命令ID。
            translated = dict(pending['item'],received=item['received'],epoch=item['epoch'],_validated_input=True)
            self.apply(translated,seq,expires,target_key)
            self.confirmations.pop(target_key,None)
            self.records[target_key]['reason']='OPERATOR_CONFIRMED'
            self.refresh_record(target_key)
            return
        if (path=='/api/ring-event' and source!='ring') or (path in ('/api/voice','/api/voice-result') and source!='voice'):
            raise APIError(403,'接口与来源能力不匹配','CAPABILITY_MISMATCH')
        try:
            parsed = ring_event(body,policy) if source=='ring' else voice_result(body,policy)
        except ValueError as exc:
            raise APIError(400,str(exc),'INVALID_ARGUMENT')
        if source=='ring':
            event_seq = body['event_sequence']
            if event_seq<=self.sessions[source]['event_sequence']:
                raise APIError(409,'SDK事件重复或乱序','STALE_DEVICE_EVENT')
        if 'ignored' in parsed:
            # 无识别/低置信度/未知词/按键释放只回传结果，不创建运动、不续时。
            self.records[key]['status']='IGNORED'
            self.records[key]['reason']=parsed['ignored']
            if source=='ring':
                self.sessions[source]['event_sequence']=body['event_sequence']
            return
        translated_body = dict(body)
        translated_body.update(parsed)
        translated = dict(item,body=translated_body,_validated_input=True)
        if source=='ring':
            translated['path']=parsed['path']
            self.apply(translated,seq,expires,key)
            self.sessions[source]['event_sequence']=body['event_sequence']
            return
        action = self.config['voice_mapping'].get(parsed['action'],parsed['action'])
        translated_body['action']=action
        parsed['needs_confirmation']=action in policy['dangerous_actions']
        self.records[key]['semantic_action']=action
        if action not in SUPPORTED:
            # 起飞/降落/返航是任务语义，当前空中悬停模型不具备这些任务，不能假执行。
            raise APIError(501,'已识别'+action+'，但起降/返航任务模块尚未实现','TASK_NOT_SUPPORTED')
        translated['path']='/api/protect' if action=='protect' else '/api/stop' if action in ('stop','hover') else '/api/voice'
        if parsed['needs_confirmation']:
            self.accept_owner(source)
            # 一个新危险动作只等待一次确认，等待期间不会向动力学发送该运动。
            self.confirmations[key]=dict(item=translated,epoch=self.control_epoch,
                until=time.monotonic()+policy['confirmation_timeout_ms']/1000)
            self.records[key]['status']='WAITING_CONFIRMATION'
            self.records[key]['reason']='OPERATOR_CONFIRMATION_REQUIRED'
            return
        self.apply(translated,seq,expires,key)

    def tick(self):
        self.expire()
        if self.motion is not None:
            # 偏航参考角速度同样做斜率限制：0.5 rad/s²，跨来源保留连续性。
            delta = .5*self.core.dt
            difference = self.motion[3]-self.filtered_yaw
            self.filtered_yaw += max(-delta, min(delta,difference))
            self.core.set_motion(*self.motion[:3], self.filtered_yaw, .5)
        previous = self.core.get_state()['mode']
        raw = self.core.step()
        if previous != 'PROTECTED' and raw['mode'] == 'PROTECTED':
            self.clear_selection(raw['protection_reason'])
            self.new_epoch(raw['protection_reason'])
        self.publish(raw)

    def _wait_for_next_tick(self, next_tick):
        # Windows 上 Event.wait 的短超时会被系统计时粒度拉长。每次短睡后重新检查指令和停止信号。
        delay = min(.002, next_tick - time.monotonic())
        if delay > 0.:
            time.sleep(delay)

    def run(self):
        next_tick, last = time.monotonic(), time.monotonic()
        last_tick = None
        last_diagnostics = last
        try:
            while not self.stopping.is_set():
                now = time.monotonic()
                if now-last>.1 or now-next_tick>.08:
                    self.core.protect('SCHEDULER_STALL')
                    self.clear_selection('SCHEDULER_STALL')
                    self.new_epoch('SCHEDULER_STALL')
                    next_tick = now
                last = now
                self.expire()
                # 每轮命令耗时有界，优先保证动力学和TTL检查继续推进。
                batch_start = time.monotonic()
                for _ in range(16):
                    if time.monotonic()-batch_start>.004:
                        break
                    try:
                        _, _, item = self.commands.get_nowait()
                    except queue.Empty:
                        break
                    with item['claim_lock']:
                        if item['queue_state']=='CANCELLED':
                            item['done'].set()
                            continue
                        item['queue_state']='EXECUTING'
                    try:
                        extra = self.execute(item)
                        self.publish()
                        result = self.state()
                        result.update(extra)
                        result['ack'] = dict(status='APPLIED', source=item['source'],
                            server_received_at_ms=item['epoch'], confirmed_at_ms=time.time()*1000,
                            queue_and_apply_ms=(time.monotonic()-item['received'])*1000)
                        item['result'] = result
                    except APIError as exc:
                        if item['path'] in ('/api/control','/api/input','/api/voice','/api/ring-event','/api/voice-result') and self.owner==item['source'] and exc.code in ('INVALID_ARGUMENT','EXPIRED_COMMAND','CAPABILITY_MISMATCH'):
                            # 当前来源的输入失效立即保护；旧轮次/旧序号报文只拒绝，不干扰新操纵。
                            self.core.protect('INPUT_INVALID')
                            self.clear_selection('INPUT_INVALID')
                            self.new_epoch('INPUT_INVALID')
                        self.publish()
                        item['error'] = exc
                    except (ValueError, RuntimeError) as exc:
                        self.publish()
                        item['error'] = APIError(409, str(exc), 'CORE_REJECTED')
                    finally:
                        with item['claim_lock']: item['queue_state']='DONE'
                        item['done'].set()
                now = time.monotonic()
                if now >= next_tick:
                    if now-next_tick >= self.core.dt:
                        self.tick_overrun_count += 1
                    if last_tick is not None:
                        self.tick_intervals.append(abs(now-last_tick-self.core.dt)*1000)
                    last_tick = now
                    if self.tick_intervals and now-last_diagnostics >= 1.:
                        samples = sorted(self.tick_intervals)
                        self.tick_diagnostics = dict(tick_jitter_p99_ms=samples[math.ceil(.99*len(samples))-1],
                            tick_jitter_samples=len(samples))
                        last_diagnostics = now
                    self.tick()
                    next_tick += self.core.dt
                self._wait_for_next_tick(next_tick)
        except Exception as exc:
            with self.lock:
                self.failure = '仿真线程异常：'+str(exc)


def make_server(service, host, port, allowed_origins=()):
    class Handler(JsonHandler):
        protocol_version = 'HTTP/1.1'

        def setup(self):
            super().setup()
            # 控制包尽快发送，避免小包合并给确认链路增加延迟。
            self.connection.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)

        def log_message(self, *_):
            pass

        def stream(self):
            self.close_connection = True
            self.connection.settimeout(3)
            self.send_response(200)
            self.send_header('Content-Type','text/event-stream; charset=utf-8')
            self.send_header('Cache-Control','no-store')
            self.send_header('Connection','close')
            self.end_headers()
            last_sequence = None
            next_send = time.monotonic()
            try:
                while not service.stopping.is_set():
                    state = service.state()
                    if state['sequence'] != last_sequence:
                        record = 'event: state\nid: '+str(state['sequence'])+'\ndata: '+json.dumps(state,ensure_ascii=False,allow_nan=False)+'\n\n'
                        self.wfile.write(record.encode('utf-8'))
                        self.wfile.flush()
                        last_sequence = state['sequence']
                    next_send += .04
                    now = time.monotonic()
                    if next_send < now:
                        next_send = now+.04
                    service.stopping.wait(max(0.,next_send-now))
            except (OSError, APIError):
                pass

        def dispatch(self, post=False):
            try:
                self.validate_origin()
                source = service.authenticate(self.headers.get('Authorization',''))
                if not post:
                    if self.path == '/api/health':
                        health = service.health()
                        return self.reply(200 if health['healthy'] else 503,dict(ok=health['healthy'],data=health))
                    if self.path == '/api/events':
                        return self.stream()
                    parts = self.path.split('/')
                    if len(parts)==5 and parts[:3]==['','api','commands']:
                        result = service.command_state(parts[3],parts[4],source)
                        return self.reply(200,dict(ok=True,data=result))
                    if self.path != '/api/state':
                        raise APIError(404,'接口不存在','NOT_FOUND')
                    result = service.state()
                    result['authenticated_source'] = source
                else:
                    body = self.read_json(4096)
                    result = service.submit(self.path,body,source)
                self.reply(200,dict(ok=True,data=result))
            except (APIError,TransportError) as exc:
                if post: self.close_connection=True
                self.reply(exc.status,dict(ok=False,error=exc.message,error_code=exc.code))
            except OSError:
                self.close_connection=True

        def do_GET(self):
            self.dispatch()

        def do_POST(self):
            self.dispatch(True)

    Handler.allowed_origins=validate_origins(allowed_origins)
    return ThreadingHTTPServer((host,port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host',default='127.0.0.1')
    parser.add_argument('--port',type=int,default=8765)
    parser.add_argument('--allow-origin',action='append',default=[])
    parser.add_argument('--app-token',default=DEMO_TOKENS['app'])
    parser.add_argument('--ring-token',default=DEMO_TOKENS['ring'])
    parser.add_argument('--voice-token',default=DEMO_TOKENS['voice'])
    parser.add_argument('--wind',type=float,nargs=3,default=[0.,0.,0.])
    parser.add_argument('--compensation',action='store_true')
    parser.add_argument('--config',type=Path,default=Path(__file__).resolve().parent/'control_config.json')
    args = parser.parse_args()
    from simulation_core import SimulationCore
    core = SimulationCore()
    core.set_wind(args.wind)
    core.enable_disturbance_compensation(args.compensation)
    config = json.loads(args.config.read_text(encoding='utf-8')) if args.config.exists() else None
    service = SimulationService(core,dict(app=args.app_token,ring=args.ring_token,voice=args.voice_token),config,args.config)
    server = make_server(service,args.host,args.port,args.allow_origin)
    service.start()
    print(f'Multi-source simulation API: http://{args.host}:{server.server_port}',flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        service.close()
        server.server_close()


if __name__ == '__main__':
    main()
