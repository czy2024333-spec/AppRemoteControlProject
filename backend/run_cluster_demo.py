"""三机本机HTTP开发基线：集群、分组、单机接管、失联与退出，生成原始数据和报告。"""
import argparse
import ctypes
import json
import os
import platform
import struct
import threading
import time
import urllib.request
from pathlib import Path
import numpy as np
from app_server import DEMO_TOKENS
from cluster_manager import ClusterManager
from cluster_server import make_cluster_server
from cluster_client import ClusterClient
from runtime_info import IMPLEMENTATION_VERSION


def hardware():
    data = dict(os=platform.platform(), architecture=platform.machine() or 'not_available',
        cpu=os.environ.get('PROCESSOR_IDENTIFIER',platform.processor()) or 'not_available',
        process_bits=struct.calcsize('P')*8, logical_cpu_count=os.cpu_count())
    if os.name=='nt':
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,r'HARDWARE\DESCRIPTION\System\CentralProcessor\0') as key:
                data['cpu']=winreg.QueryValueEx(key,'ProcessorNameString')[0].strip()
        except OSError: pass
        class Memory(ctypes.Structure):
            _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong)]+[(k,ctypes.c_ulonglong) for k in
                ('total_physical','available_physical','total_page','available_page','total_virtual','available_virtual','extended')]
        status=Memory(); status.length=ctypes.sizeof(status)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            data['physical_memory_gib']=round(status.total_physical/2**30,2)
    data['gpu_used_for_dynamics']=False
    return data


