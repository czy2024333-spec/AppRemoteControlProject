"""UE CoreStateAccess 联调桥接：平铺集群流、解析兼容性与坐标一致性。

解析规则逐条对应 CsaBackendSseClient::ParseMemberObject / ParseStatePayload：
- 集群负载看顶层 members 数组，逐成员独立解析，单个坏帧不影响其他成员；
- 成员必须含数值 sequence、非空 device_id、三个长度>=3 的数组与长度>=4 的四元数；
- APP 的嵌套只读契约不满足上述要求，因此 UE 使用独立的 /api/v1/ue/events。
"""
import http.client
import json
import math
from pathlib import Path
import tempfile
import threading
import time
import unittest

from independent_fleet import IndependentFleet
from independent_server import make_independent_server
from state_replay import replay_sample


def parse_member_object(obj):
    """UE ParseMemberObject 的等价实现；返回 None 表示 UE 会丢弃该帧。"""
    if not isinstance(obj, dict):
        return None
    sequence = obj.get('sequence')
    if not isinstance(sequence, (int, float)) or isinstance(sequence, bool):
        return None
    device_id = obj.get('device_id')
    if not isinstance(device_id, str) or not device_id:
        return None
    state = dict(sequence=int(sequence), device_id=device_id,
        simulation_time_s=obj.get('simulation_time_s', 0.0),
        server_time_ms=obj.get('server_time_ms', 0.0),
        data_source=obj.get('data_source', ''), mode=obj.get('mode', ''),
        protection_reason=obj.get('protection_reason') or '',
        control_epoch=obj.get('control_epoch', ''), data_quality=obj.get('data_quality', ''),
        link_status=obj.get('link_status', ''), state_age_ms=obj.get('state_age_ms', 0.0),
        online=obj.get('online', True))
    if str(state['link_status']).upper() == 'LOST':
        state['online'] = False
    for field in ('position_m', 'velocity_m_s', 'angular_velocity_body_rad_s'):
        value = obj.get(field)
        if not isinstance(value, list) or len(value) < 3:
            return None
        state[field] = [float(v) for v in value[:3]]
    quaternion = obj.get('quaternion_xyzw')
    if not isinstance(quaternion, list) or len(quaternion) < 4:
        return None
    state['quaternion_xyzw'] = [float(v) for v in quaternion[:4]]
    return state


def parse_state_payload(payload):
    """UE ParseStatePayload：有 members 即集群，否则按单成员处理。"""
    if isinstance(payload.get('members'), list):
        states = []
        for item in payload['members']:
            state = parse_member_object(item)
            if state is not None:
                state['from_cluster'] = True
                state['cluster_id'] = payload.get('cluster_id', '')
                states.append(state)
        return states
    state = parse_member_object(payload)
    return [state] if state is not None else []


def local_flu_to_ue(member):
    """CsaCoordinateConverter 的 LocalFLU 规则：镜像 Y，米转厘米。"""
    position, velocity = member['position_m'], member['velocity_m_s']
    qx, qy, qz, qw = member['quaternion_xyzw']
    wx, wy, wz = member['angular_velocity_body_rad_s']
    return dict(position_cm=[position[0]*100, -position[1]*100, position[2]*100],
        linear_velocity_cm_s=[velocity[0]*100, -velocity[1]*100, velocity[2]*100],
        quaternion_xyzw=[-qx, qy, -qz, qw], angular_velocity_body_rad_s=[-wx, wy, -wz])


def raw_member(device_id='sim-drone-1', **overrides):
    member = dict(device_id=device_id, boot_id='member-boot', sequence=7,
        simulation_time_s=1.25, server_time_ms=1234., data_source='simulation_truth',
        position_m=[1., 2., 3.], velocity_m_s=[.1, .2, .3],
        quaternion_xyzw=[0., 0., 0., 1.], angular_velocity_body_rad_s=[.4, .5, .6],
        online=True, mode='HOLD', protection_reason=None, control_epoch='epoch-1',
        data_quality='SIMULATION', link_status='ONLINE', state_age_ms=12.)
    member.update(overrides)
    return member


def nested_contract_member():
    """APP 只读契约的形状：position/attitude 是测量对象而非数组。"""
    return dict(device_id='sim-drone-1', sequence=7, simulation_time_s=1.25,
        server_time_unix_ms=1234., online=True, mode='HOLD',
        position=dict(value=dict(x=1., y=2., z=3.), validity='VALID'),
        attitude=dict(value=dict(x=0., y=0., z=0., w=1.)))


class UeBridgeContractTests(unittest.TestCase):
    def test_nested_app_contract_cannot_drive_ue(self):
        # 记录联调前真实问题：直接复用 APP 契约会导致 UE 整帧丢弃。
        self.assertEqual(parse_state_payload(nested_contract_member()), [])

    def test_flat_cluster_payload_parses_every_member(self):
        payload = dict(cluster_id='sim-cluster-1', boot_id='boot', selection=None,
            members=[raw_member('sim-drone-1'), raw_member('sim-drone-2'),
                raw_member('sim-drone-3')])
        states = parse_state_payload(payload)
        self.assertEqual([s['device_id'] for s in states],
            ['sim-drone-1', 'sim-drone-2', 'sim-drone-3'])
        self.assertTrue(all(s['from_cluster'] for s in states))

    def test_one_bad_member_does_not_drop_whole_cluster(self):
        payload = dict(members=[raw_member('sim-drone-1'),
            dict(device_id='sim-drone-2'), raw_member('sim-drone-3')])
        states = parse_state_payload(payload)
        self.assertEqual([s['device_id'] for s in states], ['sim-drone-1', 'sim-drone-3'])

    def test_live_stream_and_replay_share_identical_ue_conversion(self):
        member = raw_member(quaternion_xyzw=[.2, .3, .4, math.sqrt(1-.2**2-.3**2-.4**2)])
        live = local_flu_to_ue(member)
        recorded = replay_sample(member, cluster_id='c', boot_id='b',
            frame_sequence=1, recorded_at_unix_ms=1.)['ue']
        for field in ('position_cm', 'linear_velocity_cm_s', 'quaternion_xyzw',
                'angular_velocity_body_rad_s'):
            for actual, wanted in zip(live[field], recorded[field]):
                self.assertAlmostEqual(actual, wanted)


