"""有界异步审计：文件写入不进入控制线程，失败对外可见并触发停止策略。"""
import collections
import json
from pathlib import Path
import queue
import threading
import time


class AsyncAudit:
    def __init__(self, path, capacity=8192):
        if type(capacity) is not int or not 1<=capacity<=65536:
            raise ValueError('审计队列容量必须为1至65536的整数')
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.queue = queue.Queue(maxsize=capacity)
        self.lock = threading.Lock()
        self.failure = None
        self.accepted = self.written = self.rejected = 0
        self.failed_events = collections.deque(maxlen=128)
        self.stopping = threading.Event()
        self.ready = threading.Event()
        self.thread = threading.Thread(target=self._run, name='fleet-audit', daemon=True)
        self.thread.start()
        # 启动时确认文件可打开；运行中绝不等待磁盘完成运动回执。
        if not self.ready.wait(3) or self.failure:
            self.close()
            raise OSError('审计文件启动失败：'+str(self.failure or '打开超时'))

    def _fail(self, reason, row=None):
        with self.lock:
            if self.failure is None:
                self.failure = str(reason)
            if row is not None:
                self.rejected += 1
                self.failed_events.append(row)

    def emit(self, row):
        # 不写文件、不等空位；队列满时拒绝并记录计数，禁止静默丢弃。
        with self.lock:
            if self.failure is not None or self.stopping.is_set():
                self.rejected += 1
                self.failed_events.append(dict(row))
                return False
            try:
                self.queue.put_nowait(dict(row))
            except queue.Full:
                self.failure = 'AUDIT_QUEUE_FULL'
                self.rejected += 1
                self.failed_events.append(dict(row))
                return False
            self.accepted += 1
        return True

    def _write(self, log, row):
        log.write(json.dumps(row, ensure_ascii=False, allow_nan=False)+'\n')

    def _run(self):
        row = None
        try:
            with self.path.open('a', encoding='utf-8') as log:
                self.ready.set()
                flushed = time.monotonic()
                while not self.stopping.is_set() or not self.queue.empty():
                    try:
                        row = self.queue.get(timeout=.1)
                    except queue.Empty:
                        row = None
                    if row is not None:
                        self._write(log, row)
                        with self.lock:
                            self.written += 1
                        row = None
                    if time.monotonic()-flushed >= .1:
                        log.flush()
                        flushed = time.monotonic()
                log.flush()
        except Exception as exc:
            # 失败后由控制循环锁存 AUDIT_UNAVAILABLE 保护，不再接受普通操纵。
            self._fail('AUDIT_WRITE_FAILED: '+str(exc), row)
        finally:
            self.ready.set()

    def status(self):
        with self.lock:
            return dict(healthy=self.failure is None and self.thread.is_alive() and not self.stopping.is_set(),
                failure=self.failure, pending=self.queue.qsize(), capacity=self.queue.maxsize,
                accepted=self.accepted, written=self.written, rejected=self.rejected,
                failed_events_retained=len(self.failed_events), flush_interval_ms=100,
                durability='BUFFERED_NO_FSYNC')

    def close(self, timeout=5):
        self.stopping.set()
        self.thread.join(timeout)
        if self.thread.is_alive():
            self._fail('AUDIT_SHUTDOWN_TIMEOUT')
        return self.failure is None and self.queue.empty() and not self.thread.is_alive()
