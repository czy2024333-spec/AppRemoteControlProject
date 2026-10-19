"""dc3_* C ABI 的 ctypes 适配；使用同包内 Windows x64 DLL。"""
import ctypes as C
import math
import numbers
from pathlib import Path


class Config(C.Structure):
    _fields_ = [('interface_version', C.c_uint32), ('step_s', C.c_double),
                ('initial_position_m', C.c_double*3)] + [(name, C.c_double) for name in (
                    'min_altitude_m','max_altitude_m','horizontal_radius_m',
                    'max_horizontal_speed_m_s','max_vertical_speed_m_s','max_yaw_rate_rad_s',
                    'reference_acceleration_m_s2','max_input_ttl_s')]


class State(C.Structure):
    _fields_ = [('interface_version',C.c_uint32), ('mode',C.c_int32),('reason',C.c_int32),
                ('sequence',C.c_uint64),('simulation_time_s',C.c_double),('monotonic_time_s',C.c_double)] + [
                    (name,C.c_double*size) for name,size in (
                        ('position_m',3),('velocity_m_s',3),('quaternion_xyzw',4),
                        ('angular_velocity_rad_s',3),('rotor_speeds_rad_s',4),
                        ('commanded_rotor_speeds_rad_s',4),('target_position_m',3),('reference_velocity_m_s',3))
                ] + [('target_yaw_rad',C.c_double),('reference_yaw_rate_rad_s',C.c_double)]


class Environment(C.Structure):
    _fields_=[('environment_version',C.c_uint32),('world_wind_m_s',C.c_double*3),
              ('compensation_acceleration_m_s2',C.c_double*3),('compensation_enabled',C.c_int32)]


class CppCore3D:
    def __init__(self, dll_path=None):
        self.dll=C.CDLL(str(Path(dll_path or Path(__file__).parent/'cpp/build/drone_core_c.dll').resolve()))
        self.handle=C.c_void_p()
        signatures={
            'default_config':[C.POINTER(Config)], 'create':[C.POINTER(Config),C.POINTER(C.c_void_p)],
            'destroy':[C.c_void_p], 'reset':[C.c_void_p], 'stop':[C.c_void_p],
            'protect':[C.c_void_p,C.c_int32], 'resume':[C.c_void_p,C.c_int32],
            'step':[C.c_void_p,C.c_double], 'get_state':[C.c_void_p,C.POINTER(State)],
            'set_control':[C.c_void_p,C.POINTER(C.c_double),C.c_double,C.c_double,C.c_double],
            'set_wind':[C.c_void_p,C.POINTER(C.c_double)],
            'apply_impulse':[C.c_void_p,C.POINTER(C.c_double),C.POINTER(C.c_double)],
            'enable_compensation':[C.c_void_p,C.c_int32],
            'get_environment':[C.c_void_p,C.POINTER(Environment)]}
        for name,args in signatures.items():
            function=getattr(self.dll,'dc3_'+name)
            function.argtypes=args
            function.restype=None if name=='destroy' else C.c_int32
        self.config=Config()
        self.check(self.dll.dc3_default_config(C.byref(self.config)))
        self.check(self.dll.dc3_create(C.byref(self.config),C.byref(self.handle)))

    @staticmethod
    def check(code):
        if code!=0: raise RuntimeError(f'dc3 status={code}')

    def set_motion(self, velocity, yaw_rate, ttl, now):
        values=(C.c_double*3)(*velocity)
        self.check(self.dll.dc3_set_control(self.handle,values,yaw_rate,ttl,now))

    def step(self, now):
        self.check(self.dll.dc3_step(self.handle,now))
        return self.get_state()

    def stop(self):
        self.check(self.dll.dc3_stop(self.handle))

    def protect(self):
        self.check(self.dll.dc3_protect(self.handle,3))

    def resume(self, neutral_confirmed):
        self.check(self.dll.dc3_resume(self.handle,1 if neutral_confirmed is True else 0))

    @staticmethod
    def vector(value):
        if not isinstance(value,(list,tuple)) or len(value)!=3: raise ValueError('expected three vector elements')
        if any(isinstance(v,bool) or not isinstance(v,numbers.Real) or not math.isfinite(v) for v in value):
            raise ValueError('vector must contain finite real numbers, not bool')
        return (C.c_double*3)(*value)

    def reset(self):
        self.check(self.dll.dc3_reset(self.handle))

    def set_wind(self, wind):
        self.check(self.dll.dc3_set_wind(self.handle,self.vector(wind)))

    def apply_impulse(self, linear, angular=(0.,0.,0.)):
        self.check(self.dll.dc3_apply_impulse(self.handle,self.vector(linear),self.vector(angular)))

    def enable_disturbance_compensation(self, enabled=True):
        if type(enabled) is not bool: raise ValueError('enabled must be bool')
        self.check(self.dll.dc3_enable_compensation(self.handle,int(enabled)))

    def get_state(self):
        state=State()
        self.check(self.dll.dc3_get_state(self.handle,C.byref(state)))
        if state.interface_version!=2: raise RuntimeError('dc3 version mismatch')
        environment=Environment()
        self.check(self.dll.dc3_get_environment(self.handle,C.byref(environment)))
        if environment.environment_version!=1: raise RuntimeError('environment version mismatch')
        arrays={name:list(getattr(state,name)) for name,_ in State._fields_ if isinstance(getattr(state,name),C.Array)}
        return {'interface_version':'0.3','data_source':'simulation_truth',
                'time_s':state.simulation_time_s,'sequence':state.sequence,
                'mode':('HOLD','ACTIVE','PROTECTED')[state.mode],
                'protection_reason':state.reason,
                'state':{'x':arrays['position_m'],'v':arrays['velocity_m_s'],
                         'q':arrays['quaternion_xyzw'],'w':arrays['angular_velocity_rad_s'],
                         'rotor_speeds':arrays['rotor_speeds_rad_s'],'wind':list(environment.world_wind_m_s)},
                'target_position_m':arrays['target_position_m'],
                'reference_velocity_m_s':arrays['reference_velocity_m_s'],
                'target_yaw_rad':state.target_yaw_rad,
                'yaw_rate_rad_s':state.reference_yaw_rate_rad_s,
                'environment':{'wind_m_s':list(environment.world_wind_m_s),
                               'compensation_enabled':bool(environment.compensation_enabled),
                               'compensation_acceleration_m_s2':list(environment.compensation_acceleration_m_s2)}}

    def close(self):
        if self.handle:
            self.dll.dc3_destroy(self.handle)
            self.handle=C.c_void_p()

    def __enter__(self): return self
    def __exit__(self,*_): self.close()