class UeBridgeHttpTests(unittest.TestCase):
    def setUp(self):
        base = Path(__file__).resolve().parent/'results/ue_bridge_tests'
        base.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=base)
        config = json.loads(Path(__file__).with_name('cluster_config.json').read_text('utf-8'))
        # 同时启用最新GNSS模型，核对UE平铺入口和APP嵌套入口都可继续读取。
        config['gnss'] = dict(config.get('gnss',{}),enabled=True)
        self.initial_positions = {m['device_id']: m['dynamics']['hover_position_m'] for m in config['members']}
        self.credentials = json.loads(Path(__file__).with_name('independent_credentials.json').read_text('utf-8'))
        self.fleet = IndependentFleet(config, self.credentials, self.directory.name)
        self.server = make_independent_server(self.fleet, port=0)
        self.thread = threading.Thread(target=lambda: self.server.serve_forever(poll_interval=.02), daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.fleet.close()
        self.directory.cleanup()

    def read_stream(self, path, token, count=3, timeout=6, last_id=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=timeout)
        try:
            headers = {'Authorization': 'Bearer '+token, 'Accept': 'text/event-stream'}
            if last_id is not None:
                headers['Last-Event-ID'] = last_id
            connection.request('GET', path, headers=headers)
            response = connection.getresponse()
            if response.status != 200:
                return response.status, []
            frames = []
            deadline = time.monotonic()+timeout
            while len(frames) < count and time.monotonic() < deadline:
                line = response.readline()
                if not line:
                    break
                if line.startswith(b'data: '):
                    frames.append(json.loads(line[6:].decode('utf-8')))
            return response.status, frames
        finally:
            connection.close()

    def test_cross_view_resume_cursor_resyncs_and_preserves_gnss_metadata(self):
        token = self.credentials['credentials'][0]['token']
        status, ue = self.read_stream('/api/v1/ue/events',token,count=5)
        self.assertEqual(status,200)
        self.assertTrue(all('gnss_local_measurement' in m for m in ue[-1]['members']))
        # 将APP格式的旧游标交给UE端点，首帧必须按新视图重新同步。
        wrong = self.fleet.boot_id+'/fleet/-/0:1'
        _, resynced = self.read_stream('/api/v1/ue/events',token,count=1,last_id=wrong)
        self.assertFalse(resynced[0]['replayed'])
        self.assertEqual(len(parse_state_payload(resynced[0])),3)
        # 反向错用UE游标也触发重新同步，并仍返回含GNSS的APP契约。
        wrong = self.fleet.boot_id+'/ue/fleet/-/0:1'
        _, app = self.read_stream('/api/v1/events',token,count=1,last_id=wrong)
        self.assertFalse(app[0]['replayed'])
        self.assertTrue(all('gnss_local_position' in m['sensors'] for m in app[0]['members']))

    def test_real_backend_feeds_three_flat_members_to_ue(self):
        token = self.credentials['credentials'][0]['token']
        status, frames = self.read_stream('/api/v1/ue/events', token)
        self.assertEqual(status, 200)
        self.assertGreaterEqual(len(frames), 3, '状态静默时只发心跳，需确认发布线程在推进')
        for payload in frames:
            states = parse_state_payload(payload)
            self.assertEqual(len(states), 3, payload.get('cluster_id'))
            self.assertEqual({s['device_id'] for s in states},
                {'sim-drone-1', 'sim-drone-2', 'sim-drone-3'})
            for state in states:
                self.assertTrue(state['online'])
                self.assertEqual(state['data_quality'], 'SIMULATION')
                self.assertEqual(state['link_status'], 'ONLINE')
                self.assertTrue(state['control_epoch'])
                self.assertGreater(state['sequence'], 0)
                for actual, expected in zip(state['position_m'],self.initial_positions[state['device_id']]):
                    self.assertAlmostEqual(actual,expected,places=6)
                self.assertEqual(state['quaternion_xyzw'], [0., 0., 0., 1.])

    def test_capabilities_advertise_ue_route_and_auth_is_enforced(self):
        token = self.credentials['credentials'][0]['token']
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)
        try:
            connection.request('GET', '/api/v1/capabilities', headers={'Authorization': 'Bearer '+token})
            payload = json.loads(connection.getresponse().read())
        finally:
            connection.close()
        self.assertEqual(payload['data']['routes']['ue_events'], '/api/v1/ue/events')
        self.assertTrue(payload['data']['capabilities']['ue_state_stream'])
        status, _ = self.read_stream('/api/v1/ue/events', 'wrong-token')
        self.assertEqual(status, 401)


if __name__ == '__main__':
    unittest.main(verbosity=2)
