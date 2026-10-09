package com.vrxfusion.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.device.CommandStatus
import com.vrxfusion.remote.device.DeviceState
import com.vrxfusion.remote.device.LinkState
import com.vrxfusion.remote.device.RealityType
import com.vrxfusion.remote.ui.theme.BoundPurple
import com.vrxfusion.remote.ui.theme.OfflineDot
import com.vrxfusion.remote.ui.theme.OnlineDot
import com.vrxfusion.remote.ui.theme.RealRed
import com.vrxfusion.remote.ui.theme.SurfaceCard
import com.vrxfusion.remote.ui.theme.TextSecondary
import com.vrxfusion.remote.ui.theme.VirtualGreen
import com.vrxfusion.remote.ui.theme.WarnAmber

/** 虚实属性徽标 —— APP-004：显著标识控制对象 */
@Composable
fun RealityBadge(reality: RealityType, modifier: Modifier = Modifier) {
    val (text, color) = when (reality) {
        RealityType.REAL -> "真实" to RealRed
        RealityType.VIRTUAL -> "虚拟" to VirtualGreen
        RealityType.BOUND -> "虚实绑定" to BoundPurple
    }
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** 遥测面板 —— APP-008：位置/姿态/速度/电量/模式/链路 */
@Composable
fun TelemetryPanel(state: DeviceState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(SurfaceCard, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TelemetryRow("模式", state.mode, highlight = state.mode == "EMERGENCY_STOP")
        TelemetryRow("位置", "X %.1f  Y %.1f  Z %.1f m".format(state.position.x, state.position.y, state.position.z))
        TelemetryRow("航向", "${state.yawDeg.toInt()}°")
        TelemetryRow("速度", "%.2f m/s（垂 %.2f）".format(state.speed, state.vz))
        TelemetryRow("电量", "${state.batteryPercent}%", highlight = state.batteryPercent < 20)
        TelemetryRow("链路", state.linkState.label, highlight = state.linkState != LinkState.ONLINE)
        if (state.qualityLabel.isNotEmpty()) {
            // APP-004 / DAT-002：数据来源明确标记，接口测试数据不得冒充真实在线
            TelemetryRow("数据源", state.qualityLabel, highlight = state.quality != "real")
        }
        if (state.controller != null) TelemetryRow("控制权", state.controller!!)
        if (state.alarms.isNotEmpty()) {
            state.alarms.forEach { alarm ->
                Text(
                    "⚠ $alarm",
                    color = WarnAmber,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(
            value,
            color = if (highlight) WarnAmber else Color.Unspecified,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 指令状态反馈列表 —— APP-010 */
@Composable
fun CommandStatusList(
    records: List<com.vrxfusion.remote.device.CommandRecord>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SurfaceCard, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (records.isEmpty()) {
            Text("暂无指令记录", color = TextSecondary, fontSize = 12.sp)
        } else {
            records.take(8).forEach { r ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "#${r.seq} ${r.name}",
                        color = Color.Unspecified,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    val (txt, color) = when (r.status) {
                        CommandStatus.SENT -> "已发送" to TextSecondary
                        CommandStatus.RECEIVED -> "已接收" to Color.Unspecified
                        CommandStatus.EXECUTING -> "执行中" to Color.Unspecified
                        CommandStatus.SUCCESS -> "成功" to VirtualGreen
                        CommandStatus.FAILED -> "失败" to RealRed
                        CommandStatus.CANCELLED -> "取消" to TextSecondary
                        CommandStatus.TIMEOUT -> "超时" to WarnAmber
                    }
                    Text(txt, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 在线状态圆点 */
@Composable
fun OnlineDot(online: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier
            .size(8.dp)
            .background(
                if (online) OnlineDot else OfflineDot,
                androidx.compose.foundation.shape.CircleShape
            )
    )
}
