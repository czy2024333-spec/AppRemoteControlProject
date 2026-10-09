package com.vrxfusion.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.control.ControlSession
import com.vrxfusion.remote.device.Capability
import com.vrxfusion.remote.device.DeviceKind
import com.vrxfusion.remote.device.DeviceRepository
import com.vrxfusion.remote.device.GaitPosture
import com.vrxfusion.remote.device.SimAction
import com.vrxfusion.remote.ui.components.CommandStatusList
import com.vrxfusion.remote.ui.components.Joystick
import com.vrxfusion.remote.ui.components.RealityBadge
import com.vrxfusion.remote.ui.components.TelemetryPanel
import com.vrxfusion.remote.ui.theme.AccentBlue
import com.vrxfusion.remote.ui.theme.EstopRed
import com.vrxfusion.remote.ui.theme.SurfaceCard
import com.vrxfusion.remote.ui.theme.TextSecondary
import com.vrxfusion.remote.ui.theme.WarnAmber

/**
 * 控制台页 —— 4.2 统一控制界面（APP-006~010）+ 运行模式（MODE-001~005）+ 安全（SAFE-002/004）。
 */
@Composable
fun ControlScreen(vm: MainViewModel, onBack: () -> Unit) {
    val device = vm.selected.collectAsState().value ?: return
    val state by vm.states.collectAsState()
    val authority by vm.sessionAuthority.collectAsState()
    val records by vm.records.collectAsState()
    val runMode by vm.runMode.collectAsState()
    val conn by vm.connState.collectAsState()
    val role by vm.role.collectAsState()
    val devState = state[device.id] ?: com.vrxfusion.remote.device.DeviceState()

    var showModeDialog by remember { mutableStateOf(false) }
    val hasAuthority = authority == ControlSession.Authority.GRANTED
    val canControl = role.canControl && !device.reserved

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ---- 顶部：设备身份 + 控制权 ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (device.kind == DeviceKind.QUADRUPED) "🐕" else "🚁", fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(device.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    RealityBadge(device.reality) // APP-004：显著标识控制对象
                }
                Text(
                    "${device.id} · ${device.model}" +
                        (device.boundTo?.let { " · 绑定 $it" } ?: ""),
                    fontSize = 11.sp, color = TextSecondary
                )
            }
            TextButton(onClick = onBack) { Text("返回目录") }
        }

        Spacer(Modifier.height(8.dp))

        // ---- 链路/角色/数据时效提示（APP-005：过期不得显示为实时）----
        if (conn != com.vrxfusion.remote.protocol.WsTransport.ConnState.CONNECTED &&
            device.id != "uav-demo-local"
        ) {
            Text(
                "⚠ 服务未连接：无法下发指令，状态显示不可用",
                fontSize = 12.sp, color = EstopRed,
                modifier = Modifier.fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(8.dp)).padding(10.dp)
            )
            Spacer(Modifier.height(8.dp))
        } else if (devState.online && devState.isStale) {
            Text(
                "⚠ 状态过期（超过 ${com.vrxfusion.remote.protocol.Protocol.STATE_STALE_MS}ms 未更新），非实时数据",
                fontSize = 12.sp, color = WarnAmber,
                modifier = Modifier.fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(8.dp)).padding(10.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
        if (!canControl) {
            Text(
                if (device.reserved) "预留接口设备：本期禁用控制（EXT-004）"
                else "观察员只读：可查看状态，不能控制（APP-002）",
                fontSize = 12.sp, color = WarnAmber,
                modifier = Modifier.fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(8.dp)).padding(10.dp)
            )
            Spacer(Modifier.height(8.dp))
        }

        // ---- 运行模式（MODE-005：切换须确认指令接收对象）----
        Row(
            Modifier.fillMaxWidth().background(SurfaceCard, RoundedCornerShape(8.dp)).padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("运行模式", fontSize = 12.sp, color = TextSecondary)
                Text(runMode.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = { showModeDialog = true }) { Text("切换") }
        }

        Spacer(Modifier.height(10.dp))

        // ---- 控制权 + 紧急停止（SAFE-001/002）----
        if (canControl) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { if (hasAuthority) vm.releaseAuthority() else vm.requestAuthority() },
                    modifier = Modifier.weight(1f),
                    colors = if (hasAuthority) ButtonDefaults.buttonColors()
                    else ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(if (hasAuthority) "释放控制权" else "申请控制权")
                }
                Button(
                    onClick = { vm.emergencyStop() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = EstopRed),
                    enabled = hasAuthority
                ) {
                    Text("紧急停止", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---- 左：遥测 ----
            TelemetryPanel(devState, modifier = Modifier.weight(1.3f))

            // ---- 右：虚拟摇杆（APP-003，仅可控角色显示）----
            if (canControl) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f)
                ) {
                    Joystick(
                        size = 150.dp,
                        deadZone = 0.08f,
                        onInput = { x, y -> vm.updateJoystick(vx = y, vy = x, wz = 0f) },
                        onRelease = { vm.updateJoystick(0f, 0f, 0f) }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "前进/后退·左右平移",
                        fontSize = 11.sp, color = TextSecondary
                    )
                    if (!hasAuthority) {
                        Spacer(Modifier.height(4.dp))
                        Text("未持有控制权", fontSize = 11.sp, color = EstopRed)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 能力驱动功能按钮（APP-006：按能力动态显示，仅可控角色可用）----
        val caps = device.capabilities
        if (canControl) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (Capability.GAIT in caps) {
                ButtonRow("姿态控制（EXT-002 可选能力编码）", listOf("站立" to GaitPosture.STAND_UP, "趴下" to GaitPosture.LIE_DOWN)) {
                    vm.sendGait(it)
                }
            }
            if (Capability.TAKEOFF_LAND in caps) {
                ButtonRow(
                    "飞行控制（SIM-002）",
                    listOf("起飞" to "takeoff", "降落" to "land", "悬停" to "hover", "返航" to "rtl")
                ) { key ->
                    when (key) {
                        "takeoff" -> vm.sendTakeoff()
                        "land" -> vm.sendLand()
                        "hover" -> vm.sendHover()
                        "rtl" -> vm.sendRtl()
                    }
                }
            }
            if (Capability.SIM_MANAGE in caps) {
                ButtonRow(
                    "仿真管理（SAFE-005：仅虚拟设备）",
                    listOf("复位" to SimAction.RESET, "暂停" to SimAction.PAUSE, "继续" to SimAction.RESUME)
                ) { vm.sendSimAction(it) }
            }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 指令状态反馈（APP-010）----
        Text("指令状态", fontSize = 13.sp, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        CommandStatusList(records)

        Spacer(Modifier.height(24.dp))
    }

    // ---- 模式切换确认对话框（MODE-005）----
    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("切换运行模式") },
            text = {
                Text(
                    "当前控制对象：${device.name}（${device.reality.label}）。\n" +
                        "模式切换后，状态来源如下（ARC-005），请确认：\n\n" +
                        "· 独立仿真：动力学驱动 UE 模型（本期）\n" +
                        "· 实机同步（预留）：实机状态驱动模型，与动力学驱动互斥\n" +
                        "· 回放模式（预留）：已记录状态驱动展示，不重算动力学\n\n" +
                        "切换时明确状态来源，并清除残留指令。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val next = when (runMode) {
                        DeviceRepository.RunMode.VIRTUAL_ONLY -> DeviceRepository.RunMode.REAL_ONLY
                        DeviceRepository.RunMode.REAL_ONLY -> DeviceRepository.RunMode.SYNC
                        DeviceRepository.RunMode.SYNC -> DeviceRepository.RunMode.VIRTUAL_ONLY
                    }
                    vm.switchRunMode(next)
                    showModeDialog = false
                }) { Text("确认切换") }
            },
            dismissButton = {
                TextButton(onClick = { showModeDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun <T> ButtonRow(
    title: String,
    buttons: List<Pair<String, T>>,
    onClick: (T) -> Unit
) {
    Column {
        Text(title, fontSize = 12.sp, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons.forEach { (label, value) ->
                OutlinedButton(onClick = { onClick(value) }) { Text(label) }
            }
        }
    }
}
