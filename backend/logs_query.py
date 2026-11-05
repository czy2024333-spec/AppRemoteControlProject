"""审计日志查询与导出：归并独立模式落盘的 JSONL，按设备、事件、时间过滤。

事件来源为 results 目录下的 fleet_events.jsonl（MEMBER_STARTED / COMMAND_ACK /
CONTROL_TRANSITION 等集群与成员事件）及成员目录 <device_id>/control_events.jsonl。
审计是异步落盘的，读取时可能撞到正在写入的尾部半行，直接跳过，不把解析
失败当成日志损坏；日志中不写 Token，查询参数也不允许携带凭据。
"""
import csv
import io
import json
from pathlib import Path

DEFAULT_LIMIT = 200
MAX_LIMIT = 2000
# CSV 固定列之外的全部字段原样放入 detail，保证导出信息无损。
CSV_COLUMNS = ['server_time_ms','event','device_id','boot_id','client_id','source','path','sequence','command_id']


def _integer(params, name, default=None, minimum=None, maximum=None):
    raw = params.get(name)
    if raw is None or raw == '':
        return default
    if not isinstance(raw, str) or not raw.isascii() or not (raw.lstrip('-').isdigit()):
        raise ValueError(name+' 必须是整数')
    value = int(raw)
    if minimum is not None and value < minimum:
        raise ValueError(name+' 不得小于 '+str(minimum))
    if maximum is not None and value > maximum:
        return maximum
    return value


def parse_filters(params, known_devices):
    device_id = params.get('device_id') or None
    event = params.get('event') or None
    filters = dict(device_id=device_id,event=event,
        since_ms=_integer(params,'since_ms',None,minimum=0),
        until_ms=_integer(params,'until_ms',None,minimum=0))
    if device_id is not None and device_id not in known_devices:
        raise ValueError('设备不存在: '+device_id)
    if filters['since_ms'] is not None and filters['until_ms'] is not None \
            and filters['since_ms'] > filters['until_ms']:
        raise ValueError('since_ms 不得晚于 until_ms')
    return filters


def collect_events(directory, members):
    """返回 (events, files)，events 按 server_time_ms 升序归并所有日志文件。"""
    directory = Path(directory)
    candidates = [directory/'fleet_events.jsonl'] \
        + [directory/name/'control_events.jsonl' for name in members]
    events, files = [], []
    for path in candidates:
        if not path.is_file():
            continue
        rows = 0
        with path.open('r',encoding='utf-8',errors='replace') as log:
            for line in log:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if not isinstance(row, dict):
                    continue
                rows += 1
                events.append(row)
        files.append(dict(file=str(path.relative_to(directory)),rows=rows))
    events.sort(key=lambda row: row.get('server_time_ms') or 0)
    return events, files


def apply_filters(events, filters):
    matched = []
    for row in events:
        if filters['device_id'] is not None and row.get('device_id') != filters['device_id']:
            continue
        if filters['event'] is not None and row.get('event') != filters['event']:
            continue
        stamp = row.get('server_time_ms')
        if filters['since_ms'] is not None and (stamp is None or stamp < filters['since_ms']):
            continue
        if filters['until_ms'] is not None and (stamp is None or stamp > filters['until_ms']):
            continue
        matched.append(row)
    return matched


def to_jsonl(rows):
    return ''.join(json.dumps(row,ensure_ascii=False,allow_nan=False)+'\n' for row in rows)


def to_csv(rows):
    buffer = io.StringIO()
    writer = csv.writer(buffer,lineterminator='\n')
    writer.writerow(CSV_COLUMNS+['detail'])
    for row in rows:
        detail = {k: v for k, v in row.items() if k not in CSV_COLUMNS}
        writer.writerow([row.get(k,'') for k in CSV_COLUMNS]
            + [json.dumps(detail,ensure_ascii=False,allow_nan=False)])
    # BOM 让 Excel 直接打开时正确识别 UTF-8。
    return '\ufeff'+buffer.getvalue()
