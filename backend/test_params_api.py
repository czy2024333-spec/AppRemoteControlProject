# -*- coding: utf-8 -*-
"""动力学参数闭环回归：编辑→版本→落盘→重启恢复→限幅与物理参数生效。"""
import os
import copy
import json
import math
import http.client
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from app_server import SimulationService, APIError
from simulation_core import SimulationCore
from independent_client import IndependentClient
from independent_fleet import IndependentFleet
from independent_server import make_independent_server
import dynamics_params

WORK = Path('results/params_tests') / str(os.getpid())


class ParamsApiTests(unittest.TestCase):
    def setUp(self):
        WORK.mkdir(parents=True, exist_ok=True)
        self.path = WORK / ('params-%s.json' % self._testMethodName)
        if self.path.exists():
            self.path.unlink()
        self.service = SimulationService(SimulationCore(), dynamics_params_path=self.path)
        self.service.core.stop()
        self.session = self.service.execute(dict(source='app', path='/api/session', body={},
                                                 received=time.monotonic(), epoch=time.time() * 1000))
        self._seq = 0

    def cmd(self, path, body):
        self._seq += 1
        payload = dict(device_id='sim-drone-1', session_id=self.session['session_id'],
                       control_epoch=self.service.control_epoch, command_sequence=self._seq,
                       ttl_ms=500, sent_at_ms=time.time() * 1000,
                       command_id='p-%d' % self._seq)
        payload.update(body)
        self.last_item = dict(source='app', path=path, body=payload,
                              received=time.monotonic(), epoch=time.time() * 1000)
        return self.service.execute(self.last_item)

    def tearDown(self):
        self.service.close()

    def test_edit_bumps_version_applies_limits_and_physics(self):
        self.assertEqual(self.service.dynamics_params_version, 1)
        before_mass = self.service.core.params['mass']
        r = self.cmd('/api/params', dict(params_version=1,
                                         patch={'mass_kg': 1.5,
                                                'power': {'max_thrust_n': 20.0},
                                                'limits': {'max_horizontal_speed_m_s': 0.9}}))
        self.assertEqual(r['params_version'], 2)
        self.assertEqual(self.service.dynamics_params['mass_kg'], 1.5)
        self.assertEqual(self.service.core.limits.max_horizontal_speed_m_s, 0.9)
        self.assertAlmostEqual(self.service.core.params['mass'], 1.5)
        self.assertNotAlmostEqual(before_mass, self.service.core.params['mass'])
        self.service.publish()
        self.assertEqual(self.service.state()['dynamics_params_version'], 2)

    def test_persist_and_restart(self):
        self.cmd('/api/params', dict(params_version=1, patch={'mass_kg': 1.2,
                                                              'power': {'max_thrust_n': 18.0,
                                                                        'motor_time_constant_s': 0.08}}))
        restarted = SimulationService(SimulationCore(), dynamics_params_path=self.path)
        self.assertEqual(restarted.dynamics_params_version, 2)
        self.assertEqual(restarted.dynamics_params['mass_kg'], 1.2)
        self.assertAlmostEqual(restarted.core.params['tau_m'], 0.08)

    def test_version_conflict(self):
        self.cmd('/api/params', dict(params_version=1, patch={'mass_kg': 1.1, 'power': {'max_thrust_n': 18.0}}))
        with self.assertRaises(APIError) as exc:
            self.cmd('/api/params', dict(params_version=1, patch={'mass_kg': 1.2, 'power': {'max_thrust_n': 18.0}}))
        self.assertEqual(exc.exception.code, 'PARAMS_VERSION_CONFLICT')

    def test_invalid_and_out_of_range(self):
        for body, code in ((dict(params_version=1, patch={'mass_kg': 0.0}), 'INVALID_PARAMS'),
                           (dict(params_version=1, patch={'limits': {'max_altitude_m': 0.5}}),
                            'PARAM_OUT_OF_RANGE')):
            with self.assertRaises(APIError) as exc:
                self.cmd('/api/params', body)
            self.assertEqual(exc.exception.code, code)

    def test_hover_stays_stable_after_mass_change(self):
        self.cmd('/api/params', dict(params_version=1, patch={'mass_kg': 1.5, 'power': {'max_thrust_n': 20.0}}))
        start = self.service.core.get_state()['state']['x'][2]
        for _ in range(500):
            self.service.core.step()
        z = self.service.core.get_state()['state']['x'][2]
        self.assertLess(abs(z - start), 0.5, '改质量后悬停高度漂移过大: %.3f' % (z - start))

    def test_model_update_preserves_clock_environment_velocity_and_geofence_origin(self):
        core = self.service.core
        for _ in range(20):
            core.step()
        core.set_wind([.2, -.1, 0.])
        core.apply_impulse([.0003, 0., 0.],[.000001, 0., 0.])
        core._state['x'] = core._state['x'] + [1., 2., .2]
        core._state['q'][:] = [0., 0., math.sin(.2), math.cos(.2)]
        before = core.get_state()
        origin = core._origin_xy.copy()
        self.cmd('/api/params', dict(params_version=1,patch={'mass_kg':1.5,'power':{'max_thrust_n':20.}}))
        after = core.get_state()
        self.assertEqual((after['time_s'],after['sequence']),(before['time_s'],before['sequence']))
        for key in ('x','q','v','w','wind'):
            self.assertEqual(after['state'][key],before['state'][key])
        self.assertEqual(core._origin_xy.tolist(),origin.tolist())
        core.step()
        self.assertGreater(core.time_s,before['time_s'])

    def test_limit_only_update_keeps_model_and_time(self):
        for _ in range(10):
            self.service.core.step()
        before = self.service.core.get_state()
        model = self.service.core.vehicle
        with patch.object(self.service.core,'prepare_physics',side_effect=AssertionError('不应重建模型')):
            self.cmd('/api/params',dict(params_version=1,patch={'limits':{'max_horizontal_speed_m_s':.2}}))
        self.assertIs(self.service.core.vehicle,model)
        self.assertEqual(self.service.core.time_s,before['time_s'])
        self.assertEqual(self.service.motion_limits()['max_horizontal_speed_m_s'],.2)

    def test_save_failure_preserves_model_version_epoch_state_and_previous_file(self):
        self.cmd('/api/params',dict(params_version=1,patch={'limits':{'max_horizontal_speed_m_s':.2}}))
        before_file = self.path.read_bytes()
        before = self.service.core.get_state()
        epoch = self.service.control_epoch
        vehicle = self.service.core.vehicle
        with patch.object(Path,'replace',side_effect=OSError('TEST_ATOMIC_REPLACE_FAILURE')):
            with self.assertRaises(APIError) as error:
                self.cmd('/api/params',dict(params_version=2,patch={'mass_kg':1.2,'power':{'max_thrust_n':18.}}))
        self.assertEqual(error.exception.code,'PARAMS_SAVE_FAILED')
        self.assertEqual(self.service.dynamics_params_version,2)
        self.assertEqual(self.path.read_bytes(),before_file)
        self.assertEqual(self.service.core.get_state(),before)
        self.assertEqual(self.service.control_epoch,epoch)
        self.assertIs(self.service.core.vehicle,vehicle)
        self.assertEqual(list(self.path.parent.glob(self.path.name+'.*.tmp')),[])

    def test_invalid_model_combination_does_not_commit_or_persist(self):
        before = self.service.core.get_state()
        with self.assertRaises(APIError) as error:
            self.cmd('/api/params',dict(params_version=1,patch={'mass_kg':5.}))
        self.assertEqual(error.exception.code,'INVALID_PARAMS')
        self.assertEqual(self.service.core.get_state(),before)
        self.assertEqual(self.service.dynamics_params_version,1)
        self.assertFalse(self.path.exists())

    def test_old_discrete_request_replays_original_version_without_applying_twice(self):
        self.cmd('/api/params',dict(params_version=1,patch={'limits':{'max_horizontal_speed_m_s':.2}}))
        original = copy.deepcopy(self.last_item)
        self.cmd('/api/params',dict(params_version=2,patch={'limits':{'max_horizontal_speed_m_s':.3}}))
        replay = self.service.execute(original)
        self.assertTrue(replay['idempotent_replay'])
        self.assertEqual(replay['params_version'],2)
        self.assertEqual(self.service.dynamics_params_version,3)
        self.assertEqual(self.service.core.limits.max_horizontal_speed_m_s,.3)

    def test_active_protected_and_pending_switch_reject_parameter_edits(self):
        self.service.core.set_motion(.1,0.,0.,0.,.5)
        with self.assertRaises(APIError) as active:
            self.cmd('/api/params',dict(params_version=1,patch={'mass_kg':1.}))
        self.assertEqual(active.exception.code,'MODE_NOT_ALLOWED')
        self.service.core.protect('TEST_PROTECTION')
        with self.assertRaises(APIError) as protected:
            self.cmd('/api/params',dict(params_version=1,patch={'mass_kg':1.}))
        self.assertEqual(protected.exception.code,'MODE_NOT_ALLOWED')
        self.service.core.resume(neutral_confirmed=True)
        self.service.pending_source = 'app'
        self.service.pending_until = time.monotonic()+5
        with self.assertRaises(APIError) as pending:
            self.cmd('/api/params',dict(params_version=1,patch={'mass_kg':1.}))
        self.assertEqual(pending.exception.code,'MODE_NOT_ALLOWED')

    def test_validation_and_legacy_file_loading_reject_damaged_versions(self):
        for changes in ({'mass_kg':10**400},{'mass_kg':True},
                {'power':{'max_thrust_n':float('nan')}},{'limits':{'min_altitude_m':0.}}):
            with self.subTest(changes=tuple(changes)), self.assertRaises(ValueError):
                dynamics_params.update(dynamics_params.DEFAULT_DYNAMICS_PARAMS,changes)
        self.path.write_text(json.dumps(dynamics_params.DEFAULT_DYNAMICS_PARAMS),encoding='utf-8')
        params, version = dynamics_params.load(self.path)
        self.assertEqual(version,1)
        self.assertEqual(params['mass_kg'],1.)
        self.path.write_text('{',encoding='utf-8')
        with self.assertRaises(ValueError):
            dynamics_params.load(self.path)
        for version in (0,True,1.5):
            self.path.write_text(json.dumps(dict(version=version,params=params)),encoding='utf-8')
            with self.assertRaises(ValueError):
                dynamics_params.load(self.path)


