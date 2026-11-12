"""CLU多机真实HTTP回归，仿真测量不作为实物传感器验收。"""
import copy
import json
import threading
import time
import unittest
import uuid
from pathlib import Path
from cluster_manager import ClusterManager
from cluster_server import make_cluster_server
from cluster_client import ClusterClient
from client import ClientError


class ClusterTests(unittest.TestCase):
    def setUp(self):
        self.config = json.loads(Path('cluster_config.json').read_text(encoding='utf-8'))
        self.directory = Path('results/cluster_tests')/uuid.uuid4().hex
        self.manager = ClusterManager(self.config,self.directory)
        self.server = make_cluster_server(self.manager,port=0)
        self.thread = threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        self.app = ClusterClient(self.url)
        self.app.open()

    def tearDown(self):
        self.app.close()
        self.server.shutdown()
        self.server.server_close()
        self.manager.close()

    def reject(self, code, fn):
        with self.assertRaises(ClientError) as caught: fn()
        self.assertEqual(caught.exception.payload['error_code'],code)

    def activate(self,kind='cluster',**fields):
        self.app.select(kind,**fields)
        self.assertEqual(self.app.batch('/api/session')['status'],'APPLIED')
        self.assertEqual(self.app.batch('/api/switch',target_source='app',user_initiated=True)['status'],'APPLIED')
        self.assertEqual(self.app.batch('/api/neutral',axes=[0.,0.,0.,0.])['status'],'APPLIED')
        self.app.begin_intent()

    def test_instances_configuration_and_sensor_isolation(self):
        state = self.app.state()
        self.assertEqual((state['online_count'],state['sensor_enabled_count']),(3,3))
        members = state['members']
        self.assertEqual(len({m['device_id'] for m in members}),3)
        self.assertEqual([m['control_config']['sensitivity'] for m in members],[1.,.8,.9])
        self.assertEqual([m['instance_config']['sensors']['seed'] for m in members],[101,102,103])
        self.assertTrue(all(m['sensor_measurement']['quality']=='SIMULATED' for m in members))
        self.assertEqual(len({id(e['service'].core.vehicle) for e in self.manager.members.values()}),3)
        self.assertEqual(len({id(e['service'].rng) for e in self.manager.members.values()}),3)
        self.activate('single',device_id='sim-drone-1')
        for _ in range(6): self.app.controls(vx=.3); time.sleep(.05)
        states = {m['device_id']:m for m in self.app.state()['members']}
        self.assertGreater(states['sim-drone-1']['position_m'][0],.001)
        self.assertAlmostEqual(states['sim-drone-2']['position_m'][0],2.,places=5)
        self.assertAlmostEqual(states['sim-drone-3']['position_m'][0],0.,places=5)

    def test_group_selection_only_moves_members_and_takeover_retires_scope(self):
        self.activate('group',group_id='alpha')
        old_selection = copy.deepcopy(self.app.selection)
        for _ in range(5): self.assertEqual(self.app.controls(vy=.2)['status'],'APPLIED'); time.sleep(.05)
        self.assertEqual(self.app.state()['selected_count'],2)
        self.reject('CONTROL_CONFLICT',lambda:self.app.select('single',device_id='sim-drone-1'))
        self.app.select('single',device_id='sim-drone-1',takeover=True)
        states = {s['device_id']:s for s in self.app.state()['members']}
        self.assertIsNone(states['sim-drone-1']['selected_source'])
        self.assertIsNone(states['sim-drone-2']['active_source'])
        self.assertAlmostEqual(states['sim-drone-3']['position_m'][1],2.,places=5)
        self.reject('STALE_SELECTION',lambda:self.app.manage('/api/cluster/dispatch',
            selection_id=old_selection['selection_id'],commands=[]))

    def test_single_route_cannot_bypass_group_control(self):
        self.activate()
        name = 'sim-drone-1'
        body = self.app.member_body(name,'/api/control',dict(intent_sequence=0,
            frame='world',vx_m_s=.2,vy_m_s=0.,vz_m_s=0.,yaw_rate_rad_s=0.))
        body.update(cluster_id=self.app.cluster_id,selection_id=self.app.selection['selection_id'])
        self.reject('CONTROL_CONFLICT',lambda:self.app.request('/api/devices/'+name+'/control',body))
        self.reject('NOT_FOUND',lambda:self.app.request('/api/devices/'+name+'/__cluster_barrier',{}))

    def test_partial_ack_preserves_per_member_validation_and_log_correlation(self):
        self.activate()
        rows = []
        for i,name in enumerate(self.app.selection['member_ids']):
            body = self.app.member_body(name,'/api/control',dict(intent_sequence=0,
                frame='world',vx_m_s=.2 if i!=1 else 5.,vy_m_s=0.,vz_m_s=0.,yaw_rate_rad_s=0.))
            rows.append(dict(device_id=name,path='/api/control',body=body))
        packet = self.app.envelope(selection_id=self.app.selection['selection_id'],commands=rows)
        result = self.app.request('/api/cluster/dispatch',packet)
        self.assertEqual((result['status'],result['succeeded'],result['failed']),('PARTIAL',2,1))
        self.assertEqual(result['results'][1]['error_code'],'INVALID_ARGUMENT')
        records = [json.loads(line) for line in self.manager.audit_path.read_text(encoding='utf-8').splitlines()]
        acknowledgements = [r for r in records if r['event']=='MEMBER_ACK' and r['request_id']==packet['request_id']]
        self.assertEqual(len(acknowledgements),3)
        self.assertEqual({r['device_id'] for r in acknowledgements},set(self.app.selection['member_ids']))

    def test_expired_outer_and_member_commands_rejected(self):
        self.activate()
        expired = self.app.envelope(selection_id=self.app.selection['selection_id'],commands=[])
        expired['sent_at_ms'] -= 1000
        self.reject('EXPIRED_COMMAND',lambda:self.app.request('/api/cluster/dispatch',expired))
        rows=[]
        for name in self.app.selection['member_ids']:
            b=self.app.member_body(name,'/api/control',dict(intent_sequence=0,frame='world',vx_m_s=.1,vy_m_s=0.,vz_m_s=0.,yaw_rate_rad_s=0.))
            b['sent_at_ms']-=1000
            rows.append(dict(device_id=name,path='/api/control',body=b))
        result=self.app.manage('/api/cluster/dispatch',selection_id=self.app.selection['selection_id'],commands=rows)
        self.assertEqual(result['failed'],3)
        self.assertTrue(all(r['error_code']=='EXPIRED_COMMAND' for r in result['results']))

    def test_management_idempotence_and_changed_request_id_rejected(self):
        body=self.app.envelope(group_id='newgroup',member_ids=['sim-drone-1'])
        first=self.app.request('/api/cluster/groups',body)
        replay=self.app.request('/api/cluster/groups',body)
        self.assertTrue(replay['idempotent_replay'])
        self.assertEqual(first['revision'],replay['revision'])
        changed=dict(body,member_ids=['sim-drone-2'])
        self.reject('REQUEST_ID_REUSED',lambda:self.app.request('/api/cluster/groups',changed))

    def test_member_leave_join_unregister_and_register_capacity(self):
        self.app.manage('/api/cluster/leave',group_id='alpha',device_id='sim-drone-2')
        self.assertEqual(self.app.state()['groups']['alpha'],['sim-drone-1'])
        self.app.manage('/api/cluster/join',group_id='beta',device_id='sim-drone-2')
        self.assertIn('sim-drone-2',self.app.state()['groups']['beta'])
        spec=copy.deepcopy(self.config['members'][0]); spec['device_id']='sim-drone-4'
        self.reject('CAPACITY_LIMIT',lambda:self.app.manage('/api/cluster/register',member=spec))
        self.app.manage('/api/cluster/unregister',device_id='sim-drone-3')
        self.app.manage('/api/cluster/register',member=spec)
        self.assertEqual(self.app.state()['registered_count'],3)
        self.reject('DEVICE_ID_REUSED',lambda:self.app.manage('/api/cluster/register',member=self.config['members'][2]))

    def test_loss_and_reconnect_do_not_restore_previous_control(self):
        self.activate()
        old=copy.deepcopy(self.app.selection)
        self.app.controls(vx=.2)
        self.app.manage('/api/cluster/member-link',device_id='sim-drone-2',online=False)
        state=self.app.state()
        self.assertEqual(state['online_count'],2)
        self.assertIsNone(state['selection'])
        self.assertTrue(all(m['mode']=='PROTECTED' for m in state['members']))
        self.reject('MEMBER_LOST',lambda:self.app.select())
        self.app.manage('/api/cluster/member-link',device_id='sim-drone-2',online=True)
        self.assertIsNone(self.app.state()['selection'])
        self.reject('STALE_SELECTION',lambda:self.app.manage('/api/cluster/dispatch',selection_id=old['selection_id'],commands=[]))
        self.app.select()
        self.assertEqual(self.app.batch('/api/session')['status'],'APPLIED')
        self.assertEqual(self.app.batch('/api/resume',neutral_confirmed=True)['status'],'APPLIED')
        self.assertTrue(all(m['active_source'] is None for m in self.app.state()['members']))

    def test_stopped_worker_is_marked_lost_automatically(self):
        self.activate()
        self.manager.members['sim-drone-2']['service'].close()
        until=time.monotonic()+2
        while time.monotonic()<until:
            state=self.app.state()
            if state['online_count']==2: break
            time.sleep(.05)
        self.assertEqual(state['online_count'],2)
        self.assertIsNone(state['selection'])

    def test_wrong_cluster_and_operator_permissions_and_reserved_algorithm(self):
        body=self.app.envelope(group_id='x',member_ids=['sim-drone-1']); body['cluster_id']='other'
        self.reject('WRONG_CLUSTER',lambda:self.app.request('/api/cluster/groups',body))
        ring=ClusterClient(self.url,'ring')
        try:
            ring.open()
            self.reject('OPERATOR_REQUIRED',lambda:ring.select())
        finally: ring.close()
        self.reject('COLLABORATION_NOT_IMPLEMENTED',lambda:self.app.manage('/api/cluster/formation',
            member_ids=['sim-drone-1'],task_id='task-1',contract_version='0.1',parameters={'shape':'line'}))

    def test_batch_requires_exact_explicit_member_set(self):
        self.activate()
        self.reject('TARGET_MISMATCH',lambda:self.app.manage('/api/cluster/dispatch',
            selection_id=self.app.selection['selection_id'],commands=[{'device_id':'sim-drone-1','path':'/api/session','body':{}}]))

    def test_simultaneous_single_and_cluster_claims_cannot_both_succeed(self):
        packets=[self.app.envelope(target_kind='cluster',user_initiated=True),
                 self.app.envelope(target_kind='single',device_id='sim-drone-1',user_initiated=True)]
        gate=threading.Barrier(2)
        results=[]
        def claim(packet):
            client=ClusterClient(self.url)
            try:
                gate.wait()
                client.request('/api/cluster/select',packet)
                results.append('APPLIED')
            except ClientError as exc:
                results.append(exc.payload['error_code'])
            finally: client.close()
        jobs=[threading.Thread(target=claim,args=(packet,)) for packet in packets]
        for job in jobs: job.start()
        for job in jobs: job.join(5)
        self.assertEqual(results.count('APPLIED'),1)
        self.assertTrue(all(r in ('APPLIED','CONTROL_CONFLICT','STALE_CLUSTER_SEQUENCE') for r in results))

    def test_cluster_motion_requires_neutral_and_heartbeat_does_not_extend_input(self):
        self.app.select()
        self.app.batch('/api/session')
        self.app.batch('/api/switch',target_source='app',user_initiated=True)
        self.app.begin_intent()
        self.assertEqual(self.app.controls(vx=.1)['failed'],3)
        self.app.batch('/api/neutral',axes=[0.,0.,0.,0.])
        self.app.begin_intent()
        self.assertEqual(self.app.controls(vx=.1)['succeeded'],3)
        for _ in range(8):
            self.app.batch('/api/heartbeat')
            time.sleep(.08)
        self.assertTrue(all(m['mode']=='PROTECTED' for m in self.app.state()['members']))
        self.assertTrue(all(m['protection_reason']=='INPUT_TIMEOUT' for m in self.app.state()['members']))

    def test_continuous_samples_do_not_fill_discrete_history(self):
        self.activate()
        size=len(self.manager.history)
        for _ in range(8): self.app.controls(vx=.1)
        self.assertEqual(len(self.manager.history),size)

    def test_member_control_configuration_persists_without_affecting_others(self):
        from cluster_member import ManagedMember
        from app_server import DEMO_TOKENS
        self.activate('single',device_id='sim-drone-1')
        result=self.app.batch('/api/config',config_version=1,patch={'sensitivity':.6})
        self.assertEqual(result['status'],'APPLIED')
        restored=ManagedMember(self.config['members'][0],self.manager.cluster_id,DEMO_TOKENS,
                               self.directory/'sim-drone-1')
        self.assertEqual(restored.state()['control_config']['sensitivity'],.6)
        self.assertEqual(restored.state()['control_config_origin'],'FILE')
        self.assertEqual(self.manager.member_state('sim-drone-2')['control_config']['sensitivity'],.8)
        self.assertEqual(self.manager.member_state('sim-drone-2')['control_config_origin'],'REGISTRY')
        restored.close()


if __name__=='__main__': unittest.main(verbosity=2)
