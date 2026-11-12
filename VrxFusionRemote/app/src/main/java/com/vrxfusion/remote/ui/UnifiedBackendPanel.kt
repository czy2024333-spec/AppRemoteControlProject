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
import kotlinx.serialization.json.JsonNull

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
    val sensor = local.qualityLabel("本地测量", simulated = "模拟测量")
    return "$sensor · ${gnss.qualityLabel("GNSS")} · ${battery.qualityLabel("电量")}"
}

private val unspecifiedMeasurement = OptionalMeasurement()

/** 缺字段和默认测量反序列化结果相同，只展示能够从现有数据确认的状态。 */
private fun OptionalMeasurement?.qualityLabel(name: String, simulated: String = "${name}模拟"): String = when {
    this == null || this == unspecifiedMeasurement -> "${name}未知"
    validity == "NOT_CONNECTED" -> "${name}未接入"
    validity == "STALE" || validity == "EXPIRED" -> "${name}过期"
    validity == "INVALID" -> "${name}失效"
    validity != "VALID" || value == null || value == JsonNull -> "${name}未知"
    source.startsWith("SIM_") -> simulated
    source == "UNKNOWN" || source.isBlank() -> "${name}来源未知"
    else -> "$name $source"
}
