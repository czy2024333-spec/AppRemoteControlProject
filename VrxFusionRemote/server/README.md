# 统一控制/状态服务 v0.1（角色 B 交付物）

对应《旋翼式无人机仿真与多通路遥控系统技术需求书 V2.3》第 4 章（CTL-001~006）、
第 6 章（DAT-001/002）与分工文档「成员 B：控制/状态服务」职责。
协议见 `../VrxFusionRemote/docs/PROTOCOL_v0.1.md`（10/16 冻结首版接口）。

## 功能覆盖（首周联调范围）

| 需求 | 实现 |
|---|---|
| CTL-001 统一校验 | 角色/参数范围/能力/控制权/运行状态五层校验，按序拒绝并回错误码 |
| CTL-002 单一连续输入源 | 每设备一个控制权持有者；stop/保护指令优先级最高 |
| CTL-003 通路切换 | 用户主动申请/释放；管理员强制接管先清残留（强制归零）再中立生效 |
| CTL-004 TTL/幂等/应答 | 连续指令 1000ms TTL + 序列号防乱序；离散指令按 cmd_id 幂等；800ms 设备应答超时 |
| CTL-005 失效保护 | APP 断连/心跳超时/租约到期 → 立即向设备下发强制归零；重连不恢复旧输入 |
| APP-002 角色 | admin/operator 可控，observer 只读（E_ROLE 强制拒绝） |
| PERF-002 20Hz | 设备状态零缓冲透传转发（实测 20Hz 达标） |
| LOG-001 记录 | SQLite 元数据（sessions/commands/leases/states_meta）+ 每会话 JSONL 分文件日志 |

## 启动步骤

```bash
# 1. 安装依赖（Python 3.10+）
pip install -r requirements.txt

# 2. 启动服务（默认 0.0.0.0:8767）
python server.py

# 3. 【临时测试适配】无 UE 时启动模拟设备（quality=iface_test，明确标识非正式模型）
python mock_device.py

# 4. APP 侧连接 ws://<本机IP>:8767/ws
```

健康检查：`curl http://127.0.0.1:8767/health`

## 验收用例（自动化）

```bash
python test_acceptance.py
```

覆盖首周硬标准：操作员获得控制权并发指令、观察员拒绝（E_ROLE）、乱序丢弃（E_SEQ）、
无控制权拒绝（E_LEASE）、杀 APP 后租约自动 EXPIRED + 服务端强制归零、20Hz 状态转发。
当前结果：9/9 全部通过。

## 文件说明

| 文件 | 说明 |
|---|---|
| `server.py` | 服务主体（FastAPI + WebSocket + SQLite watchdog） |
| `mock_device.py` | 【临时测试适配】最小竖直模型模拟设备，A 的仿真核心/C 的 UE 接入后替换 |
| `test_acceptance.py` | 首周验收用例自动化脚本 |
| `service.db` | SQLite 元数据（运行后生成） |
| `logs/<session>.jsonl` | 每会话分文件日志（运行后生成） |

## 配置项（server.py 顶部，CTL-006 预留配置管理）

`LEASE_TTL_MS`（租约 TTL，默认 1000）、`CMD_ACK_TIMEOUT_MS`（800）、
`HEARTBEAT_TIMEOUT_S`（5）、`STATE_STALE_MS`（500）、`MAX_SPEED`（5.0 m/s）、
`MAX_YAW_RATE`（1.5 rad/s）。详细设计阶段书面确认后冻结。

## UE/真实设备端接入方式（给 A/C）

设备端以 WebSocket 客户端连接 `ws://<服务IP>:8767/ws`，首帧 hello：
`{"type":"hello","payload":{"role":"device","client":"device","device_id":"uav-sim-01","kind":"rotary_sim","capabilities":[...]}}`
此后按 ≥20Hz 发 `state`，收 `cmd` 并回 `ack`（corr 回填 cmd_id）。字段见协议文档第 5/6/7 节。
