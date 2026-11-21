"""11/26 UE 状态回放的数据契约、HTTP 查询导出及故障隔离回归。"""
import http.client
import json
import math
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from independent_fleet import IndependentFleet
from independent_client import IndependentClient
from independent_server import make_independent_server
from logs_query import LogError
from state_replay import REPLAY_SCHEMA, StateReplay, replay_sample


def temporary_directory():
    base = Path(__file__).resolve().parent/'results/replay_tests'
    base.mkdir(parents=True,exist_ok=True)
    return tempfile.TemporaryDirectory(dir=base)


def raw_member(device_id='sim-drone-1', *, online=True):
    return dict(device_id=device_id,boot_id='member-boot',sequence=7,
        simulation_time_s=1.25,position_m=[1.,2.,3.],velocity_m_s=[.1,.2,.3],
        quaternion_xyzw=[0.,0.,0.,1.],angular_velocity_body_rad_s=[.4,.5,.6],
        online=online,mode='HOLD',protection_reason=None)


class ReplayContractTests(unittest.TestCase):
    def test_ue_snapshot_matches_csa_units_axes_and_flags(self):
        sample = replay_sample(raw_member(),cluster_id='cluster',boot_id='boot',
            frame_sequence=3,recorded_at_unix_ms=123456.)
        self.assertEqual(sample['schema'],REPLAY_SCHEMA)
        self.assertEqual(sample['ue_device_id'],1)
        self.assertEqual(sample['ue']['position_cm'],[100.,-200.,300.])
        self.assertEqual(sample['ue']['linear_velocity_cm_s'],[10.,-20.,30.])
        self.assertEqual(sample['ue']['quaternion_xyzw'],[0.,0.,0.,1.])
        self.assertEqual(sample['ue']['angular_velocity_body_rad_s'],[-.4,.5,-.6])
        self.assertEqual(sample['ue']['flags'],15)
        self.assertNotIn('wall_clock_sec',sample['ue'])
        stale = replay_sample(raw_member(online=False),cluster_id='cluster',boot_id='boot',
            frame_sequence=4,recorded_at_unix_ms=123457.)
        self.assertEqual(stale['ue']['flags'],8)

    def test_nontrivial_rotation_uses_reflection_on_body_and_world_axes(self):
        sine = math.sin(math.radians(15)); cosine = math.cos(math.radians(15))
        cases = [
            ([sine,0.,0.,cosine],[-sine,0.,0.,cosine]),
            ([0.,sine,0.,cosine],[0.,sine,0.,cosine]),
            ([0.,0.,sine,cosine],[0.,0.,-sine,cosine]),
            ([.2,.3,.4,math.sqrt(1-.2**2-.3**2-.4**2)],
             [-.2,.3,-.4,math.sqrt(1-.2**2-.3**2-.4**2)])]
        for source, expected in cases:
            with self.subTest(source=source):
                raw = raw_member(); raw['quaternion_xyzw'] = source
                sample = replay_sample(raw,cluster_id='cluster',boot_id='boot',
                    frame_sequence=1,recorded_at_unix_ms=1.)
                for actual, wanted in zip(sample['ue']['quaternion_xyzw'],expected):
                    self.assertAlmostEqual(actual,wanted)
                self.assertAlmostEqual(math.sqrt(sum(v*v for v in sample['ue']['quaternion_xyzw'])),1.)

    def test_invalid_state_is_rejected_instead_of_exporting_nan(self):
        for value in ([0.,0.,0.,0.],[0.,0.,0.,float('nan')]):
            raw = raw_member(); raw['quaternion_xyzw'] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                replay_sample(raw,cluster_id='cluster',boot_id='boot',frame_sequence=1,
                    recorded_at_unix_ms=1.)

    def test_twenty_five_hz_source_is_decimated_to_average_ten_hz(self):
        with temporary_directory() as folder:
            replay = StateReplay(folder,['sim-drone-1'],'cluster','boot')
            try:
                accepted = [replay.capture([raw_member()],index*.04) for index in range(25)]
                self.assertEqual(sum(accepted),10)
            finally:
                replay.close()

    def test_string_device_ids_keep_working_with_unavailable_numeric_mapping(self):
        for members in (['no-number'],['sim-drone-01','other-1'],
                ['sim-drone-2147483648']):
            with self.subTest(members=members), temporary_directory() as folder:
                replay = StateReplay(folder,members,'cluster','boot')
                try:
                    self.assertTrue(replay.capture([raw_member(name) for name in members],1.))
                finally:
                    self.assertTrue(replay.close())
                rows = [json.loads(line) for line in
                    (Path(folder)/'replay/state_frames.jsonl').read_text('utf-8').splitlines()]
                self.assertEqual([row['device_id'] for row in rows],members)
                self.assertTrue(all(row['ue_device_id'] is None for row in rows))

    def test_recording_start_failure_is_visible_without_aborting_realtime_fleet(self):
        config = json.loads(Path(__file__).with_name('cluster_config.json').read_text('utf-8'))
        credentials = json.loads(Path(__file__).with_name('independent_credentials.json').read_text('utf-8'))
        with temporary_directory() as folder:
            with patch('state_replay.AsyncAudit',side_effect=OSError('TEST_REPLAY_START_FAILURE')):
                fleet = IndependentFleet(config,credentials,folder)
            try:
                before = fleet.member_state('sim-drone-1')['sequence']
                time.sleep(.08)
                self.assertGreater(fleet.member_state('sim-drone-1')['sequence'],before)
                self.assertTrue(fleet.audit.status()['healthy'])
                self.assertFalse(fleet.replay.status()['healthy'])
                self.assertIn('REPLAY_START_FAILED',fleet.replay.status()['failure'])
                self.assertFalse(fleet.record_replay([],time.monotonic()))
                with self.assertRaises(LogError) as error:
                    fleet.replay.query({},set(fleet.members))
                self.assertEqual((error.exception.status,error.exception.code),(503,'REPLAY_RECORDING_FAILED'))
            finally:
                self.assertFalse(fleet.close())

    def test_capture_exception_does_not_kill_shared_state_source(self):
        config = json.loads(Path(__file__).with_name('cluster_config.json').read_text('utf-8'))
        credentials = json.loads(Path(__file__).with_name('independent_credentials.json').read_text('utf-8'))
        with temporary_directory() as folder:
            fleet = IndependentFleet(config,credentials,folder)
            try:
                with self.assertLogs(level='ERROR'), patch.object(fleet.replay,'capture',side_effect=RuntimeError('TEST_CAPTURE')):
                    self.assertFalse(fleet.record_replay([],time.monotonic()))
                self.assertEqual(fleet.replay.status()['failure'],'REPLAY_CAPTURE_FAILED')
                self.assertTrue(fleet.audit.status()['healthy'])
                self.assertEqual(len(fleet.fleet_snapshot()['members']),3)
                with self.assertRaises(LogError) as error:
                    with fleet.replay.export({},set(fleet.members)):
                        self.fail('录制失败应在下载前拒绝导出')
                self.assertEqual(error.exception.code,'REPLAY_RECORDING_FAILED')
            finally:
                fleet.close()

    def test_capture_is_decimated_to_ten_hz_and_survives_reopen(self):
        with temporary_directory() as folder:
            replay = StateReplay(folder,['sim-drone-1'],'cluster','boot')
            states = [raw_member()]
            self.assertTrue(replay.capture(states,10.))
            self.assertFalse(replay.capture(states,10.05))
            self.assertTrue(replay.capture(states,10.1))
            self.assertTrue(replay.close())
            rows = [json.loads(line) for line in
                (Path(folder)/'replay/state_frames.jsonl').read_text('utf-8').splitlines()]
            self.assertEqual([row['frame_sequence'] for row in rows],[1,2])
            # 新索引只依赖持久化JSONL，不依赖旧进程内存或SQLite缓存。
            migrated = StateReplay(folder,['sim-drone-1'],'cluster','new-boot')
            try:
                deadline = time.monotonic()+2
                result = None
                while time.monotonic()<deadline:
                    try:
                        result = migrated.query({}, {'sim-drone-1'})
                    except LogError as exc:
                        if exc.code != 'LOG_INDEX_BUILDING':
                            raise
                        time.sleep(.03)
                        continue
                    if result['total']==2:
                        break
                    time.sleep(.03)
                self.assertIsNotNone(result)
                self.assertEqual(result['total'],2)
                self.assertEqual(result['samples'][0]['schema'],REPLAY_SCHEMA)
            finally:
                migrated.close()


