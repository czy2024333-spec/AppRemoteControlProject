"""统一启动与自检；v0.9默认三端独立操控、全机共享观察。"""
import argparse
import importlib
import importlib.metadata
import ipaddress
import json
from pathlib import Path
import shutil
import statistics
import sys
import time
from runtime_info import (COMMAND_HISTORY_LIMIT, COMMAND_QUEUE_LIMIT, DEFAULT_INDEPENDENT_RESULTS,
    DEMO_INDEPENDENT_TOKENS, IMPLEMENTATION_VERSION, INDEPENDENT_AUDIT_FILENAME, PROTECT_QUEUE_RESERVE,
    RECENT_COMMANDS_LIMIT, SAFETY_COMMAND_HISTORY_LIMIT, STOP_QUEUE_RESERVE)
from http_transport import strict_loads, validate_origins

ROOT = Path(__file__).resolve().parent
MIN_PYTHON = (3, 11)
SLEEP_SAMPLES = 31
SLEEP_REQUEST_MS = 2
SLEEP_WARN_MS = 5


def short_sleep_probe():
    # 用单调高精度时钟量启动时的真实唤醒间隔，避免受系统校时影响。
    samples = []
    for _ in range(SLEEP_SAMPLES):
        started = time.perf_counter_ns()
        time.sleep(SLEEP_REQUEST_MS/1000)
        samples.append((time.perf_counter_ns()-started)/1_000_000)
    return dict(item='short_sleep',ok=True,requested_ms=SLEEP_REQUEST_MS,samples=SLEEP_SAMPLES,
        median_ms=statistics.median(samples),max_ms=max(samples),warning_threshold_ms=SLEEP_WARN_MS)


def all_interfaces(host):
    if host == '':
        return True
    try:
        return ipaddress.ip_address(host).is_unspecified
    except ValueError:
        return False


def audit_storage(results_path):
    # 自检只读。目录尚未创建时，检查将承载它的最近已存在目录。
    directory = Path(results_path).resolve()
    target = directory/INDEPENDENT_AUDIT_FILENAME
    volume_path = next((path for path in (directory,*directory.parents) if path.exists()),None)
    if volume_path is None or not volume_path.is_dir():
        raise OSError('结果路径没有可用的上级目录')
    if target.exists() and not target.is_file():
        raise OSError('审计文件路径被目录或其他文件类型占用')
    usage = shutil.disk_usage(volume_path)
    return dict(results_path=str(directory),audit_path=str(target),probe_ok=True,
        existing_audit_bytes=target.stat().st_size if target.is_file() else 0,available_bytes=usage.free)


