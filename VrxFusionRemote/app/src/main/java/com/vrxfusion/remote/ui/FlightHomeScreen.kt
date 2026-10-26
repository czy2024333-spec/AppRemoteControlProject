package com.vrxfusion.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.theme.*

/** 主入口按真实服务能力分开，避免把单机控制误连到集群端口。 */
@Composable
fun FlightHomeScreen(onSingle: ()->Unit,onCluster: ()->Unit,onLegacy: ()->Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(SurfaceDark,BgDark)))
        .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("ROTOR / FLIGHT CONSOLE",color=AccentBlue,fontSize=12.sp,letterSpacing=2.sp)
        Text("旋翼飞行控制台",fontSize=30.sp,fontWeight=FontWeight.Bold,color=TextPrimary)
        Text("三机独立控制台 · v0.9.0",color=TextSecondary)
        Text("连接电脑上的动力学后端，查看真实仿真状态。所有位置以米显示。",color=TextSecondary)
        HomeEntry("01  独立操纵","操纵端1 / 2 / 3 · 本机控制 · 全机位置",
            "对应Token绑定一架 · 另两架只观察 · 主动申请控制",onSingle)
        HomeEntry("02  三机只读总览","三机位置 · 模式 · 在线 / 告警 · 小组筛选",
            "与单机同一地址（只读，不接管）",onCluster)
        OutlinedButton(onClick=onLegacy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            Text("旧协议联调（高级）")
        }
        HorizontalDivider(color=TextSecondary.copy(alpha=.2f))
        Text("首次使用",fontWeight=FontWeight.SemiBold,color=TextPrimary)
        Text("1  v0.9后端启动 --mode independent\n2  手机与电脑连接同一局域网\n3  地址填 http://电脑IPv4:8765\n4  选择操纵端1、2、3或观察端预设\n5  确认本端绑定飞机，再主动申请控制\n三台手机各选不同操纵端，可同时控制各自飞机。",color=TextSecondary,lineHeight=26.sp)
        Text("UE画面尚待UE端提供，可先在单机设置中启用手机或电脑摄像头测试叠加效果。地图仍为遥测位置示意。",color=WarnAmber,fontSize=12.sp)
    }
}
@Composable
private fun HomeEntry(title: String,subtitle: String,note: String,onClick: ()->Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp,AccentBlue.copy(alpha=.25f),RoundedCornerShape(20.dp))
        .background(SurfaceCard,RoundedCornerShape(20.dp)).clickable(onClick=onClick).padding(22.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(title,color=TextPrimary,fontSize=22.sp,fontWeight=FontWeight.Bold)
        Text(subtitle,color=TextSecondary,fontSize=13.sp)
        Text(note,color=AccentBlue,fontSize=11.sp)
        Text("进入工作台  →",color=AccentBlue,fontWeight=FontWeight.SemiBold)
    }
}
