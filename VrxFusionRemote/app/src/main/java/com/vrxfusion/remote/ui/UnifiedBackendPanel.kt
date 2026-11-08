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

/** 连接配置和设备目录已统一至管理页；此处只保留原命令回执及查询入口。 */
@Composable
fun CommandReceiptPanel(vm: MainViewModel) {
    val gateway=vm.flightGateway
    val info by gateway.repository.info.collectAsState()
    val feedback by gateway.commandFeedback.collectAsState()
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        HorizontalDivider()
        Text("最近命令回执",color=TextPrimary,fontSize=16.sp)
        if (feedback==null) Text("暂无命令回执",color=TextSecondary,fontSize=11.sp)
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
