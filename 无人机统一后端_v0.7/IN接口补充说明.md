# v0.5软件接入接口（实物联调待完成）

沿用CTL基础公共字段：device_id、session_id、control_epoch、command_sequence、sent_at_ms、ttl_ms；身份由来源Token确定。离散事件有command_id，连续事件有intent_sequence。所有接口仍经过单设备控制权、中立握手、参数/权限/能力和运行模式校验。

## 指环 /api/ring-event

每个事件增加SDK原始event_sequence（递增，不能复用）和event_kind。来源必须为ring。

动作样例，以下字段与公共字段合并：

```json
{"event_sequence":0,"event_kind":"axes","phase":"neutral","axes":[0,0,0,0],"command_id":"ring-neutral-001"}
```

```json
{"event_sequence":1,"event_kind":"axes","phase":"begin","intent_sequence":0,"axes":[0.6,0,0,0],"frame":"heading"}
```

后续update沿用intent_sequence；end事件加command_id并停止。begin/update是连续指令，不积累离散幂等记录，TTL仍按报文采样时刻判断。动作轴顺序前/左/上/左转，归一化[-1,1]，其余灵敏度/死区/映射使用统一配置。

按键：`event_kind:"button",button:"stop",pressed:true,command_id:"ring-stop-001"`；confirm按键映射到归中握手，必须另带实际axes中立值；emergency映射到保护。pressed:false只返回IGNORED，不生成动作。连接事件：`event_kind:"connection",connected:false,command_id:"ring-disconnect-001"`将记录PERIPHERAL_DISCONNECTED并保护；重连先创建新会话，再由用户重新选择归中，不能只报connected:true就恢复。

## 语音 /api/voice-result

text（中文最终识别结果）或command_code（SDK指令编码），至少提供一项；两项都提供时必须语义一致。recognized和confidence必填，置信度范围[0,1]；final可显式报告，默认当前请求已是最终结果。

```json
{"command_id":"speech-001","intent_sequence":0,"text":"前进","recognized":true,"confidence":0.94,"final":true,"duration_ms":1000,"frame":"heading"}
```

也可`command_code:"forward"`。SDK自定义编码如1001的字符串映射由input_policy.voice_codes配置。旧/api/voice保留编码别名action，但同样必须带recognized/confidence，不允许绕过置信度或危险动作确认。

recognized=false、低于阈值、final=false、词表未知：HTTP200、command_status=IGNORED，原因可从命令查询读取；不会创建运动或延长已有语音动作。text/code冲突、非法置信度拒绝400。停止与悬停直接复用统一stop语义，紧急停止复用protect；停止/保护走高优先级队列。方向动作限时100–5000ms。

### 危险动作确认

默认“下降”返回WAITING_CONFIRMATION，等待APP确认，不发送下降速度。APP使用自己的会话和新鲜公共字段请求/api/confirm：

```json
{"command_id":"confirm-001","target_command_id":"speech-001","confirmed":true}
```

确认成功后原语音命令状态转EXECUTING，以原command_id查询/取消；APP确认命令自己状态COMPLETED。不允许指环或语音来源自行确认。切换、停止、断连会取消待确认动作，默认5秒超时为TIMED_OUT。词表与运动映射完成之后才判断是否危险，不能通过把“前进”映射成“下降”绕过确认。

### 起飞、降落、返航

词表包含这些语义，但返回HTTP501、error_code=TASK_NOT_SUPPORTED，状态input_task_capabilities对应项false。没有任务模块时不执行，不能用于IN-003验收。当前“悬停”是空中动力学制动保持，不是起飞；“返航”需要以后任务模块提供原点、航迹、保护与任务完成判断。

## 软件配置与失败处理

/api/config可更新input_policy（完整对象），包含词表/编码/按键映射、min_confidence、dangerous_actions及confirmation_timeout_ms，仍要求APP权限、HOLD和配置版本一致。默认文件control_config.json已包含示例，更新后需要重新选择/归中。

SDK桥接保留采样/识别完成时间及SDK序号；过期、乱序、网络失败不会被重新包装成新动作。参考桥接接收供应商模块事件，不实现语音识别或指环算法。软件日志与模拟测试见results；实际硬件/SDK信息、部署授权和联网条件见SDK接入与兼容说明，当前均待确认。

新增反馈字段：hardware_acceptance=PENDING_REAL_HARDWARE_AND_SDK；input_task_capabilities；离散命令semantic_action、status和reason。硬件验收状态不会因接口文件存在或模拟测试通过就转为完成。
