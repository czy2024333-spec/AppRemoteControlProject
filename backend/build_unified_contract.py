"""用隔离仿真生成真实响应样例和v1只读OpenAPI；不连接或操纵现场服务。"""
import http.client
import json
from pathlib import Path
import threading
from cluster_manager import ClusterManager
from unified_server import make_unified_server
from client import SourceClient
from app_server import DEMO_TOKENS

ROOT=Path(__file__).resolve().parent


def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')


def openapi():
    ref=lambda name: {'$ref':'#/components/schemas/'+name}
    number={'type':'number'}; text={'type':'string'}
    nullable_text=dict(type='string',nullable=True)
    vec=dict(type='object',nullable=True,required=list('xyz'),properties={k:number for k in 'xyz'})
    quat=dict(type='object',nullable=True,required=list('xyzw'),properties={k:number for k in 'xyzw'})
    def field(value,unit=None):
        props=dict(value=value if '$ref' in value else dict(value,nullable=True),source=dict(type='string',enum=['SIM_TRUTH','SIM_MEASUREMENT','REAL','INTERFACE_TEST','UNKNOWN']),
            validity=dict(type='string',enum=['VALID','STALE','INVALID','NOT_CONNECTED']))
        if unit: props['unit']=dict(type='string',enum=[unit])
        return dict(type='object',required=list(props),properties=props)
    state=dict(type='object',required=['contract_version','device_id','boot_id','sequence','server_time_unix_ms',
        'online','mode','coordinate_frame','position','velocity','attitude','battery','gnss','control'],properties=dict(
        contract_version=dict(type='string',enum=['1.0']),device_id=text,boot_id=text,sequence=dict(type='integer',format='int64'),
        server_time_unix_ms=number,simulation_time_s=number,state_age_ms=dict(type='number',nullable=True),online=dict(type='boolean'),
        mode=text,coordinate_frame=dict(type='string',enum=['SIM_WORLD_X_FORWARD_Y_LEFT_Z_UP']),
        position=field(ref('Vector3'),'m'),velocity=field(ref('Vector3'),'m/s'),
        attitude=field(ref('Quaternion')),angular_velocity=field(ref('Vector3'),'rad/s'),
        battery=field(number,'%'),gnss=field({'type':'object'}),
        sensors={'type':'object'},task={'type':'object'},video={'type':'object'},alarms=dict(type='array',items={'type':'object'}),
        control=dict(type='object',properties=dict(source=nullable_text,selected_source=nullable_text,pending_source=nullable_text,
            phase=text,control_epoch=text,has_control=dict(type='boolean',nullable=True,description='观察请求无法确认租约，当前为null'),lease_required={'type':'boolean'}))))
    schemas=dict(Vector3=vec,Quaternion=quat,DeviceState=state,
        FleetState=dict(type='object',required=['contract_version','cluster_id','boot_id','target_revision','members','selection'],
            properties=dict(contract_version=text,cluster_id=text,boot_id=text,target_revision={'type':'integer'},
                members=dict(type='array',items=ref('DeviceState')),selection=dict(type='object',nullable=True),
                groups=dict(type='object',additionalProperties=dict(type='array',items=text)),summary={'type':'object'})),
        Receipt=dict(type='object',required=['phase','raw_code','completion_scope'],properties=dict(
            command_id=nullable_text,device_id=nullable_text,source=nullable_text,
            phase=dict(type='string',enum=['RECEIVED','WAITING_NEUTRAL','WAITING_CONFIRMATION','EXECUTING','COMPLETED',
                'FAILED','REJECTED','CANCELLED','EXPIRED','TIMED_OUT','OUTCOME_UNKNOWN','PARTIAL']),
            raw_code=nullable_text,reason=nullable_text,completion_scope=dict(type='string',nullable=True,enum=['SERVICE_APPLIED',None]))),
        Capabilities={'type':'object'},Directory=dict(type='object',required=['devices','cluster_id','target_revision'],
            properties=dict(devices=dict(type='array',items={'type':'object'}),cluster_id=text,target_revision={'type':'integer'})),
        Error=dict(type='object',required=['ok','error','error_code','request_id'],properties=dict(ok={'type':'boolean'},
            error=text,error_code=text,error_detail={'type':'object'},request_id=text)))
    routes=[('/api/v1/capabilities','能力及认证方式发现','Capabilities',False),
        ('/api/v1/devices','实际设备目录','Directory',False),('/api/v1/state','全部成员统一状态','FleetState',False),
        ('/api/v1/events','全部成员统一状态SSE','FleetState',True),
        ('/api/v1/devices/{device_id}/state','指定成员统一状态','DeviceState',False),
        ('/api/v1/devices/{device_id}/events','指定成员统一状态SSE','DeviceState',True),
        ('/api/v1/devices/{device_id}/commands/{source}/{command_id}','统一命令回执查询','Receipt',False)]
    paths={}
    for path,summary,schema,stream in routes:
        if stream:
            content={'text/event-stream':dict(schema=dict(type='string',description='event: state；data为'+schema+'对象；不包ok/data外壳'))}
        else:
            content={'application/json':dict(schema=dict(type='object',required=['ok','data','request_id'],properties=dict(
                ok={'type':'boolean'},data=ref(schema),request_id=text)))}
        operation=dict(summary=summary,security=[dict(sourceToken=[])],responses={'200':dict(description='成功',content=content)})
        params=[]
        for name in ('device_id','source','command_id'):
            if '{'+name+'}' in path:
                item=dict(name=name,required=True,schema=text); item['in']='path'
                if name=='source': item['schema']=dict(type='string',enum=['app','ring','voice'])
                params.append(item)
        if params: operation['parameters']=params
        for status in ('400','401','403','404','409','503'):
            operation['responses'][status]=dict(description='拒绝或不可用，保留原始错误码',content={'application/json':dict(schema=ref('Error'))})
        paths[path]=dict(get=operation)
    return dict(openapi='3.0.3',info=dict(title='无人机统一只读接口',version='1.0',description=
        '后端0.8.0；旧写控制见openapi.json及openapi_cluster.json。v1统一MotionIntent写协议尚未开放。来源Token是开发认证，不是正式用户角色。'),
        servers=[dict(url='http://localhost:8765',description='实际部署请替换为电脑IPv4')],paths=paths,
        components=dict(schemas=schemas,securitySchemes=dict(sourceToken=dict(type='http',scheme='bearer',bearerFormat='DEVELOPMENT_SOURCE_TOKEN'))))


