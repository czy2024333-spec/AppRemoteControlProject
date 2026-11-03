"""同一地址提供单机兼容、集群及统一发现/只读模型；不重新实现写控制校验。"""
import argparse
import copy
import json
import socket
import time
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from app_server import APIError, DEMO_TOKENS
from cluster_manager import ClusterManager, MEMBER_PATHS, SAFETY_PATHS
from http_transport import JsonHandler, TransportError, strict_loads, validate_origins
from runtime_info import IMPLEMENTATION_VERSION, UNIFIED_PROTOCOL_VERSION, identifier
from unified_contract import device_descriptor, device_state, snapshot, receipt, error_detail


class UnifiedGateway:
    def __init__(self,manager,primary_device_id='sim-drone-1'):
        self.manager=manager
        self.primary=identifier(primary_device_id)
        if self.primary not in manager.members: raise ValueError('主设备必须为已注册成员')

    def info(self,source):
        return dict(contract_version=UNIFIED_PROTOCOL_VERSION,implementation_version=IMPLEMENTATION_VERSION,
            deployment_mode='UNIFIED',cluster_id=self.manager.cluster_id,primary_device_id=self.primary,
            authentication=dict(mode='DEVELOPMENT_SOURCE_TOKEN',authenticated_source=source.upper(),
                user_id=None,role=None,expires_at_unix_ms=None,formal_user_authentication=False),
            protocols=dict(unified_read='1.0',single_compat='0.5',cluster_compat='0.6',transport=['HTTP','SSE']),
            capabilities=dict(directory=True,unified_state=True,unified_receipts=True,
                single_and_cluster_same_address=True,unified_intent_write=False,cluster_control=True,
                ue_video=False,formal_user_roles=False,autonomous_tasks=False),
            control_policy=dict(default_frame='WORLD',supported_frames=['WORLD','HEADING'],state_stream_target_hz=25,
                axes_order=['X','Y','Z','YAW'],axes_unit='NORMALIZED',input_ttl_max_ms=500,
                source_switch='USER_SWITCH_NEUTRAL',observe_grants_control=False),
            routes=dict(devices='/api/v1/devices',state='/api/v1/state',events='/api/v1/events',
                single_compat_prefix='/api',cluster_compat_prefix='/api/cluster',member_prefix='/api/devices'),
            limits=copy.deepcopy(self.manager.limits))

    def legacy_request(self,source,path,body):
        if path not in MEMBER_PATHS: raise APIError(404,'接口不存在','NOT_FOUND')
        if body.get('device_id',self.primary)!=self.primary:
            raise APIError(400,'单机兼容入口固定绑定主设备，禁止静默换机','WRONG_DEVICE')
        if body.get('cluster_id',self.manager.cluster_id)!=self.manager.cluster_id:
            raise APIError(400,'显式集群标识与当前服务不一致','WRONG_CLUSTER')
        # 由服务端固定补齐路由上下文，不补会话、epoch、TTL、序号，也不替用户生成中立输入。
        packet=dict(body,cluster_id=self.manager.cluster_id)
        if path in SAFETY_PATHS:
            return self.manager.single_request(source,self.primary,path,packet)
        with self.manager.lock:
            selection=self.manager.selection
            if path=='/api/session' and selection is None and source=='app':
                # 唯一兼容例外：APP明确申请旧单机会话时预留主设备；GET连接绝不选中或接管。
                self.manager._apply(source,'/api/cluster/select',dict(target_kind='single',
                    device_id=self.primary,user_initiated=True))
                selection=self.manager.selection
            if not selection or selection['target_kind']!='single' or selection['member_ids']!=[self.primary]:
                raise APIError(409,'主设备正由其他控制对象管理，请用户明确选择单机','CONTROL_CONFLICT')
            if body.get('selection_id',selection['selection_id'])!=selection['selection_id']:
                raise APIError(409,'显式目标快照已失效','STALE_SELECTION')
            packet['selection_id']=selection['selection_id']
            return self.manager.single_request(source,self.primary,path,packet)


