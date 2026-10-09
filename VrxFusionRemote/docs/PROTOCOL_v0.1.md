# 控制/状态协议 v0.1（接口基线，10/16 冻结）

> 传输：WebSocket（文本帧，UTF-8 JSON，一帧一条消息）
> 适用：遥控APP ⇄ 统一控制服务 ⇄ 仿真/设备端（UE）
> 状态：v0.1 首版，字段变更须先通知并评估，不擅自改字段

## 1. 通用消息头（每条消息必带）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `v` | int | ✓ | 协议版本，当前恒为 1 |
| `type` | string | ✓ | 消息类型，见第 2 节 |
| `session` | string | ✓ | 会话ID，hello 时由服务端分配，此后每帧携带 |
| `seq` | long | ✓ | 会话内单调递增序号（防乱序依据） |
| `ts` | long | ✓ | 毫秒 Unix 时间戳 |
| `src` | string | ✓ | 来源标识：`app:<用户名>` / `dev:<设备号>` / `svc` |
| `tgt` | string | 条件 | 目标设备编号（cmd/lease 类必填） |
| `corr` | string | 条件 | 关联编号：ack 回填对应 cmd 的 `cmd_id` |
| `ttl_ms` | int | 条件 | 有效期毫秒；过期消息必须丢弃（默认 1000） |
| `payload` | object | ✓ | 业务载荷，可为 `{}` |

## 2. 消息类型

| type | 方向 | 说明 |
|---|---|---|
| `hello` | 客户端→服务 | 注册：角色/名称/客户端类别 |
| `welcome` | 服务→客户端 | 分配 session，回告生效角色 |
| `roster` | 服务→APP | 设备目录（上线/离线/能力/控制权持有者） |
| `lease_req` | APP→服务 | 申请设备控制权 |
| `lease_rel` | APP→服务 | 释放控制权 |
| `lease_state` | 服务→全体APP | 控制权状态广播 |
| `cmd` | APP→服务→设备 | 控制指令 |
| `ack` | 服务/设备→APP | 指令应答（接收/执行/完成/失败/超时/拒绝） |
| `state` | 设备→服务→APP | 设备状态，≥20Hz |
| `heartbeat` | 双向 | 心跳保活，2s 周期 |
| `error` | 服务→客户端 | 协议级错误 |

## 3. 角色与权限（APP-002）

| role | 申请控制权 | 发 cmd | 收 state | 说明 |
|---|---|---|---|---|
| `admin` | ✓ | ✓ | ✓ | 管理员，可强制接管 |
| `operator` | ✓ | ✓ | ✓ | 操作员 |
| `observer` | ✗ | ✗（拒绝码 E_ROLE） | ✓ | 观察员只读 |

`hello.payload`：`{"role":"operator","name":"tan","client":"app"}`
设备端：`{"role":"device","client":"device","device_id":"uav-01","kind":"rotary_sim"}`

## 4. 控制权租约（SAFE-001 / CTL-002 / CTL-003）

状态机：`IDLE → REQUESTED → GRANTED → RELEASED|EXPIRED`

- 同一设备同一时刻仅一个持有者；单机接管与组控互斥
- `lease_state.payload`：`{"device":"uav-01","state":"GRANTED","holder":"app:tan","lease_seq":7,"expire_ts":…}`
- 切换由用户主动发起：先 `lease_rel` 或新持有者申请，服务清除上一通路残留指令，新通路中立后生效
- **TTL 兜底**：租约期内连续输入间隔超过 `ttl_ms`（默认 1000ms）或 APP 断连/心跳丢失 → 租约 EXPIRED，服务**立即向设备下发强制归零指令**（速度/角速度全 0 的 stop），并广播 lease_state；重连后不得自动恢复旧输入

## 5. 控制指令 cmd（CTL-001/004）

```json
{
  "type": "cmd",
  "tgt": "uav-01",
  "payload": {
    "cmd_id": "uuid",
    "action": "move | takeoff | land | hover | stop | return_home",
    "params": { "vx": 0.0, "vy": 0.0, "vz": 0.0, "yaw_rate": 0.0 }
  }
}
```

- 单位 SI：速度 m/s，角速度 rad/s；坐标系约定见数据字典（机体系：x 前 y 右 z 上；UE 侧转换由 C 负责）
- 连续指令（move）须携带递增 `seq` 与 `ttl_ms`，服务端校验乱序丢弃旧包（E_SEQ）
- 离散指令（takeoff/land/hover/return_home）幂等：同 `cmd_id` 重发不重复执行
- **stop/保护指令优先级最高**，不受租约限制（持有者可随时发），服务自身也可下发
- 参数越限 → 拒绝 E_RANGE；设备无该能力 → E_CAP；无控制权 → E_LEASE

## 6. 应答 ack（APP-006 / CTL-004）

`ack.payload`：`{"cmd_id":"…","status":"RECEIVED|EXECUTING|DONE|FAILED|TIMEOUT|REJECTED","err":"E_xxx","detail":"…"}`

- 服务收到合法 cmd 先回 RECEIVED 并转发设备；设备执行回 EXECUTING/DONE/FAILED，服务透传
- 服务转发后 800ms 未收到设备应答 → 向 APP 回 TIMEOUT
- 校验失败直接 REJECTED + 错误码

## 7. 状态 state（DAT-001/002，≥20Hz）

```json
{
  "type": "state", "src": "dev:uav-01",
  "payload": {
    "device": "uav-01", "kind": "rotary_sim",
    "pos": {"x":0.0,"y":0.0,"z":1.25},
    "vel": {"vx":0.0,"vy":0.0,"vz":0.0},
    "att": {"roll":0.0,"pitch":0.0,"yaw":0.0},
    "mode": "IDLE|FLYING|HOVER|LANDING|RETURN",
    "battery_pct": 87.5,
    "quality": "sim_truth|sim_meas|iface_test|real",
    "validity": {"gnss": true, "link": true, "stale": false}
  }
}
```

- `quality` 区分仿真真值/模拟测量/接口测试/未来实机，APP 必须按标记显示，未接入不得显示为真实在线（APP-004）
- 状态帧间隔 > 500ms 视为过期，APP 显示"状态过期"提示（APP-005），不得将缓存显示为实时

## 8. 错误码

| code | 含义 |
|---|---|
| E_ROLE | 角色无权限（如观察员发指令） |
| E_LEASE | 无控制权 / 控制权被他人持有 |
| E_SEQ | 乱序/旧包，已丢弃 |
| E_EXPIRED | 消息超过 ttl |
| E_RANGE | 参数超范围 |
| E_CAP | 设备不支持该能力 |
| E_STATE | 会话状态非法（未 ACTIVE） |
| E_TIMEOUT | 设备应答超时 |

## 9. 连接与日志

- 心跳 2s 周期；服务 5s 未收心跳判定断连 → 触发第 4 节兜底
- 全部 cmd/ack/lease/state 元数据写 SQLite（表：sessions/commands/leases/states），明细按会话分文件 JSONL 日志
- 服务异常不中断其他适配器；单设备断连不影响其他设备
