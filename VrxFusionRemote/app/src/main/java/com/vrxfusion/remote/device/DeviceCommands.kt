package com.vrxfusion.remote.device

/**
 * 统一控制指令语义 —— OBJ-002 / MAP-002：
 * 同一套指令在真实设备与虚拟设备之间复用（B2-008 / DJI-010）。
 * 每条指令携带能力标记，发送前由 ControlSession 做能力校验（PRI-004）。
 */
sealed class DeviceCommand(val requires: Capability) {
    /** 连续运动指令：vx 前(+)后(-)、vy 左(-)右(+)、wz 原地转向角速度 rad/s */
    data class Motion(val vx: Float, val vy: Float, val wz: Float) : DeviceCommand(Capability.MOTION)

    /** 停止连续运动 / 悬停（对机器狗为停止，对无人机为悬停） */
    object Stop : DeviceCommand(Capability.MOTION)

    /** B2 姿态：站立 / 趴下 —— B2-003 */
    data class Gait(val posture: GaitPosture) : DeviceCommand(Capability.GAIT)

    /** 无人机起降 —— DJI-003 */
    object Takeoff : DeviceCommand(Capability.TAKEOFF_LAND)
    object Land : DeviceCommand(Capability.TAKEOFF_LAND)
    object Hover : DeviceCommand(Capability.HOVER)

    /** 返航 —— DJI-003 */
    object ReturnHome : DeviceCommand(Capability.RTL)

    /** 航点任务 —— DJI-004 */
    data class WaypointMission(val waypoints: List<Vec3>) : DeviceCommand(Capability.WAYPOINT_MISSION)

    /** 仿真管理（仅虚拟设备，SAFE-005：绝不下发至真实设备） */
    data class SimManage(val action: SimAction) : DeviceCommand(Capability.SIM_MANAGE)

    /** 紧急停止 —— SAFE-002：安全指令优先级最高 */
    object EmergencyStop : DeviceCommand(Capability.MOTION)
}

enum class GaitPosture { STAND_UP, LIE_DOWN }

enum class SimAction { RESET, PAUSE, RESUME }

/** 指令执行状态反馈 —— APP-010 */
enum class CommandStatus { SENT, RECEIVED, EXECUTING, SUCCESS, FAILED, CANCELLED, TIMEOUT }

/** 一条指令的执行记录（用于界面状态反馈与日志审计 SAFE-006） */
data class CommandRecord(
    val seq: Long,
    val name: String,
    var status: CommandStatus,
    val sentAt: Long = System.currentTimeMillis(),
    val detail: String? = null
)
