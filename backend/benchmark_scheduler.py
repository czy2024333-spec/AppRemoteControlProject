"""重复对照两种仿真短等待方式，复用三机控制和四路 SSE 观察负载。"""
import argparse
import json
import math
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import time
from unittest.mock import patch

from app_server import SimulationService
from run_independent_acceptance import run


def wait_with_event(service, next_tick):
    # 对照组复现旧循环，只替换等待方式，使用同一份后端和负载。
    service.stopping.wait(max(0., min(.002, next_tick - time.monotonic())))


def trial(arm, seconds, directory):
    ticks = {}
    original_tick = SimulationService.tick
    wait = wait_with_event if arm == 'event_wait' else SimulationService._wait_for_next_tick

    def timed_tick(service):
        period_ms = service.core.dt * 1000
        ticks.setdefault(service.device_id, (period_ms, []))[1].append(time.monotonic())
        return original_tick(service)

    with patch.object(SimulationService, 'tick', timed_tick), patch.object(SimulationService, '_wait_for_next_tick', wait):
        passed = run(seconds, directory)
    jitter = {}
    for device_id, (period_ms, stamps) in ticks.items():
        # 留出启动和结束缓冲，每组使用相同的稳态观察窗口。
        begin, end = stamps[0] + 1., stamps[0] + seconds - 1.
        errors = sorted(abs((b - a) * 1000 - period_ms) for a, b in zip(stamps, stamps[1:]) if begin <= a and b <= end)
        jitter[device_id] = dict(samples=len(errors), period_ms=period_ms,
            p99_ms=errors[math.ceil(.99 * len(errors)) - 1] if errors else None)
    summary_path = directory / 'summary.json'
    summary = json.loads(summary_path.read_text(encoding='utf-8'))
    summary.update(wait_arm=arm, step_jitter=jitter)
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return passed and len(jitter) == 3 and all(row['samples'] > 0 for row in jitter.values())


def distribution(values):
    valid = [value for value in values if value is not None]
    if not valid:
        return dict(min=None, median=None, max=None, runs=0)
    return dict(min=min(valid), median=statistics.median(valid), max=max(valid), runs=len(valid))


def compare(rounds, seconds, directory):
    directory.mkdir(parents=True, exist_ok=False)
    arms = {'event_wait': [], 'sleep': []}
    order = []
    for index in range(rounds):
        # 相邻两轮交换先后顺序，降低机器预热或背景负载变化带来的偏差。
        order_for_round = ('event_wait', 'sleep') if index % 2 == 0 else ('sleep', 'event_wait')
        for arm in order_for_round:
            name = f'{index + 1:02d}-{arm}'
            output = directory / name
            print(f'开始 {name}，测量 {seconds}s', flush=True)
            result = subprocess.run([sys.executable, '-B', str(Path(__file__).resolve()), '--arm', arm,
                '--seconds', str(seconds), '--out', str(output)], capture_output=True, encoding='utf-8', errors='replace',
                timeout=seconds + 35)
            (directory / f'{name}.txt').write_text(result.stdout + result.stderr, encoding='utf-8')
            row = json.loads((output / 'summary.json').read_text(encoding='utf-8'))
            row.update(passed=result.returncode == 0, output=name)
            arms[arm].append(row)
            order.append(name)
            jitter = [round(m['p99_ms'], 2) for m in row['step_jitter'].values() if m['p99_ms'] is not None]
            print(f'{name} passed={row["passed"]} p99={jitter}', flush=True)

    stats = {}
    for arm, runs in arms.items():
        stats[arm] = dict(
            step_jitter_p99_ms={name: distribution([row['step_jitter'].get(name, {}).get('p99_ms') for row in runs])
                for name in sorted({name for row in runs for name in row['step_jitter']})},
            http_roundtrip_p95_ms={item['device_id']: distribution([row['controls'][i]['p95_ms'] for row in runs])
                for i, item in enumerate(runs[0]['controls'])},
            sse_hz={item['client_id']: distribution([row['observers'][i]['frames_per_test_second'] for row in runs])
                for i, item in enumerate(runs[0]['observers'])})
    passed = all(row['passed'] for runs in arms.values() for row in runs)
    summary = dict(passed=passed, python=sys.version, system=platform.platform(), seconds_per_run=seconds,
        rounds=rounds, order=order, statistics=stats)
    (directory / 'comparison.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return passed


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds', type=int, default=60)
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--arm', choices=('event_wait', 'sleep'), help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.seconds < 3 or args.rounds < 1:
        parser.error('每轮时长至少 3 秒，轮数至少 1')
    passed = trial(args.arm, args.seconds, args.out) if args.arm else compare(args.rounds, args.seconds, args.out)
    raise SystemExit(0 if passed else 1)
