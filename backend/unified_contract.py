"""统一只读模型与回执语义；不代替会话/权限/控制权验证，不伪造未接入的数据。"""
import copy
import math
from runtime_info import IMPLEMENTATION_VERSION, UNIFIED_PROTOCOL_VERSION

COORDINATE_FRAME='SIM_WORLD_X_FORWARD_Y_LEFT_Z_UP'
KNOWN_ERROR_CODES = frozenset("""
    ACK_TIMEOUT BARRIER_FAILED BOUNDARY_RECOVERY_PENDING CAPACITY_LIMIT CLOCK_SKEW
    COMMAND_HISTORY_FULL COMMAND_ID_REUSED COMMAND_NOT_FOUND CONFIG_SAVE_FAILED CONFIG_VERSION_CONFLICT
    CONFIRMATION_REQUIRED CORE_REJECTED CORS_HEADERS_NOT_ALLOWED CORS_METHOD_NOT_ALLOWED
    DEPLOYMENT_MODE_MISMATCH DEVICE_ID_REUSED DISCONNECTED GROUP_NOT_FOUND HISTORY_FULL INVALID_CONFIG
    INVALID_MEMBERS LOG_BUSY LOG_DATA_CORRUPT LOG_EXPORT_TOO_LARGE LOG_INDEX_BUILDING
    LOG_INDEX_UNAVAILABLE LOG_QUERY_TIMEOUT LOG_READ_FAILED LOG_SNAPSHOT_EXPIRED MEMBER_NOT_FOUND
    MODE_NOT_ALLOWED NOT_FOUND NO_PENDING_CONFIRMATION PROTECTED QUEUE_FULL REQUEST_ID_REUSED
    REQUEST_REJECTED RETIRED_INPUT SENSOR_CAPACITY_LIMIT STALE_CLUSTER_SEQUENCE STALE_DEVICE_EVENT
    STALE_SEQUENCE TARGET_DISCONNECTED TASK_NOT_SUPPORTED USER_ACTION_REQUIRED WRONG_CLUSTER WRONG_DEVICE
""".split())


def measurement(value,unit=None,source='UNKNOWN',validity='NOT_CONNECTED',**fields):
    result=dict(value=value,source=source,validity=validity,**fields)
    if unit is not None: result['unit']=unit
    return result


def vector(value,names,online,unit=None,*,age_ms=None,loss_reason=None,**fields):
    valid=isinstance(value,(list,tuple)) and len(value)==len(names) and all(
        type(v) in (int,float) and math.isfinite(v) for v in value)
    if not valid:
        validity,reason='INVALID','NON_FINITE_OR_BAD_SHAPE'
    elif not online:
        validity,reason='STALE',loss_reason or 'LINK_LOST'
    else:
        validity,reason='VALID',None
    return measurement(dict(zip(names,value)) if valid else None,unit,'SIM_TRUTH',
        validity,age_ms=age_ms,reason=reason,**fields)


def sensor_channel(raw, online):
    sample=raw.get('sensor_measurement')
    if sample is None:
        return measurement(None,age_ms=None,stale_after_ms=None,reason='NO_SAMPLE')
    sensor=raw.get('instance_config',{}).get('sensors',{})
    rate=raw.get('sensor_rate_hz',sensor.get('rate_hz'))
    threshold=round(3000/rate) if rate else None
    sample_time=sample.get('simulation_time_s')
    if type(sample_time) not in (int,float) or not math.isfinite(sample_time):
        return measurement(None,source='SIM_MEASUREMENT',validity='INVALID',
            age_ms=None,stale_after_ms=threshold,reason='INVALID_SAMPLE_TIME')
    # 测量与状态来自同一仿真时钟，状态停更的时间也计入样本年龄。
    sim_age=(raw['simulation_time_s']-sample_time)*1000
    age=max(0.,sim_age)+max(0.,raw.get('state_age_ms') or 0.)
    if not online:
        validity,reason='STALE',raw.get('loss_reason') or 'LINK_LOST'
    elif threshold is not None and age > threshold:
        validity,reason='STALE','SAMPLE_TOO_OLD'
    else:
        validity,reason='VALID',None
    return measurement(copy.deepcopy(sample),source='SIM_MEASUREMENT',validity=validity,
        age_ms=round(age,1),stale_after_ms=threshold,reason=reason)


