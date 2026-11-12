"""独立多机 HTTP/SSE：一个地址，按设备隔离写入，向所有授权端发布全机状态。"""
import argparse
from collections import deque
from dataclasses import dataclass
import json
import logging
import math
from http.server import ThreadingHTTPServer
from pathlib import Path
import socket
import threading
import time
from urllib.parse import parse_qs, urlsplit
from app_server import APIError
from http_transport import JsonHandler, TransportError, strict_loads, validate_origins
from independent_fleet import IndependentFleet
from logs_query import LogError
from runtime_info import identifier
from unified_contract import error_detail, receipt


@dataclass(frozen=True)
class StreamFrame:
    number: int
    captured_at: float
    state: dict
    raw_members: dict


class StateFeed:
    """每台服务共享一份有界状态历史。客户端写入在各自 HTTP 线程完成。"""

    max_progress_gap_s = 1.

    def __init__(self, fleet, stopping):
        self.fleet = fleet
        self.stopping = stopping
        self.changed = threading.Condition()
        self.history = deque(maxlen=50)
        self.last_progress_at = time.monotonic()
        self.failure = None
        self.thread = threading.Thread(target=self.run, name='independent-state-feed', daemon=True)
        self.thread.start()

    def run(self):
        next_capture = 0.
        previous = None
        try:
            while not self.stopping.is_set():
                # 发布唤醒触发取样。无发布时也检查超时，保证冻结能切换到 LOST。
                self.fleet.state_changed.wait(.1)
                self.fleet.state_changed.clear()
                if self.stopping.wait(max(0., next_capture-time.monotonic())):
                    return
                now = time.monotonic()
                # 沿计划时刻推进，避免把 Windows 线程唤醒延迟累计到每一帧。
                next_capture += .04
                if next_capture <= now:
                    next_capture = now + .04
                states = [self.fleet.member_state(name, copy_state=False) for name in sorted(self.fleet.members)]
                signature = tuple((s['boot_id'],s['sequence'],s['server_time_ms'],s['online'],
                    s['mode'],s['control_epoch'],s['selected_source'],s['pending_source'],s['last_applied']) for s in states)
                signature = (signature, self.fleet.audit.failure)
                data = self.fleet.fleet_snapshot(states) if signature != previous else None
                with self.changed:
                    # 记录采样循环完成的时间。静态数据属于正常空闲，卡在取样或聚合则不会刷新。
                    self.last_progress_at = time.monotonic()
                    if data is not None:
                        number = self.history[-1].number+1 if self.history else 1
                        self.history.append(StreamFrame(number, now, data, {s['device_id']:s for s in states}))
                        self.changed.notify_all()
                previous = signature
        except Exception:
            with self.changed:
                self.failure = 'STATE_PUBLISHER_FAILED'
                self.changed.notify_all()
            logging.exception('共享状态发布线程异常退出')

    def _problem(self, now):
        if self.failure:
            return self.failure
        if self.stopping.is_set() or not self.thread.is_alive():
            return 'STATE_PUBLISHER_STOPPED'
        if now - self.last_progress_at >= self.max_progress_gap_s:
            return 'STATE_PUBLISHER_STALLED'
        return None

    def status(self):
        with self.changed:
            now = time.monotonic()
            problem = self._problem(now)
            return dict(healthy=problem is None, thread_alive=self.thread.is_alive(), reason=problem,
                progress_age_ms=(now-self.last_progress_at)*1000, progress_timeout_ms=self.max_progress_gap_s*1000)

    def frames_after(self, cursor, replay):
        with self.changed:
            self.changed.wait_for(lambda: self._problem(time.monotonic()) or
                bool(self.history and self.history[-1].number != cursor), timeout=self.max_progress_gap_s)
            if self._problem(time.monotonic()):
                # 结束已有连接，让客户端走断线处理，避免继续发送心跳掩盖故障。
                return None, False
            if not self.history or self.history[-1].number == cursor:
                return (), False
            latest = self.history[-1]
            if not replay:
                return (latest,), False
            if cursor is None or cursor < self.history[0].number-1 or cursor > latest.number:
                return (latest,), True
            return tuple(frame for frame in self.history if frame.number>cursor), False

    def close(self):
        self.stopping.set()
        self.fleet.state_changed.set()
        with self.changed:
            self.changed.notify_all()
        self.thread.join(1.)


def peer_summary(member):
    result = {key:member[key] for key in ('device_id','boot_id','sequence','simulation_time_s',
        'state_age_ms','online','mode','position')}
    attitude = member['attitude']
    yaw = None
    if attitude['value'] is not None:
        q = attitude['value']
        yaw = math.atan2(2*(q['w']*q['z']+q['x']*q['y']), 1-2*(q['y']**2+q['z']**2))
    result['yaw'] = dict(value=yaw, unit='rad', source=attitude['source'],
        validity=attitude['validity'], age_ms=attitude['age_ms'], reason=attitude['reason'])
    return result


