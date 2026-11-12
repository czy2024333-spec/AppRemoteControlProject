package com.vrxfusion.remote

import com.vrxfusion.remote.rotorpy.BoundaryRecovery
import com.vrxfusion.remote.rotorpy.LinkPhase
import com.vrxfusion.remote.ui.state.ControlSnapshot
import com.vrxfusion.remote.ui.state.ControlStage
import com.vrxfusion.remote.ui.state.FlightAction
import com.vrxfusion.remote.ui.state.InputBlockReason
import com.vrxfusion.remote.ui.state.deriveControlUi
import com.vrxfusion.remote.ui.state.headerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlUiTest {
    private val ready = ControlSnapshot(
        connected = true, running = true, stale = false, writable = true,
        phase = LinkPhase.READY, busy = false, safetyBusy = false, holding = false,
        mode = "HOLD", neutral = true, error = null,
        sheetBlocking = false, routeMoving = false, safetyAllowed = true
    )

    @Test fun allStagesFollowExistingNoticePriority() {
        val observing = ready.copy(phase = LinkPhase.HANDSHAKE)
        val protected = ready.copy(mode = "PROTECTED")
        val cases = mapOf(
            ControlStage.STALE to protected.copy(stale = true, connected = false, writable = false),
            ControlStage.OFFLINE to protected.copy(connected = false),
            ControlStage.RECOVERING to protected.copy(
                boundaryRecovery = BoundaryRecovery(required = true, can_resume = false), neutral = false
            ),
            ControlStage.RELEASE to protected.copy(neutral = false),
            ControlStage.PROTECTED to protected.copy(writable = false, error = "设备能力刷新失败"),
            ControlStage.CAPABILITY_UNAVAILABLE to ready.copy(writable = false, error = "设备能力刷新失败"),
            ControlStage.READ_ONLY to observing.copy(writable = false, busy = true),
            ControlStage.REQUESTING to observing.copy(busy = true, neutral = false),
            ControlStage.NEUTRAL to observing.copy(neutral = false, error = "申请失败"),
            ControlStage.REJECTED to observing.copy(error = "申请失败"),
            ControlStage.OBSERVING to observing,
            ControlStage.READY to ready,
            ControlStage.FLYING to ready.copy(holding = true)
        )
        assertEquals(ControlStage.entries.toSet(), cases.keys)
        for ((stage, snapshot) in cases) {
            val ui = deriveControlUi(snapshot)
            assertEquals(stage.name, stage, ui.stage)
            assertEquals(stage.name, stage == ControlStage.READY || stage == ControlStage.FLYING, ui.inputEnabled)
        }
        val recovery = deriveControlUi(cases.getValue(ControlStage.RECOVERING)).notice
        assertEquals("等待恢复", recovery.actionLabel)
        assertFalse(recovery.actionEnabled)
        val readOnlyProtection = deriveControlUi(cases.getValue(ControlStage.PROTECTED)).notice
        assertEquals("只读观察", readOnlyProtection.actionLabel)
        assertFalse(readOnlyProtection.actionEnabled)
    }

    @Test fun blockReasonsFollowActionablePriorityAndDoNotDisableSafety() {
        val cases = mapOf(
            InputBlockReason.NOT_CONNECTED to ready.copy(connected = false),
            InputBlockReason.STALE to ready.copy(stale = true, connected = false),
            InputBlockReason.READ_ONLY to ready.copy(writable = false),
            InputBlockReason.BUSY to ready.copy(busy = true),
            InputBlockReason.PROTECTED to ready.copy(mode = "PROTECTED", writable = false),
            InputBlockReason.SAFETY_BUSY to ready.copy(safetyBusy = true),
            InputBlockReason.NOT_READY to ready.copy(phase = LinkPhase.HANDSHAKE),
            InputBlockReason.SETTINGS_OPEN to ready.copy(sheetBlocking = true),
            InputBlockReason.ROUTE_MOVING to ready.copy(routeMoving = true)
        )
        assertEquals(InputBlockReason.entries.toSet(), cases.keys)
        for ((reason, snapshot) in cases) {
            val ui = deriveControlUi(snapshot)
            assertEquals(reason.name, reason, ui.blockReason)
            assertFalse(ui.inputEnabled)
            assertTrue(ui.safetyEnabled)
        }
        assertFalse(deriveControlUi(ready.copy(running = false)).safetyEnabled)
        assertFalse(deriveControlUi(ready.copy(safetyAllowed = false)).safetyEnabled)
    }

    @Test fun failedCommandWhileReadyIsSeparateFromPermissionToMove() {
        val ui = deriveControlUi(ready.copy(error = "单次指令失败"))
        assertEquals(ControlStage.REJECTED, ui.stage)
        assertEquals(FlightAction.NONE, ui.notice.action)
        assertTrue(ui.inputEnabled)
        assertNull(ui.blockReason)
    }

    @Test fun unavailableSafetyCapabilitiesDisableOnlyTheirOwnActions() {
        val controls = deriveControlUi(ready.copy(sheetBlocking = true, protectAvailable = false))
        assertTrue(controls.stopEnabled)
        assertFalse(controls.protectEnabled)
        assertFalse(controls.inputEnabled)
        assertEquals("设置中", controls.headerStatus().label)
        val protection = deriveControlUi(ready.copy(mode = "PROTECTED", resumeAvailable = false))
        assertFalse(protection.notice.actionEnabled)
        assertEquals("无法解除", protection.notice.actionLabel)
    }

    @Test fun allFlagCombinationsMatchTheCurrentScreenGates() {
        for (mask in 0 until (1 shl 11)) {
            fun flag(bit: Int) = mask and (1 shl bit) != 0
            for (phase in LinkPhase.entries) {
                for (mode in listOf(null, "HOLD", "PROTECTED")) {
                    val snapshot = ControlSnapshot(
                        connected = flag(0), running = flag(1), stale = flag(2), writable = flag(3),
                        phase = phase, busy = flag(4), safetyBusy = flag(5), holding = flag(6),
                        mode = mode, neutral = flag(7), error = null,
                        sheetBlocking = flag(8), routeMoving = flag(9), safetyAllowed = flag(10)
                    )
                    // 保留原操纵页的布尔式作为影子基准。
                    val legacy = snapshot.connected && snapshot.writable && !snapshot.stale && !snapshot.busy &&
                        snapshot.mode != "PROTECTED" && !snapshot.safetyBusy && phase == LinkPhase.READY &&
                        !snapshot.sheetBlocking && !snapshot.routeMoving
                    val ui = deriveControlUi(snapshot)
                    assertEquals(snapshot.toString(), legacy, ui.inputEnabled)
                    assertEquals(snapshot.toString(), legacy, ui.blockReason == null)
                    assertEquals(snapshot.toString(), snapshot.running && snapshot.safetyAllowed, ui.safetyEnabled)
                }
            }
        }
    }
}
