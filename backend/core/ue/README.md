> GitHub 协作仓库只提供本目录的源码与历史骨架，不含预编译 DLL。使用前按下文构建并核对适用维度；本目录不代表当前三机手机画面或 UE 视频已验收。

# UE 最小接入包 v0.1

本包准备了UE5/Windows x64插件源码、C++项目骨架和已在本机编译验证的竖直核心DLL。当前机器没有UE引擎，因此UE模块、UHT、编辑器画面和独立游戏打包均尚未验证。不得把本包标记为UE联调通过。

## 现在可以运行的验证

在 `rotorpy_day1` 项目目录运行：

```powershell
& ./ue/prepare_ue_plugin.ps1
```

它编译并运行10项核心测试、6项调度测试、固定输入演示和原生C DLL消费端，然后同步头文件/DLL，并生成 `DroneCoreSandbox/Plugins/DroneCoreBridge`。不安装UE或网络依赖。

调度测试用30/60/144Hz渲染帧推进同一个100Hz模型，检查3s内都执行300步、持续0.3m/s输入下的最终高度一致。其他用例检查5s墙钟卡顿先保护后丢弃积压、超出每帧8步预算、停止调度不执行核心、补步均使用当前墙钟、时钟倒退/NaN拒绝和核心故障停机。日志在 `cpp/build/scheduler_test.log`。

## 文件

- `DroneCoreBridge/`：插件源码，可放到既有UE项目的 `Plugins/`。
- `DroneCoreSandbox/DroneCoreSandbox.uproject`：最小C++工程骨架，prepare脚本已复制插件进去。
- `DroneCoreComponent`：每Actor独立核心、固定步长调度、米到厘米的位置输出、保护和生命周期释放。
- `DroneCoreDemoActor`：引擎自带立方体作为外形，带离线固定输入演示和屏幕状态。
- `ThirdParty/Include`：从C++核心同步的C ABI和调度器头文件。
- `ThirdParty/Win64/drone_core_c.dll`：从当前构建同步的二进制；prepared_manifest记录SHA256及未进行UE验证。

## 安装UE后进行的步骤

先与成员C确认并锁定同一个UE5版本和编译工具链。工程的EngineAssociation留空，当前没有替你选择或锁定具体版本；它需要关联到实际安装的引擎。工程骨架没有 `.umap` 场景或已编译的UE插件二进制。

1. 用确认的UE版本关联并打开 `DroneCoreSandbox.uproject`，生成/编译Editor目标。版本相关的UBT设置若不兼容，保存完整构建日志后再调整；当前仅核心DLL已验证。
2. 在编辑器新建一个基础关卡并保存。保留地面、光源和可观察立方体的视角。
3. 在Place Actors/类列表中查找 `DroneCoreDemoActor`，拖入场景。其立方体边长约25cm。
4. 将Actor位置设为X=0、Y=0、Z=100cm；姿态设为0。根组件设为Movable，不开启Simulate Physics，不添加活动MovementComponent，不启用Replicate Movement。
5. 在Actor的Offline Demo属性中显式启用 `Enable Offline Demo`。默认关闭此测试输入来源。
6. 点击Play，预期先升2s、停止2s、降1s，停止续输入后约0.5s触发超时保护。屏幕应显示高度、速度、mode和reason。mode=2/reason=2代表输入超时。
7. 关闭Offline Demo后，Actor只保持悬停；后续接B服务时由已授权的服务路径调用组件接口。
8. 记录同版本下的截图/视频、Output Log和正常/异常用例；最后还要验证独立运行包内DLL是否正确部署。

演示时间使用墙钟，具体指令边界会按渲染帧量化，不要求与独立8s数值样例逐点一致。调试断点或长帧可能触发服务保护，这是有意行为；暂停后不得自动继续旧运动。

## 组件接口与责任

初始化默认自动执行，读取初始高度、创建独立句柄，检查C ABI版本并捕获世界原点。Actor出生位置保持不变，后续核心位置以该原点计算，所以屏幕HeightMeters是局部核心高度，未实现地形距离或GNSS绝对高度。示例放在Z=100cm时局部原点对应世界Z=0。

`SetVerticalInput(speed, remainingTTL)`只执行B已经合法化的指令；组件通过单调墙钟调用核心。B负责输入来源、权限、会话、控制权、序列、重复报文、TTL扣除和恢复确认。没有网络输入、视频直控或实机适配实现。默认关闭的DemoActor仅是明确标记的离线测试来源，不是APP授权链验证。

`StopMotion()`闭环停止，`ProtectMotion()`锁存服务保护，`ResumeAfterNeutral(true)`只能在B确认中立后调用。若Actor已被外部逻辑移动超过1cm，恢复会被拒绝，避免从回放/外部姿态继续积分。当前首版不能把任意回放姿态同步回一维核心。

`DisableSimulation()`触发保护并停止调度；它只是冻结核心，未实现回放播放器。模式切换期间不得同时让核心、UE物理、MovementComponent、网络位置复制或回放驱动同一Actor。需要回放时由C实现独立状态驱动和生命周期管理。组件检测活动MovementComponent/ReplicateMovement会拒绝初始化；初始化会关闭该Actor上的Primitive物理模拟。

所有接口应在游戏线程调用，网络线程先排队。不要在网络回调中直接操作UObject。每次Tick按累积时间补固定0.01s步长；补步最多8次，墙钟/帧间隔大于0.1s或积压超预算时先保护、丢弃本帧积压，再从后续帧执行保持。保护成功后核心仍能推进制动，运动输入会被拒绝；核心故障则冻结调度。Actor结束播放时释放句柄。更换DLL或重编插件前应关闭编辑器，当前没有验证热重载。

## 当前限制和后续验证

一维模型只更新位置，保持立方体的视觉旋转，不输出完整六自由度姿态。无碰撞扫掠、地面接触、避障、起降状态机、APP通信或传感器。SetActorLocation不扫掠，因此不能把穿越障碍行为视为避障成功。UE物理仅关闭重复积分，不替代核心的碰撞设计。

本次完成“可交接源码及独立调度测试”。完成UE环境后必须实测：UHT/UBT编译、类能放入关卡、正向位移/米厘米、DLL缺失错误、重复运动来源拒绝、断点/长帧保护、中立恢复、结束播放释放、多Actor隔离、插件迁移新工程及独立包DLL部署。

实现参考Epic官方 [插件结构](https://dev.epicgames.com/documentation/en-us/unreal-engine/plugins-in-unreal-engine) 和 [第三方库集成](https://dev.epicgames.com/documentation/unreal-engine/integrating-third-party-libraries-into-unreal-engine?lang=en-US)。本包通过GetDllHandle/GetDllExport动态绑定C接口，并声明DLL为NonUFS运行时依赖；实际打包路径仍需引擎验证。
