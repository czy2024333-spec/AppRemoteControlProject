"""统一只读模型与回执语义；不代替会话/权限/控制权验证，不伪造未接入的数据。"""
import math
from runtime_info import IMPLEMENTATION_VERSION, UNIFIED_PROTOCOL_VERSION

COORDINATE_FRAME='SIM_WORLD_X_FORWARD_Y_LEFT_Z_UP'


def measurement(value,unit=None,source='UNKNOWN',validity='NOT_CONNECTED',**fields):
    result=dict(value=value,source=source,validity=validity,**fields)
    if unit is not None: result['unit']=unit
    return result


def vector(value,names,online,unit=None,**fields):
    valid=isinstance(value,(list,tuple)) and len(value)==len(names) and all(
        type(v) in (int,float) and math.isfinite(v) for v in value)
    return measurement(dict(zip(names,value)) if valid else None,unit,'SIM_TRUTH',
        'STALE' if valid and not online else 'VALID' if valid else 'INVALID',**fields)


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
            'max_vertical_speed_m_s','max_yaw_rate_rad_s','input_ttl_ms','sources_enabled')},
        control_config_version=raw['config_version'],
        routes=dict(state=f"/api/v1/devices/{raw['device_id']}/state",
            events=f"/api/v1/devices/{raw['device_id']}/events",
            legacy_control_prefix=f"/api/devices/{raw['device_id']}"))


def device_state(raw):
    online=raw.get('online',False)
    alarms=[]
    if raw.get('protection_reason'): alarms.append(dict(code=raw['protection_reason'],level='DANGER'))
    if not online: alarms.append(dict(code=raw.get('loss_reason') or 'LINK_LOST',level='DANGER'))
    local=raw.get('sensor_measurement')
    sensors=dict(local_position_velocity=measurement(local,source='SIM_MEASUREMENT' if local is not None else 'UNKNOWN',
        validity='STALE' if local is not None and not online else 'VALID' if local is not None else 'NOT_CONNECTED'))
    return dict(contract_version=UNIFIED_PROTOCOL_VERSION,device_id=raw['device_id'],boot_id=raw['boot_id'],
        sequence=raw['sequence'],server_time_unix_ms=raw['server_time_ms'],
        simulation_time_s=raw['simulation_time_s'],state_age_ms=raw.get('state_age_ms'),
        online=online,mode=raw['mode'],coordinate_frame=COORDINATE_FRAME,
        position=vector(raw.get('position_m'),'xyz',online,'m'),
        velocity=vector(raw.get('velocity_m_s'),'xyz',online,'m/s'),
        attitude=vector(raw.get('quaternion_xyzw'),'xyzw',online,
            representation='QUATERNION_XYZW_BODY_TO_WORLD'),
        angular_velocity=vector(raw.get('angular_velocity_body_rad_s'),'xyz',online,'rad/s',reference_frame='BODY'),
        battery=measurement(None,'%'),gnss=measurement(None),sensors=sensors,
        task=dict(task_id=None,status='NOT_SUPPORTED'),alarms=alarms,
        control=dict(source=(raw.get('active_source') or '').upper() or None,
            selected_source=(raw.get('selected_source') or '').upper() or None,
            pending_source=(raw.get('pending_source') or '').upper() or None,control_epoch=raw['control_epoch'],
            phase='PROTECTED' if raw['mode']=='PROTECTED' else 'WAITING_NEUTRAL' if raw.get('pending_source') else
                'SOURCE_SELECTED' if raw.get('selected_source') else 'OBSERVING',
            has_control=None,lease_required=True),
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
    elif code in ('NOT_SUPPORTED','COLLABORATION_NOT_IMPLEMENTED'): category='NOT_SUPPORTED'
    else: category='INPUT_INVALID' if code=='INVALID_ARGUMENT' else code
    return dict(code=category,message_key='backend.'+category.lower(),message=message,raw_code=code,
        retry_policy='QUERY_SAME_COMMAND_ID' if category=='OUTCOME_UNKNOWN' else 'USER_ACTION_REQUIRED',
        device_id=device_id,command_id=command_id)
