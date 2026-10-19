"""CTL-001～006：真实HTTP及真实动力学，覆盖切换、幂等、取消和失效。"""
import json
import math
import threading
import time
import unittest
import urllib.request
import uuid
from app_server import DEMO_TOKENS, SimulationService, make_server
from client import ClientError, SourceClient
from simulation_core import SimulationCore


class HTTPTests(unittest.TestCase):
    def setUp(self):
        self.service = SimulationService(SimulationCore())
        self.server = make_server(self.service,'127.0.0.1',0)
        self.service.start()
        self.thread = threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        self.clients = {s:SourceClient(s,self.url) for s in DEMO_TOKENS}
        for c in self.clients.values():
            c.open_session()

    def tearDown(self):
        for c in self.clients.values():
            c.close()
        self.service.close()
        self.server.shutdown()
        self.server.server_close()

    def rejected(self, code, callback):
        with self.assertRaises(ClientError) as caught:
            callback()
        self.assertEqual(caught.exception.payload['error_code'],code)

    def select(self, source):
        self.clients[source].heartbeat()
        state = self.clients['app'].switch(source)
        self.assertEqual(state['pending_source'],source)
        self.assertIsNone(state['selected_source'])
        state = self.clients[source].neutral()
        self.assertEqual(state['selected_source'],source)
        self.assertEqual(state['mode'],'HOLD')
        self.clients[source].begin_intent()

    def test_user_switch_neutral_no_automatic_takeover(self):
        app,ring = self.clients['app'],self.clients['ring']
        self.select('app')
        app.control(vx=.3)
        ring.begin_intent()
        self.rejected('SOURCE_NOT_SELECTED',lambda:ring.control(vx=.3))
        self.assertEqual(app.switch('ring')['mode'],'HOLD')
        self.rejected('RETIRED_INPUT',lambda:app.control(vx=.3))
        self.rejected('NOT_NEUTRAL',lambda:ring.neutral([.2,0.,0.,0.]))
        ring.neutral()
        ring.begin_intent()
        self.assertEqual(ring.control(vy=.2)['active_source'],'ring')
        app.begin_intent()
        self.rejected('SOURCE_NOT_SELECTED',lambda:app.control(vx=.2))

    def test_idempotence_cancel_and_command_query(self):
        voice,app = self.clients['voice'],self.clients['app']
        self.select('voice')
        body = voice.envelope()
        body.update(command_id='voice-001',intent_sequence=voice.intent,frame='heading',action='forward',duration_ms=2000,recognized=True,confidence=1.)
        self.assertEqual(voice.request('/api/voice',body)['command_status'],'EXECUTING')
        first_end = self.service.voice_end
        self.assertTrue(voice.request('/api/voice',body)['idempotent_replay'])
        self.assertEqual(first_end,self.service.voice_end)
        self.rejected('COMMAND_ID_REUSED',lambda:voice.request('/api/voice',dict(body,action='backward')))
        app.discrete('/api/cancel',target_source='voice',target_command_id='voice-001')
        self.assertEqual(app.request('/api/commands/voice/voice-001')['status'],'CANCELLED')
        replay = voice.request('/api/voice',body)
        self.assertEqual(replay['command_status'],'CANCELLED')
        self.assertIsNone(replay['active_source'])

    def test_voice_completion_and_stale_replay(self):
        voice = self.clients['voice']
        self.select('voice')
        state = voice.voice('left',200)
        time.sleep(.28)
        self.assertEqual(voice.request('/api/state')['mode'],'HOLD')
        self.assertEqual(voice.request('/api/commands/voice/'+state['command_id'])['status'],'COMPLETED')
        self.rejected('STALE_EPOCH',lambda:voice.voice('left'))

    def test_timeout_resume_requires_new_switch(self):
        ring,app = self.clients['ring'],self.clients['app']
        self.select('ring')
        ring.control(vx=.3,ttl_ms=80)
        time.sleep(.16)
        self.assertEqual(app.request('/api/state')['protection_reason'],'INPUT_TIMEOUT')
        self.rejected('OPERATOR_REQUIRED',lambda:ring.resume())
        app.resume()
        ring.begin_intent()
        self.rejected('SOURCE_NOT_SELECTED',lambda:ring.control(vx=.3))

    def test_heartbeat_does_not_extend_motion(self):
        app = self.clients['app']
        self.select('app')
        app.control(vx=.3,ttl_ms=60)
        for _ in range(6):
            app.heartbeat()
            time.sleep(.02)
        self.assertEqual(app.request('/api/state')['protection_reason'],'INPUT_TIMEOUT')

    def test_permission_capability_device_and_protection(self):
        voice,ring,app = (self.clients[k] for k in ('voice','ring','app'))
        self.select('voice')
        self.rejected('CAPABILITY_MISMATCH',lambda:voice.control(vx=.2))
        self.rejected('OPERATOR_REQUIRED',lambda:ring.switch('ring'))
        body = voice.envelope()
        body.update(device_id='other-drone',command_id='wrong-device',intent_sequence=voice.intent,action='forward',duration_ms=200,frame='world')
        self.rejected('WRONG_DEVICE',lambda:voice.request('/api/voice',body))
        ring.epoch = ring.request('/api/state')['control_epoch']
        ring.discrete('/api/protect')
        self.rejected('PROTECTED',lambda:app.switch('app'))
        wrong = SourceClient('app',self.url,token='incorrect-token')
        try:
            self.rejected('UNAUTHORIZED',lambda:wrong.request('/api/state'))
        finally:
            wrong.close()

    def test_stale_stop_and_retired_gesture(self):
        app = self.clients['app']
        self.select('app')
        app.control(vx=.3)
        old = app.envelope()
        old.update(command_id='old-stop',command_sequence=999)
        app.stop()
        self.rejected('STALE_EPOCH',lambda:app.request('/api/stop',old))
        self.rejected('RETIRED_INPUT',lambda:app.control(vx=.3))
        app.begin_intent()
        self.assertEqual(app.control(vy=.2)['mode'],'ACTIVE')

    def test_disconnect_reconnect_and_app_exit(self):
        ring,app = self.clients['ring'],self.clients['app']
        self.select('ring')
        ring.control(vx=.3)
        state = ring.discrete('/api/disconnect',reason='PERIPHERAL_DISCONNECTED')
        self.assertEqual(state['mode'],'PROTECTED')
        ring.open_session()
        app.resume()
        ring.begin_intent()
        self.rejected('SOURCE_NOT_SELECTED',lambda:ring.control(vx=.3))
        self.select('ring')
        ring.control(vy=.2)
        self.assertEqual(app.discrete('/api/disconnect',reason='APP_EXIT')['protection_reason'],'APP_EXIT')

    def test_restricted_configuration_and_mapping(self):
        app,ring = self.clients['app'],self.clients['ring']
        app.discrete('/api/config',config_version=1,patch={'deadzone':.1,'sensitivity':.5,'max_horizontal_speed_m_s':.2})
        self.rejected('INVALID_CONFIG',lambda:app.discrete('/api/config',config_version=2,patch={'mass':1}))
        self.rejected('INVALID_CONFIG',lambda:app.discrete('/api/config',config_version=2,patch={'max_horizontal_speed_m_s':1}))
        ring.epoch = ring.request('/api/state')['control_epoch']
        self.rejected('OPERATOR_REQUIRED',lambda:ring.discrete('/api/config',config_version=2,patch={'deadzone':.2}))
        self.select('app')
        body = app.envelope()
        body.update(intent_sequence=app.intent,frame='world',axes=[.05,0.,0.,0.])
        self.assertEqual(app.request('/api/input',body)['reference_velocity_m_s'],[0.,0.,0.])
        body = app.envelope()
        body.update(intent_sequence=app.intent,frame='world',axes=[1.,0.,0.,0.])
        app.request('/api/input',body)
        self.assertAlmostEqual(self.service.motion[0],.1)
        self.rejected('MODE_NOT_ALLOWED',lambda:app.discrete('/api/config',config_version=2,patch={'deadzone':.2}))
        app.stop()
        app.discrete('/api/config',config_version=2,patch={'sources_enabled':{'app':True,'ring':True,'voice':False}})
        self.rejected('SOURCE_DISABLED',lambda:app.switch('voice'))

    def test_adaptive_endpoint_same_user_neutral_rules(self):
        app,ring = self.clients['app'],self.clients['ring']
        self.rejected('USER_ACTION_REQUIRED',lambda:app.discrete('/api/adaptive-switch',target_source='ring',user_initiated=False))
        self.assertEqual(app.switch('ring',adaptive=True)['pending_source'],'ring')
        ring.begin_intent()
        self.rejected('SOURCE_NOT_SELECTED',lambda:ring.control(vx=.3))
        ring.neutral()
        self.assertEqual(ring.control(vx=.3)['active_source'],'ring')

    def test_stream_unique_states(self):
        req = urllib.request.Request(self.url+'/api/events',headers={'Authorization':'Bearer '+DEMO_TOKENS['app']})
        states = []
        with urllib.request.urlopen(req,timeout=3) as response:
            while len(states)<10:
                line = response.readline().decode()
                if line.startswith('data: '):
                    states.append(json.loads(line[6:]))
        self.assertEqual(len({s['sequence'] for s in states}),10)

    def test_invalid_active_input_protects_and_logs_reason(self):
        app = self.clients['app']
        self.select('app')
        app.control(vx=.2)
        self.rejected('INVALID_ARGUMENT',lambda:app.control(vx=1.))
        state = app.request('/api/state')
        self.assertEqual(state['protection_reason'],'INPUT_INVALID')
        self.assertEqual(state['recent_events'][-1]['reason'],'INPUT_INVALID')