def diagnose(mode='independent',config_path=None,credentials_path=None,*,host='127.0.0.1',
        results_path=None,source_tokens=None):
    supported = sys.version_info >= MIN_PYTHON
    runtime = dict(item='python_runtime',ok=supported,minimum_version='.'.join(map(str,MIN_PYTHON)),
        implementation=sys.implementation.name)
    if not supported:
        runtime['error'] = '请使用 Python 3.11 或更新版本'
    checks = [runtime]
    warnings = []
    if supported:
        try:
            sleep = short_sleep_probe()
            checks.append(sleep)
            if sleep['median_ms'] > SLEEP_WARN_MS:
                warnings.append(dict(code='SLEEP_LATENCY_HIGH',
                    message=f"2 ms 短等待实测中位数为 {sleep['median_ms']:.2f} ms，超过 {SLEEP_WARN_MS} ms"))
        except OSError as exc:
            checks.append(dict(item='short_sleep',ok=False,error=str(exc)))
        for package in ('numpy','scipy','rotorpy'):
            try:
                importlib.import_module(package)
                metadata = importlib.metadata.metadata(package)
                version = metadata['Version']
                if package=='rotorpy' and version!='3.0.0': raise ValueError('本项目锁定RotorPy 3.0.0')
                checks.append(dict(item=package,ok=True,version=version,requires_python=metadata.get('Requires-Python')))
            except Exception as exc:
                checks.append(dict(item=package,ok=False,error=str(exc)))
    if all(c['ok'] for c in checks):
        try:
            from app_server import DEMO_TOKENS, validate_source_tokens
            if mode in ('cluster','unified','independent'):
                from config_validation import validate_cluster_config
                config_path=config_path or ROOT/'cluster_config.json'
                config=validate_cluster_config(strict_loads(Path(config_path).read_text(encoding='utf-8-sig')))
            else:
                from control_config import validate
                config_path=config_path or ROOT/'control_config.json'
                validate(strict_loads(Path(config_path).read_text(encoding='utf-8-sig')))
            if mode=='independent':
                from independent_fleet import validate_credentials
                credentials_path = credentials_path or ROOT/'independent_credentials.json'
                rows = validate_credentials(strict_loads(Path(credentials_path).read_text(encoding='utf-8-sig')),
                    {m['device_id'] for m in config['members']})
                if config['limits']['max_controlled']<len(config['members']):
                    raise ValueError('独立模式受控容量不得小于成员数')
                active_tokens = {row['token'] for row in rows}
            else:
                active_tokens = set(validate_source_tokens({**DEMO_TOKENS,**(source_tokens or {})}).values())
            public_count = len(active_tokens & (DEMO_INDEPENDENT_TOKENS | set(DEMO_TOKENS.values())))
            checks.append(dict(item='configuration',ok=True,path=str(config_path),public_sample_token_count=public_count))
            if public_count and all_interfaces(host):
                # 只输出数量，诊断结果不能泄露实际凭据。
                warnings.append(dict(code='PUBLIC_TOKENS_ON_ALL_INTERFACES',
                    message='全网卡监听的明文 HTTP 正在使用公开示例 Token',public_sample_token_count=public_count))
        except Exception as exc:
            checks.append(dict(item='configuration',ok=False,error=str(exc)))
    result = dict(healthy=all(c['ok'] for c in checks),implementation_version=IMPLEMENTATION_VERSION,
        python_executable=sys.executable,python_version=sys.version.split()[0],mode=mode,bind_host=host,
        checks=checks,warnings=warnings,command_queue=dict(ordinary_admission_limit=COMMAND_QUEUE_LIMIT,
            stop_disconnect_reserve=STOP_QUEUE_RESERVE, protect_reserve=PROTECT_QUEUE_RESERVE,
            capacity=COMMAND_QUEUE_LIMIT+STOP_QUEUE_RESERVE+PROTECT_QUEUE_RESERVE),
        command_history=dict(per_source_session_limit=COMMAND_HISTORY_LIMIT,
            safety_per_source_session_limit=SAFETY_COMMAND_HISTORY_LIMIT,
            recent_state_records=RECENT_COMMANDS_LIMIT))
    if mode=='independent':
        directory = results_path or DEFAULT_INDEPENDENT_RESULTS
        try:
            result['audit_storage'] = audit_storage(directory)
            if result['audit_storage']['available_bytes'] == 0:
                warnings.append(dict(code='AUDIT_VOLUME_FULL',message='审计目录所在卷已没有可用空间'))
        except (OSError,RuntimeError) as exc:
            result['audit_storage'] = dict(results_path=str(directory),probe_ok=False)
            warnings.append(dict(code='AUDIT_STORAGE_UNVERIFIED',message=str(exc)))
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode',choices=['single','cluster','unified','independent'],default='independent')
    parser.add_argument('--primary-device',default='sim-drone-1')
    parser.add_argument('--doctor',action='store_true')
    parser.add_argument('--host',default='127.0.0.1')
    parser.add_argument('--port',type=int,default=8765)
    parser.add_argument('--config',type=Path)
    parser.add_argument('--results',type=Path)
    parser.add_argument('--credentials',type=Path)
    parser.add_argument('--allow-origin',action='append',default=[])
    for source in ('app','ring','voice'): parser.add_argument('--'+source+'-token')
    args=parser.parse_args()
    if not 0<=args.port<=65535: parser.error('端口范围0至65535')
    try: validate_origins(args.allow_origin)
    except ValueError as exc: parser.error(str(exc))
    if args.credentials and args.mode!='independent': parser.error('--credentials仅用于独立模式')
    if args.results and args.mode=='single': parser.error('--results不用于单机模式')
    source_tokens = {source:getattr(args,source+'_token') for source in ('app','ring','voice')
        if getattr(args,source+'_token')}
    if args.mode=='independent' and source_tokens:
        parser.error('独立模式请通过--credentials配置逐设备凭据')
    result=diagnose(args.mode,args.config,args.credentials,host=args.host,results_path=args.results,
        source_tokens=source_tokens)
    print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
    if args.doctor or not result['healthy']: return 0 if result['healthy'] else 2
    forwarded=['--host',args.host,'--port',str(args.port)]
    if args.config: forwarded+=['--config',str(args.config)]
    if args.results: forwarded+=['--results',str(args.results)]
    if args.credentials: forwarded+=['--credentials',str(args.credentials)]
    for origin in args.allow_origin: forwarded+=['--allow-origin',origin]
    for source, token in source_tokens.items(): forwarded+=['--'+source+'-token',token]
    sys.argv=[sys.argv[0]]+forwarded
    try:
        if args.mode=='independent':
            from independent_server import main as start
        elif args.mode=='unified':
            forwarded+=['--primary-device',args.primary_device]
            sys.argv=[sys.argv[0]]+forwarded
            from unified_server import main as start
        elif args.mode=='cluster':
            from cluster_server import main as start
        else:
            from app_server import main as start
        start()
    except OSError as exc:
        print('启动失败：'+str(exc)+'。请检查监听地址、端口、网络权限及结果目录。',file=sys.stderr)
        return 2
    return 0


if __name__=='__main__': raise SystemExit(main())
