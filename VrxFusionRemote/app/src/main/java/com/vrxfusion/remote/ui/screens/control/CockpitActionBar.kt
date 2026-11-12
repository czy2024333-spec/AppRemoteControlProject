package com.vrxfusion.remote.ui.screens.control

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.LocalAppMotionEnabled
import com.vrxfusion.remote.ui.design.ControlStageStrip
import com.vrxfusion.remote.ui.design.ProtectionText
import com.vrxfusion.remote.ui.design.SafetyDock
import com.vrxfusion.remote.ui.motionPress
import com.vrxfusion.remote.ui.state.ControlStep
import com.vrxfusion.remote.ui.state.ControlUi
import com.vrxfusion.remote.ui.state.NoticeTone
import com.vrxfusion.remote.ui.state.headerStatus
import com.vrxfusion.remote.ui.theme.*

@Composable
internal fun CockpitActionBar(
    control: ControlUi,
    simulated: Boolean,
    deviceLabel: String?,
    wide: Boolean,
    narrowHeader: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    onProtect: () -> Unit
) {
    val notice = control.notice
    val status = control.headerStatus()
    val animateStatus = LocalAppMotionEnabled.current && status.tone != NoticeTone.DANGER
    val pageTitle = if (narrowHeader) "飞行" else "飞行操控"
    val sourceLabel = if (simulated) "仿真" else "来源未确认"

    @Composable fun buttons(modifier: Modifier) {
        val primarySource = remember { MutableInteractionSource() }
        SafetyDock(control.stopEnabled, control.protectEnabled, onStop, onProtect,
            modifier.testTag("critical-actions"), compact = true,
            pressEffect = { source -> Modifier.motionPress(source) }) {
            Button(
                onClick = onPrimary, enabled = notice.actionEnabled, interactionSource = primarySource,
                modifier = Modifier.weight(1f).height(48.dp).motionPress(primarySource).testTag("flight-primary"),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ControlFill, contentColor = ControlText,
                    disabledContainerColor = Color.Transparent, disabledContentColor = TextSecondary
                ),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(notice.actionLabel, maxLines = 1, fontSize = 12.sp)
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().height(48.dp).background(
            Brush.horizontalGradient(listOf(HudSurface, HudSurface.copy(alpha = .72f))), RoundedCornerShape(24.dp)
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("flight-back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回首页", tint = TextPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "$pageTitle · $sourceLabel",
                color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 四步点位和新文案同帧更新，仅调整入场亮度，不延迟按钮或摇杆状态。
                key(status, animateStatus) {
                    val effect = if (animateStatus) {
                        val opacity = remember { Animatable(.78f) }
                        LaunchedEffect(Unit) { opacity.animateTo(1f, tween(160)) }
                        Modifier.graphicsLayer { alpha = opacity.value }
                    } else Modifier
                    ControlStageStrip(
                        status.step?.ordinal, ControlStep.entries.size, status.label, stageColor(status.tone),
                        effect.testTag("control-stage-strip")
                    )
                }
                if (deviceLabel != null) Text(
                    deviceLabel, modifier = Modifier.weight(1f, fill = false), color = TextSecondary,
                    fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (wide) {
            buttons(Modifier.width(if (narrowHeader) 224.dp else 274.dp))
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onSettings, modifier = Modifier.testTag("flight-settings")) {
            Icon(Icons.Default.Settings, contentDescription = "飞行设置", tint = TextPrimary)
        }
    }
    if (!wide) buttons(Modifier.fillMaxWidth())
}

private fun stageColor(tone: NoticeTone) = when (tone) {
    NoticeTone.NORMAL -> AccentBlue
    NoticeTone.GOOD -> VirtualGreen
    NoticeTone.WARNING -> WarnAmber
    NoticeTone.DANGER -> ProtectionText
}
