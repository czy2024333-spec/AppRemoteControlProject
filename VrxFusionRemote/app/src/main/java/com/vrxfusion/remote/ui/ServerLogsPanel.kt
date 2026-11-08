package com.vrxfusion.remote.ui

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.logs.*
import com.vrxfusion.remote.ui.theme.*
import kotlinx.serialization.json.*

/** 先下载至有界临时文件并校验，再打开保存选择器；失败不会留一个“成功”的空文件。 */
@Composable
fun ServerLogsPanel(vm: MainViewModel) {
    val store=vm.serverLogs
    val state by store.view.collectAsState()
    val info by vm.flightGateway.repository.info.collectAsState()
    val directory by vm.flightGateway.repository.directory.collectAsState()
    val connected by vm.flightGateway.repository.connected.collectAsState()
    val running by vm.flightGateway.repository.running.collectAsState()
    val context=LocalContext.current
    val motionBlocked=LocalMotionInputBlocked.current
    val owner=LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,_->foreground=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri==null) store.cancelSave() else store.save(
            open={ context.contentResolver.openOutputStream(uri,"wt") },
            onFailed={ runCatching { DocumentsContract.deleteDocument(context.contentResolver,uri) }; Unit })
    }
    LaunchedEffect(state.download?.file?.absolutePath,state.pickerStarted,foreground,motionBlocked) {
        val ready=state.download
        // 正文退出/进入动画期间不能从尚未激活的日志页弹出保存选择器。
        if (foreground && !motionBlocked && ready!=null && !state.pickerStarted) {
            store.pickerStarted()
            saver.launch("fleet-logs-${System.currentTimeMillis()}.${ready.format}")
        }
    }
    var device by rememberSaveable { mutableStateOf("") }
    var event by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableIntStateOf(0) }
    var appliedMinutes by rememberSaveable { mutableIntStateOf(0) }
    val supported=info?.capabilities?.get("logs_query")==true
    val available=supported && connected && running
    val idle=!state.busy && state.download==null
    val applied=state.filter
    val draftMatches=applied!=null && applied.device==device && applied.event==event.trim() && minutes==appliedMinutes
    Column(Modifier.fillMaxWidth().testTag("server-logs-panel"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("服务端审计日志",color=TextPrimary,fontSize=16.sp)
        Text("历史记录 · 索引可能延迟 · 日志错误独立于飞行控制权",color=TextSecondary,fontSize=11.sp)
        if (!supported) Text("当前后端未声明日志能力，请连接GitHub最新日志后端。",color=WarnAmber,fontSize=11.sp)
        if (supported) {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FilterChip(selected=device.isEmpty(),onClick={device=""},enabled=idle,label={Text("全部设备")})
                directory?.devices?.forEach { d -> FilterChip(selected=device==d.device_id,onClick={device=d.device_id},
                    enabled=idle,label={Text(d.device_id)}) }
            }
            OutlinedTextField(value=event,onValueChange={event=it.take(128)},enabled=idle,singleLine=true,
                label={Text("事件编码（留空为全部）")},modifier=Modifier.fillMaxWidth().testTag("logs-event"))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(0 to "全部历史",15 to "最近15分钟",60 to "最近1小时").forEach { (n,label) ->
                    FilterChip(selected=minutes==n,onClick={minutes=n},enabled=idle,label={Text(label)})
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={
                    val now=System.currentTimeMillis(); appliedMinutes=minutes
                    vm.serverLogConnection()?.let { store.query(it,LogFilter(device,event.trim(),
                        if (minutes==0) null else now-minutes*60000L,if (minutes==0) null else now)) }
                },enabled=available && idle,modifier=Modifier.weight(1f).testTag("logs-query")) { Text("查询 / 刷新") }
                OutlinedButton(onClick={ vm.serverLogConnection()?.let { store.query(it,applied!!,true) } },
                    enabled=available && idle && draftMatches && state.page?.nextOffset!=null,
                    modifier=Modifier.weight(1f).testTag("logs-next")) { Text("下一页") }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("jsonl","csv").forEach { format ->
                    OutlinedButton(onClick={vm.serverLogConnection()?.let { store.export(it,format) }},
                        enabled=available && idle && draftMatches && info?.capabilities?.get("logs_export")==true &&
                            (state.page?.total ?: 0)>0,
                        modifier=Modifier.weight(1f).testTag("logs-export-$format")) { Text("导出${format.uppercase()}") }
                }
            }
            if (state.busy) TextButton(onClick=store::clear) { Text("取消日志操作") }
            if (!draftMatches && state.page!=null) Text("筛选已修改，请重新查询后翻页或导出。",color=WarnAmber,fontSize=11.sp)
            Text(state.message,color=WarnAmber,fontSize=11.sp,modifier=Modifier.testTag("logs-message"))
            state.page?.events?.forEach { row ->
                Text(listOf("event","device_id","server_time_ms","command_id").mapNotNull { key ->
                    (row[key] as? JsonPrimitive)?.contentOrNull?.let { "$key: $it" }
                }.joinToString(" · ").take(600),color=TextSecondary,fontSize=11.sp)
            }
            Text("导出沿用当前筛选和查询快照，最大16MiB。选择保存位置会暂停操纵，返回后需手动重连。",
                color=TextSecondary,fontSize=10.sp)
        }
    }
}
