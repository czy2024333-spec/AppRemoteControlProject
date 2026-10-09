package com.vrxfusion.remote.adapters

import com.vrxfusion.remote.device.CommandRecord
import com.vrxfusion.remote.device.CommandStatus
import com.vrxfusion.remote.device.DeviceCommand
import com.vrxfusion.remote.device.DeviceDescriptor
import com.vrxfusion.remote.device.DeviceState
import com.vrxfusion.remote.device.LinkState
import com.vrxfusion.remote.device.Vec3
import com.vrxfusion.remote.protocol.AckStatus
import com.vrxfusion.remote.protocol.MsgType
import com.vrxfusion.remote.protocol.Protocol
import com.vrxfusion.remote.protocol.WsMessage
import com.vrxfusion.remote.protocol.WsTransport
import com.vrxfusion.remote.sim.VirtualDeviceSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 设备适配器接口 —— OBJ-004 / ARC-002：
 * 本地演示适配器、统一服务路由适配器、预留接口适配器实现同一接口，
 * APP 核心逻辑不感知设备差异；新增设备类型只需新增适配器实现。
 */
interface DeviceAdapter {
    val descriptor: DeviceDescriptor
    val state: StateFlow<DeviceState>

    fun start()
    fun stop()

    /** 发送指令，返回执行记录；后续 ack 经 [onRecordUpdate] 回填（APP-006） */
    fun send(cmd: DeviceCommand): CommandRecord

    /** 控制权申请/释放（SAFE-001；ServiceAdapter 映射为 lease_req/lease_rel） */
    fun setController(name: String?)
}

/** 适配器公共基类：序列号、指令记录管理 */
abstract class BaseAdapter(final override val descriptor: DeviceDescriptor) : DeviceAdapter {
    protected val _state = MutableStateFlow(DeviceState(online = false))
    override val state: StateFlow<DeviceState> = _state

    val records = MutableStateFlow<List<CommandRecord>>(emptyList())
    var onRecordUpdate: ((CommandRecord) -> Unit)? = null

    protected fun makeRecord(cmd: DeviceCommand, status: CommandStatus, detail: String? = null): CommandRecord {
        val r = CommandRecord(nextSeq(), cmd.displayName(), status, detail = detail)
        records.value = (records.value + r).takeLast(30)
        onRecordUpdate?.invoke(r.copy())
        return r
    }

