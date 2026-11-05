"""独立多机 HTTP/SSE：一个地址，按设备隔离写入，向所有授权端发布全机状态。"""
import argparse
import json
from http.server import ThreadingHTTPServer
from pathlib import Path
import socket
import threading
import time
from urllib.parse import parse_qs, urlsplit
from app_server import APIError
from http_transport import JsonHandler, TransportError, strict_loads, validate_origins
from independent_fleet import IndependentFleet
from runtime_info import identifier
from unified_contract import error_detail, receipt


def make_independent_server(fleet, host='127.0.0.1', port=8765, allowed_origins=()):
    class Handler(JsonHandler):
        protocol_version = 'HTTP/1.1'

        def setup(self):
            super().setup()
            self.connection.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
            self.connection.settimeout(3)

        def log_message(self, *args):
            pass

        def stream(self, identity, name=None, legacy=False):
            if name is not None:
                fleet.member_state(name)
            self.send_response(200)
            self.send_header('Content-Type','text/event-stream; charset=utf-8')
            self.send_header('Cache-Control','no-store')
            self.send_header('Connection','close')
            self.end_headers()
            self.close_connection = True
            previous = None
            next_send = time.monotonic()
            try:
                while not self.server.fleet_stopping.is_set():
                    if name is None:
                        data = fleet.state(identity)
                        signature = tuple((m['device_id'],m['boot_id'],m['sequence'],m['online'],m['mode']) for m in data['members'])
                    else:
                        data = fleet.member_state(name) if legacy else fleet.view(identity,name)
                        signature = (data['boot_id'],data['sequence'],data['online'],data['mode'])
                    if signature != previous:
                        self.wfile.write(('event: state\ndata: '+json.dumps(data,ensure_ascii=False,allow_nan=False)+'\n\n').encode('utf-8'))
                        self.wfile.flush()
                        previous = signature
                    # 同一条全机 SSE 即可画出自己和其他两架；慢观察端不会占用仿真线程。
                    next_send += .04
                    now = time.monotonic()
                    if next_send < now:
                        next_send = now
                    self.server.fleet_stopping.wait(max(0.,next_send-now))
            except (OSError,APIError):
                pass

        def export_logs(self, identity, query):
            # 导出是文件下载而非JSON应答；流式内容已在fleet侧生成，文件名仅含ASCII时间戳。
            export = fleet.logs_export(identity,query)
            payload = export['content'].encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type',
                'text/csv; charset=utf-8' if export['format']=='csv' else 'application/x-ndjson; charset=utf-8')
            self.send_header('Content-Length',str(len(payload)))
            self.send_header('Cache-Control','no-store')
            self.send_header('Content-Disposition','attachment; filename="%s"' % export['filename'])
            if self.close_connection:
                self.send_header('Connection','close')
            self.end_headers()
            self.wfile.write(payload)

        def dispatch(self, post=False):
            body = None
            name = None
            try:
                self.validate_origin()
                identity = fleet.authenticate(self.headers.get('Authorization',''))
                path = urlsplit(self.path).path
                parts = path.strip('/').split('/')
                if post:
                    body = self.read_json(4096)
                    # 只读端在路由分发前就拒绝写入，不能借旧接口绕过。
                    if identity['role'] == 'OBSERVER':
                        raise APIError(403,'此凭据仅可观察','READ_ONLY')
                if not post and path == '/api/health':
                    data = fleet.health()
                    return self.reply(200 if data['healthy'] else 503,dict(ok=data['healthy'],data=data))
                if not post and path == '/api/v1/capabilities':
                    data = fleet.info(identity)
                elif not post and path == '/api/v1/devices':
                    data = fleet.directory(identity)
                elif not post and path == '/api/v1/state':
                    data = fleet.state(identity)
                elif not post and path == '/api/v1/events':
                    return self.stream(identity)
                elif not post and path == '/api/v1/logs':
                    query = {k:v[0] for k,v in parse_qs(urlsplit(self.path).query).items()}
                    data = fleet.logs(identity,query)
                elif not post and path == '/api/v1/logs/export':
                    query = {k:v[0] for k,v in parse_qs(urlsplit(self.path).query).items()}
                    return self.export_logs(identity,query)
                elif parts[:3] == ['api','v1','devices'] and len(parts)>=5:
                    name = identifier(parts[3])
                    action = parts[4]
                    if not post and len(parts)==5 and action=='state':
                        data = fleet.view(identity,name)
                    elif not post and len(parts)==5 and action=='events':
                        return self.stream(identity,name)
                    elif not post and len(parts)==7 and action=='commands':
                        data = receipt(fleet.command_state(identity,name,parts[5],parts[6]),name,parts[6])
                    elif post and len(parts)==5:
                        data = fleet.request(identity,name,'/api/'+action,body)
                    else:
                        raise APIError(404,'设备接口不存在','NOT_FOUND')
                elif parts[:2] == ['api','devices'] and len(parts)>=4:
                    name = identifier(parts[2])
                    if not post and len(parts)==4 and parts[3]=='state':
                        data = fleet.member_state(name)
                    elif not post and len(parts)==4 and parts[3]=='events':
                        return self.stream(identity,name,True)
                    elif not post and len(parts)==6 and parts[3]=='commands':
                        data = fleet.command_state(identity,name,parts[4],parts[5])
                    elif post and len(parts)==4:
                        data = fleet.request(identity,name,'/api/'+parts[3],body)
                    else:
                        raise APIError(404,'设备接口不存在','NOT_FOUND')
                elif path.startswith('/api/cluster/') or path in ('/api/session','/api/input','/api/control'):
                    # 不隐式回退到主飞机，也不启用原全局集群选中逻辑。
                    raise APIError(409,'独立模式须显式指定设备；请读取 capabilities 的路由','DEPLOYMENT_MODE_MISMATCH')
                else:
                    raise APIError(404,'接口不存在','NOT_FOUND')
                response = dict(ok=True,data=data)
                if post:
                    response['receipt'] = receipt(data,name,body.get('command_id'),identity['source'])
                self.reply(200,response)
            except (APIError,TransportError) as exc:
                if post:
                    self.close_connection = True
                self.reply(exc.status,dict(ok=False,error=exc.message,error_code=exc.code,
                    error_detail=error_detail(exc.code,exc.message,name,body.get('command_id') if body else None)))
            except (ValueError,TypeError,KeyError) as exc:
                self.close_connection = True
                self.reply(400,dict(ok=False,error=str(exc),error_code='INVALID_ARGUMENT',
                    error_detail=error_detail('INVALID_ARGUMENT',str(exc),name)))
            except OSError:
                self.close_connection = True

        def do_GET(self):
            self.dispatch()

        def do_POST(self):
            self.dispatch(True)

    class Server(ThreadingHTTPServer):
        request_queue_size = 64

        def server_close(self):
            self.fleet_stopping.set()
            super().server_close()

    Handler.allowed_origins = validate_origins(allowed_origins)
    server = Server((host,port),Handler)
    server.fleet_stopping = threading.Event()
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host',default='127.0.0.1')
    parser.add_argument('--port',type=int,default=8765)
    parser.add_argument('--config',type=Path,default=Path(__file__).with_name('cluster_config.json'))
    parser.add_argument('--credentials',type=Path,default=Path(__file__).with_name('independent_credentials.json'))
    parser.add_argument('--results',type=Path,default=Path(__file__).parent/'results/independent_live')
    parser.add_argument('--allow-origin',action='append',default=[])
    args = parser.parse_args()
    fleet = IndependentFleet(strict_loads(args.config.read_text('utf-8-sig')),
        strict_loads(args.credentials.read_text('utf-8-sig')),args.results)
    try:
        server = make_independent_server(fleet,args.host,args.port,args.allow_origin)
        print(f'Independent fleet http://{args.host}:{server.server_port}/api/v1/capabilities',flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()
    finally:
        if not fleet.close():
            print('审计关闭时未完全排空，请保留运行目录并检查 health/audit。',flush=True)


if __name__=='__main__':
    main()
