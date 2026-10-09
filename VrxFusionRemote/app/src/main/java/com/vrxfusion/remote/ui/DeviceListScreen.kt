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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
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
import com.vrxfusion.remote.protocol.Role
import com.vrxfusion.remote.protocol.WsTransport
import com.vrxfusion.remote.ui.components.OnlineDot
import com.vrxfusion.remote.ui.theme.AccentBlue
import com.vrxfusion.remote.ui.theme.SurfaceCard
import com.vrxfusion.remote.ui.theme.TextSecondary
import com.vrxfusion.remote.ui.theme.WarnAmber

/**
 * 设备目录页 —— APP-002（登录/角色/连接状态）/ CLU-002（控制对象选择）。
 * 顶部为服务连接卡片：服务器地址、用户名、角色选择；连接后显示设备目录。
 * 预留接口设备（EXT-001/002）禁用选择并明确标注（EXT-004 / APP-004）。
 */
@Composable
fun DeviceListScreen(
    vm: MainViewModel,
    onSelect: (DeviceDescriptor) -> Unit
) {
    val descriptors by vm.descriptors.collectAsState()
    val states by vm.states.collectAsState()
    val conn by vm.connState.collectAsState()
    val role by vm.role.collectAsState()
    val name by vm.userName.collectAsState()
    val url by vm.serverUrl.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("设备目录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (conn) {
                        WsTransport.ConnState.CONNECTED -> "已连接 · ${role.label} · $name"
                        WsTransport.ConnState.CONNECTING -> "连接中…"
                        else -> "未连接"
                    },
                    fontSize = 12.sp,
                    color = if (conn == WsTransport.ConnState.CONNECTED) AccentBlue else TextSecondary
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // ---- 登录/连接卡片（APP-002）----
        item {
            Column(
                Modifier.fillMaxWidth().background(SurfaceCard, RoundedCornerShape(10.dp)).padding(14.dp)
            ) {
                Text("统一控制服务", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { vm.setServerUrl(it) },
                    label = { Text("服务地址 ws://<服务IP>:8765/ws") },
                    singleLine = true,
                    enabled = conn == WsTransport.ConnState.DISCONNECTED,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { vm.setUserName(it) },
                    label = { Text("用户名") },
                    singleLine = true,
                    enabled = conn == WsTransport.ConnState.DISCONNECTED,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Role.entries.forEach { r ->
                        FilterChip(
                            selected = role == r,
                            onClick = { vm.setRole(r) },
                            enabled = conn == WsTransport.ConnState.DISCONNECTED,
                            label = { Text(r.label) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { if (conn == WsTransport.ConnState.DISCONNECTED) vm.connect() else vm.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (conn == WsTransport.ConnState.DISCONNECTED)
                        ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    else ButtonDefaults.buttonColors()
                ) {
                    Text(
                        when (conn) {
                            WsTransport.ConnState.DISCONNECTED -> "连接服务"
                            WsTransport.ConnState.CONNECTING -> "连接中…"
                            else -> "断开连接"
                        }
                    )
                }
                if (role == Role.OBSERVER) {
                    Spacer(Modifier.height(6.dp))
                    Text("观察员为只读角色：可查看状态，不能控制（服务端强制拒绝）",
                        fontSize = 11.sp, color = WarnAmber)
                }
            }
        }

        // ---- 设备分组 ----
        val groups = listOf(
            "服务设备（经统一控制服务）" to descriptors.filter { !it.reserved && it.id != "uav-demo-local" },
            "预留接口（本期禁用）" to descriptors.filter { it.reserved },
            "本地演示（接口测试数据）" to descriptors.filter { it.id == "uav-demo-local" }
        )
        groups.forEach { (title, list) ->
            if (list.isNotEmpty()) {
                item {
                    Text(title, fontSize = 13.sp, color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                items(list, key = { it.id }) { d ->
                    DeviceCard(d, states[d.id] ?: DeviceState()) {
                        if (!d.reserved) onSelect(d)
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
            .clickable(enabled = !d.reserved) { onClick() }
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (d.kind == DeviceKind.QUADRUPED) "🐕" else "🚁", fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(d.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("${d.id} · ${d.model}", fontSize = 11.sp, color = TextSecondary)
                }
            }
            if (d.reserved) {
                Text("预留接口", fontSize = 11.sp, color = WarnAmber)
            }
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
                    when {
                        d.reserved -> "未接入（禁用）"
                        s.online -> if (s.isStale) "状态过期" else s.linkState.label
                        else -> "离线"
                    },
                    fontSize = 12.sp,
                    color = if (s.online && s.isStale) WarnAmber else TextSecondary
                )
                if (!d.reserved) {
                    Spacer(Modifier.width(12.dp))
                    Text("电量 ${s.batteryPercent}%", fontSize = 12.sp, color = TextSecondary)
                }
                if (s.qualityLabel.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Text(s.qualityLabel, fontSize = 11.sp, color = WarnAmber)
                }
            }
            if (!d.reserved) {
                Text(
                    if (s.controller != null) "控制权：${s.controller}" else "无人控制",
                    fontSize = 12.sp,
                    color = if (s.controller != null) AccentBlue else TextSecondary
                )
            }
        }
        if (d.reserved && d.reservedNote != null) {
            Spacer(Modifier.height(6.dp))
            Text(d.reservedNote, fontSize = 11.sp, color = TextSecondary)
        }
        if (s.alarms.isNotEmpty() && !d.reserved) {
            Spacer(Modifier.height(4.dp))
            Text("⚠ ${s.alarms.joinToString("、")}", fontSize = 12.sp, color = WarnAmber)
        }
    }
}