def main():
    write(ROOT/'openapi_unified.json',openapi())
    config=json.loads((ROOT/'cluster_config.json').read_text(encoding='utf-8'))
    manager=ClusterManager(config,ROOT/'results/contract_capture')
    server=make_unified_server(manager,port=0)
    thread=threading.Thread(target=lambda:server.serve_forever(poll_interval=.02),daemon=True);thread.start()
    url=f'http://127.0.0.1:{server.server_port}'
    client=SourceClient('app',url)
    def request(path,body=None):
        connection=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=3)
        try:
            connection.request('GET' if body is None else 'POST',path,None if body is None else json.dumps(body).encode(),
                {'Authorization':'Bearer '+DEMO_TOKENS['app'],'Content-Type':'application/json'})
            response=connection.getresponse();result=json.loads(response.read())
            return response.status,result
        finally: connection.close()
    try:
        for path,name in (('/api/v1/capabilities','能力发现'),('/api/v1/devices','设备目录'),('/api/v1/state','统一状态')):
            code,data=request(path); assert code==200; write(ROOT/f'契约样例/{name}.json',data)
        assert manager.selection is None
        client.open_session();client.switch('app');client.neutral()
        packet=client.envelope();packet['command_id']='contract-protect-example'
        code,data=request('/api/protect',packet);assert code==200
        write(ROOT/'契约样例/保护服务回执.json',data)
        code,data=request('/api/v1/devices/sim-drone-1/commands/app/contract-protect-example');assert code==200
        write(ROOT/'契约样例/统一命令查询.json',data)
        code,data=request('/api/v1/input',{'axes_normalized':{'x':1,'y':0,'z':0,'yaw':0}});assert code==501
        write(ROOT/'契约样例/未开放写协议错误.json',data)
        print('OPENAPI_AND_6_REAL_RESPONSE_EXAMPLES_OK')
    finally:
        client.close();server.shutdown();server.server_close();manager.close();thread.join(2)


if __name__=='__main__':main()
