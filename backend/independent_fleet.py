"""三端各控一机、共享全机位置；没有全局单选控制对象或广播运动入口。"""
import copy
import hmac
import logging
from pathlib import Path
import time
import threading
import uuid
from app_server import APIError, DEMO_TOKENS, safety_action_path
from async_audit import AsyncAudit
from cluster_manager import MEMBER_PATHS
from cluster_member import ManagedMember
from config_validation import validate_cluster_config
from control_config import INPUT_TTL_MAX_MS
from logs_query import LogIndex
from runtime_info import (FOCUS_PEER_TARGET_HZ, IMPLEMENTATION_VERSION, INDEPENDENT_AUDIT_FILENAME,
    MAX_CLIENT_CLOCK_AHEAD_MS, SSE_IDLE_HEARTBEAT_MS, SSE_REPLAY_FRAMES, STATE_PUBLISHER_TIMEOUT_MS,
    STATE_STREAM_TARGET_HZ, identifier, is_continuous)
from state_replay import REPLAY_SCHEMA, StateReplay
from unified_contract import device_descriptor, device_state, snapshot

def validate_credentials(value, device_ids):
    if not isinstance(value, dict) or set(value) != {'credentials'} or not isinstance(value['credentials'], list):
        raise ValueError('凭据配置必须只含 credentials 列表')
    tokens, names, bindings = set(), set(), set()
    for row in value['credentials']:
        if not isinstance(row, dict) or set(row) != {'client_id','token','role','device_id','source'}:
            raise ValueError('凭据需要 client_id/token/role/device_id/source，禁止未知字段')
        identifier(row['client_id'])
        token = row['token']
        if not isinstance(token, str) or len(token)<16 or not token.isascii() or any(c.isspace() for c in token):
            raise ValueError('Token至少16位无空白ASCII字符')
        if row['client_id'] in names or token in tokens:
            raise ValueError('client_id 与 Token 不得重复')
        names.add(row['client_id']); tokens.add(token)
        if row['role'] == 'OBSERVER':
            if row['device_id'] is not None or row['source'] is not None:
                raise ValueError('只读凭据不能绑定控制设备或来源')
        elif row['role'] == 'CONTROLLER':
            if row['device_id'] not in device_ids or row['source'] not in DEMO_TOKENS:
                raise ValueError('操纵凭据的设备或来源无效')
            pair = (row['device_id'], row['source'])
            if pair in bindings:
                raise ValueError('每架飞机每种来源只配置一份操纵凭据')
            bindings.add(pair)
        else:
            raise ValueError('role 只允许 CONTROLLER 或 OBSERVER')
    if any((name, 'app') not in bindings for name in device_ids):
        raise ValueError('每架飞机必须有独立 APP 操纵凭据')
    if not any(row['role']=='OBSERVER' for row in value['credentials']):
        raise ValueError('至少配置一个只读凭据')
    return copy.deepcopy(value['credentials'])


class IndependentMember(ManagedMember):
    _audit_fault_applied = False

    def expire(self):
        # 审计故障不能继续不可追溯的运动；普通断连只影响本机。
        if self.audit_sink is not None and self.audit_sink.failure is not None and not self._audit_fault_applied:
            # 已锁存边界等原因时核心会保留原保护原因，同一次审计故障只处理一次。
            self._audit_fault_applied = True
            self.core.protect('AUDIT_UNAVAILABLE')
            self.clear_selection('AUDIT_UNAVAILABLE')
            self.new_epoch('AUDIT_UNAVAILABLE')
        super().expire()