class ReplayHttpTests(unittest.TestCase):
    def setUp(self):
        self.directory = temporary_directory()
        self.config = json.loads(Path(__file__).with_name('cluster_config.json').read_text('utf-8'))
        self.credentials = json.loads(Path(__file__).with_name('independent_credentials.json').read_text('utf-8'))
        self.fleet = IndependentFleet(self.config,self.credentials,self.directory.name)
        self.server = make_independent_server(self.fleet,port=0)
        self.thread = threading.Thread(target=lambda:self.server.serve_forever(poll_interval=.02),daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.fleet.close()
        self.directory.cleanup()

    def request(self, path, index=3, raw=False):
        connection = http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
        try:
            headers = {'Authorization':'Bearer '+self.credentials['credentials'][index]['token']}
            connection.request('GET',path,headers=headers)
            response = connection.getresponse(); payload = response.read()
            return (response.status,payload,response.headers) if raw else (response.status,json.loads(payload))
        finally:
            connection.close()

    def wait_for_samples(self, minimum=9):
        deadline = time.monotonic()+4
        last = None
        while time.monotonic()<deadline:
            code, result = self.request('/api/v1/replay/frames?limit=100')
            if code==200 and result['data']['total']>=minimum:
                return result['data']
            last = (code,result); time.sleep(.05)
        self.fail('回放样本超时未落盘: '+str(last))

    def test_all_roles_read_same_ordered_ue_samples_and_capabilities(self):
        baseline = self.wait_for_samples()
        self.assertEqual(baseline['replay_schema'],REPLAY_SCHEMA)
        self.assertEqual(baseline['target_rate_hz'],10)
        self.assertEqual({row['device_id'] for row in baseline['samples']},
            {'sim-drone-1','sim-drone-2','sim-drone-3'})
        self.assertTrue(all(row['event']=='STATE_FRAME' for row in baseline['samples']))
        self.assertTrue(baseline['integrity']['complete'])
        for index in range(4):
            code, result = self.request('/api/v1/replay/frames?limit=100&snapshot='+baseline['snapshot'],index=index)
            self.assertEqual(code,200,result)
            self.assertEqual(result['data']['total'],baseline['total'])
            self.assertEqual(result['data']['samples'],baseline['samples'])
        code, capabilities = self.request('/api/v1/capabilities')
        self.assertEqual(code,200)
        data = capabilities['data']
        self.assertEqual(data['replay_schema'],REPLAY_SCHEMA)
        self.assertEqual(data['routes']['replay_frames'],'/api/v1/replay/frames')
        self.assertTrue(data['capabilities']['state_replay'])
        self.assertTrue(data['capabilities']['replay_export'])
        health = self.request('/api/health')[1]['data']
        self.assertTrue(health['replay']['healthy'])
        self.assertEqual(health['replay']['target_rate_hz'],10)

    def test_device_time_pagination_and_jsonl_export(self):
        baseline = self.wait_for_samples(12)
        first = baseline['samples'][0]['recorded_at_unix_ms']
        path = '/api/v1/replay/frames?device_id=sim-drone-2&since_ms=%d&limit=2' % first
        code, page = self.request(path)
        self.assertEqual(code,200,page)
        data = page['data']
        self.assertEqual(len(data['samples']),2)
        self.assertTrue(all(row['device_id']=='sim-drone-2' for row in data['samples']))
        self.assertTrue(all(row['recorded_at_unix_ms']>=first for row in data['samples']))
        self.assertTrue(data['has_more'])
        code, second = self.request(path+'&offset=2&snapshot='+data['snapshot'])
        self.assertEqual(code,200,second)
        self.assertNotEqual(data['samples'][0]['frame_sequence'],
            second['data']['samples'][0]['frame_sequence'])
        code, payload, headers = self.request('/api/v1/replay/export?device_id=sim-drone-2',raw=True)
        self.assertEqual(code,200,payload[:200])
        self.assertEqual(headers['Content-Type'],'application/x-ndjson; charset=utf-8')
        self.assertEqual(headers['X-Replay-Schema'],REPLAY_SCHEMA)
        self.assertIn('ue-replay-',headers['Content-Disposition'])
        rows = [json.loads(line) for line in payload.decode('utf-8').splitlines()]
        self.assertGreaterEqual(len(rows),4)
        self.assertTrue(all(row['device_id']=='sim-drone-2' and row['schema']==REPLAY_SCHEMA for row in rows))

    def test_bad_filters_and_recording_failure_are_explicit(self):
        self.wait_for_samples(3)
        for path in ('/api/v1/replay/frames?device_id=unknown',
                '/api/v1/replay/frames?since_ms=-1','/api/v1/replay/frames?event=COMMAND_ACK',
                '/api/v1/replay/export?event=COMMAND_ACK','/api/v1/replay/export?format=csv'):
            code, result = self.request(path)
            self.assertEqual(code,400,(path,result))
            self.assertEqual(result['error_code'],'INVALID_ARGUMENT')
        self.fleet.replay.writer._fail('TEST_REPLAY_DISK_FAILURE')
        code, result = self.request('/api/v1/replay/frames')
        self.assertEqual(code,200,result)
        self.assertFalse(result['data']['integrity']['complete'])
        code, result = self.request('/api/v1/replay/export')
        self.assertEqual(code,409,result)
        self.assertEqual(result['error_code'],'REPLAY_RECORDING_FAILED')
        # 回放属于观察证据，故障不得锁存或中断实时控制/状态。
        self.assertEqual(self.request('/api/v1/state')[0],200)
        self.assertEqual(self.request('/api/health')[0],200)
        self.assertFalse(self.request('/api/health')[1]['data']['replay']['healthy'])
        pilot = self.credentials['credentials'][0]
        client = IndependentClient(pilot['device_id'],pilot['token'],
            'http://127.0.0.1:'+str(self.server.server_port))
        try:
            client.open_session(); client.switch('app'); client.neutral(); client.begin_intent()
            self.assertEqual(client.control(vx=.1)['mode'],'ACTIVE')
            client.stop()
        finally:
            client.close()


if __name__=='__main__':
    unittest.main(verbosity=2)
