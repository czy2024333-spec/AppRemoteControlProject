"""本机三路真实 HTTP 控制与四路全机 SSE 观察；不连接已运行的现场服务。"""
import argparse
import concurrent.futures
import http.client
import json
import math
from pathlib import Path
import threading
import time
from independent_client import IndependentClient
from independent_fleet import IndependentFleet
from independent_server import make_independent_server


def run(seconds, directory):
    directory = Path(directory)
    directory.mkdir(parents=True,exist_ok=False)
    config = json.loads(Path(__file__).with_name('cluster_config.json').read_text('utf-8'))
    credentials = json.loads(Path(__file__).with_name('independent_credentials.json').read_text('utf-8'))
    fleet = IndependentFleet(config,credentials,directory/'instances')
    server = make_independent_server(fleet,port=0)
    server_thread = threading.Thread(target=lambda:server.serve_forever(poll_interval=.02),daemon=True)
    server_thread.start()
    url = f'http://127.0.0.1:{server.server_port}'
    clients = [IndependentClient(row['device_id'],row['token'],url) for row in credentials['credentials'][:3]]
    started = threading.Event()
    stopping = threading.Event()
    window = {}
    readers = [dict(client_id=row['client_id'],frames=[],errors=[],first=None,last=None,ready=threading.Event())
        for row in credentials['credentials']]
    controls = [dict(device_id=client.device_id,durations_ms=[],errors=[]) for client in clients]

    def read_stream(index):
        row = credentials['credentials'][index]
        record = readers[index]
        connection = http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=3)
        try:
            connection.request('GET','/api/v1/events',headers={'Authorization':'Bearer '+row['token']})
            response = connection.getresponse()
            if response.status!=200:
                raise RuntimeError('SSE HTTP '+str(response.status))
            record['ready'].set()
            while not stopping.is_set():
                line = response.readline()
                if not line:
                    raise RuntimeError('SSE EOF')
                if not line.startswith(b'data: '):
                    continue
                data = json.loads(line[len(b'data: '):])
                now = time.perf_counter()
                if not started.is_set() or now>window['end']:
                    continue
                if record['first'] is None:
                    record['first'] = data
                record['last'] = data
                record['frames'].append(now)
                members = data['members']
                if len(members)!=3 or data['self_device_id']!=row['device_id']:
                    raise RuntimeError('全机列表或自身标识错误')
                if sum(m['control']['write_permitted'] for m in members)!=(1 if index<3 else 0):
                    raise RuntimeError('设备权限投影错误')
                if any(not m['online'] or m['mode']=='PROTECTED' for m in members):
                    raise RuntimeError('运行中发现失联或保护')
        except Exception as exc:
            if not stopping.is_set():
                record['errors'].append(str(exc))
        finally:
            record['ready'].set()
            connection.close()

    def control(index):
        client = clients[index]
        record = controls[index]
        started.wait()
        while time.perf_counter()<window['end']:
            before = time.perf_counter()
            sign = 1 if int((before-window['start'])/2)%2==0 else -1
            try:
                state = client.control(vx=.12*sign if index==0 else 0.,
                    vy=.12*sign if index==1 else 0.,vz=.1*sign if index==2 else 0.,frame='world')
                record['durations_ms'].append((time.perf_counter()-before)*1000)
                if state['mode']!='ACTIVE':
                    raise RuntimeError('运动未处于 ACTIVE: '+state['mode'])
            except Exception as exc:
                record['errors'].append(str(exc))
                return
            time.sleep(max(.001,.05-(time.perf_counter()-before)))

    threads = [threading.Thread(target=read_stream,args=(i,),daemon=True) for i in range(4)]
    try:
        for thread in threads:
            thread.start()
        for reader in readers:
            if not reader['ready'].wait(3):
                raise RuntimeError('观察端启动超时')
        for client in clients:
            client.open_session(); client.switch('app'); client.neutral(); client.begin_intent()
        window.update(start=time.perf_counter())
        window['end'] = window['start']+seconds
        started.set()
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            list(pool.map(control,range(3)))
        for client in clients:
            client.stop()
    finally:
        stopping.set()
        server.shutdown(); server.server_close()
        for thread in threads:
            if thread.ident is not None:
                thread.join(4)
        for client in clients:
            client.close()
        audit_drained = fleet.close()

    all_controls = []
    for control_record in controls:
        samples = sorted(control_record.pop('durations_ms'))
        all_controls.append(dict(**control_record,samples=len(samples),
            p95_ms=samples[math.ceil(len(samples)*.95)-1] if samples else None,max_ms=max(samples) if samples else None))
    all_readers = []
    for reader in readers:
        frames = reader['frames']
        all_readers.append(dict(client_id=reader['client_id'],samples=len(frames),
            frames_per_test_second=len(frames)/seconds,errors=reader['errors']))
        (directory/(reader['client_id']+'-first.json')).write_text(json.dumps(reader['first'],ensure_ascii=False,indent=2),encoding='utf-8')
        (directory/(reader['client_id']+'-last.json')).write_text(json.dumps(reader['last'],ensure_ascii=False,indent=2),encoding='utf-8')
    passed = all(not c['errors'] and c['samples']>0 and c['p95_ms']<=100 for c in all_controls)
    passed = passed and all(not r['errors'] and r['frames_per_test_second']>=20 for r in all_readers) and audit_drained
    summary = dict(passed=passed,duration_s=seconds,online_instances=3,controlled_instances=3,sensor_enabled_instances=3,
        control_ttl_ms=500,controls=all_controls,observers=all_readers,audit_drained=audit_drained,audit=fleet.audit.status(),
        scope='本机回环；3路Python HTTP控制+4路SSE观察，非手机刷新率、非局域网或4小时验收')
    (directory/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False,indent=2))
    return passed


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds',type=int,default=60)
    parser.add_argument('--out',type=Path,default=Path('results/independent_acceptance'))
    args = parser.parse_args()
    if not 2<=args.seconds<=600:
        parser.error('测试时长范围2至600秒')
    raise SystemExit(0 if run(args.seconds,args.out) else 1)