class IndependentFleet:
    def __init__(self, config, credentials, directory):
        self.config = validate_cluster_config(config)
        if self.config['limits']['max_controlled'] < len(self.config['members']):
            raise ValueError('独立操控模式的受控容量不得小于启动成员数')
        self.cluster_id = config['cluster_id']
        self.member_stale_after_ms = self.config['limits']['lost_timeout_s']*1000
        self.identities = validate_credentials(credentials, {s['device_id'] for s in config['members']})
        self.boot_id = uuid.uuid4().hex
        self.members = {}
        self.state_changed = threading.Event()
        # 审计日志落盘目录；命名避开本类的 directory() 设备目录方法。
        self.results_directory = Path(directory).resolve()
        self.audit = AsyncAudit(self.results_directory/INDEPENDENT_AUDIT_FILENAME)
        self.logs_index = LogIndex(self.results_directory,sorted(s['device_id'] for s in self.config['members']))
        self.replay = None
        try:
            self.replay = StateReplay(self.results_directory,
                [s['device_id'] for s in self.config['members']],self.cluster_id,self.boot_id)
            for spec in self.config['members']:
                name = spec['device_id']
                service = IndependentMember(spec, self.cluster_id, DEMO_TOKENS, self.results_directory/name,
                    self.audit,gnss_config=self.config.get('gnss'))
                self.members[name] = service
                service.state_changed = self.state_changed
                service.start()
                self.audit.emit(dict(event='MEMBER_STARTED',device_id=name,boot_id=service.boot_id,
                    server_time_ms=time.time()*1000, control_config_origin=service.control_config_origin,
                    sim_rate_hz=1/service.core.dt))
        except Exception:
            self.close()
            raise

    def close(self):
        for service in self.members.values():
            service.close()
        self.logs_index.close()
        replay_closed = self.replay.close() if self.replay is not None else True
        audit_closed = self.audit.close()
        return replay_closed and audit_closed

    def authenticate(self, header):
        for identity in self.identities:
            if hmac.compare_digest(header.encode('utf-8'), ('Bearer '+identity['token']).encode('ascii')):
                return {k:v for k,v in identity.items() if k!='token'}
        raise APIError(401,'访问凭据无效','UNAUTHORIZED')

    def authorize_write(self, identity, name):
        # 服务器根据 Token 确定身份，不能由 APP 自报角色或改设备号越权。
        if identity['role'] != 'CONTROLLER':
            raise APIError(403,'此凭据仅可观察，禁止提交控制请求','READ_ONLY')
        if identity['device_id'] != name:
            raise APIError(403,'此凭据只能操纵绑定的飞机','DEVICE_FORBIDDEN')

    def _service(self, name):
        if name not in self.members:
            raise APIError(404,'飞机不存在','MEMBER_NOT_FOUND')
        return self.members[name]

    def member_state(self, name, *, copy_state=True):
        service = self._service(name)
        state, stepped_at = service.published_snapshot()
        raw = copy.deepcopy(state) if copy_state else dict(state)
        age = max(0., (time.monotonic()-stepped_at)*1000)
        online = not service.failure and service.thread.is_alive() and age<self.member_stale_after_ms
        raw.update(online=online,state_age_ms=age,link_status='ONLINE' if online else 'LOST',
            loss_reason=None if online else 'MEMBER_LOST',data_quality='SIMULATION' if online else 'STALE')
        return raw

    def view(self, identity, name):
        data = device_state(self.member_state(name, copy_state=False))
        data['is_self'] = identity['device_id'] == name
        # write_permitted 是设备权限，不表示当前已完成会话、切换或归中。
        data['control']['write_permitted'] = identity['role']=='CONTROLLER' and identity['device_id']==name
        return data

    def state(self, identity):
        return self.state_for_identity(self.fleet_snapshot(), identity)

    def record_replay(self, states, captured_at):
        # 复用共享发布帧抽稀录制，每帧只采一次，与观察端数量无关。
        try:
            return self.replay.capture(states,captured_at)
        except Exception:
            # 回放异常对外可见，实时状态发布仍继续推进。
            self.replay.failure = 'REPLAY_CAPTURE_FAILED'
            logging.exception('回放采样异常，已停录')
            return False

    def fleet_snapshot(self, states=None):
        # 成员快照独立读取；不等待其他飞机的命令或日志，也不触发控制会话。
        if states is None:
            states = [self.member_state(name, copy_state=False) for name in sorted(self.members)]
        raw = dict(cluster_id=self.cluster_id,boot_id=self.boot_id,revision=1,selection=None,
            groups=copy.deepcopy(self.config['groups']),members=states,server_time_ms=time.time()*1000,
            registered_count=len(states),online_count=sum(s['online'] for s in states),
            selected_count=sum(s['selected_source'] is not None for s in states),
            active_count=sum(s['online'] and s['active_source'] is not None for s in states),
            sensor_enabled_count=sum(s['sensors']['enabled'] for s in self.config['members']))
        data = snapshot(raw)
        audit = self.audit.status()
        data.update(audit=audit, link=dict(audit='OK' if audit['healthy'] else 'UNAVAILABLE'))
        return data

    def ue_snapshot(self, raw_members):
        # UE 使用平铺的米制三维状态，由其坐标转换器统一转为厘米。
        members = [dict(raw_members[name]) for name in sorted(raw_members)]
        return dict(cluster_id=self.cluster_id,boot_id=self.boot_id,
            server_time_ms=time.time()*1000,selection=None,
            registered_count=len(members),online_count=sum(m['online'] for m in members),
            members=members)

    def state_for_identity(self, snapshot, identity):
        data = dict(snapshot)
        data['members'] = []
        for member in snapshot['members']:
            is_self = member['device_id']==identity['device_id']
            permitted = identity['role']=='CONTROLLER' and is_self
            data['members'].append(dict(member, is_self=is_self,
                control=dict(member['control'], write_permitted=permitted)))
        data.update(deployment_mode='INDEPENDENT',self_device_id=identity['device_id'],viewer_role=identity['role'])
        return data

    def directory(self, identity):
        devices = []
        for name in sorted(self.members):
            row = device_descriptor(self.member_state(name, copy_state=False))
            row.update(is_self=identity['device_id']==name,
                write_permitted=identity['role']=='CONTROLLER' and identity['device_id']==name)
            row['routes']['control_prefix'] = '/api/v1/devices/'+name
            devices.append(row)
        return dict(contract_version='1.0',cluster_id=self.cluster_id,target_revision=1,
            self_device_id=identity['device_id'],devices=devices)

    def info(self, identity):
        return dict(contract_version='1.0',implementation_version=IMPLEMENTATION_VERSION,deployment_mode='INDEPENDENT',
            cluster_id=self.cluster_id,authentication=dict(mode='DEVELOPMENT_DEVICE_BOUND_TOKEN',**identity),
            protocols=dict(unified_read='1.0',device_control='0.5',transport=['HTTP','SSE']),
            self_device_id=identity['device_id'],replay_schema=REPLAY_SCHEMA,
            writable_device_ids=[identity['device_id']] if identity['role']=='CONTROLLER' else [],
            capabilities=dict(independent_control=True,cluster_control=False,unified_intent_write=False,
                device_bound_legacy_write=True,directory=True,unified_state=True,unified_receipts=True,
                ue_video=False,ue_state_stream=True,formal_user_roles=False,logs_query=True,logs_export=True,
                state_replay=True,replay_export=True,
                hud_state_metadata=True,sse_comment_heartbeat=True,sse_resume=True,sse_focus=True,
                state_publisher_health=True),
            control_policy=dict(default_frame='WORLD',axes_order=['X','Y','Z','YAW'],
                input_ttl_max_ms=INPUT_TTL_MAX_MS,state_stream_target_hz=STATE_STREAM_TARGET_HZ,observe_grants_control=False,
                source_switch='USER_SWITCH_NEUTRAL',one_session_per_device_source=True,
                session_replacement='USER_ACTION_STOPS_ONLY_THIS_DEVICE'),
            # 布尔能力表与数值参数分别返回，现有 APP 仍可按旧模型解码。
            link_policy=dict(state_stream_target_hz=STATE_STREAM_TARGET_HZ,
                member_state_stale_after_ms=self.member_stale_after_ms,
                sse_idle_heartbeat_ms=SSE_IDLE_HEARTBEAT_MS, sse_replay_frames=SSE_REPLAY_FRAMES,
                focus_peer_target_hz=FOCUS_PEER_TARGET_HZ, state_publisher_timeout_ms=STATE_PUBLISHER_TIMEOUT_MS,
                recommended_sse_read_timeout_ms=3*SSE_IDLE_HEARTBEAT_MS,
                recommended_sse_first_frame_timeout_ms=3*SSE_IDLE_HEARTBEAT_MS,
                input_ttl_max_ms=INPUT_TTL_MAX_MS, max_client_clock_ahead_ms=MAX_CLIENT_CLOCK_AHEAD_MS),
            routes=dict(devices='/api/v1/devices',state='/api/v1/state',events='/api/v1/events',
                logs='/api/v1/logs',logs_export='/api/v1/logs/export',
                replay_frames='/api/v1/replay/frames',replay_export='/api/v1/replay/export',
                ue_events='/api/v1/ue/events',
                control_prefix='/api/v1/devices/{device_id}'))

    def request(self, identity, name, path, body):
        self.authorize_write(identity, name)
        if path not in MEMBER_PATHS:
            raise APIError(404,'控制接口不存在','NOT_FOUND')
        if body.get('device_id') != name:
            raise APIError(400,'报文设备号必须与路由一致','WRONG_DEVICE')
        if body.get('cluster_id') != self.cluster_id:
            raise APIError(400,'集群标识不匹配','WRONG_CLUSTER')
        service = self._service(name)
        state = self.member_state(name, copy_state=False)
        if not state['online']:
            raise APIError(503,'该飞机失联，拒绝指令','MEMBER_LOST')
        if path == '/api/session' and body.get('user_initiated') is not True:
            raise APIError(400,'申请或替换会话须由用户主动操作','USER_ACTION_REQUIRED')
        source = identity['source']
        if self.audit.failure and not safety_action_path(path,body,source,state['control_config']['input_policy']):
            raise APIError(503,'审计不可用，普通操纵已停用','AUDIT_UNAVAILABLE')
        # 无全局选中状态、无全局写锁；成员原执行器负责 TTL、epoch、序号、来源和能力校验。
        result = service.submit(path,copy.deepcopy(body),source)
        event = dict(event='COMMAND_ACK',device_id=name,boot_id=service.boot_id,
            client_id=identity['client_id'],source=source,path=path,
            sequence=body.get('command_sequence'),command_id=body.get('command_id'),server_time_ms=time.time()*1000)
        if path!='/api/heartbeat' and is_continuous(path,body):
            # 只记录已通过校验的运动输入字段，供联调核对方向和幅度。轴值保留映射前的数值。
            event.update(control_epoch=body['control_epoch'],intent_sequence=body['intent_sequence'],
                frame=body.get('frame','heading'),ttl_ms=body['ttl_ms'],sent_at_ms=body['sent_at_ms'])
            if path=='/api/control':
                event.update(vx_m_s=body['vx_m_s'],vy_m_s=body['vy_m_s'],vz_m_s=body['vz_m_s'],
                    yaw_rate_rad_s=body['yaw_rate_rad_s'])
            else:
                event['axes'] = body['axes'].copy()
        logged = self.audit.emit(event)
        if not logged and not safety_action_path(path,body,source,result['control_config']['input_policy']):
            raise APIError(503,'成员已处理请求，但审计异常；查询状态，禁止重发运动','ACK_OUTCOME_UNKNOWN')
        return result

    def command_state(self, identity, name, source, command_id):
        self.authorize_write(identity, name)
        return self._service(name).command_state(source,command_id,identity['source'])

    def logs(self, identity, params):
        # 日志是全机审计证据，观察端同样可读，与 state/events 的共享观察口径一致。
        result = self.logs_index.query(params,set(self.members))
        result.update(viewer=identity['client_id'],viewer_role=identity['role'])
        return result

    def logs_export(self, identity, params):
        return self.logs_index.export(params,set(self.members))

    def replay_frames(self, identity, params):
        result = self.replay.query(params,set(self.members))
        result.update(viewer=identity['client_id'],viewer_role=identity['role'])
        return result

    def replay_export(self, identity, params):
        return self.replay.export(params,set(self.members))

    def health(self):
        members = [service.health() for service in self.members.values()]
        audit = self.audit.status()
        return dict(mode='independent',implementation_version=IMPLEMENTATION_VERSION,
            boot_id=self.boot_id,healthy=all(m['healthy'] for m in members) and audit['healthy'],members=members,audit=audit,
            logs_index=self.logs_index.status(),replay=self.replay.status())
