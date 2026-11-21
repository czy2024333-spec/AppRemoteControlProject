"""实现版本与协议版本分开，升级代码不强制修改已有控制报文。"""
from pathlib import Path

IMPLEMENTATION_VERSION = '0.9.0'
UNIFIED_PROTOCOL_VERSION = '1.0'
SINGLE_PROTOCOL_VERSION = '0.5'
CLUSTER_PROTOCOL_VERSION = '0.6'

MAX_CLIENT_CLOCK_AHEAD_MS = 50
STATE_STREAM_TARGET_HZ = 25
SSE_IDLE_HEARTBEAT_MS = 1000
SSE_REPLAY_FRAMES = 50
FOCUS_PEER_TARGET_HZ = 10
STATE_PUBLISHER_TIMEOUT_MS = 1000
COMMAND_QUEUE_LIMIT = 128
STOP_QUEUE_RESERVE = 8
PROTECT_QUEUE_RESERVE = 4
CLUSTER_BARRIER_PATH = '/__cluster_barrier'
COMMAND_HISTORY_LIMIT = 1024
SAFETY_COMMAND_HISTORY_LIMIT = 64
CLUSTER_REQUEST_HISTORY_LIMIT = 2048
CLUSTER_SAFETY_HISTORY_LIMIT = 64
RECENT_COMMANDS_LIMIT = 8
DEFAULT_INDEPENDENT_RESULTS = Path(__file__).resolve().parent/'results/independent_live'
INDEPENDENT_AUDIT_FILENAME = 'fleet_events.jsonl'
DEMO_INDEPENDENT_TOKENS = frozenset((
    'demo-pilot-1-token-2026', 'demo-pilot-2-token-2026', 'demo-pilot-3-token-2026', 'demo-observer-token-2026'))


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
