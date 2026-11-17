"""v0.7网络、生命周期、传感器与会话恢复回归。"""
import copy
import http.client
import json
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from app_server import SimulationService, make_server, APIError, DEMO_TOKENS
from simulation_core import SimulationCore
from client import SourceClient, ClientError
from cluster_member import ManagedMember
from cluster_manager import ClusterManager
from cluster_server import make_cluster_server
from cluster_client import ClusterClient
from config_validation import validate_cluster_config
from http_transport import strict_loads, validate_origins
from acceptance_report import report
import run_backend
from runtime_info import (COMMAND_HISTORY_LIMIT, DEMO_INDEPENDENT_TOKENS, INDEPENDENT_AUDIT_FILENAME,
    SAFETY_COMMAND_HISTORY_LIMIT)


class DoctorTests(unittest.TestCase):
    def test_old_python_stops_before_sleep_and_dependency_checks(self):
        with patch.object(run_backend.sys,'version_info',(3,10,9)), \
                patch.object(run_backend,'short_sleep_probe') as probe, \
                patch.object(run_backend.importlib,'import_module') as load:
            result = run_backend.diagnose('single')
        self.assertFalse(result['healthy'])
        self.assertEqual(result['checks'][0]['minimum_version'],'3.11')
        probe.assert_not_called()
        load.assert_not_called()

    def test_short_sleep_probe_reports_the_observed_median(self):
        readings = []
        for index in range(run_backend.SLEEP_SAMPLES):
            start = index*10_000_000
            readings.extend((start,start+index*100_000+2_000_000))
        with patch.object(run_backend.time,'perf_counter_ns',side_effect=readings), \
                patch.object(run_backend.time,'sleep') as sleep:
            result = run_backend.short_sleep_probe()
        self.assertEqual(result['samples'],31)
        self.assertAlmostEqual(result['median_ms'],3.5)
        self.assertEqual(sleep.call_count,31)
        sleep.assert_called_with(.002)

    def test_slow_sleep_is_an_advisory_warning(self):
        slow = dict(item='short_sleep',ok=True,median_ms=8.)
        with patch.object(run_backend,'short_sleep_probe',return_value=slow):
            result = run_backend.diagnose('single')
        self.assertTrue(result['healthy'])
        self.assertIn('SLEEP_LATENCY_HIGH',{row['code'] for row in result['warnings']})

    def test_public_tokens_warn_on_all_interfaces_without_leaking_values(self):
        self.assertEqual(DEMO_INDEPENDENT_TOKENS,{row['token'] for row in
            json.loads(Path('independent_credentials.json').read_text('utf-8'))['credentials']})
        exposed = run_backend.diagnose(host='0.0.0.0')
        warning = next(row for row in exposed['warnings'] if row['code']=='PUBLIC_TOKENS_ON_ALL_INTERFACES')
        self.assertTrue(exposed['healthy'])
        self.assertEqual(warning['public_sample_token_count'],4)
        self.assertEqual(exposed['command_history']['per_source_session_limit'],COMMAND_HISTORY_LIMIT)
        self.assertEqual(exposed['command_history']['safety_per_source_session_limit'],SAFETY_COMMAND_HISTORY_LIMIT)
        self.assertFalse(any(token in json.dumps(exposed) for token in DEMO_INDEPENDENT_TOKENS))
        local = run_backend.diagnose(host='127.0.0.1')
        self.assertNotIn(warning['code'],{row['code'] for row in local['warnings']})

    def test_custom_file_warns_only_for_public_tokens_actually_used(self):
        base = Path('results/upgrade_tests').resolve()
        base.mkdir(parents=True,exist_ok=True)
        value = json.loads(Path('independent_credentials.json').read_text('utf-8'))
        with tempfile.TemporaryDirectory(dir=base) as folder:
            directory = Path(folder).resolve()
            self.assertTrue(directory.is_relative_to(base))
            path = directory/'credentials.json'
            for row in value['credentials'][:-1]:
                row['token'] = uuid.uuid4().hex
            path.write_text(json.dumps(value),encoding='utf-8')
            mixed = run_backend.diagnose(credentials_path=path,host='0.0.0.0')
            warning = next(row for row in mixed['warnings'] if row['code']=='PUBLIC_TOKENS_ON_ALL_INTERFACES')
            self.assertEqual(warning['public_sample_token_count'],1)
            value['credentials'][-1]['token'] = uuid.uuid4().hex
            path.write_text(json.dumps(value),encoding='utf-8')
            private = run_backend.diagnose(credentials_path=path,host='0.0.0.0')
            self.assertTrue(private['healthy'])
            self.assertNotIn(warning['code'],{row['code'] for row in private['warnings']})
            self.assertFalse(any(row['token'] in json.dumps(private) for row in value['credentials']))

    def test_audit_storage_probe_reads_existing_size_and_leaves_new_path_uncreated(self):
        base = Path('results/upgrade_tests').resolve()
        base.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as folder:
            directory = Path(folder).resolve()
            self.assertTrue(directory.is_relative_to(base))
            future = directory/'nested'/'new-run'
            result = run_backend.diagnose(results_path=future)['audit_storage']
            self.assertTrue(result['probe_ok'])
            self.assertEqual(result['audit_path'],str(future/INDEPENDENT_AUDIT_FILENAME))
            self.assertEqual(result['existing_audit_bytes'],0)
            self.assertFalse(future.exists())
            existing = directory/INDEPENDENT_AUDIT_FILENAME
            existing.write_bytes(b'{"sample":true}\n')
            self.assertEqual(run_backend.audit_storage(directory)['existing_audit_bytes'],existing.stat().st_size)

    def test_legacy_launch_token_overrides_are_checked(self):
        result = run_backend.diagnose('single',source_tokens={'app':'too-short'})
        self.assertFalse(result['healthy'])
        self.assertEqual(result['checks'][-1]['item'],'configuration')


