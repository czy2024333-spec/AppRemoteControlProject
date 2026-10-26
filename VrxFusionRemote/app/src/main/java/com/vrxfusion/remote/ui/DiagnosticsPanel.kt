package com.vrxfusion.remote.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 诊断入口放在原设置页，不新增应用窗口；记录只观察，不自动驾驶。 */
@Composable
fun DiagnosticsPanel(vm: MainViewModel) {
    val diagnostics=vm.diagnostics
    val state by diagnostics.view.collectAsState()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var conditions by remember { mutableStateOf(diagnostics.conditions) }
    var exportMessage by remember { mutableStateOf("") }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri!=null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { diagnostics.exportTo(it) } ?: error("无法写入")
            } }.onSuccess { exportMessage="运行记录已导出；运行中的导出仅为当前快照" }
                .onFailure { exportMessage="暂无记录或导出失败" }
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("性能与持续运行记录",color=TextPrimary,fontSize=16.sp)
        Text("接收P95 ${state.p95Ms?.let { "≤%.0f ms".format(it) } ?: "—"} · 成功 ${state.samples} · 失败 ${state.failed}",color=TextPrimary,fontSize=12.sp)
        Text("状态接收 %.1f Hz · APP状态提交 %.1f Hz".format(state.receiveHz,state.renderHz),color=TextPrimary,fontSize=12.sp)
        Text("累计 ${state.elapsedS/60} 分钟 · 前台 ${state.foregroundS/60} 分钟 · 后台次数 ${state.interruptions}",color=TextSecondary,fontSize=11.sp)
        Text("主线程队列最大延迟 ${state.maxMainDelayMs} ms · 丢失记录 ${state.lostRecords}",color=TextSecondary,fontSize=11.sp)
        OutlinedTextField(value=conditions,onValueChange={ conditions=it.take(300); diagnostics.conditions=conditions },
            enabled=!state.active,label={ Text("约定测试条件：手机、局域网及负载") },modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={ if (state.active) vm.rotorScope.launch { diagnostics.finish() } else vm.startDiagnostics() },
                modifier=Modifier.weight(1f)) { Text(if (state.active) "结束记录" else "开始记录") }
            OutlinedButton(onClick={ exporter.launch("flight-diagnostics.jsonl") },modifier=Modifier.weight(1f)) { Text("导出记录") }
        }
        Text(state.note,color=WarnAmber,fontSize=11.sp)
        Text("P95包含运动指令等待发送锁的时间，按1ms上取整；仅成功回执统计，失败另计。APP提交频率与接收频率分别计算，非屏幕GPU呈现帧率。语音识别、模型执行及视频时延待各端独立计时。",color=TextSecondary,fontSize=10.sp)
        if (exportMessage.isNotEmpty()) Text(exportMessage,color=AccentBlue,fontSize=11.sp)
    }
}
