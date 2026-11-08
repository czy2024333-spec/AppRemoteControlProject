package com.vrxfusion.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.unified.*
import com.vrxfusion.remote.ui.theme.*

/** 管理页复用生产连接，只读取服务确认的身份、目录和状态，不创建另一套演示设备。 */
@Composable
fun BackendManagementScreen(vm: MainViewModel, onBack: () -> Unit, returnLabel: String = "返回首页") {
    val repository = vm.flightGateway.repository
    val info by repository.info.collectAsState()
    val directory by repository.directory.collectAsState()
    val snapshot by repository.snapshot.collectAsState()
    val connected by repository.connected.collectAsState()
    val running by repository.running.collectAsState()
    val hz by repository.receiveHz.collectAsState()
    val error by repository.error.collectAsState()
    BackendManagementContent(info, directory, snapshot, connected, running, hz, error,
        memberFresh = { repository.fresh(it) }, onBack = onBack, returnLabel = returnLabel) {
        // 与操纵设置使用同一个连接入口。连接仅准备观察链路，申请控制仍由操纵页主动发起。
        ConnectionPanel(vm, running, vm::connectRotor, vm::pauseRotor)
    }
}

@Composable
fun BackendManagementContent(
    info: UnifiedCapabilities?, directory: UnifiedDirectory?, snapshot: UnifiedSnapshot?,
    connected: Boolean, running: Boolean, receiveHz: Double, error: String?,
    memberFresh: (UnifiedDeviceState) -> Boolean, onBack: () -> Unit, returnLabel: String = "返回首页",
    connectionContent: @Composable () -> Unit
) {
    val hasHistory = info != null || directory != null || snapshot != null
    val status = when {
        connected && directory != null -> "状态连接正常 · 仅观察"
        connected -> "状态在线 · 设备目录待确认"
        running && snapshot != null -> "状态连接中断 · 数据已过期"
        running -> "正在连接与校验"
        snapshot == null && error != null -> "连接失败"
        hasHistory -> "已断开 · 下方仅为历史信息"
        else -> "尚未连接后端"
    }
    Column(Modifier.fillMaxSize().background(BgDark).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("连接与设备管理", color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack, modifier = Modifier.testTag("management-back")) { Text(returnLabel) }
        }
        Text(status, color = if (connected && directory != null) VirtualGreen else WarnAmber,
            fontSize = 13.sp, modifier = Modifier.testTag("management-status"))
        Text("连接与操纵页共用；查看设备不等于接管。进入后台后需重新连接。",
            color = TextSecondary, fontSize = 11.sp)
        BoxWithConstraints(Modifier.weight(1f)) {
            // 横屏分成两个可独立滚动的区域，返回按钮和连接状态始终保留在顶部。
            if (maxWidth >= 650.dp && maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(.45f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ManagementSection("连接设置") { connectionContent() }
                    }
                    Column(Modifier.weight(.55f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ManagementResults(info, directory, snapshot, connected, running, receiveHz, error, memberFresh)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ManagementSection("连接设置") { connectionContent() }
                    ManagementResults(info, directory, snapshot, connected, running, receiveHz, error, memberFresh)
                }
            }
        }
    }
}

