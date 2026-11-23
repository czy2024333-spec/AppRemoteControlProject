# UI 交互美化分支说明

本分支只做界面与交互反馈层，不涉及飞行协议、后端接口与操纵语义。

| 项 | 值 |
|---|---|
| 分支 | `ui-interaction-polish` |
| 基线 | `origin/main` @ `6b1f0de`（v0.9.3 联合前端） |
| 创建 | 2026-11-23 |
| 周期 | 2026-11-24 — 2026-12-07，10 个工作日 |
| 依据 | `旋翼遥控APP交互美化与实现方案_20261123.md`、`旋翼遥控APP_交互美化两周工作分期_20261123.md`（客户要求/改进目录） |

## 一、范围

**改**

- 摇杆杆量回显（左杆“转向 / 升降”，右杆“前后 / 横移”，死区内显示中位）
- 统一按压回弹 `Modifier.pressScale()`
- FMA 三格变化高亮
- 活跃告警呼吸
- 数值更新微闪
- 列表条目按压反馈
- 连接按钮三态（可选）

**不改**

- 摇杆轴映射、`/api/input` 发送节奏与 TTL、松杆停止行为
- 控制权申请与释放流程、告警排序、去杂乱分级
- `FlightGateway`、后端任何 Python 文件
- 摇杆模式一/二、手感曲线、音效、强震动、页面转场

## 二、本期决策

| 议题 | 结论 | 状态 |
|---|---|---|
| 摇杆与仪表重叠 | 按方案 A：摇杆 104dp、bottom 2、左右 14dp；仪表高 106dp；GS/VS 移到 x=124；地图右 14、132×106 | 默认执行，可改 |
| 连接状态闭环 | 纳入本期，排 D7；若进度紧则优先砍此项 | 默认执行，可砍 |
| 触觉反馈 | 本期不开放，仅记录为后续可选项 | 默认关闭 |

三项在 D1 之后不再变更；确需调整的移入下期。

## 三、基线核对（v0.9.3）

改动前确认以下事实，避免动到操纵语义：

| 事实 | 位置 | 值 |
|---|---|---|
| 摇杆快照组装 | `ui/LandscapeControlScreen.kt:127` | `StickSnapshot(right.x, right.y, left.x, left.y)` |
| 轴顺序 | `rotorpy/RotorPyClient.kt:86` | `axes() = [ry, -rx, ly, -lx]` |
| 后端解析 | `backend/app_server.py` | `[vx, vy, vz, yaw]`，故 `yaw = -lx` |
| UI 死区 | `ui/RotorPyScreen.kt:68`（`VirtualJoystick`） | `abs(x) < .08f` 归零，即 0.08 |
| 后端死区 | `backend/control_config.py:8` | `'deadzone': .08` |
| 停止条件 | `RotorPyClient.onStick` | 两杆全零才走 `/api/stop`；单轴回中只归零该轴 |

**代码落点核定**（2026-11-23 复核）：v0.9.3 可达页面由 `MainActivity.kt:85` 路由表决定，`single` → `RotorPyScreen` → `FlightControlScreen`（`LandscapeControlScreen.kt`）→ `VirtualJoystick`。`ui/ControlScreen.kt` 与 `ui/components/Joystick.kt` 无任何路由引用，是 v0.9.0 遗留死代码，**不是页面**，不得作为实现落点。D2 读数落在 `StickPanel` 与新增 `StickReadout.kt`，`VirtualJoystick` 手势逻辑不改。

读数文案沿用上表语义：左杆横向标“转向”，纵向标“升降”，不使用工程轴名。

## 四、提交约定

- 提交前缀 `ui:`，单项增强一个提交，便于回滚；
- 不修改 `backend/`、不改 `FlightGateway.kt`；
- 每项改动附对应单测或在现有测试中断言行为不变；
- 合并前需确认 `/api/input` 间隔、TTL、松杆停止与基线一致。

## 五、验收

1. 摇杆 5% 显示“中位”，10% 显示方向与百分比，方向与 `yaw = -lx` 一致；
2. 停止键下沉回弹，长按不重复触发；
3. FMA 变化后 1.2s 描边淡出；
4. 开启“减弱动态效果”后无呼吸与位移，功能正常；
5. 连续操纵 10 分钟帧率不低于基线；
6. Android 单测不低于 175 项基线。
