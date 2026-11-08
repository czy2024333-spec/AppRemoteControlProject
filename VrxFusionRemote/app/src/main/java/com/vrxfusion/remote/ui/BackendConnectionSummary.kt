package com.vrxfusion.remote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.ui.theme.*

/** 各工作页仅显示摘要并跳转，地址、Token与身份预设只在管理页编辑。 */
@Composable
fun BackendConnectionSummary(vm: MainViewModel, onManage: () -> Unit) {
    val r=vm.flightGateway.repository
    val info by r.info.collectAsState()
    val connected by r.connected.collectAsState()
    val running by r.running.collectAsState()
    val url by vm.rotorBaseUrl.collectAsState()
    Column(Modifier.fillMaxWidth().testTag("connection-summary"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(when { connected && running -> "状态连接正常"; running -> "连接中 / 状态待确认"; else -> "尚未连接 · 缓存仅供历史参考" },
            color=if (connected && running) VirtualGreen else WarnAmber,fontSize=12.sp)
        Text("配置地址：$url",color=TextSecondary,fontSize=11.sp)
        info?.let {
            Text((if (connected && running) "后端身份：" else "上次身份：")+
                (it.authentication.client_id ?: it.authentication.user_id ?: "未提供")+
                " · 绑定 "+(it.controlDeviceId() ?: "无（观察端）"),color=TextPrimary,fontSize=12.sp)
        }
        OutlinedButton(onClick=onManage,modifier=Modifier.testTag("manage-connection")) { Text("管理连接") }
        Text("进入管理会断开当前页面连接；返回操纵后需重新申请控制。",color=TextSecondary,fontSize=11.sp)
    }
}