class UpgradePureTests(unittest.TestCase):
    def test_strict_json_duplicate_nonfinite_and_overflow(self):
        for text in ('{"x":1,"x":2}','{"x":NaN}','{"x":Infinity}','{"x":1e309}'):
            with self.assertRaises(ValueError): strict_loads(text)

    def test_config_validation_has_no_side_effects(self):
        config=json.loads(Path('cluster_config.json').read_text(encoding='utf-8'))
        for key,value in [('groups',{'alpha':['missing']}),('members',[]),('acceptance',{})]:
            changed=copy.deepcopy(config); changed[key]=value
            with self.assertRaises(ValueError): validate_cluster_config(changed)
        changed=copy.deepcopy(config); changed['members'][0]['control']=[]
        with self.assertRaises(ValueError): validate_cluster_config(changed)

    def test_origins_are_exact_and_do_not_allow_wildcards(self):
        self.assertIn('http://localhost:5173',validate_origins(['http://localhost:5173']))
        for origin in ('*','http://*.example.com','null','http://localhost:5173/','https://user:pw@host','http://host:wrong'):
            with self.assertRaises(ValueError): validate_origins([origin])

    def test_missing_hardware_cases_cannot_disappear_from_report(self):
        result=report(dict(ring={},voice={},evidence_type='REAL_HARDWARE'))
        self.assertTrue(any('ring.direction_mapping' in s for s in result['missing_items']))
        self.assertTrue(any('voice.dangerous_confirm' in s for s in result['missing_items']))
        self.assertFalse(result['hardware_acceptance_complete'])

    def test_non_divisible_sensor_rate_does_not_drift(self):
        config=json.loads(Path('cluster_config.json').read_text(encoding='utf-8'))
        spec=config['members'][0]; spec['sensors']['rate_hz']=30
        member=ManagedMember(spec,'sim-cluster-1',DEMO_TOKENS,Path('results/upgrade_tests')/uuid.uuid4().hex)
        try:
            for _ in range(round(2/member.core.dt)): member.core.step(); member.publish()
            self.assertEqual(member.state()['sensor_measurement']['sequence'],61)
        finally: member.close()

    def test_timed_out_queued_session_is_not_executed_later(self):
        service=SimulationService(SimulationCore())
        # 用替代Event缩短排队超时，仅测试排队取消；控制器及执行线程仍为真实实现。
        real_event=threading.Event
        class FastEvent:
            def __init__(self): self.event=real_event()
            def wait(self,timeout=None): return self.event.wait(.02)
            def set(self): self.event.set()
            def is_set(self): return self.event.is_set()
        with patch('app_server.threading.Event',FastEvent):
            with self.assertRaises(APIError) as caught: service.submit('/api/session',{},'app')
        self.assertEqual(caught.exception.code,'ACK_TIMEOUT')
        service.start()
        try:
            until=time.monotonic()+1
            while service.commands.qsize() and time.monotonic()<until: time.sleep(.01)
            self.assertNotIn('app',service.sessions)
        finally: service.close()


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.service=SimulationService(SimulationCore())
        self.server=make_server(self.service,'127.0.0.1',0,['http://localhost:5173'])
        self.service.start()
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.port=self.server.server_port

    def tearDown(self):
        self.service.close();self.server.shutdown();self.server.server_close()

    def request(self,method,path,body=None,headers=None):
        connection=http.client.HTTPConnection('127.0.0.1',self.port,timeout=3)
        try:
            connection.request(method,path,body,headers or {})
            response=connection.getresponse();data=response.read()
            return response.status,dict(response.getheaders()),json.loads(data) if data else None
        finally: connection.close()

    def test_health_requires_auth_and_reports_running_worker(self):
        self.assertEqual(self.request('GET','/api/health')[0],401)
        code,headers,value=self.request('GET','/api/health',headers={'Authorization':'Bearer '+DEMO_TOKENS['app']})
        self.assertEqual(code,200);self.assertTrue(value['data']['healthy'])
        from runtime_info import IMPLEMENTATION_VERSION
        self.assertEqual(value['data']['implementation_version'],IMPLEMENTATION_VERSION)
        self.assertEqual(value['request_id'],headers['X-Request-ID'])

    def test_cors_preflight_and_disallowed_origin(self):
        headers={'Origin':'http://localhost:5173','Access-Control-Request-Method':'POST','Access-Control-Request-Headers':'Authorization, Content-Type'}
        code,result,_=self.request('OPTIONS','/api/session',headers=headers)
        self.assertEqual(code,204)
        self.assertEqual(result['Access-Control-Allow-Origin'],headers['Origin'])
        headers['Origin']='http://other-host:5173'
        self.assertEqual(self.request('OPTIONS','/api/session',headers=headers)[0],403)
        headers={'Origin':'http://other-host:5173','Authorization':'Bearer '+DEMO_TOKENS['app']}
        self.assertEqual(self.request('POST','/api/session','{}',headers)[0],403)
        self.assertNotIn('app',self.service.sessions)

    def test_bad_json_and_chunked_requests_have_no_session_side_effect(self):
        for body in ('{"a":NaN}','{"a":1,"a":2}','{"a":1e309}'):
            code,_,_=self.request('POST','/api/session',body,{'Authorization':'Bearer '+DEMO_TOKENS['app']})
            self.assertEqual(code,400)
        try:
            code,_,_=self.request('POST','/api/session','{}',{
                'Authorization':'Bearer '+DEMO_TOKENS['app'],'Transfer-Encoding':'chunked'})
            self.assertEqual(code,400)
        except (ConnectionAbortedError,ConnectionResetError):
            # Windows关闭仍有非法未读字节的连接可能直接复位；同样必须没有会话副作用。
            pass
        self.assertNotIn('app',self.service.sessions)


