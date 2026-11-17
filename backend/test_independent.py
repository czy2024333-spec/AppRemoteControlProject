"""三机独立控制、共享观察及权限隔离的真实 HTTP 回归。"""
import concurrent.futures
from contextlib import contextmanager
import copy
import csv
import http.client
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import numpy as np
from async_audit import AsyncAudit
from client import ClientError
from independent_client import IndependentClient
from independent_fleet import IndependentFleet, validate_credentials
from independent_server import StateFeed, make_independent_server
from runtime_info import COMMAND_HISTORY_LIMIT, COMMAND_QUEUE_LIMIT, SAFETY_COMMAND_HISTORY_LIMIT, STATE_STREAM_TARGET_HZ


def temporary_directory():
    # 测试只在项目 results 内创建和清理临时实例，不依赖系统 TEMP 的写权限。
    base = Path(__file__).resolve().parent/'results/independent_tests'
    base.mkdir(parents=True,exist_ok=True)
    folder = tempfile.TemporaryDirectory(dir=base)
    assert Path(folder.name).resolve().is_relative_to(base.resolve())
    return folder


class StateFeedTimingTests(unittest.TestCase):
    def test_capture_phase_uses_the_gap_between_scheduled_steps(self):
        # 状态预计在每周期的 2、7、12 ms 就绪，跨步最大的空档中心是 17 ms。
        origins = (20.,20.005,20.010)
        self.assertAlmostEqual(StateFeed._aligned_capture_time(20.04,20.,origins,.02),20.037)
        # 启动时超过的空档必须顺延，避免刚起步就取上一个周期的相位。
        self.assertAlmostEqual(StateFeed._aligned_capture_time(20.,20.,origins,.02),20.017)


class CredentialsTests(unittest.TestCase):
    def test_roles_bindings_and_unique_tokens_are_validated_before_start(self):
        value = json.loads(Path('independent_credentials.json').read_text('utf-8'))
        ids = {'sim-drone-1','sim-drone-2','sim-drone-3'}
        self.assertEqual(len(validate_credentials(value,ids)),4)
        for mutate in (
            lambda v:v['credentials'][0].update(device_id='unknown'),
            lambda v:v['credentials'][3].update(device_id='sim-drone-1'),
            lambda v:v['credentials'][2].update(token=v['credentials'][0]['token']),
            lambda v:v['credentials'].pop(0),
        ):
            candidate = copy.deepcopy(value); mutate(candidate)
            with self.assertRaises(ValueError):
                validate_credentials(candidate,ids)


class AuditTests(unittest.TestCase):
    def test_capacity_cannot_be_unbounded(self):
        for invalid in (0,-1,True,65537):
            with self.assertRaises(ValueError):
                AsyncAudit(Path('results/invalid-audit.jsonl'),capacity=invalid)

    def test_bounded_queue_failure_is_visible_and_drain_is_not_silent_success(self):
        with temporary_directory() as folder:
            writer = AsyncAudit(Path(folder)/'events.jsonl',capacity=1)
            entered, release = threading.Event(), threading.Event()
            original = writer._write
            def delayed(log, row):
                entered.set(); release.wait(2)
                return original(log,row)
            try:
                with patch.object(writer,'_write',delayed):
                    self.assertTrue(writer.emit({'sequence':1}))
                    self.assertTrue(entered.wait(1))
                    self.assertTrue(writer.emit({'sequence':2}))
                    self.assertFalse(writer.emit({'sequence':3}))
                    self.assertEqual(writer.status()['failure'],'AUDIT_QUEUE_FULL')
                    self.assertEqual(writer.status()['rejected'],1)
                    release.set()
                    self.assertFalse(writer.close())
                self.assertEqual(writer.status()['written'],2)
            finally:
                release.set(); writer.close()


