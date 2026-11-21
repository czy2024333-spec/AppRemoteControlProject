package com.vrxfusion.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.vrxfusion.remote.rotorpy.RotorState
import com.vrxfusion.remote.ui.hud.HudModel
import com.vrxfusion.remote.ui.hud.HudSnapshot
import com.vrxfusion.remote.ui.hud.deriveHud
import com.vrxfusion.remote.ui.hud.effectiveHudLevel
import com.vrxfusion.remote.ui.hud.hudLayout
import com.vrxfusion.remote.ui.HudDisplayLevel
import com.vrxfusion.remote.ui.hud.instruments.wrapBank
import com.vrxfusion.remote.ui.hud.instruments.readoutSurface
import com.vrxfusion.remote.ui.qualityText
import com.vrxfusion.remote.ui.theme.TextPrimary
import com.vrxfusion.remote.unified.OptionalMeasurement
import com.vrxfusion.remote.unified.UnifiedDeviceState
import org.junit.Assert.*
import org.junit.Test

class HudModelTest {
    private val valid = RotorState(velocity_m_s = listOf(3.0, 4.0, -2.0))

    @Test fun invalidFieldsAreIndependentAndCannotAppearAsZero() {
        val partial = valid.copy(
            position_m = listOf(1.0, 2.0),
            velocity_m_s = listOf(3.0, 4.0, Double.NaN),
            quaternion_xyzw = listOf(0.0, 0.0, 0.0, 0.0)
        )
        val hud = deriveHud(HudSnapshot(partial, true))
        assertNull(hud.position)
        assertNull(hud.attitude)
        assertNull(hud.verticalSpeed)
        assertEquals(5.0, hud.groundSpeed ?: Double.NaN, 0.0)
        val rest = deriveHud(HudSnapshot(valid.copy(velocity_m_s = listOf(0.0, 0.0, 0.0)), true))
        assertEquals(0.0, rest.groundSpeed ?: Double.NaN, 0.0)
        assertEquals(0.0, rest.verticalSpeed ?: Double.NaN, 0.0)
        val withoutGroundSpeed = deriveHud(HudSnapshot(valid.copy(velocity_m_s = listOf(Double.NaN, 4.0, -2.0)), true))
        assertNull(withoutGroundSpeed.groundSpeed)
        assertEquals(-2.0, withoutGroundSpeed.verticalSpeed ?: Double.NaN, 0.0)
        assertNotNull(withoutGroundSpeed.attitude)
        assertNotNull(withoutGroundSpeed.position)
    }

    @Test fun noInstrumentTreatsStaleOrOfflineFramesAsLive() {
        assertEquals(HudModel(invalidReason = "状态未更新"), deriveHud(HudSnapshot(valid, false)))
        assertEquals(HudModel(invalidReason = "设备离线"), deriveHud(HudSnapshot(valid.copy(online = false), true)))
        assertEquals(HudModel(invalidReason = "等待状态"), deriveHud(HudSnapshot(null, true)))
        assertEquals(HudModel(invalidReason = "速度无效"),
            deriveHud(HudSnapshot(valid.copy(velocity_m_s = emptyList()), false)))
        assertEquals(HudModel(invalidReason = "状态过期"), deriveHud(HudSnapshot(valid.copy(state_age_ms = 501.0), false)))
    }

    @Test fun qualityUsesUnknownForCollapsedDefaultsAndPreservesKnownSensorStatus() {
        val aircraft = UnifiedDeviceState(device_id = "sim-1")
        assertEquals("本地测量未知 · GNSS未知 · 电量未知", aircraft.qualityText())
        assertEquals("本地测量未知 · GNSS未接入 · 电量过期", aircraft.copy(
            gnss = OptionalMeasurement(source = "GNSS", validity = "NOT_CONNECTED"),
            battery = OptionalMeasurement(validity = "STALE")
        ).qualityText())
    }

    @Test fun narrowLayoutHidesMapBeforeReplacingTheAttitudeDial() {
        fun plan(width: Int) = hudLayout(width.dp, true, 1f, true, true, true, centered = false)
        assertEquals(200.dp, plan(784).centerWidth)
        assertEquals(128.dp, plan(624).centerWidth)
        assertEquals(0.dp, plan(576).mapWidth)
        assertFalse(plan(576).numericAttitude)
        assertTrue(plan(460).numericAttitude)
        assertTrue(plan(460).centerWidth >= 120.dp)
    }

    @Test fun manualHudLevelSurvivesPhaseChangesAndAutoFollowsControl() {
        assertEquals(HudDisplayLevel.L2, effectiveHudLevel(HudDisplayLevel.L2, false, false))
        assertEquals(HudDisplayLevel.L1, effectiveHudLevel(HudDisplayLevel.L1, true, false))
        assertEquals(HudDisplayLevel.L2, effectiveHudLevel(HudDisplayLevel.L1, true, true))
        assertEquals(HudDisplayLevel.L3, effectiveHudLevel(HudDisplayLevel.AUTO, false, false))
        assertEquals(HudDisplayLevel.L2, effectiveHudLevel(HudDisplayLevel.AUTO, true, false))
    }

    @Test fun bankSmoothingTakesTheShortPathAcross180Degrees() {
        assertEquals(2f, wrapBank(-179f - 179f), 0f)
        assertEquals(-2f, wrapBank(179f - (-179f)), 0f)
    }

    @Test fun numericReadoutRetainsContrastAtMinimumOpacityOverWhiteVideo() {
        val background = readoutSurface(.2f).compositeOver(Color.White)
        val ratio = (TextPrimary.luminance() + .05f) / (background.luminance() + .05f)
        assertTrue("亮视频上的数值对比度为 $ratio", ratio >= 4.5f)
    }
}
