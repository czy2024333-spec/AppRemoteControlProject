"""真实后端 ↔ UE CoreStateAccess 联调检查。

用法：
    python ue_link_check.py --seconds 8
    python ue_link_check.py --seconds 8 --token demo-pilot-1-token-2026

从 UE 消费端视角验证：health 的 boot_id、平铺集群流的解析成功率、每机帧率、
以及 APP 契约直接喂给 UE 时的失败对比。只读，不发送任何控制指令。
"""
import argparse
import http.client
import json
import time

from test_ue_bridge import parse_state_payload


def get_json(port, path, token):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
    try:
        connection.request('GET', path, headers={'Authorization': 'Bearer '+token})
        response = connection.getresponse()
        return response.status, json.loads(response.read())
    finally:
        connection.close()


def collect(port, path, token, seconds):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=seconds+5)
    started = time.monotonic()
    stats = dict(path=path, frames=0, parsed_members=0, dropped_members=0,
        bad_payloads=0, devices={}, first_boot_id=None, modes={})
    try:
        connection.request('GET', path, headers={'Authorization': 'Bearer '+token,
            'Accept': 'text/event-stream'})
        response = connection.getresponse()
        stats['http_status'] = response.status
        if response.status != 200:
            return stats
        deadline = started+seconds
        while time.monotonic() < deadline:
            line = response.readline()
            if not line:
                break
            if not line.startswith(b'data: '):
                continue
            payload = json.loads(line[6:].decode('utf-8'))
            stats['frames'] += 1
            stats['first_boot_id'] = stats['first_boot_id'] or payload.get('boot_id')
            states = parse_state_payload(payload)
            stats['parsed_members'] += len(states)
            members = payload.get('members')
            expected = len(members) if isinstance(members, list) else 1
            stats['dropped_members'] += expected-len(states)
            for state in states:
                device = state['device_id']
                entry = stats['devices'].setdefault(device, dict(frames=0, last=None))
                entry['frames'] += 1
                entry['last'] = dict(mode=state['mode'], online=state['online'],
                    data_quality=state['data_quality'], link_status=state['link_status'],
                    position_m=[round(v, 3) for v in state['position_m']],
                    protection_reason=state['protection_reason'] or None,
                    sequence=state['sequence'])
                stats['modes'][state['mode']] = stats['modes'].get(state['mode'], 0)+1
    finally:
        connection.close()
    stats['seconds'] = round(time.monotonic()-started, 2)
    stats['cluster_frames_hz'] = round(stats['frames']/stats['seconds'], 2) if stats['seconds'] else 0
    return stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--token', default='demo-pilot-1-token-2026')
    parser.add_argument('--seconds', type=float, default=8.)
    parser.add_argument('--output')
    args = parser.parse_args()

    status, health = get_json(args.port, '/api/health', args.token)
    report = dict(checked_at=time.strftime('%Y-%m-%d %H:%M:%S'), port=args.port,
        health_http=status, boot_id=health.get('data', {}).get('boot_id'),
        publisher=health.get('data', {}).get('state_publisher', {}))
    report['ue_stream'] = collect(args.port, '/api/v1/ue/events', args.token, args.seconds)
    report['app_contract_stream'] = collect(args.port, '/api/v1/events', args.token, 3)

    ue = report['ue_stream']
    print('boot_id:', report['boot_id'])
    print('UE 平铺流: HTTP %s, %s 帧 / %ss = %sHz' % (
        ue.get('http_status'), ue['frames'], ue['seconds'], ue['cluster_frames_hz']))
    print('解析成功成员 %s，被丢弃 %s' % (ue['parsed_members'], ue['dropped_members']))
    for device, entry in sorted(ue['devices'].items()):
        print('  %s: %s 帧, %s' % (device, entry['frames'], json.dumps(entry['last'], ensure_ascii=False)))
    app = report['app_contract_stream']
    print('对照 APP 契约流: %s 帧，UE 可解析成员 %s，丢弃 %s（说明必须使用 UE 平铺端点）' % (
        app['frames'], app['parsed_members'], app['dropped_members']))
    ok = (ue.get('http_status') == 200 and ue['frames'] > 0 and ue['dropped_members'] == 0
        and len(ue['devices']) == 3)
    report['ue_compatible'] = ok
    if args.output:
        with open(args.output, 'w', encoding='utf-8') as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
        print('报告已写入', args.output)
    print('UE 兼容结论:', 'PASS' if ok else 'FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    raise SystemExit(main())
