# 三维模型 v0.2 · 2026-10-12

这是三维基础接口说明。当前v0.3新增环境与抗扰功能，见 [升级说明](DISTURBANCE_MODEL.md)。下述“零风”描述仅适用于v0.2基线；v0.3已支持 `set_wind`。

本次把控制范围从升降扩展到世界坐标系的三轴速度和偏航角速度。Python复用RotorPy 3.0.0的完整刚体动力学；C++新增六自由度刚体及四独立电机模型。原一维代码保留为回归基线。

## 运行（VS Code）

在VS Code打开本说明所在的 `rotorpy_day1` 文件夹。Ctrl+Shift+P → Python: Select Interpreter，选择已安装RotorPy 3.0.0的环境。已有环境通常是：

```text
C:\Users\24107\.workbuddy-ai\binaries\python\envs\rotorpy-day1\Scripts\python.exe
```

按F5选择「5. 三维核心：轨迹与C++对照」。该入口先构建C++再运行演示；也可以在项目终端运行：

```powershell
$py = "$env:USERPROFILE\.workbuddy-ai\binaries\python\envs\rotorpy-day1\Scripts\python.exe"
powershell.exe -NoProfile -File .\cpp\build_windows.ps1
& $py .\run_3d_demo.py
& $py -m unittest test_core test_core_3d test_lab -v
```

未安装C++ Build Tools时，先用 `& $py .\run_3d_demo.py --python-only`。依赖锁文件沿用第一阶段；本包不重复附带离线wheel包。已有第一阶段wheelhouse可以继续用于对应Windows x64/Python3.13环境。

## 输入和状态

```python
from simulation_core import SimulationCore, to_ue_pose
core = SimulationCore()
# vx向前、vy向左、vz向上，速度m/s；偏航绕世界+z，rad/s。
core.set_motion(0.3, 0.2, 0.1, 0.2, ttl_s=0.5)
snapshot = core.step()       # 固定0.01s，调用方负责100Hz调度
pose = to_ue_pose(snapshot)  # 已支持三轴位置和姿态的UE坐标转换
core.stop()                 # 捕获实际三轴位置和偏航，闭环制动
core.protect("DISCONNECTED")
core.resume(neutral_confirmed=True)
```

`set_control(vz,ttl)`保留为旧升降入口，会把水平速度和偏航速度归零。建议所有新逻辑使用 `set_motion`。

默认水平合速度≤0.5m/s，竖直速度绝对值≤0.5m/s，偏航速度绝对值≤0.5rad/s。速度参考变化按三维向量范数限幅为0.5m/s²；偏航参考没有额外加速度斜坡，实际姿态由控制器、电机与刚体动力学响应。初始为(0,0,1)m空中悬停。

快照版本0.2新增 `reference_velocity_m_s`、`target_yaw_rad`、`yaw_rate_rad_s`；原 `state.x/v/q/w/rotor_speeds` 分别是实际位置、速度、xyzw四元数、机体角速度和电机转速。目标与实际状态应分开显示。

## C++模型与DLL

新增文件：

- `cpp/include/drone_core_3d.hpp`：C++三维核心。
- `cpp/src/drone_core_3d.cpp`：刚体动力学、控制器与电机分配。
- `cpp/include/drone_core_3d_c.h`：独立 `dc3_*` C ABI，版本2。
- `cpp/src/drone_core_3d_c.cpp`：异常转换为状态码。
- `cpp_core_3d.py`：ctypes加载实际DLL，包含句柄创建及销毁。

三维接口与旧 `dc_*` 一维接口共存在 `cpp/build/drone_core_c.dll` 中；旧接口继续执行旧一维模型。不能把 `dc_handle` 当作 `dc3_handle`，也不能互换快照结构。C调用需包含新头文件并使用 `dc3_create`、`dc3_set_control`、`dc3_step`、`dc3_get_state`、`dc3_destroy` 等导出。

三维控制器采用位置/速度PD与几何姿态PD，把期望推力和力矩分配到四电机。刚体积分包含：

```text
p_dot = v
m*v_dot = R(q)*F_body + [0,0,-m*g]
q_dot = 0.5*q ⊗ [omega_body,0]
J*omega_dot = torque_body - omega_body × (J*omega_body)
rotor_speed_dot = (commanded_speed - rotor_speed)/motor_tau
```

单桨推力 `k_eta*speed²`，反扭矩 `direction*k_m*speed²`，桨毂空气速度 `Rᵀ*v + omega×rotor_position`，阻力按 `k_d/k_z` 计算；桨毂力还通过力臂贡献力矩。四电机速度限在0–2500rad/s。控制100Hz，RK4积分子步≤1ms，每子步归一化四元数。

默认物理参数来自仓库内固定官方源码 `vendor/rotorpy/vehicles/crazyflie_params.py`；位置和姿态增益来自 `vendor/rotorpy/controllers/quadrotor_control.py`，保留其来源信息与 `vendor/LICENSE.md`。C++实现目前限于对角惯量、X型四旋翼、零风、零噪声，以及默认参数中非零的桨毂气动项；不包含RotorPy所有可配置机型或抽象控制入口。偏航目标角速度在C++中投影至机体系，RotorPy现有SE3控制器直接给定机体系z角速度，因此两者不是逐位相同的实现。

## 保护与限制

三轴和偏航共用输入有效期；过期、越界或数值故障清除全部旧输入并锁存保护。默认高度0.3–10m，水平范围是以初始xy为中心的半径10m圆柱。停止/保护保持实际位置并将越界参考投影回范围，实际制动仍有位移，不保证硬边界内绝不越界。

Python TTL按仿真时间计算，C++ TTL按调用方单调时钟计算。真实调度卡顿仍需要调用方的帧调度器先保护并丢弃积压；核心单次 `Step` 只推进一个固定步长。B仍负责授权、报文年龄、序列、控制权和中立确认。

没有地面接触、碰撞/避障、起飞降落流程、返航任务、GNSS或实机适配。本次未修改手机APP或HTTP协议。`ue/` 是保留的第一阶段一维组件骨架，仍调用 `dc_*`；它不会自动切换到三维模型。后续C需要迁移到 `dc3_*` 并把位置和旋转一起应用到Actor，完成UE内编译和方向验证。不能把当前UE骨架当作已接入的三维展示。

## 验证和输出

`test_core_3d.py` 验证六个有符号轴向、斜向合成、向量加速度、偏航及实际偏航保持、全轴TTL、水平边界、参数验证和实例隔离。

`cpp/tests/core_3d_tests.cpp` 除对应行为外，验证单电机变化引起耦合姿态/平移、四元数归一化、版本化C ABI和独立解析解：关闭桨毂阻力后电机衰减及竖直自由落体。原10项一维与6项调度测试仍运行。

`run_3d_demo.py` 同输入运行15s：向前2s→侧移/上升2s→反向斜移/下降/偏航3s→原地偏航2s→停止2s→斜移1s→输入失效保护至15s。反向段需给参考速度斜坡及实际制动留出时间。

输出在 `results/3d/`：

- `python_states.jsonl` / `cpp_states.jsonl`：1501帧轨迹。
- `summary.json`：检查结果、位置/姿态差异及本次DLL哈希。
- `trajectory_3d.png`：三维轨迹与三轴位置随时间曲线。

本次对照设位置最大差异<0.05m、姿态最大差异<0.05rad作为该固定场景的回归门槛；它不是官方实验实测数据，也不是需求方确认的全工况精度验收。编译验证采用Windows MSVC；CMake文件已更新但本机未验证CMake构建。UE和手机真机没有在本次任务中验证。
