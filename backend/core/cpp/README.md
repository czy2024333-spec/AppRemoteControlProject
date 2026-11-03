# v0.3环境与抗扰更新

新增风场、冲量、可选积分补偿和7组环境测试；保持原dc3 ABI结构布局。参见 [环境接口说明](../DISTURBANCE_MODEL.md)。

# 三维版本更新（2026-10-12）新增 `drone_core_3d.hpp` / `drone_core_3d_c.h` 与独立 `dc3_*` API；详见 [三维模型说明](../THREE_DIMENSIONAL_CORE.md)。`build_windows.ps1` 现在同时构建并测试一维、三维核心。下面原有一维说明保留为回归基线。

# A 向 C 交接的 C++ 竖直仿真核心 v0.1

后续增加了 `frame_scheduler.hpp` 和6项独立帧调度测试，以及 [UE源码接入包](../ue/README.md)。UE组件/工程骨架目前仅已准备源码，未进行引擎编译或画面验证。

本模块提供可独立编译的一维竖直动力学、受限升降控制、停止和保护状态机，以及Windows x64 DLL/C ABI。它用于首周最小模型接入，尚未实现C++六自由度模型、UE组件或三端联调。Python `simulation_core.py` 和 RotorPy 模型保留为对照基线。

## 已验证的构建方式

在 `rotorpy_day1` 目录运行：

```powershell
& ./cpp/build_windows.ps1
& "$env:USERPROFILE/.workbuddy-ai/binaries/python/envs/rotorpy-day1/Scripts/python.exe" verify_cpp_bridge.py
```

脚本通过vswhere发现已有的Visual Studio x64 C++工具链，不安装依赖。已在本机MSVC 14.44.35207、C++17、`/W4 /WX /O2 /MD` 编译并运行。可用 `-OutDir` 改变构建输出目录。`verify_cpp_bridge.py --dll <路径>`可指定DLL。

输出包含：

- `build/drone_core.lib`：纯C++静态库。
- `build/drone_core_c.dll` 与 `drone_core_c.lib`：C ABI动态库和导入库。
- `build/core_tests.exe`：数值和状态机测试。
- `build/core_demo.exe` 与 `core_demo.csv`：8s固定输入样例及801个状态样本。
- `build/c_api_smoke.exe`：实际编译为C语言并链接DLL的消费端验证。
- `build/compile.log`、`test.log`、`demo.log`、`c_api_smoke.log`：构建和验证证据。
- `../results/cpp/comparison.csv` 和 `summary.json`：Python加载DLL及RotorPy对照证据。

另提供 `CMakeLists.txt`。本机未找到CMake，当前交付只验证了上述MSVC脚本；CMake配置及其他平台编译尚未实测。

## 模型和参数

保持水平姿态，固定x/y，只计算z方向：

```text
dz/dt = v
dv/dt = T/m - g
dT/dt = (T_command - T)/tau
T_command = clamp(m*(g + kp*(z_target-z) + kd*(v_reference-v)), 0, T_max)
```

每个固定步长内保持命令推力不变，采用解析积分处理总推力一阶响应，再更新速度和位置。独立Plant允许验证推力小于、等于、大于重力；核心在其上执行控制和保护。

默认质量0.03kg、重力9.81m/s²、总推力上限0.575N、总推力响应时间0.072s、步长0.01s、初始高度1m、高度参考范围0.3至10m、竖直输入±0.5m/s、参考速度变化率0.5m/s²、TTL上限0.5s、位置增益15、速度增益9。参数通过C++ `Config` 或C `dc_config` 配置并校验。

0.575N对应当前RotorPy示例四个电机在2500rad/s的理论总推力；质量、重力和响应时间的数值参照现有实验参数，但总推力一阶模型与RotorPy电机转速一阶模型及气动阻力并不等价。默认值仅用于原型，不是实际设备标定值或需求方确认指标。

停止捕获当前高度，清除运动输入，随后闭环制动。真实状态可能短暂越过捕获点；不会直接修改位置或关闭电机。高度检查触发保护，不是碰撞/地面模型，也不能保证实际状态绝不越界。模型从空中平衡状态启动，尚不支持起飞、着陆、姿态、横向运动、航点、GNSS或避障。

## C接口调用

C/C++消费端包含 `include/drone_core_c.h`，DLL消费端定义 `DC_USE_DLL`，链接 `drone_core_c.lib`，运行时加载同版本x64 DLL。当前二进制使用动态MSVC运行库，部署时需确保目标机器具有兼容运行库。C调用不会收到跨DLL的C++异常，所有可失败操作返回状态码。