def device_descriptor(raw):
    sensor=raw.get('instance_config',{}).get('sensors',{})
    return dict(device_id=raw['device_id'],name=raw['device_id'],kind='MULTIROTOR',reality='SIMULATION',
        integration_status='CONNECTED' if raw.get('online',False) else 'DISCONNECTED',
        coordinate_frame=COORDINATE_FRAME,
        capabilities=dict(motion=True,motion_axes=['X','Y','Z','YAW'],motion_frames=['WORLD','HEADING'],
            default_motion_frame='WORLD',normalized_input=True,velocity_setpoint=True,
            actions=['STOP','PROTECT','RESUME','HOVER'],takeoff=False,land=False,return_home=False,
            battery=False,gnss=False,local_position_velocity_measurement=sensor.get('enabled',False),
            autonomous_tasks=False,ue_video=False,hardware_ring_verified=False,hardware_voice_verified=False),
        control_limits={k:raw['control_config'][k] for k in ('sensitivity','deadzone','max_horizontal_speed_m_s',
            'max_vertical_speed_m_s','max_yaw_rate_rad_s','input_ttl_ms')}
            | dict(sources_enabled=dict(raw['control_config']['sources_enabled'])),
        control_config_version=raw['config_version'],
        control_config_origin=raw.get('control_config_origin'),
        yaw_convention='CCW_FROM_WORLD_X_ABOUT_POSITIVE_Z',
        routes=dict(state=f"/api/v1/devices/{raw['device_id']}/state",
            events=f"/api/v1/devices/{raw['device_id']}/events",
            legacy_control_prefix=f"/api/devices/{raw['device_id']}"))


def device_state(raw):
    online=raw.get('online',False)
    alarms=[]
    if raw.get('protection_reason'): alarms.append(dict(code=raw['protection_reason'],level='DANGER'))
    if not online: alarms.append(dict(code=raw.get('loss_reason') or 'LINK_LOST',level='DANGER'))
    sensors=dict(local_position_velocity=sensor_channel(raw,online))
    freshness=dict(age_ms=raw.get('state_age_ms'),loss_reason=raw.get('loss_reason'))
    return dict(contract_version=UNIFIED_PROTOCOL_VERSION,device_id=raw['device_id'],boot_id=raw['boot_id'],
        sequence=raw['sequence'],server_time_unix_ms=raw['server_time_ms'],
        simulation_time_s=raw['simulation_time_s'],state_age_ms=raw.get('state_age_ms'),
        online=online,mode=raw['mode'],coordinate_frame=COORDINATE_FRAME,
        position=vector(raw.get('position_m'),'xyz',online,'m',**freshness),
        velocity=vector(raw.get('velocity_m_s'),'xyz',online,'m/s',**freshness),
        attitude=vector(raw.get('quaternion_xyzw'),'xyzw',online,
            representation='QUATERNION_XYZW_BODY_TO_WORLD',**freshness),
        angular_velocity=vector(raw.get('angular_velocity_body_rad_s'),'xyz',online,'rad/s',reference_frame='BODY',**freshness),
        battery=measurement(None,'%'),gnss=measurement(None),sensors=sensors,
        task=dict(task_id=None,status='NOT_SUPPORTED'),alarms=alarms,
        control=dict(source=(raw.get('active_source') or '').upper() or None,
            selected_source=(raw.get('selected_source') or '').upper() or None,
            pending_source=(raw.get('pending_source') or '').upper() or None,control_epoch=raw['control_epoch'],
            phase='PROTECTED' if raw['mode']=='PROTECTED' else 'WAITING_NEUTRAL' if raw.get('pending_source') else
                'SOURCE_SELECTED' if raw.get('selected_source') else 'OBSERVING',
            has_control=None,lease_required=True,
            active=online and raw['mode']!='PROTECTED' and bool(raw.get('active_source')),
            awaiting_neutral=online and raw['mode']!='PROTECTED' and bool(raw.get('pending_source')),
            last_applied=copy.deepcopy(raw.get('last_applied'))),
        protection=dict(latched=raw['mode']=='PROTECTED',reason=raw.get('protection_reason'),
            recovery=copy.deepcopy(raw.get('boundary_recovery'))),
        protection_reason=raw.get('protection_reason'),target_yaw_rad=raw.get('target_yaw_rad'),
        environment=copy.deepcopy(raw.get('environment')),
        video=dict(source='UE',status='NOT_CONNECTED'))


def selection_snapshot(selection,cluster_id):
    if selection is None: return None
    kind={'single':'DEVICE','group':'GROUP','cluster':'CLUSTER'}[selection['target_kind']]
    return dict(selection_id=selection['selection_id'],target_revision=selection['revision'],kind=kind,
        cluster_id=cluster_id,device_id=selection['member_ids'][0] if kind=='DEVICE' else None,
        group_id=selection['group_id'],member_ids=list(selection['member_ids']))


