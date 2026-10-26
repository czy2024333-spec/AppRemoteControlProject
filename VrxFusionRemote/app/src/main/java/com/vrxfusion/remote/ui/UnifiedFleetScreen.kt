package com.vrxfusion.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.unified.*
import com.vrxfusion.remote.ui.theme.*
import kotlin.math.*

/** 设备目录与集群共用v1状态仓库；筛选永远不发select或接管请求。 */
@Composable
fun ClusterMonitorScreen(vm: MainViewModel,onBack: ()->Unit) {
    val c=vm.flightGateway.repository
    val state by c.snapshot.collectAsState()
    val directory by c.directory.collectAsState()
    val connected by c.connected.collectAsState()
    val running by c.running.collectAsState()
    val hz by c.receiveHz.collectAsState()
    val error by c.error.collectAsState()
    Column(Modifier.fillMaxSize().background(BgDark).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("设备与集群",fontSize=24.sp,fontWeight=FontWeight.Bold,color=TextPrimary)
            TextButton(onClick=onBack) { Text("返回") }
        }
        Text("统一地址 · 只读观察 · 筛选不接管设备",color=AccentBlue,fontSize=12.sp)
        ConnectionPanel(vm,running,vm::connectCluster,vm::pauseRotor)
        error?.let { Text(it,color=WarnAmber) }
        Text(if (connected) "● 统一状态  %.1f Hz".format(hz) else "● 未连接 / 历史状态",color=if (connected) VirtualGreen else WarnAmber)
        state?.let { UnifiedFleetContent(it,directory,connected) { m->c.fresh(m) } }
    }
}

