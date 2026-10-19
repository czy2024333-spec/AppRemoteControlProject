"""环境管理、冲量物理与抗扰恢复的行为测试。"""
import unittest
import numpy as np
from simulation_core import SimulationCore, CoreLimits
from wind_profiles import wind_at


class EnvironmentTests(unittest.TestCase):
    def run_steps(self, core, count):
        for _ in range(count): core.step()
        return core.get_state()

    def test_wind_copy_validation_and_reset(self):
        first,second=SimulationCore(),SimulationCore()
        wind=[2.,-1.,.2]; first.set_wind(wind); wind[0]=99
        self.assertEqual(first.get_state()['state']['wind'],[2.,-1.,.2])
        self.assertEqual(second.get_state()['state']['wind'],[0.,0.,0.])
        for value in ([6.,0.,0.],[float('nan'),0.,0.],[True,0.,0.],[1.,2.],np.array(1.),[[1.],[2.],[3.]]):
            with self.assertRaises(ValueError): first.set_wind(value)
        first.enable_disturbance_compensation(True); first.step(); first.reset()
        self.assertEqual(first.get_state()['state']['wind'],[0.,0.,0.])
        self.assertFalse(first.get_state()['environment']['compensation_enabled'])
        self.assertEqual(first.get_state()['environment']['compensation_acceleration_m_s2'],[0.,0.,0.])

    def test_world_wind_pushes_in_correct_direction(self):
        for axis in range(3):
            for sign in (-1,1):
                core=SimulationCore(); wind=[0.,0.,0.]; wind[axis]=float(sign)
                core.set_wind(wind)
                state=core.step()
                self.assertGreater(state['state']['v'][axis]*sign,0.)

    def test_impulse_changes_momentum_without_teleport(self):
        core=SimulationCore(); before=core.get_state()
        core.apply_impulse([.012,-.009,.003],[4.29e-6,-2.86e-6,5.78e-6])
        after=core.get_state()
        np.testing.assert_allclose(after['state']['v'],[.4,-.3,.1],atol=1e-12)
        np.testing.assert_allclose(after['state']['w'],[.3,-.2,.2],atol=1e-12)
        for field in ('x','q','rotor_speeds'): self.assertEqual(after['state'][field],before['state'][field])
        for field in ('mode','sequence','time_s','target_position_m'): self.assertEqual(after[field],before[field])
        state_before=core.get_state()
        with self.assertRaises(ValueError): core.apply_impulse([.04,0.,0.])
        with self.assertRaises(ValueError): core.apply_impulse([0.,0.,0.],[.001,0.,0.])
        self.assertEqual(core.get_state(),state_before)

    def test_impulse_recovers_to_original_hold(self):
        core=SimulationCore(); core.apply_impulse([.012,-.009,.003],[4.29e-6,-2.86e-6,5.78e-6])
        stopped=self.run_steps(core,800)
        self.assertLess(np.linalg.norm(np.array(stopped['state']['x'])-[0.,0.,1.]),.01)
        self.assertLess(np.linalg.norm(stopped['state']['v']),.01)
        self.assertLess(np.linalg.norm(stopped['state']['w']),.01)
        self.assertEqual(stopped['mode'],'HOLD')

    def test_integral_reduces_steady_wind_offset(self):
        baseline,compensated=SimulationCore(),SimulationCore()
        compensated.enable_disturbance_compensation(True)
        for core in (baseline,compensated): core.set_wind([2.,-1.,.25])
        b=self.run_steps(baseline,1200); c=self.run_steps(compensated,1200)
        error_b=np.linalg.norm(np.array(b['state']['x'])-[0.,0.,1.])
        error_c=np.linalg.norm(np.array(c['state']['x'])-[0.,0.,1.])
        self.assertGreater(error_b,.04)
        self.assertLess(error_c,.01)
        self.assertLess(error_c,error_b*.2)
        compensated.set_wind([0.,0.,0.]); c=self.run_steps(compensated,1200)
        self.assertLess(np.linalg.norm(np.array(c['state']['x'])-[0.,0.,1.]),.01)
        self.assertLess(np.linalg.norm(c['state']['v']),.01)

    def test_integral_bound_and_saturation_freeze(self):
        core=SimulationCore(); core.enable_disturbance_compensation()
        core.set_wind([5.,0.,0.])
        for _ in range(500):
            s=core.step()
            self.assertLessEqual(max(abs(v) for v in s['environment']['compensation_acceleration_m_s2']),1.)
        # 借助控制器输出的饱和标记验证冻结，而非重复实现积分公式。
        core=SimulationCore(); core.enable_disturbance_compensation()
        core._state['x'][0]=.1
        real=core.controller.update
        def saturated(t,state,flat):
            result=real(t,state,flat); result['cmd_motor_speeds'][:]=3000.; return result
        core.controller.update=saturated
        core.step()
        self.assertEqual(core.get_state()['environment']['compensation_acceleration_m_s2'],[0.,0.,0.])

    def test_environment_does_not_release_protection(self):
        core=SimulationCore(); core.protect('TEST')
        core.set_wind([1.,0.,0.]); core.apply_impulse([.003,0.,0.])
        core.enable_disturbance_compensation()
        self.run_steps(core,10)
        self.assertEqual(core.get_state()['mode'],'PROTECTED')
        with self.assertRaises(RuntimeError): core.set_motion(.1,0.,0.,0.,.5)
        bounded=SimulationCore(limits=CoreLimits(horizontal_radius_m=.02))
        bounded.set_wind([5.,0.,0.])
        self.run_steps(bounded,100)
        self.assertEqual(bounded.get_state()['mode'],'PROTECTED')

    def test_wind_profiles_are_repeatable_and_bounded(self):
        for name in ('steady','gust','impulse'):
            a=[wind_at(name,i*.01) for i in range(1500)]
            self.assertEqual(a,[wind_at(name,i*.01) for i in range(1500)])
            self.assertLessEqual(max(np.linalg.norm(v) for v in a),5.)
            self.assertEqual(wind_at(name,15.),(0.,0.,0.))
        with self.assertRaises(ValueError): wind_at('unknown',0.)


if __name__=='__main__': unittest.main(verbosity=2)
