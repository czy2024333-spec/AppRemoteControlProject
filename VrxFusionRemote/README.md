# VrxFusionRemote —— 虚实融合异构无人装置遥控APP

基于《虚实融合异构无人装置遥控APP技术需求书 V1.0》实现的 Android 工程（Kotlin + Jetpack Compose）。
当前版本为**工程原型**：内置虚拟设备仿真，无需任何真实硬件或 Isaac Sim 即可安装运行、演示完整控制链路；真实设备（B2 / 大疆）与 Isaac Sim 通过预留适配器接入。

## 一、编译运行

环境要求：
- Android Studio Koala（2024.1.1）及以上（含 AGP 8.5 支持，JDK 17 由 AS 内置）
- 本工程未附带 gradle-wrapper.jar：用 Android Studio 打开工程目录后，AS 会按 `gradle/wrapper/gradle-wrapper.properties`（Gradle 8.7）自动处理；如提示 wrapper 缺失，可在 Settings → Build Tools → Gradle 中选择 AS 内置 Gradle 或执行 `gradle wrapper` 生成
- minSdk 29（Android 10，对应 NFR-003），targetSdk 34

运行：连接手机/模拟器 → Run 'app'。启动后进入设备目录，点击任一**虚拟设备**→「申请控制权」→ 摇杆即遥控（内置 20Hz 仿真状态、电量消耗、低电告警）。

演示要点：
1. 摇杆拖动 → 设备位置/航向/速度实时变化（`Motion` 指令 10Hz）
2. 松开摇杆 → 自动停机（SAFE-004 看门狗）
3. 无人机虚拟样机 → 起飞/降落/悬停/返航按钮（DJI-003）
4. B2 虚拟样机 → 站立/趴下（B2-003）、仿真复位/暂停（SAFE-005，仅虚拟设备可见）
5. 紧急停止红色按钮（SAFE-002）
6. 运行模式切换带确认对话框，明示指令接收对象（MODE-005）
7. 「设备目录」真实设备项默认离线 —— 接入统一控制服务（MQTT）后自动上线

## 二、工程结构

```
VrxFusionRemote/
├── app/src/main/java/com/vrxfusion/remote/
│   ├── MainActivity.kt                 # 单 Activity 入口（Compose Navigation 由状态驱动）
│   ├── MainViewModel.kt                # MVVM：目录/选择/控制权/指令记录
│   ├── device/
│   │   ├── DeviceModels.kt             # 统一设备模型：类型/虚实属性/能力清单/状态（OBJ-002, MAP-002）
│   │   ├── DeviceCommands.kt           # 统一指令语义 + 指令状态（B2-008, DJI-010, APP-010）
│   │   └── DeviceRepository.kt         # 设备目录服务（ARC, APP-002）
│   ├── protocol/
│   │   ├── Protocol.kt                 # 版本化统一消息/主题规划（IF-001~005）
│   │   └── MqttTransport.kt            # MQTT 传输层（Paho，断线重连 NFR-002）
│   ├── control/
│   │   └── ControlSession.kt           # 控制权/10Hz 连续指令/看门狗/能力校验/急停（SAFE-001~004）
│   ├── adapters/
│   │   └── DeviceAdapters.kt           # 适配器接口 + LocalSim/Mqtt 底座 + B2/DJI/Isaac 桩（OBJ-004）
│   ├── sim/
│   │   └── VirtualDeviceSimulator.kt   # 内置虚拟设备仿真（20Hz，MODE-001）
│   └── ui/
│       ├── DeviceListScreen.kt         # 设备目录（APP-002~005）
│       ├── ControlScreen.kt            # 统一控制台（APP-006~010, MODE-005, SAFE-002）
│       ├── components/Joystick.kt      # 虚拟摇杆：死区/灵敏度/回中（APP-007）
│       ├── components/Telemetry.kt     # 遥测面板/虚实徽标/指令状态
│       └── theme/
```

## 三、需求条款映射（当前实现状态）