def run(seconds=4.):
    directory=Path(__file__).parent/'results/cluster_demo_v07'
    directory.mkdir(parents=True,exist_ok=True)
    manager=ClusterManager(json.loads(Path(__file__).with_name('cluster_config.json').read_text(encoding='utf-8')),directory)
    server=make_cluster_server(manager,port=0)
    thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
    url=f'http://127.0.0.1:{server.server_port}'
    app=ClusterClient(url)
    samples,latencies,phase_results,errors=[],[],[],[]
    stop=threading.Event(); ready=threading.Event()
    phase={'name':'startup'}
    def read_stream():
        try:
            req=urllib.request.Request(url+'/api/cluster/events',headers={'Authorization':'Bearer '+DEMO_TOKENS['app']})
            with urllib.request.urlopen(req,timeout=3) as response:
                while not stop.is_set():
                    line=response.readline().decode('utf-8')
                    if line.startswith('data: '):
                        samples.append(dict(received_monotonic_s=time.monotonic(),phase=phase['name'],state=json.loads(line[6:])))
                        ready.set()
        except Exception as exc:
            if not stop.is_set(): errors.append(str(exc))
            ready.set()
    listener=threading.Thread(target=read_stream,daemon=True)
    try:
        app.open(); listener.start()
        assert ready.wait(3) and samples,errors
        for kind,options in [('cluster',{}),('group',{'group_id':'alpha'}),('single',{'device_id':'sim-drone-3'})]:
            phase['name']='switching'
            app.select(kind,takeover=True,**options)
            for path,fields in [('/api/session',{}),('/api/switch',dict(target_source='app',user_initiated=True)),('/api/neutral',dict(axes=[0.,0.,0.,0.]))]:
                result=app.batch(path,**fields)
                assert result['status']=='APPLIED',result
            app.begin_intent()
            phase['name']=kind
            started=next_send=time.monotonic()
            frames=0
            while time.monotonic()-started<seconds:
                before=time.perf_counter()
                result=app.controls(vx=.12,vy=.08 if kind=='cluster' else 0.,vz=.04 if kind=='single' else 0.)
                elapsed=(time.perf_counter()-before)*1000
                assert result['status']=='APPLIED',result
                latencies.append(dict(phase=kind,elapsed_ms=elapsed,member_count=result['succeeded']))
                frames+=1
                next_send+=.05
                time.sleep(max(0.,next_send-time.monotonic()))
            result=app.batch('/api/stop'); assert result['status']=='APPLIED',result
            phase_results.append(dict(kind=kind,member_ids=app.selection['member_ids'],input_frames=frames))
        phase['name']='membership'
        app.manage('/api/cluster/member-link',device_id='sim-drone-2',online=False)
        lost=app.state(); assert lost['online_count']==2 and lost['selection'] is None
        app.manage('/api/cluster/member-link',device_id='sim-drone-2',online=True)
        assert app.state()['selection'] is None
        app.manage('/api/cluster/unregister',device_id='sim-drone-3')
        assert app.state()['registered_count']==2
    finally:
        stop.set(); app.close(); manager.close(); server.shutdown(); server.server_close()
        listener.join(2)
    assert not errors, errors
    counts={}
    for kind in ('cluster','group','single'):
        rows=[r for r in samples if r['phase']==kind]
        rate=(len(rows)-1)/(rows[-1]['received_monotonic_s']-rows[0]['received_monotonic_s']) if len(rows)>1 else 0.
        counts[kind]=dict(samples=len(rows),received_hz=rate,max_active=max((r['state']['active_count'] for r in rows),default=0))
    times=[r['elapsed_ms'] for r in latencies]
    member_rates={}
    for name in ('sim-drone-1','sim-drone-2','sim-drone-3'):
        rows=[(r['received_monotonic_s'],s) for r in samples if r['phase']=='cluster'
              for s in r['state']['members'] if s['device_id']==name]
        elapsed=rows[-1][0]-rows[0][0]
        member_rates[name]=dict(physics_observed_hz=(rows[-1][1]['sequence']-rows[0][1]['sequence'])/elapsed,
            sensor_observed_hz=(rows[-1][1]['sensor_measurement']['sequence']-rows[0][1]['sensor_measurement']['sequence'])/elapsed)
    report=dict(implementation_version=IMPLEMENTATION_VERSION,manager_boot_id=manager.boot_id,
        hardware=hardware(),profile={'online':3,'controlled':3,'sensor_enabled':3,
        'physics_target_hz_per_member':int(round(1/next(iter(manager.members.values()))['service'].core.dt)),
        'sensor_target_hz_per_member':20,'stream_target_hz':25},
        sample_duration_s_per_phase=seconds,phases=phase_results,stream=counts,member_rates=member_rates,
        ack_samples=len(times),ack_p95_ms=float(np.percentile(times,95)),ack_max_ms=max(times),
        observed_max_online=max(r['state']['online_count'] for r in samples),
        observed_max_controlled=max(r['state']['active_count'] for r in samples),
        observed_max_sensor_enabled=max(r['state']['sensor_enabled_count'] for r in samples),
        formal_quantity_and_hardware_confirmed=False,app_render_rate_verified=False,
        hardware_sensor_acceptance=False,autonomous_collaboration_implemented=False)
    (directory/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    (directory/'states.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in samples),encoding='utf-8')
    (directory/'ack_latencies.json').write_text(json.dumps(latencies,indent=2),encoding='utf-8')
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig=plt.figure(figsize=(8,6)); ax=fig.add_subplot(111,projection='3d')
    for name in ('sim-drone-1','sim-drone-2','sim-drone-3'):
        xyz=np.array([s['position_m'] for r in samples for s in r['state']['members'] if s['device_id']==name])
        ax.plot(xyz[:,0],xyz[:,1],xyz[:,2],label=name)
    ax.set(xlabel='X (m)',ylabel='Y (m)',zlabel='Z (m)',title='Three independent simulated members')
    ax.legend(); fig.tight_layout(); fig.savefig(directory/'trajectories.png',dpi=150); plt.close(fig)
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--seconds',type=float,default=4.)
    args=parser.parse_args()
    if not 2<=args.seconds<=30: parser.error('seconds范围2至30')
    run(args.seconds)
