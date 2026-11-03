"""三机独立控制、共享观察及权限隔离的真实 HTTP 回归。"""
import concurrent.futures
import copy
import http.client
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from async_audit import AsyncAudit
from client import ClientError
from independent_client import IndependentClient
from independent_fleet import IndependentFleet, validate_credentials
from independent_server import make_independent_server


def temporary_directory():
    # 测试只在项目 results 内创建和清理临时实例，不依赖系统 TEMP 的写权限。
    base = Path(__file__).resolve().parent/'results/independent_tests'
    base.mkdir(parents=True,exist_ok=True)
    folder = tempfile.TemporaryDirectory(dir=base)
    assert Path(folder.name).resolve().is_relative_to(base.resolve())
    return folder


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
        self.fleet = IndependentFleet(self.config,self.credentials,self.directory.name)
        self.server = make_independent_server(self.fleet,port=0,allowed_origins=['http://localhost:5173'])
        self.thread = threading.Thread(target=lambda:self.server.serve_forever(poll_interval=.02),daemon=True)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        self.clients = [IndependentClient(row['device_id'],row['token'],self.url)
            for row in self.credentials['credentials'][:3]]

    def tearDown(self):
        for client in self.clients:
            client.close()
        self.server.shutdown(); self.server.server_close(); self.fleet.close()
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

    def test_expired_and_old_epoch_are_rejected_and_stop_is_idempotent(self):
        app = self.clients[0]; self.activate(app)
        old = app.envelope(); old.update(intent_sequence=0,frame='world',axes=[.1,0,0,0])
        stop = app.envelope(); stop.update(command_id='stop-once')
        path = '/api/v1/devices/sim-drone-1/stop'
        code, first = self.request(path,stop,index=0)
        self.assertEqual(code,200,first)
        code, replay = self.request(path,stop,index=0)
        self.assertEqual(code,200,replay)
        self.assertTrue(replay['data']['idempotent_replay'])
        self.assertEqual(self.request('/api/v1/devices/sim-drone-1/input',old,index=0)[0],409)
        expired = app.envelope(); expired.update(command_id='expired',sent_at_ms=time.time()*1000-1000)
        self.assertEqual(self.request('/api/v1/devices/sim-drone-1/protect',expired,index=0)[1]['error_code'],'EXPIRED_COMMAND')
        self.assertEqual(self.request('/api/v1/devices/sim-drone-1/commands/app/stop-once',index=0)[0],200)
        self.assertEqual(self.request('/api/v1/devices/sim-drone-1/commands/app/stop-once')[0],403)

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


if __name__=='__main__':
    unittest.main(verbosity=2)