class IndependentTests(unittest.TestCase):
    def setUp(self):
        self.directory = temporary_directory()
        self.config = json.loads(Path('cluster_config.json').read_text('utf-8'))
        self.credentials = json.loads(Path('independent_credentials.json').read_text('utf-8'))
        self.start_instance()

    def start_instance(self):
        self.fleet = IndependentFleet(self.config,self.credentials,self.directory.name)
        try:
            self.server = make_independent_server(self.fleet,port=0,allowed_origins=['http://localhost:5173'])
        except OSError:
            self.fleet.close()
            raise
        self.thread = threading.Thread(target=lambda:self.server.serve_forever(poll_interval=.02),daemon=True)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        self.clients = [IndependentClient(row['device_id'],row['token'],self.url)
            for row in self.credentials['credentials'][:3]]

    def stop_instance(self):
        for client in self.clients:
            client.close()
        self.server.shutdown(); self.server.server_close(); self.fleet.close()

    def tearDown(self):
        self.stop_instance()
        self.directory.cleanup()

    def request(self, path, body=None, index=3, headers=None):
        connection = http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
        try:
            auth = {'Authorization':'Bearer '+self.credentials['credentials'][index]['token'],'Content-Type':'application/json'}
            auth.update(headers or {})
            connection.request('GET' if body is None else 'POST',path,None if body is None else json.dumps(body).encode(),auth)
            response = connection.getresponse()
            return response.status,json.loads(response.read())
        finally:
            connection.close()

    def activate(self, client):
        client.open_session(); client.switch('app'); client.neutral(); client.begin_intent()

    @contextmanager
    def open_stream(self, path='/api/v1/events', last_id=None, index=3):
        connection = http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
        headers = {'Authorization':'Bearer '+self.credentials['credentials'][index]['token']}
        if last_id is not None:
            headers['Last-Event-ID'] = last_id
        try:
            connection.request('GET',path,headers=headers)
            response = connection.getresponse()
            self.assertEqual(response.status,200)
            yield response
        finally:
            connection.close()

    def read_event(self, response):
        fields = {}
        while line := response.readline():
            line = line.rstrip(b'\r\n')
            if not line and fields:
                return fields
            if line.startswith(b': '):
                fields['comment'] = line[2:].decode()
            elif line.startswith(b'id: '):
                fields['id'] = line[4:].decode()
            elif line.startswith(b'data: '):
                fields['data'] = json.loads(line[6:])
        self.fail('状态流意外结束')

    def test_state_stream_reconnect_retains_order_and_reprojects_permissions(self):
        with self.open_stream(index=0) as response:
            first = self.read_event(response)
        self.assertEqual(first['data']['self_device_id'],'sim-drone-1')
        time.sleep(.16)
        with self.open_stream(last_id=first['id']) as response:
            replay = self.read_event(response)
            self.assertTrue(replay['data']['replayed'])
            self.assertIsNone(replay['data']['self_device_id'])
            self.assertFalse(any(m['control']['write_permitted'] for m in replay['data']['members']))
            self.assertEqual(int(replay['id'].split(':')[-1]),int(first['id'].split(':')[-1])+1)
        with self.open_stream(last_id='old-boot/fleet/-/0:1') as response:
            self.assertEqual(self.read_event(response),{'comment':'resync'})
            fresh = self.read_event(response)
            self.assertFalse(fresh['data']['replayed'])
            self.assertEqual(len(fresh['data']['members']),3)
        status, payload = self.request('/api/health')
        self.assertEqual(status,200)
        cadence = payload['data']['state_publisher']['cadence']
        self.assertEqual(cadence['target_hz'],STATE_STREAM_TARGET_HZ)
        self.assertIn(cadence['samples'],range(1,self.server.state_feed.history.maxlen))
        self.assertGreater(cadence['capture_gap_p95_ms'],0)
        self.assertGreaterEqual(cadence['capture_gap_max_ms'],cadence['capture_gap_p95_ms'])
        self.assertGreaterEqual(cadence['latest_frame_age_ms'],0)

    def test_stream_resume_header_is_allowed_for_an_approved_origin(self):
        connection = http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
        try:
            connection.request('OPTIONS','/api/v1/events',headers={
                'Origin':'http://localhost:5173','Access-Control-Request-Method':'GET',
                'Access-Control-Request-Headers':'Authorization, Last-Event-ID'})
            response = connection.getresponse()
            self.assertEqual(response.status,204)
            self.assertIn('Last-Event-ID',response.getheader('Access-Control-Allow-Headers'))
        finally:
            connection.close()

    def test_stream_heartbeat_does_not_publish_a_fake_state(self):
        for service in self.fleet.members.values():
            service.close()
        with self.open_stream() as response:
            first = self.read_event(response)
            deadline = time.monotonic()+2.3
            self.assertIn('data',first)
            while time.monotonic() < deadline:
                event = self.read_event(response)
                if event.get('comment') == 'heartbeat':
                    self.assertNotIn('data',event)
                    self.assertTrue(self.server.state_feed.status()['healthy'])
                    break
            else:
                self.fail('状态停更后未收到心跳')

    def test_publisher_exception_closes_stream_and_keeps_snapshots_available(self):
        original = self.fleet.fleet_snapshot

        def fail_publish(states=None):
            if states is not None:
                raise RuntimeError('模拟共享发布异常')
            return original()

        with self.open_stream() as response:
            self.assertIn('data',self.read_event(response))
            with self.assertLogs(level='ERROR'), patch.object(self.fleet,'fleet_snapshot',fail_publish):
                self.server.state_feed.thread.join(2)
                self.assertFalse(self.server.state_feed.thread.is_alive())
            # 已经在 TCP 缓冲中的旧状态允许到达，但断流前不再发注释心跳。
            self.assertNotIn(b': heartbeat',response.read())
        status, payload = self.request('/api/health')
        self.assertEqual(status,503)
        publisher = payload['data']['state_publisher']
        self.assertFalse(publisher['healthy'])
        self.assertFalse(publisher['thread_alive'])
        self.assertEqual(publisher['reason'],'STATE_PUBLISHER_FAILED')
        for path in ('/api/v1/events','/api/v1/devices/sim-drone-1/events','/api/devices/sim-drone-1/events'):
            with self.subTest(path=path):
                status, payload = self.request(path)
                self.assertEqual(status,503)
                self.assertEqual(payload['error_code'],'STATE_PUBLISHER_UNAVAILABLE')
                self.assertEqual(payload['error_detail']['code'],'SERVICE_DEGRADED')
        self.assertEqual(self.request('/api/v1/state')[0],200)

    def test_publisher_stall_closes_stream_while_thread_is_alive(self):
        entered, release = threading.Event(), threading.Event()
        original = self.fleet.fleet_snapshot

        def block_publish(states=None):
            if states is not None:
                entered.set()
                release.wait(5.)
            return original(states)

        with self.open_stream() as response:
            self.assertIn('data',self.read_event(response))
            with patch.object(self.fleet,'fleet_snapshot',block_publish):
                try:
                    self.assertTrue(entered.wait(1.))
                    self.assertNotIn(b': heartbeat',response.read())
                    status, payload = self.request('/api/health')
                    self.assertEqual(status,503)
                    publisher = payload['data']['state_publisher']
                    self.assertTrue(publisher['thread_alive'])
                    self.assertEqual(publisher['reason'],'STATE_PUBLISHER_STALLED')
                    self.assertGreaterEqual(publisher['progress_age_ms'],publisher['progress_timeout_ms'])
                finally:
                    release.set()
        deadline = time.monotonic()+1.
        while not self.server.state_feed.status()['healthy'] and time.monotonic()<deadline:
            time.sleep(.01)
        with self.open_stream() as response:
            self.assertIn('data',self.read_event(response))
        self.assertEqual(self.request('/api/v1/state')[0],200)

    def test_capabilities_advertise_hud_and_stream_features_as_booleans(self):
        status, payload = self.request('/api/v1/capabilities')
        self.assertEqual(status,200)
        data = payload['data']
        self.assertEqual(data['contract_version'],'1.0')
        for key in ('hud_state_metadata','sse_comment_heartbeat','sse_resume','sse_focus','state_publisher_health'):
            self.assertIs(data['capabilities'][key],True)
        self.assertTrue(all(type(value) is bool for value in data['capabilities'].values()))
        policy = data['link_policy']
        self.assertEqual(policy['state_stream_target_hz'],data['control_policy']['state_stream_target_hz'])
        self.assertEqual(policy['member_state_stale_after_ms'],self.config['limits']['lost_timeout_s']*1000)
        self.assertEqual(policy['sse_replay_frames'],self.server.state_feed.history.maxlen)
        self.assertEqual(policy['state_publisher_timeout_ms'],self.server.state_feed.status()['progress_timeout_ms'])
        self.assertGreater(policy['recommended_sse_read_timeout_ms'],policy['sse_idle_heartbeat_ms'])
        self.assertGreater(policy['recommended_sse_first_frame_timeout_ms'],policy['sse_idle_heartbeat_ms'])

    def test_boundary_recovery_matches_legacy_and_unified_for_same_snapshot(self):
        service = self.fleet.members['sim-drone-1']
        # 停止工作线程，保证两次真实 HTTP 请求读取同一发布序号及仍有阻塞项的恢复状态。
        service.close()
        service.core._state['x'] = np.array([0.,0.,.299])
        service.core.protect('ALTITUDE_STATE_LIMIT')
        service.publish()
        old_status, old = self.request('/api/devices/sim-drone-1/state')
        new_status, new = self.request('/api/v1/devices/sim-drone-1/state')
        self.assertEqual((old_status,new_status),(200,200))
        old, new = old['data'],new['data']
        self.assertEqual((new['boot_id'],new['sequence']),(old['boot_id'],old['sequence']))
        self.assertIn('ALTITUDE',old['boundary_recovery']['blockers'])
        self.assertEqual(new['protection']['recovery'],old['boundary_recovery'])
        status, payload = self.request('/api/v1/state')
        self.assertEqual(status,200)
        overview = next(row for row in payload['data']['members'] if row['device_id']==old['device_id'])
        self.assertEqual((overview['boot_id'],overview['sequence']),(old['boot_id'],old['sequence']))
        self.assertEqual(overview['protection']['recovery'],old['boundary_recovery'])

    def test_focus_stream_keeps_full_self_and_explicit_sparse_peers(self):
        with self.open_stream('/api/v1/events?focus=sim-drone-2',index=1) as response:
            first = self.read_event(response)['data']
            self.assertEqual(first['focus_device_id'],'sim-drone-2')
            self.assertTrue(first['partial'])
            self.assertTrue(first['peers_included'])
            self.assertEqual(len(first['members']),3)
            rows = {m['device_id']:m for m in first['members']}
            self.assertTrue(rows['sim-drone-2']['control']['write_permitted'])
            self.assertIn('yaw',rows['sim-drone-1'])
            self.assertNotIn('control',rows['sim-drone-1'])
            self.assertFalse(self.read_event(response)['data']['peers_included'])
        self.assertEqual(self.request('/api/v1/events?focus=unknown')[0],404)

    def test_control_state_reports_applied_motion_and_clears_on_new_epoch(self):
        app = self.clients[0]
        app.open_session()
        app.switch('app')
        path = '/api/v1/devices/sim-drone-1/state'
        state = lambda: self.request(path)[1]['data']['control']
        waiting = state()
        self.assertTrue(waiting['awaiting_neutral'])
        self.assertFalse(waiting['active'])
        app.neutral(); app.begin_intent()
        selected = state()
        self.assertEqual(selected['phase'],'SOURCE_SELECTED')
        self.assertFalse(selected['active'])
        command = app.control(vx=.1)
        active = state()
        self.assertTrue(active['active'])
        self.assertEqual(active['last_applied']['command_sequence'],command['accepted_command_sequence'])
        app.heartbeat()
        self.assertEqual(state()['last_applied'],active['last_applied'])
        app.stop()
        self.assertIsNone(state()['last_applied'])

    def test_every_viewer_sees_three_members_and_only_own_permission(self):
        for i in range(4):
            code, result = self.request('/api/v1/state',index=i)
            self.assertEqual(code,200,result)
            members = result['data']['members']
            self.assertEqual(len(members),3)
            self.assertEqual(len({m['boot_id'] for m in members}),3)
            self.assertTrue(all(m['position']['unit']=='m' and m['position']['validity']=='VALID' for m in members))
            self.assertEqual(sum(m['is_self'] for m in members),1 if i<3 else 0)
            self.assertEqual(sum(m['control']['write_permitted'] for m in members),1 if i<3 else 0)
            self.assertTrue(all(m['battery']['value'] is None and m['gnss']['value'] is None for m in members))
            code, directory = self.request('/api/v1/devices',index=i)
            self.assertEqual(code,200)
            self.assertEqual(sum(d['write_permitted'] for d in directory['data']['devices']),1 if i<3 else 0)
        self.assertTrue(all(not s.sessions for s in self.fleet.members.values()),'观察不得创建控制会话')

    def test_observer_and_other_device_cannot_write_through_any_route(self):
        name = 'sim-drone-2'
        body = dict(device_id=name,cluster_id=self.fleet.cluster_id,user_initiated=True)
        for prefix in ('/api/v1/devices/','/api/devices/'):
            self.assertEqual(self.request(prefix+name+'/session',body,index=0)[0],403)
            self.assertEqual(self.request(prefix+name+'/protect',body)[0],403)
        self.assertFalse(self.fleet.members[name].sessions)
        self.assertEqual(self.request('/api/v1/state',headers={'Authorization':'Bearer invalid'})[0],401)
        self.assertEqual(self.request('/api/session',{},index=0)[0],409)
        self.assertEqual(self.request('/api/cluster/select',{},index=0)[0],409)

    def test_explicit_session_correct_route_and_neutral_are_required(self):
        body = dict(device_id='sim-drone-1',cluster_id=self.fleet.cluster_id)
        self.assertEqual(self.request('/api/v1/devices/sim-drone-1/session',body,index=0)[1]['error_code'],'USER_ACTION_REQUIRED')
        body.update(user_initiated=True,device_id='sim-drone-2')
        self.assertEqual(self.request('/api/v1/devices/sim-drone-1/session',body,index=0)[1]['error_code'],'WRONG_DEVICE')
        app = self.clients[0]; app.open_session(); app.begin_intent()
        with self.assertRaises(ClientError) as raised:
            app.control(vx=.1)
        self.assertEqual(raised.exception.payload['error_code'],'SOURCE_NOT_SELECTED')
        app.switch('app')
        with self.assertRaises(ClientError) as raised:
            app.neutral([.2,0,0,0])
        self.assertEqual(raised.exception.payload['error_code'],'NOT_NEUTRAL')
        app.neutral(); app.begin_intent(); app.control(vx=.1)

    def test_optional_session_clock_check_rejects_before_replacing_session(self):
        app = self.clients[0]
        self.activate(app)
        active = app.control(vx=.1)
        path = '/api/v1/devices/sim-drone-1/session'
        body = dict(device_id=app.device_id,cluster_id=app.cluster_id,user_initiated=True)
        for offset, reason in ((10000,'CLIENT_TIME_AHEAD'),(-10000,'EXPIRED_AT_RECEIPT')):
            with self.subTest(reason=reason):
                code, payload = self.request(path,dict(body,client_sent_at_ms=time.time()*1000+offset),index=0)
                self.assertEqual((code,payload['error_code']),(409,'CLOCK_SYNC_REQUIRED'))
                detail = payload['error_detail']
                self.assertEqual(detail['code'],'CLOCK_SYNC_REQUIRED')
                self.assertEqual(detail['retry_policy'],'USER_ACTION_REQUIRED')
                self.assertEqual(detail['timing']['reason'],reason)
                self.assertEqual(detail['timing']['ttl_ms'],active['control_config']['input_ttl_ms'])
                self.assertGreater(detail['timing']['server_received_at_ms'],0)
        self.assertEqual(app.control(vx=.1)['control_epoch'],active['control_epoch'])
        code, payload = self.request(path,dict(body,client_sent_at_ms=time.time()*1000),index=0)
        self.assertEqual(code,200)
        data = payload['data']
        self.assertNotEqual(data['session_id'],app.session_id)
        self.assertEqual(data['control_epoch_reason'],'SESSION_REPLACED')
        self.assertLessEqual(data['ack']['server_received_at_ms'],data['ack']['confirmed_at_ms'])

    def test_command_timing_reports_skew_expiry_on_arrival_and_queue_expiry(self):
        app = self.clients[0]
        app.open_session()
        path = '/api/v1/devices/sim-drone-1/heartbeat'
        for offset, status, raw_code, reason in ((10000,400,'CLOCK_SKEW','CLIENT_TIME_AHEAD'),
            (-10000,409,'EXPIRED_COMMAND','EXPIRED_AT_RECEIPT')):
            with self.subTest(reason=reason):
                body = dict(app.envelope(),sent_at_ms=time.time()*1000+offset)
                code, payload = self.request(path,body,index=0)
                self.assertEqual((code,payload['error_code']),(status,raw_code))
                timing = payload['error_detail']['timing']
                self.assertEqual(timing['reason'],reason)
                self.assertEqual(timing['ttl_ms'],body['ttl_ms'])

        service = self.fleet.members[app.device_id]
        envelope = service.envelope

        def delayed(item, require_epoch=True):
            # 只模拟服务器队列滞留，接收时的报文年龄保持正常。
            return envelope(dict(item,received=item['received']-1),require_epoch=require_epoch)

        with patch.object(service,'envelope',delayed):
            code, payload = self.request(path,app.envelope(),index=0)
        self.assertEqual((code,payload['error_code']),(409,'EXPIRED_COMMAND'))
        timing = payload['error_detail']['timing']
        self.assertEqual(timing['reason'],'EXPIRED_WHILE_QUEUED')
        self.assertLess(timing['observed_age_ms'],timing['ttl_ms'])
        self.assertGreaterEqual(timing['queue_age_ms'],1000)

    def test_out_of_range_integer_timestamp_does_not_stop_member(self):
        app = self.clients[0]
        app.open_session()
        path = '/api/v1/devices/sim-drone-1/heartbeat'
        code, payload = self.request(path,dict(app.envelope(),sent_at_ms=10**400),index=0)
        self.assertEqual((code,payload['error_code']),(400,'INVALID_ARGUMENT'))
        self.assertEqual(self.request(path,app.envelope(),index=0)[0],200)
        self.assertTrue(self.fleet.members[app.device_id].thread.is_alive())

    def test_epoch_reason_stays_stable_through_neutral_and_motion(self):
        app = self.clients[0]
        app.open_session()
        switched = app.switch('app')
        app.neutral(); app.begin_intent()
        active = app.control(vx=.1)
        self.assertEqual(active['transition_reason'],'INPUT_app')
        self.assertEqual(active['control_epoch'],switched['control_epoch'])
        self.assertEqual(active['control_epoch_reason'],'USER_SWITCH_TO_app')
        with self.open_stream('/api/v1/devices/sim-drone-1/events') as response:
            # SSE 按独立节拍取样。连接首帧可能早于刚完成的 HTTP 回执。
            for _ in range(5):
                state = self.read_event(response)['data']
                if state['mode']=='ACTIVE':
                    break
        self.assertEqual(state['mode'],'ACTIVE')
        self.assertEqual(state['control']['control_epoch'],active['control_epoch'])
        self.assertEqual(state['control']['epoch_reason'],active['control_epoch_reason'])

    def test_three_concurrent_pilots_move_independently_and_observer_sees_all(self):
        for app in self.clients:
            self.activate(app)
        def drive(pair):
            index, app = pair
            for _ in range(9):
                app.control(vx=.18 if index==0 else 0,vy=-.18 if index==1 else 0,
                    vz=.15 if index==2 else 0,frame='world')
                time.sleep(.04)
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            list(pool.map(drive,enumerate(self.clients)))
        states = self.request('/api/v1/state')[1]['data']['members']
        self.assertTrue(all(m['mode']=='ACTIVE' for m in states),states)
        self.assertGreater(states[0]['position']['value']['x'],.005)
        self.assertLess(states[1]['position']['value']['y'],-.005)
        self.assertGreater(states[2]['position']['value']['z'],1.505)
        self.assertAlmostEqual(states[1]['position']['value']['x'],2.,places=4)
        self.assertAlmostEqual(states[2]['position']['value']['x'],0.,places=4)

    def test_one_timeout_does_not_revoke_other_two(self):
        for app in self.clients:
            self.activate(app)
        self.clients[0].control(vx=.1,ttl_ms=150)
        def drive(app):
            for _ in range(16):
                app.control(vy=.1,frame='world'); time.sleep(.04)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(drive,self.clients[1:]))
        states = self.request('/api/v1/state')[1]['data']['members']
        self.assertEqual(states[0]['mode'],'PROTECTED')
        self.assertTrue(any(a['code']=='INPUT_TIMEOUT' for a in states[0]['alarms']))
        self.assertTrue(all(m['mode']=='ACTIVE' for m in states[1:]),states)

    def test_replacing_one_session_invalidates_only_that_devices_old_packets(self):
        for app in self.clients:
            self.activate(app)
        old = self.clients[0].envelope()
        old.update(intent_sequence=0,frame='world',axes=[.1,0,0,0])
        other_sessions = [dict(self.fleet.members[app.device_id].sessions['app'])['id'] for app in self.clients[1:]]
        self.clients[0].open_session()
        code, result = self.request('/api/v1/devices/sim-drone-1/input',old,index=0)
        self.assertEqual((code,result['error_code']),(409,'STALE_SESSION'))
        for app, old_id in zip(self.clients[1:],other_sessions):
            self.assertEqual(self.fleet.members[app.device_id].sessions['app']['id'],old_id)
            self.assertEqual(app.control(vx=.1)['mode'],'ACTIVE')
        self.assertIsNone(self.fleet.member_state('sim-drone-1')['selected_source'])

    def test_explicit_disconnect_protects_only_this_device(self):
        for app in self.clients:
            self.activate(app)
        self.clients[0].control(vx=.1)
        result = self.clients[0].discrete('/api/disconnect',reason='APP_EXIT')
        self.assertEqual((result['mode'],result['protection_reason']),('PROTECTED','APP_EXIT'))
        for app in self.clients[1:]:
            self.assertEqual(app.control(vx=.1)['mode'],'ACTIVE')

    def test_failed_member_is_stale_and_rejects_writes_without_stopping_others(self):
        for app in self.clients:
            self.activate(app)
        with patch.object(self.fleet.members['sim-drone-1'],'failure','FORCED_TEST_FAILURE'):
            states = self.request('/api/v1/state')[1]['data']['members']
            self.assertFalse(states[0]['online'])
            self.assertEqual(states[0]['position']['validity'],'STALE')
            with self.assertRaises(ClientError) as raised:
                self.clients[0].control(vx=.1)
            self.assertEqual(raised.exception.payload['error_code'],'MEMBER_LOST')
            for app in self.clients[1:]:
                self.assertEqual(app.control(vx=.1)['mode'],'ACTIVE')

    def test_slow_member_does_not_block_other_member_or_global_observation(self):
        for app in self.clients[:2]:
            self.activate(app)
        entered, release = threading.Event(), threading.Event()
        original = self.fleet.members['sim-drone-1'].submit
        def delayed(path, body, source):
            if path in ('/api/input','/api/control'):
                entered.set(); release.wait(1.5)
            return original(path,body,source)
        outcomes = []
        def drive():
            try:
                self.clients[0].control(vx=.1)
            except ClientError as exc:
                outcomes.append(exc.payload['error_code'])
        with patch.object(self.fleet.members['sim-drone-1'],'submit',delayed):
            worker = threading.Thread(target=drive); worker.start()
            try:
                self.assertTrue(entered.wait(1))
                self.assertEqual(self.request('/api/v1/state')[0],200)
                self.assertEqual(self.clients[1].control(vx=.1)['mode'],'ACTIVE')
                self.assertTrue(worker.is_alive(),'慢成员请求应仍在等待，另一架已完成')
                # 同机保护也不等待该普通请求；旧输入在保护后释放时应被拒绝。
                packet = self.clients[0].envelope(); packet.update(command_id='priority-protect')
                code, value = self.request('/api/v1/devices/sim-drone-1/protect',packet,index=0)
                self.assertEqual(code,200,value)
                self.assertEqual(value['data']['mode'],'PROTECTED')
            finally:
                release.set(); worker.join(3)

    def test_slow_disk_is_not_waited_by_control_acknowledgements(self):
        self.activate(self.clients[0]); self.activate(self.clients[1])
        entered, release = threading.Event(), threading.Event()
        original = self.fleet.audit._write
        def delayed(log, row):
            if row.get('event')=='COMMAND_ACK' and row.get('path')=='/api/control':
                entered.set(); release.wait(2)
            return original(log,row)
        with patch.object(self.fleet.audit,'_write',delayed):
            try:
                self.assertEqual(self.clients[0].control(vx=.1)['mode'],'ACTIVE')
                self.assertTrue(entered.wait(1))
                self.assertFalse(release.is_set())
                self.assertEqual(self.clients[1].control(vy=.1)['mode'],'ACTIVE')
                self.assertGreaterEqual(self.fleet.audit.status()['pending'],1)
            finally:
                release.set()

    def test_audit_failure_protects_members_and_rejects_new_motion(self):
        self.activate(self.clients[0]); self.clients[0].control(vx=.1)
        self.fleet.audit._fail('TEST_DISK_FAILURE')
        deadline = time.monotonic()+.5
        while time.monotonic()<deadline and not all(s['mode']=='PROTECTED' for s in self.request('/api/v1/state')[1]['data']['members']):
            time.sleep(.01)
        states = self.request('/api/v1/state')[1]['data']['members']
        self.assertTrue(all(m['mode']=='PROTECTED' for m in states))
        self.assertEqual(self.request('/api/health')[0],503)
        with self.assertRaises(ClientError) as raised:
            self.clients[0].control(vx=.1)
        self.assertEqual(raised.exception.payload['error_code'],'AUDIT_UNAVAILABLE')

    def test_audit_failure_does_not_rotate_existing_protection_repeatedly(self):
        app = self.clients[0]
        self.activate(app)
        app.discrete('/api/protect')
        self.fleet.audit._fail('TEST_DISK_FAILURE')
        service = self.fleet.members['sim-drone-1']
        deadline = time.monotonic()+.5
        while time.monotonic()<deadline and not service._audit_fault_applied:
            time.sleep(.01)
        self.assertTrue(service._audit_fault_applied)
        # 等待一次发布，验证控制轮次随后保持稳定且 STOP 仍可执行。
        time.sleep(.06)
        epoch = service.state()['control_epoch']
        time.sleep(.06)
        self.assertEqual(service.state()['control_epoch'],epoch)
        self.assertEqual(self.request('/api/v1/state')[1]['data']['link']['audit'],'UNAVAILABLE')
        app.stop()

    def test_audit_failure_before_execution_preserves_mapped_safety_actions(self):
        self.stop_instance()
        voice_row = dict(client_id='test-voice',token='test-voice-token-for-audit',role='CONTROLLER',
            device_id='sim-drone-1',source='voice')
        self.credentials['credentials'].append(voice_row)
        self.start_instance()
        voice = IndependentClient(voice_row['device_id'],voice_row['token'],self.url,source='voice')
        self.addCleanup(voice.close)
        voice.open_session()
        app = self.clients[0]
        self.activate(app)
        app.control(vx=.1)
        old_session = app.session_id
        service = self.fleet.members[app.device_id]
        execute = service.execute

        def fail_after_admission(item):
            self.fleet.audit._fail('TEST_AUDIT_FAILED_AFTER_ADMISSION')
            return execute(item)

        with patch.object(service,'execute',fail_after_admission):
            with self.assertRaises(ClientError) as error:
                app.open_session()
        self.assertEqual(error.exception.payload['error_code'],'AUDIT_UNAVAILABLE')
        self.assertEqual(service.sessions['app']['id'],old_session)
        protected = voice.discrete('/api/voice-result',command_code='protect',recognized=True,confidence=.95)
        self.assertEqual(protected['mode'],'PROTECTED')
        self.assertEqual(protected['protection_reason'],'AUDIT_UNAVAILABLE')
        self.assertEqual(protected['control_epoch_reason'],'EMERGENCY_VOICE')
        receipt = voice.request('/api/commands/voice/'+protected['command_id'])
        self.assertEqual(receipt['status'],'COMPLETED')

    def test_audit_write_failure_requires_restart_and_fresh_control_session(self):
        app = self.clients[0]
        self.activate(app)
        self.assertEqual(app.control(vx=.1)['mode'],'ACTIVE')
        old_input = dict(app.envelope(),intent_sequence=0,frame='world',axes=[.1,0,0,0])
        fleet_boot = self.fleet.boot_id
        member_boot = self.fleet.members[app.device_id].boot_id
        with patch.object(self.fleet.audit,'_write',side_effect=OSError('模拟磁盘写入失败')):
            self.assertTrue(self.fleet.audit.emit({'event':'TEST_WRITE_FAILURE'}))
            self.fleet.audit.thread.join(2.)
        self.assertTrue(self.fleet.audit.failure.startswith('AUDIT_WRITE_FAILED:'))
        deadline = time.monotonic()+1.
        service = self.fleet.members[app.device_id]
        while service.state()['mode']!='PROTECTED' and time.monotonic()<deadline:
            time.sleep(.01)
        # 底层写入恢复也不解除已锁存的审计故障，原会话可请求停止但不能解除保护。
        self.assertEqual(app.stop()['mode'],'PROTECTED')
        self.assertEqual(app.discrete('/api/protect')['mode'],'PROTECTED')
        with self.assertRaises(ClientError) as raised:
            app.discrete('/api/resume',neutral_confirmed=True)
        self.assertEqual(raised.exception.payload['error_code'],'AUDIT_UNAVAILABLE')
        self.stop_instance()
        self.start_instance()
        self.assertNotEqual(self.fleet.boot_id,fleet_boot)
        self.assertNotEqual(self.fleet.members[app.device_id].boot_id,member_boot)
        status, health = self.request('/api/health')
        self.assertEqual(status,200)
        self.assertEqual(health['data']['boot_id'],self.fleet.boot_id)
        _, fresh = self.request('/api/v1/state')
        self.assertTrue(all(row['mode']=='HOLD' and row['control']['source'] is None and
            row['control']['selected_source'] is None for row in fresh['data']['members']))
        old_input['sent_at_ms'] = time.time()*1000
        status, payload = self.request('/api/v1/devices/sim-drone-1/input',old_input,index=0)
        self.assertEqual(status,409)
        self.assertEqual(payload['error_code'],'STALE_SESSION')
        self.activate(self.clients[0])
        self.assertEqual(self.clients[0].control(vx=.1)['mode'],'ACTIVE')

    def test_expired_and_old_epoch_are_rejected_and_stop_is_idempotent(self):
        app = self.clients[0]; self.activate(app)
        app.control(vx=.1)
        old = app.envelope(); old.update(intent_sequence=0,frame='world',axes=[.1,0,0,0])
        stop = app.envelope(); stop.update(command_id='stop-once')
        path = '/api/v1/devices/sim-drone-1/stop'
        code, first = self.request(path,stop,index=0)
        self.assertEqual(code,200,first)
        epoch = first['data']['control_epoch']
        self.assertNotEqual(epoch,stop['control_epoch'])
        self.assertEqual(first['data']['mode'],'HOLD')
        self.assertIsNone(first['data']['last_applied'])
        # 即使未收到 STOP 应答，也能从只读状态确认新轮次，旧触摸意图不能借新轮次恢复。
        latest = self.request('/api/v1/devices/sim-drone-1/state')[1]['data']
        self.assertEqual((latest['control']['control_epoch'],latest['control']['epoch_reason']),(epoch,'STOP_APP'))
        self.assertEqual(latest['mode'],'HOLD')
        stale = dict(app.envelope(),intent_sequence=app.intent,frame='world',axes=[.1,0,0,0])
        code, payload = self.request('/api/v1/devices/sim-drone-1/input',stale,index=0)
        self.assertEqual((code,payload['error_code']),(409,'STALE_EPOCH'))
        retired = dict(app.envelope(),control_epoch=epoch,intent_sequence=app.intent,frame='world',axes=[.1,0,0,0])
        code, payload = self.request('/api/v1/devices/sim-drone-1/input',retired,index=0)
        self.assertEqual((code,payload['error_code']),(409,'RETIRED_INPUT'))
        code, replay = self.request(path,stop,index=0)
        self.assertEqual(code,200,replay)
        self.assertTrue(replay['data']['idempotent_replay'])
        self.assertEqual(replay['data']['control_epoch'],epoch)
        self.assertEqual(self.request('/api/v1/devices/sim-drone-1/input',old,index=0)[0],409)
        expired = app.envelope(); expired.update(command_id='expired',sent_at_ms=time.time()*1000-1000)
        self.assertEqual(self.request('/api/v1/devices/sim-drone-1/protect',expired,index=0)[1]['error_code'],'EXPIRED_COMMAND')
        status, result = self.request('/api/v1/devices/sim-drone-1/commands/app/stop-once',index=0)
        self.assertEqual(status,200)
        self.assertEqual(result['data']['command_id'],'stop-once')
        self.assertEqual(result['data']['phase'],'COMPLETED')
        self.assertEqual(result['data']['completion_scope'],'SERVICE_APPLIED')
        self.assertEqual(self.request('/api/v1/devices/sim-drone-1/commands/app/stop-once')[0],403)

    def test_discrete_id_cannot_be_reused_for_a_different_safety_action(self):
        app = self.clients[0]
        self.activate(app)
        prefix = '/api/v1/devices/sim-drone-1'
        stop = dict(app.envelope(),command_id='safety-id')
        code, result = self.request(prefix+'/stop',stop,index=0)
        self.assertEqual(code,200)
        code, error = self.request(prefix+'/protect',stop,index=0)
        self.assertEqual(code,409)
        self.assertEqual(error['error_code'],'COMMAND_ID_REUSED')
        self.assertEqual(error['error_detail']['code'],'COMMAND_ID_REUSED')
        self.assertEqual(self.request(prefix+'/state')[1]['data']['mode'],'HOLD')
        # 新 ID 使用当前轮次和序号，保护仍可正常执行。
        protect = dict(app.envelope(),command_id='new-protect',control_epoch=result['data']['control_epoch'])
        code, result = self.request(prefix+'/protect',protect,index=0)
        self.assertEqual(code,200)
        self.assertEqual(result['data']['mode'],'PROTECTED')

    def test_full_regular_history_accepts_fresh_stop_protect_and_disconnect(self):
        pilot, peer = self.clients[:2]
        self.activate(pilot)
        prefix = '/api/v1/devices/'+pilot.device_id
        before = pilot.request('/api/state')
        regular_id = before['recent_commands'][0]['command_id']
        # 用真实离散配置请求填满普通历史，保留配置版本和当前会话的原有语义。
        config_count = COMMAND_HISTORY_LIMIT-4  # 前后各有一次切换和一次归中。
        for version in range(before['config_version'],before['config_version']+config_count):
            pilot.discrete('/api/config',config_version=version,patch={'sensitivity':.9})
        pilot.switch('app'); pilot.neutral(); pilot.begin_intent()
        with self.assertRaises(ClientError) as full:
            pilot.discrete('/api/config',config_version=before['config_version']+config_count,patch={'sensitivity':.8})
        self.assertEqual((full.exception.status,full.exception.payload['error_code']),(503,'COMMAND_HISTORY_FULL'))

        self.activate(peer)
        peer_epoch = peer.control(vy=.1)['control_epoch']
        moving = pilot.control(vx=.1)
        stop = dict(pilot.envelope(),command_id='full-history-stop')
        status, stopped = self.request(prefix+'/stop',stop,index=0)
        self.assertEqual(status,200)
        self.assertEqual(stopped['data']['mode'],'HOLD')
        self.assertNotEqual(stopped['data']['control_epoch'],moving['control_epoch'])
        self.assertEqual(stopped['receipt']['phase'],'COMPLETED')

        pilot.begin_intent()
        moving = pilot.control(vx=.1)
        status, replay = self.request(prefix+'/stop',stop,index=0)
        self.assertEqual(status,200)
        self.assertTrue(replay['data']['idempotent_replay'])
        self.assertEqual(replay['data']['control_epoch'],moving['control_epoch'])
        self.assertEqual(replay['data']['mode'],'ACTIVE')

        expired = dict(pilot.envelope(),command_id='full-history-expired',sent_at_ms=time.time()*1000-1000)
        status, error = self.request(prefix+'/protect',expired,index=0)
        self.assertEqual((status,error['error_code']),(409,'EXPIRED_COMMAND'))
        status, error = self.request(prefix+'/protect',stop,index=0)
        self.assertEqual((status,error['error_code']),(409,'COMMAND_ID_REUSED'))
        fresh = dict(pilot.envelope(),command_id='full-history-protect')
        self.assertEqual(self.request(prefix+'/protect',fresh)[0],403)
        self.assertEqual(self.request(prefix+'/protect',fresh,index=1)[0],403)
        status, protected = self.request(prefix+'/protect',fresh,index=0)
        self.assertEqual(status,200)
        self.assertEqual(protected['data']['mode'],'PROTECTED')
        self.assertEqual(protected['data']['protection_reason'],'EMERGENCY_APP')

        disconnected = dict(pilot.envelope(),command_id='full-history-disconnect',reason='APP_EXIT',
            control_epoch=protected['data']['control_epoch'])
        status, released = self.request(prefix+'/disconnect',disconnected,index=0)
        self.assertEqual(status,200)
        self.assertEqual(released['receipt']['phase'],'COMPLETED')
        self.assertEqual(peer.control(vy=.1)['control_epoch'],peer_epoch)
        for command_id in (regular_id,stop['command_id'],fresh['command_id'],disconnected['command_id']):
            status, payload = self.request(prefix+'/commands/app/'+command_id,index=0)
            self.assertEqual(status,200)
            self.assertEqual(payload['data']['phase'],'COMPLETED')

    def test_safety_receipts_roll_without_dropping_regular_history_or_replaying_evicted_actions(self):
        pilot = self.clients[0]
        self.activate(pilot)
        prefix = '/api/v1/devices/'+pilot.device_id
        regular_id = pilot.request('/api/state')['recent_commands'][0]['command_id']
        first = dict(pilot.envelope(),command_id='oldest-safety-receipt')
        pilot.epoch = pilot.request('/api/stop',first)['control_epoch']
        for _ in range(SAFETY_COMMAND_HISTORY_LIMIT-1):
            pilot.discrete('/api/stop')
        first_query = prefix+'/commands/app/'+first['command_id']
        self.assertEqual(self.request(first_query,index=0)[0],200)

        expired = dict(pilot.envelope(),command_id='rejected-safety',sent_at_ms=time.time()*1000-1000)
        status, error = self.request(prefix+'/stop',expired,index=0)
        self.assertEqual((status,error['error_code']),(409,'EXPIRED_COMMAND'))
        self.assertEqual(self.request(first_query,index=0)[0],200)

        # 连续超过两倍容量后，最新安全动作仍能执行并查询，普通记录仍在原会话内。
        for _ in range(SAFETY_COMMAND_HISTORY_LIMIT+2):
            recent = pilot.discrete('/api/stop')
        status, error = self.request(first_query,index=0)
        self.assertEqual((status,error['error_code']),(404,'COMMAND_NOT_FOUND'))
        self.assertEqual(self.request(prefix+'/commands/app/'+regular_id,index=0)[0],200)
        self.assertEqual(self.request(prefix+'/commands/app/'+recent['command_id'],index=0)[0],200)
        service = self.fleet.members[pilot.device_id]
        self.assertEqual(len(service.records),2+SAFETY_COMMAND_HISTORY_LIMIT)
        self.assertEqual(len(service.command_view),len(service.records))

        pilot.begin_intent()
        moving = pilot.control(vx=.1)
        # 原序号已失效，即使重新填入当前时刻和轮次，已换出的原报文也不能重新执行。
        stale = dict(first,control_epoch=moving['control_epoch'],sent_at_ms=time.time()*1000)
        status, error = self.request(prefix+'/stop',stale,index=0)
        self.assertEqual((status,error['error_code']),(409,'STALE_SEQUENCE'))
        self.assertEqual(pilot.request('/api/state')['mode'],'ACTIVE')

        reopened = pilot.open_session()
        self.assertEqual(reopened['control_epoch_reason'],'SESSION_REPLACED')
        self.assertEqual(self.request(prefix+'/commands/app/'+recent['command_id'],index=0)[0],404)
        self.assertFalse(service.records)

    def test_full_member_queue_still_executes_safety_and_fences_queued_motion(self):
        pilot, peer = self.clients[:2]
        self.activate(pilot); self.activate(peer)
        path = '/api/v1/devices/'+pilot.device_id
        service = self.fleet.members[pilot.device_id]
        for action,mode in (('stop','HOLD'),('protect','PROTECTED')):
            with self.subTest(action=action):
                pilot.begin_intent()
                moving = pilot.control(vx=.1)
                peer_before = peer.control(vy=.1)['control_epoch']
                queued = dict(pilot.envelope(),intent_sequence=pilot.intent,frame='world',axes=[.1,0,0,0])
                overflow = dict(pilot.envelope(),intent_sequence=pilot.intent,frame='world',axes=[.1,0,0,0])
                entered, release = threading.Event(), threading.Event()
                execute = service.execute
                safety_admitted = threading.Event()
                enqueue = service.commands.put_nowait

                def pause_consumer(item):
                    if item['path']=='/api/heartbeat':
                        entered.set()
                        release.wait(1.)
                    return execute(item)

                def track_admission(entry):
                    enqueue(entry)
                    if entry[2]['path']=='/api/'+action:
                        safety_admitted.set()

                with (patch.object(service,'execute',pause_consumer),
                      patch.object(service.commands,'put_nowait',track_admission),
                      concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool):
                    current = pool.submit(self.request,path+'/heartbeat',pilot.envelope(),0)
                    try:
                        self.assertTrue(entered.wait(1.))
                        # 固定填入 128 个已排队普通报文，避免依赖系统能否同时建立 128 个连接。
                        for _ in range(COMMAND_QUEUE_LIMIT):
                            item = dict(path='/api/input',source='app',body=queued,received=time.monotonic(),
                                epoch=time.time()*1000,done=threading.Event(),claim_lock=threading.Lock(),queue_state='QUEUED')
                            service.commands.put_nowait((2,next(service.queue_counter),item))
                        last = item
                        status,error = self.request(path+'/input',overflow,index=0)
                        self.assertEqual((status,error['error_code']),(503,'QUEUE_FULL'))
                        safety = dict(pilot.envelope(),command_id='queued-'+action)
                        pending = pool.submit(self.request,path+'/'+action,safety,0)
                        self.assertTrue(safety_admitted.wait(.35))
                        self.assertEqual(service.commands.qsize(),COMMAND_QUEUE_LIMIT+1)
                    finally:
                        release.set()
                    self.assertEqual(current.result(timeout=3)[0],200)
                    status,payload = pending.result(timeout=3)
                self.assertEqual(status,200)
                self.assertEqual(payload['data']['mode'],mode)
                self.assertEqual(payload['receipt']['phase'],'COMPLETED')
                self.assertNotEqual(payload['data']['control_epoch'],moving['control_epoch'])
                self.assertTrue(last['done'].wait(.5))
                self.assertIn(last['error'].code,('STALE_SEQUENCE','STALE_EPOCH'))
                self.assertEqual(pilot.request('/api/state')['mode'],mode)
                self.assertEqual(peer.control(vy=.1)['control_epoch'],peer_before)

    def test_sse_contains_all_three_members_for_observer(self):
        connection = http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
        try:
            connection.request('GET','/api/v1/events',headers={'Authorization':'Bearer '+self.credentials['credentials'][3]['token']})
            response = connection.getresponse(); self.assertEqual(response.status,200)
            self.assertEqual(response.readline().strip(),b'event: state')
            line = response.readline().decode('utf-8')
            data = json.loads(line[len('data: '):])
            self.assertEqual(len(data['members']),3)
            self.assertIsNone(data['self_device_id'])
            self.assertTrue(all(not m['control']['write_permitted'] for m in data['members']))
        finally:
            connection.close()


