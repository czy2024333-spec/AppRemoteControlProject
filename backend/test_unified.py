"""统一入口真实HTTP/SSE回归；只使用本机临时端口和独立仿真实例。"""
import copy
import http.client
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from app_server import DEMO_TOKENS
from client import SourceClient, ClientError
from cluster_client import ClusterClient
from cluster_manager import ClusterManager
from unified_server import make_unified_server
from unified_contract import device_state, receipt, error_detail


class ContractTests(unittest.TestCase):
    def test_unknown_status_is_received_and_applied_is_only_service_scope(self):
        self.assertEqual(receipt({})['phase'],'RECEIVED')
        self.assertEqual(receipt(dict(ack=dict(status='APPLIED')))['completion_scope'],'SERVICE_APPLIED')
        self.assertEqual(receipt(dict(command_status='WAITING_NEUTRAL'))['phase'],'WAITING_NEUTRAL')
        self.assertIsNone(receipt(dict(command_status='EXECUTING'))['completion_scope'])

    def test_unknown_outcome_is_query_same_id_not_motion_retry(self):
        result=error_detail('ACK_OUTCOME_UNKNOWN','未知',command_id='same-id')
        self.assertEqual((result['code'],result['retry_policy']),('OUTCOME_UNKNOWN','QUERY_SAME_COMMAND_ID'))
        self.assertEqual(result['command_id'],'same-id')