@Composable
fun UnifiedFleetContent(state: UnifiedSnapshot,directory: UnifiedDirectory?,connected: Boolean,
    memberFresh: (UnifiedDeviceState)->Boolean) {
    var group by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.groups.keys) { if (group!=null && group !in state.groups) group=null }
    val shown=state.members.filter { group==null || it.device_id in state.groups[group].orEmpty() }
    val realtime=state.members.count { connected && memberFresh(it) }
    Text("${state.cluster_id} · 后端${state.implementation_version} · 契约${state.contract_version}",color=TextSecondary,fontSize=12.sp)
    Text("实时 $realtime/${state.members.size} · 服务在线 ${state.summary["online_count"] ?: 0} · 传感器启用 ${state.summary["sensor_enabled_count"] ?: 0}",color=TextPrimary,fontSize=12.sp)
    Text(if (state.deployment_mode=="INDEPENDENT") "三机独立 · "+(state.self_device_id?.let { "本端绑定 $it（此页仅观察）" } ?: "观察端，可查看全部飞机")
        else "控制对象："+(state.selection?.let { "${it.kind} · ${it.group_id ?: it.device_id ?: "集群"} · ${it.member_ids.size}成员" } ?: "尚未选择"),color=TextSecondary,fontSize=11.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(selected=group==null,onClick={group=null},label={Text("全部成员")})
        state.groups.keys.forEach { name->FilterChip(selected=group==name,onClick={group=name},label={Text(name)}) }
    }
    FleetMap(shown,state.self_device_id) { connected && memberFresh(it) }
    shown.forEach { m ->
        val fresh=connected && memberFresh(m)
        val descriptor=directory?.devices?.find { it.device_id==m.device_id }
        Column(Modifier.fillMaxWidth().background(SurfaceCard,RoundedCornerShape(16.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(descriptor?.name?.ifBlank { m.device_id } ?: m.device_id,color=TextPrimary,fontSize=18.sp,fontWeight=FontWeight.Bold)
            val source=when(m.position.source) { "SIM_TRUTH"->"仿真真值";"SIM_MEASUREMENT"->"模拟测量";"REAL"->"实机数据";else->"来源未确认" }
            Text(if (fresh) "$source · ${modeLabel(m.mode)}" else "状态过期或无效 · 仅供历史参考",color=if (fresh) VirtualGreen else WarnAmber,fontSize=12.sp)
            Text(positionText(m,fresh),color=TextPrimary,fontSize=12.sp)
            val speed=m.velocity.value?.values()?.sumOf { it*it }?.let(::sqrt)
            Text(if (fresh && speed!=null) "速度 %.2f m/s · 来源 ${m.control.selected_source ?: "未选择"}".format(speed) else "速度 — · 控制权以本客户端握手为准",color=TextSecondary,fontSize=11.sp)
            Text(m.qualityText(),color=TextSecondary,fontSize=11.sp)
            Text("任务 ${if (m.task.status=="NOT_SUPPORTED") "未接入" else m.task.status} · UE ${if (m.video.status=="NOT_CONNECTED") "未接入" else m.video.status}",color=TextSecondary,fontSize=11.sp)
            m.alarms.forEach { Text("告警：${it.code}",color=WarnAmber,fontSize=11.sp) }
        }
    }
    Text("此页不申请控制、不广播运动；操纵页使用对应端凭据独立控制本机。",color=TextSecondary,fontSize=12.sp)
}

fun positionText(m: UnifiedDeviceState,fresh: Boolean)=if (!fresh || !m.position.valid("m")) "X —  Y —  Z — m" else
    m.position.value!!.let { "X %.2f  Y %.2f  Z %.2f m".format(it.x,it.y,it.z) }
fun modeLabel(mode: String)=when(mode) { "HOLD"->"悬停";"ACTIVE"->"运动输入生效";"PROTECTED"->"保护锁存";else->mode }

@Composable
private fun FleetMap(members: List<UnifiedDeviceState>,selfId: String?,fresh: (UnifiedDeviceState)->Boolean) {
    val markers=fleetMarkers(members,selfId,fresh)
    Canvas(Modifier.fillMaxWidth().height(190.dp).background(SurfaceDark,RoundedCornerShape(12.dp))) {
        val extent=max(3.0,markers.flatMap { listOf(it.position.x,it.position.y) }.maxOfOrNull { abs(it) }?.plus(1) ?: 3.0)
        val scale=(size.minDimension/2-16)/extent.toFloat()
        drawLine(TextSecondary.copy(alpha=.3f),Offset(center.x,0f),Offset(center.x,size.height))
        drawLine(TextSecondary.copy(alpha=.3f),Offset(0f,center.y),Offset(size.width,center.y))
        markers.forEach { marker ->
            val value=marker.position
            val p=Offset(center.x-value.y.toFloat()*scale,center.y-value.x.toFloat()*scale)
            drawFleetMarker(marker,p)
        }
    }
    // 地图下先紧凑汇总全部成员，观察员无需逐张翻详情卡也能对照三机位置和状态。
    Column(Modifier.fillMaxWidth().background(SurfaceCard,RoundedCornerShape(12.dp))
        .padding(10.dp).testTag("fleet-quick-overview"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        members.forEach { m ->
            val live=fresh(m)
            val marker=markers.find {it.member.device_id==m.device_id}
            Column(Modifier.fillMaxWidth().testTag("fleet-overview-${m.device_id}")) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("● "+m.device_id+(if (m.device_id==selfId) " · 本机" else ""),
                        color=marker?.color ?: Color.Gray,fontSize=11.sp)
                    Text(if (live) "实时 · ${modeLabel(m.mode)}" else "状态过期",
                        color=if (!live || m.mode=="PROTECTED" || m.alarms.isNotEmpty()) WarnAmber else VirtualGreen,fontSize=11.sp)
                }
                Text("坐标 · "+positionText(m,live),color=if (live) TextPrimary else TextSecondary,fontSize=11.sp)
            }
        }
    }
    Text("XY位置：上+X、左+Y · 米；无效坐标不绘制",color=TextSecondary,fontSize=10.sp)
}
