"""日志的有界索引、快照分页和文件导出；HTTP 请求不扫描原始 JSONL。"""
from contextlib import contextmanager
import copy
import csv
import io
import json
import math
from pathlib import Path
import sqlite3
import tempfile
import threading
import time
import uuid
from http_transport import strict_loads

DEFAULT_LIMIT = 200
MAX_LIMIT = 2000
MAX_PAGE_BYTES = 2*1024*1024
MAX_EXPORT_BYTES = 16*1024*1024
MAX_LINE_BYTES = 64*1024
MAX_BATCH_BYTES = 512*1024
MAX_BATCH_ROWS = 512
MAX_OFFSET = 100000
CSV_COLUMNS = ['server_time_ms','event','device_id','boot_id','client_id','source','path','sequence','command_id']


class LogError(Exception):
    def __init__(self, status, code, message):
        self.status, self.code, self.message = status, code, message
        super().__init__(message)


def _integer(params, name, default=None, minimum=None, maximum=None):
    raw = params.get(name)
    if raw is None or raw == '':
        return default
    if not isinstance(raw,str) or not raw.isascii() or not raw.isdigit():
        raise ValueError(name+' 必须是非负整数')
    value = int(raw)
    if minimum is not None and value < minimum:
        raise ValueError(name+' 不得小于 '+str(minimum))
    return min(value,maximum) if maximum is not None else value


def parse_filters(params, known_devices):
    device = params.get('device_id') or None
    event = params.get('event') or None
    if device is not None and device not in known_devices:
        raise ValueError('设备不存在: '+device)
    if event is not None and len(event)>128:
        raise ValueError('event 过长')
    filters = dict(device_id=device,event=event,since_ms=_integer(params,'since_ms',minimum=0),
        until_ms=_integer(params,'until_ms',minimum=0))
    if filters['since_ms'] is not None and filters['until_ms'] is not None and filters['since_ms']>filters['until_ms']:
        raise ValueError('since_ms 不得晚于 until_ms')
    return filters


def _csv_line(row):
    output = io.StringIO()
    csv.writer(output,lineterminator='\n').writerow(row)
    return output.getvalue().encode('utf-8')


