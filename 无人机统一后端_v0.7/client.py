"""APP/指环/语音适配参考；不包含蓝牙协议或语音识别。"""
import http.client
import json
import time
import uuid
from urllib.parse import urlsplit
from app_server import DEMO_TOKENS


class ClientError(RuntimeError):
    def __init__(self, status, payload):
        super().__init__(str(payload))
        self.status, self.payload = status, payload


class SourceClient:
    def __init__(self, source, url='http://127.0.0.1:8765', token=None):
        parsed = urlsplit(url)
        if parsed.scheme != 'http' or not parsed.hostname:
            raise ValueError('局域网URL应为http://主机:端口')
        self.source = source
        self.token = token or DEMO_TOKENS[source]
        self.connection = http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=3)
        self.sequence = -1
        self.intent = -1
        self.session_id = None
        self.epoch = None
        self.last_request_at = time.monotonic()
        self.max_ttl_ms = 500

    def request(self, path, body=None):
        # 未选通路可能长时间闲置；提前重建空闲HTTP连接，不重发任何运动请求。
        if time.monotonic()-self.last_request_at>2.:
            self.connection.close()
        self.last_request_at = time.monotonic()
        data = None if body is None else json.dumps(body, allow_nan=False).encode()
        self.connection.request('GET' if body is None else 'POST', path, data,
            {'Authorization':'Bearer '+self.token, 'Content-Type':'application/json'})
        response = self.connection.getresponse()
        result = json.loads(response.read())
        if response.status != 200:
            raise ClientError(response.status, result)
        if 'control_config' in result['data']:
            self.max_ttl_ms = result['data']['control_config']['input_ttl_ms']
        return result['data']

    def open_session(self):
        state = self.request('/api/session', {})
        self.session_id, self.epoch = state['session_id'], state['control_epoch']
        self.sequence, self.intent = -1, -1
        return state

    def begin_intent(self):
        # 仅在新的触摸/手势/语音事件调用。被抢占后禁止循环自动增加intent。
        state = self.request('/api/state')
        self.epoch = state['control_epoch']
        self.intent += 1
        return state

    def envelope(self, ttl_ms=500):
        # 序号每次发送递增；离散重试则复用原请求，不能重新生成命令ID。
        self.sequence += 1
        return dict(device_id='sim-drone-1',session_id=self.session_id, control_epoch=self.epoch,
            command_sequence=self.sequence, ttl_ms=min(ttl_ms,self.max_ttl_ms), sent_at_ms=time.time()*1000)

    def discrete(self, path, **fields):
        body = self.envelope()
        body.update(command_id=uuid.uuid4().hex,**fields)
        state = self.request(path,body)
        self.epoch = state['control_epoch']
        return state

    def switch(self, target, adaptive=False):
        # 只在用户选择通路时调用。切换后目标来源必须另发中立采样。
        self.epoch = self.request('/api/state')['control_epoch']
        return self.discrete('/api/adaptive-switch' if adaptive else '/api/switch',
                             target_source=target,user_initiated=True)

    def neutral(self, axes=None):
        self.epoch = self.request('/api/state')['control_epoch']
        return self.discrete('/api/neutral',axes=[0.,0.,0.,0.] if axes is None else axes)

    def heartbeat(self):
        return self.request('/api/heartbeat',self.envelope())

    def control(self, vx=0., vy=0., vz=0., yaw_rate=0., frame='heading', ttl_ms=500):
        body = self.envelope(ttl_ms)
        body.update(intent_sequence=self.intent, frame=frame, vx_m_s=vx, vy_m_s=vy,
                    vz_m_s=vz, yaw_rate_rad_s=yaw_rate)
        return self.request('/api/control', body)

    def voice(self, action, duration_ms=1000, frame='heading'):
        # 示例已识别编码。实际适配器必须传SDK报告的置信度，不能固定填1。
        return self.discrete('/api/voice-result',intent_sequence=self.intent,command_code=action,
            recognized=True,confidence=1.,duration_ms=duration_ms,frame=frame)

    def stop(self):
        # 用户明确停止时读取最新屏障，旧排队stop仍应保留旧epoch。
        self.epoch = self.request('/api/state')['control_epoch']
        return self.discrete('/api/stop')

    def resume(self):
        self.epoch = self.request('/api/state')['control_epoch']
        return self.discrete('/api/resume',neutral_confirmed=True)

    def close(self):
        self.connection.close()