```c
dc_handle* handle = NULL;
dc_state snapshot;
/* 调用方必须检查每一步返回码。以下now由调用方单调时钟提供。 */
int32_t status = dc_create(NULL, &handle);   /* NULL配置采用默认参数 */
if (status == DC_OK) {
    status = dc_set_control(handle, 0.3, 0.5, now);
    if (status == DC_OK) status = dc_step(handle, next_now);
    if (status == DC_OK) status = dc_get_state(handle, &snapshot);
    dc_destroy(handle);
}
```

状态码：0成功；1参数无效（包括时钟倒退）；2保护锁存，拒绝运动；3内部/数值故障。`dc_step()`在正常执行保护保持时仍返回成功，必须通过快照的mode/reason获知保护事件。不要把成功返回码当成“运动指令已执行”。

模式：0保持；1连续输入；2保护。原因：0无、1停止、2输入超时、3服务保护、4参考高度限制、5实际高度限制、6数值故障。调用 `dc_protect(handle, DC_SERVICE_PROTECTION)` 执行服务下发的保护。保护锁存后，停止指令不会解除锁存。只有B确认合法控制权及新来源中立，才可调用 `dc_resume(handle, 1)`，之后仍需新的合法运动输入。

`dc_reset()`用于仿真管理，会复位位置、状态、时间和保护。B应限制其调用权限，不应路由至实机。快照携带接口版本、仿真时间、单调时间、序列、SI位置和速度、xyzw四元数、实际/命令推力、参考高度及参考速度。当前横向位置/速度恒为0，姿态恒为单位四元数。

头文件的C ABI首版要求编译端/运行端使用完全相同版本，使用默认结构体对齐。当前没有跨版本布局协商；禁止自定义pack，也不能直接把内存结构当网络报文。B仍负责序列、会话、权限、来源、幂等、协议编码、输入仲裁及剩余TTL计算。

`dc_create`输出变量应为空，创建后由同一DLL的 `dc_destroy`恰好释放一次；不要传入已销毁或其他来源的句柄。每个句柄由一个仿真线程使用，输入通过线程安全队列交给该线程。禁止并发调用同一个实例。

## 墙钟超时与固定步长

`dc_set_control`和`dc_step`的now必须是秒单位、有限、非负、不倒退的单调墙钟值。可以使用任意共同原点，但同一实例内必须一致；不能传UTC系统时间或混用不同来源时钟。复位后可继续使用调用方原来的单调时钟。

TTL表示指令到达核心时的剩余有效期，deadline=now+remaining_ttl。B先根据原报文年龄扣除已用有效期，不得用重复/旧输入续期。核心在步进或接收新输入前检查到期并锁存保护；在时钟100.0收到0.1s有效输入，下一次调用为105.0时，先保护，再推进0.01s模型，不会补执行过期运动。

每次step仅推进配置中的固定dt，与now间隔无关。C负责用累积器按固定步长调用，不可直接将渲染帧间隔作为动力学步长。需要补步时每次都传当前墙钟，不能用过去时间重演失效指令；发生长帧/调度停顿时应主动触发服务保护、限制补步并记录丢弃的积压时间。没有调用发生时库不会在后台自行运行或通知B，所以B/C还需要独立的断连/调度看门狗。

## UE交接检查

本版位置 `[0,0,z]` 单位m，姿态xyzw为 `[0,0,0,1]`；UE位置为 `[0,0,100*z]` cm。后续扩展横向/姿态时继续沿用核心x前/y左/z上的右手坐标到UE x前/y右/z上的约定，位置 `100*S*x`，姿态矩阵 `S*R*S`，`S=diag(1,-1,1)`。已有Python转换测试提供三个旋转轴的参考。

C应创建每设备独立核心实例，将快照位置/姿态应用到Actor，禁用UE再次推进同一运动状态。回放仅应用记录快照，不调用核心step。UE模块可使用C ABI包装，使异常留在DLL内部。UE加载、Build.cs、实际Actor坐标、渲染调度和打包仍待C按锁定的UE版本联调，当前没有声称这些已通过。

## 已执行的验证

10项C++测试覆盖推力与重力关系、解析解、Plant步长分割一致性、悬停、实例/快照隔离、升降、停止收敛、TTL边界、调度停顿、时钟倒退、保护恢复、参考高度限制、复位、非法参数、控制步长收敛及C接口错误码。

原生C消费端链接并运行DLL成功。Python通过ctypes实际加载DLL，验证错误码、墙钟超时、保护锁存与恢复，然后以同一组8s输入与RotorPy对照。结果详见 `results/cpp/summary.json`。差值用于记录当前两种简化程度的行为，尚未定义模型等价验收指标。
