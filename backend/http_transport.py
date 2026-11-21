"""单机及集群共用HTTP边界：完整JSON读取、精确跨域白名单与追踪编号。"""
import json
import math
import uuid
from http.server import BaseHTTPRequestHandler
from urllib.parse import urlsplit


class TransportError(Exception):
    def __init__(self,status,message,code='INVALID_ARGUMENT'):
        self.status,self.message,self.code=status,message,code
        super().__init__(message)


def validate_origins(values):
    origins=set()
    for value in values:
        parsed=urlsplit(value)
        if '*' in value or parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
            raise ValueError('跨域来源须为精确http(s) origin，例如http://localhost:5173，不能带路径或通配符')
        _=parsed.port  # 验证端口是否合法。
        origins.add(value)
    return frozenset(origins)


def strict_loads(raw):
    def pairs(rows):
        result={}
        for key,value in rows:
            if key in result: raise ValueError('JSON不能包含重复字段')
            result[key]=value
        return result
    def constant(value): raise ValueError('JSON不能包含NaN或Infinity')
    def floating(value):
        number=float(value)
        if not math.isfinite(number): raise ValueError('JSON数值不能溢出为Infinity')
        return number
    return json.loads(raw,object_pairs_hook=pairs,parse_constant=constant,parse_float=floating)


class JsonHandler(BaseHTTPRequestHandler):
    allowed_origins=frozenset()
    allowed_request_headers=('Authorization','Content-Type')

    def handle_one_request(self):
        self.transport_request_id=uuid.uuid4().hex
        try:
            return super().handle_one_request()
        except (ConnectionResetError,ConnectionAbortedError,BrokenPipeError):
            # APP取消普通HTTP以优先提交保护是正常断连，不应打印为服务崩溃。
            self.close_connection=True

    def validate_origin(self):
        origin=self.headers.get('Origin')
        if origin is not None and origin not in self.allowed_origins:
            self.close_connection=True
            raise TransportError(403,'网页来源未加入跨域白名单','ORIGIN_NOT_ALLOWED')

    def end_headers(self):
        self.send_header('X-Request-ID',self.transport_request_id)
        origin=self.headers.get('Origin')
        if origin is not None and origin in self.allowed_origins:
            self.send_header('Access-Control-Allow-Origin',origin)
            self.send_header('Access-Control-Expose-Headers','X-Request-ID')
            self.send_header('Vary','Origin')
        super().end_headers()

    def reply(self,status,payload):
        payload=dict(payload,request_id=self.transport_request_id)
        content=json.dumps(payload,ensure_ascii=False,allow_nan=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Content-Length',str(len(content)))
        self.send_header('Cache-Control','no-store')
        if self.close_connection: self.send_header('Connection','close')
        self.end_headers()
        self.wfile.write(content)

    def read_json(self,limit):
        try:
            lengths=self.headers.get_all('Content-Length',[])
            if self.headers.get('Transfer-Encoding') is not None or len(lengths)!=1 or not lengths[0].isascii() or not lengths[0].isdigit():
                raise ValueError('需要唯一且有效的Content-Length；不接受分块请求体')
            length=int(lengths[0])
            if not 0<length<=limit: raise ValueError(f'请求体必须为1至{limit}字节')
            media=self.headers.get('Content-Type','application/json').split(';')[0].strip().lower()
            if media!='application/json': raise ValueError('Content-Type需为application/json')
            raw=self.rfile.read(length)
            if len(raw)!=length: raise ValueError('请求体不完整')
            body=strict_loads(raw.decode('utf-8'))
            if not isinstance(body,dict): raise ValueError('请求体必须为JSON对象')
            return body
        except (ValueError,UnicodeError,OSError) as exc:
            # 错误请求后关闭连接，残余字节不能被解释成下一条运动指令。
            self.close_connection=True
            raise TransportError(400,str(exc)) from exc

    def do_OPTIONS(self):
        try:
            self.validate_origin()
            if self.headers.get('Origin') is None:
                raise TransportError(403,'预检必须携带允许的Origin','ORIGIN_NOT_ALLOWED')
            if self.headers.get('Access-Control-Request-Method') not in ('GET','POST'):
                raise TransportError(403,'不支持预检方法','CORS_METHOD_NOT_ALLOWED')
            requested={h.strip().lower() for h in self.headers.get('Access-Control-Request-Headers','').split(',') if h.strip()}
            if requested-{name.lower() for name in self.allowed_request_headers}:
                raise TransportError(403,'不支持预检请求头','CORS_HEADERS_NOT_ALLOWED')
            self.send_response(204)
            self.send_header('Access-Control-Allow-Methods','GET, POST, OPTIONS')
            self.send_header('Access-Control-Allow-Headers',', '.join(self.allowed_request_headers))
            self.send_header('Access-Control-Max-Age','600')
            self.send_header('Content-Length','0')
            self.end_headers()
        except TransportError as exc:
            self.reply(exc.status,dict(ok=False,error=exc.message,error_code=exc.code))
