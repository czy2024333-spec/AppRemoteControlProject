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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.rotorpy.RotorState
import com.vrxfusion.remote.ui.theme.*
import kotlin.math.*

@Composable
fun ClusterMonitorScreen(vm: MainViewModel,onBack: ()->Unit) {
    val c=vm.clusterClient
    val state by c.state.collectAsState()
    val connected by c.connected.collectAsState()
    val running by c.running.collectAsState()
    val hz by c.receiveHz.collectAsState()
    val error by c.error.collectAsState()
    var group by remember { mutableStateOf<String?>(null) }
    val shown=state?.members.orEmpty().filter { group == null || it.device_id in state?.groups?.get(group).orEmpty() }
    Column(Modifier.fillMaxSize().background(BgDark).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("集群监控",fontSize=24.sp,fontWeight=FontWeight.Bold,color=TextPrimary)
            TextButton(onClick=onBack) { Text("返回") }
        }
        Text("只读观察 · 不创建控制会话，不影响正在操纵的客户端",color=AccentBlue,fontSize=12.sp)
        ConnectionPanel(vm,running,vm::connectCluster,c::stop)
        error?.let { Text(it,color=WarnAmber) }
        Text(if (connected) "● 实时仿真  %.1f Hz".format(hz) else "● 未连接 / 数据失效",color=if (connected) VirtualGreen else WarnAmber)
        state?.let { s ->
            Text("${s.cluster_id} · 后端${s.implementation_version}",color=TextSecondary,fontSize=12.sp)
            Text("在线 ${s.online_count}/${s.members.size}    已选 ${s.selected_count}    执行输入 ${s.active_count}",color=TextPrimary)
            Text("控制对象：${s.selection?.toString() ?: "尚未选择"}",color=TextSecondary,fontSize=11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=group == null,onClick={ group=null },label={ Text("全部成员") })
                s.groups.keys.forEach { name -> FilterChip(selected=group == name,onClick={ group=name },label={ Text(name) }) }
            }
            FleetMap(shown,connected)
            shown.forEachIndexed { i,m ->
                Column(Modifier.fillMaxWidth().background(SurfaceCard,RoundedCornerShape(16.dp)).padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("${i+1}  ${m.device_id}",color=TextPrimary,fontSize=18.sp,fontWeight=FontWeight.Bold)
                    Text(if (!connected) "历史状态，等待实时数据" else if (!m.online || m.state_age_ms > 500) "成员失联 / 状态过期" else "在线 · ${modeLabel(m.mode)}",
                        color=if (connected && m.online && m.state_age_ms <= 500) VirtualGreen else WarnAmber)
                    Text("X %.2f    Y %.2f    Z %.2f m".format(m.position_m.getOrElse(0){0.0},m.position_m.getOrElse(1){0.0},m.position_m.getOrElse(2){0.0}),color=TextPrimary)
                    Text("速度 %.2f m/s · 来源 ${m.active_source ?: "无持续输入"}".format(m.speed()),color=TextSecondary,fontSize=12.sp)
                    m.protection_reason?.let { Text("保护原因：$it",color=WarnAmber,fontSize=12.sp) }
                }
            }
        }
        Text("小组筛选只影响显示。多机运动控制仍需接入集群选择、逐成员校验和应答汇总流程。",color=TextSecondary,fontSize=12.sp)
    }
}
fun modeLabel(mode: String)=when(mode) { "HOLD"->"自动悬停"; "ACTIVE"->"运动输入生效"; "PROTECTED"->"保护锁存"; "WAITING_NEUTRAL"->"等待归中"; else->mode }
@Composable
private fun FleetMap(members: List<RotorState>,fresh: Boolean) {
    val colors=listOf(AccentBlue,VirtualGreen,BoundPurple)
    Column(Modifier.fillMaxWidth().background(SurfaceDark,RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text("XY位置示意 · 上为+X，左为+Y · 单位m",color=TextSecondary,fontSize=12.sp)
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val extent=max(3.0,members.flatMap { it.position_m.take(2) }.maxOfOrNull { abs(it) }?.plus(1) ?: 3.0)
            val scale=(size.minDimension/2-16)/extent.toFloat()
            drawLine(TextSecondary.copy(alpha=.3f),Offset(center.x,0f),Offset(center.x,size.height))
            drawLine(TextSecondary.copy(alpha=.3f),Offset(0f,center.y),Offset(size.width,center.y))
            members.forEachIndexed { i,m ->
                val p=Offset(center.x-m.position_m.getOrElse(1){0.0}.toFloat()*scale,center.y-m.position_m.getOrElse(0){0.0}.toFloat()*scale)
                val color=if (fresh && m.online) colors[i%3] else Color.Gray
                drawCircle(color,8.dp.toPx(),p)
                drawLine(color,p,p+Offset(-sin(m.yawRad()).toFloat()*22,-cos(m.yawRad()).toFloat()*22),3.dp.toPx())
            }
        }
        members.forEachIndexed { i,m -> Text("● ${i+1} ${m.device_id}",color=colors[i%3],fontSize=11.sp) }
    }
}