class LogsQueryTests(unittest.TestCase):
    """日志查询与导出接口：过滤、分页、导出内容与权限边界。"""

    def setUp(self):
        self.directory = temporary_directory()
        self.config = json.loads(Path('cluster_config.json').read_text('utf-8'))
        self.credentials = json.loads(Path('independent_credentials.json').read_text('utf-8'))
        # 覆盖命令行 --results 指定相对路径的正常用法。
        relative_results = Path(self.directory.name).relative_to(Path.cwd())
        self.fleet = IndependentFleet(self.config,self.credentials,relative_results)
        self.server = make_independent_server(self.fleet,port=0)
        self.thread = threading.Thread(target=lambda:self.server.serve_forever(poll_interval=.02),daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.fleet.close()
        self.directory.cleanup()

    def request(self, path, body=None, index=3, raw=False):
        connection = http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
        try:
            headers = {'Authorization':'Bearer '+self.credentials['credentials'][index]['token'],
                'Content-Type':'application/json'}
            connection.request('GET' if body is None else 'POST',path,
                None if body is None else json.dumps(body).encode(),headers)
            response = connection.getresponse()
            payload = response.read()
            if raw:
                return response.status,payload,response.headers
            return response.status,json.loads(payload)
        finally:
            connection.close()

    def wait_for_events(self, minimum):
        # 审计异步落盘（约100ms刷新一次），轮询等待，避免时序抖动造成假失败。
        deadline = time.time()+3
        last = None
        while time.time() < deadline:
            code, result = self.request('/api/v1/logs')
            if code==200 and result['data']['total'] >= minimum:
                return result['data']
            last = (code,result); time.sleep(.05)
        self.fail('审计事件超时未落盘: 期望至少%d, 实际=%s' % (minimum,last))

    def test_all_viewers_can_query_and_startup_events_are_visible(self):
        baseline = self.wait_for_events(3)
        for index in range(4):
            code, result = self.request('/api/v1/logs',index=index)
            self.assertEqual(code,200,result)
            self.assertEqual(result['data']['total'],baseline['total'])
        events = baseline['events']
        self.assertEqual(len(events),min(baseline['limit'],baseline['total']))
        self.assertTrue(all(row['event']=='MEMBER_STARTED' for row in events))
        self.assertTrue(all('server_time_ms' in row and 'device_id' in row for row in events))
        files = baseline['files']
        self.assertTrue(any(row['file']=='fleet_events.jsonl' for row in files))
        # 路由须向所有端广播，前端按 capabilities 发现入口。
        code, result = self.request('/api/v1/capabilities',index=3)
        self.assertEqual(code,200,result)
        routes = result['data']['routes']
        self.assertEqual(routes['logs'],'/api/v1/logs')
        self.assertEqual(routes['logs_export'],'/api/v1/logs/export')
        self.assertTrue(result['data']['capabilities']['logs_query'])

    def test_device_event_and_time_filters_match_only_requested_rows(self):
        now = int(time.time()*1000)
        self.fleet.audit.emit(dict(event='COMMAND_ACK',device_id='sim-drone-2',path='/api/input',
            sequence=1,command_id='c1',server_time_ms=now))
        self.fleet.audit.emit(dict(event='COMMAND_ACK',device_id='sim-drone-1',path='/api/input',
            sequence=2,command_id='c2',server_time_ms=now+1))
        self.wait_for_events(5)
        code, result = self.request('/api/v1/logs?device_id=sim-drone-2')
        self.assertEqual(code,200,result)
        self.assertTrue(all(row.get('device_id')=='sim-drone-2' for row in result['data']['events']))
        code, result = self.request('/api/v1/logs?event=COMMAND_ACK')
        self.assertEqual(code,200,result)
        self.assertEqual(result['data']['total'],2)
        self.assertTrue(all(row['event']=='COMMAND_ACK' for row in result['data']['events']))
        code, result = self.request('/api/v1/logs?event=COMMAND_ACK&since_ms=%d&until_ms=%d' % (now,now))
        self.assertEqual(code,200,result)
        self.assertEqual(result['data']['total'],1)
        self.assertEqual(result['data']['events'][0]['command_id'],'c1')

    def test_ack_audit_preserves_only_validated_motion_fields_and_exports_them(self):
        credential = self.credentials['credentials'][0]
        pilot = IndependentClient(credential['device_id'],credential['token'],
            f'http://127.0.0.1:{self.server.server_port}')
        self.addCleanup(pilot.close)
        pilot.open_session(); pilot.switch('app'); pilot.neutral(); pilot.begin_intent()
        axes = [0.,-.5,0.,.1]
        body = dict(pilot.envelope(),axes=axes,frame='world',intent_sequence=pilot.intent,
            token='not-an-audit-field')
        self.assertEqual(pilot.request('/api/input',body)['mode'],'ACTIVE')
        with self.assertRaises(ClientError) as rejected:
            pilot.request('/api/input',dict(body,axes=[0.,.5,0.,0.]))
        self.assertEqual(rejected.exception.payload['error_code'],'STALE_SEQUENCE')
        pilot.control(vx=.12,frame='heading')
        pilot.heartbeat()

        # 观察端能查到已受理的输入，重复序号不会生成第二条成功记录。
        baseline = self.wait_for_events(self.fleet.audit.status()['accepted'])
        events = [row for row in baseline['events'] if row['event']=='COMMAND_ACK']
        inputs = [row for row in events if row['path']=='/api/input']
        self.assertEqual(len(inputs),1)
        motion = inputs[0]
        for key in ('axes','frame','control_epoch','intent_sequence','ttl_ms','sent_at_ms'):
            self.assertEqual(motion[key],body[key])
        self.assertFalse({'token','session_id'} & motion.keys())
        velocity = next(row for row in events if row['path']=='/api/control')
        self.assertEqual((velocity['vx_m_s'],velocity['frame']),(.12,'heading'))
        self.assertNotIn('axes',velocity)
        heartbeat = next(row for row in events if row['path']=='/api/heartbeat')
        self.assertNotIn('frame',heartbeat)

        for fmt in ('jsonl','csv'):
            with self.subTest(format=fmt):
                path = '/api/v1/logs/export?format='+fmt+'&snapshot='+baseline['snapshot']
                code, payload, _ = self.request(path,raw=True)
                self.assertEqual(code,200)
                lines = payload.decode('utf-8-sig').splitlines()
                exported = [json.loads(line) for line in lines] if fmt=='jsonl' else list(csv.DictReader(lines))
                row = next(row for row in exported if row.get('path')=='/api/input')
                self.assertEqual((row if fmt=='jsonl' else json.loads(row['detail']))['axes'],axes)

    def test_pagination_offset_limit_and_cap(self):
        baseline = self.wait_for_events(3)
        code, result = self.request('/api/v1/logs?limit=2&offset=1')
        self.assertEqual(code,200,result)
        data = result['data']
        self.assertEqual(data['limit'],2); self.assertEqual(data['offset'],1)
        self.assertEqual(data['total'],baseline['total'])
        self.assertEqual([row['device_id'] for row in data['events']],
            [row['device_id'] for row in baseline['events'][1:3]])
        code, result = self.request('/api/v1/logs?limit=99999')
        self.assertEqual(code,200,result)
        self.assertEqual(result['data']['limit'],2000)
        code, result = self.request('/api/v1/logs?offset=9999')
        self.assertEqual(code,200,result)
        self.assertEqual(result['data']['events'],[])

    def test_unknown_device_and_bad_numbers_are_rejected(self):
        for path in ('/api/v1/logs?device_id=unknown','/api/v1/logs?limit=abc',
                '/api/v1/logs?since_ms=-5','/api/v1/logs?since_ms=200&until_ms=100'):
            code, result = self.request(path)
            self.assertEqual(code,400,(path,result))
            self.assertEqual(result['error_code'],'INVALID_ARGUMENT')

    def test_export_jsonl_and_csv_attachments_and_content(self):
        total = self.wait_for_events(3)['total']
        code, payload, headers = self.request('/api/v1/logs/export',raw=True)
        self.assertEqual(code,200,payload[:200])
        self.assertEqual(headers.get('Content-Type'),'application/x-ndjson; charset=utf-8')
        self.assertIn('attachment;',headers.get('Content-Disposition',''))
        self.assertIn('.jsonl',headers.get('Content-Disposition'))
        lines = [row for row in payload.decode('utf-8').splitlines() if row.strip()]
        self.assertEqual(len(lines),total)
        self.assertTrue(all(json.loads(row)['event']=='MEMBER_STARTED' for row in lines))
        code, payload, headers = self.request('/api/v1/logs/export?format=csv',raw=True)
        self.assertEqual(code,200,payload[:200])
        self.assertEqual(headers.get('Content-Type'),'text/csv; charset=utf-8')
        text = payload.decode('utf-8')
        self.assertTrue(text.startswith(chr(0xfeff)))
        rows = [row for row in text.splitlines() if row.strip()]
        self.assertEqual(len(rows),total+1)
        self.assertIn('server_time_ms',rows[0]); self.assertIn('detail',rows[0])
        code, result = self.request('/api/v1/logs/export?format=xml')
        self.assertEqual(code,400,result)
        self.assertEqual(result['error_code'],'INVALID_ARGUMENT')

    def test_write_to_logs_route_is_rejected_for_all_roles(self):
        code, result = self.request('/api/v1/logs',body={'query':1},index=3)
        self.assertEqual(code,403,result)
        self.assertEqual(result['error_code'],'READ_ONLY')
        code, result = self.request('/api/v1/logs',body={'query':1},index=0)
        self.assertEqual(code,404,result)


if __name__=='__main__':
    unittest.main(verbosity=2)