def snapshot(raw):
    return dict(contract_version=UNIFIED_PROTOCOL_VERSION,implementation_version=IMPLEMENTATION_VERSION,
        cluster_id=raw['cluster_id'],boot_id=raw['boot_id'],target_revision=raw['revision'],
        server_time_unix_ms=raw['server_time_ms'],selection=selection_snapshot(raw['selection'],raw['cluster_id']),
        groups=raw['groups'],members=[device_state(s) for s in raw['members']],
        summary={k:raw[k] for k in ('registered_count','online_count','selected_count','active_count','sensor_enabled_count')})


def receipt(raw,device_id=None,command_id=None,source=None):
    if 'results' in raw:
        rows=[]
        for row in raw['results']:
            rows.append(receipt(row['data'],row['device_id'],source=source) if row['ok'] else dict(
                device_id=row['device_id'],phase='REJECTED',raw_code=row['error_code'],
                reason=row['error'],completion_scope=None))
        return dict(request_id=raw.get('request_id'),phase='PARTIAL' if raw['status']=='PARTIAL' else
            'COMPLETED' if raw['status']=='APPLIED' else 'REJECTED',raw_code=raw['status'],
            completion_scope='SERVICE_APPLIED',succeeded=raw['succeeded'],failed=raw['failed'],members=rows)
    status=raw['command_status'] if isinstance(raw.get('command_status'),dict) else raw
    code=raw['command_status'] if isinstance(raw.get('command_status'),str) else status.get('status') or raw.get('ack',{}).get('status')
    phase={'APPLIED':'COMPLETED','COMPLETED':'COMPLETED','FAILED':'FAILED','CANCELLED':'CANCELLED',
        'TIMED_OUT':'TIMED_OUT','WAITING_NEUTRAL':'WAITING_NEUTRAL','WAITING_CONFIRMATION':'WAITING_CONFIRMATION',
        'EXECUTING':'EXECUTING','REJECTED':'REJECTED'}.get(code,'RECEIVED')
    return dict(command_id=status.get('command_id',command_id),device_id=device_id or raw.get('device_id'),
        source=(status.get('source') or source or '').upper() or None,phase=phase,raw_code=code,reason=status.get('reason'),
        completion_scope='SERVICE_APPLIED' if phase=='COMPLETED' else None,
        server_received_at_unix_ms=raw.get('ack',{}).get('server_received_at_ms'),
        confirmed_at_unix_ms=raw.get('ack',{}).get('confirmed_at_ms'))


def error_detail(code,message,device_id=None,command_id=None):
    if code=='UNAUTHORIZED': category='AUTH_REQUIRED'
    elif code in ('OPERATOR_REQUIRED','CAPABILITY_MISMATCH','SOURCE_DISABLED','ORIGIN_NOT_ALLOWED','READ_ONLY','DEVICE_FORBIDDEN'): category='FORBIDDEN'
    elif code in ('STALE_SELECTION','TARGET_MISMATCH'): category='TARGET_CHANGED'
    elif code in ('CONTROL_CONFLICT','SOURCE_NOT_SELECTED','SOURCE_NOT_PENDING'): category='CONTROL_CONFLICT'
    elif code in ('STALE_EPOCH','STALE_SESSION','STALE_CLUSTER_SESSION'): category='STALE_EPOCH'
    elif code=='EXPIRED_COMMAND': category='EXPIRED_COMMAND'
    elif code in ('NOT_NEUTRAL','NEUTRAL_REQUIRED'): category='NEUTRAL_REQUIRED'
    elif code in ('MEMBER_LOST','SIMULATION_FAILURE'): category='LINK_LOST'
    elif code=='ACK_OUTCOME_UNKNOWN': category='OUTCOME_UNKNOWN'
    elif code in ('AUDIT_UNAVAILABLE','SCHEDULER_STALL','STATE_PUBLISHER_UNAVAILABLE'): category='SERVICE_DEGRADED'
    elif code in ('NOT_SUPPORTED','COLLABORATION_NOT_IMPLEMENTED'): category='NOT_SUPPORTED'
    elif code=='INVALID_ARGUMENT': category='INPUT_INVALID'
    elif code in KNOWN_ERROR_CODES: category=code
    else: category='UNKNOWN_ERROR'
    retry={'OUTCOME_UNKNOWN':'QUERY_SAME_COMMAND_ID','SERVICE_DEGRADED':'RETRY_LATER'}
    return dict(code=category,message_key='backend.'+category.lower(),message=message,raw_code=code,
        retry_policy=retry.get(category,'USER_ACTION_REQUIRED'),
        device_id=device_id,command_id=command_id)
