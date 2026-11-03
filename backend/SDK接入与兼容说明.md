# IN-001～006：SDK接入和硬件联调设计

甲方采购并提供指环、语音硬件，不在软件交付方硬件供货范围内。截至2026-10-13，具体型号、SDK、协议和识别组件均未提供。兼容性审核、真实驱动/SDK实现及硬件验收状态：**待甲方提供设备后完成**。

## 软件已准备的接入链路

供应商SDK/驱动 → vendor_provider模块 → sdk_bridge.py → HTTP规范化事件 → 身份/范围/能力/控制权/模式校验 → 三维动力学 → HTTP应答、命令状态查询及SSE反馈。

vendor_provider_example.py是接口契约，不是假设备驱动：open_device明确抛出尚未提供SDK的错误。实际供应商模块需实现：

```python
def open_device(profile):
    # 调用甲方设备SDK，返回实际连接对象。
    return device

# device.read_event(timeout_s) -> 一个规范化事件dict，超时返回None
# device.is_connected() -> 实际SDK报告的连接状态bool
# device.close() -> 释放SDK句柄、音频或蓝牙连接
```

SDK原始协议与本项目规范化协议是两层，不能要求未知指环直接发送这里的JSON。供应商模块负责将原始回调转换为规范化事件，不研发底层感知或识别算法。桥接采用Python模块接口；若实际SDK只提供Android/JNI/C++，需要按SDK提供的平台另做等价适配，当前Python契约不能证明其兼容。

## 硬件及SDK兼容审核清单

填hardware_profile.json，双方确认型号、固件、SDK版本、操作系统/CPU架构、通信方式（如BLE/USB/串口/网络）、原始帧格式、采样率、按键/动作定义、序号与时钟、断连回调、重连规则、驱动安装条件。指环连续有效输入目标≥20Hz，实际支持情况需查供应商资料并实测，当前不能声明已满足。

语音还需确认麦克风/控制硬件、中文词表支持、置信度与最终结果标记、是否提供指令编码、噪声条件、部署方式、授权与离线/联网依赖。预留本机SDK模块部署；没有选定供应商，许可证、费用、联网要求和CPU/GPU依赖均**未确认**，不能承诺离线可用或永久授权。云识别如被选用，须另行配置网络/凭据并确认时延和失败策略，不在本包中调用。

## 规范化事件与统一语义

SDK桥接输入公共字段：sequence（SDK事件递增序号）、timestamp_ms（SDK采集或识别完成时刻Unix毫秒）、ttl_ms（不超过500且服从后台配置）。source由桥接启动角色及独立Token确定，目标device_id为sim-drone-1，来源session_id、command_sequence、control_epoch由桥接添加；SDK时间戳不能被刷新成转发时间，过期/乱序事件直接拒绝。

指环axes方向为前/左/上/左转，值[-1,1]；控制死区、灵敏度、映射与APP共用后台配置。phase=begin表示真实新手势开始；update沿用同一意图；end停止；neutral需真实新鲜的中立采样。按键confirm默认只用于通路归中确认，不代替APP危险语音确认；stop停止，emergency保护。通信、数据格式与动作方向必须在实物SDK接入时核对，不能靠名称猜方向。

语音text或command_code必须白名单匹配，识别失败、低于阈值（默认0.8）、非最终结果、未知词都返回IGNORED，不生成运动指令。text/code同时存在必须语义一致。SDK不提供置信度时不能自行固定填1，应确认供应商的有效识别标记/编码保证及替代策略后调整详细设计。

方向、停止、悬停语义已接三维仿真。起飞、降落、返航词条能识别，但返回501 TASK_NOT_SUPPORTED：现有核心从空中悬停启动，缺少地面起降状态机、返航原点/任务与完成判断。**IN-003这些执行项尚未完成**，后续须接任务模块才能验收，不能用上升、下降、停止冒充。

默认下降视为需确认的动作，后台返回WAITING_CONFIRMATION，APP确认后才执行；切换、停止、断连取消待确认动作，超时TIMED_OUT。确认使用当前来源、设备、会话、轮次及新鲜APP报文校验。停止、悬停、保护不能配置为需要危险确认，以免阻塞制动。

## 软件配置

control_config.json新增input_policy：min_confidence、dangerous_actions、confirmation_timeout_ms、voice_vocabulary、voice_codes、ring_buttons。支持受限/api/config更新，或启动前修改文件。词表与编码最多各128项，语义仅允许已有白名单；配置新词条不等于添加任务执行能力。所有配置更新撤销通路并要求重新选择/归中。

部署命令（实际SDK模块实现并安装后）：

```powershell
python sdk_bridge.py --source ring --provider 实际指环供应商模块 --profile hardware_profile.json --url http://电脑局域网IP:8765
python sdk_bridge.py --source voice --provider 实际语音供应商模块 --profile hardware_profile.json --url http://电脑局域网IP:8765
```

profile由供应商模块读取，需要自行选择对应ring/voice配置。APP操作端持续监督心跳，来源选择后SDK桥接持续报告连接存活；读取状态、SDK回调接收不等于自动获得控制权。供应商SDK异常、设备断连、输入过期/非法或网络丢失均停止更新运动，尝试主动上报；无法上报由后台TTL/心跳保护。重连需新会话及重新选择归中，绝不自动回放旧手势或识别结果。

## IN-006实物验收

必须使用甲方实物，分别留存：设备/固件/SDK版本和部署授权信息、连接与断连记录、原始输入与标准事件对照、方向映射、按键确认与停止、中文识别词表与置信度、仿真执行、结果反馈、通路切换、重连无旧动作恢复。语音另测起飞/降落/返航任务（当前待实现）。

SDK桥接日志results/sdk_events.jsonl能记录接收、反馈与拒绝，但日志中provider名字或软件自报hardware标签不能独立证明实际硬件存在。测试脚本的SIMULATION_TEST_ONLY报文只用于软件回归，不算硬件联调。

hardware_acceptance_template.json用于收集资料与人工证据；acceptance_report.py检查项目是否缺项，最终验收仍需双方使用真实设备确认。甲方设备未到位前，报告必须保持hardware_acceptance_complete=false。
