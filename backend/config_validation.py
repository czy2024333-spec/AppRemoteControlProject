"""先完整校验集群启动配置，再创建任何仿真实例或写文件。"""
import copy
import math
from runtime_info import identifier


def validate_cluster_config(config):
    from cluster_member import validate_member
    if not isinstance(config,dict) or set(config)!={'cluster_id','limits','acceptance','members','groups'}:
        raise ValueError('集群配置需要cluster_id/limits/acceptance/members/groups且禁止未知字段')
    identifier(config['cluster_id'])
    limits=config['limits']
    if not isinstance(limits,dict) or set(limits)!={'max_online','max_controlled','max_sensor_enabled','lost_timeout_s'}:
        raise ValueError('集群容量配置字段无效')
    for key in ('max_online','max_controlled','max_sensor_enabled'):
        if type(limits[key]) is not int or not 1<=limits[key]<=16: raise ValueError(key+'范围1至16')
    if max(limits['max_controlled'],limits['max_sensor_enabled'])>limits['max_online']:
        raise ValueError('受控/传感器容量不能大于在线容量')
    timeout=limits['lost_timeout_s']
    if type(timeout) not in (float,int) or not math.isfinite(timeout) or not .2<=timeout<=5:
        raise ValueError('lost_timeout_s范围0.2至5')
    acceptance=config['acceptance']
    if not isinstance(acceptance,dict) or set(acceptance)!={'status','formal_quantity_and_hardware_confirmed'} or not isinstance(acceptance['status'],str) or type(acceptance['formal_quantity_and_hardware_confirmed']) is not bool:
        raise ValueError('acceptance必须声明状态和正式数量硬件是否确认')
    members=config['members']
    if not isinstance(members,list) or not 1<=len(members)<=limits['max_online']:
        raise ValueError('启动成员数量为空或超出容量')
    for member in members: validate_member(member)
    names=[m['device_id'] for m in members]
    if len(set(names))!=len(names): raise ValueError('启动成员编号重复')
    if sum(m['sensors']['enabled'] for m in members)>limits['max_sensor_enabled']:
        raise ValueError('启用传感器数量超出容量')
    if not isinstance(config['groups'],dict): raise ValueError('groups必须为对象')
    for group,ids in config['groups'].items():
        identifier(group)
        if not isinstance(ids,list) or not ids or any(not isinstance(n,str) for n in ids) or len(set(ids))!=len(ids) or set(ids)-set(names):
            raise ValueError('分组必须包含已注册成员且不能为空或重复')
    return copy.deepcopy(config)