| 需求条款 | 状态 |
|---|---|
| OBJ-002 统一设备模型/统一控制协议 | ✅ `DeviceModels` + `Protocol`（版本化 MQTT 消息） |
| OBJ-004 插件化适配架构 | ✅ `DeviceAdapter` 接口，新增设备零改核心 |
| APP-002~005 设备目录/虚实标识/控制权展示 | ✅ 设备列表页 |
| APP-006 能力驱动装载控制界面 | ✅ 按 `Capability` 动态显示按钮 |
| APP-007 虚拟摇杆（死区/灵敏度/回中） | ✅ Joystick 组件；蓝牙手柄输入待接（按键映射入口已预留） |
| APP-008/010 遥测显示、指令状态反馈 | ✅ 遥测面板 + 指令记录（已发送/成功/失败…） |
| MODE-001~005 运行模式与切换确认 | ✅ 三种模式 + 切换确认对话框（明示指令接收对象） |
| B2-002~003 运动控制/姿态 | ✅ 统一 Motion/Gait 指令（演示由仿真器执行） |
| DJI-003 起降/悬停/返航 | ✅ 统一指令（演示由仿真器执行） |
| SAFE-001 单控制者 | ✅ ControlSession 控制权状态机（正式版接入服务端仲裁） |
| SAFE-002 紧急停止 | ✅ 最高优先级急停 |
| SAFE-003 危险指令校验 | ◐ 控制权+能力校验已实现；二次确认 UI 待加 |
| SAFE-004 输入中断停机 | ✅ 600ms 看门狗自动发送停止 |
| SAFE-005 仿真指令隔离 | ✅ SimManage 仅虚拟/绑定设备可见且控制层硬校验 |
| IF-001~005 通信接口 | ✅ 统一消息头（版本/类型/会话/序列/时间戳/来源/关联） |
| PERF-002 20Hz 状态 | ✅ 仿真器 20Hz；PERF-001 时延统计待加 |
| MAP-004~007 坐标/时间映射 | ◐ 演示用 ENU 简化模型；正式转换待统一控制服务 |
| 地图/航点/视频（APP-009, DJI-004/005） | ❌ 二期（对应报价模块 3/6） |
| 真实 B2/DJI/Isaac Sim 接入 | ❌ 桩就绪，见下方路线 |

## 四、真实设备接入路线（对应报价模块）

1. **统一控制服务（模块 2/4）**：局域网部署 MQTT Broker（如 EMQX/mosquitto）+ 路由服务；APP 侧改 `MqttTransport` 的 brokerUri 即可。
2. **B2 与 ROS 2（模块 7）**：ROS 2 侧跑 MQTT 桥接节点，把 `/cmd_vel`、关节状态、里程计转成本协议 STATE/CMD；`B2Ros2Adapter` 已经走此通道，无需改 APP。
3. **大疆 MSDK（模块 6）**：在 `app/build.gradle.kts` 引入 `com.dji:dji-sdk:5.x` 并按 DJI 文档配置打包；或由统一控制服务侧做 MSDK 网关。`DjiMsdkAdapter` 替换实现即可。
4. **Isaac Sim（模块 9）**：Isaac Sim 工作站跑 Python 扩展（Isaac API + paho-mqtt），发布统一 STATE；`IsaacSimAdapter` 走 MQTT，无需改 APP。
5. **QGC 定制（模块 2）**：如需复用 QGC 地图/航线能力，QGC 以 library 形式集成进本 APK（JNI 桥已在报价方案中规划）。

## 五、说明

- 传输层用 Paho MQTT（纯 JVM 客户端，Android 兼容）；如后续需要 WebSocket/TLS，只需改 `MqttTransport`。
- 演示用仿真器不代表 Isaac Sim 物理精度，仅用于链路与 UI 验证。
- 需求书要求的登录/角色权限（APP-001）、日志审计平台（SAFE-006）在正式版补齐；当前指令记录已留痕于本地。
