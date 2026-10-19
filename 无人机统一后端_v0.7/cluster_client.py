"""APP集群接入参考：保留每架独立会话、轮次和序号，不伪造硬件中立采样。"""
import json
import time
import uuid
import http.client
from urllib.parse import urlsplit
from app_server import DEMO_TOKENS
from client import ClientError
from runtime_info import is_continuous


class ClusterClient:
    def __init__(self, url, source='app', token=None):
        parsed = urlsplit(url)
        if parsed.scheme!='http' or not parsed.hostname: raise ValueError('需要http地址')
        self.connection = http.client.HTTPConnection(parsed.hostname,parsed.port or 80,timeout=3)
        self.token = token or DEMO_TOKENS[source]
        self.source = source
        self.session = None
        self.cluster_id = None
        self.sequence = -1
        self.selection = None
        self.members = {}
        self.last_request = time.monotonic()

    def request(self,path,body=None):
        if time.monotonic()-self.last_request>2: self.connection.close()
        self.last_request = time.monotonic()
        self.connection.request('GET' if body is None else 'POST',path,
            None if body is None else json.dumps(body,allow_nan=False).encode(),
            {'Authorization':'Bearer '+self.token,'Content-Type':'application/json'})
        response = self.connection.getresponse()
        value = json.loads(response.read())
        if response.status!=200: raise ClientError(response.status,value)
        return value['data']

    def open(self):
        result = self.request('/api/cluster/session',{})
        self.session,self.cluster_id = result['cluster_session_id'],result['cluster_id']
        self.sequence = -1
        return result

    def envelope(self,**fields):
        self.sequence += 1
        return dict(cluster_id=self.cluster_id,cluster_session_id=self.session,
            request_id=uuid.uuid4().hex,sequence=self.sequence,sent_at_ms=time.time()*1000,ttl_ms=500,**fields)

    def manage(self,path,**fields): return self.request(path,self.envelope(**fields))

    def select(self,kind='cluster',**fields):
        self.selection = self.manage('/api/cluster/select',target_kind=kind,user_initiated=True,**fields)
        return self.selection

    def bind_selection(self,selection):
        # 指环/语音读取APP选择结果，不自行抢占控制对象。
        self.selection = selection

    def state(self): return self.request('/api/cluster/state')

    def member_body(self,name,path,fields):
        if path=='/api/session': return {}
        entry = self.members[name]
        entry['sequence'] += 1
        body = dict(device_id=name,session_id=entry['session_id'],control_epoch=entry['control_epoch'],
            command_sequence=entry['sequence'],sent_at_ms=time.time()*1000,
            ttl_ms=min(500,entry.get('ttl_ms',500)))
        if not is_continuous(path,fields):
            body['command_id'] = uuid.uuid4().hex
        body.update(fields)
        return body

    def batch(self,path,per_member=None,**fields):
        if path in ('/api/resume','/api/stop','/api/protect') or (path=='/api/neutral' and self.source!='app'):
            # 明确的新握手/安全操作读取当前轮次；绝不用于自动重试连续运动。
            # APP切换来源后，指环或语音自己的客户端也必须更新对应成员轮次。
            self.refresh_epochs()
        rows = []
        for name in self.selection['member_ids']:
            body = self.member_body(name,path,fields if per_member is None else per_member[name])
            rows.append(dict(device_id=name,path=path,body=body))
        result = self.request('/api/cluster/dispatch',self.envelope(
            selection_id=self.selection['selection_id'],commands=rows))
        for row in result['results']:
            if row['ok']:
                name,data = row['device_id'],row['data']
                entry = self.members.setdefault(name,dict(sequence=-1,intent=-1))
                entry.update(session_id=data.get('session_id',entry.get('session_id')),
                    control_epoch=data['control_epoch'],ttl_ms=data['control_config']['input_ttl_ms'])
                if path=='/api/session': entry.update(sequence=-1,intent=-1)
        return result

    def refresh_epochs(self):
        states={s['device_id']:s for s in self.state()['members']}
        for name in self.selection['member_ids']:
            if name in self.members:
                self.members[name]['control_epoch']=states[name]['control_epoch']

    def begin_intent(self):
        # 每次明确的新操纵才增加意图编号，不在超时/保护后自动重试旧运动。
        states = {s['device_id']:s for s in self.state()['members']}
        for name in self.selection['member_ids']:
            self.members[name]['control_epoch'] = states[name]['control_epoch']
            self.members[name]['intent'] += 1

    def controls(self,vx=0.,vy=0.,vz=0.,yaw=0.):
        return self.batch('/api/control',per_member={n:dict(intent_sequence=self.members[n]['intent'],
            frame='world',vx_m_s=vx,vy_m_s=vy,vz_m_s=vz,yaw_rate_rad_s=yaw)
            for n in self.selection['member_ids']})

    def close(self): self.connection.close()
