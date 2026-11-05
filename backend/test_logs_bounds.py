"""覆盖大日志边界、文件故障、坏行定位与稳定快照，避免只测正常小文件。"""
import http.client
import json
from pathlib import Path
import sqlite3
import threading
import time
import unittest
from unittest.mock import patch
from logs_query import LogError, LogIndex, MAX_LINE_BYTES, MAX_PAGE_BYTES
from test_independent import temporary_directory
import test_independent as base_tests


def event(stamp=1, **fields):
    return dict(server_time_ms=stamp,device_id='sim-drone-1',event='COMMAND_ACK',**fields)


class IndexTests(unittest.TestCase):
    def setUp(self):
        self.folder=temporary_directory()
        self.path=Path(self.folder.name)/'fleet_events.jsonl'
        self.path.write_text('',encoding='utf-8')
        self.index=LogIndex(self.folder.name,['sim-drone-1'],poll_s=.01)

    def tearDown(self):
        self.index.close();self.folder.cleanup()

    def append(self, value):
        with self.path.open('ab') as output:
            output.write(value if isinstance(value,bytes) else (json.dumps(value)+'\n').encode())

    def wait(self, rows=None, invalid=None):
        deadline=time.monotonic()+3
        while time.monotonic()<deadline:
            state=self.index.status()
            if state['ready'] and not state['failure'] and (rows is None or state['indexed_rows']==rows) and (invalid is None or state['invalid_rows']==invalid):
                return state
            time.sleep(.01)
        self.fail(str(self.index.status()))

    def query(self, **params):
        return self.index.query(params,{'sim-drone-1'})

    def test_query_parses_only_requested_page_and_reads_no_jsonl_in_caller(self):
        for i in range(1000):self.append(event(i))
        self.wait(rows=1000)
        original=Path.open
        caller=threading.get_ident()
        def guard(path,*args,**kwargs):
            if path==self.path and threading.get_ident()==caller:
                self.fail('查询调用者不应打开原始日志')
            return original(path,*args,**kwargs)
        with patch.object(Path,'open',guard):
            result=self.query(limit='1')
        self.assertEqual((len(result['events']),result['total']),(1,1000))
        self.assertTrue(result['has_more'])

    def test_snapshot_pagination_ignores_new_backdated_rows(self):
        self.append(event(10));self.append(event(20));self.wait(rows=2)
        page=self.query(limit='1')
        self.append(event(5));self.wait(rows=3)
        second=self.query(limit='1',offset='1',snapshot=page['snapshot'])
        self.assertEqual(second['events'][0]['server_time_ms'],20)
        self.assertEqual(second['total'],2)

    def test_file_truncation_invalidates_old_snapshot(self):
        self.append(event(10));self.wait(rows=1)
        snapshot=self.query()['snapshot']
        self.path.write_bytes(b'')
        deadline=time.monotonic()+3
        while time.monotonic()<deadline:
            if self.index.status().get('generation')!=snapshot.split(':')[0]:break
            time.sleep(.01)
        self.wait(rows=0)
        with self.assertRaises(LogError) as raised:self.query(snapshot=snapshot)
        self.assertEqual(raised.exception.code,'LOG_SNAPSHOT_EXPIRED')

    def test_pending_tail_is_retried_and_not_counted_as_corruption(self):
        raw=json.dumps(event(1)).encode()
        self.append(raw);time.sleep(.08)
        self.wait(rows=0,invalid=0)
        self.assertEqual(self.query()['integrity']['pending_tail'],1)
        self.append(b'\n');self.wait(rows=1)
        self.assertEqual(self.query()['integrity']['pending_tail'],0)

    def test_bad_middle_and_bad_timestamp_are_reported_with_line_numbers(self):
        self.append(event(1));self.append(b'{BAD}\n');self.append(dict(server_time_ms='bad'))
        self.append(event(2));self.wait(rows=2,invalid=2)
        result=self.query(allow_partial='1')
        self.assertFalse(result['integrity']['complete'])
        self.assertEqual([s['line'] for s in result['files'][0]['invalid_samples']],[2,3])
        with self.assertRaises(LogError) as raised:
            with self.index.export({}, {'sim-drone-1'}):pass
        self.assertEqual(raised.exception.code,'LOG_DATA_CORRUPT')

    def test_nonfinite_and_invalid_utf8_rows_are_visible_not_http_input_errors(self):
        self.append(b'{"server_time_ms":NaN}\n');self.append(b'\xff\n')
        self.append(b'{"server_time_ms":1,"note":"\\ud800"}\n')
        self.append(event(1));self.wait(rows=1,invalid=3)
        self.assertEqual(self.query(allow_partial='1')['integrity']['invalid_rows'],3)

    def test_overlong_row_is_counted_once_and_following_row_survives(self):
        self.append(b'x'*(MAX_LINE_BYTES*3)+b'\n');self.append(event(1))
        self.wait(rows=1,invalid=1)
        self.assertEqual(self.query(allow_partial='1')['files'][0]['invalid_samples'][0]['code'],'LINE_TOO_LONG')

    def test_page_byte_budget_and_continuation_do_not_lose_rows(self):
        payload='x'*40000
        for i in range(100):self.append(event(i,note=payload))
        self.wait(rows=100)
        page=self.query(limit='2000')
        self.assertLess(len(page['events']),100)
        self.assertLess(sum(len(json.dumps(r).encode()) for r in page['events']),MAX_PAGE_BYTES+5000)
        next_page=self.query(limit='2000',offset=str(page['next_offset']),snapshot=page['snapshot'])
        self.assertEqual(len(page['events'])+len(next_page['events']),100)

    def test_export_size_limit_is_checked_before_success_and_releases_slot(self):
        self.append(event(1));self.wait(rows=1)
        self.index.max_export_bytes=10
        with self.assertRaises(LogError) as raised:
            with self.index.export({}, {'sim-drone-1'}):pass
        self.assertEqual((raised.exception.status,raised.exception.code),(413,'LOG_EXPORT_TOO_LARGE'))
        self.assertEqual(self.query()['total'],1)

    def test_export_is_a_bounded_file_and_has_fixed_snapshot(self):
        self.append(event(1));self.wait(rows=1)
        with self.index.export({}, {'sim-drone-1'}) as result:
            self.assertNotIn('content',result)
            self.assertEqual(result['total'],1)
            data=result['file'].read()
            self.assertEqual(len(data),result['size'])
            self.assertEqual(json.loads(data)['server_time_ms'],1)
            with self.assertRaises(LogError) as raised:self.query()
            self.assertEqual(raised.exception.code,'LOG_BUSY')
        self.assertEqual(self.query()['total'],1)

    def test_read_failure_is_visible_then_recovers_without_dropping_rows(self):
        self.append(event(1));self.wait(rows=1)
        original=Path.open
        def denied(path,*args,**kwargs):
            if path==self.path:raise PermissionError('INJECTED')
            return original(path,*args,**kwargs)
        with patch.object(Path,'open',denied):
            self.append_failure_wait()
            with self.assertRaises(LogError) as raised:self.query()
            self.assertEqual(raised.exception.code,'LOG_READ_FAILED')
        self.append(event(2));self.wait(rows=2)
        self.assertEqual(self.query()['total'],2)

    def test_mid_batch_read_failure_rolls_back_cursor_before_retry(self):
        original=Path.open
        broken=threading.Event();broken.set()
        class Reader:
            def __init__(self, wrapped):self.wrapped=wrapped;self.lines=0
            def __enter__(self):return self
            def __exit__(self,*args):return self.wrapped.__exit__(*args)
            def __getattr__(self,key):return getattr(self.wrapped,key)
            def readline(self,*args):
                self.lines+=1
                if self.lines>1 and broken.is_set():raise OSError('INJECTED_MID_BATCH')
                return self.wrapped.readline(*args)
        def fail_after_one(path,*args,**kwargs):
            wrapped=original(path,*args,**kwargs)
            return Reader(wrapped) if path==self.path and args and args[0]=='rb' else wrapped
        with patch.object(Path,'open',fail_after_one):
            self.append(b''.join((json.dumps(event(i))+'\n').encode() for i in range(3)))
            self.append_failure_wait()
            self.assertEqual(self.index.status()['indexed_rows'],0)
            broken.clear();self.wait(rows=3)
        self.assertEqual([r['server_time_ms'] for r in self.query()['events']],[0,1,2])

    def test_initial_history_returns_building_until_first_index_is_complete(self):
        self.index.close()
        for i in range(10):self.append(event(i))
        self.index=LogIndex(self.folder.name,['sim-drone-1'],batch_rows=1,poll_s=.05)
        with self.assertRaises(LogError) as raised:self.query()
        self.assertEqual(raised.exception.code,'LOG_INDEX_BUILDING')
        self.wait(rows=10)
        self.assertEqual(self.query()['total'],10)

    def test_index_database_failure_is_structured_and_releases_slot(self):
        self.append(event(1));self.wait(rows=1)
        original=sqlite3.connect
        def broken(*args,**kwargs):
            if kwargs.get('uri'):raise sqlite3.DatabaseError('INJECTED_BAD_INDEX')
            return original(*args,**kwargs)
        with patch('logs_query.sqlite3.connect',broken):
            with self.assertRaises(LogError) as raised:self.query()
        self.assertEqual((raised.exception.status,raised.exception.code),(503,'LOG_INDEX_UNAVAILABLE'))
        self.assertEqual(self.query()['total'],1)

    def append_failure_wait(self):
        deadline=time.monotonic()+2
        while time.monotonic()<deadline and not self.index.status()['failure']:time.sleep(.01)
        self.assertEqual(self.index.status()['failure'],'LOG_READ_FAILED')


