"""实现版本与协议版本分开，升级代码不强制修改已有控制报文。"""
IMPLEMENTATION_VERSION = '0.9.0'
UNIFIED_PROTOCOL_VERSION = '1.0'
SINGLE_PROTOCOL_VERSION = '0.5'
CLUSTER_PROTOCOL_VERSION = '0.6'


def identifier(value):
    import re
    if not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',value):
        raise ValueError('标识符必须为1至64位ASCII字母数字或-_')
    return value


def is_continuous(path, body):
    # 指环begin/update也是连续采样，不应占用离散命令幂等历史。
    return path in ('/api/control','/api/input','/api/heartbeat') or (
        path=='/api/ring-event' and isinstance(body,dict) and body.get('event_kind')=='axes'
        and body.get('phase') in ('begin','update'))
