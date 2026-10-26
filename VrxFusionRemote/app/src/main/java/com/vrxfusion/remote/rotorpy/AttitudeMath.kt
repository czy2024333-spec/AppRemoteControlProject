package com.vrxfusion.remote.rotorpy

import kotlin.math.*

data class AttitudeAngles(val rollDeg: Double, val pitchDeg: Double, val yawDeg: Double)

/** 仅从姿态四元数计算仪表角度，位置、速度及地图缩放不能参与此计算。 */
object AttitudeMath {
    fun fromQuaternion(q: List<Double>): AttitudeAngles? {
        if (q.size != 4 || q.any { !it.isFinite() }) return null
        val norm = sqrt(q.sumOf { it * it })
        if (!norm.isFinite() || norm < 1e-9) return null
        val (x, y, z, w) = q.map { it / norm }
        return AttitudeAngles(
            atan2(2 * (w*x+y*z), 1-2*(x*x+y*y))*180/PI,
            asin((2*(w*y-z*x)).coerceIn(-1.0,1.0))*180/PI,
            atan2(2*(w*z+x*y), 1-2*(y*y+z*z))*180/PI)
    }

    /** 零俯仰的地平线必须位于画布中心；原实现漏加height/2，导致水平时整块显示地面。 */
    fun horizonY(height: Float, pitchDeg: Double): Float = height/2 + (pitchDeg.coerceIn(-90.0,90.0)*height/110).toFloat()

    // 仅抑制显示抖动，不修改后端姿态、运动指令或保护条件。
    fun stableAngle(previous: Float, next: Double): Float =
        if (abs(next-previous) < .15) previous else next.toFloat()
}