class LogFailureHttpTests(unittest.TestCase):
    # 复用既有 HTTP 夹具；新增失败用例，避免重复计数原有六个用例。
    setUp=base_tests.LogsQueryTests.setUp
    tearDown=base_tests.LogsQueryTests.tearDown
    request=base_tests.LogsQueryTests.request
    wait_for_events=base_tests.LogsQueryTests.wait_for_events
    def test_log_read_failure_returns_503_json_and_state_still_works(self):
        self.wait_for_events(3)
        original=Path.open
        target=self.fleet.results_directory/'fleet_events.jsonl'
        def denied(path,*args,**kwargs):
            if path==target and args and args[0]=='rb':raise PermissionError('INJECTED')
            return original(path,*args,**kwargs)
        with patch.object(Path,'open',denied):
            deadline=time.monotonic()+2
            while time.monotonic()<deadline and not self.fleet.logs_index.status()['failure']:time.sleep(.01)
            for route in ('/api/v1/logs','/api/v1/logs/export'):
                code,result=self.request(route)
                self.assertEqual((code,result['error_code']),(503,'LOG_READ_FAILED'))
                self.assertTrue(result['request_id'])
            self.assertEqual(self.request('/api/v1/state')[0],200)
            self.assertEqual(self.request('/api/health')[0],200)

    def test_stored_bad_timestamp_is_server_409_with_opt_in_diagnostics(self):
        self.wait_for_events(3)
        self.fleet.audit.emit(dict(server_time_ms='bad'))
        deadline=time.monotonic()+2
        while time.monotonic()<deadline and not self.fleet.logs_index.status()['invalid_rows']:time.sleep(.01)
        code,result=self.request('/api/v1/logs')
        self.assertEqual((code,result['error_code']),(409,'LOG_DATA_CORRUPT'))
        self.assertIn('fleet_events.jsonl',result['error'])
        code,result=self.request('/api/v1/logs?allow_partial=1')
        self.assertEqual(code,200)
        self.assertEqual(result['data']['integrity']['invalid_rows'],1)
        code,result=self.request('/api/v1/logs/export')
        self.assertEqual((code,result['error_code']),(409,'LOG_DATA_CORRUPT'))

    def test_export_temporary_file_failure_has_json_response(self):
        self.wait_for_events(3)
        with patch('logs_query.tempfile.TemporaryFile',side_effect=OSError('INJECTED_DISK_FAILURE')):
            code,result=self.request('/api/v1/logs/export')
        self.assertEqual((code,result['error_code']),(503,'LOG_READ_FAILED'))
        self.assertEqual(self.request('/api/v1/logs')[0],200)


if __name__=='__main__':
    unittest.main(verbosity=2)
