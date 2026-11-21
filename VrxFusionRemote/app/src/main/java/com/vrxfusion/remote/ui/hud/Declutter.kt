package com.vrxfusion.remote.ui.hud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vrxfusion.remote.ui.HudDisplayLevel
import com.vrxfusion.remote.ui.HudPreferences
import com.vrxfusion.remote.ui.state.ControlStage
import com.vrxfusion.remote.ui.state.ControlUi
import com.vrxfusion.remote.ui.state.NoticeTone
import kotlinx.coroutines.delay

internal fun effectiveHudLevel(preferred: HudDisplayLevel, hasControl: Boolean, raiseLevel: Boolean): HudDisplayLevel {
    val selected = if (preferred == HudDisplayLevel.AUTO) {
        if (hasControl) HudDisplayLevel.L2 else HudDisplayLevel.L3
    } else preferred
    return if (raiseLevel && selected == HudDisplayLevel.L1) HudDisplayLevel.L2 else selected
}

internal data class HudDisplay(val level: HudDisplayLevel, val auxiliaryDimmed: Boolean) {
    val showFlightMetrics: Boolean get() = level != HudDisplayLevel.L1
    val showDiagnostics: Boolean get() = level == HudDisplayLevel.L3
}

/** 红警告持续抬档，橙色注意只在首次出现时短时抬档。 */
@Composable
internal fun rememberHudDisplay(preferences: HudPreferences, control: ControlUi): HudDisplay {
    val notices = remember(control.notice, control.secondaryNotices) { control.activeHudNotices() }
    val hasWarning = notices.any { it.tone == NoticeTone.DANGER }
    val attentionKeys = notices.filter { it.tone == NoticeTone.WARNING }.map { it.hudAlertKey() }.toSet()
    var previousAttention by remember { mutableStateOf(emptySet<ControlStage>()) }
    var attentionVersion by remember { mutableStateOf(0) }
    var attentionBoost by remember { mutableStateOf(false) }
    LaunchedEffect(attentionKeys) {
        if (attentionKeys.any { it !in previousAttention }) attentionVersion++
        previousAttention = attentionKeys
    }
    LaunchedEffect(attentionVersion) {
        if (attentionVersion > 0) {
            attentionBoost = true
            delay(1000)
            attentionBoost = false
        }
    }
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(preferences.dimDuringFlight, control.moving) {
        if (!preferences.dimDuringFlight) {
            dimmed = false
        } else if (control.moving) {
            dimmed = true
        } else if (dimmed) {
            delay(3000)
            dimmed = false
        }
    }
    val attentionJustAppeared = attentionKeys.any { it !in previousAttention }
    return HudDisplay(
        effectiveHudLevel(preferences.level, control.hasControl, hasWarning || attentionBoost || attentionJustAppeared),
        preferences.dimDuringFlight && (control.moving || dimmed)
    )
}
