# 环境与抗扰 v0.3

本次新增均匀世界系风场、可复现阵风、物理冲量和可选位置积分补偿。Python基于RotorPy 3.0.0，C++基于v0.2的六自由度四电机刚体。原有三维速度、偏航、TTL和保护继续保留。

## 在VS Code运行

打开本包内 `rotorpy_day1` 文件夹，选择第一阶段使用的RotorPy Python环境。按F5选择「7. 风场、冲量与恢复对照」。它会先构建C++并运行原有及新增测试，再执行三个各25秒的仿真实验。

也可以在项目终端运行：

```powershell
$py = "$env:USERPROFILE\.workbuddy-ai\binaries\python\envs\rotorpy-day1\Scripts\python.exe"
powershell.exe -NoProfile -File .\cpp\build_windows.ps1
& $py .\run_disturbance_demo.py
& $py -m unittest test_core test_core_3d test_environment -v
```

只有Python环境时，运行 `& $py .\run_disturbance_demo.py --python-only`，不会加载DLL。已有依赖锁文件继续适用，无新增Python第三方依赖。

## 环境与冲量接口

```python
from simulation_core import SimulationCore
core = SimulationCore()
core.set_wind([2.0, -1.0, 0.25])
core.enable_disturbance_compensation(True)
core.apply_impulse([0.012, -0.009, 0.003], [4.29e-6, -2.86e-6, 5.78e-6])
snapshot = core.step()
```

- `set_wind`：世界系空气速度，单位m/s，向量范数≤5。它通过相对空气速度与桨毂阻力作用于模型，不直接给飞机指定速度。没有空间梯度；每一步可更新风速，模拟时变风。
- `apply_impulse`：第一个向量为世界系线冲量，单位N·s；第二个为机体系角冲量，单位N·m·s。线冲量产生Δv=J/m，角冲量产生Δω=惯量矩阵逆乘角冲量。默认质量0.03kg时0.012N·s产生0.4m/s的速度变化。
- 单次冲量导致的速度变化范数≤1m/s、角速度变化范数≤1rad/s。它不改变位置、姿态、参考目标、步进序号或保护模式，之后由动力学与控制器逐步制动。它是理想瞬时冲量，不是持续接触力、碰撞或地面接触模型。
- 环境管理入口不续控制TTL、不解除保护。停止和保护不会关闭真实存在的风；reset清零风与积分并关闭补偿。调用方仍负责单线程调度和环境管理权限。

快照Python版本0.3新增 `environment`，包含 `wind_m_s`、`compensation_enabled`、`compensation_acceleration_m_s2`。`state.wind`也保留RotorPy语义。

## 可选抗持续扰动控制

原位置PD控制器在持续侧风下存在稳态位置偏差。新增的位置积分项以2.0s⁻³的增益累计目标减实际位置误差，形成额外期望加速度，每个世界轴限幅±1m/s²。

如果本步电机命令超过0–2500rad/s，冻结本步积分并重新计算控制，避免继续积累不可执行的修正。积分和物理状态均在物理步成功后提交。停止/保护捕获新保持点但保留已有积分，因为环境扰动仍可能存在；切换开关和reset清空积分。

补偿默认关闭，保持v0.2的基线行为；开启后主要针对持续扰动。风撤掉后已有积分需要释放，因此风停恢复可能比原PD更慢。它不是风速估计器，控制器没有读取真实风速作为前馈；积分限幅也不保证任意风况都可抵抗。

## C++ / DLL兼容

现有 `dc3_config`、`dc3_state`布局和ABI版本2不变，仅增加四个导出：

```text
dc3_set_wind(handle, world_wind_m_s[3])
dc3_apply_impulse(handle, world_linear_impulse_n_s[3], body_angular_impulse_nm_s[3])
dc3_enable_compensation(handle, 0或1)
dc3_get_environment(handle, dc3_environment*)
```

独立 `dc3_environment` 的版本为1。使用本包的新头文件与DLL；v0.2的旧DLL没有这些新增导出。旧一维dc_*接口仍可用。

`cpp_core_3d.py`把环境读取结果合并到统一快照。风和冲量在每个实例独立保存，不使用全局随机数。

## 实验与证据

`wind_profiles.py`定义三个固定场景，每个实验25s、100Hz，记录2501帧：

1. 持续风：2–3s平滑起风，3–12s为 `[2,-1,0.25]m/s`，12–13s减弱至零。比较10–12s的位置误差均值，随后观察恢复。
2. 阵风：2–12s施加带平滑包络的三轴正弦风，之后无风。属于确定性合成阵风，不是Dryden/Von Kármán湍流或实测气象数据。
3. 冲量：2s施加线冲量，4s施加角冲量，观察速度、姿态及位置的恢复。

每个场景同时运行：原PD RotorPy、开启补偿的RotorPy、开启补偿的真实C++ DLL。恢复时刻定义为位置误差<1cm、速度范数<0.01m/s、机体角速度范数<0.01rad/s，且余下整个记录都保持达标。持续风从13s计恢复，阵风从12s计，冲量从4s计。这里未将偏航误差作为恢复指标。

输出：`results/disturbance/summary.json`、各场景三组JSONL状态，以及 `disturbance_recovery.png` 对照图。报告同时给出原PD的恢复时间，避免把稳态改善误写为所有瞬态都改善。

测试包含：六方向风作用、风/冲量参数及独立实例、冲量的动量变化与无瞬移、恢复到原保持点、持续风偏移改善、积分限幅与电机饱和冻结、环境不解除保护、风致越界及新增C ABI兼容。

本次回归门槛为固定场景的位置峰值<0.3m、风中偏移降低≥80%、恢复达标、C++与RotorPy最大位置/姿态差分别<0.02m/<0.02rad。这些是开发用回归门槛，不是官方实测精度或实机验收指标。未修改手机协议或迁移UE三维组件，也没有加入碰撞、空间风场、传感器噪声或GNSS。

## 本次实际结果

持续风10–12s位置误差均值：原PD约8.407cm，积分补偿约0.495cm，降低94.1%。风完全停止后，原PD恢复时间1.18s，补偿开启时6.08s。冲量场景在最后角冲量之后，原PD1.56s恢复，开启补偿时1.95s恢复。因此当前补偿适合减小持续扰动下的偏移，不能宣称其突变响应或阵风峰值优于原PD。

完整27项Python测试无失败、无跳过；C++原10项一维、6项调度、10组三维以及新增7组环境测试均通过。官方示例、原升降及三维对照也已重跑通过。原v0.2未修改的dc3 Python消费者加载新DLL测试通过，证明原数据结构及导出仍能使用。所有结果和本次DLL哈希保存在 `results/validation_3d/` 与 `results/disturbance/`。
