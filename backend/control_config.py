"""受限配置：不能通过策略更新扩大核心硬限制或修改角色权限。"""
import copy
import math
from input_protocol import DEFAULT_POLICY, validate_policy

DEFAULT = {
    'input_policy': copy.deepcopy(DEFAULT_POLICY),
    'sensitivity': 1., 'deadzone': .08,
    'max_horizontal_speed_m_s': .5, 'max_vertical_speed_m_s': .5, 'max_yaw_rate_rad_s': .5,
    'input_ttl_ms': 500,
    'sources_enabled': {'app':True,'ring':True,'voice':True},
    'input_mapping': {'app':[1,2,3,4],'ring':[1,2,3,4]},
    'voice_mapping': {k:k for k in ('forward','backward','left','right','up','down','turn_left','turn_right')},
}
CAPABILITIES = {'app': {'continuous':True,'axes':['vx','vy','vz','yaw'],'operator':True},
                'ring': {'continuous':True,'axes':['vx','vy','vz','yaw'],'operator':False},
                'voice': {'continuous':False,'actions':list(DEFAULT['voice_mapping']),'operator':False}}


def validate(candidate):
    if set(candidate) != set(DEFAULT):
        raise ValueError('仅允许修改公开配置字段')
    for key, low, high in [('sensitivity',.1,1.),('deadzone',0.,.4),
                          ('max_horizontal_speed_m_s',.05,.5),('max_vertical_speed_m_s',.05,.5),
                          ('max_yaw_rate_rad_s',.05,.5),('input_ttl_ms',50,500)]:
        value = candidate[key]
        if type(value) not in (float,int) or not math.isfinite(value) or not low <= value <= high:
            raise ValueError(key+'超出允许范围')
    enabled = candidate['sources_enabled']
    if not isinstance(enabled,dict) or set(enabled)!= {'app','ring','voice'} or any(type(v) is not bool for v in enabled.values()):
        raise ValueError('sources_enabled必须完整声明三种来源bool值')
    mapping = candidate['input_mapping']
    if not isinstance(mapping,dict) or set(mapping)!= {'app','ring'}:
        raise ValueError('input_mapping需完整声明app/ring')
    for order in mapping.values():
        if not isinstance(order,list) or len(order)!=4 or any(type(v) is not int for v in order) or sorted(abs(v) for v in order)!=[1,2,3,4]:
            raise ValueError('轴映射应是带正负号的1,2,3,4排列')
    voice = candidate['voice_mapping']
    if not isinstance(voice,dict) or set(voice)!=set(DEFAULT['voice_mapping']) or any(not isinstance(v,str) or v not in DEFAULT['voice_mapping'] for v in voice.values()):
        raise ValueError('voice_mapping仅允许八个白名单动作映射')
    validate_policy(candidate['input_policy'])
    return copy.deepcopy(candidate)


def update(current, patch):
    if not isinstance(patch,dict) or not patch or set(patch)-set(DEFAULT):
        raise ValueError('配置补丁为空或含不可修改字段')
    candidate = copy.deepcopy(current)
    candidate.update(patch)
    return validate(candidate)
