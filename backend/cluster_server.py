"""集群统一HTTP入口。所有成员控制均经过管理器，无成员旁路监听端口。"""
import argparse
import json
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from app_server import APIError, DEMO_TOKENS
from cluster_manager import ClusterManager
from http_transport import JsonHandler, TransportError, validate_origins


def make_cluster_server(manager, host='127.0.0.1', port=8765, allowed_origins=()):
    class Handler(JsonHandler):
        protocol_version = 'HTTP/1.1'

        def setup(self):
            super().setup()
            self.connection.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
            self.connection.settimeout(3)

        def log_message(self,*args): pass

        def stream(self, name=None):
            # 状态推送不建立控制会话，不代替APP或成员输入心跳。
            if name is not None: manager.member_state(name)
            self.send_response(200)
            self.send_header('Content-Type','text/event-stream; charset=utf-8')
            self.send_header('Cache-Control','no-store')
            self.send_header('Connection','close')
            self.end_headers()
            self.close_connection = True
            previous = None
            next_send = time.monotonic()
            try:
                while not manager.stop_event.is_set():
                    state = manager.member_state(name) if name else manager.state()
                    identity = (state['sequence'],state['online']) if name else (
                        state['revision'],tuple((s['device_id'],s['sequence'],s['online']) for s in state['members']))
                    if identity!=previous:
                        self.wfile.write(('event: state\ndata: '+json.dumps(state,ensure_ascii=False,allow_nan=False)+'\n\n').encode())
                        self.wfile.flush()
                        previous = identity
                    rate = state['state_stream_target_hz'] if name else 25
                    # 用绝对节拍，不能把取快照、序列化和发送耗时再叠加到40ms周期。
                    next_send += 1/rate
                    now = time.monotonic()
                    if next_send < now: next_send = now
                    manager.stop_event.wait(max(0.,next_send-now))
            except (OSError,APIError): pass

        def dispatch(self, post=False):
            try:
                self.validate_origin()
                source = manager.authenticate(self.headers.get('Authorization',''))
                path = urlsplit(self.path).path
                parts = path.strip('/').split('/')
                body = None
                if post:
                    body = self.read_json(65536)
                if path=='/api/health' and not post:
                    health=manager.health()
                    return self.reply(200 if health['healthy'] else 503,dict(ok=health['healthy'],data=health))
                elif path=='/api/cluster/session' and post:
                    data = manager.new_session(source)
                elif path=='/api/cluster/state' and not post:
                    data = manager.state()
                elif path=='/api/cluster/events' and not post:
                    return self.stream()
                elif path.startswith('/api/cluster/') and post:
                    data = manager.request(source,path,body)
                elif len(parts)>=4 and parts[:2]==['api','devices']:
                    name, member_path = parts[2], '/api/'+'/'.join(parts[3:])
                    if not post and member_path=='/api/state': data = manager.member_state(name)
                    elif not post and member_path=='/api/events': return self.stream(name)
                    elif not post and len(parts)==6 and parts[3]=='commands':
                        # 始终先确认成员存在，再复用单机命令查询权限。
                        data = manager.command_state(name,parts[4],parts[5],source)
                    elif post: data = manager.single_request(source,name,member_path,body)
                    else: raise APIError(404,'接口不存在','NOT_FOUND')
                else:
                    raise APIError(404,'集群版需使用带成员标识的/devices或/cluster接口','NOT_FOUND')
                self.reply(200,dict(ok=True,data=data))
            except (APIError,TransportError) as exc:
                if post: self.close_connection=True
                self.reply(exc.status,dict(ok=False,error=exc.message,error_code=exc.code))
            except (ValueError,TypeError,KeyError) as exc:
                self.reply(400,dict(ok=False,error=str(exc),error_code='INVALID_ARGUMENT'))
            except (OSError,TimeoutError):
                self.close_connection = True

        def do_GET(self): self.dispatch()
        def do_POST(self): self.dispatch(True)

    Handler.allowed_origins=validate_origins(allowed_origins)
    return ThreadingHTTPServer((host,port),Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host',default='127.0.0.1')
    parser.add_argument('--port',type=int,default=8765)
    parser.add_argument('--allow-origin',action='append',default=[])
    parser.add_argument('--config',type=Path,default=Path(__file__).with_name('cluster_config.json'))
    parser.add_argument('--results',type=Path,default=Path(__file__).parent/'results/cluster_live')
    for source in DEMO_TOKENS: parser.add_argument('--'+source+'-token',default=DEMO_TOKENS[source])
    args = parser.parse_args()
    manager = ClusterManager(json.loads(args.config.read_text(encoding='utf-8-sig')),args.results,
        {source:getattr(args,source+'_token') for source in DEMO_TOKENS})
    try:
        server = make_cluster_server(manager,args.host,args.port,args.allow_origin)
        print(f'Cluster API http://{args.host}:{server.server_port}/api/cluster/state',flush=True)
        try: server.serve_forever()
        except KeyboardInterrupt: pass
        finally: server.server_close()
    finally: manager.close()


if __name__=='__main__': main()
