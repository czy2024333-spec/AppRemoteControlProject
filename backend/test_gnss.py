"""局部 GNSS 误差模型回归，覆盖实际延迟、时间积分、丢包与三机读取。"""
import http.client
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from app_server import DEMO_TOKENS
from cluster_manager import ClusterManager
from cluster_member import ManagedMember
from config_validation import validate_cluster_config
from gnss import LocalGnssSimulator, validate_gnss_config
from independent_fleet import IndependentFleet
from independent_server import make_independent_server
from unified_contract import device_descriptor, gnss_local_channel
from unified_server import make_unified_server


def settings(**changes):
    result = dict(enabled=True,rate_hz=20,noise_std_m=0.,drift_m_per_s=[0.,0.,0.],
        latency_ms=0,loss_probability=0.,stale_after_ms=500)
    result.update(changes)
    return result


def sample(simulator, seconds, position=None):
    return simulator.update([seconds,0.,1.] if position is None else position,seconds,1000000+seconds*1000)


class GnssModelTests(unittest.TestCase):
    def test_config_defaults_backward_compatibility_and_invalid_values(self):
        config = json.loads(Path('cluster_config.json').read_text(encoding='utf-8'))
        config.pop('gnss')
        self.assertNotIn('gnss',validate_cluster_config(config))
        self.assertFalse(validate_gnss_config({})['enabled'])
        for change in (
            dict(enabled=1),dict(rate_hz=0),dict(rate_hz=1.5),dict(noise_std_m=-1),
            dict(noise_std_m=10**400),dict(latency_ms=-1),dict(latency_ms=5001),
            dict(loss_probability=float('nan')),dict(loss_probability=True),
            dict(drift_m_per_s=[0.,float('inf'),0.]),dict(drift_m_per_s=[0.,0.]),
            dict(stale_after_ms=0),dict(home_lat_deg=22.5),
        ):
            with self.subTest(fields=tuple(change)), self.assertRaises(ValueError):
                validate_gnss_config(change)

    def test_delay_delivers_historical_position_and_preserves_original_time(self):
        sim = LocalGnssSimulator(settings(latency_ms=80,rate_hz=50))
        self.assertIsNone(sample(sim,0.)['sample'])
        self.assertIsNone(sample(sim,.02,[4.,0.,1.])['sample'])
        first = sample(sim,.08,[8.,0.,1.])['sample']
        self.assertEqual(first['position_m'],[0.,0.,1.])
        self.assertEqual(first['measured_at_unix_ms'],1000000)
        second = sample(sim,.10,[10.,0.,1.])['sample']
        self.assertEqual(second['position_m'],[4.,0.,1.])
        self.assertEqual(second['simulation_time_s'],.02)
        self.assertEqual(second['delivered_at_simulation_time_s'],.10)

    def test_sample_clock_matches_grid_and_interpolates_all_axes_and_wall_time(self):
        # 从非整点开始，墙钟故意以不同于仿真钟的速度走，检验两个时钟各自的插值。
        start_s = .013
        for physics_hz in (50, 55, 100):
            with self.subTest(physics_hz=physics_hz):
                sim = LocalGnssSimulator(settings())
                samples = []
                for step in range(physics_hz + 1):
                    elapsed = step / physics_hz
                    position = [2 * elapsed, -3 * elapsed, 1 + .5 * elapsed]
                    result = sim.update(position, start_s + elapsed, 1000000 + 1250 * elapsed)
                    value = result['sample']
                    if value['sequence'] > len(samples):
                        samples.append(value)
                self.assertEqual(len(samples), 21)
                for slot, value in enumerate(samples):
                    elapsed = slot / 20
                    self.assertAlmostEqual(value['simulation_time_s'], start_s + elapsed)
                    self.assertAlmostEqual(value['measured_at_unix_ms'], 1000000 + 1250 * elapsed)
                    expected = [2 * elapsed, -3 * elapsed, 1 + .5 * elapsed]
                    for actual, target in zip(value['position_m'], expected):
                        self.assertAlmostEqual(actual, target)

    def test_delay_starts_at_sample_time_and_duplicate_publication_keeps_anchor(self):
        sim = LocalGnssSimulator(settings(latency_ms=30))
        sample(sim, 0.)
        sample(sim, .02)
        anchor = [.04, 0., 1.]
        sim.update(anchor, .04, 1000040)
        # 调用者复用位置数组或同一步重新发布指令，都不能改变上一步的采样依据。
        anchor[0] = 80.
        sim.update([70., 0., 1.], .04, 2000000)
        self.assertEqual(sample(sim, .06)['packet_sequence'], 1)
        delivered = sample(sim, .08)['sample']
        self.assertEqual(delivered['sequence'], 2)
        self.assertAlmostEqual(delivered['position_m'][0], .05)
        self.assertAlmostEqual(delivered['simulation_time_s'], .05)
        self.assertAlmostEqual(delivered['measured_at_unix_ms'], 1000050)
        self.assertAlmostEqual(delivered['delivered_at_simulation_time_s'], .08)

    def test_gap_resumes_from_observed_position_and_preserves_elapsed_drift(self):
        sim = LocalGnssSimulator(settings(drift_m_per_s=[.5, 0., 0.]))
        sample(sim, 0.)
        sample(sim, .02)
        # 中间缺少真值时，从恢复点重新计采样周期，只交付该点一次。
        resumed = sample(sim, 10.013, [30., 2., 1.])
        self.assertEqual(resumed['packet_sequence'], 2)
        self.assertEqual(resumed['sample']['simulation_time_s'], 10.013)
        self.assertAlmostEqual(resumed['sample']['position_m'][0], 30. + .5 * 10.013)
        self.assertEqual(sample(sim, 10.033)['packet_sequence'], 2)
        self.assertEqual(sample(sim, 10.053)['packet_sequence'], 2)
        following = sample(sim, 10.073)['sample']
        self.assertEqual(following['sequence'], 3)
        self.assertAlmostEqual(following['simulation_time_s'], 10.063)

    def test_drift_depends_on_elapsed_time_across_sampling_and_step_rates(self):
        ends = []
        for rate,step_hz in ((10,50),(20,55),(20,100)):
            sim = LocalGnssSimulator(settings(rate_hz=rate,drift_m_per_s=[.2,-.1,.05]))
            for step in range(step_hz+1):
                result = sample(sim,step/step_hz,[0.,0.,1.])
                if step==0:
                    for _ in range(10):
                        self.assertEqual(sample(sim,0.,[0.,0.,1.]),result)
            ends.append(result['sample']['position_m'])
        for value in ends:
            self.assertEqual(value,[.2,-.1,1.05])

    def test_noise_is_reproducible_and_extra_reads_do_not_change_rng(self):
        left = LocalGnssSimulator(settings(noise_std_m=.3),seed=101)
        right = LocalGnssSimulator(settings(noise_std_m=.3),seed=101)
        first = sample(left,0.)
        self.assertEqual(first,sample(right,0.))
        for _ in range(10):
            self.assertEqual(sample(left,0.),first)
        self.assertEqual(sample(left,.05),sample(right,.05))
        other = LocalGnssSimulator(settings(noise_std_m=.3),seed=102)
        self.assertNotEqual(first['sample']['position_m'],sample(other,0.)['sample']['position_m'])

    def test_noise_and_loss_can_be_varied_independently_across_restarts(self):
        with_both = LocalGnssSimulator(settings(noise_std_m=.8, loss_probability=.3), seed=101)
        loss_only = LocalGnssSimulator(settings(loss_probability=.3), seed=101)
        noise_only = LocalGnssSimulator(settings(noise_std_m=.8), seed=101)
        histories = []
        for _ in range(2):
            # 第二遍将仿真钟拨回零，两个随机源都应该复现第一遍的轨迹。
            history = []
            for step in range(101):
                t = step / 20
                both, lost, noisy = (sample(sim, t) for sim in (with_both, loss_only, noise_only))
                self.assertEqual(both['last_packet_lost'], lost['last_packet_lost'])
                if not both['last_packet_lost']:
                    self.assertEqual(both['sample'], noisy['sample'])
                history.append(both)
            self.assertGreater(history[-1]['lost_packets'], 0)
            self.assertLess(history[-1]['lost_packets'], len(history))
            histories.append(history)
        self.assertEqual(histories[0], histories[1])

    def test_loss_preserves_previous_sample_until_a_new_packet_arrives(self):
        sim = LocalGnssSimulator(settings(loss_probability=.5))
        with patch.object(sim.loss_rng,'random',side_effect=[.9,.1,.9]):
            first = sample(sim,0.)
            lost = sample(sim,.05)
            restored = sample(sim,.1)
        self.assertEqual(lost['sample'],first['sample'])
        self.assertEqual((lost['packet_sequence'],lost['lost_packets']),(2,1))
        self.assertTrue(lost['last_packet_lost'])
        raw = dict(gnss_local_measurement=lost,simulation_time_s=.05,state_age_ms=10.)
        field = gnss_local_channel(raw,True)
        self.assertEqual((field['validity'],field['reason'],field['age_ms']),
            ('STALE','SIMULATED_PACKET_LOSS',60.))
        self.assertFalse(restored['last_packet_lost'])
        self.assertEqual(restored['sample']['sequence'],3)

    def test_packet_loss_becomes_visible_after_the_configured_delay(self):
        sim = LocalGnssSimulator(settings(latency_ms=80,loss_probability=.5))
        with patch.object(sim.loss_rng,'random',side_effect=[.9,.1,.9]):
            sample(sim,0.)
            self.assertFalse(sample(sim,.05)['last_packet_lost'])
            self.assertIsNotNone(sample(sim,.08)['sample'])
            lost = sample(sim,.13)
        self.assertTrue(lost['last_packet_lost'])
        self.assertEqual(lost['sample']['simulation_time_s'],0.)

    def test_initial_loss_waiting_delay_staleness_and_offline_are_explicit(self):
        delayed = LocalGnssSimulator(settings(latency_ms=80,stale_after_ms=50))
        waiting = sample(delayed,0.)
        raw = dict(gnss_local_measurement=waiting,simulation_time_s=0.,state_age_ms=0.)
        self.assertEqual(gnss_local_channel(raw,True)['reason'],'WAITING_DELAYED_SAMPLE')
        raw.update(gnss_local_measurement=sample(delayed,.08),simulation_time_s=.08)
        self.assertEqual(gnss_local_channel(raw,True)['reason'],'SAMPLE_TOO_OLD')
        self.assertEqual(gnss_local_channel(raw,False)['reason'],'LINK_LOST')
        raw['gnss_local_measurement'] = sample(LocalGnssSimulator(settings(loss_probability=1.)),0.)
        self.assertEqual(gnss_local_channel(raw,True)['validity'],'INVALID')

    def test_sample_at_expiry_boundary_is_valid_until_age_exceeds_threshold(self):
        sim = LocalGnssSimulator(settings(latency_ms=50, stale_after_ms=50))
        for step in range(9):
            now = step / 20
            record = sample(sim, now)
        raw = dict(gnss_local_measurement=record, simulation_time_s=now, state_age_ms=0.)
        at_limit = gnss_local_channel(raw, True)
        self.assertEqual((at_limit['age_ms'], at_limit['validity'], at_limit['reason']), (50., 'VALID', None))
        raw['state_age_ms'] = .2
        expired = gnss_local_channel(raw, True)
        self.assertEqual((expired['age_ms'], expired['validity'], expired['reason']), (50.2, 'STALE', 'SAMPLE_TOO_OLD'))

    def test_delay_buffer_is_bounded_and_clock_reset_discards_old_packets(self):
        sim = LocalGnssSimulator(settings(latency_ms=5000,rate_hz=50))
        for step in range(1001):
            result = sample(sim,step/100)
            self.assertLessEqual(result['pending_samples'],252)
        self.assertIsNotNone(result['sample'])
        reset = sample(sim,0.,[20.,0.,1.])
        self.assertIsNone(reset['sample'])
        self.assertEqual(reset['packet_sequence'],0)
        self.assertEqual(reset['pending_samples'],1)


