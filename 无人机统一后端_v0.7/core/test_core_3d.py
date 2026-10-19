"""三维运动、偏航与保护的行为回归，实际使用RotorPy四电机刚体。"""
import math
import unittest
import numpy as np
from scipy.spatial.transform import Rotation
from simulation_core import SimulationCore, CoreLimits


class Core3DTests(unittest.TestCase):
    def advance(self, core, ticks, velocity=None, yaw=0.):
        for tick in range(ticks):
            if velocity is not None and tick % 10 == 0:
                core.set_motion(*(float(v) for v in velocity), yaw, .5)
            core.step()
        return core.get_state()

    def test_six_signed_axis_movements_and_stop(self):
        for axis in range(3):
            for sign in (-1, 1):
                with self.subTest(axis=axis, sign=sign):
                    core = SimulationCore()
                    v = np.zeros(3); v[axis] = sign * .3
                    start = np.array(core.get_state()['state']['x'])
                    moving = self.advance(core, 250, v)
                    p = np.array(moving['state']['x'])
                    self.assertGreater((p[axis]-start[axis])*sign, .4)
                    if axis < 2:
                        self.assertGreater(np.linalg.norm(np.array(moving['state']['q'])[:2]), .0001)
                    core.stop()
                    stopped = self.advance(core, 600)
                    self.assertLess(np.linalg.norm(np.array(stopped['state']['x'])-p), .02)
                    self.assertLess(np.linalg.norm(stopped['state']['v']), .01)

    def test_diagonal_and_vector_acceleration(self):
        core = SimulationCore()
        core.set_motion(.3, .3, .2, 0., .5)
        previous = np.zeros(3)
        for _ in range(20):
            state = core.step()
            ref = np.array(state['reference_velocity_m_s'])
            self.assertLessEqual(np.linalg.norm(ref-previous), .005+1e-12)
            previous = ref
        state = self.advance(core, 250, (.3,.3,.2))
        self.assertGreater(state['state']['x'][0], .4)
        self.assertGreater(state['state']['x'][1], .4)
        self.assertGreater(state['state']['x'][2], 1.2)

    def test_yaw_then_hold_actual_heading(self):
        for sign in (-1,1):
            core=SimulationCore()
            s=self.advance(core,200,(0.,0.,0.),sign*.3)
            rotation=Rotation.from_quat(s['state']['q']).as_matrix()
            yaw=math.atan2(rotation[1,0],rotation[0,0])
            self.assertGreater(yaw*sign,.4)
            core.stop()
            stopped=self.advance(core,600)
            r=Rotation.from_quat(stopped['state']['q']).as_matrix()
            self.assertLess(abs(math.remainder(math.atan2(r[1,0],r[0,0])-yaw,2*math.pi)),.02)
            self.assertLess(np.linalg.norm(stopped['state']['w']),.01)
            self.assertAlmostEqual(np.linalg.norm(stopped['state']['q']),1.,places=10)

    def test_three_axes_expire_and_neutral(self):
        core=SimulationCore()
        core.set_motion(.3,.2,.2,.3,.1)
        self.advance(core,10)
        self.assertEqual(core.get_state()['mode'],'PROTECTED')
        self.assertEqual(core.get_state()['reference_velocity_m_s'],[0.,0.,0.])
        self.assertEqual(core.get_state()['yaw_rate_rad_s'],0.)
        with self.assertRaises(RuntimeError): core.set_motion(.1,0.,0.,0.,.5)
        core.resume(neutral_confirmed=True)
        self.assertEqual(core.get_state()['mode'],'HOLD')
        self.advance(core,300)
        self.assertLess(np.linalg.norm(core.get_state()['state']['v']),.01)

    def test_horizontal_fence(self):
        core=SimulationCore(limits=CoreLimits(horizontal_radius_m=.15))
        for _ in range(200):
            if core.get_state()['mode']=='PROTECTED': break
            core.set_motion(.5,0.,0.,0.,.5); core.step()
        self.assertEqual(core.get_state()['mode'],'PROTECTED')
        self.assertIn(core.get_state()['protection_reason'],{'HORIZONTAL_REFERENCE_LIMIT','HORIZONTAL_STATE_LIMIT'})

    def test_validation_and_isolation(self):
        first,second=SimulationCore(),SimulationCore()
        for args in ((.5,.5,0.,0.,.5),(0.,0.,.6,0.,.5),(0.,0.,0.,.6,.5),
                     (float('nan'),0.,0.,0.,.5),(True,0.,0.,0.,.5),(0.,0.,0.,0.,0.)):
            with self.assertRaises(ValueError): first.set_motion(*args)
        self.advance(first,100,(.3,0.,0.))
        self.assertEqual(second.get_state()['state']['x'],[0.,0.,1.])
        snapshot=first.get_state(); snapshot['reference_velocity_m_s'][0]=99
        self.assertNotEqual(first.get_state()['reference_velocity_m_s'][0],99)
        first.reset()
        self.assertEqual(first.get_state()['state']['x'],[0.,0.,1.])

    def test_numerical_exception_protects_and_preserves_state(self):
        core=SimulationCore()
        before=core.get_state()['state']
        def fail(*_): raise RuntimeError('injected integrator failure')
        core.vehicle.step=fail
        with self.assertRaises(RuntimeError): core.step()
        state=core.get_state()
        self.assertEqual(state['mode'],'PROTECTED')
        self.assertEqual(state['protection_reason'],'NUMERICAL_FAULT')
        self.assertEqual(state['state'],before)
        self.assertEqual(state['sequence'],0)


if __name__=='__main__': unittest.main(verbosity=2)
