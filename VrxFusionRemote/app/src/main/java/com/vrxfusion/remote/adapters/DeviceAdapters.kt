package com.vrxfusion.remote.adapters

import com.vrxfusion.remote.device.CommandRecord
import com.vrxfusion.remote.device.DeviceCommand
import com.vrxfusion.remote.device.DeviceDescriptor
import com.vrxfusion.remote.device.DeviceState
import com.vrxfusion.remote.protocol.MqttTransport
import com.vrxfusion.remote.protocol.MessageType
import com.vrxfusion.remote.protocol.UnifiedMessage
import com.vrxfusion.remote.sim.VirtualDeviceSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 设备适配器接口 —— ARC-004 / ARC-005 / IF-004：
 * 真实设备适配器与仿真适配器实现同一接口，APP 核心逻辑不感知设备差异。
 * 新增设备类型只需新增适配器实现，不改 APP 核心（OBJ-004 插件化）。
 */
interface DeviceAdapter {
    val descriptor: DeviceDescriptor
    val state: StateFlow<DeviceState>

    fun start()
    fun stop()

    /**
     * 发送指令。返回命令执行记录（含序列号与初始状态），
     * 后续状态回填通过 [onRecordUpdate] 通知。
     */
    fun send(cmd: DeviceCommand): CommandRecord

    /** 控制权持有人变化（SAFE-001：单控制者） */
    fun setController(name: String?)
}

/** 适配器公共基类：序列号、指令记录管理 */
abstract class BaseAdapter(final override val descriptor: DeviceDescriptor) : DeviceAdapter {
    protected val _state = MutableStateFlow(DeviceState(online = false))
    override val state: StateFlow<DeviceState> = _state

    val records = MutableStateFlow<List<CommandRecord>>(emptyList())
    var onRecordUpdate: ((CommandRecord) -> Unit)? = null

    protected fun makeRecord(cmd: DeviceCommand, status: com.vrxfusion.remote.device.CommandStatus, detail: String? = null): CommandRecord {
        val r = CommandRecord(nextSeq(), cmd.name(), status, detail = detail)
        records.value = (records.value + r).takeLast(30)
        onRecordUpdate?.invoke(r.copy())
        return r
    }

    protected fun updateRecord(seq: Long, status: com.vrxfusion.remote.device.CommandStatus, detail: String? = null) {
        val list = records.value.toMutableList()
        val idx = list.indexOfFirst { it.seq == seq }
        if (idx >= 0) {
            list[idx] = list[idx].copy(status = status, detail = detail ?: list[idx].detail)
            records.value = list
            onRecordUpdate?.invoke(list[idx])
        }
    }

    private val seqGen = java.util.concurrent.atomic.AtomicLong(0)
    private fun nextSeq() = seqGen.incrementAndGet()

    private fun DeviceCommand.name(): String = when (this) {
        is DeviceCommand.Motion -> "运动指令"
        is DeviceCommand.Stop -> "停止"
        is DeviceCommand.Gait -> "姿态(${posture.name})"
        is DeviceCommand.Takeoff -> "起飞"
        is DeviceCommand.Land -> "降落"
        is DeviceCommand.Hover -> "悬停"
        is DeviceCommand.ReturnHome -> "返航"
        is DeviceCommand.WaypointMission -> "航点任务"
        is DeviceCommand.SimManage -> "仿真管理(${action.name})"
        is DeviceCommand.EmergencyStop -> "紧急停止"
    }
}

/**
 * 本地虚拟设备适配器：直连内置仿真器，用于纯虚拟演示模式（MODE-001）
 * 与无统一控制服务时的离线开发调试。
 */
class LocalSimAdapter(
    private val scope: CoroutineScope,
    descriptor: DeviceDescriptor
) : BaseAdapter(descriptor) {
    private val sim = VirtualDeviceSimulator(scope, descriptor)

    override fun start() {
        scope.launch {
            sim.state.collect { s -> _state.value = s }
        }
        sim.start()
    }

    override fun stop() = sim.stop()

    override fun send(cmd: DeviceCommand): CommandRecord {
        val rec = makeRecord(cmd, com.vrxfusion.remote.device.CommandStatus.SUCCESS)
        sim.onCommand(cmd)
        return rec
    }

    override fun setController(name: String?) = sim.setController(name)
}

/**
 * MQTT 适配器：指令经统一控制服务路由（真实设备与 Isaac Sim 均走此通道，IF-004）。
 * 这是所有正式适配器的传输底座。
 */
