"""三维固定输入演示及真实C++ DLL / RotorPy对照，生成轨迹图。"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from scipy.spatial.transform import Rotation
from simulation_core import SimulationCore
from cpp_core_3d import CppCore3D


def input_for_tick(tick):
    if tick<200: return (.3,0.,0.),0.
    if tick<400: return (0.,.3,.15),0.
    if tick<700: return (-.25,-.2,-.1),.25
    if tick<900: return (0.,0.,0.),-.25
    if tick<1100: return None
    if tick<1200: return (.2,.1,.1),0.
    return None # 最后输入11.9s，到期12.4s，继续保护悬停到15s。


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,default=Path(__file__).parent/'results/3d')
    parser.add_argument('--python-only',action='store_true',help='无需DLL，只运行RotorPy')
    args=parser.parse_args()
    args.out.mkdir(parents=True,exist_ok=True)
    python=SimulationCore()
    cpp=None if args.python_only else CppCore3D()
    python_records=[python.get_state()]
    cpp_records=[] if cpp is None else [cpp.get_state()]
    try:
        for tick in range(1500):
            now=tick*.01
            if tick==900:
                python.stop()
                if cpp: cpp.stop()
            command=input_for_tick(tick)
            if command is not None and tick%10==0:
                velocity,yaw=command
                python.set_motion(*velocity,yaw,.5)
                if cpp: cpp.set_motion(velocity,yaw,.5,now)
            python_records.append(python.step())
            if cpp: cpp_records.append(cpp.step(now))
    finally:
        if cpp: cpp.close()
    for name,records in (('python_states',python_records),('cpp_states',cpp_records)):
        if records:
            (args.out/(name+'.jsonl')).write_text(''.join(json.dumps(s,ensure_ascii=False)+'\n' for s in records),encoding='utf-8')
    p=np.array([s['state']['x'] for s in python_records])
    times=np.array([s['time_s'] for s in python_records])
    checks={'forward':p[200,0]>.35,'sideways_and_up':p[400,1]>.35 and p[400,2]>1.15,
            'reverse_diagonal':p[700,0]<p[400,0]-.25 and p[700,1]<p[400,1]-.25,
            'stop_holds':np.linalg.norm(p[1100]-p[900])<.03,
            'timeout':python_records[-1]['mode']=='PROTECTED' and python_records[-1]['protection_reason']=='INPUT_TIMEOUT',
            'protected_hover':np.linalg.norm(python_records[-1]['state']['v'])<.01}
    report={'samples':len(p),'checks':checks,'python_final':python_records[-1],
            'scenario':'world-axis velocity, coupled translation+yaw, stop, timeout; 15 s at 100 Hz'}
    if cpp_records:
        cp=np.array([s['state']['x'] for s in cpp_records]); difference=cp-p
        pq=Rotation.from_quat(np.array([s['state']['q'] for s in python_records]))
        cq=Rotation.from_quat(np.array([s['state']['q'] for s in cpp_records]))
        angles=(pq.inv()*cq).magnitude()
        report['comparison']={'position_rmse_m':float(np.sqrt(np.mean(np.sum(difference**2,axis=1)))),
                              'position_max_difference_m':float(np.max(np.linalg.norm(difference,axis=1))),
                              'attitude_max_difference_rad':float(np.max(angles)),
                              'note':'默认参数与桨毂阻力相同；C++使用RK4<=1ms，RotorPy使用RK45；偏航机体系角速度投影不同。'}
        report['cpp_final']=cpp_records[-1]
        dll=Path(__file__).parent/'cpp/build/drone_core_c.dll'
        report['dll_sha256']=hashlib.sha256(dll.read_bytes()).hexdigest()
        checks['cpp_protected_hover']=cpp_records[-1]['mode']=='PROTECTED' and np.linalg.norm(cpp_records[-1]['state']['v'])<.01
        # 本次演示的工程回归门槛，并非需求方确认的全工况精度验收。
        checks['cross_model_regression']=report['comparison']['position_max_difference_m']<.05 and report['comparison']['attitude_max_difference_rad']<.05
    for key in checks:
        checks[key]=bool(checks[key])
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig=plt.figure(figsize=(12,5))
    ax=fig.add_subplot(121,projection='3d'); ax.plot(*p.T,label='RotorPy')
    if cpp_records: ax.plot(*cp.T,'--',label='C++ rigid body')
    ax.scatter(*p[0],color='green',label='Start'); ax.scatter(*p[-1],color='red',label='Protected hold')
    ax.set(xlabel='x (m)',ylabel='y (m)',zlabel='z (m)',title='3D trajectory'); ax.legend()
    right=fig.add_subplot(122)
    for axis,name in enumerate(('x','y','z')): right.plot(times,p[:,axis],label=name)
    for value,label in ((9,'Stop'),(12.4,'Timeout')):
        right.axvline(value,color='gray',linestyle=':')
        right.text(value,.95,label,rotation=90,va='top',transform=right.get_xaxis_transform(),backgroundcolor='white')
    right.set(xlabel='simulation time (s)',ylabel='position (m)',title='Motion / stop / protected hold'); right.legend(); right.grid(alpha=.3)
    fig.tight_layout(); fig.savefig(args.out/'trajectory_3d.png',dpi=150); plt.close(fig)
    (args.out/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'checks':checks,'comparison':report.get('comparison')},ensure_ascii=False,indent=2))
    if not all(checks.values()): raise SystemExit('3D demo regression failed')


if __name__=='__main__': main()
