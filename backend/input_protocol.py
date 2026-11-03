"""供应商SDK输出的规范化协议；不实现指环感知或语音识别算法。"""
import math
import unicodedata

SEMANTICS = ('forward','backward','left','right','up','down','turn_left','turn_right',
             'hover','stop','protect','takeoff','land','return_home')
SUPPORTED = SEMANTICS[:11]
DEFAULT_POLICY = {
    'min_confidence': .8,
    'dangerous_actions': ['down','takeoff','land','return_home'],
    'confirmation_timeout_ms': 5000,
    'voice_vocabulary': {'前进':'forward','向前':'forward','后退':'backward','向后':'backward',
        '向左':'left','向右':'right','上升':'up','下降':'down','左转':'turn_left','右转':'turn_right',
        '悬停':'hover','停止':'stop','紧急停止':'protect','起飞':'takeoff','降落':'land','返航':'return_home'},
    'voice_codes': {s:s for s in SEMANTICS},
    'ring_buttons': {'confirm':'neutral','stop':'stop','emergency':'protect'},
}


def finite(value, name, low, high):
    if type(value) not in (int,float) or not math.isfinite(value) or not low<=value<=high:
        raise ValueError(name+'数值或范围无效')
    return value


def normalize(text):
    # 只规范空白与句末标点，不做模糊匹配，避免误将不明确的中文识别结果变成运动。
    if not isinstance(text,str) or len(text)>128:
        raise ValueError('中文识别文本必须是长度不超过128的字符串')
    return unicodedata.normalize('NFKC',text).strip().strip('。！!，,？?').strip()


def validate_policy(policy):
    if not isinstance(policy,dict) or set(policy)!=set(DEFAULT_POLICY):
        raise ValueError('输入策略字段不完整或含未知字段')
    finite(policy['min_confidence'],'min_confidence',.5,1.)
    finite(policy['confirmation_timeout_ms'],'confirmation_timeout_ms',1000,10000)
    dangerous = policy['dangerous_actions']
    if not isinstance(dangerous,list) or any(not isinstance(a,str) or a not in SEMANTICS or a in ('stop','hover','protect') for a in dangerous):
        raise ValueError('危险动作只能来自白名单，停止/悬停/保护不能要求确认')
    for name in ('voice_vocabulary','voice_codes'):
        mapping = policy[name]
        if not isinstance(mapping,dict) or not 1<=len(mapping)<=128:
            raise ValueError(name+'映射数量需为1–128')
        for phrase,action in mapping.items():
            if not isinstance(phrase,str) or not phrase or len(phrase)>128 or not isinstance(action,str) or action not in SEMANTICS:
                raise ValueError(name+'含无效映射')
            if name=='voice_vocabulary' and normalize(phrase)!=phrase:
                raise ValueError('词表键应预先去掉空白和句末标点')
    buttons = policy['ring_buttons']
    if not isinstance(buttons,dict) or not 1<=len(buttons)<=32 or any(not isinstance(k,str) or not 1<=len(k)<=64 or v not in ('neutral','stop','protect') for k,v in buttons.items()):
        raise ValueError('指环按键映射无效')
    return policy


def voice_result(body, policy):
    if type(body.get('recognized')) is not bool:
        raise ValueError('recognized必须由识别组件明确报告bool值')
    confidence = finite(body.get('confidence'),'confidence',0.,1.)
    if type(body.get('final',True)) is not bool:
        raise ValueError('final应为bool')
    if not body.get('final',True):
        return {'ignored':'INCOMPLETE_RECOGNITION_RESULT'}
    if not body['recognized'] or confidence<policy['min_confidence']:
        return {'ignored':'UNRECOGNIZED_OR_LOW_CONFIDENCE'}
    text = body.get('text')
    code = body.get('command_code',body.get('action'))
    by_text = policy['voice_vocabulary'].get(normalize(text)) if text is not None else None
    if code is not None and (not isinstance(code,str) or len(code)>128):
        raise ValueError('command_code需为长度不超过128的字符串')
    by_code = policy['voice_codes'].get(code) if code is not None else None
    if text is not None and code is not None and (by_text is None or by_code is None or by_text!=by_code):
        raise ValueError('文本与指令编码不一致，不能选择其中一个执行')
    action = by_text or by_code
    if action is None:
        return {'ignored':'UNKNOWN_VOCABULARY'}
    duration = finite(body.get('duration_ms',1000),'duration_ms',100,5000)
    frame = body.get('frame','heading')
    if frame not in ('world','heading'):
        raise ValueError('frame必须为world或heading')
    return dict(action=action,duration_ms=duration,frame=frame,
                needs_confirmation=action in policy['dangerous_actions'])


def ring_event(body, policy):
    event_sequence = body.get('event_sequence')
    if type(event_sequence) is not int or event_sequence<0:
        raise ValueError('SDK事件event_sequence应为非负整数')
    kind = body.get('event_kind')
    if kind=='connection':
        if type(body.get('connected')) is not bool:
            raise ValueError('connected应为bool')
        return {'path':'/api/heartbeat'} if body['connected'] else {'path':'/api/disconnect','reason':'PERIPHERAL_DISCONNECTED'}
    if kind=='axes':
        phase = body.get('phase')
        if phase not in ('begin','update','end','neutral'):
            raise ValueError('动作阶段需为begin/update/end/neutral')
        if phase=='end':
            return {'path':'/api/stop'}
        return {'path':'/api/neutral' if phase=='neutral' else '/api/input',
                'axes':body.get('axes'),'frame':body.get('frame','heading')}
    if kind=='button':
        if type(body.get('pressed')) is not bool:
            raise ValueError('pressed应为bool')
        button = body.get('button')
        if not isinstance(button,str) or len(button)>64:
            raise ValueError('button应为长度不超过64的字符串')
        if not body['pressed']:
            return {'ignored':'BUTTON_RELEASED'}
        action = policy['ring_buttons'].get(button)
        if action is None:
            return {'ignored':'UNKNOWN_BUTTON'}
        return {'path':'/api/'+action,'axes':body.get('axes')}
    raise ValueError('未知指环event_kind')
