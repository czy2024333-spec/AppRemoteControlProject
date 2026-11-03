"""按设备绑定的 Python 联调客户端；所有端均可获取全机状态。"""
from client import SourceClient


class IndependentClient(SourceClient):
    def __init__(self, device_id, token, url='http://127.0.0.1:8765', source='app'):
        super().__init__(source,url,token)
        self.device_id = device_id
        self.cluster_id = None

    def request(self, path, body=None):
        if path.startswith('/api/') and not path.startswith('/api/v1/'):
            path = '/api/devices/'+self.device_id+'/'+path[len('/api/'):]
        if body is not None:
            body = dict(body,device_id=self.device_id,cluster_id=self.cluster_id)
            if path.endswith('/session'):
                body['user_initiated'] = True
        return super().request(path,body)

    def open_session(self):
        # 主动建立本机新会话；不会触碰另外两架的控制权。
        self.cluster_id = super().request('/api/v1/capabilities')['cluster_id']
        return super().open_session()

    def envelope(self, ttl_ms=500):
        return dict(super().envelope(ttl_ms),device_id=self.device_id,cluster_id=self.cluster_id)

    def fleet_state(self):
        return super().request('/api/v1/state')