@Composable
private fun ManagementSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(SurfaceCard, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun ManagementResults(
    info: UnifiedCapabilities?, directory: UnifiedDirectory?, snapshot: UnifiedSnapshot?,
    connected: Boolean, running: Boolean, receiveHz: Double, error: String?,
    memberFresh: (UnifiedDeviceState) -> Boolean
) {
    error?.let { Text(it, color = WarnAmber, fontSize = 12.sp, modifier = Modifier.testTag("management-error")) }
    ManagementSection(if (connected && running) "后端确认身份" else "身份信息") {
        if (info == null) {
            Text("连接成功后显示实际身份；上方预设只用于填写凭据。", color = TextSecondary, fontSize = 12.sp)
        } else {
            if (!connected || !running) Text("最近确认的历史身份，不代表当前已认证。", color = WarnAmber, fontSize = 12.sp)
            val auth = info.authentication
            val role = when (auth.role) {
                "CONTROLLER" -> "设备操纵端"
                "OBSERVER" -> "观察端"
                else -> auth.role ?: "后端未提供角色"
            }
            Text("后端身份：$role", color = TextPrimary, fontSize = 13.sp)
            Text("客户端：${auth.client_id ?: auth.user_id ?: "未提供"}", color = TextSecondary, fontSize = 12.sp)
            Text("本端绑定：${info.controlDeviceId() ?: "无（只读观察）"}", color = TextPrimary, fontSize = 13.sp,
                modifier = Modifier.testTag("management-binding"))
            Text("输入来源：${auth.inputSource() ?: "无"}", color = TextSecondary, fontSize = 12.sp)
            Text(if (auth.formal_user_authentication) "用户认证状态由后端确认" else "开发Token认证，尚未接入正式用户登录。",
                color = WarnAmber, fontSize = 11.sp)
            Text("后端 ${info.implementation_version} · 契约 ${info.contract_version} · "+
                if (info.independent()) "逐机独立控制" else "统一服务模式", color = TextSecondary, fontSize = 11.sp)
        }
    }
    val members = snapshot?.members?.associateBy { it.device_id }.orEmpty()
    val current = connected && running
    val freshCount = directory?.devices?.count { d -> members[d.device_id]?.let {
        current && it.online && it.validTelemetry() && memberFresh(it)
    } == true } ?: 0
    Text("设备与能力", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    if (directory == null) {
        // 目录校验失败时不能用旧状态或本地演示设备补出可操作的目录。
        Text("尚未取得有效设备目录，请检查连接或等待重新校验。", color = TextSecondary, fontSize = 12.sp,
            modifier = Modifier.testTag("management-no-directory"))
    } else {
        Text("${directory.devices.size}台设备 · 实时状态 $freshCount/${directory.devices.size} · 接收 %.1f Hz".format(
            if (current && receiveHz.isFinite()) receiveHz else 0.0), color = TextSecondary, fontSize = 12.sp)
        if (!current) Text("以下目录及身份为历史记录；当前权限和状态需重新连接确认。", color = WarnAmber, fontSize = 11.sp)
        if (directory.devices.isEmpty()) Text("后端设备目录为空。", color = TextSecondary, fontSize = 12.sp)
        directory.devices.forEach { d -> key(d.device_id) {
            ManagementDevice(d, members[d.device_id], info, current, memberFresh)
        } }
    }
    Text("位置、速度和告警请在操纵页或三机总览查看；操纵需主动申请控制。",
        color = TextSecondary, fontSize = 11.sp)
}

@Composable
private fun ManagementDevice(d: UnifiedDevice, m: UnifiedDeviceState?, info: UnifiedCapabilities?,
    current: Boolean, memberFresh: (UnifiedDeviceState) -> Boolean) {
    val fresh = current && m != null && m.online && m.validTelemetry() && memberFresh(m)
    val own = d.device_id == info?.controlDeviceId()
    // 此处仅显示可申请资格；即使后端允许写入，也不能把它显示成“已接管”。
    val canRequest = fresh && own && d.permitsMotion() && info.authentication.inputSource() == "APP" &&
        (info.independent().not() || info.authentication.role == "CONTROLLER" && d.is_self &&
            d.write_permitted == true && d.device_id in info.writable_device_ids && m?.control?.write_permitted == true &&
            info.capabilities["device_bound_legacy_write"] == true)
    val status = when {
        !current -> "历史状态"
        m == null -> "尚未收到状态"
        !m.online -> "设备离线"
        !m.validTelemetry() -> "状态无效"
        !fresh -> "状态过期"
        else -> "在线 · 状态有效"
    }
    Column(Modifier.fillMaxWidth().background(SurfaceCard, RoundedCornerShape(16.dp)).padding(14.dp)
        .testTag("management-device-${d.device_id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(d.device_id, color = if (own) WarnAmber else TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (d.name.isNotBlank() && d.name != d.device_id) Text(d.name, color = TextSecondary, fontSize = 12.sp)
        Text(status, color = if (!fresh || m?.mode == "PROTECTED") WarnAmber else VirtualGreen, fontSize = 12.sp)
        val permission = when {
            !current -> "历史权限，需重新确认"
            canRequest -> "本端绑定 · 可到操纵页申请"
            own -> "本端绑定 · 当前仅观察"
            else -> "其他成员 · 仅观察"
        }
        Text(permission, color = TextSecondary, fontSize = 12.sp)
        Text(when (d.reality) { "SIMULATION" -> "仿真设备"; "REAL" -> "实机类型"; else -> "设备类型未确认" },
            color = TextSecondary, fontSize = 11.sp)
        Text(if (d.permitsMotion()) "目录能力：支持三维归一化操纵" else "目录能力：未开放完整三维操纵",
            color = TextSecondary, fontSize = 11.sp)
        Text("输入有效期 ${d.control_limits.input_ttl_ms}ms · 配置版本 ${d.control_config_version}",
            color = TextSecondary, fontSize = 11.sp)
    }
}