def make_independent_server(fleet, host='127.0.0.1', port=8765, allowed_origins=()):
    class Handler(JsonHandler):
        protocol_version = 'HTTP/1.1'
        allowed_request_headers = (*JsonHandler.allowed_request_headers, 'Last-Event-ID')

        def setup(self):
            super().setup()
            self.connection.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
            self.connection.settimeout(3)

        def log_message(self, *args):
            pass

        def stream(self, identity, name=None, legacy=False, focus=None):
            if name is not None:
                fleet.member_state(name, copy_state=False)
            if focus is not None:
                fleet.member_state(focus, copy_state=False)
            feed = self.server.state_feed
            if not feed.status()['healthy']:
                raise APIError(503,'共享状态发布不可用，可通过状态快照检查','STATE_PUBLISHER_UNAVAILABLE')
            prefix = f"{fleet.boot_id}/{name or 'fleet'}/{focus or '-'}/{int(legacy)}:"
            last_id = self.headers.get('Last-Event-ID')
            replay = bool(last_id)
            cursor = None
            if last_id and last_id.startswith(prefix):
                number = last_id[len(prefix):]
                if number.isascii() and number.isdigit() and len(number)<=18:
                    cursor = int(number)
            next_peers = None
            subscribed_at = time.monotonic()
            self.send_response(200)
            self.send_header('Content-Type','text/event-stream; charset=utf-8')
            self.send_header('Cache-Control','no-store')
            self.send_header('Connection','close')
            self.end_headers()
            self.close_connection = True
            try:
                while not self.server.fleet_stopping.is_set():
                    frames, resync = feed.frames_after(cursor, replay)
                    if frames is None:
                        break
                    if not frames:
                        # 发布线程健康时才发送注释帧，旧 APP 不会将注释解码成状态。
                        self.wfile.write(b': heartbeat\n\n')
                        self.wfile.flush()
                        continue
                    if resync:
                        self.wfile.write(b': resync\n\n')
                    for frame in frames:
                        if name is None:
                            data = fleet.state_for_identity(frame.state, identity)
                            if focus is not None:
                                include_peers = next_peers is None or frame.captured_at >= next_peers
                                if include_peers:
                                    next_peers = (frame.captured_at if next_peers is None else
                                        next_peers+math.floor((frame.captured_at-next_peers)/.1)*.1)+.1
                                data['members'] = [member if member['device_id']==focus else peer_summary(member)
                                    for member in data['members'] if member['device_id']==focus or include_peers]
                                data.update(partial=True, focus_device_id=focus, peers_included=include_peers)
                        elif legacy:
                            data = dict(frame.raw_members[name])
                        else:
                            member = next(item for item in frame.state['members'] if item['device_id']==name)
                            is_self = name == identity['device_id']
                            data = dict(member,is_self=is_self,control=dict(member['control'],
                                write_permitted=identity['role']=='CONTROLLER' and is_self))
                        data.update(sent_at_unix_ms=time.time()*1000,
                            replayed=replay and not resync and frame.captured_at <= subscribed_at)
                        payload = json.dumps(data,ensure_ascii=False,allow_nan=False)
                        # 兼容按顺序读取 event 和 data 的旧工具，id 在事件结束前附加。
                        message = f'event: state\ndata: {payload}\nid: {prefix}{frame.number}\n\n'
                        self.wfile.write(message.encode('utf-8'))
                        self.wfile.flush()
                        cursor = frame.number
                    replay = False
            except (OSError,APIError):
                pass

        def export_logs(self, identity, query):
            # 先完成有界快照文件，再发200；失败不会留下“成功但0字节”的下载。
            with fleet.logs_export(identity,query) as export:
                self.send_response(200)
                self.send_header('Content-Type',
                    'text/csv; charset=utf-8' if export['format']=='csv' else 'application/x-ndjson; charset=utf-8')
                self.send_header('Content-Length',str(export['size']))
                self.send_header('Cache-Control','no-store')
                self.send_header('Content-Disposition','attachment; filename="%s"' % export['filename'])
                self.send_header('X-Log-Snapshot',export['snapshot'])
                self.send_header('X-Log-Rows',str(export['total']))
                self.send_header('X-Log-Pending-Tail',str(export['pending_tail']))
                if self.close_connection:
                    self.send_header('Connection','close')
                self.end_headers()
                self._log_response_started=True
                download_deadline=time.monotonic()+30
                while True:
                    if time.monotonic()>download_deadline:
                        raise OSError('日志下载超过30秒预算')
                    block=export['file'].read(64*1024)
                    if not block:
                        break
                    self.wfile.write(block)

        def dispatch(self, post=False):
            body = None
            name = None
            self._log_response_started=False
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
                    data['state_publisher'] = self.server.state_feed.status()
                    data['healthy'] = data['healthy'] and data['state_publisher']['healthy']
                    return self.reply(200 if data['healthy'] else 503,dict(ok=data['healthy'],data=data))
                if not post and path == '/api/v1/capabilities':
                    data = fleet.info(identity)
                elif not post and path == '/api/v1/devices':
                    data = fleet.directory(identity)
                elif not post and path == '/api/v1/state':
                    data = fleet.state(identity)
                elif not post and path == '/api/v1/events':
                    query = parse_qs(urlsplit(self.path).query, keep_blank_values=True)
                    focus = query.get('focus')
                    if focus is not None and len(focus)!=1:
                        raise APIError(400,'focus 只能指定一个设备','INVALID_ARGUMENT')
                    return self.stream(identity,focus=identifier(focus[0]) if focus else None)
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
            except (APIError,TransportError,LogError) as exc:
                if self._log_response_started:
                    self.close_connection=True
                    return
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
            self.state_feed.close()
            super().server_close()

    Handler.allowed_origins = validate_origins(allowed_origins)
    server = Server((host,port),Handler)
    server.fleet_stopping = threading.Event()
    server.state_feed = StateFeed(fleet, server.fleet_stopping)
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
