package com.vrxfusion.remote.sim

import com.vrxfusion.remote.device.DeviceDescriptor
import com.vrxfusion.remote.device.DeviceKind
import com.vrxfusion.remote.device.DeviceState
import com.vrxfusion.remote.device.GaitPosture
import com.vrxfusion.remote.device.SimAction
import com.vrxfusion.remote.device.Vec3
import com.vrxfusion.remote.device.DeviceCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 内置虚拟设备仿真器 —— MODE-001（纯虚拟模式）：
 * 在无 Isaac Sim、无真实设备的环境下，以轻量运动学模型驱动虚拟设备，
 * 使 APP 可以独立演示、训练与调试（对应报价模块 9 的前置演示能力）。
 *
 * Isaac Sim 正式接入后，本仿真器被 IsaacSimAdapter 替代（ARC-005），
 * APP 核心逻辑不变 —— 这正是插件化适配架构（OBJ-004）的意义。
 */
class VirtualDeviceSimulator(
    private val scope: CoroutineScope,
    private val descriptor: DeviceDescriptor
) {
    private var tickJob: Job? = null
    private val lock = Any()

    // ---- 运动学状态 ----
    private var pos = Vec3()
    private var yaw = 0f
    private var vx = 0f
    private var vy = 0f
    private var wz = 0f
    private var battery = 0.92f
    private var mode = "IDLE"
    private var paused = false
    private var alarms = mutableListOf<String>()
    private var controller: String? = null

    val state = kotlinx.coroutines.flow.MutableStateFlow(
        DeviceState(online = true, mode = "IDLE")
    )

    fun start() {
        if (tickJob?.isActive == true) return
        mode = if (descriptor.kind == DeviceKind.DRONE) "STANDBY" else "STAND"
        tickJob = scope.launch {
            val dt = 1f / com.vrxfusion.remote.protocol.Protocol.STATE_RATE_HZ
            while (isActive) {
                if (!paused) step(dt)
                publish()
                delay((1000 / com.vrxfusion.remote.protocol.Protocol.STATE_RATE_HZ).toLong())
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        synchronized(lock) { controller = null }
    }

    /** 接收指令并更新内部目标（对应 B2-008 / DJI-008：虚拟模型接受统一指令） */
    fun onCommand(cmd: DeviceCommand) {
        synchronized(lock) {
            when (cmd) {
                is DeviceCommand.Motion -> {
                    vx = cmd.vx; vy = cmd.vy; wz = cmd.wz
                    mode = if (cmd.vx == 0f && cmd.vy == 0f && cmd.wz == 0f) {
                        if (descriptor.kind == DeviceKind.DRONE) "HOVER" else "STAND"
                    } else "MOVING"
                }
                is DeviceCommand.Stop -> {
                    vx = 0f; vy = 0f; wz = 0f
                    mode = if (descriptor.kind == DeviceKind.DRONE) "HOVER" else "STAND"
                }
                is DeviceCommand.Gait -> mode = if (cmd.posture == GaitPosture.STAND_UP) "STAND" else "LIE_DOWN"
                is DeviceCommand.Takeoff -> { mode = "TAKING_OFF"; pos = pos.copy(z = 1.5f) }
                is DeviceCommand.Land -> { mode = "LANDING"; pos = pos.copy(z = 0f) }
                is DeviceCommand.Hover -> { vx = 0f; vy = 0f; wz = 0f; mode = "HOVER" }
                is DeviceCommand.ReturnHome -> {
                    vx = 0f; vy = 0f; wz = 0f; mode = "RTL"
                    pos = Vec3(); yaw = 0f
                }
                is DeviceCommand.WaypointMission -> mode = "MISSION(${cmd.waypoints.size} wp)"
                is DeviceCommand.SimManage -> when (cmd.action) {
                    SimAction.RESET -> { pos = Vec3(); yaw = 0f; vx = 0f; vy = 0f; wz = 0f; mode = "STAND"; alarms.clear() }
                    SimAction.PAUSE -> paused = true
                    SimAction.RESUME -> paused = false
                }
                is DeviceCommand.EmergencyStop -> {
                    vx = 0f; vy = 0f; wz = 0f; mode = "EMERGENCY_STOP"
                    if (descriptor.kind == DeviceKind.DRONE) pos = pos.copy(z = 0f)
                    alarms.add("已执行紧急停止")
                }
            }
        }
    }

    fun setController(name: String?) {
        synchronized(lock) { controller = name }
    }

    /** 单步积分（ENU 局部坐标演示；正式坐标系转换见 MAP-004/005） */
    private fun step(dt: Float) {
        synchronized(lock) {
            // 机体系速度 -> ENU（演示用简化模型：yaw 旋转）
            val yawRad = Math.toRadians(yaw.toDouble())
            val ex = (vx * kotlin.math.cos(yawRad) - vy * kotlin.math.sin(yawRad)).toFloat()
            val ey = (vx * kotlin.math.sin(yawRad) + vy * kotlin.math.cos(yawRad)).toFloat()
            pos = Vec3(pos.x + ex * dt, pos.y + ey * dt, pos.z)
            yaw = ((yaw + Math.toDegrees(wz.toDouble()) * dt + 360f) % 360f).toFloat()
            // 电量缓慢消耗，运动时加快；低于 20% 告警（SAFE 告警展示）
            val drain = if (mode == "MOVING" || mode.startsWith("MISSION")) 0.0004f else 0.0001f
            battery = (battery - drain).coerceAtLeast(0f)
            if (battery < 0.2f && alarms.none { it.contains("电量") }) alarms.add("电量低")
            if (battery > 0.2f) alarms.removeAll { it.contains("电量") }
        }
    }

    private fun publish() {
        val speed: Float
        val p: Vec3; val y: Float; val b: Float; val m: String; val a: List<String>; val c: String?
        synchronized(lock) {
            speed = kotlin.math.hypot(vx, vy); p = pos; y = yaw; b = battery; m = mode; a = alarms.toList(); c = controller
        }
        state.value = DeviceState(
            online = true,
            controller = c,
            battery = b,
            position = p,
            yawDeg = y,
            speed = speed,
            mode = m,
            linkState = com.vrxfusion.remote.device.LinkState.ONLINE,
            alarms = a,
            updatedAt = System.currentTimeMillis()
        )
    }
}
