# 初版历史说明（归档）

以下为最早的WebSocket联调说明，不适用于v0.9.1。当前使用方式请阅读工程根目录README与v0.9.1使用说明。下文路径按原工程根目录解释。

基于《旋翼式无人机仿真与多通路遥控系统技术需求书 V2.3》与《无人机项目_三人职责分工 V1.0》
实现的 Android 工程（Kotlin + Jetpack Compose），**角色 B 交付物**。
当前版本 **v0.1 联调版**：覆盖 10/13–10/16 首次联调范围 —— 真机按钮/摇杆经统一控制服务
进入仿真设备并回传状态，含角色权限、控制权租约、心跳/TTL、防乱序与日志。

> 架构基线变更（相对旧版）：传输层 MQTT → **WebSocket**（W1 计划：FastAPI + WebSocket 服务端）；
> 仿真平台 Isaac Sim → **UE**；机器狗暂缓，仅预留 EXT-002 接口并在 APP 中禁用。
> 服务端代码见同仓库 `server/` 目录；协议见 `docs/PROTOCOL_v0.1.md`。

## 一、编译运行

环境要求：
- Android Studio Koala（2024.1.1）及以上；Gradle JDK 17（**注意：AS 内置 JDK 25 与
  Gradle 8.7 不兼容**，Settings → Build Tools → Gradle → Gradle JDK 选择 JDK 17）
- minSdk 29（Android 10 及以上，APP-001），targetSdk 34

运行步骤（首周联调）：
1. 服务端：`cd server && pip install -r requirements.txt && python server.py`
2. 【无 UE 时】临时模拟设备：`python mock_device.py`（输出标记 iface_test）
3. 手机与电脑同一局域网 → 安装 APP → 首页填 `ws://<电脑IP>:8765/ws`、用户名、角色 → 连接
4. 选择「UE 仿真无人机」→ 申请控制权 → 摇杆/起飞/降落/悬停/停止
5. 观察员角色登录：可看状态，控制按钮不显示；即使构造指令也被服务端 E_ROLE 拒绝

## 二、首周验收用例对应（10/16 硬标准）

| 用例 | 实现位置 |
|---|---|
| 真机按钮/摇杆发有效指令，经统一服务到设备 | ControlScreen → ControlSession → ServiceAdapter → server.py |
| 观察员不能控制 | UI 隐藏控制件 + 服务端 E_ROLE 强制拒绝（双层） |
| 序列号防乱序 | 服务端按会话校验 seq，旧包丢弃回 E_SEQ |
| 租约 TTL（APP 闪退自动剥夺控制权） | 服务端 watchdog：TTL 到期 → 租约 EXPIRED + 强制归零 stop |
| 断网/输入超时保护，重连不续动 | APP 看门狗 600ms（SAFE-004）+ 服务端 TTL 1000ms 双保险 |
| 状态 20Hz 刷新与过期提示 | 服务端零缓冲透传；APP 超 500ms 未更新显示「状态过期」（APP-005） |
| 数据来源标记 | quality=iface_test/sim_truth/… 遥测面板显示（APP-004/DAT-002） |
| 日志 | 服务端 SQLite 元数据 + 每会话 JSONL；APP 指令记录留痕 |

## 三、工程结构

```
app/src/main/java/com/vrxfusion/remote/
├── MainActivity.kt                 # 单 Activity 入口
├── MainViewModel.kt                # 登录角色/连接/目录/控制会话（MVVM）
├── device/
│   ├── DeviceModels.kt             # 统一设备模型 + quality 数据标记 + 预留设备（EXT）
│   ├── DeviceCommands.kt           # 统一指令语义与指令状态（APP-006）
│   └── DeviceRepository.kt         # 设备目录（服务设备/预留接口/本地演示）
├── protocol/
│   ├── Protocol.kt                 # 协议 v0.1 信封/角色/错误语义（对应 docs/PROTOCOL_v0.1.md）
│   └── WsTransport.kt              # WebSocket 传输层（hello/2s 心跳/自动重连）
├── control/
│   └── ControlSession.kt           # 10Hz 连续指令、600ms 输入看门狗、急停、能力校验
├── adapters/
│   └── DeviceAdapters.kt           # LocalSim / Service（WS 路由）/ Reserved（EXT 预留）
├── sim/
│   └── VirtualDeviceSimulator.kt   # 内置演示仿真（标记 iface_test）
└── ui/
    ├── DeviceListScreen.kt         # 登录连接卡片 + 设备目录 + 预留禁用
    ├── ControlScreen.kt            # 控制台（观察员只读/过期提示/能力驱动按钮）
    └── components/                 # 摇杆、遥测面板、指令状态列表
```

## 四、需求条款映射（V2.3，当前状态）

| 条款 | 状态 |
|---|---|
| APP-001 Android 10+ | ✅ minSdk 29 |
| APP-002 登录与角色（管理员/操作员/观察员） | ✅ 角色选择 + 服务端强制仲裁 |
| APP-003 摇杆/按钮控制（死区/灵敏度） | ✅ 摇杆死区 0.08，配置项预留 |
| APP-004 状态显示与数据标记 | ✅ quality 标记；未接入/预留不显示为在线 |
| APP-005 画面/状态过期提示 | ✅ 状态过期提示；画面控件待 C 的视频组件接入 |
| APP-006 指令反馈（接收/执行/完成/失败/超时） | ✅ ack 状态机回填指令记录 |
| CTL-001~005 服务端校验/仲裁/TTL/失效保护 | ✅ server.py（见 server/README） |
| EXT-001/002 真实无人机/机器狗预留接口 | ✅ ReservedAdapter 禁用展示 + 模拟适配器验证链路 |
| EXT-004 未接入禁用、仿真管理不路由实机 | ✅ UI 禁用 + 适配器硬校验 |
| PERF-001 P95 ≤100ms | ◐ 计时埋点预留（cmd_id/corr），统计报表 W8 阶段 |
| PERF-002 20Hz 状态 | ✅ 实测 20Hz 透传 |
| CLU-001~006 多机/集群 | ❌ W5–W7（协议已留 device 路由与成员字段） |
| ADP-001~005 配置驱动控件 | ❌ W5–W7（控件已组件化，配置加载后续接） |
| 指环/语音通路（IN-001~006） | ❌ W3–W4（协议 src 字段已支持多来源） |

## 五、说明

- 本地演示机为内置仿真，仅用于无服务端时验证 UI 与看门狗，状态标记 iface_test，
  不代表 A 的动力学模型精度。
- 「实机同步」「回放模式」在运行模式中显示为预留（ARC-005），本期不启用。
- 协议字段冻结前如需变更，先通知 A/C 评估（分工文档接口规则）。