class UnifiedTests(unittest.TestCase):
    def setUp(self):
        Path('results/unified_tests').mkdir(parents=True,exist_ok=True)
        self.directory=tempfile.TemporaryDirectory(dir='results/unified_tests')
        self.config=json.loads(Path('cluster_config.json').read_text(encoding='utf-8'))
        self.manager=ClusterManager(self.config,self.directory.name)
        self.server=make_unified_server(self.manager,port=0,allowed_origins=['http://localhost:5173'])
        self.thread=threading.Thread(target=lambda:self.server.serve_forever(poll_interval=.02),daemon=True)
        self.thread.start()
        self.url=f'http://127.0.0.1:{self.server.server_port}'
        self.app=SourceClient('app',self.url)

    def tearDown(self):
        self.app.close(); self.server.shutdown(); self.server.server_close(); self.manager.close()
        self.directory.cleanup()

    def request(self,path,body=None,source='app',headers=None):
        connection=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
        try:
            auth={'Authorization':'Bearer '+DEMO_TOKENS[source],'Content-Type':'application/json'}
            auth.update(headers or {})
            connection.request('GET' if body is None else 'POST',path,
                None if body is None else json.dumps(body).encode(),auth)
            response=connection.getresponse()
            return response.status,json.loads(response.read())
        finally: connection.close()

    def activate(self):
        self.app.open_session(); self.app.switch('app'); self.app.neutral(); self.app.begin_intent()

    def test_one_address_observation_never_selects_or_opens_sessions(self):
        for path in ('/api/state','/api/cluster/state','/api/v1/capabilities','/api/v1/devices','/api/v1/state'):
            code,result=self.request(path); self.assertEqual(code,200,result)
        state=self.request('/api/v1/state')[1]['data']
        self.assertEqual(len(state['members']),3)
        self.assertEqual(len({s['boot_id'] for s in state['members']}),3)
        self.assertIsNone(self.manager.selection)
        self.assertTrue(all(not e['service'].sessions for e in self.manager.members.values()))

    def test_missing_data_and_simulated_measurement_are_truthful(self):
        data=self.request('/api/v1/devices/sim-drone-1/state')[1]['data']
        self.assertEqual(data['position']['source'],'SIM_TRUTH')
        self.assertEqual(data['position']['unit'],'m')
        self.assertIsNone(data['battery']['value']); self.assertIsNone(data['gnss']['value'])
        self.assertEqual(data['battery']['validity'],'NOT_CONNECTED')
        self.assertEqual(data['sensors']['local_position_velocity']['source'],'SIM_MEASUREMENT')
        self.assertIsNone(data['control']['has_control'])
        self.assertEqual(data['video']['status'],'NOT_CONNECTED')
        descriptors=self.request('/api/v1/devices')[1]['data']['devices']
        self.assertTrue(all(not d['capabilities']['takeoff'] and not d['capabilities']['ue_video'] for d in descriptors))
        self.assertTrue(all(d['capabilities']['default_motion_frame']=='WORLD' for d in descriptors))
        self.assertEqual([d['control_limits']['sensitivity'] for d in descriptors],[1.,.8,.9])

    def test_invalid_vector_and_offline_state_are_not_fresh_zeroes(self):
        raw=self.manager.member_state('sim-drone-1')
        raw['position_m']=None
        self.assertIsNone(device_state(raw)['position']['value'])
        self.assertEqual(device_state(raw)['position']['validity'],'INVALID')
        raw=self.manager.member_state('sim-drone-1'); raw['online']=False
        self.assertEqual(device_state(raw)['position']['validity'],'STALE')

    def test_development_identity_and_authorization_are_explicit(self):
        data=self.request('/api/v1/capabilities',source='ring')[1]['data']
        self.assertEqual(data['authentication']['mode'],'DEVELOPMENT_SOURCE_TOKEN')
        self.assertIsNone(data['authentication']['role']); self.assertIsNone(data['authentication']['user_id'])
        self.assertFalse(data['capabilities']['formal_user_roles'])
        code,value=self.request('/api/v1/state',headers={'Authorization':'Bearer wrong'})
        self.assertEqual(code,401); self.assertEqual(value['error_detail']['code'],'AUTH_REQUIRED')
        code,value=self.request('/api/session',{},headers={'Origin':'http://untrusted:5173'})
        self.assertEqual(code,403); self.assertIsNone(self.manager.selection)

    def test_old_client_moves_only_primary_on_same_address(self):
        self.activate()
        for _ in range(7): self.app.control(vy=.25,frame='world'); time.sleep(.05)
        state=self.app.request('/api/state')
        self.assertTrue(state['boot_id']); self.assertGreater(state['position_m'][1],.005)
        self.assertEqual(self.request('/api/v1/devices/sim-drone-1/state')[1]['data']['control']['selected_source'],'APP')
        members={s['device_id']:s for s in self.app.request('/api/cluster/state')['members']}
        self.assertAlmostEqual(members['sim-drone-2']['position_m'][0],2.,places=5)
        self.assertAlmostEqual(members['sim-drone-3']['position_m'][1],2.,places=5)
        self.app.stop()

    def test_group_selection_and_old_single_client_are_mutually_exclusive(self):
        cluster=ClusterClient(self.url)
        try:
            cluster.open(); cluster.select('group',group_id='alpha')
            code,value=self.request('/api/session',{})
            self.assertEqual(code,409); self.assertEqual(value['error_code'],'CONTROL_CONFLICT')
            self.assertEqual(self.manager.selection['target_kind'],'group')
            self.assertFalse(self.manager.members['sim-drone-1']['service'].sessions)
        finally: cluster.close()

    def test_target_change_rejects_old_legacy_motion(self):
        self.activate()
        packet=self.app.envelope(); packet.update(intent_sequence=self.app.intent,frame='world',axes=[.2,0,0,0])
        cluster=ClusterClient(self.url)
        try:
            cluster.open(); cluster.select('cluster',takeover=True)
            code,value=self.request('/api/input',packet)
            self.assertEqual((code,value['error_code']),(409,'CONTROL_CONFLICT'))
            self.assertTrue(all(s['active_source'] is None for s in self.manager.state()['members']))
        finally: cluster.close()

    def test_draft_intent_cannot_be_posted_as_a_new_write_protocol(self):
        code,result=self.request('/api/v1/input',dict(source='APP',axes_normalized=dict(x=1,y=0,z=0,yaw=0)))
        self.assertEqual(code,501); self.assertEqual(result['error_detail']['code'],'NOT_SUPPORTED')
        self.assertIsNone(self.manager.selection)

    def test_discrete_receipt_query_and_idempotence(self):
        self.activate()
        packet=self.app.envelope(); packet.update(command_id='protect-test')
        code,first=self.request('/api/protect',packet)
        self.assertEqual(code,200,first)
        self.assertEqual(first['receipt']['phase'],'COMPLETED')
        self.assertEqual(first['receipt']['completion_scope'],'SERVICE_APPLIED')
        code,replay=self.request('/api/protect',packet)
        self.assertEqual(code,200,replay); self.assertTrue(replay['data']['idempotent_replay'])
        code,result=self.request('/api/v1/devices/sim-drone-1/commands/app/protect-test')
        self.assertEqual(code,200,result); self.assertEqual(result['data']['raw_code'],'COMPLETED')
        self.assertEqual(self.request('/api/v1/devices/sim-drone-1/commands/app/protect-test',source='ring')[0],403)

    def test_partial_batch_never_reports_all_completed(self):
        cluster=ClusterClient(self.url)
        try:
            cluster.open(); cluster.select()
            cluster.batch('/api/session'); cluster.batch('/api/switch',target_source='app',user_initiated=True)
            cluster.batch('/api/neutral',axes=[0,0,0,0]); cluster.begin_intent()
            rows=[]
            for i,name in enumerate(cluster.selection['member_ids']):
                body=cluster.member_body(name,'/api/control',dict(intent_sequence=0,frame='world',
                    vx_m_s=5. if i==1 else .2,vy_m_s=0.,vz_m_s=0.,yaw_rate_rad_s=0.))
                rows.append(dict(device_id=name,path='/api/control',body=body))
            code,result=self.request('/api/cluster/dispatch',cluster.envelope(
                selection_id=cluster.selection['selection_id'],commands=rows))
            self.assertEqual(code,200,result)
            self.assertEqual(result['data']['status'],'PARTIAL')
            self.assertEqual((result['receipt']['phase'],result['receipt']['succeeded'],result['receipt']['failed']),('PARTIAL',2,1))
            self.assertEqual(sum(r['phase']=='REJECTED' for r in result['receipt']['members']),1)
        finally: cluster.close()

    def test_expired_protection_does_not_bypass_ttl(self):
        self.activate()
        packet=self.app.envelope(); packet.update(command_id='expired',sent_at_ms=time.time()*1000-1000)
        code,result=self.request('/api/protect',packet)
        self.assertEqual((code,result['error_code']),(409,'EXPIRED_COMMAND'))
        self.assertNotEqual(self.app.request('/api/state')['mode'],'PROTECTED')

    def test_safety_bypasses_slow_normal_manager_lock(self):
        self.activate()
        service=self.manager.members['sim-drone-1']['service']
        original=service.submit; entered=threading.Event(); release=threading.Event(); outcomes=[]
        def slow(path,body,source):
            if path=='/api/input': entered.set(); release.wait(1.5)
            return original(path,body,source)
        packet=self.app.envelope(); packet.update(intent_sequence=0,frame='world',axes=[.2,0,0,0])
        protection=self.app.envelope(); protection.update(command_id='fast-protect')
        with patch.object(service,'submit',slow):
            worker=threading.Thread(target=lambda:outcomes.append(self.request('/api/input',packet)))
            worker.start()
            try:
                self.assertTrue(entered.wait(1))
                start=time.monotonic(); code,state=self.request('/api/state')
                self.assertEqual(code,200,state)
                self.assertLess(time.monotonic()-start,.5,'保护前读取epoch同样不能被普通请求阻塞')
                start=time.monotonic(); code,value=self.request('/api/protect',protection)
                self.assertEqual(code,200,value)
                self.assertLess(time.monotonic()-start,.5,'保护不能等待1.5秒的普通请求')
                self.assertEqual(value['data']['mode'],'PROTECTED')
            finally: release.set(); worker.join(3)
        self.assertNotEqual(outcomes[0][0],200)
        self.assertIsNone(service.state()['active_source'])

    def test_sse_state_matches_read_model_without_control_side_effect(self):
        connection=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
        try:
            connection.request('GET','/api/v1/events',headers={'Authorization':'Bearer '+DEMO_TOKENS['app']})
            response=connection.getresponse(); self.assertEqual(response.status,200)
            frames=[]
            while len(frames)<2:
                line=response.fp.readline().decode()
                if line.startswith('data: '): frames.append(json.loads(line[6:]))
            self.assertEqual(len(frames[0]['members']),3)
            self.assertGreater(frames[1]['members'][0]['sequence'],frames[0]['members'][0]['sequence'])
            self.assertIsNone(self.manager.selection)
        finally: connection.close()

    def test_primary_removal_never_silently_selects_another_aircraft(self):
        cluster=ClusterClient(self.url)
        try:
            cluster.open(); cluster.manage('/api/cluster/unregister',device_id='sim-drone-1')
            code,result=self.request('/api/state')
            self.assertEqual(code,404); self.assertEqual(result['error_code'],'MEMBER_NOT_FOUND')
            self.assertEqual(len(self.request('/api/v1/devices')[1]['data']['devices']),2)
        finally: cluster.close()


if __name__=='__main__': unittest.main()
