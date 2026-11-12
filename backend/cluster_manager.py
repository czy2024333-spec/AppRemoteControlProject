"""集群注册、控制对象互斥、按成员分发与应答汇总。暂不包含自主协同算法。"""
import copy
import hashlib
import json
import math
import re
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from app_server import APIError, DEMO_TOKENS
from runtime_info import IMPLEMENTATION_VERSION, is_continuous, identifier
from cluster_member import ManagedMember, validate_member

MEMBER_PATHS = {'/api/session','/api/heartbeat','/api/switch','/api/adaptive-switch',
    '/api/neutral','/api/control','/api/input','/api/stop','/api/protect','/api/resume',
    '/api/cancel','/api/disconnect','/api/config','/api/ring-event','/api/voice-result',
    '/api/voice','/api/confirm'}
SAFETY_PATHS = {'/api/stop','/api/protect','/api/disconnect'}


class ClusterManager:
    def __init__(self, config, directory, tokens=None):
        from config_validation import validate_cluster_config
        config=validate_cluster_config(config)
        self.lock = threading.RLock()
        self.audit_lock = threading.Lock()
        self.cluster_id = identifier(config['cluster_id'])
        self.config = copy.deepcopy(config)
        self.limits = self.config['limits']
        if set(self.limits)!={'max_online','max_controlled','max_sensor_enabled','lost_timeout_s'}:
            raise ValueError('集群容量字段不完整或包含未知字段')
        for key in ('max_online','max_controlled','max_sensor_enabled'):
            if type(self.limits[key]) is not int or not 1<=self.limits[key]<=16:
                raise ValueError(key+'必须为1至16，实际容量需在目标硬件验证')
        timeout = self.limits['lost_timeout_s']
        if type(timeout) not in (int,float) or not math.isfinite(timeout) or not .2<=timeout<=5:
            raise ValueError('lost_timeout_s范围0.2至5秒')
        if self.limits['max_controlled']>self.limits['max_online'] or self.limits['max_sensor_enabled']>self.limits['max_online']:
            raise ValueError('受控和传感器数量不得超过在线容量')
        self.tokens = dict(tokens or DEMO_TOKENS)
        if set(self.tokens)!=set(DEMO_TOKENS) or len(set(self.tokens.values()))!=3 or any(
                not isinstance(t,str) or len(t)<16 or not t.isascii() or any(c.isspace() for c in t)
                for t in self.tokens.values()):
            raise ValueError('三个来源Token必须独立且至少16位无空白ASCII字符')
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.boot_id = uuid.uuid4().hex
        self.audit_path = self.directory/'cluster_events.jsonl'
        self.members, self.groups, self.used_ids = {}, {}, set()
        self.sessions, self.history = {}, {}
        self.selection = None
        self.revision = 0
        self.stop_event = threading.Event()
        self.pool = ThreadPoolExecutor(max_workers=self.limits['max_online'],thread_name_prefix='cluster-dispatch')
        try:
            for spec in config['members']:
                self._register(spec)
            for group, members in config['groups'].items():
                identifier(group)
                self._validate_member_list(members)
                self.groups[group] = list(members)
        except Exception:
            for entry in self.members.values(): entry['service'].close()
            self.pool.shutdown(wait=True)
            raise
        self.monitor = threading.Thread(target=self._monitor, daemon=True)
        self.monitor.start()

    def authenticate(self, header):
        # Token独立于注册表，成员增删不能导致鉴权读取竞争。
        import hmac
        for source, token in self.tokens.items():
            if hmac.compare_digest(header.encode(), ('Bearer '+token).encode()): return source
        raise APIError(401,'来源Token无效','UNAUTHORIZED')

    def close(self):
        self.stop_event.set()
        if hasattr(self,'monitor'): self.monitor.join(2)
        for entry in self.members.values(): entry['service'].close()
        self.pool.shutdown(wait=True)

    def _audit(self, event, **fields):
        row = dict(fields)
        row.update(event=event, cluster_id=self.cluster_id, boot_id=self.boot_id,
                   server_time_ms=time.time()*1000, revision=self.revision)
        with self.audit_lock, self.audit_path.open('a',encoding='utf-8') as log:
            log.write(json.dumps(row,ensure_ascii=False,allow_nan=False)+'\n')

    def _register(self, spec):
        spec, _ = validate_member(spec)
        name = spec['device_id']
        if name in self.used_ids:
            raise APIError(409,'编号本次运行中已使用，退出后不能复用','DEVICE_ID_REUSED')
        if len(self.members)>=self.limits['max_online']:
            raise APIError(409,'注册数量已达到配置容量','CAPACITY_LIMIT')
        enabled = sum(e['spec']['sensors']['enabled'] for e in self.members.values())
        if spec['sensors']['enabled'] and enabled>=self.limits['max_sensor_enabled']:
            raise APIError(409,'传感器启用数量已达到配置容量','SENSOR_CAPACITY_LIMIT')
        service = ManagedMember(spec,self.cluster_id,self.tokens,self.directory/name)
        service.start()
        self.members[name] = dict(service=service, spec=spec, online=True, loss_reason=None)
        self.used_ids.add(name)
        self.revision += 1
        self._audit('MEMBER_REGISTERED', device_id=name, member_boot_id=service.boot_id)

    def _validate_member_list(self, members):
        if not isinstance(members,list) or not members or any(not isinstance(x,str) for x in members) or len(set(members))!=len(members):
            raise APIError(400,'成员列表需非空且不能重复','INVALID_MEMBERS')
        if any(x not in self.members for x in members):
            raise APIError(404,'列表包含未注册成员','MEMBER_NOT_FOUND')

    def _barrier(self, names, reason, protect=False):
        result = {}
        def apply_one(name):
            service = self.members[name]['service']
            try:
                if service.failure or not service.thread.is_alive():
                    raise APIError(503,'成员运行线程不可用','MEMBER_LOST')
                service.barrier(reason,protect)
                return 'APPLIED'
            except APIError as exc:
                return exc.code
        jobs = {name:self.pool.submit(apply_one,name) for name in names if name in self.members}
        for name, job in jobs.items():
            result[name] = job.result()
            if result[name]!='APPLIED':
                self.members[name]['online'],self.members[name]['loss_reason'] = False,result[name]
        self._audit('CONTROL_BARRIER', reason=reason, members=result)
        return result

    def _invalidate(self, reason, extra=(), protect=False):
        old = self.selection
        # 先撤销管理器通行凭据，再逐个清除已下发的输入；所有新请求由同一锁串行校验。
        self.selection = None
        names = set(extra) | (set(old['member_ids']) if old else set())
        results = self._barrier(sorted(names),reason,protect) if names else {}
        self.revision += 1
        self._audit('SELECTION_INVALIDATED', reason=reason,
                    selection_id=old['selection_id'] if old else None, member_ids=sorted(names))
        return results

    def _reconcile(self):
        lost = []
        for name, entry in self.members.items():
            service = entry['service']
            if entry['online'] and (service.failure or not service.thread.is_alive() or
                    time.monotonic()-service.last_step_at>self.limits['lost_timeout_s']):
                entry['online'], entry['loss_reason'] = False, 'MEMBER_LOST'
                lost.append(name)
                self._audit('MEMBER_LOST',device_id=name)
        if lost:
            self._invalidate('MEMBER_LOST',lost,protect=True)

    def _monitor(self):
        while not self.stop_event.wait(.05):
            with self.lock: self._reconcile()

    def member_state(self, name):
        # APP安全动作会先GET最新epoch。成员快照也不能被普通分发持有的管理器锁阻塞。
        # ID禁止复用，取得服务引用后即使注销，也不会读到别的实例；服务自身锁保证整份快照。
        entry=self.members.get(name)
        if entry is None: raise APIError(404,'成员不存在','MEMBER_NOT_FOUND')
        service=entry['service']
        raw, stepped_at = service.published_snapshot()
        state=copy.deepcopy(raw)
        age=max(0.,(time.monotonic()-stepped_at)*1000)
        online=entry['online'] and not service.failure and service.thread.is_alive() and age<self.limits['lost_timeout_s']*1000
        state.update(online=bool(online),link_status='ONLINE' if online else 'LOST',
            loss_reason=entry['loss_reason'] or (None if online else 'MEMBER_LOST'),state_age_ms=age,
            data_quality='SIMULATION' if online else 'STALE')
        return state

    def state(self):
        with self.lock:
            self._reconcile()
            states = [self.member_state(n) for n in sorted(self.members)]
            return dict(api_version='0.6', implementation_version=IMPLEMENTATION_VERSION, cluster_id=self.cluster_id, boot_id=self.boot_id,
                revision=self.revision, selection=copy.deepcopy(self.selection), groups=copy.deepcopy(self.groups),
                members=states, registered_count=len(states), online_count=sum(s['online'] for s in states),
                selected_count=len(self.selection['member_ids']) if self.selection else 0,
                active_count=sum(s['active_source'] is not None and s['online'] for s in states),
                sensor_enabled_count=sum(e['spec']['sensors']['enabled'] for e in self.members.values()),
                limits=copy.deepcopy(self.limits), acceptance=copy.deepcopy(self.config['acceptance']),
                collaboration_capabilities={'task_split':False,'allocation':False,'formation':False,'cooperative_trajectory':False},
                server_time_ms=time.time()*1000)

    def new_session(self, source):
        with self.lock:
            if source in self.sessions:
                # 外设来源重建会话也必须撤销它尚在执行/待归中的输入，禁止旧连接继续操纵。
                owns = any(source in (e['service'].selected_source,e['service'].pending_source,e['service'].owner)
                           for e in self.members.values())
                if source=='app' or owns:
                    self._invalidate('CLUSTER_SESSION_REPLACED',protect=True)
            session = dict(id=uuid.uuid4().hex, sequence=-1)
            self.sessions[source] = session
            self.history = {k:v for k,v in self.history.items() if k[0]!=source}
            self._audit('CLUSTER_SESSION_CREATED', source=source)
            return dict(cluster_session_id=session['id'], cluster_id=self.cluster_id, revision=self.revision)

    def _envelope(self, source, path, body):
        if not isinstance(body,dict): raise APIError(400,'请求必须为JSON对象','INVALID_ARGUMENT')
        if body.get('cluster_id')!=self.cluster_id:
            raise APIError(400,'集群标识不匹配','WRONG_CLUSTER')
        session = self.sessions.get(source)
        if not session or body.get('cluster_session_id')!=session['id']:
            raise APIError(409,'集群会话失效','STALE_CLUSTER_SESSION')
        request_id = identifier(body.get('request_id'))
        key = (source,request_id)
        fingerprint = hashlib.sha256(json.dumps(dict(path=path,body={k:v for k,v in body.items()
            if k not in ('sent_at_ms','ttl_ms')}),sort_keys=True,allow_nan=False).encode()).hexdigest()
        if key in self.history:
            old = self.history[key]
            if old['fingerprint']!=fingerprint:
                raise APIError(409,'request_id不能复用于不同内容','REQUEST_ID_REUSED')
            return key,fingerprint,copy.deepcopy(old['result'])
        if len(self.history)>=2048 and not self._continuous(path,body):
            raise APIError(503,'集群请求历史已满，请停止后重建集群会话','HISTORY_FULL')
        seq = body.get('sequence')
        if type(seq) is not int or seq<=session['sequence']:
            raise APIError(409,'集群序号重复或倒退','STALE_CLUSTER_SEQUENCE')
        ttl, sent = body.get('ttl_ms'),body.get('sent_at_ms')
        if any(type(v) not in (float,int) or not math.isfinite(v) for v in (ttl,sent)) or not 0<ttl<=500:
            raise APIError(400,'时间戳/有效期无效','INVALID_ARGUMENT')
        age = time.time()*1000-sent
        if age < -50: raise APIError(400,'客户端时钟超前','CLOCK_SKEW')
        if age >= ttl: raise APIError(409,'集群请求已过期','EXPIRED_COMMAND')
        session['sequence'] = seq
        return key,fingerprint,None

    @staticmethod
    def _continuous(path,body):
        rows = body.get('commands')
        return path=='/api/cluster/dispatch' and isinstance(rows,list) and bool(rows) and all(
            isinstance(r,dict) and is_continuous(r.get('path'),r.get('body')) for r in rows)

    def request(self, source, path, body):
        with self.lock:
            try:
                self._reconcile()
                key, fingerprint, replay = self._envelope(source,path,body)
                if replay is not None:
                    replay['idempotent_replay'] = True
                    return replay
                if path!='/api/cluster/dispatch' and source!='app':
                    raise APIError(403,'集群管理与控制对象选择仅允许APP','OPERATOR_REQUIRED')
                result = self._apply(source,path,body)
                if not self._continuous(path,body):
                    self.history[key] = dict(fingerprint=fingerprint,result=copy.deepcopy(result))
                self._audit('CLUSTER_REQUEST',request_id=body['request_id'],source=source,path=path,
                    selection_id=body.get('selection_id'),result_status=result.get('status','APPLIED'))
                return result
            except (APIError,ValueError,TypeError,KeyError) as exc:
                request_id=body.get('request_id') if isinstance(body,dict) else None
                self._audit('CLUSTER_REJECTED',request_id=request_id if isinstance(request_id,str) else None,
                    source=source,path=path,error_code=getattr(exc,'code','INVALID_ARGUMENT'))
                raise

    def command_state(self,name,source,command_id,requester):
        # 成员查询与注销共享注册表锁，避免检查存在后被并发删除。
        with self.lock:
            if name not in self.members: raise APIError(404,'成员不存在','MEMBER_NOT_FOUND')
            return self.members[name]['service'].command_state(source,command_id,requester)

    def health(self):
        with self.lock:
            self._reconcile()
            members={n:dict(worker_alive=e['service'].thread.is_alive(),online=e['online'],
                state_age_ms=(time.monotonic()-e['service'].last_step_at)*1000,
                failure=e['service'].failure) for n,e in self.members.items()}
            healthy=bool(members) and self.monitor.is_alive() and all(
                m['worker_alive'] and m['online'] and m['failure'] is None for m in members.values())
            return dict(healthy=healthy,mode='cluster',implementation_version=IMPLEMENTATION_VERSION,
                cluster_id=self.cluster_id,boot_id=self.boot_id,monitor_alive=self.monitor.is_alive(),members=members)

    def _apply(self, source, path, body):
        if path=='/api/cluster/register':
            # 先做所有无副作用校验，注册成功后使旧成员快照失效。
            self._register(body.get('member'))
            self._invalidate('MEMBER_REGISTERED')
            return self.state()
        if path=='/api/cluster/groups':
            group = identifier(body.get('group_id'))
            members = body.get('member_ids')
            self._validate_member_list(members)
            self._invalidate('GROUP_UPDATED')
            self.groups[group] = list(members)
            self._audit('GROUP_UPDATED',group_id=group,member_ids=members)
            return self.state()
        if path in ('/api/cluster/join','/api/cluster/leave'):
            group, name = body.get('group_id'), body.get('device_id')
            if not isinstance(group,str) or group not in self.groups or not isinstance(name,str) or name not in self.members:
                raise APIError(404,'分组或成员不存在','MEMBER_NOT_FOUND')
            self._invalidate('GROUP_MEMBERSHIP_CHANGED')
            if path.endswith('/join') and name not in self.groups[group]: self.groups[group].append(name)
            if path.endswith('/leave') and name in self.groups[group]: self.groups[group].remove(name)
            return self.state()
        if path in ('/api/cluster/unregister','/api/cluster/member-link'):
            name = body.get('device_id')
            if not isinstance(name,str) or name not in self.members:
                raise APIError(404,'成员不存在','MEMBER_NOT_FOUND')
            entry = self.members[name]
            if path.endswith('/member-link'):
                online = body.get('online')
                if type(online) is not bool: raise APIError(400,'online需为bool','INVALID_ARGUMENT')
                service = entry['service']
                if online and (service.failure or not service.thread.is_alive() or time.monotonic()-service.last_step_at>self.limits['lost_timeout_s']):
                    raise APIError(409,'成员尚未恢复有效状态','MEMBER_LOST')
                self._invalidate('MEMBER_RECONNECTED' if online else 'MEMBER_DISCONNECTED',[name],protect=True)
                entry['online'],entry['loss_reason'] = online, None if online else 'MEMBER_DISCONNECTED'
            else:
                self._invalidate('MEMBER_LEFT',[name],protect=True)
                entry['service'].close()
                del self.members[name]
                for members in self.groups.values():
                    if name in members: members.remove(name)
                self._audit('MEMBER_UNREGISTERED',device_id=name)
            return self.state()
        if path=='/api/cluster/select':
            if body.get('user_initiated') is not True:
                raise APIError(400,'选择控制对象必须由用户发起','USER_ACTION_REQUIRED')
            kind = body.get('target_kind')
            if kind=='single': members = [body.get('device_id')]
            elif kind=='group':
                group = body.get('group_id')
                if not isinstance(group,str) or group not in self.groups: raise APIError(404,'分组不存在','GROUP_NOT_FOUND')
                members = list(self.groups[group])
            elif kind=='cluster': members = sorted(self.members)
            else: raise APIError(400,'target_kind为single/group/cluster','INVALID_ARGUMENT')
            self._validate_member_list(members)
            if len(members)>self.limits['max_controlled']: raise APIError(409,'受控数量超过配置容量','CAPACITY_LIMIT')
            if any(not self.members[n]['online'] for n in members): raise APIError(409,'目标包含失联成员','MEMBER_LOST')
            if self.selection and body.get('takeover') is not True:
                raise APIError(409,'已有控制对象；用户明确接管需takeover=true','CONTROL_CONFLICT')
            barriers=self._invalidate('CONTROL_TARGET_CHANGED',members)
            if any(v!='APPLIED' for v in barriers.values()) or any(not self.members[n]['online'] for n in members): raise APIError(409,'清除旧输入未确认，禁止启用新对象','BARRIER_FAILED')
            self.selection = dict(selection_id=uuid.uuid4().hex,target_kind=kind,
                group_id=body.get('group_id') if kind=='group' else None,member_ids=sorted(members),revision=self.revision)
            self._audit('TARGET_SELECTED',**self.selection)
            return copy.deepcopy(self.selection)
        if path=='/api/cluster/dispatch': return self._dispatch(source,body)
        if path in ('/api/cluster/task-split','/api/cluster/allocation','/api/cluster/formation','/api/cluster/cooperative-trajectory'):
            identifier(body.get('task_id'))
            self._validate_member_list(body.get('member_ids'))
            if body.get('contract_version')!='0.1' or not isinstance(body.get('parameters'),dict):
                raise APIError(400,'协同接口需要contract_version=0.1及parameters对象','INVALID_ARGUMENT')
            raise APIError(501,'已预留接口；本期未实现自主拆分、分配、编队或协同航迹算法','COLLABORATION_NOT_IMPLEMENTED')
        raise APIError(404,'集群接口不存在','NOT_FOUND')

    def _dispatch(self, source, body):
        selection = self.selection
        if not selection or body.get('selection_id')!=selection['selection_id']:
            raise APIError(409,'控制对象已变更，旧指令不能重放','STALE_SELECTION')
        rows = body.get('commands')
        if not isinstance(rows,list) or not rows or len(rows)>self.limits['max_controlled']:
            raise APIError(400,'commands为空或超过受控容量','INVALID_ARGUMENT')
        names = [r.get('device_id') if isinstance(r,dict) else None for r in rows]
        if any(not isinstance(n,str) for n in names) or len(set(names))!=len(names) or set(names)!=set(selection['member_ids']):
            raise APIError(400,'必须逐个明确当前选中成员，不得遗漏、重复或额外广播','TARGET_MISMATCH')
        for row in rows:
            if row.get('path') not in MEMBER_PATHS or not isinstance(row.get('body'),dict):
                raise APIError(400,'成员路径或请求体无效','INVALID_ARGUMENT')
            if row['path']!='/api/session' and row['body'].get('device_id')!=row['device_id']:
                raise APIError(400,'成员内外设备编号不一致','WRONG_DEVICE')
        results = []
        def dispatch_one(row):
            name,path,member_body = row['device_id'],row['path'],row['body']
            entry = self.members[name]
            try:
                # 逐成员重验原始报文TTL，不能因分发排队而重写时间戳延长指令。
                if time.time()*1000-body['sent_at_ms']>=body['ttl_ms']:
                    raise APIError(409,'分发前请求已过期','EXPIRED_COMMAND')
                if not entry['online'] or entry['service'].failure or not entry['service'].thread.is_alive():
                    raise APIError(409,'成员失联','MEMBER_LOST')
                data = entry['service'].submit(path,copy.deepcopy(member_body),source)
                return dict(device_id=name,ok=True,data=data)
            except APIError as exc:
                return dict(device_id=name,ok=False,error_code=exc.code,error=exc.message)
        # 各实例线程独立校验和执行；并行等待避免三机应答延迟串行累加。
        jobs = [self.pool.submit(dispatch_one,row) for row in rows]
        for row, job in zip(rows,jobs):
            results.append(job.result())
            name,path,member_body = row['device_id'],row['path'],row['body']
            self._audit('MEMBER_ACK',request_id=body['request_id'],selection_id=selection['selection_id'],
                member_boot_id=self.members[name]['service'].boot_id,
                device_id=name,source=source,path=path,member_command_id=member_body.get('command_id'),
                member_sequence=member_body.get('command_sequence'),ok=results[-1]['ok'],
                error_code=results[-1].get('error_code'))
        succeeded = sum(r['ok'] for r in results)
        return dict(cluster_id=self.cluster_id,request_id=body['request_id'],selection_id=selection['selection_id'],
                    status='APPLIED' if succeeded==len(rows) else 'REJECTED' if succeeded==0 else 'PARTIAL',
                    succeeded=succeeded,failed=len(rows)-succeeded,results=results,
                    note='逐成员非原子执行；重试同request_id只返回原应答，不重复运动')

    def single_request(self, source, name, path, body):
        if path in SAFETY_PATHS:
            return self._single_safety_request(source,name,path,body)
        with self.lock:
            self._reconcile()
            if name not in self.members: raise APIError(404,'成员不存在','MEMBER_NOT_FOUND')
            if path not in MEMBER_PATHS: raise APIError(404,'成员接口不存在','NOT_FOUND')
            if not isinstance(body,dict) or body.get('cluster_id')!=self.cluster_id:
                raise APIError(400,'单机请求也必须携带集群标识','WRONG_CLUSTER')
            selection = self.selection
            # 停止/保护可跨控制对象，但仍通过该成员的会话、来源、序号与TTL检查。
            if path not in SAFETY_PATHS and (not selection or selection['target_kind']!='single'
                    or selection['member_ids']!=[name] or body.get('selection_id')!=selection['selection_id']):
                raise APIError(409,'单机与集群控制互斥，请用户先选择单机对象','CONTROL_CONFLICT')
            if not self.members[name]['online']: raise APIError(409,'成员失联','MEMBER_LOST')
            data = self.members[name]['service'].submit(path,copy.deepcopy(body),source)
            self._audit('SINGLE_ACK',device_id=name,source=source,path=path,
                member_boot_id=self.members[name]['service'].boot_id,
                selection_id=body.get('selection_id'),member_command_id=body.get('command_id'),
                member_sequence=body.get('command_sequence'))
            return data

    def _single_safety_request(self, source, name, path, body):
        # 安全动作本来就允许跨控制对象；不要等待持有管理器锁的普通/批量慢请求。
        # 成员ID本次运行禁止复用，get取得的服务不会被重绑到另一架飞机；注销竞争时只会拒绝。
        if not isinstance(body,dict) or body.get('cluster_id')!=self.cluster_id:
            raise APIError(400,'单机请求也必须携带集群标识','WRONG_CLUSTER')
        entry=self.members.get(name)
        if entry is None: raise APIError(404,'成员不存在','MEMBER_NOT_FOUND')
        if not entry['online']: raise APIError(409,'成员失联','MEMBER_LOST')
        # 来源、设备、会话、序号、TTL及保护优先仍由原成员执行器最终校验。
        data=entry['service'].submit(path,copy.deepcopy(body),source)
        self._audit('SINGLE_SAFETY_ACK',device_id=name,source=source,path=path,
            member_boot_id=entry['service'].boot_id,member_command_id=body.get('command_id'))
        return data