class DeterministicTests(unittest.TestCase):
    def setUp(self):
        self.core = SimulationCore()
        self.service = SimulationService(self.core)
        self.seq = {s:-1 for s in DEMO_TOKENS}
        self.ids = {s:self.call(s,'/api/session',{})['session_id'] for s in DEMO_TOKENS}

    def call(self, source, path, body):
        return self.service.execute(dict(source=source,path=path,body=body,received=time.monotonic(),epoch=time.time()*1000))

    def command(self, source, path, **fields):
        self.seq[source]+=1
        body = dict(device_id='sim-drone-1',session_id=self.ids[source],control_epoch=self.service.control_epoch,
            command_sequence=self.seq[source],ttl_ms=500,sent_at_ms=time.time()*1000,command_id=uuid.uuid4().hex)
        body.update(fields)
        if path=='/api/voice':
            body.update(recognized=True,confidence=1.)
        return self.call(source,path,body)

    def select(self, source):
        self.command('app','/api/switch',target_source=source,user_initiated=True)
        self.command(source,'/api/neutral',axes=[0.,0.,0.,0.])

    def test_physical_continuity_and_reference_limits(self):
        self.select('ring')
        for _ in range(80):
            self.command('ring','/api/control',intent_sequence=0,frame='world',vx_m_s=.4,vy_m_s=0.,vz_m_s=0.,yaw_rate_rad_s=.4)
            self.command('app','/api/heartbeat')
            self.service.tick()
        before = self.core.get_state()['state']
        self.select('app')
        after = self.core.get_state()['state']
        for key in ('x','v','q','w'):
            self.assertEqual(before[key],after[key])
        for _ in range(120):
            self.command('app','/api/control',intent_sequence=0,frame='world',vx_m_s=-.4,vy_m_s=0.,vz_m_s=0.,yaw_rate_rad_s=-.4)
            previous = self.core.get_state()['reference_velocity_m_s']
            yaw = self.service.filtered_yaw
            self.service.tick()
            self.assertLessEqual(math.dist(previous,self.core.get_state()['reference_velocity_m_s']),.5*self.core.dt+1e-10)
            self.assertLessEqual(abs(self.service.filtered_yaw-yaw),.5*self.core.dt+1e-10)

    def test_neutral_handshake_timeout(self):
        result = self.command('app','/api/switch',target_source='ring',user_initiated=True)
        self.service.pending_until = time.monotonic()-1
        self.service.expire()
        self.assertEqual(self.core.get_state()['mode'],'PROTECTED')
        self.assertEqual(self.service.records[(self.ids['app'],result['command_id'])]['status'],'TIMED_OUT')

    def test_voice_network_loss_stops_bounded_action(self):
        self.select('voice')
        result = self.command('voice','/api/voice',intent_sequence=0,frame='world',action='forward',duration_ms=3000)
        self.service.sessions['voice']['last_seen'] = time.monotonic()-1
        self.service.expire()
        self.assertEqual(self.core.get_state()['protection_reason'],'NETWORK_INTERRUPTED')
        self.assertEqual(self.service.records[(self.ids['voice'],result['command_id'])]['status'],'CANCELLED')

    def test_priority_queue_stops_before_general_commands(self):
        # 真实提交两条待处理请求，再启动工作线程：停止必须先执行，旧运动不能恢复。
        self.select('app')
        common = dict(device_id='sim-drone-1',session_id=self.ids['app'],control_epoch=self.service.control_epoch,
            ttl_ms=500,sent_at_ms=time.time()*1000)
        motion = dict(common,command_sequence=self.seq['app']+1,intent_sequence=0,frame='world',
            vx_m_s=.3,vy_m_s=0.,vz_m_s=0.,yaw_rate_rad_s=0.)
        stop = dict(common,command_sequence=self.seq['app']+2,command_id='priority-stop')
        results = {}
        def submit(name,path,body):
            try:
                results[name] = self.service.submit(path,body,'app')
            except Exception as exc:
                results[name] = exc
        threads = []
        for name,path,body in [('motion','/api/control',motion),('stop','/api/stop',stop)]:
            thread = threading.Thread(target=submit,args=(name,path,body))
            threads.append(thread)
            thread.start()
            limit = time.monotonic()+.2
            while self.service.commands.qsize()<len(threads) and time.monotonic()<limit:
                time.sleep(.001)
        self.service.start()
        try:
            for thread in threads:
                thread.join(3)
            self.assertEqual(results['stop']['mode'],'HOLD')
            self.assertEqual(results['motion'].status,409)
            self.assertIsNone(self.service.state()['active_source'])
        finally:
            self.service.close()

    def test_configuration_persists_and_old_session_cannot_replay(self):
        from pathlib import Path
        # 将测试配置写入项目results，不依赖Windows系统临时目录权限。
        directory = Path(__file__).resolve().parent/'results/config_persistence'
        directory.mkdir(parents=True,exist_ok=True)
        path = directory/(uuid.uuid4().hex+'.json')
        self.service.config_path = path
        self.command('app','/api/config',config_version=1,patch={'sensitivity':.4})
        saved = json.loads(path.read_text(encoding='utf-8'))
        self.assertEqual(saved['sensitivity'],.4)
        restarted = SimulationService(SimulationCore(),config=saved)
        self.assertEqual(restarted.state()['control_config']['sensitivity'],.4)
        from app_server import APIError
        with self.assertRaises(APIError) as caught:
            restarted.execute(dict(source='app',path='/api/heartbeat',body=dict(session_id=self.ids['app']),
                received=time.monotonic(),epoch=time.time()*1000))
        self.assertEqual(caught.exception.code,'STALE_SESSION')


if __name__ == '__main__':
    unittest.main(verbosity=2)
