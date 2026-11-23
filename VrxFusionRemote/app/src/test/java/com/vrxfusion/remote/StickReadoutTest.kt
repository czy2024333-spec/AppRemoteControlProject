package com.vrxfusion.remote

import com.vrxfusion.remote.rotorpy.StickSnapshot
import com.vrxfusion.remote.ui.DEFAULT_DEAD_ZONE
import com.vrxfusion.remote.ui.StickAxis
import com.vrxfusion.remote.ui.stickReadoutLines
import org.junit.Assert.assertEquals
import org.junit.Test

/** D2 验收：死区、方向词、百分比，以及方向词与实际发送轴符号的一致性。 */
class StickReadoutTest {

    private fun lines(x: Float, y: Float, xAxis: StickAxis, yAxis: StickAxis) =
        stickReadoutLines(x, y, xAxis, yAxis)

    @Test
    fun deadZoneShowsNeutralAndBoundaryIsActive() {
        assertEquals("转向 中位" to "升降 中位", lines(0f, 0f, StickAxis.YAW, StickAxis.VERTICAL))
        // 5% 处在死区内，两行都显示中位
        assertEquals("转向 中位" to "升降 中位", lines(.05f, -.05f, StickAxis.YAW, StickAxis.VERTICAL))
        // 恰好 0.08 不算中位，与 Joystick.emit()/VirtualJoystick 的 abs(v) < deadZone 一致
        assertEquals("转向 右 8%" to "升降 中位", lines(DEFAULT_DEAD_ZONE, 0f, StickAxis.YAW, StickAxis.VERTICAL))
    }

    @Test
    fun leftStickHorizontalMatchesYawSign() {
        val lx = -.5f
        assertEquals("转向 左 50%", lines(lx, 0f, StickAxis.YAW, StickAxis.VERTICAL).first)
        // yaw = -lx > 0 → 机头左转，文案方向一致
        assertEquals(0.5, StickSnapshot(lx = lx).axes()[3], 1e-6)

        assertEquals("转向 右 50%", lines(.5f, 0f, StickAxis.YAW, StickAxis.VERTICAL).first)
        assertEquals(-0.5, StickSnapshot(lx = .5f).axes()[3], 1e-6)
    }

    @Test
    fun leftStickVerticalMatchesVzSign() {
        assertEquals("升降 升 50%", lines(0f, .5f, StickAxis.YAW, StickAxis.VERTICAL).second)
        assertEquals(0.5, StickSnapshot(ly = .5f).axes()[2], 1e-6)

        assertEquals("升降 降 50%", lines(0f, -.5f, StickAxis.YAW, StickAxis.VERTICAL).second)
        assertEquals(-0.5, StickSnapshot(ly = -.5f).axes()[2], 1e-6)
    }

    @Test
    fun rightStickLabelsMatchVxAndVySign() {
        val (lateral, forward) = lines(.5f, 1f, StickAxis.LATERAL, StickAxis.FORWARD)
        assertEquals("横移 右 50%", lateral)
        assertEquals("前后 前 100%", forward)
        assertEquals(1.0, StickSnapshot(ry = 1f).axes()[0], 1e-6)   // vx = ry
        assertEquals(-0.5, StickSnapshot(rx = .5f).axes()[1], 1e-6)  // vy = -rx

        assertEquals("横移 左 50%", lines(-.5f, 0f, StickAxis.LATERAL, StickAxis.FORWARD).first)
        assertEquals("前后 后 50%", lines(0f, -.5f, StickAxis.LATERAL, StickAxis.FORWARD).second)
    }

    @Test
    fun diagonalStickShowsBothAxesIndependently() {
        val (yawLine, verticalLine) = lines(-.3f, .7f, StickAxis.YAW, StickAxis.VERTICAL)
        assertEquals("转向 左 30%", yawLine)
        assertEquals("升降 升 70%", verticalLine)
    }

    @Test
    fun invalidOrOutOfRangeValueIsSafe() {
        assertEquals("转向 中位", lines(Float.NaN, 0f, StickAxis.YAW, StickAxis.VERTICAL).first)
        assertEquals("转向 右 100%", lines(2f, 0f, StickAxis.YAW, StickAxis.VERTICAL).first)
        assertEquals("升降 降 100%", lines(0f, -2f, StickAxis.YAW, StickAxis.VERTICAL).second)
    }

    @Test
    fun customDeadZoneIsHonoured() {
        assertEquals("转向 中位", StickAxis.YAW.text(.2f, deadZone = .3f))
        assertEquals("转向 右 20%", StickAxis.YAW.text(.2f, deadZone = .1f))
    }
}
