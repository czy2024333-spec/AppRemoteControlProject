package com.vrxfusion.remote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.unified.*
import com.vrxfusion.remote.ui.theme.*
import kotlinx.coroutines.launch

/** 展示真实发现结果，不把来源Token当成用户角色，也不把能力发现当成自动接管。 */
@Composable
fun UnifiedBackendPanel(vm: MainViewModel) {
    val gateway=vm.flightGateway
    val info by gateway.repository.info.collectAsState()
    val directory by gateway.repository.directory.collectAsState()
    val error by gateway.repository.error.collectAsState()
    val feedback by gateway.commandFeedback.collectAsState()
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        HorizontalDivider()
        Text("统一后端与设备",color=TextPrimary,fontSize=16.sp)
        info?.let { i ->
            Text("后端 ${i.implementation_version} · 契约 ${i.contract_version} · 同一地址",color=AccentBlue,fontSize=12.sp)
            Text("本端飞机 ${i.controlDeviceId() ?: "无（只读观察端）"}",color=TextPrimary,fontSize=12.sp)
            Text("${i.authentication.client_id ?: "旧版端"} · 来源 ${i.authentication.inputSource() ?: "只读"} · 开发Token认证，非正式用户登录",color=WarnAmber,fontSize=11.sp)
            directory?.devices?.forEach { device ->
                Text("${device.name.ifBlank { device.device_id }} · ${if (device.reality=="SIMULATION") "仿真" else device.reality} · "+
                    if (device.device_id==i.controlDeviceId()) "本机 · 可主动申请控制" else "只读成员",color=TextPrimary,fontSize=12.sp)
            }
            gateway.primaryDevice()?.let { device ->
                Text("输入有效期 ${device.control_limits.input_ttl_ms}ms · 配置版本 ${device.control_config_version}",color=TextSecondary,fontSize=11.sp)
                Text("起飞/降落/返航、UE视频及实物硬件按能力开放；当前未接入项保持禁用。",color=TextSecondary,fontSize=11.sp)
            }
        } ?: Text("连接后确认绑定飞机；v0.9请运行后端 --mode independent",color=TextSecondary,fontSize=12.sp)
        error?.let { Text(it,color=WarnAmber,fontSize=11.sp) }
        feedback?.let { receipt ->
            Text(receipt.text(),color=TextPrimary,fontSize=12.sp)
            if (receipt.completionScope=="SERVICE_APPLIED") Text("完成范围：服务已应用，不代表飞行器已物理停稳",color=TextSecondary,fontSize=10.sp)
            OutlinedButton(onClick={ vm.rotorScope.launch { gateway.queryLastReceipt() } },enabled=info!=null) { Text("查询原命令结果") }
        }
    }
}

fun UnifiedDeviceState.qualityText(): String {
    val local=sensors["local_position_velocity"]
    val sensor=when {
        local?.value==null || local.validity=="NOT_CONNECTED" -> "本地测量未接入"
        local.validity!="VALID" -> "本地测量失效"
        local.source=="SIM_MEASUREMENT" -> "模拟测量"
        else -> "本地测量 ${local.source}"
    }
    val batteryLabel=if (battery.value==null || battery.validity!="VALID") "电量未接入" else "电量${battery.source}"
    val gnssLabel=if (gnss.value==null || gnss.validity!="VALID") "GNSS未接入" else "GNSS${gnss.source}"
    return "$sensor · $gnssLabel · $batteryLabel"
}