class ClusterUpgradeTests(unittest.TestCase):
    def setUp(self):
        self.manager=ClusterManager(json.loads(Path('cluster_config.json').read_text(encoding='utf-8')),
            Path('results/upgrade_cluster')/uuid.uuid4().hex)
        self.server=make_cluster_server(self.manager,port=0,allowed_origins=['http://localhost:5173'])
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.url=f'http://127.0.0.1:{self.server.server_port}'
        self.app=ClusterClient(self.url);self.app.open()

    def tearDown(self):
        self.app.close();self.manager.close();self.server.shutdown();self.server.server_close()

    def test_cluster_health_reports_loss(self):
        self.assertTrue(self.app.request('/api/health')['healthy'])
        self.app.manage('/api/cluster/member-link',device_id='sim-drone-2',online=False)
        with self.assertRaises(ClientError) as caught:self.app.request('/api/health')
        self.assertEqual(caught.exception.status,503)
        self.assertFalse(caught.exception.payload['data']['healthy'])

    def test_ring_continuous_batches_do_not_consume_history(self):
        ring=ClusterClient(self.url,'ring')
        try:
            ring.open();selection=self.app.select();self.app.batch('/api/session')
            ring.bind_selection(selection);ring.batch('/api/session')
            self.app.batch('/api/switch',target_source='ring',user_initiated=True)
            self.app.batch('/api/heartbeat')
            ring.batch('/api/neutral',axes=[0.,0.,0.,0.]);ring.begin_intent()
            self.app.batch('/api/heartbeat')
            size=len(self.manager.history)
            for seq in range(5):
                self.app.batch('/api/heartbeat')
                result=ring.batch('/api/ring-event',per_member={n:dict(event_kind='axes',
                    phase='begin' if seq==0 else 'update',event_sequence=seq,
                    intent_sequence=ring.members[n]['intent'],axes=[.2,0.,0.,0.],frame='world')
                    for n in selection['member_ids']})
                self.assertEqual(result['status'],'APPLIED',result)
            self.assertEqual(len(self.manager.history),size)
            ring.open()
            self.assertIsNone(self.app.state()['selection'])
            self.assertTrue(all(m['mode']=='PROTECTED' and m['active_source'] is None for m in self.app.state()['members']))
        finally:ring.close()

    def test_rejected_cluster_requests_are_audited(self):
        packet=self.app.envelope(target_kind='cluster',user_initiated=True)
        packet['sent_at_ms']-=1000
        with self.assertRaises(ClientError):self.app.request('/api/cluster/select',packet)
        records=[json.loads(line) for line in self.manager.audit_path.read_text(encoding='utf-8').splitlines()]
        self.assertTrue(any(r['event']=='CLUSTER_REJECTED' and r['request_id']==packet['request_id'] and r['error_code']=='EXPIRED_COMMAND' for r in records))


if __name__=='__main__':unittest.main(verbosity=2)