class ParamsHttpTests(unittest.TestCase):
    def setUp(self):
        WORK.mkdir(parents=True,exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=WORK)
        self.config = json.loads(Path('cluster_config.json').read_text('utf-8'))
        self.config['gnss'] = dict(self.config['gnss'],enabled=True)
        self.credentials = json.loads(Path('independent_credentials.json').read_text('utf-8'))
        self.start()

    def start(self):
        self.fleet = IndependentFleet(self.config,self.credentials,self.directory.name)
        self.server = make_independent_server(self.fleet,port=0)
        self.thread = threading.Thread(target=lambda:self.server.serve_forever(poll_interval=.02),daemon=True)
        self.thread.start()
        row = self.credentials['credentials'][0]
        self.client = IndependentClient(row['device_id'],row['token'],'http://127.0.0.1:'+str(self.server.server_port))

    def stop(self):
        self.client.close()
        self.server.shutdown(); self.server.server_close(); self.fleet.close(); self.thread.join(2)

    def tearDown(self):
        self.stop()
        self.directory.cleanup()

    def read(self,path,index=3,body=None):
        connection = http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
        try:
            headers = dict(Authorization='Bearer '+self.credentials['credentials'][index]['token'],
                           **{'Content-Type':'application/json'})
            connection.request('GET' if body is None else 'POST',path,
                json.dumps(body).encode() if body is not None else None,headers)
            response = connection.getresponse()
            return response.status,json.loads(response.read())
        finally:
            connection.close()

    def edit(self,changes,version=1):
        return self.client.discrete('/api/params',params_version=version,patch=changes)

    def test_per_device_routes_permissions_restart_and_audit(self):
        path = '/api/v1/devices/sim-drone-1/params'
        self.assertEqual(self.read(path)[1]['data']['params_version'],1)
        self.assertEqual(self.read(path,body={})[0],403)
        self.assertEqual(self.read(path,index=1,body={})[0],403)
        self.client.open_session()
        packet = dict(self.client.envelope(),params_version=1,patch={'limits':{'max_horizontal_speed_m_s':.2}},
                      command_id='params-v1')
        status, changed = self.read(path,index=0,body=packet)
        self.assertEqual(status,200,changed)
        self.assertEqual(changed['data']['params_version'],2)
        peer = self.read('/api/v1/devices/sim-drone-2/params')[1]['data']
        self.assertEqual(peer['params_version'],1)
        self.assertAlmostEqual(peer['dynamics_params']['mass_kg'],.033)
        raw = self.read('/api/devices/sim-drone-1/state')[1]['data']
        self.assertEqual(raw['dynamics_params_version'],2)
        self.stop(); self.start()
        restored = self.read(path)[1]['data']
        self.assertEqual(restored['params_version'],2)
        self.assertEqual(restored['effective_control_limits']['max_horizontal_speed_m_s'],.2)
        rows = [json.loads(line) for line in (Path(self.directory.name)/'fleet_events.jsonl').read_text('utf-8').splitlines()]
        updates = [row for row in rows if row.get('reason')=='PARAMS_UPDATED']
        self.assertEqual(len(updates),1)
        self.assertEqual(updates[0]['params_version'],2)
        self.assertEqual(updates[0]['parameter_changes']['limits']['after']['max_horizontal_speed_m_s'],.2)

    def test_effective_limit_maps_full_joystick_and_leaves_peer_motion_available(self):
        self.client.open_session()
        self.edit({'limits':{'max_horizontal_speed_m_s':.2,'max_vertical_speed_m_s':.1,'max_yaw_rate_rad_s':.15}})
        self.client.switch('app'); self.client.neutral(); self.client.begin_intent()
        packet = dict(self.client.envelope(),intent_sequence=self.client.intent,frame='world',axes=[1.,0.,1.,1.])
        data = self.client.request('/api/input',packet)
        self.assertEqual(data['mode'],'ACTIVE')
        self.assertLessEqual(abs(data['reference_velocity_m_s'][2]),.1)
        self.assertEqual(data['effective_control_limits'],dict(max_horizontal_speed_m_s=.2,
            max_vertical_speed_m_s=.1,max_yaw_rate_rad_s=.15))
        self.client.stop()

    def test_gnss_and_replay_keep_time_and_sequence_across_model_change(self):
        self.client.open_session()
        deadline = time.monotonic()+3
        while time.monotonic() < deadline:
            before = self.read('/api/v1/devices/sim-drone-1/state')[1]['data']
            if before['sensors']['gnss_local_position']['value'] is not None:
                break
            time.sleep(.02)
        self.assertIsNotNone(before['sensors']['gnss_local_position']['value'])
        self.edit({'mass_kg':1.2,'power':{'max_thrust_n':18.}})
        after = self.read('/api/v1/devices/sim-drone-1/state')[1]['data']
        self.assertGreaterEqual(after['simulation_time_s'],before['simulation_time_s'])
        self.assertGreaterEqual(after['sequence'],before['sequence'])
        self.assertGreaterEqual(after['sensors']['gnss_local_position']['packet_sequence'],
            before['sensors']['gnss_local_position']['packet_sequence'])
        self.assertTrue(self.fleet.replay.status()['healthy'])
        self.assertEqual(after['dynamics_params_version'],2)
        self.assertEqual(self.read('/api/v1/devices/sim-drone-2/state')[1]['data']['dynamics_params_version'],1)

if __name__ == '__main__':
    unittest.main()
