"""在本机临时端口验证三路 HTTP 控制及 SSE，支持多流与并发日志负载。"""
import argparse
from collections import Counter
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
from runtime_info import STATE_STREAM_TARGET_HZ


def latency_summary(samples):
    values = sorted(samples)
    return {label:values[math.ceil(len(values)*q)-1] if values else None
        for label,q in (('p95_ms',.95),('p99_ms',.99),('p99_9_ms',.999),('max_ms',1.))}


def run(seconds, directory, *, mixed_load=False):
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
    identities = credentials['credentials']
    streams = [(row['client_id'],row,'/api/v1/events',None) for row in identities]
    if mixed_load:
        # 按三套 HUD 与总览、一个只读总览、一个 UE 兼容读取器配置八路连接。
        streams.extend((row['client_id']+'-hud',row,f"/api/devices/{row['device_id']}/events",row['device_id'])
            for row in identities[:3])
        streams.append(('ue-legacy-reader',identities[3],'/api/devices/sim-drone-1/events','sim-drone-1'))
    readers = [dict(client_id=name,frames=[],receive_gaps=[],send_gaps=[],state_line_bytes=0,simulation_deltas=Counter(),
            errors=[],first=None,last=None,ready=threading.Event(),reconnect_ms=None)
        for name,_,_,_ in streams]
    controls = [dict(device_id=client.device_id,durations_ms=[],queue_apply_ms=[],errors=[]) for client in clients]
    steps = {name:[] for name in fleet.members}
    probes = dict(health_checks=0,log_exports=[],errors=[])
    if mixed_load:
        for name, member in fleet.members.items():
            def measure_step(step=member.core.step, samples=steps[name]):
                begin = time.perf_counter_ns()
                value = step()
                end = time.perf_counter_ns()
                if started.is_set() and window['start']<=begin/1e9<=window['end']:
                    samples.append((begin,(end-begin)/1e6))
                return value
            member.core.step = measure_step

    def read_stream(index):
        _, row, path, device_id = streams[index]
        record = readers[index]
        reconnect_at = None
        try:
            for attempt in range(2 if mixed_load else 1):
                if stopping.is_set():
                    break
                connection = http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=3)
                try:
                    connection.request('GET',path,headers={'Authorization':'Bearer '+row['token']})
                    response = connection.getresponse()
                    if response.status!=200:
                        raise RuntimeError('SSE HTTP '+str(response.status))
                    record['ready'].set()
                    previous = None
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
                        if mixed_load and attempt==0 and now>=window['start']+seconds/2:
                            reconnect_at = now
                            break
                        if device_id is None:
                            members = data['members']
                            if len(members)!=3 or data['self_device_id']!=row['device_id']:
                                raise RuntimeError('全机列表或自身标识错误')
                            if sum(m['control']['write_permitted'] for m in members)!=(row['role']=='CONTROLLER'):
                                raise RuntimeError('设备权限投影错误')
                        else:
                            members = [data]
                            if data['device_id']!=device_id:
                                raise RuntimeError('逐机流设备号错误')
                        if any(not m['online'] or m['mode']=='PROTECTED' for m in members):
                            raise RuntimeError('运行中发现失联或保护')
                        if attempt and record['reconnect_ms'] is None:
                            record['reconnect_ms'] = (now-reconnect_at)*1000
                        if record['first'] is None:
                            record['first'] = data
                        record['last'] = data
                        record['frames'].append(now)
                        record['state_line_bytes'] += len(line)
                        # 重连跨越的间隔单独测量。这里统计同一条连接的发帧、实收和仿真采样间隔。
                        sent_at_ms = data['sent_at_unix_ms']
                        sample_times = {member['device_id']:member['simulation_time_s'] for member in members}
                        if previous is not None:
                            last_receive,last_send,last_times = previous
                            record['receive_gaps'].append((now-last_receive)*1000)
                            record['send_gaps'].append(sent_at_ms-last_send)
                            for member_id in sample_times.keys() & last_times.keys():
                                delta = (sample_times[member_id]-last_times[member_id])*1000
                                record['simulation_deltas'][(member_id,round(delta,3))] += 1
                        previous = now,sent_at_ms,sample_times
                finally:
                    connection.close()
        except Exception as exc:
            if not stopping.is_set():
                record['errors'].append(str(exc))
        finally:
            record['ready'].set()

    def control(index):
        client = clients[index]
        record = controls[index]
        started.wait()
        next_heartbeat = window['start']
        if mixed_load:
            record['heartbeats'] = 0
        while time.perf_counter()<window['end']:
            before = time.perf_counter()
            sign = 1 if int((before-window['start'])/2)%2==0 else -1
            try:
                if mixed_load and before>=next_heartbeat:
                    client.heartbeat()
                    record['heartbeats'] += 1
                    next_heartbeat = before+.1
                sent = time.perf_counter()
                state = client.control(vx=.12*sign if index==0 else 0.,
                    vy=.12*sign if index==1 else 0.,vz=.1*sign if index==2 else 0.,frame='world')
                record['durations_ms'].append((time.perf_counter()-sent)*1000)
                record['queue_apply_ms'].append(state['ack']['queue_and_apply_ms'])
                if state['mode']!='ACTIVE':
                    raise RuntimeError('运动未处于 ACTIVE: '+state['mode'])
            except Exception as exc:
                record['errors'].append(str(exc))
                return
            time.sleep(max(.001,.05-(time.perf_counter()-before)))

    def inspect_service():
        started.wait()
        headers = {'Authorization':'Bearer '+identities[3]['token']}
        next_export = window['start']+max(1.,seconds/6)
        connection = http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=3)
        try:
            while not stopping.is_set() and time.perf_counter()<window['end']:
                connection.request('GET','/api/health',headers=headers)
                response = connection.getresponse()
                health = json.loads(response.read())
                if response.status!=200 or not health['ok']:
                    raise RuntimeError('健康检查返回异常')
                probes['health_checks'] += 1
                probes['publisher_cadence'] = health['data']['state_publisher']['cadence']
                now = time.perf_counter()
                if now>=next_export:
                    connection.request('GET','/api/v1/logs/export?format=jsonl',headers=headers)
                    response = connection.getresponse()
                    content = response.read()
                    if response.status!=200:
                        failure = json.loads(content)
                        raise RuntimeError(f"导出返回 {response.status} {failure.get('error_code','')} {failure.get('error','')}")
                    rows = int(response.getheader('X-Log-Rows'))
                    if rows<1 or content.count(b'\n')!=rows:
                        raise RuntimeError('导出数据与回执行数不一致')
                    probes['log_exports'].append(dict(rows=rows,bytes=len(content),elapsed_ms=(time.perf_counter()-now)*1000))
                    next_export += max(1.,seconds/4)
                stopping.wait(1.)
        except Exception as exc:
            if not stopping.is_set():
                probes['errors'].append(str(exc))
        finally:
            connection.close()

    threads = [threading.Thread(target=read_stream,args=(i,),daemon=True) for i in range(len(streams))]
    probe_thread = threading.Thread(target=inspect_service,daemon=True) if mixed_load else None
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
        cpu_start = time.process_time()
        if probe_thread:
            probe_thread.start()
        started.set()
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            list(pool.map(control,range(3)))
        cpu_pct = (time.process_time()-cpu_start)/(time.perf_counter()-window['start'])*100
        for client in clients:
            client.stop()
    finally:
        stopping.set()
        server.shutdown(); server.server_close()
        for thread in threads:
            if thread.ident is not None:
                thread.join(4)
        if probe_thread and probe_thread.ident is not None:
            probe_thread.join(4)
        for client in clients:
            client.close()
        audit_drained = fleet.close()

    all_controls = []
    for control_record in controls:
        samples = control_record.pop('durations_ms')
        apply = latency_summary(control_record.pop('queue_apply_ms'))
        all_controls.append(dict(**control_record,samples=len(samples),**latency_summary(samples),server_queue_and_apply_ms=apply))
    all_readers = []
    period_ms = 1000/STATE_STREAM_TARGET_HZ
    for reader in readers:
        frames = reader['frames']
        simulation_deltas = {}
        for (device_id,delta_ms), count in sorted(reader['simulation_deltas'].items()):
            simulation_deltas.setdefault(device_id,{})[str(delta_ms)] = count
        receive_gaps = reader['receive_gaps']
        all_readers.append(dict(client_id=reader['client_id'],samples=len(frames),
            frames_per_test_second=len(frames)/seconds,errors=reader['errors'],
            sse_state_line_bytes=reader['state_line_bytes'],
            receive_gap_ms=latency_summary(receive_gaps),server_send_gap_ms=latency_summary(reader['send_gaps']),
            receive_gap_abs_error_ms=latency_summary([abs(gap-period_ms) for gap in receive_gaps]),
            receive_gaps_over_2_periods=sum(gap>2*period_ms for gap in receive_gaps),
            simulation_gap_counts_ms=simulation_deltas))
        if mixed_load:
            all_readers[-1]['reconnect_ms'] = reader['reconnect_ms']
        (directory/(reader['client_id']+'-first.json')).write_text(json.dumps(reader['first'],ensure_ascii=False,indent=2),encoding='utf-8')
        (directory/(reader['client_id']+'-last.json')).write_text(json.dumps(reader['last'],ensure_ascii=False,indent=2),encoding='utf-8')
    passed = all(not c['errors'] and c['samples']>0 and c['p95_ms']<=100 for c in all_controls)
    passed = passed and all(not r['errors'] and r['frames_per_test_second']>=20 for r in all_readers) and audit_drained
    summary = dict(passed=passed,duration_s=seconds,online_instances=3,controlled_instances=3,sensor_enabled_instances=3,
        control_ttl_ms=500,controls=all_controls,observers=all_readers,audit_drained=audit_drained,audit=fleet.audit.status(),
        scope='本机回环；3路Python HTTP控制+4路SSE观察，非手机刷新率、非局域网或4小时验收')
    if mixed_load:
        step_stats = {}
        for name, samples in steps.items():
            period_ms = fleet.members[name].core.dt*1000
            jitter = [abs((b-a)/1e6-period_ms) for (a,_),(b,_) in zip(samples,samples[1:])]
            step_stats[name] = dict(samples=len(samples),step_compute_ms=latency_summary([d for _,d in samples]),
                step_interval_abs_error_ms=latency_summary(jitter))
        passed = passed and all(r['reconnect_ms'] is not None and r['reconnect_ms']<=3000 for r in all_readers)
        passed = passed and bool(probes['health_checks'] and probes['log_exports']) and not probes['errors']
        passed = passed and all(row['samples']>1 for row in step_stats.values())
        summary.update(passed=passed,service_probes=probes,step_stats=step_stats,process_cpu_pct_one_core=cpu_pct,
            scope='本机回环；3路控制和心跳、8路SSE、健康检查和日志导出。进程CPU含同进程压测客户端。')
    (directory/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False,indent=2))
    return passed


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds',type=int,default=60)
    parser.add_argument('--out',type=Path,default=Path('results/independent_acceptance'))
    parser.add_argument('--mixed-load',action='store_true',help='增加逐机HUD、UE兼容读取、重连、心跳和日志负载')
    args = parser.parse_args()
    if not 2<=args.seconds<=600:
        parser.error('测试时长范围2至600秒')
    raise SystemExit(0 if run(args.seconds,args.out,mixed_load=args.mixed_load) else 1)
