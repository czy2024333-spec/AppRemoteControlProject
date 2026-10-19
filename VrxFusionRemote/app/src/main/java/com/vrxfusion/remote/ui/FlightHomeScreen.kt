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
        Text("仿真联调工作台 · v0.7.8",color=TextSecondary)
        Text("连接电脑上的动力学后端，查看真实仿真状态。所有位置以米显示。",color=TextSecondary)
        HomeEntry("01  单机操纵","双摇杆 · 三维遥测 · 保护与安全恢复",
            "后端启动参数：--mode single",onSingle)
        HomeEntry("02  三机监控","成员状态 · 小组筛选 · XY位置总览",
            "后端启动参数：--mode cluster（只读，不接管）",onCluster)
        OutlinedButton(onClick=onLegacy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            Text("设备目录 / WebSocket 服务")
        }
        HorizontalDivider(color=TextSecondary.copy(alpha=.2f))
        Text("首次使用",fontWeight=FontWeight.SemiBold,color=TextPrimary)
        Text("1  手机与电脑连接同一局域网\n2  后端地址填写电脑的IPv4，端口8765\n3  Token填写 demo-app-token-2026\n4  先观察状态，再申请APP控制",color=TextSecondary,lineHeight=26.sp)
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