class LogIndex:
    """原始 JSONL 是证据；SQLite 只是可重建索引，每周期处理量和缓存有界。"""
    def __init__(self, directory, members, *, batch_rows=MAX_BATCH_ROWS, poll_s=.05,
                 max_export_bytes=MAX_EXPORT_BYTES):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True,exist_ok=True)
        self.paths = [self.directory/'fleet_events.jsonl']+[self.directory/name/'control_events.jsonl' for name in members]
        self.db_path = self.directory/('.logs-index-'+uuid.uuid4().hex+'.sqlite3')
        self.batch_rows, self.poll_s = batch_rows, poll_s
        self.max_export_bytes = max_export_bytes
        self.stopping = threading.Event()
        self.lock = threading.Lock()
        self.operation = threading.BoundedSemaphore(1)
        self.generation = uuid.uuid4().hex
        self.sources = [self._empty(path) for path in self.paths]
        self._status = dict(ready=False,failure=None,indexed_rows=0,invalid_rows=0,pending_tail=0,files=[])
        self.thread = threading.Thread(target=self._run,name='logs-index',daemon=True)
        self.thread.start()

    def _empty(self, path):
        return dict(file=str(path.relative_to(self.directory)),offset=0,line=0,rows=0,
            invalid_rows=0,invalid_samples=[],pending_tail=0,identity=None,discarding=False,size=0)

    def status(self):
        with self.lock:
            return dict(self._status)

    def _publish(self, meta):
        with self.lock:
            self._status = dict(meta)

    def _invalid(self, state, code, offset):
        state['invalid_rows'] += 1
        if len(state['invalid_samples'])<20:
            state['invalid_samples'].append(dict(line=state['line']+1,byte_offset=offset,code=code))

    def _scan(self, path, state, budget):
        records=[]
        if not state['size']:
            state['pending_tail']=0
            return records
        # 文件读取失败在后台显式标记；不在 HTTP 层伪装成 socket 断连。
        with path.open('rb') as stream:
            stream.seek(state['offset'])
            state['pending_tail']=0
            while stream.tell()<state['size'] and budget[0]>0 and budget[1]>0:
                position=stream.tell()
                raw=stream.readline(min(MAX_LINE_BYTES+1,state['size']-position))
                if not raw:
                    break
                budget[0]-=len(raw);budget[1]-=1
                if state['discarding'] or len(raw)>MAX_LINE_BYTES:
                    if not state['discarding']:
                        self._invalid(state,'LINE_TOO_LONG',position)
                    state['discarding']=not raw.endswith(b'\n')
                    state['offset']=stream.tell()
                    if not state['discarding']:
                        state['line']+=1
                    continue
                if not raw.endswith(b'\n'):
                    # 未写完的尾行下周期重读；不能把半行作为完整证据。
                    state['pending_tail']=1
                    break
                state['offset']=stream.tell()
                try:
                    row=strict_loads(raw.decode('utf-8'))
                    if not isinstance(row,dict):
                        raise ValueError('ROW_NOT_OBJECT')
                    stamp=row.get('server_time_ms')
                    if type(stamp) not in (int,float) or not math.isfinite(stamp) or stamp<0:
                        raise ValueError('BAD_TIMESTAMP')
                    # 其余字段也不得带 NaN/Infinity，避免导出时才发现存储损坏。
                    payload=json.dumps(row,ensure_ascii=False,allow_nan=False,separators=(',',':'))
                    payload.encode('utf-8')  # 拒绝转义形式的孤立代理码，避免索引写入时才崩溃。
                    records.append((float(stamp),row.get('device_id') if isinstance(row.get('device_id'),str) else None,
                        row.get('event') if isinstance(row.get('event'),str) else None,payload))
                    state['rows']+=1
                except (ValueError,UnicodeError,OverflowError,RecursionError) as exc:
                    if raw.strip():
                        code='BAD_TIMESTAMP' if str(exc)=='BAD_TIMESTAMP' else 'INVALID_LOG_ROW'
                        self._invalid(state,code,position)
                state['line']+=1
        return records

    def _run(self):
        connection=None
        try:
            connection=sqlite3.connect(self.db_path,timeout=.1)
            connection.executescript('''PRAGMA journal_mode=WAL; PRAGMA cache_size=-2048;
                CREATE TABLE events(id INTEGER PRIMARY KEY,stamp REAL,device TEXT,event TEXT,payload TEXT);
                CREATE INDEX by_time ON events(stamp,id);
                CREATE INDEX by_device ON events(device,stamp,id);
                CREATE INDEX by_event ON events(event,stamp,id);
                CREATE TABLE metadata(value TEXT);''')
            connection.commit()
            ready=False;turn=0
            while not self.stopping.is_set():
                failure=None;records=[];budget=[MAX_BATCH_BYTES,self.batch_rows]
                try:
                    # 替换/截断使旧快照失效，不能将新旧文件的行号和内容拼在一起。
                    reset=False
                    for path,state in zip(self.paths,self.sources):
                        try:
                            stat=path.stat();identity=(stat.st_dev,stat.st_ino)
                        except FileNotFoundError:
                            stat=None;identity=None
                        if state['identity'] is not None and (identity!=state['identity'] or stat.st_size<state['offset']):
                            reset=True
                    if reset:
                        self.generation=uuid.uuid4().hex;ready=False
                        self.sources=[self._empty(p) for p in self.paths]
                        connection.execute('DELETE FROM events')
                    for index in [(turn+i)%len(self.paths) for i in range(len(self.paths))]:
                        path,state=self.paths[index],self.sources[index]
                        try:
                            stat=path.stat()
                        except FileNotFoundError:
                            state['size']=0
                            continue
                        state['identity']=(stat.st_dev,stat.st_ino);state['size']=stat.st_size
                        if budget[0]>0 and budget[1]>0:
                            # 文件读取中途失败时不能提交游标而丢失本批前面的有效行。
                            candidate=copy.deepcopy(state)
                            batch=self._scan(path,candidate,budget)
                            self.sources[index]=candidate
                            records.extend(batch)
                    turn=(turn+1)%len(self.paths)
                except OSError:
                    failure='LOG_READ_FAILED'
                connection.executemany('INSERT INTO events(stamp,device,event,payload) VALUES(?,?,?,?)',records)
                caught_up=not failure and all(s['offset']>=s['size'] or s['pending_tail'] for s in self.sources)
                ready=ready or caught_up
                files=[{k:s[k] for k in ('file','rows','invalid_rows','invalid_samples','pending_tail','offset','size')}
                    for s in self.sources if s['identity'] is not None]
                meta=dict(ready=ready,failure=failure,generation=self.generation,indexed_at_ms=time.time()*1000,
                    failure_file=str(path.relative_to(self.directory)) if failure else None,
                    indexed_rows=sum(s['rows'] for s in self.sources),invalid_rows=sum(s['invalid_rows'] for s in self.sources),
                    pending_tail=sum(s['pending_tail'] for s in self.sources),caught_up=caught_up,files=files)
                connection.execute('DELETE FROM metadata')
                connection.execute('INSERT INTO metadata(value) VALUES(?)',(json.dumps(meta),))
                connection.commit()
                self._publish(meta)
                self.stopping.wait(self.poll_s)
        except (sqlite3.Error,OSError):
            self._publish(dict(self.status(),failure='LOG_INDEX_UNAVAILABLE'))
        finally:
            if connection is not None:
                connection.close()

    @contextmanager
    def _snapshot(self, token=None, timeout_s=.5):
        if not self.operation.acquire(blocking=False):
            raise LogError(503,'LOG_BUSY','日志操作正在进行，请稍后重试')
        connection=None
        try:
            status=self.status()
            if status.get('failure'):
                raise LogError(503,status['failure'],'暂时无法读取或索引日志，请检查文件权限与磁盘')
            if not status['ready']:
                raise LogError(503,'LOG_INDEX_BUILDING','历史日志正在分批索引，请稍后查询')
            connection=sqlite3.connect(self.db_path.as_uri()+'?mode=ro',uri=True,timeout=.1)
            connection.execute('PRAGMA cache_size=-2048')
            deadline=time.monotonic()+timeout_s
            connection.set_progress_handler(lambda: int(time.monotonic()>deadline),1000)
            connection.execute('BEGIN')
            meta=json.loads(connection.execute('SELECT value FROM metadata').fetchone()[0])
            maximum=connection.execute('SELECT coalesce(max(id),0) FROM events').fetchone()[0]
            if token:
                try:
                    generation,bound=token.split(':');bound=int(bound)
                except (ValueError,AttributeError):
                    raise ValueError('snapshot 无效')
                if generation!=meta['generation']:
                    raise LogError(409,'LOG_SNAPSHOT_EXPIRED','日志已替换或截断，请从第一页重新查询')
                if not 0<=bound<=maximum:
                    raise ValueError('snapshot 无效')
                maximum=bound
            if meta.get('failure'):
                raise LogError(503,meta['failure'],'暂时无法读取日志')
            if not meta['ready']:
                raise LogError(503,'LOG_INDEX_BUILDING','历史日志正在重建索引')
            yield connection,meta,maximum
        except sqlite3.Error as exc:
            code='LOG_QUERY_TIMEOUT' if 'interrupt' in str(exc).lower() else 'LOG_INDEX_UNAVAILABLE'
            raise LogError(503,code,'日志查询超过预算或索引暂不可用，请缩小范围后重试') from exc
        except OSError as exc:
            raise LogError(503,'LOG_READ_FAILED','日志文件暂不可读，请检查权限与磁盘') from exc
        finally:
            if connection is not None:
                connection.close()
            self.operation.release()

    def _where(self, filters, maximum):
        clauses=['id<=?'];values=[maximum]
        for key,column,operator in [('device_id','device','='),('event','event','='),('since_ms','stamp','>='),('until_ms','stamp','<=')]:
            if filters[key] is not None:
                clauses.append(column+operator+'?');values.append(filters[key])
        return ' AND '.join(clauses),values

    def query(self, params, members):
        filters=parse_filters(params,members)
        if params.get('allow_partial') not in (None,'','0','1'):
            raise ValueError('allow_partial 仅支持0或1')
        offset=_integer(params,'offset',0,minimum=0)
        if offset>MAX_OFFSET:
            raise ValueError('offset 过大，请按时间或设备缩小查询范围')
        limit=_integer(params,'limit',DEFAULT_LIMIT,minimum=1,maximum=MAX_LIMIT)
        with self._snapshot(params.get('snapshot')) as (connection,meta,maximum):
            if meta['invalid_rows'] and params.get('allow_partial')!='1':
                broken=next(f for f in meta['files'] if f['invalid_rows'])
                line=broken['invalid_samples'][0]['line']
                # 旧APP只显示total而忽略新增字段，默认明确拒绝，避免隐藏审计缺失。
                raise LogError(409,'LOG_DATA_CORRUPT','日志有%d处损坏（%s，第%d行），请保留原始文件并检查'%(
                    meta['invalid_rows'],broken['file'],line))
            where,values=self._where(filters,maximum)
            total=connection.execute('SELECT count(*) FROM events WHERE '+where,values).fetchone()[0]
            cursor=connection.execute('SELECT payload FROM events WHERE '+where+' ORDER BY stamp,id LIMIT ? OFFSET ?',values+[limit,offset])
            events=[];used=0
            for (payload,) in cursor:
                size=len(payload.encode('utf-8'))
                if used+size>MAX_PAGE_BYTES:
                    break
                events.append(json.loads(payload));used+=size
            next_offset=offset+len(events)
            return dict(contract_version='1.0',events=events,total=total,offset=offset,limit=limit,
                next_offset=next_offset if next_offset<total else None,has_more=next_offset<total,
                snapshot=meta['generation']+':'+str(maximum),files=meta['files'],
                integrity=dict(invalid_rows=meta['invalid_rows'],pending_tail=meta['pending_tail'],
                    complete=meta['invalid_rows']==0 and meta['caught_up'] and not meta['pending_tail']),
                index=dict(indexed_at_ms=meta['indexed_at_ms'],caught_up=meta['caught_up']),
                filters={k:v for k,v in filters.items() if v is not None},generated_at_ms=time.time()*1000)

    @contextmanager
    def export(self, params, members):
        fmt=params.get('format') or 'jsonl'
        if fmt not in ('jsonl','csv'):
            raise ValueError('format 仅支持 jsonl 或 csv')
        filters=parse_filters(params,members)
        output=None
        try:
            with self._snapshot(params.get('snapshot'),timeout_s=5) as (connection,meta,maximum):
                if not meta['caught_up']:
                    raise LogError(503,'LOG_INDEX_BUILDING','新增历史文件尚未索引完成，请稍后导出')
                if meta['invalid_rows']:
                    raise LogError(409,'LOG_DATA_CORRUPT','日志存在损坏行，请先查询损坏位置；未生成完整导出')
                output=tempfile.TemporaryFile(mode='w+b',dir=self.directory)
                size=0;rows=0;deadline=time.monotonic()+5
                def append(raw):
                    nonlocal size
                    if size+len(raw)>self.max_export_bytes:
                        raise LogError(413,'LOG_EXPORT_TOO_LARGE','导出超过16 MiB，请按设备或时间缩小范围')
                    if time.monotonic()>deadline:
                        raise LogError(503,'LOG_QUERY_TIMEOUT','导出超过处理预算，请缩小范围')
                    output.write(raw);size+=len(raw)
                if fmt=='csv':
                    append(b'\xef\xbb\xbf'+_csv_line(CSV_COLUMNS+['detail']))
                where,values=self._where(filters,maximum)
                cursor=connection.execute('SELECT payload FROM events WHERE '+where+' ORDER BY stamp,id',values)
                for (payload,) in cursor:
                    if fmt=='jsonl':
                        append((payload+'\n').encode('utf-8'))
                    else:
                        row=json.loads(payload)
                        append(_csv_line([row.get(k,'') for k in CSV_COLUMNS]+[
                            json.dumps({k:v for k,v in row.items() if k not in CSV_COLUMNS},ensure_ascii=False,allow_nan=False)]))
                    rows+=1
                output.flush();output.seek(0)
                # 此时才能发送 200：超限、损坏和读取失败均在响应头之前明确拒绝。
                yield dict(file=output,size=size,total=rows,format=fmt,
                    snapshot=meta['generation']+':'+str(maximum),pending_tail=meta['pending_tail'],
                    filename='fleet-logs-%s.%s'%(time.strftime('%Y%m%d-%H%M%S'),fmt))
        finally:
            if output is not None:
                output.close()

    def close(self):
        self.stopping.set();self.thread.join(3)
        if not self.thread.is_alive():
            # 仅清理本实例生成的三个明确文件，不递归删除运行目录或原始日志。
            for suffix in ('','-wal','-shm'):
                try:
                    Path(str(self.db_path)+suffix).unlink(missing_ok=True)
                except OSError:
                    pass
