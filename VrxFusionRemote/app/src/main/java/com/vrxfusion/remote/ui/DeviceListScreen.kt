package com.vrxfusion.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.device.DeviceDescriptor
import com.vrxfusion.remote.device.DeviceKind
import com.vrxfusion.remote.device.DeviceState
import com.vrxfusion.remote.device.RealityType
import com.vrxfusion.remote.ui.components.OnlineDot
import com.vrxfusion.remote.ui.components.RealityBadge
import com.vrxfusion.remote.ui.theme.SurfaceCard
import com.vrxfusion.remote.ui.theme.TextSecondary

/**
 * 设备列表页 —— APP-002 / APP-003：
 * 分类显示（真实/虚拟/虚实绑定）、在线状态、电量、控制状态、告警。
 */
@Composable
fun DeviceListScreen(
    vm: MainViewModel,
    onSelect: (DeviceDescriptor) -> Unit
) {
    val descriptors by vm.descriptors.collectAsState()
    val states by vm.states.collectAsState()
    val mqtt by vm.mqttState.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("设备目录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                when (mqtt) {
                    com.vrxfusion.remote.protocol.MqttTransport.ConnState.CONNECTED -> "控制服务：已连接"
                    com.vrxfusion.remote.protocol.MqttTransport.ConnState.CONNECTING -> "控制服务：连接中…"
                    else -> "控制服务：未连接（本地演示）"
                },
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
        Spacer(Modifier.height(12.dp))

        val groups = listOf(
            RealityType.VIRTUAL to "虚拟设备",
            RealityType.REAL to "真实设备",
            RealityType.BOUND to "虚实绑定设备"
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { (reality, title) ->
                val list = descriptors.filter { it.reality == reality }
                if (list.isNotEmpty()) {
                    item {
                        Text(
                            title,
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(list, key = { it.id }) { d ->
                        DeviceCard(d, states[d.id] ?: DeviceState()) { onSelect(d) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(d: DeviceDescriptor, s: DeviceState, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (d.kind == DeviceKind.QUADRUPED) "🐕" else "🚁",
                    fontSize = 20.sp
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(d.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${d.id} · ${d.model}",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
            RealityBadge(d.reality)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OnlineDot(s.online)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (s.online) s.linkState.label else "离线",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.width(12.dp))
                Text("电量 ${s.batteryPercent}%", fontSize = 12.sp, color = TextSecondary)
            }
            Text(
                if (s.controller != null) "控制权：${s.controller}" else "无人控制",
                fontSize = 12.sp,
                color = if (s.controller != null) com.vrxfusion.remote.ui.theme.AccentBlue else TextSecondary
            )
        }
        if (s.alarms.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "⚠ ${s.alarms.joinToString("、")}",
                fontSize = 12.sp,
                color = com.vrxfusion.remote.ui.theme.WarnAmber
            )
        }
    }
}
