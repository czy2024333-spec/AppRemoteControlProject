"""持续风/阵风/冲量实验：原PD vs积分抗扰，以及真实DLL与RotorPy对照。"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from scipy.spatial.transform import Rotation
from simulation_core import SimulationCore
from cpp_core_3d import CppCore3D
from wind_profiles import wind_at, impulses_at_tick


def recovery_time(records, start_s):
    position=np.array([s['state']['x'] for s in records])
    velocity=np.array([s['state']['v'] for s in records])
    angular=np.array([s['state']['w'] for s in records])
    valid=(np.linalg.norm(position-[0.,0.,1.],axis=1)<.01)&(np.linalg.norm(velocity,axis=1)<.01)&(np.linalg.norm(angular,axis=1)<.01)
    # 返回首次满足且余下整个记录均满足的时刻，排除瞬时穿过容差带。
    stable=np.logical_and.accumulate(valid[::-1])[::-1]
    for index,ok in enumerate(stable):
        if ok and index*.01>=start_s: return round(index*.01-start_s,2)
    return None


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,default=Path(__file__).parent/'results/disturbance')
    parser.add_argument('--python-only',action='store_true')
    args=parser.parse_args(); args.out.mkdir(parents=True,exist_ok=True)
    reports={}; plot_data={}
    for scenario in ('steady','gust','impulse'):
        baseline,python=SimulationCore(),SimulationCore()
        python.enable_disturbance_compensation()
        cpp=None if args.python_only else CppCore3D()
        if cpp: cpp.enable_disturbance_compensation()
        records={'baseline':[baseline.get_state()],'python':[python.get_state()],'cpp':[] if cpp is None else [cpp.get_state()]}
        try:
            for tick in range(2500):
                now=tick*.01; wind=wind_at(scenario,now)
                for core in (baseline,python): core.set_wind(wind)
                if cpp: cpp.set_wind(wind)
                impulse=impulses_at_tick(scenario,tick)
                if impulse:
                    for core in (baseline,python): core.apply_impulse(*impulse)
                    if cpp: cpp.apply_impulse(*impulse)
                records['baseline'].append(baseline.step())
                records['python'].append(python.step())
                if cpp: records['cpp'].append(cpp.step(now))
        finally:
            if cpp: cpp.close()
        for name,items in records.items():
            if items:
                (args.out/(scenario+'_'+name+'.jsonl')).write_text(''.join(json.dumps(s,ensure_ascii=False)+'\n' for s in items),encoding='utf-8')
        p=np.array([s['state']['x'] for s in records['python']]); b=np.array([s['state']['x'] for s in records['baseline']])
        error=np.linalg.norm(p-[0.,0.,1.],axis=1); baseline_error=np.linalg.norm(b-[0.,0.,1.],axis=1)
        recovery_start={'steady':13.,'gust':12.,'impulse':4.}[scenario]
        report={'max_position_error_m':float(error.max()),'final_position_error_m':float(error[-1]),
                'recovery_time_s':recovery_time(records['python'],recovery_start),
                'baseline_final_position_error_m':float(baseline_error[-1]),
                'baseline_recovery_time_s':recovery_time(records['baseline'],recovery_start),
                'compensation_max_abs_m_s2':max(abs(v) for s in records['python'] for v in s['environment']['compensation_acceleration_m_s2'])}
        checks={'recovered':report['recovery_time_s'] is not None,'bounded_error':report['max_position_error_m']<.3,
                'no_unexpected_protection':all(s['mode']=='HOLD' for s in records['python']),
                'compensation_bounded':report['compensation_max_abs_m_s2']<=1.}
        if scenario=='steady':
            baseline_tail=float(np.mean(baseline_error[1000:1200])); tail=float(np.mean(error[1000:1200]))
            report.update({'steady_baseline_error_m':baseline_tail,'steady_compensated_error_m':tail,
                           'steady_error_reduction_fraction':1-tail/baseline_tail})
            checks['steady_offset_reduction']=tail<baseline_tail*.2
        if records['cpp']:
            cp=np.array([s['state']['x'] for s in records['cpp']])
            angles=(Rotation.from_quat(np.array([s['state']['q'] for s in records['python']])).inv()*
                    Rotation.from_quat(np.array([s['state']['q'] for s in records['cpp']]))).magnitude()
            report['comparison']={'position_max_difference_m':float(np.linalg.norm(cp-p,axis=1).max()),
                                  'attitude_max_difference_rad':float(angles.max()),
                                  'cpp_recovery_time_s':recovery_time(records['cpp'],recovery_start)}
            checks['cross_model_regression']=report['comparison']['position_max_difference_m']<.02 and report['comparison']['attitude_max_difference_rad']<.02
            checks['cpp_recovered']=report['comparison']['cpp_recovery_time_s'] is not None
            checks['cpp_no_unexpected_protection']=all(s['mode']=='HOLD' for s in records['cpp'])
        report['checks']={key:bool(value) for key,value in checks.items()}
        reports[scenario]=report; plot_data[scenario]=(records,error,baseline_error)
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig,axes=plt.subplots(3,2,figsize=(13,10))
    for row,(scenario,(records,error,baseline_error)) in enumerate(plot_data.items()):
        time=np.arange(len(error))*.01
        left,right=axes[row]
        left.plot(time,baseline_error,label='Original PD'); left.plot(time,error,label='Integral compensation')
        left.axhline(.01,color='gray',linestyle=':',label='1 cm tolerance'); left.set(title=scenario+' / position error',ylabel='error (m)',xlabel='simulation time (s)'); left.legend(); left.grid(alpha=.25)
        wind=np.array([s['environment']['wind_m_s'] for s in records['python']])
        for index,axis in enumerate('xyz'): right.plot(time,wind[:,index],label='wind '+axis)
        if scenario=='impulse':
            right.axvline(2,color='red',linestyle='--',label='linear impulse'); right.axvline(4,color='purple',linestyle='--',label='angular impulse')
        right.set(title=scenario+' / disturbance schedule',ylabel='wind (m/s)',xlabel='simulation time (s)'); right.legend(); right.grid(alpha=.25)
    fig.tight_layout(); fig.savefig(args.out/'disturbance_recovery.png',dpi=150); plt.close(fig)
    report={'duration_per_case_s':25.,'samples_per_case':2501,'cases':reports,
            'recovery_definition':'position error, speed and body rate norms all <0.01; stay inside for remaining record; reference (0,0,1)',
            'validation_note':'deterministic simulated disturbances; provisional scenario regression, not real-flight or official empirical validation',
            'passed':all(all(s['checks'].values()) for s in reports.values())}
    if not args.python_only:
        report['dll_sha256']=hashlib.sha256((Path(__file__).parent/'cpp/build/drone_core_c.dll').read_bytes()).hexdigest()
    (args.out/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    if not report['passed']: raise SystemExit('disturbance regression failed')


if __name__=='__main__': main()