def make_unified_server(manager,host='127.0.0.1',port=8765,allowed_origins=(),primary_device_id='sim-drone-1'):
    gateway=UnifiedGateway(manager,primary_device_id)
    class Handler(JsonHandler):
        protocol_version='HTTP/1.1'

        def setup(self):
            super().setup()
            self.connection.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
            self.connection.settimeout(3)

        def log_message(self,*args): pass

        def stream(self,name=None,unified=False):
            if name is not None: manager.member_state(name)
            self.send_response(200)
            self.send_header('Content-Type','text/event-stream; charset=utf-8')
            self.send_header('Cache-Control','no-store'); self.send_header('Connection','close')
            self.end_headers(); self.close_connection=True
            previous=None; next_send=time.monotonic()
            try:
                while not manager.stop_event.is_set():
                    raw=manager.member_state(name) if name is not None else manager.state()
                    identity=(raw['sequence'],raw['online']) if name is not None else (
                        raw['revision'],tuple((s['device_id'],s['boot_id'],s['sequence'],s['online']) for s in raw['members']))
                    if previous!=identity:
                        data=(device_state(raw) if name is not None else snapshot(raw)) if unified else raw
                        self.wfile.write(('event: state\ndata: '+json.dumps(data,ensure_ascii=False,allow_nan=False)+'\n\n').encode())
                        self.wfile.flush(); previous=identity
                    # 观察推送只随真实步号/注册修订变化，不能用GET次数或时间戳制造新鲜度。
                    next_send+=.04
                    now=time.monotonic()
                    if next_send<now: next_send=now
                    manager.stop_event.wait(max(0.,next_send-now))
            except (OSError,APIError): pass

        def dispatch(self,post=False):
            body=None; name=None
            try:
                self.validate_origin()
                source=manager.authenticate(self.headers.get('Authorization',''))
                path=urlsplit(self.path).path
                parts=path.strip('/').split('/')
                if post: body=self.read_json(65536 if path.startswith('/api/cluster/') else 4096)
                if path=='/api/health' and not post:
                    data=manager.health(); data['mode']='unified'
                    return self.reply(200 if data['healthy'] else 503,dict(ok=data['healthy'],data=data))
                if path=='/api/v1/capabilities' and not post: data=gateway.info(source)
                elif path=='/api/v1/devices' and not post:
                    raw=manager.state()
                    data=dict(contract_version='1.0',cluster_id=manager.cluster_id,target_revision=raw['revision'],
                        devices=[device_descriptor(s) for s in raw['members']])
                elif path=='/api/v1/state' and not post: data=snapshot(manager.state())
                elif path=='/api/v1/events' and not post: return self.stream(unified=True)
                elif len(parts)>=5 and parts[:3]==['api','v1','devices'] and not post:
                    name=identifier(parts[3])
                    if len(parts)==5 and parts[4]=='state': data=device_state(manager.member_state(name))
                    elif len(parts)==5 and parts[4]=='events': return self.stream(name,True)
                    elif len(parts)==7 and parts[4]=='commands':
                        data=receipt(manager.command_state(name,parts[5],parts[6],source),name,parts[6])
                    else: raise APIError(404,'统一接口不存在','NOT_FOUND')
                elif path.startswith('/api/v1/'):
                    if post: raise APIError(501,'v1当前提供发现、状态和回执；写控制请按公布的兼容协议发送','NOT_SUPPORTED')
                    raise APIError(404,'统一接口不存在','NOT_FOUND')
                elif path=='/api/cluster/session' and post: data=manager.new_session(source)
                elif path=='/api/cluster/state' and not post: data=manager.state()
                elif path=='/api/cluster/events' and not post: return self.stream()
                elif path.startswith('/api/cluster/') and post: data=manager.request(source,path,body)
                elif len(parts)>=4 and parts[:2]==['api','devices']:
                    name=identifier(parts[2]); member_path='/api/'+'/'.join(parts[3:])
                    if not post and member_path=='/api/state': data=manager.member_state(name)
                    elif not post and member_path=='/api/events': return self.stream(name)
                    elif not post and len(parts)==6 and parts[3]=='commands': data=manager.command_state(name,parts[4],parts[5],source)
                    elif post: data=manager.single_request(source,name,member_path,body)
                    else: raise APIError(404,'成员接口不存在','NOT_FOUND')
                elif path=='/api/state' and not post:
                    data=manager.member_state(gateway.primary); data['authenticated_source']=source
                elif path=='/api/events' and not post: return self.stream(gateway.primary)
                elif len(parts)==4 and parts[:2]==['api','commands'] and not post:
                    data=manager.command_state(gateway.primary,parts[2],parts[3],source)
                elif post: data=gateway.legacy_request(source,path,body)
                else: raise APIError(404,'接口不存在','NOT_FOUND')
                response=dict(ok=True,data=data)
                if post: response['receipt']=receipt(data,name or (gateway.primary if not path.startswith('/api/cluster/') else None),body.get('command_id'),source)
                self.reply(200,response)
            except (APIError,TransportError) as exc:
                if post: self.close_connection=True
                self.reply(exc.status,dict(ok=False,error=exc.message,error_code=exc.code,
                    error_detail=error_detail(exc.code,exc.message,name,body.get('command_id') if body else None)))
            except (ValueError,TypeError,KeyError) as exc:
                self.close_connection=True
                self.reply(400,dict(ok=False,error=str(exc),error_code='INVALID_ARGUMENT',
                    error_detail=error_detail('INVALID_ARGUMENT',str(exc),name)))
            except OSError: self.close_connection=True

        def do_GET(self): self.dispatch()
        def do_POST(self): self.dispatch(True)
    Handler.allowed_origins=validate_origins(allowed_origins)
    return ThreadingHTTPServer((host,port),Handler)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host',default='127.0.0.1'); parser.add_argument('--port',type=int,default=8765)
    parser.add_argument('--allow-origin',action='append',default=[])
    parser.add_argument('--primary-device',default='sim-drone-1')
    parser.add_argument('--config',type=Path,default=Path(__file__).with_name('cluster_config.json'))
    parser.add_argument('--results',type=Path,default=Path(__file__).parent/'results/unified_live')
    for source in DEMO_TOKENS: parser.add_argument('--'+source+'-token',default=DEMO_TOKENS[source])
    args=parser.parse_args()
    manager=ClusterManager(strict_loads(args.config.read_text(encoding='utf-8-sig')),args.results,
        {s:getattr(args,s+'_token') for s in DEMO_TOKENS})
    try:
        server=make_unified_server(manager,args.host,args.port,args.allow_origin,args.primary_device)
        print(f'Unified API http://{args.host}:{server.server_port}/api/v1/capabilities',flush=True)
        try: server.serve_forever()
        except KeyboardInterrupt: pass
        finally: server.server_close()
    finally: manager.close()


if __name__=='__main__': main()
