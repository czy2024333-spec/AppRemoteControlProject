package com.vrxfusion.remote.ui.hud.instruments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.rotorpy.StateRate
import com.vrxfusion.remote.ui.HudPreferences
import com.vrxfusion.remote.ui.hud.HudDisplay
import com.vrxfusion.remote.ui.hud.HudLayout
import com.vrxfusion.remote.ui.hud.HudModel
import com.vrxfusion.remote.ui.hud.HudTelemetry
import com.vrxfusion.remote.ui.design.ProtectionText
import com.vrxfusion.remote.ui.theme.HudSurface
import com.vrxfusion.remote.ui.theme.TextPrimary
import com.vrxfusion.remote.ui.theme.TextSecondary
import com.vrxfusion.remote.ui.theme.WarnAmber
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HudReadouts(
    telemetry: HudTelemetry, model: State<HudModel>, fresh: Boolean, display: HudPreferences, wide: Boolean, videoMessage: String?,
    renderedRate: StateRate?, countFrames: Boolean, appearance: HudDisplay, layout: HudLayout, centered: Boolean
) {
    val state = telemetry.aircraft.value
    val qualityLabel = telemetry.qualityLabel.value
    val live = fresh && state?.online == true
    // 仍按提交到 Compose 的不同步号计数，接收频率另计。
    if (live && countFrames && renderedRate != null) {
        SideEffect { renderedRate.record("${state.boot_id}:${state.sequence}") }
    }
    val instruments = model.value
    val invalidReason = instruments.invalidReason
    val compactMetrics = !centered && appearance.showFlightMetrics && layout.bandWidth == 0.dp
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(26.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val fill = readoutSurface(1f)
            if (!centered) HudHeading(instruments.headingDeg, layout.full)
            if (layout.numericAttitude) {
                val attitude = instruments.attitude
                if (attitude == null) InstrumentFlag("姿态 N/A", "attitude-invalid-flag")
                else HudNumber("横滚 %+.1f°  俯仰 %+.1f°".format(Locale.ROOT, attitude.rollDeg, attitude.pitchDeg),
                    fill, Modifier.testTag("numeric-attitude"))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (appearance.showDiagnostics && display.showPosition) Box(Modifier.testTag("position-readout")) {
                    val position = instruments.position
                    if (position == null) InstrumentFlag("位置 N/A", "position-invalid-flag")
                    else HudNumber("X %.2f  Y %.2f m".format(Locale.ROOT, position.x, position.y),
                        readoutSurface(display.positionOpacity),
                        Modifier.alpha(if (appearance.auxiliaryDimmed) minOf(.4f, display.positionOpacity) else 1f))
                }
            }
            if (compactMetrics && !layout.numericAttitude) HudCompactMetrics(instruments)
            if (appearance.showDiagnostics && live) {
                HudNumber(hudReceiveRateLabel(telemetry.receiveHz.value), fill,
                    Modifier.alpha(if (appearance.auxiliaryDimmed) .4f else 1f).testTag("receive-rate-readout"))
            }
        }
        if (compactMetrics && layout.numericAttitude) {
            // 极窄时给高度和速度独立一行，保留完整的数值、符号与单位。
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HudCompactMetrics(instruments)
            }
        }
    }
    // 运行模式和来源与模拟/缺失标记始终可见，显示配置不能隐藏数据质量提示。
    Row(Modifier.fillMaxWidth().heightIn(min=24.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.SpaceBetween) {
        val qualityText = if (!live) "数据质量未知" else
            "${state.mode} · 通路 ${state.selected_source ?: "未选择"} · ${qualityLabel ?: "数据质量未知"}"
        Text(listOfNotNull(invalidReason, qualityText).joinToString(" · "),
            color=if (invalidReason != null) ProtectionText else if (live) TextSecondary else WarnAmber,
            fontSize=10.sp,lineHeight=14.sp,
            maxLines=1,overflow=TextOverflow.Ellipsis,
            modifier=Modifier.weight(1f,fill=false).background(HudSurface,RoundedCornerShape(8.dp))
                .padding(horizontal=8.dp,vertical=4.dp).testTag("data-quality"))
        if (videoMessage!=null) {
            Text(videoMessage,color=TextSecondary,fontSize=10.sp,lineHeight=14.sp,maxLines=1,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(start=8.dp).widthIn(max=if (wide) 240.dp else 120.dp)
                    .background(HudSurface,RoundedCornerShape(8.dp))
                    .padding(horizontal=8.dp,vertical=4.dp).testTag("video-viewport-status"))
        }
    }
}

@Composable
private fun HudCompactMetrics(instruments: HudModel) {
    HudAltitudeNumber(instruments.position?.z)
    HudGroundSpeedNumber(instruments.groundSpeed)
    HudVerticalSpeedNumber(instruments.verticalSpeed)
}

internal fun hudReceiveRateLabel(rate: Double): String =
    if (rate.isFinite() && rate > 0.0) "%.1f Hz".format(Locale.ROOT, rate) else "采样中"

@Composable
internal fun HudNumber(label: String, fill: Color, modifier: Modifier = Modifier) {
    Text(label, color = TextPrimary, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.background(fill, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
}
