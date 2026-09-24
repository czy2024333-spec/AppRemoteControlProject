package com.vrxfusion.remote.device

/**
 * 统一设备模型 —— 对应需求书 2.1/OBJ-002、7.1/MAP-002：
 * 真实设备与虚拟设备使用统一的身份、类型、能力与状态语义。
 */

/** 设备类型（首期：B2 机器狗、大疆无人机） */
enum class DeviceKind(val label: String) {
    QUADRUPED("四足机器人"),
    DRONE("无人机");
}

/** 虚实属性 —— APP-004：控制对象必须显著标识 */
enum class RealityType(val label: String) {
    REAL("真实设备"),
    VIRTUAL("虚拟设备"),
    BOUND("虚实绑定");
}

/**
 * 能力清单 —— PRI-004 / APP-006：
 * APP 根据能力动态显示控制功能，绝不向设备发送其不支持的指令。
 */
enum class Capability(val label: String) {
    TELEMETRY("状态遥测"),
    MOTION("连续运动控制"),          // B2-002 / DJI-003
    GAIT("姿态与运动模式"),          // B2-003
    TAKEOFF_LAND("起降控制"),        // DJI-003
    HOVER("悬停"),                   // DJI-003
    RTL("返航"),                     // DJI-003
    WAYPOINT_MISSION("航点任务"),    // DJI-004
    SIM_MANAGE("仿真管理");          // 仅虚拟设备（SAFE-005）
}

/** 设备静态描述（身份与能力 —— MAP-001/MAP-003） */
data class DeviceDescriptor(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val reality: RealityType,
    val model: String,
    /** 绑定的对端设备编号（RealityType.BOUND 时有效，MAP-001 唯一绑定） */
    val boundTo: String? = null,
    val capabilities: Set<Capability> = setOf(Capability.TELEMETRY)
)

enum class LinkState(val label: String) {
    OFFLINE("离线"), CONNECTING("连接中"), ONLINE("在线"), DEGRADED("链路降级");
}

data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)

/** 统一运行状态 —— APP-008：位置、姿态、速度、电量、链路、任务、告警 */
data class DeviceState(
    val online: Boolean = false,
    /** 当前控制权持有人（APP-005），null 表示无人持有 */
    val controller: String? = null,
    val battery: Float = 1f,
    val position: Vec3 = Vec3(),
    /** 航向角，单位：度 */
    val yawDeg: Float = 0f,
    /** 水平合速度，单位：m/s */
    val speed: Float = 0f,
    val mode: String = "IDLE",
    val linkState: LinkState = LinkState.OFFLINE,
    val alarms: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val batteryPercent: Int get() = (battery.coerceIn(0f, 1f) * 100).toInt()
}