class GnssMemberTests(unittest.TestCase):
    def test_member_measurement_does_not_change_truth_or_existing_sensor_samples(self):
        spec = json.loads(Path('cluster_config.json').read_text(encoding='utf-8'))['members'][0]
        base = Path('results/gnss_tests')
        base.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as directory:
            enabled = ManagedMember(spec,'test',DEMO_TOKENS,Path(directory)/'enabled',
                gnss_config=settings(noise_std_m=1.,drift_m_per_s=[.1,0.,0.]))
            disabled = ManagedMember(spec,'test',DEMO_TOKENS,Path(directory)/'disabled')
            try:
                for _ in range(10):
                    enabled.tick(); disabled.tick()
                on,off = enabled.state(),disabled.state()
                self.assertEqual(on['position_m'],off['position_m'])
                self.assertEqual(on['quaternion_xyzw'],off['quaternion_xyzw'])
                self.assertEqual(on['sensor_measurement'],off['sensor_measurement'])
                self.assertNotEqual(on['gnss_local_measurement']['sample']['position_m'],on['position_m'])
                descriptor = device_descriptor(on)
                self.assertTrue(descriptor['capabilities']['gnss_local_simulation'])
                self.assertFalse(descriptor['capabilities']['gnss'])
                self.assertNotIn('gnss_local_measurement',off)
            finally:
                enabled.close(); disabled.close()


