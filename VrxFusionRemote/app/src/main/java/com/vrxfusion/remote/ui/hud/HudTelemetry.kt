package com.vrxfusion.remote.ui.hud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import com.vrxfusion.remote.rotorpy.BoundaryRecovery
import com.vrxfusion.remote.rotorpy.RotorState
import com.vrxfusion.remote.unified.UnifiedDeviceState

/** 仪表各自读取所需的状态，HUD 容器只持有引用。 */
@Stable
internal class HudTelemetry(
    val aircraft: State<RotorState?>,
    val trail: State<List<Pair<Double, Double>>>,
    val receiveHz: State<Double>,
    val members: State<List<UnifiedDeviceState>>,
    val qualityLabel: State<String?>
)

@Immutable
internal data class HudControlSignals(
    val mode: String?,
    val boundary: BoundaryRecovery?,
    val protectionReason: String?,
    val deviceId: String?,
    val simulated: Boolean
)

/** 控制层只看保护阈值和判定，恢复过程中的实测值仍属于遥测。 */
internal fun RotorState?.hudControlSignals() = HudControlSignals(
    mode = this?.mode,
    boundary = this?.boundary_recovery?.copy(
        actual_altitude_m = 0.0, actual_horizontal_radius_m = 0.0, actual_speed_m_s = 0.0
    ),
    protectionReason = this?.protection_reason,
    deviceId = this?.device_id?.takeIf { it.isNotBlank() },
    simulated = this?.data_source == "simulation_truth"
)

@Composable
internal fun rememberHudControlSignals(aircraft: State<RotorState?>): State<HudControlSignals> =
    remember(aircraft) { derivedStateOf(structuralEqualityPolicy()) { aircraft.value.hudControlSignals() } }
