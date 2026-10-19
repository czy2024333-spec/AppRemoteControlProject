"""协议和SDK桥接软件验证；此测试不替代IN-006要求的实物联调。"""
import json
from pathlib import Path
import time
import unittest
import test_ctl
from sdk_bridge import SDKBridge


class InputTests(unittest.TestCase):
    setUp=test_ctl.HTTPTests.setUp
    tearDown=test_ctl.HTTPTests.tearDown
    rejected=test_ctl.HTTPTests.rejected
    select=test_ctl.HTTPTests.select

    def speech(self, **fields):
        return self.clients['voice'].discrete('/api/voice-result',intent_sequence=self.clients['voice'].intent,
            recognized=fields.pop('recognized',True),confidence=fields.pop('confidence',.95),**fields)

    def test_low_confidence_unknown_and_partial_do_not_move(self):
        self.select('voice')
        for fields in [dict(text='前进',confidence=.4),dict(text='前进',recognized=False),
                       dict(text='前进',final=False),dict(text='天气很好')]:
            state=self.speech(**fields)
            self.assertEqual(state['command_status'],'IGNORED')
            self.assertEqual(state['mode'],'HOLD')
            self.assertIsNone(state['active_source'])
        self.rejected('INVALID_ARGUMENT',lambda:self.speech(text='前进',command_code='backward'))

    def test_chinese_direction_hover_and_feedback(self):
        self.select('voice')
        state=self.speech(text=' 前进！ ',duration_ms=300)
        self.assertEqual(state['command_status'],'EXECUTING')
        voice=self.clients['voice']
        record=voice.request('/api/commands/voice/'+state['command_id'])
        self.assertEqual(record['semantic_action'],'forward')
        self.assertEqual(self.speech(text='悬停')['mode'],'HOLD')

    def test_dangerous_confirmation_and_idempotence(self):
        self.select('voice')
        state=self.speech(text='下降',duration_ms=500)
        self.assertEqual(state['command_status'],'WAITING_CONFIRMATION')
        self.assertEqual(state['mode'],'HOLD')
        app=self.clients['app']
        self.rejected('CONFIRMATION_REQUIRED',lambda:app.discrete('/api/confirm',target_command_id=state['command_id'],confirmed=False))
        result=app.discrete('/api/confirm',target_command_id=state['command_id'],confirmed=True)
        self.assertEqual(result['active_source'],'voice')
        record=app.request('/api/commands/voice/'+state['command_id'])
        self.assertEqual(record['status'],'EXECUTING')
        self.assertEqual(record['reason'],'OPERATOR_CONFIRMED')

    def test_unsupported_tasks_are_not_faked(self):
        self.select('voice')
        for text in ('起飞','降落','返航'):
            self.rejected('TASK_NOT_SUPPORTED',lambda:self.speech(text=text))
        self.assertEqual(self.clients['app'].request('/api/state')['mode'],'HOLD')

    def test_ring_real_event_semantics_and_stop(self):
        ring,app=self.clients['ring'],self.clients['app']
        app.switch('ring')
        bridge=SDKBridge('ring',ring,Path(__file__).resolve().parent/'results/protocol_test_events.jsonl','SIMULATION_TEST_ONLY')
        def event(sequence, **fields):
            return dict(sequence=sequence,timestamp_ms=time.time()*1000,ttl_ms=500,**fields)
        state=bridge.handle(event(0,event_kind='axes',phase='neutral',axes=[0.,0.,0.,0.]))
        self.assertEqual(state['selected_source'],'ring')
        state=bridge.handle(event(1,event_kind='axes',phase='begin',axes=[.6,0.,0.,0.]))
        self.assertEqual(state['active_source'],'ring')
        state=bridge.handle(event(2,event_kind='button',button='stop',pressed=True))
        self.assertEqual(state['mode'],'HOLD')
        with self.assertRaises(ValueError):
            bridge.handle(event(2,event_kind='axes',phase='begin',axes=[.6,0.,0.,0.]))
        state=bridge.handle(event(3,event_kind='connection',connected=False))
        self.assertEqual(state['protection_reason'],'PERIPHERAL_DISCONNECTED')

    def test_configuration_and_confirm_timeout(self):
        app=self.clients['app']
        policy=app.request('/api/state')['control_config']['input_policy']
        policy['voice_vocabulary']['往前飞']='forward'
        app.discrete('/api/config',config_version=1,patch={'input_policy':policy})
        self.select('voice')
        self.assertEqual(self.speech(text='往前飞',duration_ms=100)['command_status'],'EXECUTING')
        self.clients['voice'].stop()
        self.clients['voice'].begin_intent()
        state=self.speech(text='下降')
        key=(self.clients['voice'].session_id,state['command_id'])
        self.service.confirmations[key]['until']=time.monotonic()-1
        time.sleep(.02)
        self.assertEqual(app.request('/api/commands/voice/'+state['command_id'])['status'],'TIMED_OUT')

    def test_switch_cancels_pending_confirmation(self):
        self.select('voice')
        state=self.speech(text='下降')
        app=self.clients['app']
        app.switch('app')
        self.assertEqual(app.request('/api/commands/voice/'+state['command_id'])['status'],'CANCELLED')
        self.rejected('NO_PENDING_CONFIRMATION',lambda:app.discrete('/api/confirm',target_command_id=state['command_id'],confirmed=True))

    def test_sdk_timestamp_is_not_rewritten(self):
        self.select('voice')
        bridge=SDKBridge('voice',self.clients['voice'],Path(__file__).resolve().parent/'results/protocol_test_events.jsonl','SIMULATION_TEST_ONLY')
        with self.assertRaises(ValueError):
            bridge.handle(dict(sequence=0,timestamp_ms=time.time()*1000-1000,ttl_ms=500,text='前进',recognized=True,confidence=.95))
        self.assertIsNone(self.clients['app'].request('/api/state')['active_source'])

    def test_mapping_cannot_bypass_dangerous_confirmation(self):
        app=self.clients['app']
        mapping=app.request('/api/state')['control_config']['voice_mapping']
        mapping['forward']='down'
        app.discrete('/api/config',config_version=1,patch={'voice_mapping':mapping})
        self.select('voice')
        self.assertEqual(self.speech(text='前进')['command_status'],'WAITING_CONFIRMATION')
        self.assertIsNone(app.request('/api/state')['active_source'])


if __name__=='__main__':
    unittest.main(verbosity=2)