class GnssHttpTests(unittest.TestCase):
    def assert_sample_timing(self, members):
        self.assertEqual(len(members), 3)
        for member in members:
            field = member['sensors']['gnss_local_position']
            self.assertEqual((field['source'], field['unit'], field['validity']), ('SIM_GNSS', 'm', 'VALID'))
            value = field['value']
            self.assertGreaterEqual(value['sequence'], 2)
            self.assertAlmostEqual(value['simulation_time_s'], (value['sequence'] - 1) / 20)
            # 80 ms 延迟期满后在第一个可用的 50 Hz 动力学步交付。
            delay_s = value['delivered_at_simulation_time_s'] - value['simulation_time_s']
            self.assertGreaterEqual(delay_s, .080 - 1e-9)
            self.assertLess(delay_s, .100 + 1e-9)
            self.assertGreaterEqual(field['age_ms'], 80. - 1e-6)
            self.assertIsNone(member['gnss']['value'])
            self.assertNotIn('latitude_deg', value)

    def test_both_entry_points_publish_local_samples_through_state_and_sse(self):
        config = json.loads(Path('cluster_config.json').read_text(encoding='utf-8'))
        config['gnss'] = settings(latency_ms=80,noise_std_m=.2)
        credentials = json.loads(Path('independent_credentials.json').read_text(encoding='utf-8'))
        root = Path('results/gnss_http_tests')
        root.mkdir(parents=True,exist_ok=True)
        for independent in (True,False):
            with self.subTest(independent=independent), tempfile.TemporaryDirectory(dir=root) as directory:
                if independent:
                    service = IndependentFleet(config,credentials,directory)
                    server = make_independent_server(service,port=0)
                    token = credentials['credentials'][3]['token']
                else:
                    service = ClusterManager(config,directory)
                    server = make_unified_server(service,port=0)
                    token = DEMO_TOKENS['app']
                thread = threading.Thread(target=lambda:server.serve_forever(poll_interval=.02),daemon=True)
                thread.start()
                try:
                    headers = dict(Authorization='Bearer '+token)
                    deadline = time.monotonic()+3
                    states = []
                    while time.monotonic()<deadline:
                        connection = http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=3)
                        try:
                            connection.request('GET','/api/v1/state',headers=headers)
                            response = connection.getresponse()
                            self.assertEqual(response.status,200)
                            states = json.loads(response.read())['data']['members']
                        finally:
                            connection.close()
                        if all(m['sensors']['gnss_local_position']['packet_sequence'] >= 2 for m in states):
                            break
                        time.sleep(.02)
                    self.assert_sample_timing(states)
                    connection = http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=3)
                    try:
                        connection.request('GET','/api/v1/events',headers=headers)
                        response = connection.getresponse()
                        self.assertEqual(response.status,200)
                        deadline = time.monotonic() + 3
                        while time.monotonic() < deadline and (line := response.readline()):
                            if not line.startswith(b'data: '):
                                continue
                            members = json.loads(line[6:])['members']
                            # 新订阅可能先收到上一帧缓存，等三台都有第二个采样再核对时间。
                            if all(m['sensors']['gnss_local_position']['packet_sequence'] >= 2 for m in members):
                                self.assert_sample_timing(members)
                                break
                        else:
                            self.fail('未收到包含三机后续GNSS采样的状态帧')
                    finally:
                        connection.close()
                finally:
                    server.shutdown(); server.server_close(); service.close(); thread.join(2)


if __name__=='__main__':
    unittest.main(verbosity=2)
