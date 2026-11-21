package com.vrxfusion.remote.ui.hud

import com.vrxfusion.remote.rotorpy.AttitudeAngles
import com.vrxfusion.remote.rotorpy.AttitudeMath
import com.vrxfusion.remote.rotorpy.RotorState
import com.vrxfusion.remote.unified.Vec3Value
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class HudSnapshot(val aircraft: RotorState?, val fresh: Boolean)

/** 空值表示该仪表数据无效。速度和姿态各自校验，避免用缺项补零。 */
internal data class HudModel(
    val position: Vec3Value? = null,
    val groundSpeed: Double? = null,
    val verticalSpeed: Double? = null,
    val attitude: AttitudeAngles? = null,
    val invalidReason: String? = null
) {
    val headingDeg: Int?
        get() = attitude?.yawDeg?.let { Math.floorMod(it.roundToInt(), 360) }
}

internal fun deriveHud(snapshot: HudSnapshot): HudModel {
    val aircraft = snapshot.aircraft ?: return HudModel(invalidReason = "等待状态")
    val xyz = aircraft.position_m
    val position = if (xyz.size == 3 && xyz.all { it.isFinite() && abs(it) <= 1e6 }) Vec3Value(xyz[0], xyz[1], xyz[2]) else null
    val velocity = aircraft.velocity_m_s
    val x = velocity.getOrNull(0)?.takeIf { it.isFinite() }
    val y = velocity.getOrNull(1)?.takeIf { it.isFinite() }
    val groundSpeed = if (x != null && y != null) hypot(x, y).takeIf { it.isFinite() } else null
    val verticalSpeed = velocity.getOrNull(2)?.takeIf { it.isFinite() }
    val attitude = AttitudeMath.fromQuaternion(aircraft.quaternion_xyzw)
    val frameIssue = when {
        !aircraft.online -> "设备离线"
        aircraft.state_age_ms.isFinite() && aircraft.state_age_ms > 500 -> "状态过期"
        else -> null
    }
    val fieldIssues = buildList {
        if (position == null) add("位置无效")
        if (groundSpeed == null || verticalSpeed == null) add("速度无效")
        if (attitude == null) add("姿态无效")
    }.joinToString("、").ifEmpty { null }
    // 失效帧保留字段诊断信息，显示层仍按现有整帧门槛收起数值。
    if (!snapshot.fresh || frameIssue != null) {
        return HudModel(invalidReason = frameIssue ?: fieldIssues ?: "状态未更新")
    }
    return HudModel(position, groundSpeed, verticalSpeed, attitude, fieldIssues)
}