open class MqttAdapter(
    protected val scope: CoroutineScope,
    descriptor: DeviceDescriptor,
    protected val transport: MqttTransport
) : BaseAdapter(descriptor) {

    override fun start() {
        transport.subscribe(descriptor.id)
        scope.launch {
            transport.incoming.collect { msgs ->
                msgs.lastOrNull { it.deviceId == descriptor.id }?.let { applyRemote(it) }
            }
        }
    }

    override fun stop() {}

    override fun send(cmd: DeviceCommand): CommandRecord {
        val payload = buildJsonObject {
            when (cmd) {
                is DeviceCommand.Motion -> {
                    put("kind", "motion"); put("vx", cmd.vx); put("vy", cmd.vy); put("wz", cmd.wz)
                }
                is DeviceCommand.Stop -> put("kind", "stop")
                is DeviceCommand.Gait -> { put("kind", "gait"); put("posture", cmd.posture.name) }
                is DeviceCommand.Takeoff -> put("kind", "takeoff")
                is DeviceCommand.Land -> put("kind", "land")
                is DeviceCommand.Hover -> put("kind", "hover")
                is DeviceCommand.ReturnHome -> put("kind", "rtl")
                is DeviceCommand.WaypointMission -> {
                    put("kind", "mission"); put("count", cmd.waypoints.size)
                }
                is DeviceCommand.SimManage -> { put("kind", "sim"); put("action", cmd.action.name) }
                is DeviceCommand.EmergencyStop -> put("kind", "estop")
            }
        }
        val rec = makeRecord(
            cmd,
            if (transport.state.value == MqttTransport.ConnState.CONNECTED)
                com.vrxfusion.remote.device.CommandStatus.SENT
            else com.vrxfusion.remote.device.CommandStatus.FAILED,
            detail = if (transport.state.value == MqttTransport.ConnState.CONNECTED) null else "MQTT 未连接"
        )
        val msg = UnifiedMessage(
            type = if (cmd is DeviceCommand.WaypointMission) MessageType.TASK else MessageType.CMD,
            deviceId = descriptor.id,
            sessionId = transport.currentSessionId(),
            seq = rec.seq,
            ts = System.currentTimeMillis(),
            src = com.vrxfusion.remote.protocol.DataSource.APP,
            payload = payload
        )
        val ok = transport.publish(msg)
        if (!ok) updateRecord(rec.seq, com.vrxfusion.remote.device.CommandStatus.FAILED, "发布失败")
        return rec
    }

    override fun setController(name: String?) {
        _state.value = _state.value.copy(controller = name)
    }

    /** 将远端 STATE/RESULT 消息套用到本地状态缓存 */
    private fun applyRemote(m: UnifiedMessage) {
        when (m.type) {
            MessageType.STATE -> {
                val p = m.payload ?: return
                fun f(k: String) = (p[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()
                _state.value = _state.value.copy(
                    online = true,
                    battery = f("battery")?.div(100f) ?: _state.value.battery,
                    yawDeg = f("yaw") ?: _state.value.yawDeg,
                    speed = f("speed") ?: _state.value.speed,
                    mode = (p["mode"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: _state.value.mode,
                    linkState = com.vrxfusion.remote.device.LinkState.ONLINE,
                    updatedAt = System.currentTimeMillis()
                )
            }
            MessageType.RESULT, MessageType.CMD_ACK -> {
                val status = (m.payload?.get("status") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val cs = when (status) {
                    "received" -> com.vrxfusion.remote.device.CommandStatus.RECEIVED
                    "executing" -> com.vrxfusion.remote.device.CommandStatus.EXECUTING
                    "success" -> com.vrxfusion.remote.device.CommandStatus.SUCCESS
                    "failed" -> com.vrxfusion.remote.device.CommandStatus.FAILED
                    "cancelled" -> com.vrxfusion.remote.device.CommandStatus.CANCELLED
                    else -> com.vrxfusion.remote.device.CommandStatus.TIMEOUT
                }
                m.ref?.let { updateRecord(it, cs) }
            }
            else -> Unit
        }
    }
}

/**
 * B2 机器狗真实适配器（桩）—— 报价模块 7「B2与ROS 2接入」：
 * 正式实现路径：ROS 2 侧部署 MQTT 桥接节点（ros2 mqtt bridge），
 * B2 SDK（ROS 2 话题/服务）经桥转换为本协议；APP 不直接依赖 DDS。
 * 关键约束：不改动机器人底层运动控制器（B2-001~006）。
 */
class B2Ros2Adapter(scope: CoroutineScope, descriptor: DeviceDescriptor, transport: MqttTransport) :
    MqttAdapter(scope, descriptor, transport)

/**
 * 大疆无人机适配器（桩）—— 报价模块 6「大疆MSDK接入」：
 * 正式实现路径：DJI MSDK 5.x 集成于本 APK（需在 build.gradle 添加
 * implementation("com.dji:dji-sdk:5.x") 与打包配置），或经统一控制服务
 * 侧的 MSDK 网关转发。最终能力以机型/固件/SDK 开放权限为准（DJI-001）。
 */
class DjiMsdkAdapter(scope: CoroutineScope, descriptor: DeviceDescriptor, transport: MqttTransport) :
    MqttAdapter(scope, descriptor, transport)

/**
 * Isaac Sim 适配器（桩）—— 报价模块 9「仿真环境适配」：
 * 正式实现路径：Isaac Sim 工作站运行 Python 扩展，通过其 Replicator /
 * Isaac API 订阅关节与位姿话题，经 MQTT 桥发布统一 STATE 消息（ARC-005）。
 * APP 端无需改动 —— 复用 MqttAdapter。
 */
class IsaacSimAdapter(scope: CoroutineScope, descriptor: DeviceDescriptor, transport: MqttTransport) :
    MqttAdapter(scope, descriptor, transport)
