# A 向 B 和 C 交接的仿真接口

v0.3新增风场、物理冲量和可选抗扰积分补偿，见 [环境与抗扰说明](DISTURBANCE_MODEL.md)。

2026-10-12已增加三维版本v0.2：世界系三轴速度与偏航输入、C++六自由度四电机模型、独立dc3_* ABI，详见 [三维接口与运行说明](THREE_DIMENSIONAL_CORE.md)。本文件下述内容主要保留第一阶段升降接口约定；水平及偏航请使用新说明。UE组件仍是一维骨架。

本次增加可持续步进的 Python 核心，复用 RotorPy 3.0.0 的六自由度模型和 SE3 控制器。当前控制入口只开放竖直升降、停止和保护。它是离线数值原型，尚未完成 C++ 移植、UE 接入、APP 联调或项目整体验收。

后续已新增 `cpp/` 的独立C++一维竖直模型、Windows x64 DLL/C接口和墙钟超时检查，参见 [C++交接说明](cpp/README.md)。这是首周最小模型实现，完整RotorPy六自由度模型尚未移植到C++。下述TTL规则仍描述Python原型，不应误用于C++接口。

另已准备 [UE插件及项目骨架](ue/README.md)，独立固定步长调度测试已通过；当前未安装UE，组件源码尚未在引擎内编译验证。

## 运行

使用现有 rotorpy-day1 隔离环境运行 `launch_lab.py`，依次执行官方示例、悬停/恢复、测试、核心固定输入演示。也可分别运行：

```powershell
& "$env:USERPROFILE/.workbuddy-ai/binaries/python/envs/rotorpy-day1/Scripts/python.exe" -m unittest test_lab test_core -v
& "$env:USERPROFILE/.workbuddy-ai/binaries/python/envs/rotorpy-day1/Scripts/python.exe" run_core_demo.py
```

上述命令需在本项目目录执行。默认单元测试不依赖历史输出；设置 `ROTORPY_CHECK_SAVED_RESULTS=1` 可附加检查保存的实验结果。运行实验的依赖清单写入 `results/requirements-observed.txt`，不再覆盖安装用锁文件。

## 调用顺序

```python
from simulation_core import SimulationCore, to_ue_pose

core = SimulationCore()
core.set_control(vertical_speed_m_s=0.3, ttl_s=0.5)
snapshot = core.step()       # 每次推进0.01s，默认100Hz
pose = to_ue_pose(snapshot)
core.stop()                 # 闭环制动并保持当前点
core.protect("DISCONNECTED")
# B确认控制权及新来源中立以后才调用：
core.resume(neutral_confirmed=True)
# 恢复后保持悬停，需要重新提交有效运动输入。
core.reset()                # 仿真管理操作，不应转发实机
```

`get_state()` 返回可JSON序列化的独立副本。各实例拥有自己的模型、控制器、时间和状态。接口应由一个线程调用，C应将网络输入排队送入仿真线程。当前核心关闭噪声，拒绝启用RotorPy使用全局随机源的电机噪声配置。

## 控制和保护规则

默认初始高度1m；参考高度范围0.3至10m；输入竖直速度范围±0.5m/s；参考速度变化率0.5m/s²；剩余输入TTL不大于0.5s。这些是可测试的原型参数，不是需求方确认的验收指标。默认从空中平衡状态启动，不包含地面接触、起飞/降落流程。

TTL以仿真时间计算，在到期的步长边界进入 `PROTECTED`。剩余有效期必须由B根据原始报文计算，不能每次收到旧报文都重新赋予完整TTL。调度暂停时仿真时间不会推进，因此真实网络断连、墙钟过期和调度卡顿仍须由B/C检测并调用 `protect()`；该核心不提供实时保证。

停止和保护都会清除连续输入、捕获当前位置作为保持目标。飞机通过动力学逐步制动，会存在短暂位移，绝不瞬移或断电。保护锁存后 `set_control()` 拒绝输入，`stop()`不解除保护。只有B确认新来源中立并显式调用 `resume(neutral_confirmed=True)` 才解除锁存，解除后旧运动不会续动。

参考高度越界或实际高度越界会触发保护；高度范围和参考速度限制并不保证飞机实际状态永不越界，制动距离、扰动、姿态失稳需要下一阶段保护设计。当前无静态避障、航点任务、返航、GNSS模拟或实机适配。

B负责权限、会话、序列、报文幂等、控制权、TTL与输入仲裁。此接口只执行已经合法的运动语义，`neutral_confirmed`不是身份认证。恢复、复位等操作需要由B限制调用权限。

## 单位和坐标

核心约定世界/机体x向前、y向左、z向上，右手坐标；位置m、速度m/s、角速度rad/s，四元数排列 `[x,y,z,w]`，表示机体到世界的旋转。状态 `x/v/q/w/wind/rotor_speeds` 保持RotorPy含义。快照时间是仿真时间，数据来源标记为 `simulation_truth`。

UE交接约定x向前、y向右、z向上，位置cm。令 `S=diag(1,-1,1)`，位置转换为 `100*S*x`，旋转矩阵转换为 `S*R*S`，再转为xyzw四元数。`to_ue_pose()`只转换位置和姿态。C需在实际Actor及模型轴向上验证三个正向平移、roll/pitch/yaw及单位缩放；不可把核心角速度直接当UE欧拉角速度。

C接入后只能由核心推进运动，UE端禁用重复物理积分。回放模式应只读取记录状态，禁止同时调用核心step。当前不提供UE模式管理实现。

## 固定输入与证据

`run_core_demo.py`执行8s样例：0至2s上升，2s停止，4至5s下降，然后不再续输入并进入超时保护。每0.1s续一次合法输入，TTL为0.5s。输出 `results/core/states.jsonl` 和 `summary.json`，包括高度变化、停止速度、保护原因、最终UE姿态。

`test_core.py`验证升降、停止收敛、TTL边界、保护锁存、中立恢复、参考高度限制、实例隔离、快照隔离、复位和三个旋转轴的坐标转换。现有实验测试另验证力平衡、官方参数不被修改、非法配置和碰撞终止不能误报通过。以上证据属于数值层；UE方向、APP三端链路、20Hz刷新和4小时验收仍需联调验证。
