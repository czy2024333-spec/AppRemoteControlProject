"""供应商SDK桥接组件：读取规范化事件并转发；不研发底层感知/识别算法。"""
import argparse
import importlib
import json
from pathlib import Path
import time
import uuid
from client import SourceClient, ClientError


class SDKBridge:
    def __init__(self, source, client, log_path, provider_label='UNVERIFIED_PROVIDER'):
        if source not in ('ring','voice'):
            raise ValueError('SDK桥接仅用于ring或voice')
        self.source,self.client = source,client
        self.log_path = Path(log_path)
        self.log_path.parent.mkdir(parents=True,exist_ok=True)
        self.provider_label = provider_label
        self.last_sequence = -1
        self.run_id = uuid.uuid4().hex

    def record(self, **fields):
        # 记录完整接收与反馈链路，来源标记不能代替实际硬件验收签字。
        fields.update(source=self.source,provider=self.provider_label,recorded_at_ms=time.time()*1000)
        with self.log_path.open('a',encoding='utf-8') as file:
            file.write(json.dumps(fields,ensure_ascii=False,allow_nan=False)+'\n')

    def handle(self, event):
        if not isinstance(event,dict):
            raise ValueError('SDK输出应为JSON对象')
        sequence = event.get('sequence')
        if type(sequence) is not int or sequence<=self.last_sequence:
            raise ValueError('SDK原始事件序号重复或乱序')
        timestamp,ttl = event.get('timestamp_ms'),event.get('ttl_ms')
        if type(timestamp) not in (int,float) or type(ttl) not in (int,float) or not 0<ttl<=500:
            raise ValueError('SDK时间戳/有效期无效')
        import math
        if not math.isfinite(timestamp) or not math.isfinite(ttl):
            raise ValueError('SDK时间戳/有效期必须有限')
        age=time.time()*1000-timestamp
        if age < -50 or age>=ttl:
            raise ValueError('SDK事件过期或时钟超前，不重新包装成新指令')
        self.last_sequence=sequence
        phase=event.get('phase')
        if self.source=='voice' or phase=='begin':
            self.client.begin_intent()
        elif phase=='neutral' or event.get('button')=='confirm':
            self.client.epoch=self.client.request('/api/state')['control_epoch']
        body=self.client.envelope(ttl)
        # 保留SDK采集/识别完成时间，不以转发时间掩盖旧输入。
        body['sent_at_ms']=timestamp
        body['intent_sequence']=self.client.intent
        body['command_id']=self.run_id+'-'+str(sequence)
        allowed = ('event_kind','phase','axes','frame','button','pressed','connected') if self.source=='ring' else ('text','command_code','recognized','confidence','final','duration_ms','frame')
        body.update({key:event[key] for key in allowed if key in event})
        if self.source=='ring':
            body['event_sequence']=sequence
        path='/api/ring-event' if self.source=='ring' else '/api/voice-result'
        # 只记录协议允许的事件字段，供应商事件附带的Token/授权信息不得进入日志。
        public_keys=set(allowed)|{'sequence','timestamp_ms','ttl_ms'}
        self.record(stage='RECEIVED',event={k:v for k,v in event.items() if k in public_keys})
        try:
            result=self.client.request(path,body)
            self.client.epoch=result['control_epoch']
            self.record(stage='FEEDBACK',command_id=body['command_id'],mode=result['mode'],
                selected_source=result['selected_source'],command_status=result.get('command_status'))
            return result
        except ClientError as exc:
            self.record(stage='REJECTED',http_status=exc.status,error=exc.payload)
            raise


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',required=True,choices=['ring','voice'])
    parser.add_argument('--provider',required=True,help='供应商适配模块名；须实现open_device(config)')
    parser.add_argument('--profile',type=Path,required=True)
    parser.add_argument('--url',default='http://127.0.0.1:8765')
    parser.add_argument('--token')
    args=parser.parse_args()
    profile=json.loads(args.profile.read_text(encoding='utf-8'))
    provider=importlib.import_module(args.provider)
    # 未提供SDK时示例模块会明确拒绝启动，不输出假硬件事件。
    device=provider.open_device(profile)
    client=SourceClient(args.source,args.url,args.token)
    bridge=SDKBridge(args.source,client,Path(__file__).resolve().parent/'results/sdk_events.jsonl',args.provider)
    try:
        client.open_session()
        while True:
            event=device.read_event(timeout_s=.05)
            if event is not None:
                try:
                    bridge.handle(event)
                except (ClientError,ValueError) as exc:
                    # 不自动改轮次/序号重放；后续只接收新的SDK事件，连续输入TTL仍独立生效。
                    bridge.record(stage='INPUT_REJECTED',reason=str(exc))
                    pass
            if not device.is_connected():
                client.epoch=client.request('/api/state')['control_epoch']
                client.discrete('/api/disconnect',reason='PERIPHERAL_DISCONNECTED' if args.source=='ring' else 'NETWORK_INTERRUPTED')
                break
            client.heartbeat()
    except KeyboardInterrupt:
        client.stop()
    except Exception as exc:
        bridge.record(stage='SDK_OR_NETWORK_FAILURE',reason=str(exc))
        # 连接还能使用时主动报告失效，否则后端心跳与输入TTL自动保护。
        try:
            client.epoch=client.request('/api/state')['control_epoch']
            client.discrete('/api/disconnect',reason='INPUT_INVALID')
        except Exception:
            pass
        raise
    finally:
        device.close()
        client.close()


if __name__=='__main__':
    main()
