"""无全局随机数的可复现风场：单位m/s，方向为世界系x前/y左/z上。"""
import math


def wind_at(scenario, time_s):
    if not math.isfinite(time_s) or time_s < 0:
        raise ValueError('time_s必须为非负有限值')
    if scenario == 'steady':
        # 2–3s平滑起风，3–12s恒定侧风，12–13s减弱，此后恢复无风。
        gain=max(0.,min(1.,time_s-2.,13.-time_s))
        return (2.*gain,-gain,.25*gain)
    if scenario == 'gust':
        if not 2. < time_s < 12.: return (0.,0.,0.)
        t=time_s-2.
        envelope=math.sin(math.pi*t/10.)**2
        return (2.*envelope*math.sin(2*math.pi*.4*t),
                1.5*envelope*math.sin(2*math.pi*.27*t+.4),
                .3*envelope*math.sin(2*math.pi*.6*t))
    if scenario == 'impulse': return (0.,0.,0.)
    raise ValueError('未知风场场景')


def impulses_at_tick(scenario, tick):
    if scenario != 'impulse': return None
    if tick==200: return (.012,-.009,.003),(0.,0.,0.)
    if tick==400: return (0.,0.,0.),(4.29e-6,-2.86e-6,5.78e-6)
    return None