    protected fun updateRecord(seq: Long, status: CommandStatus, detail: String? = null) {
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

    companion object {
        fun DeviceCommand.displayName(): String = when (this) {
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
}

/**
 * 本地演示适配器：直连内置仿真器。
 * 仅用于无服务端时的离线开发调试，状态标记 iface_test（APP-004 明确标记）。
 */
class LocalSimAdapter(
    private val scope: CoroutineScope,
    descriptor: DeviceDescriptor
) : BaseAdapter(descriptor) {
    private val sim = VirtualDeviceSimulator(scope, descriptor)

    override fun start() {
        scope.launch {
            sim.state.collect { s -> _state.value = s.copy(quality = "iface_test") }
        }
        sim.start()
    }

    override fun stop() = sim.stop()

    override fun send(cmd: DeviceCommand): CommandRecord {
        val rec = makeRecord(cmd, CommandStatus.SUCCESS)
        sim.onCommand(cmd)
        return rec
    }

    override fun setController(name: String?) = sim.setController(name)
}

/**
 * 统一服务路由适配器 —— 正式通道（ARC-002/ARC-004）：
 * APP → WebSocket → 统一控制服务（校验/仲裁）→ UE 仿真或设备端。
 * 服务端返回的 lease_state / ack / state 驱动本地状态与指令记录。
 */
open class ServiceAdapter(
    protected val scope: CoroutineScope,
    descriptor: DeviceDescriptor,
    protected val transport: WsTransport
) : BaseAdapter(descriptor) {

    /** cmd_id -> 本地记录 seq，用于 ack 回填 */
    private val cmdSeqMap = ConcurrentHashMap<String, Long>()

    override fun start() {
        scope.launch {
            transport.incoming.collect { m -> handle(m) }
        }
    }

    private fun handle(m: WsMessage) {
        when (m.type) {
            MsgType.STATE -> {
                if (m.str("device") != descriptor.id) return
                applyState(m.payload)
            }
            MsgType.ACK -> {
                val corr = m.corr ?: return
                val seq = cmdSeqMap[corr] ?: return
                val st = AckStatus.of(m.str("status"))
                val cs = when (st) {
                    AckStatus.RECEIVED -> CommandStatus.RECEIVED
                    AckStatus.EXECUTING -> CommandStatus.EXECUTING
                    AckStatus.DONE -> CommandStatus.SUCCESS
                    AckStatus.FAILED -> CommandStatus.FAILED
                    AckStatus.TIMEOUT -> CommandStatus.TIMEOUT
                    AckStatus.REJECTED -> CommandStatus.FAILED
                    null -> return
                }
                val err = m.str("err")?.let { code -> "$code ${m.str("detail") ?: ""}".trim() }
                updateRecord(seq, cs, err)
            }
            MsgType.LEASE_STATE -> {
                if ((m.tgt ?: m.str("device")) != descriptor.id) return
                val holder = m.str("holder")?.takeIf { it.isNotEmpty() && it != "null" }
                _state.value = _state.value.copy(controller = holder)
            }
            MsgType.ROSTER -> applyRoster(m.payload)
            else -> Unit
        }
    }

    private fun applyRoster(p: JsonObject) {
        val arr = p["devices"] as? kotlinx.serialization.json.JsonArray ?: return
        arr.mapNotNull { it as? JsonObject }.firstOrNull {
            (it["device_id"] as? JsonPrimitive)?.content == descriptor.id
        }?.let { d ->
            val online = (d["online"] as? JsonPrimitive)?.content == "true"
            val holder = (d["holder"] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotEmpty() && it != "null" }
            _state.value = _state.value.copy(
                online = online,
                controller = holder,
                linkState = if (online) LinkState.ONLINE else LinkState.OFFLINE
            )
        }
    }

    private fun applyState(p: JsonObject) {
        fun obj(k: String) = p[k] as? JsonObject
        fun f(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.content?.toFloatOrNull()
        val pos = obj("pos")
        val vel = obj("vel")
        val att = obj("att")
        val vx = f(vel, "vx") ?: 0f
        val vy = f(vel, "vy") ?: 0f
        _state.value = _state.value.copy(
            online = true,
            position = Vec3(f(pos, "x") ?: 0f, f(pos, "y") ?: 0f, f(pos, "z") ?: 0f),
            yawDeg = Math.toDegrees((f(att, "yaw") ?: 0f).toDouble()).toFloat(),
            speed = kotlin.math.hypot(vx, vy),
            vz = f(vel, "vz") ?: 0f,
            mode = (p["mode"] as? JsonPrimitive)?.content ?: _state.value.mode,
            battery = ((p["battery_pct"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 100f) / 100f,
            quality = (p["quality"] as? JsonPrimitive)?.content ?: "",
            linkState = LinkState.ONLINE,
            updatedAt = System.currentTimeMillis()
        )
    }

    override fun stop() {}

    override fun send(cmd: DeviceCommand): CommandRecord {
        if (transport.state.value != WsTransport.ConnState.CONNECTED) {
            return makeRecord(cmd, CommandStatus.FAILED, "服务未连接")
        }
        val cmdId = UUID.randomUUID().toString().replace("-", "")
        val (action, params) = when (cmd) {
            is DeviceCommand.Motion -> "move" to buildJsonObject {
                put("vx", cmd.vx); put("vy", cmd.vy); put("vz", 0f); put("yaw_rate", cmd.wz)
            }
            is DeviceCommand.Stop, is DeviceCommand.EmergencyStop -> "stop" to buildJsonObject {
                put("vx", 0f); put("vy", 0f); put("vz", 0f); put("yaw_rate", 0f)
            }
            is DeviceCommand.Takeoff -> "takeoff" to buildJsonObject {}
            is DeviceCommand.Land -> "land" to buildJsonObject {}
            is DeviceCommand.Hover -> "hover" to buildJsonObject {}
            is DeviceCommand.ReturnHome -> "return_home" to buildJsonObject {}
            is DeviceCommand.Gait -> "gait" to buildJsonObject { put("posture", cmd.posture.name) }
            is DeviceCommand.WaypointMission -> "task" to buildJsonObject { put("count", cmd.waypoints.size) }
            is DeviceCommand.SimManage ->
                // EXT-004：仿真管理指令不得经服务路由至实机类型，仅本地虚拟设备支持
                return makeRecord(cmd, CommandStatus.FAILED, "仿真管理仅本地虚拟设备可用（EXT-004）")
        }
        val rec = makeRecord(cmd, CommandStatus.SENT)
        cmdSeqMap[cmdId] = rec.seq
        val ok = transport.send(
            MsgType.CMD,
            buildJsonObject {
                put("cmd_id", cmdId)
                put("action", action)
                put("params", params)
            },
            tgt = descriptor.id,
            ttlMs = Protocol.CMD_TTL_MS
        )
        if (!ok) updateRecord(rec.seq, CommandStatus.FAILED, "发送失败")
        return rec
    }

    /** 控制权申请/释放 → 服务端租约（SAFE-001 / CTL-002） */
    override fun setController(name: String?) {
        if (transport.state.value != WsTransport.ConnState.CONNECTED) return
        transport.send(
            if (name != null) MsgType.LEASE_REQ else MsgType.LEASE_REL,
            buildJsonObject { put("device", descriptor.id) },
            tgt = descriptor.id
        )
    }
}

/**
 * 预留接口适配器 —— EXT-001/002/003/004：
 * 真实无人机与机器狗本期仅预留接口（机器狗暂缓开发）。
 * 状态永远离线、控制禁用；send 返回带说明的失败记录，
 * 不得把测试数据作为实机结果（ACC-007），不得显示为真实在线（APP-004）。
 */
class ReservedAdapter(
    descriptor: DeviceDescriptor
) : BaseAdapter(descriptor) {

    override fun start() {
        _state.value = DeviceState(
            online = false,
            linkState = LinkState.OFFLINE,
            quality = "",
            alarms = listOf(descriptor.reservedNote ?: "预留接口，本期未接入")
        )
    }

    override fun stop() {}

    override fun send(cmd: DeviceCommand): CommandRecord =
        makeRecord(cmd, CommandStatus.FAILED, "预留接口未接入（EXT-004 禁用）")

    override fun setController(name: String?) {}
}
