"""真实HTTP三通路演示及本机延迟/状态推送测量，不代表真实手机验收。"""
import argparse
import json
import math
from pathlib import Path
import threading
import time
import urllib.request
from app_server import DEMO_TOKENS, SimulationService, make_server
from client import SourceClient
from simulation_core import SimulationCore


def run(url=None):
    service = server = None
    if url is None:
        service = SimulationService(SimulationCore())
        server = make_server(service,'127.0.0.1',0)
        service.start()
        threading.Thread(target=server.serve_forever,daemon=True).start()
        url = f'http://127.0.0.1:{server.server_port}'
    clients = {source:SourceClient(source,url) for source in DEMO_TOKENS}
    samples,latencies,events = [],[],[]
    stopping = threading.Event()
    ready = threading.Event()
    stream_errors = []
    def stream():
        try:
            req = urllib.request.Request(url+'/api/events',headers={'Authorization':'Bearer '+DEMO_TOKENS['app']})
            with urllib.request.urlopen(req,timeout=3) as response:
                while not stopping.is_set():
                    line = response.readline().decode()
                    if line.startswith('data: '):
                        samples.append(dict(received_monotonic_s=time.monotonic(),state=json.loads(line[6:])))
                        ready.set()
        except Exception as exc:
            stream_errors.append(str(exc))
            ready.set()
    thread = threading.Thread(target=stream,daemon=True)
    try:
        for c in clients.values():
            c.open_session()
        thread.start()
        assert ready.wait(3) and samples,stream_errors
        app = clients['app']
        def select(source):
            # 演示模拟“用户点击选择”和“外设真实归中”的两个独立事件。
            clients[source].heartbeat()
            waiting = app.switch(source)
            events.append(dict(source=source,stage='WAITING_NEUTRAL',state=waiting))
            neutral = clients[source].neutral()
            clients[source].begin_intent()
            events.append(dict(source=source,stage='ENABLED',state=neutral))
            print('选择来源:',source,'→ 中立握手 →',neutral['mode'],flush=True)
        def continuous(source,count,velocity):
            c = clients[source]
            next_send = time.monotonic()
            for _ in range(count):
                # 从生成本条有效指令前计时，直到收到服务已应用确认，包含请求和返回耗时。
                start = time.perf_counter()
                state = c.control(*velocity,frame='world')
                latencies.append((time.perf_counter()-start)*1000)
                assert state['active_source']==source and state['mode']=='ACTIVE'
                if source!='app':
                    app.heartbeat()
                next_send += .05
                time.sleep(max(0.,next_send-time.monotonic()))
        select('app')
        continuous('app',30,(.25,0.,0.,.1))
        select('ring')
        continuous('ring',30,(0.,.25,.1,-.1))
        select('voice')
        voice = clients['voice']
        start = time.perf_counter()
        voice_state = voice.voice('backward',600,frame='world')
        latencies.append((time.perf_counter()-start)*1000)
        # 语音动作不重复提交，只发送连接心跳；心跳不会延长动作时长。
        for _ in range(8):
            app.heartbeat()
            voice.heartbeat()
            time.sleep(.1)
        status = app.request('/api/commands/voice/'+voice_state['command_id'])
        assert status['status']=='COMPLETED',status
        select('app')
        continuous('app',40,(-.15,-.15,0.,0.))
        final = app.stop()
        assert final['mode']=='HOLD' and final['active_source'] is None
        app.discrete('/api/disconnect',reason='APP_EXIT')
        events.append(dict(source='app',stage='FINAL_STOP',state=final))
        stopping.set()
        thread.join(3)
        assert not stream_errors,stream_errors
        ordered = sorted(latencies)
        p95 = ordered[math.ceil(.95*len(ordered))-1]
        times = [row['received_monotonic_s'] for row in samples]
        hz = (len(times)-1)/(times[-1]-times[0])
        windows = [sum(t<=sample<t+1 for sample in times) for t in times if t+1<=times[-1]]
        summary = dict(measurement_scope='localhost_HTTP_with_reference_client_not_real_APP',
            accepted_motion_ack_samples=len(latencies),generation_to_applied_ack_p95_ms=p95,
            max_ack_ms=max(latencies),state_samples=len(samples),state_receive_average_hz=hz,
            min_samples_in_full_one_second_window=min(windows),
            selected_sources=sorted({row['state']['selected_source'] for row in samples if row['state']['selected_source']}),
            voice_status=status['status'],final_mode=final['mode'],
            checks=dict(local_p95_le_100ms=p95<=100.,local_state_receive_ge_20hz=hz>=20.,
                        each_full_second_ge_20_samples=min(windows)>=20,
                        all_three_sources_observed=len({row['state']['selected_source'] for row in samples if row['state']['selected_source']})==3))
        output = Path(__file__).resolve().parent/'results/handover'
        output.mkdir(parents=True,exist_ok=True)
        (output/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
        (output/'states.jsonl').write_text(''.join(json.dumps(row,ensure_ascii=False)+'\n' for row in samples),encoding='utf-8')
        (output/'events.json').write_text(json.dumps(events,ensure_ascii=False,indent=2),encoding='utf-8')
        (output/'ack_latencies_ms.json').write_text(json.dumps(latencies),encoding='utf-8')
        plot(samples,output/'handover.png')
        print(json.dumps(summary,ensure_ascii=False,indent=2),flush=True)
        assert all(summary['checks'].values()),summary
        return summary
    finally:
        stopping.set()
        for c in clients.values():
            c.close()
        if service:
            service.close()
            server.shutdown()
            server.server_close()
        if thread.ident:
            thread.join(3)


def plot(samples,path):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    t = [row['received_monotonic_s']-samples[0]['received_monotonic_s'] for row in samples]
    fig,axes = plt.subplots(3,1,figsize=(10,8),sharex=True)
    for j,label in enumerate(('x','y','z')):
        axes[0].plot(t,[row['state']['position_m'][j] for row in samples],label=label)
        axes[1].plot(t,[row['state']['velocity_m_s'][j] for row in samples],label=label)
    source_codes = {None:0,'app':1,'ring':2,'voice':3}
    axes[2].step(t,[source_codes[row['state']['selected_source']] for row in samples],where='post')
    axes[0].set_ylabel('Position (m)')
    axes[1].set_ylabel('Actual velocity (m/s)')
    axes[2].set_ylabel('Selected source')
    axes[2].set_yticks([0,1,2,3],['neutral','APP','ring','voice'])
    axes[2].set_xlabel('Client elapsed time (s)')
    for axis in axes:
        axis.grid(True,alpha=.3)
    axes[0].legend()
    axes[1].legend()
    fig.suptitle('User switch + neutral handshake: APP -> ring -> voice -> APP')
    fig.tight_layout()
    fig.savefig(path,dpi=150)
    plt.close(fig)


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',help='可选：连接已启动服务；不指定则启动本机临时实例')
    run(parser.parse_args().url)
