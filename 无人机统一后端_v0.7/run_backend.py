"""统一启动与自检；默认集群模式，--mode single可启动原单机协议。"""
import argparse
import importlib
import importlib.metadata
import json
from pathlib import Path
import sys
from runtime_info import IMPLEMENTATION_VERSION
from http_transport import strict_loads, validate_origins


def diagnose(mode='cluster',config_path=None):
    checks=[]
    for package in ('numpy','scipy','rotorpy'):
        try:
            importlib.import_module(package)
            version=importlib.metadata.version(package)
            if package=='rotorpy' and version!='3.0.0': raise ValueError('本项目锁定RotorPy 3.0.0')
            checks.append(dict(item=package,ok=True,version=version))
        except Exception as exc: checks.append(dict(item=package,ok=False,error=str(exc)))
    if all(c['ok'] for c in checks):
        try:
            if mode=='cluster':
                from config_validation import validate_cluster_config
                config_path=config_path or Path(__file__).with_name('cluster_config.json')
                validate_cluster_config(strict_loads(Path(config_path).read_text(encoding='utf-8-sig')))
            else:
                from control_config import validate
                config_path=config_path or Path(__file__).with_name('control_config.json')
                validate(strict_loads(Path(config_path).read_text(encoding='utf-8-sig')))
            checks.append(dict(item='configuration',ok=True,path=str(config_path)))
        except Exception as exc: checks.append(dict(item='configuration',ok=False,error=str(exc)))
    return dict(healthy=all(c['ok'] for c in checks),implementation_version=IMPLEMENTATION_VERSION,
                python_executable=sys.executable,python_version=sys.version.split()[0],mode=mode,checks=checks)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode',choices=['single','cluster'],default='cluster')
    parser.add_argument('--doctor',action='store_true')
    parser.add_argument('--host',default='127.0.0.1')
    parser.add_argument('--port',type=int,default=8765)
    parser.add_argument('--config',type=Path)
    parser.add_argument('--results',type=Path)
    parser.add_argument('--allow-origin',action='append',default=[])
    for source in ('app','ring','voice'): parser.add_argument('--'+source+'-token')
    args=parser.parse_args()
    if not 0<=args.port<=65535: parser.error('端口范围0至65535')
    try: validate_origins(args.allow_origin)
    except ValueError as exc: parser.error(str(exc))
    result=diagnose(args.mode,args.config)
    print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
    if args.doctor or not result['healthy']: return 0 if result['healthy'] else 2
    forwarded=['--host',args.host,'--port',str(args.port)]
    if args.config: forwarded+=['--config',str(args.config)]
    if args.results:
        if args.mode!='cluster': parser.error('--results仅用于集群模式')
        forwarded+=['--results',str(args.results)]
    for origin in args.allow_origin: forwarded+=['--allow-origin',origin]
    for source in ('app','ring','voice'):
        if getattr(args,source+'_token'): forwarded+=['--'+source+'-token',getattr(args,source+'_token')]
    sys.argv=[sys.argv[0]]+forwarded
    try:
        if args.mode=='cluster':
            from cluster_server import main as start
        else:
            from app_server import main as start
        start()
    except OSError as exc:
        print('启动失败：'+str(exc)+'；请检查端口占用和网络权限。',file=sys.stderr)
        return 2
    return 0


if __name__=='__main__': raise SystemExit(main())
