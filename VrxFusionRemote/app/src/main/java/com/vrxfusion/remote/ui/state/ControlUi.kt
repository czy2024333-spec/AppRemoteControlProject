package com.vrxfusion.remote.ui.state

import androidx.compose.runtime.Immutable
import com.vrxfusion.remote.rotorpy.BoundaryRecovery
import com.vrxfusion.remote.rotorpy.LinkPhase

enum class InputBlockReason {
    NOT_CONNECTED, STALE, READ_ONLY, BUSY, PROTECTED, SAFETY_BUSY, NOT_READY, SETTINGS_OPEN, ROUTE_MOVING
}

/** 只采集权限、握手和保护字段，不携带高频位置、姿态或轨迹。 */
@Immutable
data class ControlSnapshot(
    val connected: Boolean,
    val running: Boolean,
    val stale: Boolean,
    val writable: Boolean,
    val phase: LinkPhase,
    val busy: Boolean,
    val safetyBusy: Boolean,
    val holding: Boolean,
    val mode: String?,
    val neutral: Boolean,
    val error: String?,
    val sheetBlocking: Boolean,
    val routeMoving: Boolean,
    val safetyAllowed: Boolean,
    val boundaryRecovery: BoundaryRecovery? = null,
    val protectionReason: String? = null,
    val stopAvailable: Boolean = true,
    val protectAvailable: Boolean = true,
    val resumeAvailable: Boolean = true
)

@Immutable
data class ControlUi(
    val notice: FlightNotice,
    val inputEnabled: Boolean,
    val blockReason: InputBlockReason?,
    val stopEnabled: Boolean,
    val protectEnabled: Boolean,
    val connecting: Boolean = false,
    val secondaryNotices: List<FlightNotice> = emptyList(),
    val hasControl: Boolean = notice.stage == ControlStage.READY || notice.stage == ControlStage.FLYING ||
        (notice.stage == ControlStage.REJECTED && notice.action == FlightAction.NONE),
    val moving: Boolean = notice.stage == ControlStage.FLYING && inputEnabled
) {
    val stage: ControlStage get() = notice.stage
    val safetyEnabled: Boolean get() = stopEnabled || protectEnabled
}

/** 拦截原因只用于解释。停止和保护分别核对设备能力，不依赖摇杆放行。 */
fun deriveControlUi(snapshot: ControlSnapshot): ControlUi {
    val inputEnabled = snapshot.connected && snapshot.writable && !snapshot.stale && !snapshot.busy &&
        snapshot.mode != "PROTECTED" && !snapshot.safetyBusy && snapshot.phase == LinkPhase.READY &&
        !snapshot.sheetBlocking && !snapshot.routeMoving
    val blockReason = if (inputEnabled) null else when {
        snapshot.stale -> InputBlockReason.STALE
        !snapshot.connected -> InputBlockReason.NOT_CONNECTED
        snapshot.mode == "PROTECTED" -> InputBlockReason.PROTECTED
        !snapshot.writable -> InputBlockReason.READ_ONLY
        snapshot.busy -> InputBlockReason.BUSY
        snapshot.safetyBusy -> InputBlockReason.SAFETY_BUSY
        snapshot.phase != LinkPhase.READY -> InputBlockReason.NOT_READY
        snapshot.sheetBlocking -> InputBlockReason.SETTINGS_OPEN
        snapshot.routeMoving -> InputBlockReason.ROUTE_MOVING
        else -> null
    }
    val notice = deriveFlightNotice(
        mode = snapshot.mode,
        boundary = snapshot.boundaryRecovery,
        protectionReason = snapshot.protectionReason,
        connected = snapshot.connected,
        running = snapshot.running,
        stale = snapshot.stale,
        phase = snapshot.phase,
        busy = snapshot.busy,
        holding = snapshot.holding,
        neutral = snapshot.neutral,
        error = snapshot.error,
        writable = snapshot.writable,
        resumeAvailable = snapshot.resumeAvailable
    )
    val safetyAllowed = snapshot.running && snapshot.safetyAllowed
    return ControlUi(
        notice = notice,
        inputEnabled = inputEnabled,
        blockReason = blockReason,
        stopEnabled = safetyAllowed && snapshot.stopAvailable,
        protectEnabled = safetyAllowed && snapshot.protectAvailable,
        connecting = snapshot.running && !snapshot.connected,
        secondaryNotices = if (snapshot.connected && !snapshot.writable && notice.tone == NoticeTone.DANGER) {
            listOf(flightAccessNotice(snapshot.error))
        } else {
            emptyList()
        },
        hasControl = snapshot.connected && !snapshot.stale && snapshot.writable &&
            snapshot.phase == LinkPhase.READY && snapshot.mode != "PROTECTED",
        moving = inputEnabled && snapshot.holding
    )
}
